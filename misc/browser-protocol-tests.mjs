import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../modules/spoonbill/src/main/es6/protocol.js', import.meta.url), 'utf8');
const protocol = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const frontend = readFileSync(new URL('../modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala', import.meta.url), 'utf8');

test('every browser wire table agrees with the actual Scala declarations', () => {
  for (const kind of ['Procedure', 'CallbackType', 'PropertyType', 'ModifyDomProcedure']) {
    const declarations = [...frontend.matchAll(new RegExp(`case object\\s+(\\w+)\\s+extends ${kind}\\((\\d+)(?:,\\s*(\\d+))?\\)`, 'g'))];
    assert.equal(Object.keys(protocol[kind]).length, declarations.length);
    for (const [, name, code, width] of declarations) {
      const clientName = {EvalJsResponse: 'EVALJS_RESPONSE', SensitiveAcknowledgment: 'SENSITIVE_ACK'}[name] ??
        name.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase();
      assert.equal(protocol[kind][clientName], Number(code), `${kind}.${name}`);
      if (kind === 'ModifyDomProcedure') assert.equal(protocol.domOperandWidths()[Number(code)], Number(width));
    }
  }
});

test('v4 preserves the established authentication, ownership and disclosure wire codes', () => {
  assert.deepEqual([
    protocol.Procedure.COMPLETE_AUTHENTICATION, protocol.Procedure.RESET_VIEW,
    protocol.Procedure.PATCH_VIEW, protocol.Procedure.READY_FOR_USER_EVENTS,
    protocol.Procedure.PRESENT_SENSITIVE, protocol.Procedure.CLEAR_SENSITIVE,
    protocol.Procedure.SENSITIVE_NAVIGATION_COMPLETE, protocol.Procedure.SENSITIVE_DEPARTURE_COMPLETE,
  ], [18, 19, 20, 21, 22, 23, 24, 25]);
  assert.deepEqual([
    protocol.CallbackType.VIEW_EVENT, protocol.CallbackType.SENSITIVE_ACK,
    protocol.CallbackType.SENSITIVE_CLEARED, protocol.CallbackType.SENSITIVE_DEPARTURE,
  ], [7, 8, 9, 10]);
  assert.deepEqual(protocol.domOperandWidths(), [4, 3, 2, 5, 4, 3, 2]);
});
