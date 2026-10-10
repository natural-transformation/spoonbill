# Immutable runtime capture

`artifact.mjs` captures an **already compiled** runtime classpath into a new,
exclusive directory. It does not build the reference, fabricate build identities,
fill the performance manifest, or establish that a supplied source inventory
produced those binaries. The producing job must compile, export the classpath,
run the real executable policy report, and generate current source/client
inventories from that same checkout before capture. Preserve that job's build and
test evidence alongside the artifact.

Enter the repository's Nix environment. With the actual, single-line output of
`export browserAuthBaseline/Runtime/fullClasspath` in `runtime-classpath.txt`,
the current `inventory.mjs` and `client-inventory.mjs` reports, and actual
`spoonbill.browserauthbaseline.ReferencePolicyReport` stdout in `policy.json`:

The producing wrapper compiles/exports that classpath, checks the compiled policy
against the manifest, and captures current inventories in one invocation:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/capture-browser-auth-artifact.sh \
  /absolute/path/to/new-capture
```

The optional second argument is a previously compiled classpath file. That mode
requires the caller to establish the compilation/source correspondence; local
validation records exactly which source revision was compiled. CI uses the
compile/export mode and uploads the completed bundle only after correctness
checks pass. Its job summary records the actual build revision, content/graph
identities, upload digest and immutable artifact URL. Retention is ninety days;
retain a verified copy before expiration for a longer measurement campaign.

For explicit capture from already generated inputs:

```sh
nix develop --no-write-lock-file
node misc/browser-auth-baseline/performance/artifact.mjs capture \
  runtime-classpath.txt /absolute/path/to/new-capture \
  source-inventory.json policy.json client-inventory.json
node /absolute/path/to/new-capture/launcher.mjs verify /absolute/path/to/new-capture
node /absolute/path/to/new-capture/launcher.mjs command \
  /absolute/path/to/new-capture \
  spoonbill.browserauthbaseline.MemoryReferenceServer --proof=short
node misc/browser-auth-baseline/performance/artifact.mjs archive \
  /absolute/path/to/new-capture /absolute/path/to/new-runtime.tar
