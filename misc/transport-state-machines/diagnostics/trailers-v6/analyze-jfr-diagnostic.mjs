import {readFileSync, writeFileSync} from 'node:fs';

const [input, output] = process.argv.slice(2);
const events = JSON.parse(readFileSync(input, 'utf8')).recording.events;
const allocations = new Map();
const cpu = new Map();
const className = value => value?.name?.replaceAll('/', '.') ?? '<unknown>';
const stack = event => (event.values.stackTrace?.frames ?? []).map(frame =>
  `${className(frame.method?.type)}.${frame.method?.name}:${frame.lineNumber}`);
const firstProjectFrame = frames => frames.find(frame => frame.startsWith('spoonbill.') &&
  !frame.includes('ChunkedBodyBenchmark')) ?? frames[0] ?? '<no stack>';
for (const event of events) {
  const thread = event.values.eventThread ?? event.values.sampledThread;
  if (thread?.javaName !== 'main') continue;
  const frames = stack(event);
  if (event.type === 'jdk.ObjectAllocationSample') {
    const key = `${className(event.values.objectClass)} @ ${firstProjectFrame(frames)}`;
    const value = allocations.get(key) ?? {samples: 0, sampledWeightBytes: 0, stack: frames};
    value.samples++;
    value.sampledWeightBytes += event.values.weight;
    allocations.set(key, value);
  } else if (event.type === 'jdk.ExecutionSample') {
    const key = firstProjectFrame(frames);
    const value = cpu.get(key) ?? {samples: 0, stack: frames};
    value.samples++;
    cpu.set(key, value);
  }
}
const summary = {acceptance: false, scope: 'Sampled allocation/CPU attribution, main thread only; weights are estimates, not exact object totals.',
  allocations: [...allocations].map(([site, value]) => ({site, ...value})).sort((a, b) => b.sampledWeightBytes - a.sampledWeightBytes),
  cpu: [...cpu].map(([site, value]) => ({site, ...value})).sort((a, b) => b.samples - a.samples)};
writeFileSync(output, JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({acceptance: false, allocations: summary.allocations.slice(0, 12), cpu: summary.cpu.slice(0, 12)}));
