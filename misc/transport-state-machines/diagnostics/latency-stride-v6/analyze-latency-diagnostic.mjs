import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {gunzipSync} from 'node:zlib';

const [directory] = process.argv.slice(2);
const percentile = values => values.toSorted((a, b) => a - b)[Math.ceil(values.length * .99) - 1];
const rows = [];
for (const variant of ['baseline', 'model']) for (const round of [1, 2, 3]) {
  const label = `large-${variant}-${round}`;
  const row = JSON.parse(readFileSync(join(directory, `${label}.jsonl`), 'utf8'));
  const binary = gunzipSync(readFileSync(join(directory, `${label}.trace.gz`)));
  assert.equal(binary.readInt32BE(0), 1);
  const count = binary.readInt32BE(4);
  assert.equal(binary.length, 8 + count * 8);
  assert.equal(count, row.operations);
  const trace = Array.from({length: count}, (_, index) => Number(binary.readBigInt64BE(8 + index * 8)));
  assert.equal(row.correct, true);
  assert.equal(row.acceptance, false);
  assert.equal(percentile(trace), row.aggregateP99Nanos);
  for (let offset = 0; offset < 64; offset++) {
    const sample = trace.filter((_, index) => index % 64 === offset);
    assert.equal(percentile(sample), row.p99ByOffset[offset]);
  }
  rows.push({...row, variant, round, offset0ToAggregate: row.p99ByOffset[0] / row.aggregateP99Nanos,
    maximumOffsetToAggregate: row.maximumOffsetP99 / row.aggregateP99Nanos,
    minimumOffsetToAggregate: row.minimumOffsetP99 / row.aggregateP99Nanos});
}
const median = values => values.toSorted((a, b) => a - b)[1];
const summaries = Object.fromEntries(['baseline', 'model'].map(variant => {
  const selected = rows.filter(row => row.variant === variant);
  return [variant, {aggregateP99Median: median(selected.map(row => row.aggregateP99Nanos)),
    offset0P99Median: median(selected.map(row => row.p99ByOffset[0])),
    offsetRanges: selected.map(row => [row.minimumOffsetP99, row.maximumOffsetP99])}];
}));
const summary = {acceptance: false, scope: 'Every-operation diagnostic, all 64 stride offsets retained from the same trace; no selected offsets.',
  ...summaries, aggregateRatio: summaries.model.aggregateP99Median / summaries.baseline.aggregateP99Median,
  offset0Ratio: summaries.model.offset0P99Median / summaries.baseline.offset0P99Median, rows};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({acceptance: false, baseline: summary.baseline, model: summary.model,
  aggregateRatio: summary.aggregateRatio, offset0Ratio: summary.offset0Ratio}));
