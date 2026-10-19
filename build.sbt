scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  // "-Xfatal-warnings",
  "-language:reflectiveCalls",
)

// Same stack as the course labs 6-8 (schoeberl/agile-hw).
scalaVersion := "2.13.16"
val chiselVersion = "6.7.0"
addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion
libraryDependencies += "edu.berkeley.cs" %% "chiseltest" % "6.0.0"
// ScalaTest/ScalaCheck bridge for the property-based tests (lab 6).
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test
