// Loopback PostgreSQL proxy that adds a fixed client->server delay and counts
// protocol round trips (frontend Sync 'S' and simple Query 'Q' messages).
// Usage: node pgdelay.mjs <targetPort> <delayMs> <portFile> <countFile>
import net from 'node:net';
import { writeFileSync } from 'node:fs';

const [targetPort, delayArg, portFile, countFile] = process.argv.slice(2);
const delay = Number(delayArg);
let exchanges = 0;

// Node timers round up to whole milliseconds. A setImmediate loop releases
// delayed chunks with sub-millisecond accuracy while any are pending.
const due = [];
let spinning = false;
function schedule(at, run) {
  due.push({ at, run });
  if (!spinning) { spinning = true; setImmediate(release); }
}
function release() {
  const now = performance.now();
  while (due.length && due[0].at <= now) due.shift().run();
  if (due.length) setImmediate(release); else spinning = false;
}

const server = net.createServer(client => {
  const upstream = net.connect(Number(targetPort), '127.0.0.1');
  let started = false;     // startup/SSL request has no message type byte
  let pending = Buffer.alloc(0);
  let lastDue = 0;
  const count = chunk => {
    pending = Buffer.concat([pending, chunk]);
    for (;;) {
      if (!started) {
        if (pending.length < 8) return;
        const length = pending.readInt32BE(0);
        const code = pending.readInt32BE(4);
        if (pending.length < length) return;
        pending = pending.subarray(length);
        // SSLRequest/GSSENCRequest precede the real startup message.
        if (code !== 80877103 && code !== 80877104) started = true;
        continue;
      }
      if (pending.length < 5) return;
      const type = String.fromCharCode(pending[0]);
      const length = pending.readInt32BE(1);
      if (pending.length < length + 1) return;
      if (type === 'S' || type === 'Q') exchanges++;
      pending = pending.subarray(length + 1);
    }
  };
  client.on('data', chunk => {
    count(chunk);
    // Preserve order: a chunk never overtakes an earlier delayed chunk.
    const at = Math.max(performance.now() + delay, lastDue);
    lastDue = at;
    if (delay > 0) schedule(at, () => upstream.write(chunk)); else upstream.write(chunk);
  });
  upstream.on('data', chunk => client.write(chunk));
  const close = () => { client.destroy(); upstream.destroy(); };
  client.on('error', close); upstream.on('error', close);
  client.on('close', close); upstream.on('close', close);
});
server.listen(0, '127.0.0.1', () => writeFileSync(portFile, String(server.address().port)));
setInterval(() => writeFileSync(countFile, String(exchanges)), 50);
