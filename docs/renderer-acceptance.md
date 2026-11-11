# Real-service renderer acceptance (Avocet F2)

This check closes follow-up F2 of the Avocet 2.0.0 improvement plan. It runs
the Avocet renderer repairs end to end through Spoonbill: the real standalone
Scala service, its HTML5 server-side rendering (`Html5RenderContext`, no
pretty-printing), the bootstrap and the assembled browser client. The data is
synthetic. Chromium and WebKit come from the pinned `.#browser` shell.

Unit tests, serializer tests and the synthetic-transport client suites remain
separate evidence. They do not load the server-rendered page in a browser.

## Command

Run from the Spoonbill checkout. The script compiles the fixture, starts its own
JVM, enters `.#browser` itself and then runs one deliberate fault probe:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop . --no-write-lock-file --command \
  bash scripts/test-renderer-acceptance.sh
```

An optional argument names a file with one compiled `standalone/Test/fullClasspath`
line, as for `test-sensitive-acknowledgement.sh`.

| File | Role |
| --- | --- |
| `modules/standalone/src/test/scala/korolev/server/RendererAcceptanceBrowserFixture.scala` | Loopback service. Each run is a fresh, unguarded `spoonbillService`. |
| `modules/standalone/src/test/scala/korolev/server/RendererAcceptanceDocuments.scala` | The four synthetic pages and their handler counters. |
| `modules/standalone/src/test/scala/avocet/RendererCollisionWitnesses.scala` | Avocet's fixed collision witnesses. The fixture refuses to start unless they still collide in the resolved avocet-core JAR. |
| `misc/renderer-acceptance-native-tests.cjs` | Browser runner. |
| `misc/renderer-acceptance-cases.cjs` | Expected server ids, attributes, styles, text and patch operations. |
| `scripts/test-renderer-acceptance.sh` | Owned-process runner and fault probe. |

## What each run does

The service is unguarded, so the browser keeps the parsed server-rendered page
as its baseline. The first event-driven patch therefore addresses parsed nodes.
(A guarded reconnect instead resets the view from an empty root.)

1. Start a fresh service run, then load `/` and keep the raw HTML5 response.
   It must start with the doctype and `<html>`, contain no whitespace between
   tags, and contain no serialized event-handler attribute or markup from data.
2. Wait for `Spoonbill.ready`. For every described node, require that the
   client's registry entry for the server's Avocet id is that parsed node
   (`Spoonbill.element(id) === node`). Also check exact attribute names and
   values, style values, text and child counts.
3. Click `#advance` once. Require exactly one `MODIFY_DOM` frame.
4. Check the patched DOM: exact nodes and values, retained identity of
   unaffected nodes, and new identity only where a node is replaced.
5. Decode the frame and compare its exact operations with the expected list,
   as a multiset.
6. Click an element without a handler. In the encoding case, also hover, click
   and focus the data-bearing elements. No handler may run. Then click `#current`, whose handler the patch
   replaced: the current handler must run exactly once and the replaced one
   never. In the first-patch case the component's own button must also run its
   handler exactly once.
7. Require no injected global, no `on*` attribute anywhere, one WebSocket, no
   page error and no console error.

Styles are compared with the same engine's serialization of the intended value,
made on a detached element. That serialization must be non-empty. WebKit
re-quotes `'x'` as `"x"`, so a raw string comparison would test serialization,
not separation.

## Cases

| Case | Data | Required observation |
| --- | --- | --- |
| `long-names` | Tags of 12, 64 and 1024 characters; attributes of 31, 256 and 1024 characters. Pairs share the former 10-character tag and 29-character attribute prefixes. | Parsed names are exact. The patch updates one of two attributes that share 29 characters, removes and adds 256-character attributes, updates a 1024-character attribute and text, and creates a 1024-character element with a 1024-character attribute. The sibling that shares the tag prefix is untouched. |
| `collisions` | Avocet's witnesses: names `Aa`/`BB`, values `t86388`/`t118091`, subtrees `div(s121742)`/`div(s143709)`. | Each colliding change produces its operation and applies: replaced element (HTML lower-cases it to `aa`/`bb`), attribute removal and addition, text, class, a custom property and the text inside an otherwise equal subtree. |
| `encoding` | Quotes, `<`, `>`, `&`, `;`, `:`, braces, backslashes, newline, tab, U+2028, non-ASCII text, would-be `onclick`/`onmouseover`/`onfocus`/`onerror` and `</script>` text. Same-name `width` style and attribute. | Values survive SSR and setter updates exactly. The style attribute contains `&quot;` instead of closing. Style data never becomes an attribute, element or handler. Only the style or only the attribute of the same name changes. |
| `first-patch` | Styled parent with a changing child, a text node after an element sibling, a component boundary with a handler, later siblings and a last element whose attribute changes. | The first patch targets exactly the child text, the loose text and the last element. The styled parent, its other child, the component root, its children and later siblings keep their DOM identity. |

Each case runs with the default ids and with `presetIds = true`. With preset
ids every parsed element must carry `k` equal to its last server id segment;
the server writes it after the styles.

## Discovered defect: preset ids and text after an element

With `presetIds = true`, the Spoonbill 10.0.0 client registers a text node that
follows an element sibling under a wrong id. In `registerRoot`
(`modules/spoonbill/src/main/es6/spoonbill.js`), `lastId` takes the string value
of the `k` attribute, and `lastId + 1` concatenates strings. After an element
with `k="1"`, the next text node becomes `…_11` instead of `…_2`. The first
patch of that text cannot find its node, so the client appends a duplicate text
node at the end of the parent and leaves the stale text in place. Both engines
show it:

