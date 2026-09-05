ThisBuild / organization := "io.github.madrid1011"
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0-SNAPSHOT"

val chiselVersion = "7.14.0"

lazy val root = (project in file("."))
  .settings(
    name := "zircon-2026",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:reflectiveCalls",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    // The top-level AXI scenarios each compile an isolated Verilator harness.
    // Run suites concurrently, while keeping the ScalaTest distributor bounded
    // so the host is not oversubscribed by nested Verilator builds.
    Test / parallelExecution := true,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-P4"),
    Test / javaOptions += "-XX:ActiveProcessorCount=8",
    Test / fork := true
  )
