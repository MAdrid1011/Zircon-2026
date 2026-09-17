ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "zircon"

val chiselVersion = "7.15.0"

lazy val root = (project in file("."))
    .settings(
        name := "Zircon-2026",
        libraryDependencies ++= Seq(
            "org.chipsalliance" %% "chisel" % chiselVersion,
            "org.scalatest" %% "scalatest" % "3.2.20" % Test,
        ),
        addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
        scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-language:reflectiveCalls"),
        Test / parallelExecution := false,
    )
