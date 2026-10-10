import {readFileSync} from 'node:fs';
import {completedWork, identity, requiredOperationCounters, requiredResources} from './analyze.mjs';

const proposed = JSON.parse(readFileSync(new URL('./manifest.json', import.meta.url), 'utf8'));
const sha = character => character.repeat(64);

// A small reviewed SYNTHETIC contract tests the validator, not the real reference.
export function syntheticFixture() {
  const manifest = structuredClone(proposed);
  manifest.status = 'frozen';
  manifest.sourceReview = {referenceReviewed: true, reviewedBy: 'synthetic test', reviewedAt: '2026-10-10'};
  manifest.unresolved = [];
  for (const source of Object.values(manifest.baselines)) source.referenceIntegrationSha256 = sha('a');
  manifest.cells = [manifest.cells[0]];
  const cell = manifest.cells[0];
  cell.completedWorkContractSha256 = sha('b');
  cell.metrics = ['latencyP50Ns', 'operationsPerSecond', 'allocatedBytesPerOperation'];
  cell.operationCeilings = {...Object.fromEntries(requiredOperationCounters.map(name => [name, 0])), sqlExecutionsPerOperation: 2};
  cell.resourceCeilings = {...Object.fromEntries(requiredResources.map(name => [name, 0])), retainedMetadataEntriesAfterQuiescence: 4};
  manifest.protocol.familySize = cell.metrics.length;
  manifest.protocol.bootstrapReplicates = 20000;
  const source = gitCommit => ({gitCommit, dirtyDiffSha256: sha('c'), artifactSha256: sha('d'),
    referenceIntegrationSha256: sha('a'), flakeSha256: sha('e'), flakeLockSha256: sha('f'), dependencyGraphSha256: sha('a')});
  const provenance = {baselines: Object.fromEntries(Object.entries(manifest.baselines).map(([name, value]) => [name, source(value.gitCommit)])),
    candidate: {...source('c'.repeat(40)), artifactSha256: sha('e')}};
  const shared = {fixtureSha256: sha('b'), runnerSha256: sha('c'), analyzerSha256: sha('d'), referenceDataSha256: sha('e'),
    clientInventorySha256: sha('f'), measurementConfigSha256: sha('a'), os: {name: 'Synthetic', version: '1', arch: 'test'},
    hardware: {machineId: 'isolated-test', cpu: 'synthetic', cores: 8, memoryBytes: 1024},
    jvm: {vendor: 'synthetic', version: '21', gc: 'G1', flags: ['-Xmx512m'], maxHeapBytes: 512 * 1024 * 1024},
    database: {engine: 'PostgreSQL', version: '14', configSha256: sha('b')}, browsers: {chromium: 'synthetic', webkit: 'synthetic'}};
  function evidence(mode) {
    const samples = [];
    for (const pass of manifest.protocol.passes) for (let block = 0; block < 30; block++)
      for (const variant of ['baseline', 'candidate']) {
        const candidate = mode === 'comparison' && variant === 'candidate';
        samples.push({kind: 'sample', cell: cell.id, pass, block, variant, processId: `${mode}-${pass}-${block}-${variant}`,
          artifactSha256: candidate ? provenance.candidate.artifactSha256 : provenance.baselines[cell.baseline].artifactSha256,
          completedWorkContractSha256: cell.completedWorkContractSha256, completedWorkVerified: true,
          warmupOperations: completedWork(manifest, cell, pass).warmupOperations, completedOperations: completedWork(manifest, cell, pass).measuredOperations,
          orderInBlock: variant === 'baseline' ? block % 2 : 1 - block % 2,
          durationSeconds: 1, profilingEnabled: pass === 'memory', clockResolutionNs: 1,
          measurementResolutionVerified: true, measuredResolutions: Object.fromEntries(cell.metrics.map(name => [name, manifest.metricDefinitions[name].resolution])),
          diagnostics: {jitCompilationMilliseconds: 0, gcCount: 0, gcMilliseconds: 0},
          resources: {...structuredClone(cell.resourceCeilings), retainedMetadataEntriesAfterQuiescence: 3},
          counters: structuredClone(cell.operationCeilings),
          metrics: {latencyP50Ns: candidate ? 80 : 100, operationsPerSecond: candidate ? 120 : 100,
            allocatedBytesPerOperation: candidate ? 80 : 100}});
      }
    return {metadata: {kind: 'metadata', mode, manifestSha256: identity(manifest), provenance: structuredClone(provenance),
      shared: structuredClone(shared), startedAt: '2026-10-10T00:00:00Z', durationSeconds: 180}, samples,
      completion: {kind: 'completion', observations: samples.length}};
  }
  return {manifest, comparison: evidence('comparison'), calibration: evidence('calibration')};
}
