# Browser authentication lifecycle

`BrowserViewMachine` makes the coordinator's six view phases explicit:

| Phase | Ownership | Attachment |
| --- | --- | --- |
| Claimed | Provider claim retained; local lease not prepared | Absent |
| Prepared | Local lease acquired; publication pending | Absent |
| Live | Provider/local ownership retained | Eligible for guarded observation |
| RetiringAttached | Denial or replacement has fenced new observations | Still indexed until a write turn detaches |
| RetiringDetached | Local attachment removed; provider release pending or failed | Absent |
| Released | Provider release acknowledged | Absent |

Initialization and the guard-close execution claim are separate metadata axes.
A reconstructed view can be live before initialization acknowledgement; that
acknowledgement controls presentation retention on ordinary close, not authority.
Failed guard cleanup releases its execution claim for retry without reversing
retirement or restoring discarded presentation. Retirement records whether the
first cause was authority denial, committed replacement or release.

Each attached resource holds one atomic metadata value. CAS retries compute only
pure transitions. Browser identities/views (including credentials), local lease
handles, clocks, host checks and policy callbacks remain outside the model.
The existing `owned` and `attached` maps remain resource indexes with unchanged
iteration order and reference-identity checks. This preserves predecessor and
logout traversal order even while failed detached claims remain owned. The model
now determines live eligibility, retirement, retention and release claims.

The interpreter preserves these effect boundaries:

- Origin validation precedes duplicate/capacity admission and provider capture/claim.
- A provider claim is indexed before local preparation can fail.
- Fresh provider authorization and `sameAuthority` precede local lease publication;
  guard/policy construction precedes attachment publication.
- The reconstructed-state acknowledgement remains a thunk, including with strict
  Future effects. It cannot run merely because a guard is constructed.
- Local retirement and attachment removal precede local lease release/invalidation,
  which precedes provider release. Ownership is removed only after acknowledgement.
- A read-turn denial claims retirement atomically, then performs its write-turn
  detach under the existing single cancellation mask before returning the outcome.
- Guard close claims execution before checking the coordinator's stopped marker;
  failure resets only that claim. Retain eligibility is read before retirement.

Predecessor selection still includes retiring attached views: a denied same-
authority predecessor must not donate presentation to a reconnect queued ahead
of its deferred detach. Ordinary authority checks require the Live phase. The
coordinator's stopped marker, prior non-secret history stamps and capacity-warning
state retain their existing separate contracts and timing; they are not moved
into per-view transitions.

Validation ran through the repository Nix environment:

| Command | Result |
| --- | --- |
| `spoonbill/testOnly *BrowserAuthenticationSpec *BrowserAuthenticationFailureSpec` before wiring | Pass, 41 tests |
| `ce3/testOnly *BrowserAuthenticationIOSpec` before wiring | Pass, 1 native cancellation test |
| `spoonbill/testOnly *BrowserAuthenticationSpec *BrowserAuthenticationFailureSpec *BrowserViewMachineSpec` after wiring | Pass, 46 tests |
| `ce3/testOnly *BrowserAuthenticationIOSpec` after wiring | Pass, 1 native cancellation test |
| Explicit-file `scalafmtOnly` for new model/test files; `git diff --check` | Pass |

The behavioral cases include failed-release retry, local fencing before delayed
provider settlement, policy-factory failure, bounded failed claims, takeover and
late cleanup, initialization acknowledgement, queued denial, deferred detach,
maintenance interleaving and replacement through the real dispatcher/bootstrap.
The native IO case proves cancellation cannot skip the detach owed by a read turn.
No credentials or host/clock decisions were added to the pure state, and no new
effect continuation or state-transition event is added to ordinary authority reads.
