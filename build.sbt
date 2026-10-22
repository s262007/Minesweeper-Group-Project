scalaVersion := "2.13.16"
val chiselVersion = "6.7.0"
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-language:reflectiveCalls")
addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion
libraryDependencies += "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test
