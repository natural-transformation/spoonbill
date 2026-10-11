# Local consumer feedback for 4.1.0-SNAPSHOT

Status: consumer correctness validation passed; release acceptance remains open.

This feedback concerns the local snapshot based on released 4.0.0. Examples are
generic and contain no consumer deployment details or real application data.

## Confirmed release compatibility issue

`BrowserProviderCapabilities` adds a fourth constructor field,
`sharedViewOwnership`, with a default of `false`. The source default does not
preserve the old JVM signatures. An unchanged Java caller compiled against the
released 4.0.0 core JAR runs with 4.0.0, but fails with the snapshot:

```text
java.lang.NoSuchMethodError:
  'void spoonbill.auth.BrowserProviderCapabilities.<init>(boolean, boolean, boolean)'
```

The minimal caller is:

```java
public final class LegacyCaller {
  public static void main(String[] args) {
    var capabilities = new spoonbill.auth.BrowserProviderCapabilities(true, true, true);
    if (!capabilities.atomicHostPreparation()) throw new AssertionError();
  }
}
```

Compile this caller once with the released core and its Scala runtime JARs.
Run the same class first with that core, then with the snapshot core, keeping
the rest of the classpath identical. The first run succeeds; the second fails
at construction. The check used a Nix-provided JDK and made no source changes.

Artifact SHA-256 values:

| Core artifact | SHA-256 |
| --- | --- |
| Released `spoonbill-core_3:4.0.0` | `e7e74e8de0e14dd1fe788977e86e54a8533ab5e0ce6c0868482c1345b9be0f3b` |
| Local `spoonbill-core_3:4.1.0-SNAPSHOT` | `b09858b0e443cce17fd29e53fb71653a5083eb2d0e4dbcb3ccbc9ac7d53c7a97` |

`javap -s -public` also confirms that the three-Boolean instance `copy`, static
`apply`, and companion `apply` signatures have been replaced by four-Boolean
versions. Adding constructor/apply overloads alone would not cover all of these
surfaces. Three-field extractor patterns and product-shape assumptions also
need consideration if source compatibility is promised.

By contrast, `SnapshotFormat` retains its previous public erased constructor
and constructor-default getter through the new secondary constructor. That
suspected incompatibility was not present.

Before publishing a compatible 4.1 release, preserve the intended public ABI and
source behavior and add an old-compiled consumer regression test. Alternatively,
explicitly select and document a breaking release. The migration guide's
requirement to recompile every module permits controlled source migrations; it
does not make an unrecompiled consumer binary compatible. See the changed
[capability model](../modules/core/src/main/scala/spoonbill/auth/BrowserConfiguration.scala)
and the prior [major-version migration precedent](migration-3.0.md).

## Consumer correctness evidence

An independent consumer resolves the locally published modules together and
compiles its application and acceptance fixtures against those artifacts. Its
focused host-transaction checks passed, including password preparation, rollback
after host writes, and cross-provider recovery after a lost commit acknowledgement.
They retain the existing host password/factor policy and SQL transactions.

Separate-process Chromium and WebKit testing has passed initial HTML on one process and
the first socket on another, unknown-view refusal, original-ceremony recovery,
acknowledged presentation restoration, repeated handoff, pause/resume of the old
owner, graceful shutdown, abrupt owner loss after a committed business operation,
and storage-failure fencing. Both engines passed all 13 scenarios, including
incompatible-schema preservation and subsequent compatible restoration,
revocation during handoff, factor-challenge/delivery recovery, view-capacity
refusal, and logout. The eight resolved snapshot library JAR hashes remained
unchanged throughout the completed validation.

The first incompatible-schema assertion observed a `ViewSnapshotException` but
relied on the framework reporter receiving setup errors. That instrumentation
assumption was incorrect. The consumer now observes the exact
`SchemaIncompatible` exception at projection initialization and rethrows it
unchanged. Its preservation/recovery assertions remained in place and passed
in both engines. This was a test observation issue, not evidence that the
snapshot silently discarded data.

## Remaining release acceptance

The [shared-mode validation report](clustered-browser-authentication-validation.md)
explicitly contains no latency, allocation, or retained-heap acceptance result.
Protected output admission adds database transactions, and the provider serializes
transactions within a realm/namespace. This establishes additional work, not its
measured impact on an equivalent application workload.

Before claiming the requested performance bar, measure equivalent protected events,
reconnect/handoff, and sustained churn against fixed baselines with a declared
uncertainty budget. Report tail latency, throughput, allocations, retained heap,
and database/connection counts. Keep existing historical baseline pins unchanged.

Retained view/history limits and the absence of abandoned-view reclamation are
documented operational constraints. A same-view handoff pass must not be presented
as evidence that indefinitely many abandoned distinct views are reclaimed. Choose
and document the supported capacity/retirement policy without deleting replay
fences or reviving stale owners.

The feature is not yet approved for release on the basis of this feedback.
