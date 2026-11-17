import {readFileSync,writeFileSync} from 'node:fs';
const [input,output,lineArg='217']=process.argv.slice(2);
const line=Number(lineArg);
const events=JSON.parse(readFileSync(input,'utf8')).recording.events;
const normalize=s=>s.replaceAll('/','.').replace(/\$\$Lambda\+.*/,()=>'$$Lambda');
const decode=f=>({type:normalize(f.method.type.name),method:f.method.name,line:f.lineNumber,execution:f.type});
const key=f=>`${f.type}.${f.method}:${f.line}`;
const leaf=new Map(), inclusive=new Map(), source=new Map(), states=new Map(), kinds=new Map();
const count=(m,k)=>m.set(k,(m.get(k)||0)+1);
let mainSamples=0,roundSamples=0,selected=0,truncatedMain=0,missingMain=0,maxDepth=0;
const outside={}, blocking=[], examples=[];
for(const e of events){
 const v=e.values,frames=(v.stackTrace?.frames||[]).map(decode),thread=v.sampledThread??v.eventThread;
 if(thread?.javaName!=='main')continue;
 const inRound=frames.some(f=>f.type==='spoonbill.effect.StreamOperatorBenchmark$Work'&&f.method==='run');
 const caller=frames.find(f=>f.type==='spoonbill.effect.StreamOperatorBenchmark$'&&f.method==='main');
 const measured=inRound&&caller?.line===line;
 if(e.type==='jdk.JavaMonitorEnter'||e.type==='jdk.ThreadPark'){
  blocking.push({event:e.type,measured,duration:v.duration,frames:frames.slice(0,16)});continue;
 }
 if(e.type!=='jdk.ExecutionSample'&&e.type!=='jdk.NativeMethodSample')continue;
 mainSamples++;maxDepth=Math.max(maxDepth,frames.length);
 if(v.stackTrace?.truncated)truncatedMain++;
 if(inRound){roundSamples++;if(!caller)missingMain++;}
 if(!measured){const why=inRound?'warmup or other round caller':'startup/teardown/harness';outside[why]=(outside[why]||0)+1;continue;}
 selected++;count(states,v.state);count(kinds,e.type);
 if(frames[0])count(leaf,key(frames[0]));
 for(const method of new Set(frames.map(key)))count(inclusive,method);
 const domain=frames.find(f=>f.type.startsWith('spoonbill.effect.')&&!f.type.startsWith('spoonbill.effect.StreamOperatorBenchmark')&&!f.type.startsWith('spoonbill.effect.Effect')&&!f.type.startsWith('spoonbill.effect.syntax'));
 count(source,domain?key(domain):'fixture/library-only');
 if(examples.length<10)examples.push({leaf:frames[0],frames});
}
const rows=m=>[...m].sort((a,b)=>b[1]-a[1]).map(([method,samples])=>({method,samples,fraction:samples/selected}));
const result={scope:'Diagnostic only: live main Work.run ancestry at measured main callsite; sample proportions are not exact CPU times',coverage:{mainSamples,roundSamples,measuredSamples:selected,outside,truncatedMain,missingMain,maxDepth,mainCallSiteLine:line},states:Object.fromEntries(states),sampleKinds:Object.fromEntries(kinds),topLeaf:rows(leaf).slice(0,35),topInclusive:rows(inclusive).slice(0,50),nearestOperatorFrame:rows(source).slice(0,30),blockingEvents:blocking,representativeStacks:examples,limitations:['Single controlled-publication Future/parasitic workload','No concurrent producers during timing','Uncontended synchronized cost may appear in compiled Java methods without any JavaMonitorEnter event','Inclusive frames overlap and cannot be summed','JFR sampling/safepoint and JIT attribution limits apply','Diagnostic timings are not acceptance evidence']};
writeFileSync(output,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({coverage:result.coverage,states:result.states,kinds:result.sampleKinds,leaf:result.topLeaf.slice(0,12),operator:result.nearestOperatorFrame.slice(0,12),blocking:result.blockingEvents.map(e=>({event:e.event,measured:e.measured,duration:e.duration}))},null,2));
