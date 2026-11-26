// The jdbc-shared run's verdict: both the observation probe and the click
// phase must pass. Prints every problem to stderr and exits 1 when one fails.
// Usage: node shared-verdict.mjs <probe JSON> <click report JSON>
const [probeArg, reportArg] = process.argv.slice(2);
const parse = text => { try { return JSON.parse(text); } catch { return null; } };
const probe = parse(probeArg), report = parse(reportArg);
const problems = [];
if (probe?.ok !== true)
  problems.push(`observation budget not met: ${JSON.stringify(probe?.violations ?? 'no probe verdict')}`);
if (report?.ok !== true)
  problems.push(`click phase failed: ${JSON.stringify(report?.problems ?? 'no click report')}`);
for (const problem of problems) console.error(problem);
process.exit(problems.length === 0 ? 0 : 1);
