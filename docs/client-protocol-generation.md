# Scala-owned browser protocol

The wire code declarations in `Frontend.scala` are the source of the browser
procedure, callback, property and DOM-operation tables. Each DOM operation also
declares its operand count. The browser retains its existing local admission,
ordering, stale-frame and sensitive-cleanup behavior.

`project/JsUtils.scala` emits the small `protocol.js` module from those literal
declarations. The generated module is checked in so the isolated Node and native
browser fixtures can load the real ES modules without a prior JVM build. It is
not an independently maintained protocol definition: resource assembly rejects
any difference from the Scala-derived output. Regenerate after changing the
Scala protocol:

```sh
nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'spoonbill/generateBrowserProtocol'
```

The generator handles only these closed declarations and fails on missing,
duplicate or noncontiguous literal codes and missing DOM operand widths. Closure
compiles the generated constants together with the browser modules; compilation
failure cannot publish a partial client. Existing wire values remain unchanged.

Concrete duplication removed: numeric dispatch labels and error handling in
`bridge.js`, handwritten callback/property tables and DOM labels in
`spoonbill.js`, and the independently maintained DOM-width array in `sensitive.js`.
The generated module remains part of source and delivered-byte accounting.
`client-inventory.mjs` reports handwritten and generated source separately and
counts the complete emitted/compressed bundle. Source-language changes alone do
not establish a latency or memory improvement.

The focused wire-parity test and existing browser safety suites run with:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs \
  misc/sensitive-client-tests.mjs misc/browser-auth-baseline/client-inventory.test.mjs
```
