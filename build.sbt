import scala.collection.Seq

ThisBuild / crossScalaVersions := Seq("2.12.12", "2.13.1")
ThisBuild / scalaVersion := "2.12.12"

ThisBuild / githubRepository := "tectonic"

homepage in ThisBuild := Some(url("https://github.com/precog/tectonic"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/precog/tectonic"),
  "scm:git@github.com:precog/tectonic.git"))

val Fs2Version = "2.4.4"

ThisBuild / publishAsOSSProject := true

val commonOverrides = Seq(githubRepository := "tectonic")

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core, fs2, test, benchmarks, harness)
  .settings(commonOverrides)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "tectonic")
  .settings(libraryDependencies += "org.typelevel" %% "cats-effect" % "2.2.0")
  .settings(commonOverrides)
  .enablePlugins(AutomateHeaderPlugin)

lazy val fs2 = project
  .in(file("fs2"))
  .dependsOn(
    core,
    test % "test->test")
  .settings(commonOverrides)
  .settings(name := "tectonic-fs2")
  .settings(
    libraryDependencies += "co.fs2" %% "fs2-core" % Fs2Version)
  .enablePlugins(AutomateHeaderPlugin)

lazy val harness = project
  .in(file("harness"))
  .dependsOn(
    fs2)
  .settings(commonOverrides)
  .settings(name := "tectonic-harness")
  .settings(noPublishSettings)    // mostly for internal testing
  .settings(
    libraryDependencies += "co.fs2" %% "fs2-io" % Fs2Version)
  .enablePlugins(AutomateHeaderPlugin)

lazy val test = project
  .in(file("test"))
  .dependsOn(core)
  .settings(name := "tectonic-test")
  .settings(commonOverrides)
  .settings(
    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2-core" % "4.10.5",
      "org.scalacheck" %% "scalacheck" % "1.14.3"),

    libraryDependencies += "org.specs2" %% "specs2-scalacheck" % "4.10.5" % Test)
  .enablePlugins(AutomateHeaderPlugin)

lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(core, fs2)
  .settings(name := "tectonic-benchmarks")
  .settings(commonOverrides)
  .settings(noPublishSettings)
  .settings(
    scalacStrictMode := false,
    javaOptions += "-XX:+HeapDumpOnOutOfMemoryError",
    javaOptions += s"-Dproject.resource.dir=${(Compile / resourceDirectory).value}",
    javaOptions += s"-Dproject.managed.resource.dir=${(Jmh / resourceManaged).value}",

    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % Fs2Version,
      "co.fs2" %% "fs2-io"   % Fs2Version,

      "org.http4s" %% "jawn-fs2" % "1.0.0"))
  .settings(    // magic rewiring so sbt-jmh works sanely
    Jmh / sourceDirectory := (Compile / sourceDirectory).value,
    Jmh / classDirectory := (Compile / classDirectory).value,
    Jmh / dependencyClasspath := (Compile / dependencyClasspath).value,
    Jmh / compile := (Jmh / compile).dependsOn(Compile / compile).value,
    Jmh / run := (Jmh / run).dependsOn(Jmh / compile).evaluated)
  .settings(
    Jmh / resourceGenerators += Def.task {
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
