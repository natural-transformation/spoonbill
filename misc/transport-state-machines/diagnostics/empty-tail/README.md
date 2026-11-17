# Empty-remainder allocation attribution

This is a fixed-count allocation diagnostic, not a latency or acceptance run.
`Decoder` queues the `takeBack` of every `Action.Fork`, including an empty byte
value. The fragmented public fixture therefore feeds 64 empty remainders back
to its framing decoder, in addition to its 75 upstream chunks.

The two probe sources differ only in construction of the old decoder versus its
new storage cell. Both seed an unfinished payload, warm one million empty calls,
then measure another million calls using the still-live main thread's allocation
counter. Every result must be `TakeNext` and the body must remain incomplete.

| Implementation | Bytes per empty input |
| --- | ---: |
| Baseline `1f06cd3a` | 0 |
| Chunk design 5 | 104 |
| Chunk design 6, explicit empty-input return | 0 |

The 104-byte cost is the checked input state, output state and tuple. Multiplied
by 64 empty tails, it explains 6,656 bytes of the approximately 6.6 KiB fragmented
regression. The earlier implicit-wrapper hypothesis did not explain it; design
5's full matched comparison remains rejected and retained. Exact before/after
production sources and hashes are in `../../evidence/chunked-v5` and
`../../evidence/chunked-v6`. All three raw diagnostic rows are retained here.

Compile each source with `prepare-chunked.sh CHECKOUT CLASSPATH PROBE_SOURCE`
through that checkout's Nix flake, then run `spoonbill.http.protocol.ChunkedEmptyInputProbe`
with a descriptive label using Java from the same Nix environment. These runs
used `-Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8`.
The diagnostic proves the empty-input allocation cause, not whole-decoder
acceptance: design 6 still missed the independent large-input p99 and trailer
throughput guides in its full comparison.
