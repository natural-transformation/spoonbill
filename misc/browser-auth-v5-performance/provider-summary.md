# Bounded provider performance diagnostic

**Overall: inconclusive. Full zero-regression acceptance is not established.**

Provider-level measurements cannot establish browser or whole-release zero-regression readiness. All missing browser and full-matrix evidence remains unresolved.

Positive paired change means worse. Per-process quantiles are not pooled. All intervals below are exploratory.

| Provider | Metric | Baseline process mean | Candidate process mean | Paired change | Adjusted interval | A/A usable | Status |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| memory | protected-operation-p50 (ns) | 6,027.83 | 5,937.67 | -0.696% | -15.902% to 13.058% | no | inconclusive-collection-integrity |
| memory | protected-operation-p95 (ns) | 11,145.5 | 10,930.7 | -1.791% | -7.58% to 6.009% | no | inconclusive-collection-integrity |
| memory | protected-operation-p99 (ns) | 14,389.2 | 13,951.2 | -3.039% | -9.383% to 3.67% | no | inconclusive-collection-integrity |
| memory | protected-operation-throughput (operations/s) | 144,724 | 149,479 | -3.284% | -6.646% to 0.047% | no | inconclusive-collection-integrity |
| memory | protected-operation-allocation (bytes/operation) | 7,941.02 | 7,907.62 | -0.377% | -4.802% to 3.933% | numerically yes; 2 pairs | inconclusive-collection-integrity |
| memory | same-view-reconnect-p50 (ns) | 8,576.33 | 8,444.5 | -1.474% | -7.409% to 4.321% | no | inconclusive-collection-integrity |
| memory | same-view-reconnect-p95 (ns) | 14,187.5 | 13,632 | -3.601% | -12.563% to 2.146% | no | inconclusive-collection-integrity |
| memory | same-view-reconnect-p99 (ns) | 21,076.2 | 21,437.3 | 2.047% | -13.429% to 19.948% | no | inconclusive-collection-integrity |
| memory | same-view-reconnect-throughput (operations/s) | 107,135 | 109,501 | -2.312% | -6.768% to 1.76% | no | inconclusive-collection-integrity |
| memory | same-view-reconnect-allocation (bytes/operation) | 5,975.8 | 5,955.92 | -0.332% | -0.994% to 0.004% | numerically yes; 2 pairs | inconclusive-collection-integrity |
| memory | distinct-view-retained-heap (bytes) | 5,525,600 | 5,538,340 | 0.231% | 0.064% to 0.449% | numerically yes; 2 pairs | inconclusive-collection-integrity |
| jdbc | protected-operation-p50 (ns) | 6,748,310 | 6,728,570 | -0.292% | -3.215% to 4.116% | no | inconclusive-collection-integrity |
| jdbc | protected-operation-p95 (ns) | 7,856,420 | 7,765,560 | -0.994% | -6.59% to 7.811% | no | inconclusive-collection-integrity |
| jdbc | protected-operation-p99 (ns) | 8,769,330 | 8,314,310 | -4.032% | -19.867% to 5.111% | no | inconclusive-collection-integrity |
| jdbc | protected-operation-throughput (operations/s) | 145.74 | 146.81 | -0.747% | -2.31% to 3.984% | no | inconclusive-collection-integrity |
| jdbc | protected-operation-allocation (bytes/operation) | 339,717 (partial 2/3) | 339,787 (partial 2/3) | unavailable | unavailable | unavailable | inconclusive-missing-observations |
| jdbc | same-view-reconnect-p50 (ns) | 11,909,400 | 11,803,900 | -0.873% | -2.929% to -0.109% | no | inconclusive-collection-integrity |
| jdbc | same-view-reconnect-p95 (ns) | 13,682,400 | 13,281,700 | -2.545% | -10.957% to 2.973% | no | inconclusive-collection-integrity |
| jdbc | same-view-reconnect-p99 (ns) | 14,389,200 | 13,936,800 | -2.413% | -15.582% to 6.73% | no | inconclusive-collection-integrity |
| jdbc | same-view-reconnect-throughput (operations/s) | 82.721 | 83.717 | -1.243% | -3.159% to 0.753% | no | inconclusive-collection-integrity |
| jdbc | same-view-reconnect-allocation (bytes/operation) | 630,907 (partial 2/3) | 630,878 (partial 2/3) | unavailable | unavailable | unavailable | inconclusive-missing-observations |
| jdbc | distinct-view-retained-heap (bytes) | 7,503,920 (partial 2/3) | 7,527,120 (partial 2/3) | unavailable | unavailable | unavailable | inconclusive-missing-observations |

## Integrity and browser evidence

Provider observations: 118/124.

- 6 planned workload observations are missing.
- Process incomplete or failed at run record 51: deadline-stopped.
- 2 planned process completions are absent.
- browser-results.jsonl: 0 numeric samples; complete=false.
  - Line 2: page.evaluate: Error: Increment acknowledgement timeout
- browser-results-attempt2.jsonl: 0 numeric samples; complete=false.
  - Line 2: page.evaluate: Error: Increment acknowledgement timeout: expected 2, observed 1
- browser-results-attempt3.jsonl: 0 numeric samples; complete=false.
  - Line 2: page.evaluate: Error: Increment acknowledgement timeout: expected 1, observed 1, protocol acknowledgements 0

## Limits

- Six latency and three memory comparison pairs, with two A/A pairs per provider. These percentile-bootstrap intervals are exploratory, not validated population confidence bounds.
- Matched public provider API calls with synthetic fixed time and serial awaits, not end-to-end HTTP, WebSocket, DOM, authentication form, or physical-paint measurements.
- Protected work includes transaction, exact counter verification and authorize; reconnect includes claim, old release, old denial and current authorization.
- Achieved serial throughput is not saturation throughput. No concurrent clients or shared multi-node topology were measured.
- JDBC exclusive ownership is the common v4/v5 topology. Measurements cannot prove the new shared ownership path has no regression.
- JDBC uses a local test PostgreSQL instance with newly opened physical connections, not a production pool or realistic network latency. SQL counters count JDBC API executions, not wire round trips.
- The identical fixed clock exercises exclusive cached-clock behavior; checkpoint connection deltas are reported, but advancing-clock persistence work is not characterized.
- Memory is a separate invasive pass. JVM allocation and post-GC retained Java heap exclude native allocation, database server memory and browser memory.
- Distinct view records are deliberately retained: 500 for memory, 50 for JDBC, and matched within each provider. This is bounded growth, not leak freedom or post-close reclamation.
- Fixed warmups do not prove that JIT compilation and GC have stabilized; numerical A/A checks are reported without widening zero allowance.
- The historical 94-cell manifest and its fixed baseline IDs remain unchanged and unsatisfied. Missing metrics remain null.
- Browser fixture failures are preserved; no missing browser result is replaced by a favorable provider result.

Neither a favorable point estimate nor absence of a calibrated regression signal establishes release readiness.
