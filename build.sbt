import scala.collection.Seq

ThisBuild / scalaVersion := "2.13.8"

ThisBuild / githubRepository := "tectonic"

ThisBuild / homepage := Some(url("https://github.com/precog/tectonic"))

ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/precog/tectonic"), "scm:git@github.com:precog/tectonic.git"))

val CatsEffectVersion = "3.3.14"
val Fs2Version = "3.2.11"
val JawnFs2Version = "2.2.0"
val ScalacheckVersion = "1.16.0"
val Specs2Version = "4.16.1"

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(Test / packageBin / publishArtifact := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core, fs2, test, benchmarks, harness)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "tectonic")
  .settings(libraryDependencies += "org.typelevel" %% "cats-effect" % CatsEffectVersion)
  .enablePlugins(AutomateHeaderPlugin)

lazy val fs2 = project
  .in(file("fs2"))
  .dependsOn(core, test % "test->test")
  .settings(name := "tectonic-fs2")
  .settings(libraryDependencies += "co.fs2" %% "fs2-core" % Fs2Version)
  .enablePlugins(AutomateHeaderPlugin)

lazy val harness = project
  .in(file("harness"))
  .dependsOn(fs2)
  .settings(name := "tectonic-harness")
  .settings(noPublishSettings) // mostly for internal testing
  .settings(libraryDependencies += "co.fs2" %% "fs2-io" % Fs2Version)
  .enablePlugins(AutomateHeaderPlugin)

lazy val test = project
  .in(file("test"))
  .dependsOn(core)
  .settings(name := "tectonic-test")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % ScalacheckVersion,
      "org.specs2" %% "specs2-core" % Specs2Version,
      "org.specs2" %% "specs2-scalacheck" % Specs2Version % Test
    )
  )
  .enablePlugins(AutomateHeaderPlugin)

lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(core, fs2)
  .settings(name := "tectonic-benchmarks")
  .settings(noPublishSettings)
  .settings(
    scalacStrictMode := false,
    javaOptions += "-XX:+HeapDumpOnOutOfMemoryError",
    javaOptions += s"-Dproject.resource.dir=${(Compile / resourceDirectory).value}",
    javaOptions += s"-Dproject.managed.resource.dir=${(Jmh / resourceManaged).value}",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % Fs2Version,
      "co.fs2" %% "fs2-io" % Fs2Version,
      "org.typelevel" %% "jawn-fs2" % JawnFs2Version)
  )
  .settings( // magic rewiring so sbt-jmh works sanely
    Jmh / sourceDirectory := (Compile / sourceDirectory).value,
    Jmh / classDirectory := (Compile / classDirectory).value,
    Jmh / dependencyClasspath := (Compile / dependencyClasspath).value,
    Jmh / compile := (Jmh / compile).dependsOn(Compile / compile).value,
    Jmh / run := (Jmh / run).dependsOn(Jmh / compile).evaluated
  )
  .settings(Jmh / resourceGenerators += Def.task {
    import scala.sys.process._

    val targetDir = (Jmh / resourceManaged).value
    val targetFile = targetDir / "worldcitiespop.txt"

    if (!targetFile.exists()) {
      // the original source took it offline, so now it's in my dropbox 🤷‍♂️
      s"curl -L -o $targetDir/worldcitiespop.txt.gz https://www.dropbox.com/s/8tfbn4a7x2tam4n/worldcitiespop.txt.gz?dl=1".!!
      s"gunzip $targetDir/worldcitiespop.txt.gz".!!
    }

    Seq(targetFile)
  }.taskValue)
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(JmhPlugin)
