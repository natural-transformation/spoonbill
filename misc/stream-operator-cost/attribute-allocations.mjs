import {readFileSync, writeFileSync} from 'node:fs';
const [input,output,mainLineArg]=process.argv.slice(2);
const mainLine=mainLineArg===undefined?null:Number(mainLineArg);
const events=JSON.parse(readFileSync(input,'utf8')).recording.events;
const name=value=>value.replaceAll('/','.').replace(/\$\$Lambda\+.*/, ()=>'$$Lambda');
const frame=f=>({type:name(f.method.type.name),method:f.method.name,line:f.lineNumber});
const count=new Map(), origins=new Map(), sites=new Map(), examples=new Map();
const add=(map,key,value)=>map.set(key,(map.get(key)||0)+value);
function objectGroup(type){
 if(type.startsWith('scala.concurrent.impl.Promise'))return 'Future promise/transform/link';
 if(type.startsWith('spoonbill.effect.StreamRoutingMachine$'))return 'Pure routing model objects';
 if(type.startsWith('spoonbill.effect.StreamBatchMachine$'))return 'Pure batch model objects';
 if(type==='spoonbill.effect.StreamRoutingRuntime$Held')return 'Interpreter Held copies';
 if(type==='java.util.concurrent.atomic.AtomicBoolean')return 'AtomicBoolean protect markers';
 if(type.startsWith('spoonbill.effect.Effect'))return 'Effect implementation closures';
 if(type.includes('$$Lambda')&&type.includes('StreamRoutingRuntime'))return 'Routing interpreter closures';
 if(type.includes('$$Lambda')&&type.includes('StreamOperatorBenchmark'))return 'Fixture closures';
 if(type.startsWith('spoonbill.effect.StreamRoutingRuntime'))return 'Other routing helper objects';
 if(type.startsWith('scala.collection.')||type==='[Ljava.lang.Object;')return 'Collections/backing arrays';
 if(type.startsWith('scala.Tuple'))return 'Tuple records';
 if(['scala.Some','scala.util.Success','scala.util.Right','scala.util.Left'].includes(type))return 'Option/result wrappers';
 if(type==='java.lang.Long')return 'Long value boxing';
 return 'Other/unclassified object classes';
}
function origin(frames){
 for(const f of frames){
  if(f.type.includes('StreamRoutingMachine'))return ['Routing model',f];
  if(f.type.includes('StreamBatchMachine'))return ['Batch model',f];
  if(f.type.includes('StreamRoutingRuntime')&&f.method.startsWith('protect'))return ['Protect scope',f];
  if(f.type.startsWith('spoonbill.effect.StreamRoutingRuntime'))return ['Routing interpreter',f];
  if(f.type.startsWith('spoonbill.effect.StreamBufferRuntime'))return ['Buffer interpreter',f];
  if(f.type.startsWith('spoonbill.effect.StreamOperatorBenchmark'))return ['Fixture',f];
  if(f.type.startsWith('spoonbill.effect.Hub')||f.type.startsWith('spoonbill.effect.Stream$')||f.type.startsWith('spoonbill.effect.Queue'))return ['Previous operator/queue',f];
 }
 return ['Unclassified origin',null];
}
let mainWeight=0,selectedWeight=0,excludedWeight=0,truncatedWeight=0,truncatedWithoutWorkWeight=0,selectedSamples=0,mainSamples=0,maxDepth=0,allRoundWeight=0,missingMainFrameWeight=0;
for(const event of events){
 const v=event.values;
 if(v.eventThread?.javaName!=='main')continue;
 const weight=v.weight,frames=(v.stackTrace?.frames||[]).map(frame);
 mainWeight+=weight;mainSamples++;maxDepth=Math.max(maxDepth,frames.length);
 const inRound=frames.some(f=>f.type==='spoonbill.effect.StreamOperatorBenchmark$Work'&&f.method==='run');
 const caller=frames.find(f=>f.type==='spoonbill.effect.StreamOperatorBenchmark$'&&f.method==='main');
 if(inRound){allRoundWeight+=weight;if(!caller)missingMainFrameWeight+=weight;}
 const selected=inRound&&(mainLine===null||caller?.line===mainLine);
 if(v.stackTrace?.truncated){truncatedWeight+=weight;if(!selected)truncatedWithoutWorkWeight+=weight;}
 if(!selected){excludedWeight+=weight;continue;}
 selectedWeight+=weight;selectedSamples++;
 const type=name(v.objectClass.name),group=objectGroup(type),[domain,site]=origin(frames);
 add(count,group,weight);add(origins,domain,weight);
 const key=site?`${site.type}.${site.method}:${site.line}`:'unknown';
 add(sites,key,weight);
 if(!examples.has(group))examples.set(group,{allocatedClass:type,frames:frames.slice(0,18)});
}
const rows=map=>[...map].sort((a,b)=>b[1]-a[1]).map(([category,weight])=>({category,weight,fraction:weight/selectedWeight}));
const result={scope:mainLine===null?'Diagnostic JFR ObjectAllocationSample, main thread, Work.run ancestry (warmup and measured rounds); not a byte census or acceptance timing':`Diagnostic JFR main Work.run ancestry with main call-site line ${mainLine}; not a byte census or acceptance timing`,coverage:{allEvents:events.length,mainSamples,selectedSamples,mainWeight,selectedWeight,excludedMainWeight:excludedWeight,truncatedMainWeight:truncatedWeight,truncatedWithoutWorkWeight,maxExportedDepth:maxDepth,allRoundWeight,excludedRoundWeight:allRoundWeight-selectedWeight,missingMainFrameWeight,mainCallSiteLine:mainLine},objectCategories:rows(count),nearestDomainOrigin:rows(origins),topSites:rows(sites).slice(0,24),representativeStacks:Object.fromEntries(examples)};
writeFileSync(output,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({coverage:result.coverage,objects:result.objectCategories,origins:result.nearestDomainOrigin,sites:result.topSites.slice(0,10)},null,2));
