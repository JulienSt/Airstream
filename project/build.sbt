// #Note this is /project/build.sbt – see /build.sbt for the main build config.
libraryDependencies += "com.raquo" %% "buildkit" % ProjectVersions.BuildKit

lazy val versionHelperTests = project
  .in(file("version-helper-tests"))
  .settings(
    Compile / unmanagedSources := Seq(baseDirectory.value.getParentFile / "EnvironmentFlags.scala"),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
  )
