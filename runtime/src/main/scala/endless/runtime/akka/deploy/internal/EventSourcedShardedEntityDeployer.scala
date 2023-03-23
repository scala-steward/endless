package endless.runtime.akka.deploy.internal

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ActorSystem, Behavior}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted, RecoveryFailed}
import akka.util.Timeout
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.show._
import cats.tagless.FunctorK
import endless.core.entity.EntityNameProvider
import endless.core.event.EventApplier
import endless.core.interpret.EffectorT._
import endless.core.interpret.{EffectorT, EntityT, RepositoryT}
import endless.core.protocol.{CommandProtocol, EntityIDCodec}
import endless.runtime.akka.EntityPassivator
import endless.runtime.akka.data.{Command, Reply}
import endless.runtime.akka.deploy.Deployer.EffectorParameters
import endless.runtime.akka.deploy.internal.EventSourcedShardedEntityDeployer._
import org.typelevel.log4cats.Logger

private[deploy] class EventSourcedShardedEntityDeployer[F[
    _
]: Async: Logger, S, E, ID: EntityIDCodec, Alg[
    _[_]
]: FunctorK, RepositoryAlg[_[_]]](
    interpretedEntityAlg: Alg[EntityT[F, S, E, *]],
    repository: RepositoryAlg[F],
    repositoryT: RepositoryT[F, ID, Alg],
    createEffector: EffectorParameters[F, S, Alg, RepositoryAlg] => F[EffectorT[F, S, Alg, Unit]],
    customizeBehavior: (
        EntityContext[Command],
        EventSourcedBehavior[Command, E, Option[S]]
    ) => Behavior[Command]
)(implicit
    val nameProvider: EntityNameProvider[ID],
    commandProtocol: CommandProtocol[Alg],
    eventApplier: EventApplier[S, E]
) extends ShardingDeployer[F, ID] {

  protected override def createBehavior()(implicit
      dispatcher: Dispatcher[F],
      actor: ActorContext[Command],
      context: EntityContext[Command]
  ): Behavior[Command] = {
    implicit val passivator: EntityPassivator[F] = dispatcher.unsafeRunSync(EntityPassivator[F])
    implicit val entity: Alg[F] =
      repositoryT.entityFor(implicitly[EntityIDCodec[ID]].decode(context.entityId))
    implicit val effector: EffectorT[F, S, Alg, Unit] =
      dispatcher.unsafeRunSync(createEffector(EffectorT.instance, repository, entity))
    customizeBehavior(
      context,
      EventSourcedBehavior
        .withEnforcedReplies[Command, E, Option[S]](
          PersistenceId(entityTypeKey.name, context.entityId),
          Option.empty[S],
          commandHandler = handleCommand,
          eventHandler = handleEvent
        )
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            dispatcher.unsafeRunAndForget(
              Logger[F].info(
                show"Recovery of ${nameProvider()} entity ${context.entityId} completed"
              ) >> effector.runS(state, entity).flatMap(passivator.apply)
            )
          case (_, RecoveryFailed(failure)) =>
            dispatcher.unsafeRunSync(
              Logger[F].warn(
                show"Recovery of ${nameProvider()} entity ${context.entityId} failed with error ${failure.getMessage}"
              )
            )
        }
    )
  }

  private def handleEvent(state: Option[S], event: E)(implicit
      entity: Alg[F],
      dispatcher: Dispatcher[F],
      passivator: EntityPassivator[F],
      effector: EffectorT[F, S, Alg, Unit]
  ) = eventApplier.apply(state, event) match {
    case Left(error) =>
      dispatcher.unsafeRunSync(Logger[F].warn(error))
      throw new EventApplierException(error)
    case Right(newState) => newState
  }

  private def handleCommand(state: Option[S], command: Command)(implicit
      entity: Alg[F],
      dispatcher: Dispatcher[F],
      passivator: EntityPassivator[F],
      effector: EffectorT[F, S, Alg, Unit]
  ) = {
    val incomingCommand =
      commandProtocol.server[EntityT[F, S, E, *]].decode(command.payload)
    val effect = Logger[F].debug(
      show"Handling command for ${nameProvider()} entity ${command.id}"
    ) >> incomingCommand
      .runWith(interpretedEntityAlg)
      .run(state)
      .flatMap {
        case Left(error) =>
          Logger[F].warn(error) >> Effect.unhandled[E, Option[S]].thenNoReply().pure[F]
        case Right((events, reply)) if events.nonEmpty =>
          Effect
            .persist(events.toList)
            .thenRun((state: Option[S]) =>
              // run the effector asynchronously, as it can describe long-running processes
              dispatcher.unsafeRunAndForget(effector.runS(state, entity).flatMap(passivator.apply))
            )
            .thenReply(command.replyTo) { _: Option[S] =>
              Reply(incomingCommand.replyEncoder.encode(reply))
            }
            .pure[F]
        case Right((_, reply)) =>
          Effect
            .reply[Reply, E, Option[S]](command.replyTo)(
              Reply(incomingCommand.replyEncoder.encode(reply))
            )
            .pure[F]
      }
    dispatcher.unsafeRunSync(effect)
  }
}

private[deploy] object EventSourcedShardedEntityDeployer {
  final class EventApplierException(error: String) extends RuntimeException(error)
}
