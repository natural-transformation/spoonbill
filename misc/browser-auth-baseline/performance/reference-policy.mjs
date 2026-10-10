import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

// Every executable policy field has one explicit manifest location. New runtime
// fields must be reviewed here; silently ignoring them would hide policy drift.
export const policyFields = Object.freeze({
  sessionSeconds: 'sessionTtlSeconds', ceremonySeconds: 'ceremonyTtlSeconds',
  challengeSeconds: 'challengeTtlSeconds', deliverySeconds: 'deliveryTtlSeconds',
  reconnectSeconds: 'reconnectTtlSeconds', bootstrapSeconds: 'bootstrapTtlSeconds',
  operationAuthoritySeconds: 'operationAuthorityTtlSeconds',
  retainedEntries: 'maxRetainedEntriesPerKind', auditRecords: 'maxAuditRecords',
  retainedViews: 'maxRetainedViewEpochs',
  liveCeremonies: 'maxCeremonies', ceremoniesPerBinding: 'maxCeremoniesPerBrowser',
  activeViews: 'maxActiveViews', viewsPerBinding: 'maxViewsPerBrowser',
  disconnectedViews: 'maxDisconnectedViews', bootstrapEntries: 'maxBootstrapEntries',
  pendingJobs: 'maxPendingAcquisitions', nodesPerView: 'maxStoredNodesPerView',
  accountCount: 'accountCount', deliveryMaterials: 'maxDeliveryMaterials', rateScopes: 'maxRateScopes',
  proofAttempts: 'rateLimit.attempts', proofWindowSeconds: 'rateLimit.windowSeconds',
});
const expectedKeys = [...Object.keys(policyFields), 'proof'].sort();
const sameKeys = (value, keys) => value && !Array.isArray(value) &&
  JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort());

export function policyIssues(manifest, report) {
  if (!sameKeys(report, ['short', 'representative'])) return ['Both executable proof profiles are required.'];
  const issues = [], workload = manifest?.protocol?.workload;
  for (const name of ['short', 'representative']) {
    const policy = report[name];
    if (!sameKeys(policy, expectedKeys)) { issues.push(`${name}: executable policy field set changed.`); continue; }
    for (const [field, location] of Object.entries(policyFields)) {
      const expected = location.split('.').reduce((value, key) => value?.[key], workload);
      if (!Number.isSafeInteger(expected) || expected <= 0 || policy[field] !== expected)
        issues.push(`${name}.${field}: differs from manifest ${location}.`);
    }
    const expected = workload?.[`${name}Proof`];
    if (!sameKeys(policy.proof, ['algorithm', 'iterations', 'keyBits', 'saltBytes']) ||
        !sameKeys(expected, ['algorithm', 'iterations', 'keyBits', 'saltBytes']) ||
        Object.keys(expected ?? {}).some(key => policy.proof[key] !== expected[key]))
      issues.push(`${name}.proof: differs from the manifest proof algorithm or parameters.`);
  }
  return issues;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [manifestFile, reportFile] = process.argv.slice(2);
  if (!manifestFile || !reportFile || process.argv.length !== 4)
    throw new Error('Usage: node reference-policy.mjs manifest.json EXECUTABLE_POLICY_REPORT.json');
  const issues = policyIssues(JSON.parse(readFileSync(manifestFile, 'utf8')), JSON.parse(readFileSync(reportFile, 'utf8')));
  console.log(JSON.stringify({policyMatchesManifest: issues.length === 0, issues}, null, 2));
  process.exitCode = issues.length ? 1 : 0;
}
