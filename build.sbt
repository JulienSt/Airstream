import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType, JVMPlatform}
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.JSPlatform
import scalanativecrossproject.ScalaNativeCrossPlugin.autoImport.NativePlatform

// Airstream cross-builds for Scala.js, Scala Native, and the JVM.
//
// CrossType.Pure is deliberate: the shared sources stay exactly where they have always been, in src/. JavaScript-only
// sources live in .js/; the platform-neutral non-JavaScript backend shared by Native and JVM lives in .jvm-native/. The
// source tree is therefore untouched by the port, and the split is visible in the build rather than in runtime branches.
//
// The JavaScript build must come out identical. Everything JS-specific — scalajs-dom, ew, the jsdom test environment, the
// main-module initializer, MiMa, the CI source mapping — moved into jsSettings rather than being deleted or made
// conditional on a platform check in shared code.

ThisBuild / buildKitDownloads := Seq(
  _.fromGithubTag(
    repo = "raquo/scalafmt-config",
    filePath = ".scalafmt.shared.conf",
    tag = "v0.1.0"
  ).withDoNotEditComment(_.`#`)
)

ThisBuild / buildKitDownloadsDir := (ThisBuild / baseDirectory).value / ".buildkit"

// Buildkit queues an unscoped download command, which resolves against a consuming
// build when Airstream is loaded through ProjectRef. Run it in this build instead.
Global / onLoad := {
  val previous = (Global / onLoad).value
  val downloads = ProjectRef((LocalRootProject / baseDirectory).value.toURI, "root") / buildKitRunDownloads
  previous.andThen { state =>
    val scoped = state.copy(remainingCommands = state.remainingCommands.filterNot(_.commandLine == "buildKitRunDownloads"))
    Project.extract(scoped).runTask(downloads, scoped)._1
  }
}

lazy val nonJavaScriptTestExclusionReasons =
  settingKey[Map[String, String]]("Non-JavaScript test sources excluded with a reviewable reason")

lazy val TuplezSources = config("tuplez-sources").hide

lazy val nonJavaScriptSettings = Seq(
  libraryDependencies += ("app.tulz" %% "tuplez-full" % Versions.Tuplez % TuplezSources.name).classifier("sources"),
  Compile / sourceGenerators += Def.task {
    val sources = update.value.matching(configurationFilter(TuplezSources.name)).find(_.getName.endsWith("-sources.jar"))
      .getOrElse(sys.error("Missing public Tuplez source artifact"))
    val destination = (Compile / sourceManaged).value / "tuplez"
    val license = IO.read((ThisBuild / baseDirectory).value / ".jvm-native" / "src" / "main" / "resources" / "META-INF" / "LICENSE-tuplez.txt")
    val extracted = IO.unzip(sources, destination).filter(_.getName.endsWith(".scala")).toSeq
    extracted.foreach(source => IO.write(source, "/*\n" + license + "*/\n" + IO.read(source)))
    extracted
  }.taskValue,
  Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / ".jvm-native" / "src" / "main" / "resources",
  crossScalaVersions := Seq(Versions.Scala_3),
  Compile / unmanagedSourceDirectories ++= {
    val root = (ThisBuild / baseDirectory).value / ".jvm-native" / "src" / "main"
    Seq(root / "scala", root / s"scala-${scalaBinaryVersion.value}")
  },
  Test / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / ".jvm-native" / "src" / "test" / "scala",
  nonJavaScriptTestExclusionReasons := Map(
    "AsyncUnitSpec.scala" -> "Scala.js event-loop test helper; non-JS timing tests drive Timers.runDue instead",
    "EventStreamFlattenFutureSpec.scala" -> "inherits the Scala.js async helper; NativeFutureSpec covers Future flattening",
    "EventStreamFlattenSpec.scala" -> "mixes synchronous cases with Scala.js timers; switch and glitch suites cover the synchronous contract",
    "SignalFlattenFutureSpec.scala" -> "inherits the Scala.js async helper; NativeFutureSpec covers Future flattening",
    "StatusSpec.scala" -> "waits on the Scala.js wall clock; NativeStatusSpec drives delay and debounce status",
    "DelayStreamSpec.scala" -> "waits on the Scala.js wall clock; NativeTimingSpec drives the same deadlines without sleeping",
    "EventStreamFromFutureSpec.scala" -> "also tests JS Promise APIs; NativeFutureSpec covers the Future-only contract",
    "PeriodicStreamSpec.scala" -> "waits on the Scala.js wall clock; NativeTimingSpec drives periodic deadlines",
    "SignalFromFutureSpec.scala" -> "also tests JS Promise APIs; NativeFutureSpec covers the Future-only contract",
    "ThrottleStreamSpec.scala" -> "waits on the Scala.js wall clock; NativeTimingSpec drives both leading modes"
  ),
  Test / sources := {
    val candidates = (Test / sources).value
    val exclusions = nonJavaScriptTestExclusionReasons.value
    val unexplained = exclusions.collect { case (source, reason) if reason.trim.isEmpty => source }
    if (unexplained.nonEmpty) {
      sys.error(s"Non-JavaScript test exclusions without a reason: ${unexplained.toSeq.sorted.mkString(", ")}")
    }
    if (exclusions.size > 10) {
      sys.error(s"Non-JavaScript test exclusion budget exceeded: ${exclusions.size} sources (maximum 10)")
    }
    val staleExclusions = exclusions.keySet -- candidates.iterator.map(_.getName).toSet
    if (staleExclusions.nonEmpty) {
      sys.error(s"Non-JavaScript test exclusions no longer name source files: ${staleExclusions.toSeq.sorted.mkString(", ")}")
    }
    candidates.filterNot(candidate => exclusions.contains(candidate.getName))
  }
)

