// Expected browser observations for RendererAcceptanceDocuments.scala.
// Ids are the server's Avocet ids for those trees: html is 1, head 1_1
// (bootstrap script, client script, title), body 1_2, the scenario container
// 1_2_1, the advance button 1_2_2 and the current button 1_2_3.
'use strict';
const assert = require('node:assert/strict');

// Operand counts per DOM operation code (protocol.js domOperandWidths):
// create, createText, remove, setAttr, removeAttr, setStyle, removeStyle.
const widths = [4, 3, 2, 5, 4, 3, 2];

function decodeOps(frame) {
  assert.equal(frame[0], 4, 'A MODIFY_DOM frame');
  const ops = [];
  for (let i = 1; i < frame.length;) {
    const width = widths[frame[i]];
    assert.ok(width !== undefined, `Unknown DOM operation ${frame[i]}`);
    ops.push(frame.slice(i, i + 1 + width));
    i += 1 + width;
  }
  return ops;
}

const sortOps = ops => ops.map(op => JSON.stringify(op)).sort();

const common = [
  {key: 'html', select: 'html', vId: '1'},
  {key: 'head', select: 'head', vId: '1_1'},
  {key: 'body', select: 'body', vId: '1_2'},
  {key: 'advance', select: '#advance', vId: '1_2_2', attrs: {id: 'advance'}, text: 'Advance'},
  {key: 'current', select: '#current', vId: '1_2_3', attrs: {id: 'current'}, text: 'Current'},
];
const commonKeys = common.map(d => d.key);

// Values duplicated from RendererAcceptanceDocuments.Encoded, so the test
// states the intended data independently of the service.
const E = {
  title0: 'a"b\'c<d>e&f=g;h:i/j\\k onclick="window.__f2Injected=\'attr\'"\n\ttab é☃',
  title1: 'x" onmouseover="window.__f2Injected=\'patch\'" y=\'z\' </div><script>window.__f2Injected=\'script\'</script>&amp;&lt;',
  data0: '{"a":"b\\"c","on":"x\\\\y","u":"' + String.fromCharCode(0x2028) + '"}',
  data1: '[1,"two",{"three":"\'4\'"}]',
  delims0: '"a;b:c{d}e"',
  delims1: '\'x;y:z\'',
  inject0: 'x" onmouseover="window.__f2Injected=\'style\'',
  inject1: 'y" onfocus="window.__f2Injected=\'patch-style\'',
  angle: '"</div><img src=x onerror=window.__f2Injected=\'angle\'>"',
  content0: '"a;b"',
  content1: '"c;d"',
  text0: '<b>bold?</b> & "quoted" \'single\' </p><script>window.__f2Injected=\'text\'</script>',
  text1: '<i>still text</i> &lt; ]]> --> <!-- x',
};

