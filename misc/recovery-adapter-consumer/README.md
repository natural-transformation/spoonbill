# Independent payload adapter consumer

A separate sbt build that resolves the `spoonbill-recovery-testkit`
artifact in test scope and runs its laws against a payload adapter written
without JDBC, repository classes or private Spoonbill internals. It shows that
an external author can run the laws from kit artifacts alone. The local workflow
below validates packaging; public released-kit consumption remains a separate
release check.

`FilePayloadStore` keeps each payload in a directory. A put writes a temporary
file, forces it to disk and moves it atomically into place; a delete writes a
tombstone and removes the bytes under the same per-reference lock, so a later
put of a retired reference fails at the store. It therefore declares
`CleanupBasis.DurableRetirementFence` for adapters sharing one file system.
This claim assumes a durably provisioned local directory whose file locks,
atomic moves and directory force operations provide the documented guarantees,
with every writer following this protocol. The adapter acknowledges `Durable`
after forcing the bytes, moving the file and forcing the directory; matching
duplicate puts repeat the directory force. The laws do not qualify a network
filesystem or establish power-loss behavior. The example also retains its lock
files and process-local lock map.
Tombstones are never reclaimed by the example, so it does not demonstrate bounded
fence retention. A production adapter must retain each fence and its accounting
until admission is irrevocably closed and all admitted writes have settled; unique
references alone do not make reclamation safe. See the
[qualification guide](../../docs/recovery-adapter-qualification.md) for what an
adapter must state about fence retention.

`FilePayloadProcessSpec` adds independent-JVM probes: immutable writes and
retirement admission, process restart after acknowledged retirement, and a
queued upload deliberately overtaken by another process's deletion. Its stdin
gate fixes the ordering without relying on a timed race. The suite bounds and
cleans up its child JVMs and temporary files; these local process checks do not
establish power-loss, replication or distributed-filesystem durability.

Run serially from the repository root through its Nix environment. First
publish the current working tree locally, then run the consumer:

```sh
nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'core/publishLocal' 'bytes/publishLocal' 'effect/publishLocal' 'web/publishLocal' \
  'spoonbill/publishLocal' 'recoveryTestkit/publishLocal'
nix develop --no-write-lock-file --command \
  bash -c 'cd misc/recovery-adapter-consumer && sbt --batch --supershell=false -Dsbt.server.autostart=false verifyKitGraph test'
```

`-Dspoonbill.version=<version>` selects matching Spoonbill and kit versions instead
of the default local snapshot. After publication, run the isolated release check
from the repository root with an explicit stable `X.Y.Z` version:

```sh
env -u JAVA_HOME nix develop --no-write-lock-file --command \
  bash scripts/verify-recovery-kit-release.sh <released-version>
```

Replace `<released-version>` with the actual release containing portable recovery;
9.0.0 predates the kit. The runner copies only this consumer's source and build
files into a temporary directory, gives Java/sbt/Ivy/Coursier fresh directories,
discards inherited launcher and resolver overrides, and resolves only from
Maven Central with `spoonbill.released=true`. It runs `verifyKitGraph test`,
prints the repository and resolved coordinates, and removes the temporary build
and caches on success or failure. Save the command output with the release's
verification evidence. A failure exits nonzero and does not publish anything.
Passing after `publishLocal` alone does not prove public availability.
