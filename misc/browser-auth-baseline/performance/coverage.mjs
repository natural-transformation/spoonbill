import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {completedWork, dimensions, identity, requiredMetrics, requiredOperationCounters, requiredResources} from './analyze.mjs';

// This is an implementation inventory, not evidence or an accepted work contract.
// A correctness test is a reusable scenario, never an executable performance pass.
const browser = 'misc/browser-auth-baseline/browser.test.cjs';
const restart = 'misc/browser-auth-baseline/browser-restart.test.cjs';
const memory = 'modules/spoonbill/src/test/scala/korolev/security/browserauthbaseline/MemoryBrowserAuthSpec.scala';
const jdbc = 'modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcReferenceHostSpec.scala';
const transport = 'misc/websocket-performance/StandaloneTransportBenchmark.scala';
const core = 'misc/websocket-performance/CoreGuardedSessionBenchmark.scala';
const profiles = {
  'unused-feature': {assets: [core], runner: 'public-core', gap: 'Add public-only fixture with authentication disabled; guarded lifecycle is not equivalent.'},
  'guarded-setup': {assets: [browser, core], runner: 'reference-browser', gap: 'Add fresh/existing/missing state setup and true cold-child launch loop; existing browser test only covers selected warm paths.'},
  login: {assets: [browser, memory, jdbc], runner: 'reference-browser', gap: 'Repeat independent eligible ceremonies using the configured proof cost, concurrency and original challenge; expose measured completion and transport counters.'},
  protected: {assets: [browser, memory, jdbc], runner: 'reference-host', gap: 'Add exact node population, policy rounds, lock-holder barrier and transactional result checks per operation.'},
  reconnect: {assets: [browser, memory, jdbc], runner: 'reference-browser', gap: 'Drive every named reconnect scenario with controlled ownership and clocks; retain original view identity.'},
  failure: {assets: [browser, restart, memory, jdbc], runner: 'reference-fault', gap: 'Add explicit fault scheduling and per-operation settlement fences; no arbitrary retries count as useful work.'},
  'node-read': {assets: ['modules/spoonbill/src/main/scala/korolev/state/StateManager.scala'], runner: 'node-manager', gap: 'Add direct object/serialized node-manager runners; single-node reads and whole snapshots require different useful-work checks.'},
  lifetime: {assets: [browser, restart, memory, jdbc], runner: 'reference-lifetime', gap: 'Add full configured capacity sweeps, cancellation barriers, churn and retained-state accounting across release/restart.'},
  'historical-transport': {assets: [transport], runner: 'legacy-collector.mjs', gap: 'Latency adapter only: fixture does not observe teardown resources, resolution probes, retained/peak heap or operation-pass counters.'},
  'historical-core': {assets: [core], runner: 'legacy-collector.mjs', gap: 'Latency adapter only: serial mock-allow guard is the historical core workload, not host authentication. Full resource, resolution, memory and operation-pass instrumentation remain.'},
  'pekko-transport': {assets: ['interop/pekko/src/main/scala/korolev/pekko/package.scala'], runner: 'pekko-echo', gap: 'Add real official-Pekko echo fixture; standalone transport results cannot be relabeled as Pekko.'},
  'reference-browser': {assets: [browser], runner: 'reference-browser', gap: 'Add repeated full real-cookie flow with server and browser measurements synchronized at actual rendered completion.'},
};
const allowedScenarios = {
  reconnect: ['retained', 'expired', 'takeover-old-owner-write', 'repeated', 'missing'],
  failure: ['logout-active', 'logout-pending', 'revoked-output', 'rollback', 'delivery-failure', 'commit-reconcile', 'cleanup-failure'],
  lifetime: ['idle', 'churn', 'capacity', 'acquisition-cancellation', 'expiry', 'shutdown', 'restart'],
};
const scenarios = {
  retained: 'Reconnect the original retained view and verify preserved authorized state.',
  expired: 'Expire the original authority and verify reconnect cannot recover privileged state.',
  'takeover-old-owner-write': 'Install the new owner, race the old owner write, and assert the old owner changes neither domain state nor audit.',
  repeated: 'Repeat reconnect on the same view, asserting exactly one active owner and no authority resurrection.',
  missing: 'Reconnect an unknown view and verify the explicit reload/denial policy without privileged state.',
  'logout-active': 'Revoke an active session, observe closed output, and reject subsequent guarded writes.',
  'logout-pending': 'Hold acquisition pending, logout, release acquisition, and assert no privileged guard is installed.',
  'revoked-output': 'Revoke between output preparation and delivery and verify sensitive output is not emitted.',
  rollback: 'Inject transaction rollback and assert no session/domain/audit mutation survives.',
  'delivery-failure': 'Fail delivery after commit, recover the original attempt, and verify one commit and matching terminal state.',
  'commit-reconcile': 'Inject unknown commit outcome, reconcile by the original ceremony, and verify no duplicate authorization or mutation.',
  'cleanup-failure': 'Inject cleanup failure and verify fail-closed authority, settled ownership and bounded retained metadata.',
  idle: 'Hold the configured population idle; count live connection/view ownership before release and retained state after quiescence.',
  churn: 'Complete the configured cycles and duration, await each disconnect settlement, then drain and count retained metadata.',
  capacity: 'For every configured global and per-browser capacity, exercise at-limit success and beyond-limit refusal without consuming another browser quota.',
  'acquisition-cancellation': 'Cancel while acquisition is held, release the acquisition, and verify cleanup occurs exactly once before completion.',
  expiry: 'Advance the controlled authority clock through every TTL, run owned retirement and prove old authority cannot revive on rollback or reconnect.',
  shutdown: 'Hold work, initiate shutdown, settle held work, and verify owned callbacks, transactions and workers finish before resource release.',
  restart: 'Commit durable authority, restart the actual JVM with the same database, recover only the matching original operation and preserve revocation/clock fences.',
};