```

`command` emits JSON `{command:"java",args:["-cp",...,MAIN,...ARGS]}`. Execute
that argument array directly in the process-owning runner, using its Nix JDK;
do not join it into a shell string. The `launchCommand(directory, mainClass,
args)` export supplies the same result to a Node runner. The tool deliberately
does not spawn a long-lived server: the runner retains responsibility for Java
signals, timeouts, process cleanup and runtime configuration. The captured loader
uses only Node built-ins and works after moving the artifact and deleting all
original classpath entries. Node and the matching JDK remain external managed
runtime requirements. Any external database also remains a runtime dependency.

## Retaining a single archive

Upload the successful `.tar` output as one file. Direct directory upload through
`actions/upload-artifact` can omit empty directories or collapse case-distinct
paths; both are part of the verified runtime index. The archive keeps those
entries, the complete capture and its standalone loader together. Extract a
trusted, hash-checked archive into a new directory on a compatible filesystem,
then run its loader's `verify` before obtaining a launch command. A
case-insensitive filesystem cannot represent all artifacts created on a
case-sensitive filesystem.

`archiveArtifact({directory, destination})` and the `archive` CLI require GNU tar
from the active Nix environment. They first verify the capture, exclusively
create `NEW.tar` and `NEW.tar.state.json`, and stream GNU tar's output through a
byte limit. Tar receives an explicit sorted member list with no recursive
discovery, fixed zero timestamps/owner/group, GNU archive format and mode `0755`
for every member. That uniform archive mode makes filesystem permissions
irrelevant to archive identity; Java classpath resources do not require their
original executable flags. Long names use GNU long-name records. No shell
evaluation or extraction takes place in this helper.

The default tar subprocess timeout is 60 seconds, and its maximum archive output
is the capture byte limit plus bounded header/metadata overhead (about 1.2 GiB).
Programmatic `timeoutMs` (at most 300,000 ms) and `maxArchiveBytes` permit explicit
bounds. Exceeding either limit kills the owned tar process and retains partial
bytes with an incomplete sidecar. Other failures are also retained; no existing
archive or sidecar is overwritten, and no failed artifact is deleted.

After tar exits, the helper verifies the source capture again and parses the tar
without extracting: each indexed member must occur exactly once, have its
expected type/size/content hash, and use the fixed metadata. Links, extras,
truncation and trailing nonzero data are rejected. The sidecar becomes complete
only after this check. The returned JSON has separate `artifactSha256` (logical
capture identity), `tarSha256` (exact archive bytes) and `tarBytes` fields.
`verifyArchive({archive, directory})` repeats the member verification against a
verified capture; it is not an arbitrary archive extraction API. Preserve the
returned identities in the producing job's evidence. A complete tar archive does
not claim that the baseline measurements or browser collectors are complete.

## Identity and contents

Classpath order, including duplicate entries, is preserved in anonymous ordered
slots `classpath/0000`, `classpath/0001.jar`, and so on. Directories must be actual
`classes` roots; every runtime file and empty subdirectory beneath them is copied.
JARs are copied byte for byte. This captures compiled classpath inputs only; it
does not traverse the repository, engine sources, a home directory or unrelated
documents. Supply only the intended build's classpath and the three specified
reports. Do not put credentials in build resources.

`artifact.json` records:

- `index`: sorted artifact-relative directory names and file byte lengths/SHA-256
  hashes. The copied standalone loader and the three provenance reports are
  included. Absolute original paths, file timestamps and filesystem inode
  identities are excluded.
- `dependencyGraphSha256`: SHA-256 of `JSON.stringify(classpath)`, the **ordered
  resolved runtime content graph**, not a Maven dependency-resolution graph.
  Each classpath entry hashes its sorted relative file/directory content index.
  JAR timestamps inside JAR bytes are content and therefore affect this hash;
  filesystem modification times do not.
- `sourceInventorySha256`, `referencePolicySha256`, `clientInventorySha256`:
  hashes of the exact supplied report bytes. Strict report schemas reject unknown
  fields and absolute path values. `referenceIntegrationSha256` is the validated
  source inventory's own `sourceSha256`; it is distinct from the report byte hash.
- `artifactSha256`: SHA-256 of `JSON.stringify(payload)`, where `payload` is the
  entire manifest except `artifactSha256`. Preserve key order when verifying;
  use the supplied loader rather than rebuilding the JSON independently.

The original classpath text is intentionally omitted because it contains private
host paths. The manifest plus copied classpath fully describes launch order.
Content hashes detect changes; they are not signatures. Store the expected
artifact hash in independently retained collection provenance. Exclusive capture
never updates an existing artifact, but filesystem permissions are not a claim
that a user with write access cannot later modify it. Verification rejects
modified/unindexed files, incomplete output and symlinks before returning a
launch command. Run from controlled, quiescent artifact storage: verification
does not lock out another writer between verification and Java class loading.

## Failure and bounds

Once a destination is safely claimed, `capture-state.json` starts `incomplete`.
Every copy uses exclusive file creation. Source byte hashes, file identities and
directory membership are checked while copying and again before publication;
changes fail the capture. The complete marker is published last with an atomic
rename. This is an application-level commit marker, not a filesystem crash
durability guarantee. Exceptions leave partial bytes and an explicit incomplete
marker for inspection; there is no automatic retry or deletion. Use a different
new directory for a later attempt. Unsafe or existing destinations fail before
being changed. Errors recorded in the marker contain phase/code, not OS paths.

The defaults allow at most 512 classpath entries, 50,000 source files/directories,
1 GiB of copied source bytes, 128 MiB per file and 8 MiB per metadata file.
Reads and directory enumeration are bounded. The programmatic `limits` option
can set positive integer limits explicitly; larger input sets require a reviewed
caller choice. Symlinks, special files, hidden runtime names, path traversal,
output nested in an input, wildcard/relative/multiline classpaths and malformed
class headers are rejected. Supported JARs use ordinary non-encrypted ZIP with
stored/deflated entries; ZIP64 is refused. The parser validates the ZIP structure
and bounded manifest, not every class's bytecode semantics. A manifest
`Class-Path` is refused rather than silently omitting implicit dependencies.

The focused verification compiles a tiny Java fixture with Nix's `javac`, makes
a JAR with Nix's `jar`, relocates the capture, deletes its input tree, and runs it
with Nix's `java`. It also covers classpath precedence, deterministic identities,
source mutation, limits, tampering, traversal, symlinks, implicit dependencies and
incomplete captures:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/artifact.test.mjs
```

These tests establish capture behavior. A real baseline capture and retained CI
upload are separate producing-job steps; test fixture hashes never populate the
live performance manifest.