function longNames(N) {
  // The names must cross the former limits, or this case proves nothing.
  assert.equal(N.tag1024.length, 1024);
  assert.equal(N.tag1024b.length, 1024);
  assert.equal(N.attr1024.length, 1024);
  assert.equal(N.attr1024b.length, 1024);
  assert.equal(N.tag64.length, 64);
  assert.equal(N.attr256.length, 256);
  assert.ok(N.tagA.length > 10 && N.tagA.slice(0, 10) === N.tagB.slice(0, 10) && N.tagA !== N.tagB);
  assert.ok(N.attrA.length > 29 && N.attrA.slice(0, 29) === N.attrB.slice(0, 29) && N.attrA !== N.attrB);
  assert.equal(N.tag1024.slice(0, 1023), N.tag1024b.slice(0, 1023));
  const box = {key: 'box', select: '#long-names', vId: '1_2_1', attrs: {id: 'long-names'}};
  const b = {key: 'b', select: '#ln-b', vId: '1_2_1_2', localName: N.tagB, attrs: {id: 'ln-b', [N.attrA]: 'a0'}, text: 'text-b'};
  return {
    before: [...common, box,
      {key: 'a', select: '#ln-a', vId: '1_2_1_1', localName: N.tagA, attrs: {id: 'ln-a', [N.attrA]: 'a0', [N.attrB]: 'b0'}, text: 'text-a'},
      b,
      {key: 't64', select: '#ln-64', vId: '1_2_1_3', localName: N.tag64, attrs: {id: 'ln-64', [N.attr256]: 'v256'}},
      {key: 't1024', select: '#ln-1024', vId: '1_2_1_4', localName: N.tag1024, attrs: {id: 'ln-1024', [N.attr1024]: 'v0'}, text: 'text-1024-0'},
      {key: 't1024text', parent: '#ln-1024', index: 0, vId: '1_2_1_4_1', text: 'text-1024-0'}],
    absentBefore: ['#ln-new'],
    sentinel: () => document.querySelector('#ln-new') !== null,
    ops: [
      [3, '1_2_1_1', 0, N.attrA, 'a1', false],
      [4, '1_2_1_3', 0, N.attr256, false],
      [3, '1_2_1_3', 0, N.attr256b, 'v256b', false],
      [3, '1_2_1_4', 0, N.attr1024, 'v1', false],
      [1, '1_2_1_4', '1_2_1_4_1', 'text-1024-1'],
      [0, '1_2_1', '1_2_1_5', 0, N.tag1024b],
      [3, '1_2_1_5', 0, 'id', 'ln-new', false],
      [3, '1_2_1_5', 0, N.attr1024b, 'created', false],
    ],
    after: [...common, box,
      {key: 'a', select: '#ln-a', vId: '1_2_1_1', localName: N.tagA, attrs: {id: 'ln-a', [N.attrA]: 'a1', [N.attrB]: 'b0'}, text: 'text-a'},
      b,
      {key: 't64', select: '#ln-64', vId: '1_2_1_3', localName: N.tag64, attrs: {id: 'ln-64', [N.attr256b]: 'v256b'}},
      {key: 't1024', select: '#ln-1024', vId: '1_2_1_4', localName: N.tag1024, attrs: {id: 'ln-1024', [N.attr1024]: 'v1'}, text: 'text-1024-1'},
      {key: 't1024text', parent: '#ln-1024', index: 0, vId: '1_2_1_4_1', text: 'text-1024-1'},
      {key: 'new', select: '#ln-new', vId: '1_2_1_5', localName: N.tag1024b, attrs: {id: 'ln-new', [N.attr1024b]: 'created'}, children: 0}],
    retained: [...commonKeys, 'box', 'a', 'b', 't64', 't1024'],
    replaced: ['t1024text'],
    inert: '#ln-b',
  };
}

