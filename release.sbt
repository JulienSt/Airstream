name := "Airstream"

normalizedName := "airstream"

ThisBuild / organization := "com.raquo"

ThisBuild / homepage := Some(url("https://github.com/raquo/Airstream"))

ThisBuild / licenses += ("MIT", url("https://github.com/raquo/Airstream/blob/master/LICENSE.md"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/raquo/Airstream"),
    "scm:git@github.com/raquo/Airstream.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "raquo",
    name = "Nikita Gazarov",
    email = "nikita@raquo.com",
    url = url("https://github.com/raquo")
  )
)

ThisBuild / Test / publishArtifact := false

ThisBuild / pomIncludeRepository := { _ => false }
