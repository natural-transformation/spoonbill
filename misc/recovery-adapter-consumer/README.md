# Independent payload adapter consumer

A separate sbt build that resolves the published `spoonbill-recovery-testkit`
artifact in test scope and runs its laws against a payload adapter written
without JDBC, repository classes or private Spoonbill internals. It shows that
an external author can qualify an adapter from the released kit alone.

`FilePayloadStore` keeps each payload in a directory. A put writes a temporary
file, forces it to disk and moves it atomically into place; a delete writes a
tombstone and removes the bytes under the same per-reference lock, so a later
put of a retired reference fails at the store. It therefore declares
`CleanupBasis.DurableRetirementFence` for adapters sharing one file system.
Tombstones are never reclaimed by the example; see the
[qualification guide](../../docs/recovery-adapter-qualification.md) for what an
adapter must state about fence retention.

Run serially from the repository root through its Nix environment. First
publish the current working tree locally, then run the consumer:

```sh
nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'core/publishLocal' 'bytes/publishLocal' 'effect/publishLocal' 'web/publishLocal' \
  'spoonbill/publishLocal' 'recoveryTestkit/publishLocal'
nix develop --no-write-lock-file --command \
  bash -c 'cd misc/recovery-adapter-consumer && sbt --batch --supershell=false -Dsbt.server.autostart=false verifyKitGraph test'
```

`-Dspoonbill.version=<released version>` points the consumer at a released kit
instead of the local snapshot.
