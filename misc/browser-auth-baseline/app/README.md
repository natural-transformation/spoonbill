# Scala browser reference applications

This is counted v3 consumer integration glue for the Phase 0 comparison. It uses
one shared Scala UI, provider-specific host integration and the official Pekko
adapter. Both examples use Future/Direct; the memory instance discards authority
on restart, while JDBC retains committed host/browser/material records.

Run from the repository through its Nix environment:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'browserAuthBaseline/runMain spoonbill.browserauthbaseline.MemoryReferenceServer 8080'
```

Run the JDBC application against an owned disposable database:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'browserAuthBaseline/runMain spoonbill.browserauthbaseline.JdbcReferenceServer 8080 --initialize'
```

Initialization creates a new isolated synthetic schema at startup. For explicit
restart experiments retain the database, set `SPOONBILL_BASELINE_SCHEMA` to that
schema and restart without `--initialize`; see [JDBC contracts](../jdbc/README.md).
The reference key and accounts are public synthetic fixtures.

Open `http://localhost:8080`. Begin sign-in before submitting credentials. Use
`alice` / `password`, or `bob` / `password` followed by factor `123456`. The factor
form retains the original ceremony and challenge. Credentials are decoded with
`Secret` and never copied into presentation state. Typed unknown-commit results
offer recovery of the same attempt; the client does not repeat proof consumption.
The original ceremony also keeps a recovery form available after acknowledged
preparation, so reconnect can recover a completion command lost before browser
delivery. Recovery uses fresh binding/authority checks. Checking an attempt that
never committed fences it and requires a new sign-in; resubmitting an already
admitted attempt keeps its original recovery handle and hides the proof form.

After login, the protected increment checks the captured connection and current
principal, obtains a one-use execution authority, and authorizes the domain write
inside the host transaction. `/protected` checks HTTP authority before rendering.
Sensitive disclosure stays default-deny. Presentation contains only the account
display name, status, opaque lookup IDs and the last confirmed increment result.

Sign out opens a plain server-rendered HTML confirmation page. Its POST requires
the exact configured Origin, `Sec-Fetch-Site: same-origin`, and a valid binding
cookie. Success fences the browser slot, clears its session cookie and redirects
to `/`. Proof submission and ordinary actions continue to use WebSocket forms;
there is no application JavaScript, `evalJs` or copied transport implementation.
Credential and recovery forms declare a POST fallback to the unserved
`/reference-submit-unavailable` path. If the client removes its submit listener
during disconnect, native form submission fails closed and cannot put credentials
in the URL. This path has no authentication handler.

The small route wrapper supplies a fresh HttpOnly binding before v3's first HTTP
authorization and mirrors it into the response cookie. It never reparents an
existing credential to a new binding. Duplicate credential/binding cookies are
rejected. A stale credential with an existing binding yields an explicit denied
page with sign-out and public-page links; it does not create authenticated state.

Changing the port changes both the completion-origin policy and the WebSocket
guard origin. The server binds only to loopback. HTTP and cookie lifetimes are
synthetic settings inherited from the memory host; they are not the still-proposed
performance manifest's frozen acceptance settings. One owned one-second timer
admits at most one material-retirement operation at a time. Its work and SQL must
be included in idle/churn measurements. Shutdown cancels timer admission, drains
the server and releases the presentation registry and host. Restart discards all memory
authority and requires fresh login; no persisted lookup can restore execution.

Both providers passed password-only/factor flows in Chromium and WebKit. The JDBC
browser test also kills the server after commit and before reply, then restarts
the same schema and recovers the original attempt without repeating its proof.
The memory browser tests discard an acknowledged completion command and recover
the same attempt after socket loss while the host remains alive. Neither test
establishes Phase 0 completion, performance acceptance or release readiness.