export function usefulWork(cell, d) {
  if (!profiles[cell.group]) throw new Error(`Unmapped workload group: ${cell.group}`);
  if (allowedScenarios[cell.group] && !allowedScenarios[cell.group].includes(d.scenario))
    throw new Error(`Unmapped ${cell.group} scenario: ${d.scenario}`);
  switch (cell.group) {
    case 'unused-feature': {
      const operations = {
        'public-startup': 'Start a fresh child server, bootstrap HTTP and consume the first public live frame.',
        'public-events': 'Send a typed public event and validate its rendered result.',
        'public-idle': 'Hold the configured public idle population, verify liveness, then release every owner.',
        'public-teardown': 'Start a public view, release it, and wait for input, guard and view settlement.',
      };
      if (!operations[cell.id] || d.featureEnabled !== false) throw new Error(`Unmapped public cell: ${cell.id}`);
      return [operations[cell.id], 'Authentication remains disabled; observe zero authentication SQL, proof, guard and client-code work.'];
    }
    case 'guarded-setup': return [`Bootstrap ${d.auth} authority with a ${d.view} view in a ${d.temperature} server.`,
      'Verify expected public/protected rendering or explicit missing-view reload; a missing view cannot be counted as a successful protected setup.',
      'Cold observations launch a new server per operation; warm observations reuse the warmed server.'];
    case 'login': return [`Complete ${d.flow} using ${d.proof} proof policy at concurrency ${d.concurrency}.`,
      'Use a fresh eligible browser/account scope per operation; assert the accepted session cookie, protected HTTP access and live guarded render.',
      'Factor submission and recovery retain the original ceremony/challenge; count one durable completion, never a replacement attempt.'];
    case 'protected': return [`Complete one authorized ${d.operation} over ${d.nodeCount} nodes with ${d.policy} policy and ${d.contention} contention.`,
      'Await matching rendered state for an event, or matching committed domain/audit result for a write; assert denial leaves state unchanged.',
      'For controlled contention admit all workers before releasing the dedicated lock holder; count actual lock wait separately.'];
    case 'reconnect': case 'failure': case 'lifetime': return [scenarios[d.scenario],
      'Completion includes settlement barriers and observed resource ownership, not merely request initiation.'];
    case 'node-read': return [`Populate ${d.nodeCount} ${d.manager} nodes of exactly ${d.valueBytes} value bytes.`,
      d.operation === 'read' ? 'Invoke read for one designated node and validate its bytes; observe whether the actual implementation traverses or copies the whole map.' :
        'Materialize a whole snapshot, verify every populated node and prove later writes/deletes do not change the snapshot.',
      'Observe traversal/copy counters inside the actual manager; infer neither from requested size.'];
    case 'historical-transport': case 'pekko-transport': return [`Use ${d.concurrency} real loopback WebSocket connections with ${d.payloadBytes} byte binary payloads.`,
      'Each warmup and measured exchange verifies HTTP upgrade, frame shape, exact payload length and bytes before incrementing completion.',
      'Measure exactly the configured total messages across connections; never multiply completed-work counts twice.'];
    case 'historical-core': return ['Create the real guarded core session, consume a nonempty live initial frame, release the input and await guard close.',
      'After every operation assert no application, guard or input owner remains; opened and closed totals equal warmup plus measured operations.'];
    case 'reference-browser': return [`Run the reference in real ${d.browser} with real cookies and official transport.`,
      'Complete password and original-ceremony factor login, guarded action/render, retained-view reconnect and POST logout; verify protected HTTP authority before and after logout.',
      'Count only completed rendered workflows; pin browser/server transport accounting and client bundle inventory.'];
  }
}

