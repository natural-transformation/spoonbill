// Open <sessions> sessions (one click each, so every session has rendered and
// handled an event), keep them open, write <readyFile>, and wait to be killed.
// Usage: node hold.mjs <baseUrl> <sessions> <readyFile>
import { writeFileSync } from 'node:fs';
const [base, countArg, readyFile] = process.argv.slice(2);
const decoder = new TextDecoder();
async function open() {
  const response = await fetch(base + '/', { redirect: 'manual' });
  const html = await response.text();
  const sid = /sid:'([^']+)'/.exec(html)[1];
  const cookie = response.headers.getSetCookie().map(v => v.split(';')[0]).filter(v => !v.endsWith('=')).join('; ');
  const ws = new WebSocket(base.replace(/^http/, 'ws') + '/bridge/web-socket/' + sid,
    { protocols: ['json'], headers: { Cookie: cookie, Origin: base } });
  ws.binaryType = 'arraybuffer';
  let view = null, revision = null, ready = !html.includes(',auth:true'), clicked = false;
  await new Promise((resolve, reject) => {
    const maybeClick = () => {
      if (!ready || clicked) return;
      clicked = true;
      const event = '0:1_2_1:click';
      ws.send(view ? JSON.stringify([7, `${view}:${revision}:0:${event}`]) : JSON.stringify([0, event]));
    };
    ws.onopen = maybeClick;
    ws.onerror = () => reject(new Error('socket error'));
    ws.onmessage = message => {
      const frame = JSON.parse(typeof message.data === 'string' ? message.data : decoder.decode(message.data));
      if (frame[0] === 19) { view = frame[2]; revision = frame[3]; }
      if (frame[0] === 20) revision = frame[3];
      if (frame[0] === 21) { ready = true; maybeClick(); }
      if (frame[0] === 0 && clicked) resolve();
    };
  });
  return ws;
}
const sockets = [];
for (let i = 0; i < Number(countArg); i++) sockets.push(await open());
writeFileSync(readyFile, String(sockets.length));
setInterval(() => {}, 1 << 30);
