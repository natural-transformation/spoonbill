# Remaining work after portable UI recovery

Updated: 2026-11-09. Baseline: merged PRs [#25](https://github.com/natural-transformation/spoonbill/pull/25)
and [#26](https://github.com/natural-transformation/spoonbill/pull/26), commit
`dbc99641c9553701df9b6d9fdb5a3cb511e3085b`.

This is the local completion checklist. The recovery implementation and its
authenticated latency measurements are merged. Publication, deployment and
production adapter qualification remain separate gates. This change set prepares
the implementation and evidence for PR review; it does not publish a release or
deploy an application.

**Local work is complete and ready for PR review.** Existing unrelated workspace
files were preserved.
The continuation target is the **Spoonbill example locally**, as selected by
the user. No application or production deployment is in scope.

## Local work

- [x] Align adapter qualification with the cleanup contract: retain retirement
  fences and their accounting until safe reclamation is proved. Unique payload
  references alone do not bound physical retention. Document permanent example
  tombstones explicitly.
- [x] Refresh the [portable recovery plan](portable-ui-recovery-plan.md) to
  distinguish implemented R0–R3 work, local packaging evidence and release gates.
- [x] Adopt released Avocet 2.0.0 in Spoonbill, address integration differences,
  and validate rendering, recovery and application compilation. This is a
  Spoonbill integration change; it requires no new Avocet implementation.
- [x] Validate the recovery kit's local publication and independent consumer
  graph and laws, using matching runtime and kit versions.
- [x] Prepare a Maven Central-only consumer release check and reject mismatched
  Spoonbill artifact versions. Harden the example file adapter against partial
  writes and retry directory sync before acknowledging a duplicate put.
- [x] Record fresh test results and release/migration implications below.
- [x] Add a reproducible [isolated public-release verification runner](../scripts/verify-recovery-kit-release.sh)
  with private build/caches, Maven Central-only resolution, explicit stable
  version validation and inherited launcher-configuration isolation. Its four
  controlled tests are included in Scala CI's Node check step.
- [x] Add independent-JVM file-adapter probes for immutable writes, retirement,
  forced process termination after deletion, and a queued upload overtaken by
  deletion. This is local process evidence, not distributed-store qualification.
- [x] Rehearse V7 on a populated V6 schema, preserving every legacy slot/view/
  snapshot field, restoring its inline payload, advancing its revision and
  rejecting a stale write.
- [x] Write the [release and rollout runbook](portable-recovery-rollout.md),
  proposing 10.0.0 and recording the cutover, maintenance, storage accounting,
  deployment inputs and remaining external acceptance gates.
- [x] Complete independent final reviews of runtime integration, release tooling
  and documentation. No blocking findings remain. Pin the release checker's Java
  temporary directory inside its owned workspace so its law-test files are also
  removed when the runner exits.

## Final review map

Review the changes against the baseline above, including the new files listed
below:

| Area | Files and review focus |
| --- | --- |
| Renderer integration | [build.sbt](../build.sbt), [ApplicationInstance](../modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala), [Frontend](../modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala), [DevMode](../modules/spoonbill/src/main/scala/korolev/internal/DevMode.scala), [Html5RenderContext](../modules/spoonbill/src/main/scala/korolev/server/internal/Html5RenderContext.scala): Avocet 2, accepted render baselines, frame cleanup, complete snapshot I/O and styled preset IDs. |
| Renderer regressions | New [RenderLifecycleSpec](../modules/spoonbill/src/test/scala/korolev/internal/RenderLifecycleSpec.scala) and [Html5RenderContextSpec](../modules/spoonbill/src/test/scala/korolev/server/internal/Html5RenderContextSpec.scala), plus [DevModeSavedRenderContextSpec](../modules/spoonbill/src/test/scala/korolev/DevModeSavedRenderContextSpec.scala). |
| Adapter and migration evidence | [FilePayloadStore](../misc/recovery-adapter-consumer/src/main/scala/FilePayloadStore.scala), [write/law tests](../misc/recovery-adapter-consumer/src/test/scala/FilePayloadStoreLawsSpec.scala), new [process tests](../misc/recovery-adapter-consumer/src/test/scala/FilePayloadProcessSpec.scala), and [populated V6 migration test](../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcBrowserPersistenceSpec.scala). |
| Release verification | [consumer.sbt](../misc/recovery-adapter-consumer/consumer.sbt), new [isolated runner](../scripts/verify-recovery-kit-release.sh) and [runner tests](../misc/recovery-kit-release-tests.mjs), [CI inclusion](../.github/workflows/scala.yml). Actual public resolution remains a post-release gate. |
| Documentation | [Changelog](../CHANGELOG.md), [qualification guide](recovery-adapter-qualification.md), [consumer README](../misc/recovery-adapter-consumer/README.md), updated [design status](portable-ui-recovery-plan.md), new [rollout runbook](portable-recovery-rollout.md) and this checklist. |

The historical design plan is included with updated implementation status and
scope wording. Other local reviews, archives, `AGENTS.md` and `HANDOFF.md` are
outside this change set.

## Work that needs a release or deployment

- [ ] Choose the next Spoonbill release version and publish matching modules,
  including `spoonbill-recovery-testkit`. The portable recovery API is not part
  of Spoonbill 9.0.0. Assess the Avocet major dependency upgrade before choosing
  the release's compatibility promise; recompile consumers.
- [ ] Run the independent adapter consumer against the actual released
  artifacts, without satisfying the check from local candidate artifacts.
  Use `scripts/verify-recovery-kit-release.sh <stable-version>` after publication.
  `publishLocal` validates packaging, not public availability.
- [ ] Apply the documented [V7 migration procedure](request-admission-lifecycle.md#postgresql-v7-snapshot-references-migration)
  to each JDBC deployment, draining incompatible serving, writing and retention
  binaries first. Preserve the supported inline PostgreSQL snapshot path.
- [ ] In each reference-backed deployment, schedule maintenance, own binding
  shutdown, configure backlog limits and retention, and monitor unresolved
  obligations and rejected reservations.
- [ ] Qualify each production adapter's durability, fencing or quiescence,
  crash/restart behavior, retry admission, confidentiality and fence retention.
  Passing the in-process laws is necessary evidence, not proof of distributed
  storage semantics. See the [qualification guide](recovery-adapter-qualification.md).

## Optional follow-ups

- Migrate PairTime separately after reviewing its application-specific recovery
  and deployment requirements. It is a design reference, not a framework release
  prerequisite.
- Measure representative production workloads before optimizing provider realm
  lock contention. PR #26 supplies a baseline; it does not justify a speculative
  concurrency redesign or promise equal inline/reference latency.
- Additional payload or authority adapters may implement the generic public
  interfaces. There is no commitment to built-in Cassandra or Redis modules.
  Keep PostgreSQL's existing module and APIs supported.
- Deferred Avocet rendering features remain separate from portable UI recovery.

## Local verification

All builds and dependency-backed checks use the repository's `flake.nix`.
The sbt runs used `SBT_OPTS='-Xms256m -Xmx2g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2'`
inside the Nix shell, with serial task restrictions on the root build. They used
disposable loopback PostgreSQL, not an application database. No Avocet source
changes were needed; the resolved Avocet 2.0.0 jars came from Maven Central.

| Check | Result |
| --- | --- |
| Root `compile 'Test / compile' test` with disposable PostgreSQL | **Pass:** 1,534 tests; 6 fixture-specific law branches intentionally canceled. Includes the new rendering and saved-context regressions. |
| CI client/reference Node test set | **Pass:** 122 tests. |
| Core purity and explicit PostgreSQL schema parity | **Pass:** no core-boundary violations; all 25 schema statements match. |
| Local candidate publication, independent `verifyKitGraph test` | **Pass:** 27 tests after adding the 3 independent-JVM probes; 2 inapplicable law branches canceled. Matching Spoonbill artifacts, no JDBC dependency. |
| Chromium/WebKit two-JVM handoffs, inline and reference snapshots | **Pass:** all four combinations. Each reference run ended with 3 published references, 3 payloads, no staged writes and no open cleanup obligations; 6 permanent tombstones remained. |
| Released-consumer resolver configuration and rejection of an implicit snapshot | **Pass:** only Maven Central configured; a missing explicit release version is rejected. This checks tooling, not public artifact availability. |
| `node --test misc/recovery-kit-release-tests.mjs` in Nix | **Pass:** all 4 runner tests, covering version/Nix preconditions, isolation and failure propagation/cleanup. An initial macOS `/tmp` alias assertion was corrected to use canonical paths. |
| Populated V6-to-V7 SQL/storage rehearsal | **Pass:** the new focused JDBC test preserves full legacy rows and payload bytes, restores revision 7, commits revision 8 and rejects a stale revision-7 write. It does not execute an old server binary. |
| `git diff --check` and local Markdown link targets | **Pass.** |

The locally published candidate is
`9.0.0-local-recovery-20261109-SNAPSHOT`. It is not a public version selection.
The public-release check remains unchecked above until the actual release exists.
The initial full Scala run predates the new migration test; that added test was
compiled and run separately against fresh disposable PostgreSQL. Production
code has not changed since the initial full Scala/browser checks.

Rerun the Scala checks from the repository root:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash -c '
  export SBT_OPTS="-Xms256m -Xmx2g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2"
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false -Dsbt.server.autostart=false \
    "set Global / concurrentRestrictions := Seq(Tags.limitAll(1))" compile "Test / compile" test
'
```

The client checks run inside `nix develop --no-write-lock-file --command bash`:

```sh
node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs \
  misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs \
  misc/release-ref-tests.mjs misc/replacement-bootstrap-tests.mjs \
  misc/browser-auth-completion-loss-tests.mjs misc/core-purity-tests.mjs \
  misc/browser-auth-baseline/*.test.mjs
node scripts/check-core-purity.mjs
node scripts/export-browser-auth-schema.mjs --check
```

Rerun local packaging and independent consumption:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false RELEASE_VERSION=9.0.0-local-recovery-20261109-SNAPSHOT \
  nix develop --no-write-lock-file --command bash -c '
    export SBT_OPTS="-Xms256m -Xmx2g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2"
    sbt --batch --supershell=false -Dsbt.server.autostart=false \
      "set Global / concurrentRestrictions := Seq(Tags.limitAll(1))" \
      core/publishLocal bytes/publishLocal effect/publishLocal web/publishLocal \
      spoonbill/publishLocal recoveryTestkit/publishLocal &&
    cd misc/recovery-adapter-consumer &&
    sbt --batch --supershell=false -Dsbt.server.autostart=false \
      -Dspoonbill.version=9.0.0-local-recovery-20261109-SNAPSHOT verifyKitGraph test
  '
```

Rerun native handoffs:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  SPOONBILL_CLUSTER_SCENARIOS=cluster,reference-payloads SPOONBILL_AUTH_ENGINES=chromium,webkit \
  nix develop --no-write-lock-file --command bash -c '
    export SBT_OPTS="-Xms256m -Xmx2g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2"
    bash scripts/test-clustered-browser-auth.sh
  '
```

The independent consumer's [README](../misc/recovery-adapter-consumer/README.md)
contains the Maven Central-only command to run after publication. Production
datastore crash qualification and a fresh performance matrix were not run in this
local correctness pass; PR #26's measurements remain the baseline.

For the release-mode configuration check, these commands ran from
`misc/recovery-adapter-consumer` inside the repository Nix environment:

```sh
sbt --batch --supershell=false -Dsbt.server.autostart=false \
  -Dspoonbill.version=9.0.0 -Dspoonbill.released=true 'show externalResolvers'
# Expected rejection, verified by its nonzero exit and explicit-version diagnostic:
sbt --batch --supershell=false -Dsbt.server.autostart=false \
  -Dspoonbill.released=true 'show externalResolvers'
```

The first command only inspects resolver configuration; it does not attempt to
resolve a recovery kit for 9.0.0.

Rerun the continuation checks:

```sh
nix develop --no-write-lock-file --command node --test misc/recovery-kit-release-tests.mjs
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash -c '
  export SBT_OPTS="-Xms256m -Xmx2g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2"
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false -Dsbt.server.autostart=false \
    "set Global / concurrentRestrictions := Seq(Tags.limitAll(1))" \
    "securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec -- -z \"populated V6\""
'
```

The independent-consumer command above runs the process probes together with
the adapter and recovery laws. The release runner's tests use a controlled fake
sbt to verify the runner; no successful public-kit resolution is claimed.

## Avocet upgrade notes

- Generated SSR `k` attributes now follow styles, preserving Avocet 2's required
  style-before-attribute ordering. Custom documents must also follow the stricter
  rendering lifecycle and name validation.
- A render candidate becomes the next DOM baseline only after frontend
  acceptance. Failed templates discard their partial tape, and failed diff
  serialization cannot leak a partial frame into the next render. Tests cover
  ordinary and guarded recovery and subsequent empty diffs.
- Development render-context files now read/write completely and truncate on
  overwrite. Avocet 1's unversioned render buffers are incompatible with Avocet 2:
  stop the development server, remove only that application's obsolete files in
  `target/spoonbill/render-contexts` (or its configured development directory),
  and fully reload the browser. Do not clear PostgreSQL recovery data or domain
  state; presentation snapshots use a separate application codec.