function collisions() {
  const box = {key: 'box', select: '#collisions', vId: '1_2_1', attrs: {id: 'collisions'}};
  const after = {key: 'after', select: '#co-after', vId: '1_2_1_7', attrs: {id: 'co-after'}, text: 'after'};
  const style = value => ({key: 'style', select: '#co-style', vId: '1_2_1_5', attrs: {id: 'co-style'},
    styleNames: ['--f2-collision'], styles: {'--f2-collision': value}});
  return {
    before: [...common, box,
      // HTML parsing and createElement lower-case the colliding names Aa and BB.
      {key: 'tag', select: '#co-tag', vId: '1_2_1_1', localName: 'aa', attrs: {id: 'co-tag'}, text: 'tag'},
      {key: 'attr', select: '#co-attr', vId: '1_2_1_2', attrs: {id: 'co-attr', aa: '1'}},
      {key: 'text', select: '#co-text', vId: '1_2_1_3', attrs: {id: 'co-text'}, text: 't86388'},
      {key: 'class', select: '#co-class', vId: '1_2_1_4', attrs: {id: 'co-class', class: 't86388'}},
      style('t86388'),
      {key: 'subtree', select: '#co-subtree', vId: '1_2_1_6', attrs: {id: 'co-subtree'}, text: 's121742'},
      {key: 'subtreeDiv', parent: '#co-subtree', index: 0, vId: '1_2_1_6_1', attrs: {}, text: 's121742'},
      after],
    absentBefore: ['bb'],
    sentinel: () => document.querySelector('#co-subtree').textContent === 's143709',
    ops: [
      [0, '1_2_1', '1_2_1_1', 0, 'BB'],
      [3, '1_2_1_1', 0, 'id', 'co-tag', false],
      [1, '1_2_1_1', '1_2_1_1_1', 'tag'],
      [4, '1_2_1_2', 0, 'Aa', false],
      [3, '1_2_1_2', 0, 'BB', '1', false],
      [1, '1_2_1_3', '1_2_1_3_1', 't118091'],
      [3, '1_2_1_4', 0, 'class', 't118091', false],
      [5, '1_2_1_5', '--f2-collision', 't118091'],
      [1, '1_2_1_6_1', '1_2_1_6_1_1', 's143709'],
    ],
    after: [...common, box,
      {key: 'tag', select: '#co-tag', vId: '1_2_1_1', localName: 'bb', attrs: {id: 'co-tag'}, text: 'tag'},
      {key: 'attr', select: '#co-attr', vId: '1_2_1_2', attrs: {id: 'co-attr', bb: '1'}},
      {key: 'text', select: '#co-text', vId: '1_2_1_3', attrs: {id: 'co-text'}, text: 't118091'},
      {key: 'class', select: '#co-class', vId: '1_2_1_4', attrs: {id: 'co-class', class: 't118091'}},
      style('t118091'),
      {key: 'subtree', select: '#co-subtree', vId: '1_2_1_6', attrs: {id: 'co-subtree'}, text: 's143709'},
      {key: 'subtreeDiv', parent: '#co-subtree', index: 0, vId: '1_2_1_6_1', attrs: {}, text: 's143709'},
      after],
    absentAfter: ['aa'],
    retained: [...commonKeys, 'box', 'attr', 'text', 'class', 'style', 'subtree', 'subtreeDiv', 'after'],
    replaced: ['tag'],
    inert: '#co-after',
  };
}

function encoding() {
  const box = {key: 'box', select: '#encoding', vId: '1_2_1', attrs: {id: 'encoding'}};
  const styleNames = ['--f2-delims', '--f2-inject', '--f2-angle', 'content'];
  const styled = (delims, inject, content) => ({key: 'style', select: '#en-style', vId: '1_2_1_2', attrs: {id: 'en-style'},
    styleNames, styles: {'--f2-delims': delims, '--f2-inject': inject, '--f2-angle': E.angle, content}, text: 'style host', children: 1});
  const canvas = (key, vId, style, attr) => ({key, select: `#en-${key}`, vId, localName: 'canvas',
    attrs: {id: `en-${key}`, width: attr}, styleNames: ['width'], styles: {width: style}});
  const after = {key: 'after', select: '#en-after', vId: '1_2_1_6', attrs: {id: 'en-after'}, text: 'after'};
  const hover = selector => page => page.hover(selector);
  return {
    // The serializer escapes quotes inside the style attribute instead of closing it.
    rawIncludes: ['--f2-inject:x&quot; onmouseover=&quot;window.__f2Injected=\'style\';', '&lt;img src=x onerror='],
    before: [...common, box,
      {key: 'attr', select: '#en-attr', vId: '1_2_1_1', attrs: {id: 'en-attr', title: E.title0, 'data-f2': E.data0}, text: 'attr host'},
      styled(E.delims0, E.inject0, E.content0),
      canvas('same-style', '1_2_1_3', '10px', '20'),
      canvas('same-attr', '1_2_1_4', '10px', '20'),
      {key: 'text', select: '#en-text', vId: '1_2_1_5', attrs: {id: 'en-text'}, text: E.text0, children: 1},
      after],
    absentBefore: ['#encoding img', '#encoding script', '#encoding b', '#encoding i'],
    sentinel: () => document.querySelector('#en-same-attr').getAttribute('width') === '21',
    ops: [
      [3, '1_2_1_1', 0, 'title', E.title1, false],
      [3, '1_2_1_1', 0, 'data-f2', E.data1, false],
      [5, '1_2_1_2', '--f2-delims', E.delims1],
      [5, '1_2_1_2', '--f2-inject', E.inject1],
      [5, '1_2_1_2', 'content', E.content1],
      [5, '1_2_1_3', 'width', '11px'],
      [3, '1_2_1_4', 0, 'width', '21', false],
      [1, '1_2_1_5', '1_2_1_5_1', E.text1],
    ],
    after: [...common, box,
      {key: 'attr', select: '#en-attr', vId: '1_2_1_1', attrs: {id: 'en-attr', title: E.title1, 'data-f2': E.data1}, text: 'attr host'},
      styled(E.delims1, E.inject1, E.content1),
      canvas('same-style', '1_2_1_3', '11px', '20'),
      canvas('same-attr', '1_2_1_4', '10px', '21'),
      {key: 'text', select: '#en-text', vId: '1_2_1_5', attrs: {id: 'en-text'}, text: E.text1, children: 1},
      after],
    absentAfter: ['#encoding img', '#encoding script', '#encoding b', '#encoding i'],
    retained: [...commonKeys, 'box', 'attr', 'style', 'same-style', 'same-attr', 'text', 'after'],
    replaced: [],
    inert: '#en-attr',
    // Native pointer and focus events reach every element whose data names a handler.
    actions: [hover('#en-attr'), hover('#en-style'), page => page.click('#en-style'), hover('#en-text'),
      page => page.focus('#en-style')],
  };
}

