// The jdbc-shared verdict and click report. Run: node --test misc/event-latency/client/shared-verdict.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = name => fileURLToPath(new URL(name, import.meta.url));
const node = (name, ...args) => spawnSync(process.execPath, [script(name), ...args], { encoding: 'utf8' });
const verdict = (probe, report) => node('shared-verdict.mjs', JSON.stringify(probe), JSON.stringify(report));

test('a passing probe and click phase pass the run', () => {
  assert.equal(verdict({ ok: true }, { ok: true, problems: [] }).status, 0);
});

test('a click phase with a reconnect fails the run, also when the probe passed', () => {
  const run = verdict({ ok: true }, { ok: false, problems: ['1 reconnects'] });
  assert.equal(run.status, 1);
  assert.match(run.stderr, /click phase failed: \["1 reconnects"\]/);
});

test('a missed observation budget fails the run', () => {
  const run = verdict({ ok: false, violations: ['call 3: 16 statements, over the budget of 15'] }, { ok: true });
  assert.equal(run.status, 1);
  assert.match(run.stderr, /observation budget not met/);
});

test('a missing probe or report fails the run', () => {
  assert.equal(node('shared-verdict.mjs', 'null', '').status, 1);
});

// One measured click between the warmup boundary and the drained counters.
function result(snapshots, projection, saves, reconnects = 0) {
  const stats = snapshotSave => ({
    switches: { snapshots, projection }, hostResolves: 0, maintenanceRuns: 0,
    work: { authority: 0, snapshotSave, snapshotRead: 0, cleanup: 0, clock: 0, rollbacks: 0 },
    clock: { clock: 0, authority: 0, rollbacks: 0 },
  });
  return JSON.stringify({
    samples: 1, reconnects, measuredSeconds: 1, measuredClicksPerSecond: 1,
    afterWarmup: { stats: stats(0), roundTrips: 0 },
    afterDrain: { stats: stats(saves), roundTrips: 0, drained: true, drainMs: 0 },
  });
}
const report = (...args) => JSON.parse(node('shared-report.mjs', result(...args), '1', '1').stdout);

test('the click report expects no save for a warmed constant projection with guarded snapshots', () => {
  assert.equal(report('guarded', 'constant', 0).ok, true);
  const lost = report('guarded', 'constant', 1);
  assert.equal(lost.ok, false);
  assert.match(lost.problems.join(), /1 snapshot saves for 1 clicks, expected 0/);
});

test('the click report expects one save per click for a changing projection or ordinary snapshots', () => {
  assert.equal(report('guarded', 'changing', 1).ok, true);
  assert.equal(report('ordinary', 'constant', 1).ok, true);
  assert.equal(report('guarded', 'changing', 0).ok, false);
});

test('the click report fails a reconnect', () => {
  const reconnected = report('guarded', 'constant', 0, 1);
  assert.equal(reconnected.ok, false);
  assert.match(reconnected.problems.join(), /1 reconnects/);
});
