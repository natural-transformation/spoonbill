// The jdbc-shared click phase, from the server's counters at the warmup
// boundary and after the measured clicks' outstanding work drained.
// Usage: node shared-report.mjs <clientResult> <users> <clicksPerUser>
//
// Provider transactions are the work pool's committed transactions. In steady
// state every one of them observes the view's authority once: a check, an
// output admission or a snapshot save. Clock transactions are the separate
// clock pool's checkpoints. Round trips are the proxy's Sync and Query count
// for every pool. Per-click values divide by the completed measured clicks.
const [resultArg, usersArg, clicksArg] = process.argv.slice(2);
const result = JSON.parse(resultArg);
const users = Number(usersArg), clicksPerUser = Number(clicksArg);
const from = result.afterWarmup, to = result.afterDrain;
if (!from || !to) {
  console.log(JSON.stringify({ ok: false, problems: ['missing the warmup or drain boundary'] }));
  process.exit(0);
}

const at = (stats, ...path) => path.reduce((value, key) => (value == null ? undefined : value[key]), stats);
const delta = (...path) => {
  const a = at(from.stats, ...path), b = at(to.stats, ...path);
  return a == null || b == null ? null : b - a;
};
const sum = values => values.reduce((total, value) => total + (value ?? 0), 0);

const window = {
  providerTransactions: sum(['authority', 'snapshotSave', 'snapshotRead', 'cleanup', 'clock'].map(kind => delta('work', kind))),
  snapshotSaves: delta('work', 'snapshotSave'),
  clockTransactions: delta('clock', 'clock'),
  // Retention reservations also use the clock pool; none are expected while clicking.
  otherClockPoolTransactions: delta('clock', 'authority'),
  hostResolves: delta('hostResolves'),
  rollbacks: sum([delta('work', 'rollbacks'), delta('clock', 'rollbacks')]),
  maintenanceRuns: delta('maintenanceRuns'),
  roundTrips: from.roundTrips == null || to.roundTrips == null ? null : to.roundTrips - from.roundTrips,
};
const completed = result.samples;
const perClick = Object.fromEntries(Object.entries(window).map(([name, value]) =>
  [name, value == null || !completed ? null : Math.round(value / completed * 1000) / 1000]));
const pick = summary => summary && { p50: summary.p50, p95: summary.p95, p99: summary.p99 };
const problems = [
  completed !== users * clicksPerUser && `completed ${completed} of ${users * clicksPerUser} clicks`,
  window.rollbacks !== 0 && `${window.rollbacks} rolled-back transactions`,
  result.reconnects !== 0 && `${result.reconnects} reconnects`,
  !to.drained && 'the counters did not settle after the measured clicks',
].filter(Boolean);
console.log(JSON.stringify({
  ok: problems.length === 0, problems, users, clicksPerUser, completedClicks: completed,
  measuredSeconds: result.measuredSeconds, clicksPerSecond: result.measuredClicksPerSecond,
  patchMs: pick(result.patchMs), readyMs: pick(result.readyMs), perClick, window,
  failures: window.rollbacks, reconnects: result.reconnects, drainMs: to.drainMs,
  coordinator: at(to.stats, 'coordinator'),
}));
