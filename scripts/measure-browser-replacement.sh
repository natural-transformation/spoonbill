#!/usr/bin/env bash
# Frozen bounded browser evidence. No installs, publication, commits, or live data.
set -euo pipefail
umask 077
if [[ -z "${IN_NIX_SHELL:-}" || -z "${SPOONBILL_GNU_TIME:-}" || -z "${PLAYWRIGHT_DRIVER_PATH:-}" ]]; then
  printf '%s\n' 'Use nix develop .#profiling --no-write-lock-file and the disposable PostgreSQL wrapper.' >&2
  exit 1
fi
if [[ -z "${SPOONBILL_JDBC_TEST_URL:-}" || $# -ne 1 || "$1" != /* ]]; then
  printf '%s\n' 'Usage: scripts/with-test-postgres.sh bash scripts/measure-browser-replacement.sh ABSOLUTE_NEW_OUTPUT_DIRECTORY' >&2
  exit 1
fi
node <<'NODE'
try {
  const url=new URL(process.env.SPOONBILL_JDBC_TEST_URL.replace(/^jdbc:/,''));
  if(url.protocol!=='postgresql:'||!['127.0.0.1','localhost'].includes(url.hostname)||!url.port||
    url.pathname!=='/postgres'||url.username||url.password||url.searchParams.get('user')!=='spoonbill_test')throw new Error();
}catch(_){console.error('Use the owned loopback database from scripts/with-test-postgres.sh');process.exit(1);}
NODE
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_root="$1"
if [[ -e "$output_root" ]]; then
  printf '%s\n' 'The measurement output directory must not already exist.' >&2
  exit 1
fi
mkdir -p -- "$output_root"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-replacement-measurement.XXXXXX")"
baseline="$scratch/baseline"
baseline_revision=7108eda8cef0d6d8ab838fe8c963f58cda810887
server_pid=''
wrapper_pid=''
stop_server() {
  if [[ -n "$server_pid" ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    for attempt in {1..150}; do
      if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
      sleep 0.1
    done
    kill -KILL "$server_pid" 2>/dev/null || true
    if [[ -n "$wrapper_pid" ]]; then wait "$wrapper_pid" 2>/dev/null || true; fi
  fi
  server_pid=''
  wrapper_pid=''
}
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  stop_server
  rm -rf -- "$scratch"
  printf '%s\n' "Measurement artifacts retained in $output_root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$repo_root"
cp -- "$repo_root/misc/replacement-performance-tests.cjs" "$output_root/harness.cjs"
cp -- "$repo_root/scripts/measure-browser-replacement.sh" "$output_root/collector.sh"
mkdir -p -- "$baseline"
git archive --format=tar "$baseline_revision" | tar -xf - -C "$baseline"
java -version > "$output_root/java-version.txt" 2>&1
node --version > "$output_root/node-version.txt"
psql --version > "$output_root/postgres-client-version.txt"
psql "${SPOONBILL_JDBC_TEST_URL#jdbc:}" -X -t -A -v ON_ERROR_STOP=1 \
  -c 'SHOW server_version' > "$output_root/postgres-server-version.txt"
"$SPOONBILL_GNU_TIME" --version > "$output_root/gnu-time-version.txt"
node > "$output_root/playwright-version.json" <<'NODE'
const fs=require('node:fs'),path=require('node:path');
const driver=process.env.PLAYWRIGHT_DRIVER_PATH;
let directory=path.dirname(require.resolve(driver)),found;
while(directory!==path.dirname(directory)){
  const file=path.join(directory,'package.json');
  if(fs.existsSync(file)){
    const value=JSON.parse(fs.readFileSync(file,'utf8'));
    if(typeof value.name==='string'&&value.name.includes('playwright')&&typeof value.version==='string'){
      found={name:value.name,version:value.version,driver};break;
    }
  }
  directory=path.dirname(directory);
}
if(!found)throw new Error('Cannot identify the Nix-provided Playwright package version');
process.stdout.write(JSON.stringify(found,null,2)+'\n');
NODE
node - "$repo_root" "$output_root/protocol.json" "$baseline_revision" <<'NODE'
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const [root, output, revision] = process.argv.slice(2);
const hash = crypto.createHash('sha256');
function visit(relative) {
  const file = path.join(root, relative), info = fs.lstatSync(file);
  if (info.isSymbolicLink()) { hash.update(relative); hash.update(fs.readlinkSync(file)); return; }
  if (info.isDirectory()) {
    for (const child of fs.readdirSync(file).sort()) if (!['target', 'node_modules', '.git'].includes(child)) visit(path.join(relative, child));
  } else if (/\.(scala|sbt|js|cjs|mjs|sh|nix|lock|conf|properties)$/.test(relative)) {
    hash.update(relative); hash.update('\0'); hash.update(fs.readFileSync(file)); hash.update('\0');
  }
}
for (const item of ['modules', 'interop', 'examples/browser-auth', 'misc/browser-auth-conformance', 'misc/replacement-performance-tests.cjs',
  'scripts/measure-browser-replacement.sh', 'project', 'build.sbt', 'flake.nix', 'flake.lock']) visit(item);
const metadata=path.dirname(output);
const version=file=>fs.readFileSync(path.join(metadata,file),'utf8').trim();
fs.writeFileSync(output, JSON.stringify({protocol: 'authenticated-replacement-browser-v1', frozen: '2026-10-16',
  recordedAt: new Date().toISOString(), baselineRevision: revision, candidateSourceSha256: hash.digest('hex'),
  runtime:{platform:process.platform,architecture:process.arch,node:version('node-version.txt'),
    java:version('java-version.txt'),postgresClient:version('postgres-client-version.txt'),
    postgresServer:version('postgres-server-version.txt'),gnuTime:version('gnu-time-version.txt').split(/\r?\n/)[0],
    playwright:JSON.parse(version('playwright-version.json'))},
  engine: 'chromium', providers: ['memory', 'jdbc'], ownership: 'exclusive', warmups: 5, samples: 20,
  order: ['baseline/ordinary', 'candidate/ordinary', 'candidate/replacement'],
  javaArgs: ['-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2'],
  ordinaryBudget: {medianMultiplier: 1.25, medianAllowanceMs: 25, p95Multiplier: 1.35, p95AllowanceMs: 50},
  statistics: 'median is midpoint of middle two observations; p95 is nearest-rank observation 19 of 20',
  timing: 'Node monotonic clock around real browser actions through fresh authenticated application readiness',
  limitations: ['single sequential process per lane; no confidence interval', 'browser end-to-end aggregate, not a provider stage profiler',
    'peak RSS is whole process; post-GC heap is not retained-secret or allocation attribution']}, null, 2) + '\n');
NODE

compile() {
  local directory="$1" label="$2"
  (
    cd "$directory"
    nix develop --no-write-lock-file --command sbt --batch --supershell=false -Dsbt.server.autostart=false \
      'browserAuthExample/compile' 'export browserAuthExample/Runtime/fullClasspath'
  ) > "$output_root/$label-compile.log" 2>&1 || {
    tail -n 80 "$output_root/$label-compile.log" >&2
    return 1
  }
  node - "$output_root/$label-compile.log" "$output_root/$label-classpath.txt" <<'NODE'
const fs = require('node:fs');
const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/)
  .filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected one compiled runtime classpath');
fs.writeFileSync(process.argv[3], lines[0] + '\n');
NODE
}
compile "$baseline" baseline
compile "$repo_root" candidate

heap_sample() {
  local destination="$1"
  if jcmd "$server_pid" GC.run > "$destination-gc.txt" 2>&1; then
    jcmd "$server_pid" GC.heap_info > "$destination-heap.txt" 2>&1 || true
  fi
}

host_sample() {
  node - "$1" <<'NODE'
const fs=require('node:fs'),os=require('node:os');
fs.writeFileSync(process.argv[2],JSON.stringify({recordedAt:new Date().toISOString(),
  loadAverage1m5m15m:os.loadavg(),logicalCpus:os.cpus().length,
  freeMemoryBytes:os.freemem(),totalMemoryBytes:os.totalmem()},null,2)+'\n');
NODE
}

for provider in memory jdbc; do
  for lane in baseline/ordinary candidate/ordinary candidate/replacement; do
    variant="${lane%%/*}"
    flow="${lane#*/}"
    lane_root="$output_root/$provider-$variant-$flow"
    mkdir -p -- "$lane_root"
    classpath="$(<"$output_root/$variant-classpath.txt")"
    port="$(node -e 'const net=require("node:net"); const s=net.createServer(); s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close();});')"
    export SPOONBILL_AUTH_ORIGIN="http://localhost:$port"
    export SPOONBILL_AUTH_PROVIDER="$provider"
    export SPOONBILL_AUTH_CLUSTERED=false
    export SPOONBILL_PERF_VARIANT="$variant" SPOONBILL_PERF_FLOW="$flow"
    export SPOONBILL_PERF_OUTPUT="$lane_root/browser.json"
    # Keep unrelated fixture options out of the comparison.
    unset SPOONBILL_AUTH_DELIVERY_LIMIT SPOONBILL_AUTH_MAX_ACTIVE_VIEWS SPOONBILL_AUTH_TEST_FIXTURES SPOONBILL_AUTH_SNAPSHOT_VERSION
    server_args=("$port")
    if [[ "$provider" == jdbc ]]; then
      export SPOONBILL_AUTH_SCHEMA="perf_$(node -e 'process.stdout.write(require("node:crypto").randomUUID().replaceAll("-",""))')"
      server_args+=(--initialize)
    fi
    "$SPOONBILL_GNU_TIME" -v -o "$lane_root/process-resources.txt" \
      bash -c 'printf "%s\n" "$$" > "$1"; shift; exec "$@"' -- "$lane_root/server.pid" \
      java -Xms128m -Xmx512m -XX:ActiveProcessorCount=2 -cp "$classpath" example.browserauth.Main "${server_args[@]}" \
      > "$lane_root/server.log" 2>&1 &
    wrapper_pid=$!
    server_pid="$(node - "$lane_root/server.pid" <<'NODE'
const fs=require('node:fs'),{setTimeout:pause}=require('node:timers/promises');
(async()=>{const end=Date.now()+5000;while(Date.now()<end){
  if(fs.existsSync(process.argv[2])){const value=fs.readFileSync(process.argv[2],'utf8').trim();
    if(/^\d+$/.test(value)){process.stdout.write(value);return;}}
  await pause(10);
}throw new Error('Owned JVM PID was not published');})().catch(error=>{console.error(error.message);process.exitCode=1;});
NODE
)"
    node <<'NODE'
const {setTimeout: pause} = require('node:timers/promises');
(async () => {
  const end=Date.now()+45000;
  while (Date.now()<end) {
    try { const r=await fetch(process.env.SPOONBILL_AUTH_ORIGIN+'/health',{signal:AbortSignal.timeout(1000)});
      if(r.status===200){await r.arrayBuffer();return;} } catch(_) {}
    await pause(100);
  }
  throw new Error('Owned benchmark server did not become ready');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
NODE
    heap_sample "$lane_root/before"
    host_sample "$lane_root/host-before.json"
    timeout --kill-after=10s 600s node "$repo_root/misc/replacement-performance-tests.cjs" > "$lane_root/browser.log" 2>&1 || {
      node - "$SPOONBILL_PERF_OUTPUT" <<'NODE'
const fs=require('node:fs'),file=process.argv[2];
if(fs.existsSync(file)){
  const record=JSON.parse(fs.readFileSync(file,'utf8'));
  if(record.status!=='failed'){
    record.status='interrupted';record.failure={name:'HarnessProcessFailed',phase:record.phase};
    fs.writeFileSync(file+'.status.tmp',JSON.stringify(record,null,2)+'\n');
    fs.renameSync(file+'.status.tmp',file);
  }
}
NODE
      cat "$lane_root/browser.log" >&2
      host_sample "$lane_root/host-after.json"
      exit 1
    }
    host_sample "$lane_root/host-after.json"
    heap_sample "$lane_root/after-workload"
    sleep 2.2
    heap_sample "$lane_root/after-maintenance"
    if [[ "$provider" == jdbc ]]; then
      psql "${SPOONBILL_JDBC_TEST_URL#jdbc:}" -X -t -A -v ON_ERROR_STOP=1 -c "SELECT json_build_object(
        'ceremonies',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".spoonbill_auth_ceremony),
        'deliveryMaterials',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".spoonbill_auth_material),
        'retainedViews',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".spoonbill_browser_view),
        'activeViews',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".spoonbill_browser_view WHERE owner_id IS NOT NULL),
        'sessions',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".spoonbill_browser_session),
        'consumedProofs',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".example_consumed_proof),
        'auditRows',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".example_audit))" > "$lane_root/retained.json"
      if [[ "$variant" == candidate ]]; then
        psql "${SPOONBILL_JDBC_TEST_URL#jdbc:}" -X -t -A -v ON_ERROR_STOP=1 -c "SELECT json_build_object(
          'replacementRecords',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".spoonbill_browser_replacement),
          'encryptedRecoveryResults',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".example_recovery_result),
          'recoveryCodeHashes',(SELECT count(*) FROM \"$SPOONBILL_AUTH_SCHEMA\".example_recovery_code))" > "$lane_root/replacement-retained.json"
      fi
    fi
    stop_server
    printf '%s\n' "Completed $provider/$lane"
  done
done

node - "$output_root" "$repo_root" <<'NODE'
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=process.argv[2], source=process.argv[3], protocol=JSON.parse(fs.readFileSync(path.join(root,'protocol.json'),'utf8'));
const hash=crypto.createHash('sha256');
function visit(relative){
  const file=path.join(source,relative),info=fs.lstatSync(file);
  if(info.isSymbolicLink()){hash.update(relative);hash.update(fs.readlinkSync(file));return;}
  if(info.isDirectory()){
    for(const child of fs.readdirSync(file).sort())if(!['target','node_modules','.git'].includes(child))visit(path.join(relative,child));
  }else if(/\.(scala|sbt|js|cjs|mjs|sh|nix|lock|conf|properties)$/.test(relative)){
    hash.update(relative);hash.update('\0');hash.update(fs.readFileSync(file));hash.update('\0');
  }
}
for(const item of ['modules','interop','examples/browser-auth','misc/browser-auth-conformance','misc/replacement-performance-tests.cjs',
  'scripts/measure-browser-replacement.sh','project','build.sbt','flake.nix','flake.lock'])visit(item);
if(hash.digest('hex')!==protocol.candidateSourceSha256)throw new Error('Candidate source changed during collection; this attempt is invalid');
const rows=[];
function retained(provider,variant,flow){
  const lane=path.join(root,`${provider}-${variant}-${flow}`);
  const counts=JSON.parse(fs.readFileSync(path.join(lane,'retained.json'),'utf8'));
  const replacement=variant==='candidate'?JSON.parse(fs.readFileSync(path.join(lane,'replacement-retained.json'),'utf8')):null;
  const checks={activeViewsZero:counts.activeViews===0,deliveryMaterialsZero:counts.deliveryMaterials===0,
    consumedEncryptedResultsZero:replacement===null?null:replacement.encryptedRecoveryResults===0};
  return {counts,replacement,checks,passed:Object.values(checks).every(value=>value===true||value===null)};
}
for(const provider of protocol.providers){
  const read=(variant,flow)=>JSON.parse(fs.readFileSync(path.join(root,`${provider}-${variant}-${flow}`,'browser.json'),'utf8'));
  const baseline=read('baseline','ordinary').metrics.login, candidate=read('candidate','ordinary').metrics.login;
  const replacement=read('candidate','replacement');
  const budget=protocol.ordinaryBudget;
  const medianLimitMs=baseline.medianMs*budget.medianMultiplier+budget.medianAllowanceMs;
  const p95LimitMs=baseline.p95Ms*budget.p95Multiplier+budget.p95AllowanceMs;
  const resources=provider==='jdbc'?{
    baselineOrdinary:retained(provider,'baseline','ordinary'),
    candidateOrdinary:retained(provider,'candidate','ordinary'),
    candidateReplacement:retained(provider,'candidate','replacement'),
  }:null;
  rows.push({provider,baseline,candidate,ordinaryBudget:{medianLimitMs,p95LimitMs,
    passed:candidate.medianMs<=medianLimitMs&&candidate.p95Ms<=p95LimitMs},
    replacement:replacement.metrics.replacement,consumption:replacement.metrics.consumption,
    replacementAndConsumption:replacement.metrics.replacementAndConsumption,faultChecks:replacement.faultChecks,resources});
}
const result={protocol:protocol.protocol,baselineRevision:protocol.baselineRevision,
  candidateSourceSha256:protocol.candidateSourceSha256,runtime:protocol.runtime,rows};
fs.writeFileSync(path.join(root,'summary.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));
for(const row of rows)if(row.resources)for(const [lane,value]of Object.entries(row.resources))
  assert.equal(value.passed,true,`${row.provider}/${lane}: post-maintenance active owners or credential/result material remain`);
// A threshold breach is evidence to report, not a reason to rerun or change it.
NODE
