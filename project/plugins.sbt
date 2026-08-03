addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.2")

// Added for the Scala Native cross-build. CrossType.Pure keeps the existing src/ exactly where it is and puts the
// platform-specific sources in .js/ and .native/, so the source tree itself is untouched by the port.
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.11")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1"
