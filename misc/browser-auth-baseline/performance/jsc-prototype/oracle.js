// Synthetic engine-only validation. No browser or backing-store metric is emitted.
function require(condition, message) {
    if (!condition)
        throw new Error(message);
}
function measured() {
    const bytes = spoonbillCellBytes();
    require(Number.isSafeInteger(bytes) && bytes >= 0, "unusable allocation ledger");
    return bytes;
}

// Native oracle independently sums metadata sizes of the actual returned cells.
// It holds those cells rooted and defers GC during each exact allocation window.
// One object leaves a partial list; large batches exhaust/refill multiple lists.
for (const count of [1, 17, 10000, 1, 10000]) {
    const allocated = spoonbillAllocationOracle(count);
    require(Number.isSafeInteger(allocated) && allocated >= count * 8, "native allocation oracle missing signal");
    const beforeGC = measured();
    gc();
    require(measured() >= beforeGC, "collection reset cumulative allocation");
}
// Native maximum inline capacity places actual JS cells above the explicit 400B cutoff.
require(spoonbillAllocationOracle(128, 1) > 0, "precise-cell oracle missing signal");

function transientWeakMaps() {
    for (let i = 0; i < 1000; ++i) {
        const map = new WeakMap();
        map.set({}, i);
    }
}
const reuseBefore = spoonbillReusedCellBytes();
for (let cycle = 0; cycle < 3; ++cycle) {
    transientWeakMaps();
    gc();
}
const reuseAfter = spoonbillReusedCellBytes();
require(reuseAfter > reuseBefore, "lower-tier precise-cell reuse was not observed");

// Release short-lived objects before observing: retained heap is not the counter.
function transientObjects(count) {
    for (let i = 0; i < count; ++i) {
        let value = {index: i, next: i + 1};
        if (value.index === -1)
            throw new Error("unreachable");
    }
}
transientObjects(32); // Warm this exact interpreter fixture.
gc();
const before = measured();
transientObjects(10000);
gc();
const after = measured();
require(after >= before + 10000 * 8, "short-lived allocations disappeared before observation");
print(JSON.stringify({schema: "spoonbill-jsc-cell-ledger-prototype-v1", nativeOracle: "passed",
    transientObjects: 10000, observedTransientCellBytes: after - before,
    preciseCellBytes: spoonbillPreciseCellBytes(), preciseReusedCellBytes: reuseAfter - reuseBefore,
    browserAllocatedBytesPerOperation: null,
    scope: "Experimental interpreter-only GC-cell bytes; JIT, auxiliary/backing and browser coverage unverified"}));
