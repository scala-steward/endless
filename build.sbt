import Dependencies._

val commonSettings = Seq(
  Compile / scalacOptions --= Seq("-language:implicitConversions", "-Xsource:2.14"),
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
  wartremoverExcluded += sourceManaged.value,
  Compile / wartremoverErrors ++= Warts
    .allBut(Wart.Any, Wart.Nothing, Wart.ImplicitParameter, Wart.Throw, Wart.DefaultArguments),
  coverageExcludedPackages := "<empty>;endless.test.*"
)

inThisBuild(
  List(
    organization := "io.github.endless4s",
    homepage := Some(url("https://github.com/endless4s/endless")),
    licenses := List("MIT License" -> url("http://opensource.org/licenses/mit-license.php")),
    developers := List(
      Developer(
        "jchapuis",
        "Jonas Chapuis",
        "me@jonaschapuis.com",
        url("https://jonaschapuis.com")
      )
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeProjectHosting := Some(xerial.sbt.Sonatype.GitHubHosting("endless4s", "endless", "me@jonaschapuis.com")),
    scalaVersion := "2.13.6",
    Global / onChangedBuildSource := ReloadOnSourceChanges,
    PB.protocVersion := "3.17.3", // works on Apple Silicon,
    versionPolicyIntention := Compatibility.None,
    versionPolicyIgnoredInternalDependencyVersions := Some(
      "^\\d+\\.\\d+\\.\\d+\\+\\d+".r
    ) // Support for versions generated by sbt-dynver
  )
)

lazy val core = (project in file("core"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= cats ++ catsTagless ++ log4cats ++ (catsLaws ++ catsTestkit ++ mUnit)
      .map(_ % Test)
  )
  .settings(name := "endless-core")

lazy val runtime = (project in file("runtime"))
  .dependsOn(core)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= catsEffectStd ++ akka ++ log4cats)
  .settings(
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    )
  )
  .settings(name := "endless-runtime-akka")

lazy val circeHelpers = (project in file("circe"))
  .dependsOn(core)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= circe :+ (akkaActorTyped % akkaVersion))
  .settings(name := "endless-circe-helpers")

lazy val example = (project in file("example"))
  .dependsOn(core, runtime, circeHelpers)
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= catsEffect ++ http4s ++ akkaTest ++ logback ++ log4catsSlf4j ++ (mUnit ++ catsEffectMUnit ++ scalacheckEffect ++ log4catsTesting)
      .map(_ % Test)
  )
  .settings(name := "endless-example", run / fork := true, publish / skip := true)

// Generate API documentation per module, as documented in https://www.scala-sbt.org/sbt-site/api-documentation.html#scaladoc-from-multiple-projects

// Create one configuration per module
val Core    = config("core")
val Circe   = config("circe")
val Runtime = config("runtime")

// For each module define its package prefix and path in documentation API
val scaladocSiteProjects = List(
  core         -> (Core,    "endless",         "core"),
  circeHelpers -> (Circe,   "endless.circe",   "circe"),
  runtime      -> (Runtime, "endless.runtime", "runtime")
)

lazy val documentation = (project in file("documentation"))
  .enablePlugins(ParadoxMaterialThemePlugin, ParadoxPlugin, ParadoxSitePlugin, SiteScaladocPlugin)
  .settings(
    paradoxProperties ++= (
      scaladocSiteProjects.map { case (_, (_, pkg, path)) =>
        s"scaladoc.${pkg}.base_url" -> s"api/${path}"
      }
      .toMap
    ),
    scaladocSiteProjects.flatMap { case (project, (conf, _, path)) =>
      SiteScaladocPlugin.scaladocSettings(
        conf,
        project / Compile / packageDoc / mappings,
        s"api/${path}"
      )
    },
    Compile / paradoxMaterialTheme := {
        val theme = (Compile / paradoxMaterialTheme).value
        val repository =
          (ThisBuild / sonatypeProjectHosting).value.get.scmInfo.browseUrl.toURI
        theme
          .withRepository(repository)
          .withSocial(repository)
      }
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, runtime, circeHelpers, example)
  .dependsOn(example)
  .settings(Compile / mainClass := (example / Compile / mainClass).value)
  .settings(commonSettings: _*)
  .settings(publish / skip := true)
  .settings(
    name := "endless",
    version := "0.0.1"
  )
