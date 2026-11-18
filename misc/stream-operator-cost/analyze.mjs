import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
const [directory, pairsArg='3'] = process.argv.slice(2);
const pairs=Number(pairsArg);
const combined=join(directory,'trials.jsonl');
const rows=existsSync(combined)
 ? readFileSync(combined,'utf8').trim().split(/\r?\n/).map(JSON.parse)
 : readdirSync(directory).filter(x=>x.endsWith('.jsonl')).map(x=>JSON.parse(readFileSync(join(directory,x),'utf8')));
assert.equal(rows.length, pairs*6);
const median=values=>{const sorted=[...values].sort((a,b)=>a-b);return (sorted[Math.floor((sorted.length-1)/2)]+sorted[Math.floor(sorted.length/2)])/2;};
const stats=values=>({median:median(values),min:Math.min(...values),max:Math.max(...values)});
const results=['hub','sort','buffer'].map(scenario=>{
 let settings;
 const versions=Object.fromEntries(['before','after'].map(variant=>{
  const selected=Array.from({length:pairs},(_,i)=>{
   const label=`${scenario}-${variant}-${i+1}`;
   const found=rows.filter(row=>row.label===label);
   assert.equal(found.length,1,label);
   const row=found[0];
   assert.equal(row.protocol,'stream-compat-v2');
   assert.equal(row.correct,true);assert.equal(row.threadAlive,true);
   assert.equal(row.allocationCounter,'current-live-thread');
   assert.equal(row.deliveries,row.rounds*(scenario==='buffer'?1:2));
   for(const field of ['rounds','deliveries','elapsedSeconds','allocatedBytes','deliveriesPerSecond','bytesPerDelivery'])
    assert.ok(Number.isFinite(row[field])&&row[field]>=0,`${label}: ${field}`);
   assert.ok(row.rounds>0&&row.elapsedSeconds>0);
   const current=['scenario','warmupSeconds','seconds','java','processors','maxHeapBytes'].map(key=>row[key]);
   if(settings)assert.deepEqual(current,settings);else settings=current;
   const stderr=join(directory,label+'.stderr');
   if(existsSync(stderr))assert.equal(readFileSync(stderr,'utf8').trim(),'',label+' stderr');
   return row;
  });
  return [variant,{deliveriesPerSecond:stats(selected.map(r=>r.deliveriesPerSecond)),bytesPerDelivery:stats(selected.map(r=>r.bytesPerDelivery)),roundsPerSecond:stats(selected.map(r=>r.roundsPerSecond)),bytesPerRound:stats(selected.map(r=>r.bytesPerRound))}];
 }));
 return {scenario,...versions,throughputRatio:versions.after.deliveriesPerSecond.median/versions.before.deliveriesPerSecond.median,allocationRatio:versions.after.bytesPerDelivery.median/versions.before.bytesPerDelivery.median};
});
const summary={protocol:'stream-compat-v2',scope:'short compatibility-path cost screen; not whole-semantics equivalence or acceptance',trials:rows.length,pairs,statistics:'independent JVM medians/ranges; no confidence interval',allocation:'live main-thread allocated bytes; timed source/delivery thread assertions and separate all-continuation smoke',results};
writeFileSync(join(directory,'summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary,null,2));
