# Native Scala 3 Pekko publication check

The current dependency pair is Pekko **2.0.0** / Pekko HTTP **2.0.0-M2**, with
Scala **3.3.9**. Pekko HTTP has no 2.0.0 final yet, and HTTP 1.x is not binary
compatible with Pekko 2.0, so the HTTP milestone is the only usable pair.
Upstream marks it experimental and not for production use, so this pair is not
yet a production release candidate. The
actor, stream, typed and persistence 2.0.0 `_3` POMs require scala3-library
3.3.9, which matches this repository; the HTTP 2.0.0-M2 POM uses 3.3.8 and was
built against Pekko 2.0.0-M4. No
`_2.13`/`_3` binary substitutions or exclusions are used. Other version pairs
are not claimed supported until this fixture and the transport lifecycle suites
pass against them.

The earlier v4 to v10 pair was Pekko 1.7.0 / Pekko HTTP 1.4.0.

The native artifacts were verified directly in Maven Central:

- [Pekko actor 2.0.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-actor_3/2.0.0/pekko-actor_3-2.0.0.pom)
- [Pekko HTTP 2.0.0-M2](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-http_3/2.0.0-M2/pekko-http_3-2.0.0-M2.pom)
- [Typed actors 2.0.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-actor-typed_3/2.0.0/pekko-actor-typed_3-2.0.0.pom)
- [Persistence 2.0.0](https://repo.maven.apache.org/maven2/org/apache/pekko/pekko-persistence_3/2.0.0/pekko-persistence_3-2.0.0.pom)

This separate sbt build resolves the published Spoonbill artifact metadata. It
does not use `dependsOn` or repository classes. The consumer creates a typed
actor system, adapts it to classic, loads persistence, and serves a real HTTP
request through the official Spoonbill adapter. `verifyNativeGraph` rejects
any Scala 2.13 Pekko artifact anywhere in the resolved graph.

Run serially from the repository root through its Nix environment. First publish
the current v8 dependencies locally and generate the adapter POM:

```sh
nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'core/publishLocal' 'bytes/publishLocal' 'effect/publishLocal' \
  'http/publishLocal' 'web/publishLocal' 'webDsl/publishLocal' \
  'spoonbill/publishLocal' 'pekko/publishLocal' 'pekko/makePom'
nix develop --no-write-lock-file --command node \
  misc/pekko-native-consumer/check-pom.mjs \
  interop/pekko/target/scala-3.3.9/spoonbill-pekko_3-10.0.0-SNAPSHOT.pom
nix develop --no-write-lock-file --command bash -c \
  'cd misc/pekko-native-consumer && sbt --batch --supershell=false verifyNativeGraph compile run'
```

For a candidate upgrade use `-Dpekko.version=...`, `-Dpekko.http.version=...`,
and when needed `-Dspoonbill.scala.version=...`; the same commands and lifecycle
suites must pass before adding that pair to the supported matrix. The version
properties are test inputs, not dependency overrides in the published adapter.
Publication to an external repository is a separate release action.
