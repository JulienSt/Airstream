# Contributing


## Workflow

If you want to add a feature but are not sure how to do this or how it should behave in edge cases, feel free to chat us up in [Discord](https://discord.gg/JTrUxhq7sj). 

When making PRs, please allow Airstream maintainers to make changes to your branch. We might make changes, so make a copy of your branch if you need it.


## Code style

Please run `sbt scalafmtAll` before submitting the PR.

This only sets the ground rules, so please try to maintain the general style of the codebase.


## Tests

Please run `sbt +test` locally before submitting the PR.

Note that existing tests print this compiler warning in Scala 3:
- [E029] Pattern Match Exhaustivity Warning: .../src/test/scala-3/com/raquo/airstream/split/SplitMatchOneSpec.scala

This is expected. Ideally I would assert that this warning exists instead of printing it, but I don't think that's possible. I don't want to hide such warnings wholesale, but suggestions for improvement are welcome.

## Platform source sets

Platform differences live in the build, not in the code. Platform-dependent decisions belong in the
corresponding `.js` or `.jvm-native` source file (with `.jvm` / `.native` available for target-specific code). Shared sources must read as if only one platform
existed.

Run `python3 scripts/check_platform_boundaries.py` before submitting a cross-platform change. The check
rejects runtime platform probes and direct Scala.js or Scala Native APIs in shared production sources.

The fork currently includes upstream `aa6818b7f523244f5abab29e4b56136fcc31183b`.
`python3 scripts/check_js_regression.py` compares both Scala.js versions and the
optimized consumer against that unmodified upstream commit. After merging a new
upstream revision, update this baseline in both checkers and review the new diff
before changing its recorded budgets. Merge commits do not count as new port
implementation commits.

Run `python3 scripts/check_external_build.py` to verify that another sbt build can
load the Native project through `ProjectRef` without receiving formatter downloads.

JVM and Native compile the unchanged public Tuplez sources selected by
`Versions.Tuplez`, matching the Scala.js dependency. The hidden `tuplez-sources`
configuration resolves the source artifact from Maven Central; its sources are
unpacked under `sourceManaged`, never copied into the repository. This replaces
the older vendored Tuplez shim and retains the upstream MIT-licensed source.



## Custom observer storage

`WritableObservable` allocates its external and internal observer arrays independently on first use.
Empty-state checks and delivery to an unused observer kind do not create an array. Explicit access to
the protected `externalObservers` or `internalObservers` getter always returns usable mutable storage;
its aliases remain live after cleanup and restart. Once allocated, the array is retained for reuse.

The accessors are now final. A custom source that previously supplied storage through a protected val
override installs that same storage with an assignment in its constructor body:

```scala
// Previous version:
override protected val externalObservers = suppliedExternal
override protected val internalObservers = suppliedInternal

// This version, in the subclass body before registering observers:
externalObservers = suppliedExternal
internalObservers = suppliedInternal
```

This preserves the supplied arrays and their aliases. Do not replace storage while subscriptions are
active. Normal `addObserver`, `foreach`, owner cleanup and shared-start batching require no changes.

## N-Generators

Airstream offers several types of methods like `combineWith` and `mapN` in varying arity. These live in packages called `generated`, in implicit classes that are generated at compile time by generators located in the `project` folder.

To apply and execute your changes to the generators, run `reload; compile` in sbt. We commit all generated files to git because invisible code is annoying to figure out, and to help with source maps.


# Documentation

README.md needs to be updated before the changes can be merged into `master`. I usually do this after the feature is done.

We used to publish itemized changes in CHANGELOG.md, but we switched to publishing release blog posts at [laminar.dev](https://laminar.dev).