| Engine | Registry before the patch | Container after the patch |
| --- | --- | --- |
| Chromium 141 | `1_2_1_2` → none; `1_2_1_11` → the loose text | `div`, `loose text`, `div`, `p`, `p`, `loose text patched` |
| WebKit 26 | same | same |

The default (`presetIds = false`) is not affected. The defect is in the client,
not in Avocet, and also exists without the Avocet 2.0.0 upgrade.

The runner keeps this as a strict expected failure (`first-patch/preset`). The
run must fail with exactly the recorded message. If the failure changes or
disappears, the suite fails, so a fix must turn the case into an ordinary
passing one.

## Deliberate fault probe

After the matrix, the script restarts the runner for `long-names`, Chromium and
default ids with `SPOONBILL_F2_FAULT=truncate-names`. The service then shortens
long names in its DOM patches to the former 10/29-character limits. The runner
must exit with status 1 at the `patched-dom` phase; otherwise the script fails.
The recorded failure is an unexpected 29-character attribute next to the two
intended 31-character ones.

## Bounds and cleanup

| Step | Limit |
| --- | --- |
| Fixture compilation | 1200 s, plus 10 s to terminate |
| Service readiness | 45 s |
| Browser matrix | 300 s for the whole Node runner, plus 10 s to terminate |
| Fault probe | 60 s, plus 10 s to terminate |
| Browser launch | 15 s |
| Control requests, page actions and navigation | 10 s each |
| Waiting for a handler counter | 5 s |

The JVM binds a loopback port chosen by the kernel and reports it through a
readiness file. Cleanup stops only that JVM (TERM, then KILL after 22 s),
prints its log on failure and removes the script's temporary directory.
Browsers are closed by the runner in `finally`.

## Recorded run: 2026-11-11

| Item | Value |
| --- | --- |
| Producer | Avocet 2.0.0, tag `v2.0.0`, commit `c130282f48c600ae3e6afc8e89babb59ef1943ca`, Maven Central artifacts |
| Consumer | Spoonbill 10.0.0, tag `v10.0.0`, commit `f0d456344359eb63ea4b138d28352860eef49f3b`, plus the uncommitted F2 files above |
| avocet-core | `~/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/natural-transformation/avocet-core_3/2.0.0/avocet-core_3-2.0.0.jar`, SHA-256 `e29b093d93adb3f03eac939454714236c5763244442daa3809bb3816ca8e35db` |
| avocet-events | `~/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/natural-transformation/avocet-events_3/2.0.0/avocet-events_3-2.0.0.jar`, SHA-256 `9c80549be65f714f8ce3843c1946184524759078a7b14edd6c2326198bfe67c7` |
| Environment | nixpkgs `c0b0e0fddf73fd517c3471e546c0df87a42d53f4` (same as Avocet), OpenJDK 21.0.8 (Zulu), Scala 3.3.8, sbt 1.10.7, Node 24.12.0, macOS 27.0.1 arm64 |
| Browsers | Playwright 1.56.1; Chromium 141.0.7390.37; WebKit 26.0 |

The script prints both Avocet JAR paths from the classpath that the service
actually uses. `show spoonbill/Compile/dependencyClasspath` resolved the same
two JARs.

| Engine | Default ids | Preset ids |
| --- | --- | --- |
| Chromium 141.0.7390.37 | `long-names`, `collisions`, `encoding`, `first-patch`: pass | `long-names`, `collisions`, `encoding`: pass; `first-patch`: known defect reproduced |
| WebKit 26.0 | `long-names`, `collisions`, `encoding`, `first-patch`: pass | `long-names`, `collisions`, `encoding`: pass; `first-patch`: known defect reproduced |

Fault probe: detected (status 1, phase `patched-dom`). The script exited 0.

Related checks from the same worktree:

| Command | Result |
| --- | --- |
| `sbt 'show spoonbill/Compile/dependencyClasspath' 'spoonbill/test' 'testkit/test'` | Avocet core/events 2.0.0; 645 and 19 tests passed |
| `sbt 'spoonbill/testOnly spoonbill.server.internal.Html5RenderContextSpec spoonbill.internal.RenderLifecycleSpec spoonbill.DevModeSavedRenderContextSpec spoonbill.server.internal.services.InvalidDevRenderContextSpec'` | 14 tests passed |
| `sbt 'standalone/test'` (with the F2 fixture sources) | 39 tests passed |
| `scripts/test-sensitive-acknowledgement.sh` | Pass in Chromium and WebKit; obsolete-event and authorization checks retained |

## Limits of this evidence

- The run used the unguarded startup path, where the parsed page is the
  baseline. Guarded views reset from an empty root; their patching is covered by
  the sensitive-acknowledgement and authentication suites, not by these cases.
- Each case checks one patch after bootstrap. Later patches, keyed moves and
  reconnects are out of scope.
- The recorded run was on macOS. The `native-browser` job in
  `.github/workflows/scala.yml` now runs this script after the sensitive
  acknowledgement step, but its first Linux run is still pending. The runner
  applies the same Linux WebKit EGL setting as the other native fixtures.
- WebKit is Playwright's WebKit build, not Safari.
