# Native Scala 3 Pekko publication check

The initial v4 dependency pair is Pekko **1.7.0** / Pekko HTTP **1.4.0**, with
Scala **3.3.8**. The HTTP `_3` POM requires scala3-library 3.3.8; the actor and
stream 1.7.0 POMs use 3.3.7. No `_2.13`/`_3` binary substitutions or exclusions
are used. Other version pairs are not claimed supported until this fixture and
the transport lifecycle suites pass against them.

The native artifacts were verified directly in Maven Central:

- [Pekko actor 1.7.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-actor_3/1.7.0/pekko-actor_3-1.7.0.pom)
- [Pekko HTTP 1.4.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-http_3/1.4.0/pekko-http_3-1.4.0.pom)
- [Typed actors 1.7.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-actor-typed_3/1.7.0/pekko-actor-typed_3-1.7.0.pom)
- [Persistence 1.7.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-persistence_3/1.7.0/pekko-persistence_3-1.7.0.pom)

This separate sbt build resolves the published Spoonbill artifact metadata. It
does not use `dependsOn` or repository classes. The consumer creates a typed
actor system, adapts it to classic, loads persistence, and serves a real HTTP
request through the official Spoonbill adapter. `verifyNativeGraph` rejects
any Scala 2.13 Pekko artifact anywhere in the resolved graph.

Run serially from the repository root through its Nix environment. First publish
the current v5 dependencies locally and generate the adapter POM:

```sh
nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'core/publishLocal' 'bytes/publishLocal' 'effect/publishLocal' \
  'http/publishLocal' 'web/publishLocal' 'webDsl/publishLocal' \
  'spoonbill/publishLocal' 'pekko/publishLocal' 'pekko/makePom'
nix develop --no-write-lock-file --command node \
  misc/pekko-native-consumer/check-pom.mjs \
  interop/pekko/target/scala-3.3.8/spoonbill-pekko_3-5.0.0-SNAPSHOT.pom
nix develop --no-write-lock-file --command bash -c \
  'cd misc/pekko-native-consumer && sbt --batch --supershell=false verifyNativeGraph compile run'
```

For a candidate upgrade use `-Dpekko.version=...`, `-Dpekko.http.version=...`,
and when needed `-Dspoonbill.scala.version=...`; the same commands and lifecycle
suites must pass before adding that pair to the supported matrix. The version
properties are test inputs, not dependency overrides in the published adapter.
Publication to an external repository is a separate release action.
