import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import {collectionCost, coverage, usefulWork} from './coverage.mjs';

const manifest = JSON.parse(readFileSync(new URL('./manifest.json', import.meta.url), 'utf8'));
const repo = fileURLToPath(new URL('../../../', import.meta.url));

test('every required cell and pass has an explicit implementation boundary and useful-work assertions', () => {
  const rows = coverage(manifest);
  assert.equal(rows.length, 94);
  assert.deepEqual(rows.map(row => row.id), manifest.cells.map(cell => cell.id));
  for (const row of rows) {
    assert.ok(row.usefulWorkAssertions.length >= 2, row.id);
    assert.ok(row.missingImplementation, row.id);
    assert.deepEqual(Object.keys(row.passes), manifest.protocol.passes);
    for (const pass of Object.values(row.passes)) {
      assert.notEqual(pass.status, 'complete');
      assert.equal(pass.measurementResolutionValidated, false);
      assert.ok(pass.missingResourceAccounting.length > 0);
    }
    for (const asset of row.reusableAssets) assert.ok(existsSync(resolve(repo, asset)), asset);
  }
  assert.equal(rows.filter(row => row.passes.latency.status === 'partial-observation-adapter').length, 5);
});

test('unknown workloads/scenarios and duplicate cells cannot disappear from coverage', () => {
  assert.throws(() => coverage({...manifest, cells: [...manifest.cells, manifest.cells[0]]}), /Duplicate/);
  assert.throws(() => usefulWork({group: 'future'}, {}), /Unmapped/);
  assert.throws(() => usefulWork({group: 'failure'}, {scenario: 'unimplemented'}), /Unmapped/);
  assert.throws(() => usefulWork({group: 'unused-feature', id: 'public-startup'}, {featureEnabled: true}), /Unmapped/);
});

test('cost counts independent observations, all cold launches and mandatory quiescence without invented elapsed estimate', () => {
  const cost = collectionCost(manifest);
  assert.equal(cost.perMode.observations, 16920);
  assert.equal(cost.allModes.observations, 33840);
  assert.equal(cost.perMode.coldServerLaunches, 42840);
  assert.equal(cost.perMode.mandatoryMemoryQuiescenceSeconds / 3600, 47);
  assert.equal(cost.allModes.mandatoryMemoryQuiescenceSeconds / 3600, 94);
  assert.equal(cost.elapsedTimeEstimateSeconds, null);
  assert.equal(cost.computeHoursEstimate, null);
  const modified = structuredClone(manifest);
  modified.cells[0].passes.latency.measuredOperations = null;
  assert.throws(() => collectionCost(modified), /Invalid work/);
});

test('single-node read contract cannot substitute full snapshot work', () => {
  const read = coverage(manifest).find(row => row.id === 'node-object-n10000-v4096-read');
  const snapshot = coverage(manifest).find(row => row.id === 'node-object-n10000-v4096-snapshot');
  assert.match(read.usefulWorkAssertions[1], /one designated node/);
  assert.match(snapshot.usefulWorkAssertions[1], /every populated node/);
});