function firstPatch() {
  const box = {key: 'box', select: '#first-patch', vId: '1_2_1', attrs: {id: 'first-patch'}};
  const styled = {key: 'styled', select: '#fp-styled', vId: '1_2_1_1', attrs: {id: 'fp-styled', 'data-role': 'styled-parent'},
    styleNames: ['color', '--f2-parent'], styles: {color: 'rgb(1, 2, 3)', '--f2-parent': 'kept'}};
  const keep = {key: 'keep', select: '#fp-keep', vId: '1_2_1_1_2', attrs: {id: 'fp-keep'}, text: 'keep'};
  const component = [
    {key: 'component', select: '#fp-component', vId: '1_2_1_3', attrs: {id: 'fp-component'},
      styleNames: ['border-color'], styles: {'border-color': 'rgb(4, 5, 6)'}},
    {key: 'componentChild', select: '#fp-component-child', vId: '1_2_1_3_1', attrs: {id: 'fp-component-child'}, text: 'component boundary'},
    {key: 'componentButton', select: '#fp-component-button', vId: '1_2_1_3_2', attrs: {id: 'fp-component-button'}, text: 'Component'},
  ];
  const later = {key: 'later', select: '#fp-later', vId: '1_2_1_4', attrs: {id: 'fp-later'}, text: 'later sibling'};
  const stage = (target, loose, last) => [
    {key: 'target', select: '#fp-target', vId: '1_2_1_1_1', attrs: {id: 'fp-target'}, text: target},
    {key: 'targetText', parent: '#fp-target', index: 0, vId: '1_2_1_1_1_1', text: target},
    {key: 'loose', parent: '#first-patch', index: 1, vId: '1_2_1_2', text: loose},
    {key: 'last', select: '#fp-last', vId: '1_2_1_5', attrs: {id: 'fp-last', 'data-stage': last}, text: 'last'},
  ];
  return {
    before: [...common, box, styled, keep, ...component, later, ...stage('before', 'loose text', '0')],
    sentinel: () => document.querySelector('#fp-last').getAttribute('data-stage') === '1',
    ops: [
      [1, '1_2_1_1_1', '1_2_1_1_1_1', 'after'],
      [1, '1_2_1', '1_2_1_2', 'loose text patched'],
      [3, '1_2_1_5', 0, 'data-stage', '1', false],
    ],
    after: [...common, box, styled, keep, ...component, later, ...stage('after', 'loose text patched', '1')],
    retained: [...commonKeys, 'box', 'styled', 'keep', 'component', 'componentChild', 'componentButton', 'later',
      'target', 'last'],
    replaced: ['targetText', 'loose'],
    inert: '#fp-later',
  };
}

const scenarios = {'long-names': longNames, collisions, encoding, 'first-patch': firstPatch};

module.exports = {scenarios, decodeOps, sortOps};
