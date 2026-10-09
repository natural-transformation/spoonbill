import assert from 'node:assert/strict';
import {copyFile, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import test from 'node:test';

class NodeFake {
  constructor(tag = 'div') { this.tag = tag; this.childNodes = []; this.attributes = new Map(); this.isConnected = true; }
  appendChild(node) { this.childNodes.push(node); node.parentNode = this; return node; }
  replaceChildren(...nodes) { this.childNodes = []; nodes.forEach(node => this.appendChild(node)); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  contains(node) { return this === node || this.childNodes.some(child => child.contains(node)); }
  querySelectorAll(tag) { return this.childNodes.flatMap(child => [...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)]); }
  attachShadow() { assert.equal(this.root, undefined, 'closed roots cannot be attached twice'); return (this.root = new NodeFake()); }
}

function fixture(SensitiveRegions) {
  const previous = {window: globalThis.window, document: globalThis.document,
    setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout};
  globalThis.window = new EventTarget();
  const document = new EventTarget();
  const hosts = new Map(['a', 'b', 'c', 'd', 'e'].map(name => {
    const host = new NodeFake('sb-secret');
    host.setAttribute('data-sb-region', name);
    return [name, host];
  }));
  document.documentElement = new NodeFake('html');
  document.querySelectorAll = selector => {
    const region = /="([a-z]+)"/.exec(selector)?.[1];
    return hosts.has(region) ? [hosts.get(region)] : [];
  };
  document.createElement = tag => new NodeFake(tag);
  globalThis.document = document;
  let timerId = 0;
  const timers = new Map();
  globalThis.setTimeout = callback => { timers.set(++timerId, callback); return timerId; };
  globalThis.clearTimeout = id => timers.delete(id);
  const acks = [], retired = [], departures = [];
  const registry = new Map(hosts);
  const regions = new SensitiveRegions(value => acks.push(value), value => retired.push(value), id => registry.get(id),
    counter => departures.push(counter));
  return {regions, acks, retired, departures, hosts, registry, timers, restore() {
    regions.destroy();
    Object.assign(globalThis, previous);
  }};
}

test('sensitive browser protocol', async t => {
  const temporary = await mkdtemp(join(tmpdir(), 'spoonbill-sensitive-'));
  try {
    await writeFile(join(temporary, 'package.json'), '{"type":"module"}');
    await copyFile(new URL('../modules/spoonbill/src/main/es6/sensitive.js', import.meta.url), join(temporary, 'sensitive.js'));
    const {SensitiveRegions} = await import(pathToFileURL(join(temporary, 'sensitive.js')));
    const connection = '11111111-1111-4111-8111-111111111111';
    const presentation = number => `22222222-2222-4222-8222-${String(number).padStart(12, '0')}`;
    const show = (f, number, region = 'a', payload = [0, ['synthetic-sensitive-code']]) =>
      f.regions.show(connection, presentation(number), region, 'mfa.recovery', 1000, payload, Date.now() + 1000);

    await t.test('replacement, late timers and stale server clears cannot erase a new presentation', () => {
      const f = fixture(SensitiveRegions);
      try {
        show(f, 1);
        const lateTimer = [...f.timers.values()][0];
        show(f, 2);
        lateTimer();
        f.regions.clear(connection, presentation(1), 'a');
        assert.equal(f.hosts.get('a').root.childNodes.length, 1);
        assert.deepEqual(f.retired, [`${connection}:${presentation(1)}:a`]);
        show(f, 1); // A consumed presentation ID must never be redisplayed.
        assert.equal(f.acks.at(-1), `${connection}:${presentation(1)}:a:failed`);
        f.regions.clear(connection, presentation(2), 'a');
        assert.equal(f.hosts.get('a').root.childNodes.length, 0);
        assert.equal(f.retired.length, 1, 'server clear must not echo a retirement notice');
      } finally { f.restore(); }
    });

    await t.test('expiry retires once and disconnect clears without retry or retirement feedback', () => {
      const f = fixture(SensitiveRegions);
      try {
        show(f, 1);
        const expire = [...f.timers.values()][0];
        expire(); expire();
        assert.equal(f.hosts.get('a').root.childNodes.length, 0);
        assert.deepEqual(f.retired, [`${connection}:${presentation(1)}:a`]);
        show(f, 2);
        f.regions.destroy();
        assert.equal(f.hosts.get('a').root.childNodes.length, 0);
        assert.equal(f.retired.length, 1);
        assert.equal(f.acks.length, 2, 'expiry/disconnect must not replay acknowledgments');
      } finally { f.restore(); }
    });

    await t.test('unrelated mutations preserve output but ancestor replacement clears before detach', () => {
      const f = fixture(SensitiveRegions);
      try {
        const parent = new NodeFake();
        parent.appendChild(f.hosts.get('a'));
        f.registry.set('ancestor', parent);
        show(f, 1);
        f.regions.beforePatch([3, 'other', 0, 'class', 'updated', false]);
        assert.equal(f.hosts.get('a').root.childNodes.length, 1);
        f.regions.beforePatch([2, 'body', 'ancestor']);
        assert.equal(f.hosts.get('a').isConnected, true, 'the renderer has not detached anything yet');
        assert.equal(f.hosts.get('a').root.childNodes.length, 0);
        assert.deepEqual(f.retired, [`${connection}:${presentation(1)}:a`]);
      } finally { f.restore(); }
    });

    await t.test('bounded records and input shapes fail without disclosing values in acknowledgments', () => {
      const f = fixture(SensitiveRegions);
      try {
        ['a', 'b', 'c', 'd'].forEach((region, index) => show(f, index + 1, region));
        show(f, 5, 'e');
        assert.equal(f.acks.at(-1), `${connection}:${presentation(5)}:e:failed`);
        f.regions.clear(connection, '', '');
        show(f, 6, 'a', [1, 'https://outside.invalid/synthetic-secret', null]);
        show(f, 7, 'a', [0, ['x'.repeat(8193)]]);
        show(f, 8, 'a', [0, ['\uD800']]);
        f.regions.show(connection, presentation(9), 'a', 'mfa.recovery', 300001, [0, ['secret']], Date.now() + 300001);
        assert.ok(f.acks.slice(-4).every(value => value.endsWith(':failed')));
        assert.ok(f.acks.every(value => !value.includes('synthetic') && !value.includes('secret')));
        assert.equal(f.retired.length, 0);
      } finally { f.restore(); }
    });

    await t.test('delayed or malformed absolute deadlines reject before creating visible content', () => {
      const f = fixture(SensitiveRegions);
      try {
        for (const [index, deadline] of [Date.now() - 1, undefined, NaN, Number.MAX_SAFE_INTEGER + 1].entries())
          f.regions.show(connection, presentation(index + 1), 'a', 'mfa.recovery', 1000, [0, ['secret']], deadline);
        assert.equal(f.hosts.get('a').root, undefined);
        assert.equal(f.timers.size, 0);
        assert.equal(f.acks.length, 4);
        assert.ok(f.acks.every(value => value.endsWith(':failed')));
      } finally { f.restore(); }
    });

    await t.test('attempted departure clears immediately and only its latest barrier restores fresh disclosure', () => {
      const f = fixture(SensitiveRegions);
      try {
        show(f, 1);
        window.dispatchEvent(new Event('beforeunload'));
        assert.equal(f.hosts.get('a').root.childNodes.length, 0);
        assert.deepEqual(f.departures, [1]);
        assert.equal(f.regions.departurePending, true);
        show(f, 2);
        f.regions.beforePatch([]);
        f.regions.reconcile();
        f.regions.completeNavigation(1);
        assert.equal(f.regions.departurePending, true);
        window.dispatchEvent(new Event('beforeunload'));
        assert.deepEqual(f.departures, [1, 2]);
        f.regions.completeDeparture(1);
        show(f, 3);
        assert.equal(f.hosts.get('a').root.childNodes.length, 0);
        f.regions.completeDeparture(2);
        assert.equal(f.regions.departurePending, false);
        show(f, 1);
        show(f, 2);
        show(f, 3);
        assert.ok(f.acks.slice(-3).every(value => value.endsWith(':failed')), 'old or blocked presentations replayed');
        show(f, 4);
        assert.equal(f.acks.at(-1), `${connection}:${presentation(4)}:a:ok`);
        assert.equal(f.hosts.get('a').root.childNodes.length, 1);
      } finally { f.restore(); }
    });

    await t.test('departure and history barriers are independent and pagehide or destroy remains terminal', () => {
      const f = fixture(SensitiveRegions);
      try {
        show(f, 1);
        f.regions.beginNavigation(1);
        window.dispatchEvent(new Event('beforeunload'));
        f.regions.completeDeparture(1);
        show(f, 2);
        assert.equal(f.hosts.get('a').root.childNodes.length, 0, 'departure barrier released history');
        f.regions.completeNavigation(1);
        show(f, 3);
        assert.equal(f.hosts.get('a').root.childNodes.length, 1);
        window.dispatchEvent(new Event('beforeunload'));
        window.dispatchEvent(new Event('pagehide'));
        f.regions.completeDeparture(2);
        f.regions.completeNavigation(1);
        show(f, 4);
        assert.equal(f.hosts.get('a').root.childNodes.length, 0, 'pagehide reopened');
        f.regions.destroy();
        f.regions.completeDeparture(2);
        show(f, 5);
        assert.equal(f.hosts.get('a').root.childNodes.length, 0, 'destroy reopened');
        window.dispatchEvent(new Event('beforeunload'));
        assert.deepEqual(f.departures, [1, 2], 'destroy retained its departure listener');
      } finally { f.restore(); }
    });

    await t.test('navigation rejects late frames until the latest exact barrier, without replay after reopening', () => {
      const f = fixture(SensitiveRegions);
      try {
        f.regions.beginNavigation(1);
        show(f, 1);
        f.regions.beforePatch([]);
        f.regions.reconcile();
        f.regions.completeNavigation(99);
        show(f, 2);
        assert.equal(f.hosts.get('a').root, undefined, 'generic DOM work or a foreign barrier reopened disclosure');
        f.regions.beginNavigation(2);
        f.regions.completeNavigation(1);
        show(f, 3);
        assert.equal(f.hosts.get('a').root, undefined, 'a stale barrier reopened the later navigation');
        f.regions.completeNavigation(2);
        show(f, 4);
        assert.equal(f.hosts.get('a').root.childNodes.length, 1);
        f.regions.clear(connection, presentation(4), 'a');
        show(f, 1);
        assert.equal(f.hosts.get('a').root.childNodes.length, 0, 'a previously blocked frame was replayed');
        window.dispatchEvent(new Event('pagehide'));
        f.regions.completeNavigation(2);
        show(f, 5);
        assert.equal(f.hosts.get('a').root.childNodes.length, 0, 'pagehide was reopened on the old instance');
      } finally { f.restore(); }
    });
  } finally { await rm(temporary, {recursive: true, force: true}); }
});