export function coverage(manifest) {
  const seen = new Set();
  return manifest.cells.map(cell => {
    if (seen.has(cell.id)) throw new Error(`Duplicate cell: ${cell.id}`);
    seen.add(cell.id);
    const d = dimensions(manifest, cell), assertions = usefulWork(cell, d), profile = profiles[cell.group];
    return {id: cell.id, baseline: cell.baseline, dimensions: d, runner: profile.runner, reusableAssets: profile.assets,
      usefulWorkAssertions: assertions, missingImplementation: profile.gap,
      passes: Object.fromEntries(manifest.protocol.passes.map(pass => [pass, {
        status: pass === 'latency' && ['historical-core', 'historical-transport'].includes(cell.group) ? 'partial-observation-adapter' : 'unimplemented',
        work: completedWork(manifest, cell, pass),
        requiredMetrics: requiredMetrics(manifest, cell).filter(name => manifest.metricDefinitions[name].pass === pass),
        missingCounters: pass === 'operations' ? [...requiredOperationCounters] : [],
        missingResourceAccounting: [...requiredResources],
        measurementResolutionValidated: false,
      }]))};
  });
}

export function collectionCost(manifest) {
  const rows = coverage(manifest), perCellPass = manifest.protocol.pairsPerMode * 2;
  if (!Number.isSafeInteger(perCellPass) || perCellPass <= 0) throw new Error('Invalid pair count');
  let warmup = 0, measured = 0, coldServerLaunches = 0;
  for (const cell of manifest.cells) for (const pass of manifest.protocol.passes) {
    const work = completedWork(manifest, cell, pass);
    for (const field of ['warmupOperations', 'measuredOperations'])
      if (!Number.isSafeInteger(work?.[field]) || work[field] < (field === 'measuredOperations' ? 1 : 0)) throw new Error(`Invalid work: ${cell.id}/${pass}/${field}`);
    warmup += work.warmupOperations * perCellPass;
    measured += work.measuredOperations * perCellPass;
    if (cell.id === 'public-startup' || dimensions(manifest, cell).temperature === 'cold')
      coldServerLaunches += work.measuredOperations * perCellPass;
  }
  const observations = rows.length * manifest.protocol.passes.length * perCellPass;
  const mandatoryMemoryQuiescenceSeconds = manifest.protocol.passes.includes('memory') ? rows.length * perCellPass * manifest.protocol.quiescenceSeconds : 0;
  return {schema: 'spoonbill-collection-cost-v1', estimateKind: 'configured-work-and-lower-bound-only', cells: rows.length,
    perMode: {observations, configuredWarmupOperations: warmup, configuredMeasuredOperations: measured,
      coldServerLaunches, mandatoryMemoryQuiescenceSeconds, maximumCollectionSeconds: manifest.protocol.maximumCollectionSeconds},
    allModes: {modes: manifest.protocol.modes.length, observations: observations * manifest.protocol.modes.length,
      mandatoryMemoryQuiescenceSeconds: mandatoryMemoryQuiescenceSeconds * manifest.protocol.modes.length},
    elapsedTimeEstimateSeconds: null, computeHoursEstimate: null,
    exclusions: ['server/browser startup', 'actual proof and policy cost', 'operation execution', 'full GC and quiescence barriers',
      'historical artifact builds', 'sensor calibration', 'failed/inconclusive runs', 'statistical analysis'],
    nextStep: 'Measure representative baseline-only pilot process duration on the selected host before scheduling the full matrix; retain pilots separately from frozen evidence.'};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.argv[2] ?? new URL('./manifest.json', import.meta.url);
  const manifest = JSON.parse(readFileSync(file, 'utf8'));
  console.log(JSON.stringify({manifestSha256: identity(manifest), acceptanceReady: false,
    cost: collectionCost(manifest), cells: coverage(manifest)}, null, 2));
}
