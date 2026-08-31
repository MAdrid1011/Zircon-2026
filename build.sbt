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
    Test / parallelExecution := false,
    Test / fork := true
  )
