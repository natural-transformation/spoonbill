// The recovery plan's healthy commit budget, derived from /bench-stats deltas.
// Usage: node budget.mjs <mode> <statsBefore> <clientResult> <statsAfter> <users> <clicksPerUser> <tripsBefore> <tripsAfter>
//
// The client (BENCH_PHASES=1) recorded /bench-stats and the proxy's round-trip
// count once every user was open, so startup (sign-in, page load, session open
// and the initial publication of the empty view) is reported apart from the
// steady-state saves of the clicks. The budget is asserted exactly: one
// snapshot-provider transaction per inline save; two (reserve, publish), one
// payload put and no status lookup per reference-backed save.
const [mode, beforeArg, resultArg, afterArg, usersArg, perUserArg, tripsBeforeArg, tripsAfterArg] = process.argv.slice(2);
const before = JSON.parse(beforeArg), result = JSON.parse(resultArg), after = JSON.parse(afterArg);
const users = Number(usersArg), clicksPerUser = Number(perUserArg);
const open = result.afterOpen;
if (!open) { console.log(JSON.stringify({ ok: false, violations: [{ name: 'phase boundary', actual: null, expected: 'afterOpen' }] })); process.exit(0); }
const reference = mode === 'jdbc-reference';
const expectedSaves = reference ? 2 : 1;

const at = (stats, ...path) => path.reduce((value, key) => (value == null ? undefined : value[key]), stats);
const delta = (from, to, ...path) => {
  const a = at(from, ...path), b = at(to, ...path);
  return a == null || b == null ? null : b - a;
};
const window = (from, to) => ({
  sessions: delta(from, to, 'snapshots', 'initializations'),
  commits: delta(from, to, 'snapshots', 'commits'),
  failures: delta(from, to, 'snapshots', 'failures'),
  saveTransactions: delta(from, to, 'transactions', 'snapshotSave'),
  readTransactions: delta(from, to, 'transactions', 'snapshotRead'),
  cleanupTransactions: delta(from, to, 'transactions', 'cleanup'),
  clockTransactions: delta(from, to, 'transactions', 'clock'),
  authorityTransactions: delta(from, to, 'transactions', 'authority'),
  rollbacks: delta(from, to, 'transactions', 'rollbacks'),
  saveStatements: delta(from, to, 'transactions', 'saveStatements'),
  reservations: delta(from, to, 'recovery', 'reservations'),
  publications: delta(from, to, 'recovery', 'publications'),
  statusLookups: delta(from, to, 'recovery', 'statusLookups'),
  uploads: delta(from, to, 'recovery', 'uploads'),
  downloads: delta(from, to, 'recovery', 'downloads'),
  deletes: delta(from, to, 'recovery', 'deletes'),
  puts: delta(from, to, 'payloads', 'puts'),
  putBytes: delta(from, to, 'payloads', 'putBytes'),
  gets: delta(from, to, 'payloads', 'gets'),
});
const ratio = (value, by) => (value == null || !by ? null : Math.round(value / by * 1000) / 1000);

const startup = window(before, open.stats);
startup.roundTrips = open.roundTrips == null ? null : open.roundTrips - Number(tripsBeforeArg);
startup.openMs = result.openMs;
startup.initializeMs = at(open.stats, 'snapshots', 'initializeMs');
const perSession = {
  saveTransactions: ratio(startup.saveTransactions, startup.sessions),
  readTransactions: ratio(startup.readTransactions, startup.sessions),
  authorityTransactions: ratio(startup.authorityTransactions, startup.sessions),
  roundTrips: ratio(startup.roundTrips, startup.sessions),
  uploads: ratio(startup.uploads, startup.sessions),
};

const steady = window(open.stats, after);
steady.roundTrips = open.roundTrips == null ? null : Number(tripsAfterArg) - open.roundTrips;
steady.restores = result.restores ?? 0;
steady.restoreMs = result.restoreMs;
steady.commitMs = at(after, 'snapshots', 'commitMs');
const clicksExpected = users * clicksPerUser;
const perClick = {
  commits: ratio(steady.commits, clicksExpected),
  saveTransactions: ratio(steady.saveTransactions, steady.commits),
  readTransactions: ratio(steady.readTransactions, steady.commits),
  authorityTransactions: ratio(steady.authorityTransactions, steady.commits),
  clockTransactions: ratio(steady.clockTransactions, steady.commits),
  roundTrips: ratio(steady.roundTrips, clicksExpected),
  saveStatements: ratio(steady.saveStatements, steady.saveTransactions),
  reservations: ratio(steady.reservations, steady.commits),
  publications: ratio(steady.publications, steady.commits),
  uploads: ratio(steady.uploads, steady.commits),
  statusLookups: ratio(steady.statusLookups, steady.commits),
  payloadBytes: ratio(steady.putBytes, steady.puts),
};

// Exact counts, not ratios: one extra transaction anywhere must fail the run.
const checks = [
  ['steady.saveTransactions', steady.saveTransactions, expectedSaves * steady.commits],
  ['steady.commits', steady.commits, clicksExpected],
  ['steady.failures', steady.failures, 0],
  ['startup.sessions', startup.sessions, users],
  ['startup.saveTransactions', startup.saveTransactions, expectedSaves * startup.sessions],
];
if (reference) checks.push(
  ['steady.reservations', steady.reservations, steady.commits],
  ['steady.publications', steady.publications, steady.commits],
  ['steady.uploads', steady.uploads, steady.commits],
  ['steady.statusLookups', steady.statusLookups, 0],
  ['startup.uploads', startup.uploads, startup.sessions],
);
const violations = checks.filter(([, actual, expected]) => actual !== expected)
  .map(([name, actual, expected]) => ({ name, actual, expected }));
console.log(JSON.stringify({ ok: violations.length === 0, expectedSaveTransactionsPerCommit: expectedSaves, violations,
  startup, perSession, steady, perClick }));
