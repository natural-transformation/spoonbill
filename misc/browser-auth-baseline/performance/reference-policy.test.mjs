import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {policyFields, policyIssues} from './reference-policy.mjs';

const manifest = JSON.parse(readFileSync(new URL('./manifest.json', import.meta.url)));
const expectedReport = () => Object.fromEntries(['short', 'representative'].map(name => [name, {
  ...Object.fromEntries(Object.entries(policyFields).map(([field, location]) => [field,
    location.split('.').reduce((value, key) => value[key], manifest.protocol.workload)])),
  proof: {...manifest.protocol.workload[`${name}Proof`]},
}]));

test('both proof profiles must match every declared runtime field', () => {
  assert.deepEqual(policyIssues(manifest, expectedReport()), []);
  for (const field of Object.keys(policyFields)) {
    const report = expectedReport(); report.short[field]++;
    assert.match(policyIssues(manifest, report).join('\n'), new RegExp(`short\\.${field}`));
  }
});
test('unknown policy fields and missing profiles fail rather than hiding configuration drift', () => {
  const report = expectedReport(); report.representative.newPolicy = 1;
  assert.ok(policyIssues(manifest, report).length);
  delete report.short;
  assert.ok(policyIssues(manifest, report).length);
});
test('proof algorithms and all cost parameters are part of parity', () => {
  for (const field of ['algorithm', 'iterations', 'keyBits', 'saltBytes']) {
    const report = expectedReport(); report.representative.proof[field] = 'changed';
    assert.match(policyIssues(manifest, report).join('\n'), /representative\.proof/);
  }
});
