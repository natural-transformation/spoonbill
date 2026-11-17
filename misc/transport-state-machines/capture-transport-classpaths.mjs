import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';

const [logPath, chunkedOutput, frameOutput] = process.argv.slice(2);
const log = readFileSync(logPath, 'utf8');
assert.ok(!/^\[error\]/m.test(log));
const classpaths = log.split(/\r?\n/).filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
assert.equal(classpaths.length, 2);
assert.ok(classpaths[0].split(':')[0].includes('/modules/http/target/'));
assert.ok(classpaths[1].split(':')[0].includes('/interop/zio-http/target/'));
writeFileSync(chunkedOutput, classpaths[0] + '\n');
writeFileSync(frameOutput, classpaths[1] + '\n');
