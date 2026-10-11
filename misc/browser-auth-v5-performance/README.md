# Bounded v4/v5 performance evidence

See [the report](../../docs/browser-auth-v5-performance-one-hour.md) for the result
and limitations. The overall result is **inconclusive**, not a release gate pass.

- `browser-protocol.json`: original browser protocol, retained after all three
  collection attempts failed during warmup. It describes planned measurements,
  not completed measurements.
- `jvm-protocol.json`: the separate frozen provider protocol, including exact
  runtime hashes and planned process order. Absolute `/tmp` paths identify the
  original local artifacts; JAR binaries are not committed.
- `provider-results.jsonl` / `provider-runs.jsonl`: unchanged successful workload
  rows and process records, including the explicit deadline stop.
- `provider-summary.json` / `.md`: analysis, including sanitized browser failure
  summaries and input hashes. Missing observations are not replaced with zeros.
- `fixture/`: exact common Scala probe and the two version-specific adapters.
  The probe uses the repository's existing
  `misc/performance-support/scala/spoonbill/performance/PerformanceProbe.scala`.
- `analyze-provider.mjs`: analysis source. It verifies frozen local JARs before
  analysis, so rerunning requires the original artifact directory. It deliberately
  exits 1 when evidence is incomplete or the gate is inconclusive.

This directory is an experiment record, not a new CI benchmark framework. The
historical baseline manifest is unchanged. Do not treat these provider-only
results as browser, coordinator or shared-ownership performance acceptance.
