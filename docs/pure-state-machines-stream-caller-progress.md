# Stream operator caller integration

## Adapter fanout bound

Akka and Pekko fanout publishers now explicitly construct `Hub(stream, 128)`.
The generic Hub default remains unchanged. Both adapter API comments state the
fixed lossless per-subscriber capacity and detach-only subscriber cancellation.

Each adapter has two public Reactive Streams regressions. A registered subscriber
with no demand stops fast-subscriber progress at exactly 128 source elements;
subsequent demand delivers the complete FIFO sequence to both subscribers and
completes each once. Canceling the full slow subscriber releases backpressure,
allows the fast subscriber to receive the entire remaining sequence and EOF,
and never cancels the shared upstream or signals the canceled subscriber.

- `akka/testOnly *StreamPublisherFanoutCapacitySpec`: pass, 2 tests.
- `pekko/testOnly *StreamPublisherFanoutCapacitySpec`: pass, 2 tests.
- Explicit-file test formatting and `git diff --cached --check`: pass.

## Legacy Frontend partition overflow

The approved whole-partition overflow contract exposed a caller lifecycle gap.
Legacy sessions give Frontend one incoming Hub lane and use another for the idle
activity watcher. Canceling the failed partition detaches only Frontend's lane;
the shared raw source and activity watcher remain live. Legacy callback recovery
does not normally close the whole frontend, so the output/retirement signal could
remain pending indefinitely while peer traffic kept the watcher active.

Two public-topology regressions failed before the caller fix: the history rack
received `StreamSortOverflowException`, but output EOF and retirement never
arrived. One case first ends the control consumer on an unrelated legacy callback
error, proving that handling overflow only in that consumer would be insufficient.

Each legacy rack now propagates only `StreamSortOverflowException` through
`Frontend.close`. The original named exception is rethrown even if nonfatal
frontend cleanup fails. Other legacy callback handling remains unchanged. The
guarded incoming-queue path is unchanged.

The cleanup path is: overflow reaches any pending rack pull; frontend close
fences interactions and ends its output queue; output EOF drives the existing
transport release; release cancels raw incoming input; its consumed signal drives
the existing legacy session retirement/application destruction. No Hub subscriber
is given authority to cancel its siblings. The regression recreates that topology
and its transport-release/consumed-signal boundary.

The final cases cover normal overflow, an already-ended control consumer and a
frontend-lane cleanup failure. They preserve the identical overflow object across
racks, close output, and trigger raw-input/retirement once. The unrelated control
error is explicitly observed to leave the frontend live before overflow.

`spoonbill/testOnly *FrontendRoutingOverflowSpec *FrontendCloseSpec
*FrontendDownloadOwnershipSpec *BrowserRpcLifecycleSpec *ClientEventListenerSpec
*SessionsServiceSpec` passed 55 cases after the fix. The initial two overflow cases
failed before it. A focused final `*FrontendRoutingOverflowSpec` run verifies the
same-instance error assertion on all three cases. Validation uses the repository
Nix flake; no production operator or publisher state-machine logic was changed
by this caller fix.
