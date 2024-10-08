# Side-effect

```scala
trait SideEffect[F[_], S, Alg[_[_]]] {
  def apply(trigger: SideEffect.Trigger, effector: Effector[F, S, Alg]): F[Unit]

  def runModeFor(trigger: SideEffect.Trigger, state: Option[S]): F[SideEffect.RunMode] 
}
```

@scaladoc[SideEffect](endless.core.entity.SideEffect) is parametrized with the following type parameters:

 - `F[_]`: abstract effectful context `F` encapsulating all values, e.g. `IO[*]`
 - `Alg[_[_]]`: entity algebra, allowing "back-interaction" with the entity itself (e.g. for at least once process definition, see note in @ref:[Effector](effector.md))
 - `S`: entity state, e.g. @github[Booking](/example/src/main/scala/endless/example/data/Booking.scala)

It represents a side-effect that is triggered according to `trigger` either after event persistence, command handling (for a read-only behavior invocation) or recovery. Side-effects are typically asynchronous operations such as kafka writes, outgoing REST requests, and [entity passivation](https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#passivation) (flushing out of memory).  

In the runtime, the resulting `F[Unit]` is interpreted according to the `RunMode` setting indicated by a preliminary call to `runModeFor`. With `Async` mode, it triggers in *run & forget* mode so that command reply is not delayed by any lengthy side-effect. With `Async` mode, it triggers in *run & forget* mode so that any lengthy side-effect does not delay command reply. With `Sync` mode, the side-effect runs to completion before the entity processes the next command: this can simplify the side-effect logic as it precludes accounting for concurrency, but can hurt the system's responsiveness. The default implementation of `runModeFor` sets it to `Async`.

The passed @ref:[Effector](effector.md) instance can be used to access entity state, chain further interactions with the entity itself and to control passivation (for an example, see @github[BookingEffector](/example/src/main/scala/endless/example/logic/BookingSideEffect.scala)

@@@ note
Defining a side-effect is entirely optional, pass-in `SideEffectInterpreter.unit` in @scaladoc[deployRepository](endless.runtime.akka.deploy.Deployer) if there are no side-effects to describe.
@@@