// Auto-increment version for local development, using the same policy as upstream.
ThisBuild / version := buildKitDynVer.version.value
ThisBuild / dynver := buildKitDynVer.dynver.value

ThisBuild / scalaVersion := Versions.Scala_3

// -- Code generators for N-arity functionality

val generateTupleCombinatorsFrom = 2
val generateTupleCombinatorsTo = 22

/** Generators every platform needs: nothing they emit reaches beyond the standard library. */
def platformNeutralGenerators(sourceDir: File): Seq[File] = Seq.concat(
  GenerateTupleStreams(
    classNamePattern = n => s"TupleStream$n",
    fileName = "TupleStreams.scala",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run,
  GenerateTupleSignals(
    classNamePattern = n => s"TupleSignal$n",
    fileName = "TupleSignals.scala",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run,
  GenerateOptionTupleObservables(
    classNamePattern = n => s"OptionTupleObservable$n",
    fileName = "OptionTupleObservables.scala",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run,
  GenerateCombineStreamObjectOps(
    traitName = "CombineStreamObjectOps",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run,
  GenerateCombineSignalObjectOps(
    traitName = "CombineSignalObjectOps",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run
)

/** The two generated files that import app.tulz.
  *
  * Kept apart because tuplez publishes no Scala Native build. These two are the exact and only cost of that gap, which is
  * what makes restoring them a follow-up rather than a blocker for the rest of the port.
  */
def tuplezGenerators(sourceDir: File): Seq[File] = Seq.concat(
  GenerateCombineStreamOps(
    traitName = "CombineStreamOps",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run,
  GenerateCombineSignalOps(
    traitName = "CombineSignalOps",
    sourceDir = sourceDir,
    from = generateTupleCombinatorsFrom,
    to = generateTupleCombinatorsTo
  ).run
)

lazy val root = project
  .in(file("."))
  .aggregate(airstream.js, airstream.jvm, airstream.native)
  .settings(
    name := "airstream-root",
    crossScalaVersions := Seq(Versions.Scala_2_13, Versions.Scala_3),
    publish / skip := true,
    Compile / sources := Seq.empty,
    Test / sources := Seq.empty,
    Test / test / aggregate := false,
    Test / test := Def
      .sequential(
        airstream.js / Test / test,
        airstream.jvm / Test / test,
        airstream.native / Test / test
      )
      .value
  )

lazy val airstream = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("."))
  .configs(TuplezSources)
  .settings(
    name := "airstream",
    crossScalaVersions := Seq(Versions.Scala_2_13, Versions.Scala_3),
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % Versions.ScalaTest % Test,
      "org.scalacheck" %%% "scalacheck" % Versions.ScalaCheck % Test
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-language:higherKinds",
      "-language:implicitConversions",
    ),
    scalacOptions ~= { options: Seq[String] =>
      options.filterNot(Set(
        "-Ywarn-value-discard",
        "-Wvalue-discard"
      ))
    },
    // Silence Scala 3 migration warnings for constructs that we intentionally keep
    // because the sources cross-compile to Scala 2.13, which does not support the
    // suggested Scala 3 replacements:
    //  - `x: _*` vararg splices (2.13 has no `x*` splice syntax)
    //  - `with` as a type operator (2.13 has no `&` intersection types)
    //  - passing implicit arguments positionally (2.13 has no `using`)
    //  - trailing ` _` eta-expansion (kept to avoid a Scala.js eta-expansion warning
    //    about js.FunctionN not being @FunctionalInterface)
    scalacOptions ++= {
      if (scalaVersion.value.startsWith("3"))
        Seq(
          "-Wconf:msg=vararg splices:s",
          "-Wconf:msg=with as a type operator:s",
          "-Wconf:msg=Implicit parameters should be provided with a:s",
          "-Wconf:msg=for eta-expansion is unnecessary:s"
        )
      else
        Nil
    },

    // Silence Scala 3 migration/lint warnings that are only noise in the test sources
    // (and that we don't migrate there, to avoid churn and keep 2.13 cross-compilation):
    //  - ScalaTest matchers used infix, e.g. `x shouldBe y` (would require backticking
    //    every assertion; the matchers are not declared `infix`)
    (Test / scalacOptions) ++= {
      if (scalaVersion.value.startsWith("3"))
        Seq(
          "-Wconf:msg=is not declared infix:s",
        )
      else
        Nil
    },
    (Test / scalacOptions) ~= { options: Seq[String] =>
      options.filterNot { o =>
        o.startsWith("-Ywarn-unused") || o.startsWith("-Wunused")
      }
    },
    (Compile / doc / scalacOptions) ~= (_.filterNot(
      Set(
        "-deprecation",
        "-explain-types",
        "-explain",
        "-feature",
        "-language:existentials,experimental.macros,higherKinds,implicitConversions",
        "-unchecked",
        "-Xfatal-warnings",
        "-Ykind-projector",
        "-from-tasty",
        "-encoding",
        "utf8",
      )
    )),
    (Compile / doc / scalacOptions) ++= Seq(
      "-no-link-warnings" // Suppress scaladoc "Could not find any member to link for" warnings
    ),
    (Test / parallelExecution) := false,
    // The generators write into the SHARED src/main, exactly where they wrote before the cross-build existed. Pointing
    // them at each platform's own source directory instead would emit two copies of every generated trait and the
    // compiler would rightly refuse them as duplicate definitions.
    Compile / sourceGenerators += Def.task {
      platformNeutralGenerators((ThisBuild / baseDirectory).value / "src" / "main") ++
        tuplezGenerators((ThisBuild / baseDirectory).value / "src" / "main")
    }.taskValue,
    // Same reasoning as the main generators above: the shared src/test is the one place these belong, or each platform
    // emits its own copy of the same spec class and the compiler refuses both.
    Test / sourceGenerators += Def.task {
      Seq.concat(
        GenerateCombineSignalsTest(
          className = "CombineSignalsSpec",
          testSourceDir = (ThisBuild / baseDirectory).value / "src" / "test",
          from = generateTupleCombinatorsFrom,
          to = generateTupleCombinatorsTo
        ).run,
        GenerateCombineStreamsTest(
          className = "CombineStreamsSpec",
          testSourceDir = (ThisBuild / baseDirectory).value / "src" / "test",
          from = generateTupleCombinatorsFrom,
          to = generateTupleCombinatorsTo
        ).run
      )
    }.taskValue
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % Versions.ScalaJsDom,
      "app.tulz" %%% "tuplez-full" % Versions.Tuplez,
      "com.raquo" %%% "ew" % Versions.Ew
    ),
    mimaPreviousArtifacts := Set("com.raquo" %%% "airstream" % "17.2.0"),
    jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(),
    scalaJSUseMainModuleInitializer := true,
    scalacOptions += pointScalaJsSourceMapsToGithub("raquo/Airstream").value
  )
  .jvmSettings(nonJavaScriptSettings)
  .nativeSettings(nonJavaScriptSettings)
  .nativeSettings(
    scalaVersion := Versions.Scala_3,
    crossScalaVersions := Seq(Versions.Scala_3)
  )
// No MiMa here: Native and JVM have no previously published binary contract.

// https://github.com/JetBrains/sbt-ide-settings
SettingKey[Seq[File]]("ide-excluded-directories").withRank(KeyRanks.Invisible) := Seq(
  ".buildkit", ".idea", ".metals", ".bloop", ".bsp",
  "target", "project/target", "project/project/target", "project/project/project/target",
  "node_modules"
).map(file)
