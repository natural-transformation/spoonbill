// Execute only after the measurement lane is idle. No external dependencies.
// node analyze-provider.mjs PROTOCOL RESULTS RUNS OUTPUT_PREFIX [BROWSER_RESULTS...]
import {readFileSync, writeFileSync} from 'node:fs';
import {basename} from 'node:path';
import {createHash} from 'node:crypto';
const [protocolFile, resultsFile, runsFile, outputPrefix, ...browserFiles] = process.argv.slice(2);
if (!outputPrefix) throw new Error('Usage: analyze-provider.mjs PROTOCOL RESULTS RUNS OUTPUT_PREFIX [BROWSER_RESULTS...]');
const sha = text => createHash('sha256').update(text).digest('hex');
const finite = n => typeof n === 'number' && Number.isFinite(n);
const positive = n => finite(n) && n > 0;
const nonnegative = n => finite(n) && n >= 0;
const mean = xs => xs.length ? xs.reduce((a,b) => a+b,0)/xs.length : null;
const summary = xs => ({processes: xs.length, observed: xs.filter(finite).length,
  mean: xs.length && xs.every(finite) ? mean(xs) : null,
  observedMean: mean(xs.filter(finite)), values: xs});
const redact = text => String(text ?? '').replace(/\b[A-Za-z0-9_-]{43}\b/g, '[redacted-token]')
  .replace(/\/(?:Users|home|private|tmp|nix)\/[^\s"'`]+/g, '[local-path]');
function readJsonl(file) {
  const text = readFileSync(file,'utf8'), rows = [], malformed = [];
  for (const [i,line] of text.trimEnd().split(/\r?\n/).entries()) {
    if (!line.trim()) continue;
    try { rows.push({line:i+1,value:JSON.parse(line)}); }
    catch { malformed.push(i+1); }
  }
  return {file:basename(file),sha256:sha(text),rows,malformed};
}
function bootstrap(values) {
  if (!values.length || !values.every(finite)) return null;
  const estimate = mean(values);
  if (values.every(x => x === values[0])) return {estimate,bounds:[estimate,estimate],pairedChanges:values};
  let state=20261011;
  const random=()=> { state ^= state<<13; state ^= state>>>17; state ^= state<<5; return (state>>>0)/4294967296; };
  const samples=new Float64Array(50000), tail=.05/(4*22);
  for(let i=0;i<samples.length;i++) {
    let sum=0;
    for(let j=0;j<values.length;j++) sum+=values[Math.floor(random()*values.length)];
    samples[i]=sum/values.length;
  }
  samples.sort();
  return {estimate,bounds:[samples[Math.floor(tail*49999)],samples[Math.ceil((1-tail)*49999)]],pairedChanges:values};
}
const definitions=[];
for(const kind of ['protected-operation','same-view-reconnect']) {
  for(const quantile of ['p50','p95','p99']) definitions.push({kind,pass:'latency',name:`${kind}-${quantile}`,unit:'ns',get:r=>r?.[quantile+'Ns']});
  definitions.push({kind,pass:'latency',name:`${kind}-throughput`,unit:'operations/s',higherIsBetter:true,
    get:r=>positive(r?.elapsedNs)&&positive(r?.operations)?r.operations*1e9/r.elapsedNs:null});
  definitions.push({kind,pass:'memory',name:`${kind}-allocation`,unit:'bytes/operation',zeroAllowed:true,
    get:r=>nonnegative(r?.allocatedBytes)&&positive(r?.operations)?r.allocatedBytes/r.operations:null});
}
definitions.push({kind:'distinct-view-retention',pass:'memory',name:'distinct-view-retained-heap',unit:'bytes',
  get:r=>r?.gcObserved===true?r.heapAfter:null});
function value(row,definition) {
  const v=definition.get(row);
  return (definition.zeroAllowed?nonnegative(v):positive(v))?v:null;
}
const key=(provider,pass,mode,block,side,kind)=>[provider,pass,mode,block,side,kind].join('/');
const counts={latency:{calibration:2,comparison:6},memory:{calibration:2,comparison:3}};
const operations=(backend,kind)=>backend==='memory'?(kind==='protected-operation'?5000:kind==='same-view-reconnect'?1000:500)
  :(kind==='protected-operation'?200:kind==='same-view-reconnect'?100:50);

function analyze() {
  const protocolText=readFileSync(protocolFile,'utf8'),protocol=JSON.parse(protocolText);
  const evidence=readJsonl(resultsFile),runs=readJsonl(runsFile),issues=[],warnings=[],observed=new Map();
  const analysis=protocol.analysis;
  if(protocol.schema!=='spoonbill-provider-measurement-protocol-v1' || analysis.bootstrapReplicates!==50000 ||
    analysis.seed!==20261011 || analysis.primaryMetricsPerProvider!==11 || analysis.jointIntervals!==44 ||
    Math.abs(analysis.intervalTail-.05/(4*22))>1e-15 || analysis.comparisonAllowance!==0 ||
    analysis.calibrationMaximumAbsoluteDrift!==.01 || analysis.calibrationMaximumWidth!==.10)
    throw new Error('Frozen provider analysis protocol does not match the implemented estimator.');
  const expectedRevisions={baseline:'53be8c017d46423feb396d91e3b4cb67c853524b',candidate:'9e5b3011a088526096cf7585e60415ddb45e1640'};
  for(const [variant,revision] of Object.entries(expectedRevisions)) {
    const runtime=protocol.runtime?.[variant];
    if(runtime?.revision!==revision) issues.push(`Incorrect ${variant} frozen revision.`);
    if(sha(readFileSync(runtime.probeJar))!==runtime.probeSha256) issues.push(`Provider fixture artifact changed for ${variant}.`);
    for(const artifact of runtime.frozenLibraryArtifacts??[])
      if(sha(readFileSync(artifact.file))!==artifact.sha256) issues.push(`Frozen library artifact changed: ${variant}/${artifact.name}.`);
    if(!runtime.frozenLibraryArtifacts?.length) issues.push(`Missing library artifact provenance for ${variant}.`);
  }
  if(JSON.stringify(protocol.javaArgs)!==JSON.stringify(['-Xms256m','-Xmx256m','-XX:ActiveProcessorCount=2'])) issues.push('Unexpected JVM flags.');
  if(evidence.malformed.length) issues.push('Provider results contain malformed or truncated JSONL records.');
  if(runs.malformed.length) issues.push('Process run records contain malformed or truncated JSONL records.');
  const unknown=[];
  for(const {line,value:row} of evidence.rows) {
    if(row.schema!=='spoonbill-provider-probe-v1') {unknown.push({line,kind:row.kind??row.schema});continue;}
    const match=/^(calibration|comparison)-(\d+)-(left|right)$/.exec(row.block??'');
    if(!match || !['memory','jdbc'].includes(row.backend) || !['latency','memory'].includes(row.pass)) {
      issues.push(`Invalid cell identity at results line ${line}.`);continue;
    }
    const [,mode,indexText,side]=match,block=Number(indexText);
    if(row.mode!==mode || row.pair!==block || row.side!==side) issues.push(`Inconsistent appended process identity at results line ${line}.`);
    const id=key(row.backend,row.pass,mode,block,side,row.kind);
    if(observed.has(id)) { issues.push(`Duplicate observation for ${id}.`);continue; }
    observed.set(id,row);
    if(block>=counts[row.pass][mode] || !definitions.some(d=>d.kind===row.kind&&d.pass===row.pass))
      issues.push(`Unexpected workload cell ${id}.`);
    const expectedVariant=mode==='calibration'?'baseline':(block%2===0?(side==='left'?'baseline':'candidate'):(side==='left'?'candidate':'baseline'));
    if(row.variant!==expectedVariant) issues.push(`Wrong executable variant for ${id}.`);
    if(row.fixedClock!==true || row.exclusiveScope!==true) issues.push(`Changed timing/ownership scope for ${id}.`);
    if(row.operations!==operations(row.backend,row.kind)) issues.push(`Wrong operation count for ${id}.`);
    if(row.pass==='latency') {
      if(![row.elapsedNs,row.p50Ns,row.p95Ns,row.p99Ns].every(positive) || row.p50Ns>row.p95Ns || row.p95Ns>row.p99Ns)
        issues.push(`Invalid latency domain/quantile ordering for ${id}.`);
    } else if(row.kind==='distinct-view-retention') {
      if(row.gcObserved!==true || row.quietSecondsPerBoundary!==30 || ![row.heapBefore,row.heapAfter].every(positive) ||
        row.heapGrowth!==row.heapAfter-row.heapBefore || row.retainedViewsAfter-row.retainedViewsBefore!==row.operations)
        issues.push(`Invalid retention evidence for ${id}.`);
    } else {
      if(!nonnegative(row.allocatedBytes)) warnings.push(`Allocation unsupported or invalid for ${id}; metric remains null.`);
      if(row.activeConnectionsBefore!==0 || row.activeConnectionsAfter!==0) issues.push(`Connection resource boundary not zero for ${id}.`);
    }
  }
  if(unknown.length) issues.push('Unexpected records occur in provider results.');
  const expected=[];
  for(const provider of ['memory','jdbc']) for(const pass of ['latency','memory'])
    for(const mode of ['calibration','comparison']) for(let block=0;block<counts[pass][mode];block++)
      for(const side of ['left','right']) for(const kind of [...new Set(definitions.filter(d=>d.pass===pass).map(d=>d.kind))])
        expected.push(key(provider,pass,mode,block,side,kind));
  const missing=expected.filter(id=>!observed.has(id));
  if(missing.length) issues.push(`${missing.length} planned workload observations are missing.`);
  const javaVersions=[...new Set([...observed.values()].map(r=>r.javaVersion))];
  if(javaVersions.length!==1 || !javaVersions[0]) issues.push('A single explicit Java version is not established.');
  const runValues=runs.rows.map(r=>r.value);
  const plannedRuns=[];
  for(const pass of ['latency','memory']) for(const backend of ['memory','jdbc'])
    for(const mode of ['calibration','comparison']) for(let pair=0;pair<counts[pass][mode];pair++)
      for(const side of ['left','right']) plannedRuns.push({pass,backend,mode,pair,side,
        variant:mode==='calibration'?'baseline':(pair%2===0?(side==='left'?'baseline':'candidate'):(side==='left'?'candidate':'baseline')),
        block:`${mode}-${pair}-${side}`});
  if(JSON.stringify(protocol.runs)!==JSON.stringify(plannedRuns)) issues.push('Protocol workload order/count differs from declared 52-process schedule.');
  for(const [index,run] of runValues.entries()) {
    if(!plannedRuns[index] || Object.entries(plannedRuns[index]).some(([k,v])=>run[k]!==v)) issues.push(`Unexpected process order at run record ${index+1}.`);
    if(run.status!=='complete' || run.exitCode!==0 || run.timedOut!==false || run.rows!==(run.pass==='memory'?3:2))
      issues.push(`Process incomplete or failed at run record ${index+1}: ${run.status??'unknown'}.`);
  }
  if(runValues.length!==plannedRuns.length) issues.push(`${plannedRuns.length-runValues.filter(r=>r.status==='complete').length} planned process completions are absent.`);
  const failures=runValues.filter(r=>r.status==='failed');
  if(failures.length) issues.push('At least one provider process run failed.');
  const expectedRowsFromCompleteRuns=runValues.filter(r=>r.status==='complete').flatMap(run=>
    (run.pass==='latency'?['protected-operation','same-view-reconnect']:['protected-operation','same-view-reconnect','distinct-view-retention'])
      .map(kind=>key(run.backend,run.pass,run.mode,run.pair,run.side,kind)));
  const actualRows=evidence.rows.map(({value:r})=>key(r.backend,r.pass,r.mode,r.pair,r.side,r.kind));
  if(JSON.stringify(expectedRowsFromCompleteRuns)!==JSON.stringify(actualRows)) issues.push('Raw result order/content does not match completed process records.');
  const metrics=[],summaries=[];
  for(const provider of ['memory','jdbc']) for(const definition of definitions) {
    const pairs=mode=>Array.from({length:counts[definition.pass][mode]},(_,block)=> {
      const sides=mode==='comparison'&&block%2?['right','left']:['left','right'];
      return sides.map(side=>value(observed.get(key(provider,definition.pass,mode,block,side,definition.kind)),definition));
    });
    const comparison=pairs('comparison'),calibration=pairs('calibration');
    const complete=[...comparison.flat(),...calibration.flat()].every(v=>v!==null);
    const absolute=complete&&[...comparison,...calibration].some(([a])=>a===0);
    const changes=pairs=>pairs.map(([a,b])=>(absolute?b-a:b/a-1)*(definition.higherIsBetter?-1:1));
    const result={provider,pass:definition.pass,kind:definition.kind,name:definition.name,unit:definition.unit,
      scale:absolute?'absolute':'relative',higherIsBetter:!!definition.higherIsBetter,
      expectedComparisonPairs:comparison.length,comparisonPairs:comparison.filter(p=>p.every(finite)).length,
      expectedCalibrationPairs:calibration.length,calibrationPairs:calibration.filter(p=>p.every(finite)).length,
      baseline:summary(comparison.map(p=>p[0])),candidate:summary(comparison.map(p=>p[1])),
      comparison:null,calibration:null,status:'inconclusive-missing-observations'};
    if(complete) {
      result.comparison=bootstrap(changes(comparison));result.calibration=bootstrap(changes(calibration));
      const aa=result.calibration,bounds=result.comparison.bounds;
      aa.usable=!absolute&&Math.abs(aa.estimate)<=.01&&aa.bounds[1]-aa.bounds[0]<=.10;
      result.exploratorySign=bounds[0]>0?'positive-worse':bounds[1]<=0?'nonpositive':'crosses-zero';
      result.status=issues.length?'inconclusive-collection-integrity':!aa.usable?'inconclusive-calibration':
        bounds[0]>0?'exploratory-regression-signal':bounds[1]<=0?'exploratory-nonpositive-bound':'inconclusive-interval';
    }
    metrics.push(result);
    for(const mode of ['comparison','calibration']) for(const [index,label] of ['baseline','candidate'].entries())
      summaries.push({provider,pass:definition.pass,kind:definition.kind,metric:definition.name,mode,label,
        executableVariant:mode==='calibration'?'baseline':label,...summary(pairs(mode).map(p=>p[index]))});
  }
  const browserAttempts=browserFiles.map(file=> {
    const input=readJsonl(file);
    return {file:input.file,sha256:input.sha256,malformed:input.malformed,
      completed:input.rows.some(r=>r.value.kind==='complete'),
      numericSamples:input.rows.filter(r=>['browser-latency','browser-churn-memory'].includes(r.value.kind)).length,
      failures:input.rows.filter(r=>r.value.kind==='failure').map(r=>({line:r.line,atUtc:r.value.atUtc??null,reason:redact(r.value.reason)}))};
  });
  return {schema:'spoonbill-bounded-provider-diagnostic-analysis-v1',generatedAtUtc:new Date().toISOString(),
    status:'inconclusive',fullReleaseAcceptance:false,performanceGatePassed:false,
    readiness:'Provider-level measurements cannot establish browser or whole-release zero-regression readiness. All missing browser and full-matrix evidence remains unresolved.',
    protocol:{sha256:sha(protocolText),definition:JSON.parse(JSON.stringify(protocol,(key,value)=>
      ['rawFile','probeJar','classpath','file'].includes(key)?undefined:value))},
    method:{bootstrapReplicates:50000,seed:20261011,primaryMetrics:22,individualIntervals:44,adjustedTail:.05/(4*22),
      nominalFamilyConfidence:.95,nominalConfidenceIsExploratory:true,slowdownAllowance:0,
      estimator:'Mean paired per-process oriented relative changes; positive means worse. Absolute difference if any baseline is zero.',
      quantiles:'Nearest-rank quantiles calculated inside each process, never pooled across processes. Raw timing arrays were not retained by this provider collector.',
      calibration:{maximumAbsoluteMeanDrift:.01,maximumIntervalWidth:.10}},
    evidence:{file:evidence.file,sha256:evidence.sha256,observations:observed.size,expectedObservations:expected.length,
      missing,unknown,malformed:evidence.malformed,runFile:runs.file,runSha256:runs.sha256,javaVersions,
      runRecords:runValues},issues,warnings,metrics,summaries,browserAttempts,
    positiveBoundsBeforeCalibration:metrics.filter(m=>m.exploratorySign==='positive-worse').map(m=>`${m.provider}/${m.name}`),
    secondaryDiagnostics:[...observed.values()].map(r=>({...r,
      processCpuNanosPerOperation:nonnegative(r.processCpuNanos)?r.processCpuNanos/r.operations:null})),
    unsupported:{browserLatency:null,browserThroughput:null,browserReconnect:null,browserRetainedHeap:null,
      browserCumulativeAllocation:null,wholeProcessPeakRss:null,nativeAllocatorBytes:null,jvmHeapAfterProviderClose:null,
      sharedMultiNodePerformance:null},
    limitations:[
      'Six latency and three memory comparison pairs, with two A/A pairs per provider. These percentile-bootstrap intervals are exploratory, not validated population confidence bounds.',
      'Matched public provider API calls with synthetic fixed time and serial awaits, not end-to-end HTTP, WebSocket, DOM, authentication form, or physical-paint measurements.',
      'Protected work includes transaction, exact counter verification and authorize; reconnect includes claim, old release, old denial and current authorization.',
      'Achieved serial throughput is not saturation throughput. No concurrent clients or shared multi-node topology were measured.',
      'JDBC exclusive ownership is the common v4/v5 topology. Measurements cannot prove the new shared ownership path has no regression.',
      'JDBC uses a local test PostgreSQL instance with newly opened physical connections, not a production pool or realistic network latency. SQL counters count JDBC API executions, not wire round trips.',
      'The identical fixed clock exercises exclusive cached-clock behavior; checkpoint connection deltas are reported, but advancing-clock persistence work is not characterized.',
      'Memory is a separate invasive pass. JVM allocation and post-GC retained Java heap exclude native allocation, database server memory and browser memory.',
      'Distinct view records are deliberately retained: 500 for memory, 50 for JDBC, and matched within each provider. This is bounded growth, not leak freedom or post-close reclamation.',
      'Fixed warmups do not prove that JIT compilation and GC have stabilized; numerical A/A checks are reported without widening zero allowance.',
      'The historical 94-cell manifest and its fixed baseline IDs remain unchanged and unsatisfied. Missing metrics remain null.',
      'Browser fixture failures are preserved; no missing browser result is replaced by a favorable provider result.',
    ]};
}
function markdown(report) {
  const number=n=>finite(n)?Number(n.toPrecision(6)).toLocaleString('en-US'):'unavailable';
  const lines=['# Bounded provider performance diagnostic','','**Overall: inconclusive. Full zero-regression acceptance is not established.**','',report.readiness,'',
    'Positive paired change means worse. Per-process quantiles are not pooled. All intervals below are exploratory.','',
    '| Provider | Metric | Baseline process mean | Candidate process mean | Paired change | Adjusted interval | A/A usable | Status |',
    '| --- | --- | ---: | ---: | ---: | --- | --- | --- |'];
  for(const m of report.metrics??[]) {
    const scale=m.scale==='relative'?100:1,suffix=m.scale==='relative'?'%':` ${m.unit}`;
    const describe=s=>s.mean!==null?number(s.mean):s.observed?`${number(s.observedMean)} (partial ${s.observed}/${s.processes})`:'unavailable';
    lines.push(`| ${m.provider} | ${m.name} (${m.unit}) | ${describe(m.baseline)} | ${describe(m.candidate)} | ${m.comparison?number(m.comparison.estimate*scale)+suffix:'unavailable'} | ${m.comparison?m.comparison.bounds.map(n=>number(n*scale)+suffix).join(' to '):'unavailable'} | ${m.calibration?(m.calibration.usable?'numerically yes; 2 pairs':'no'):'unavailable'} | ${m.status} |`);
  }
  lines.push('','## Integrity and browser evidence','',`Provider observations: ${report.evidence?.observations??0}/${report.evidence?.expectedObservations??'unknown'}.`,'');
  for(const message of [...report.issues??[],...report.warnings??[]]) lines.push('- '+redact(message));
  for(const attempt of report.browserAttempts??[]) {
    lines.push(`- ${attempt.file}: ${attempt.numericSamples} numeric samples; complete=${attempt.completed}.`);
    for(const failure of attempt.failures) lines.push(`  - Line ${failure.line}: ${failure.reason.split('\n')[0]}`);
  }
  lines.push('','## Limits','');
  for(const limit of report.limitations??[]) lines.push('- '+limit);
  lines.push('','Neither a favorable point estimate nor absence of a calibrated regression signal establishes release readiness.','');
  return lines.join('\n');
}
let report;
try {report=analyze();}
catch(error) {report={schema:'spoonbill-bounded-provider-diagnostic-analysis-v1',status:'inconclusive',fullReleaseAcceptance:false,
  performanceGatePassed:false,readiness:'Evidence could not be analyzed; no performance decision is supported.',issues:[redact(error.message)],metrics:[]};}
writeFileSync(outputPrefix+'.json',JSON.stringify(report,null,2)+'\n',{flag:'wx'});
writeFileSync(outputPrefix+'.md',markdown(report),{flag:'wx'});
console.log(JSON.stringify({status:report.status,observations:report.evidence?.observations??0,issues:report.issues,
  positiveBoundsBeforeCalibration:report.positiveBoundsBeforeCalibration??[],fullReleaseAcceptance:false}));
// The complete strict performance gate has not passed even if this diagnostic is well formed.
process.exitCode=1;
