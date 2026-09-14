addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")

// Added for the Scala Native cross-build. CrossType.Pure keeps the existing src/ exactly where it is and puts the
// platform-specific sources in .js/ and .native/, so the source tree itself is untouched by the port.
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.11")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

addSbtPlugin("com.raquo" % "sbt-buildkit" % "0.2.0-M1")

addSbtPlugin("com.raquo" % "sbt-buildkit-scalajs" % "0.2.0-M1")

addSbtPlugin("com.raquo" % "sbt-buildkit-dynver" % "0.2.0-M1")

libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1"
