# Native finalization and returned-resource ownership

These probes tested a proposed stronger pool handoff: treating a successful
native finalizer as proof that the caller must receive the returned resource.
The proposal was rejected. A finalizer observes the protected task's outcome;
that does not universally seal delivery to an outer caller.

The repository's pinned runtimes were used through `nix develop`: cats-effect
2.5.5, Monix 3.4.1, ZIO 1.0.18 and ZIO 2.1.27. No runtime adapter was changed for
these probes. Each probe starts a successful task, holds its finalizer, requests
cancellation, then releases the finalizer and observes the fiber/result.

| Runtime | Observed result | Interpretation |
| --- | --- | --- |
| cats-effect 2 | Finalizer sees Completed; cancellation waits for release; joined result remains absent. The uncanceled control returns 7. | Successful finalization does not guarantee caller delivery. |
| Monix | Same canceled versus uncanceled outcomes as cats-effect 2. | Same counterexample. |
| ZIO 2 | `onExit` receives Success(7); interruption during the held finalizer produces a final interrupted exit. | Same counterexample with an explicit final exit. |
| ZIO 1 | Initial probe reports success both times. | Diagnostic only: this probe did not establish that its asynchronous interruption request was processed before release. It is not evidence of a sealed-delivery guarantee. |

The first cats-effect 2/Monix log has a **missing** finalizer observation: the
child had not started before cancellation, so it says nothing about successful
finalization. It is retained as an invalid probe. The second log checks that the
finalizer has started. The final control log additionally proves that the same
fixture delivers the result without cancellation. Both final controls passed
for each backend. All logs are retained under `evidence/`; the source files here
are the final control/probe versions. Only trailing horizontal whitespace was
removed from the retained logs; `evidence/provenance.json` records both raw and
normalized log hashes and the tested source hashes.

The selected internal pool helper instead owns acquisition and the supplied
task until that task succeeds. Cancellation/error before that boundary cleans
up the loan. A returned stream/resource must then be bracketed or explicitly
adopted by its caller. The helper does not claim to solve arbitrary cancellation
between task success and caller adoption. It adds no Effect capability and does
not mask capacity waits or detach user work from its fiber context.

Run these diagnostics from the repository root:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set ce2 / Test / unmanagedSources += file("misc/effect-handoff-probes/Ce2FinalizerProbeSpec.scala")' \
  'set monix / Test / unmanagedSources += file("misc/effect-handoff-probes/MonixFinalizerProbeSpec.scala")' \
  'set zio / Test / unmanagedSources += file("misc/effect-handoff-probes/Zio1FinalizerProbeSpec.scala")' \
  'set zio2 / Test / unmanagedSources += file("misc/effect-handoff-probes/Zio2FinalizerProbeSpec.scala")' \
  'ce2/testOnly *Ce2FinalizerProbeSpec' 'monix/testOnly *MonixFinalizerProbeSpec' \
  'zio/testOnly *Zio1FinalizerProbeSpec' 'zio2/testOnly *Zio2FinalizerProbeSpec'
```

These are design diagnostics, not a portable Effect contract or performance
gate. Assertions in the ZIO probes establish finalizer entry; the logged final
exit is the observation being investigated.
