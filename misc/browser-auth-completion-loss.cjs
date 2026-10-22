// Test-only accounting for loss on one explicitly armed predecessor transport.
// The token is an object identity, never a URL, credential, or protocol payload.
function createCompletionLoss(onFault) {
  let target, faultSource;
  let faultCount = 0, observedControls = 0, droppedControls = 0, forwardedControls = 0;
  let witnessedTargetClose = false;
  const closed = new WeakSet();
  function fault(source) {
    if (faultSource !== undefined) return;
    faultSource = source;
    faultCount++;
    onFault(source);
  }
  return {
    arm(connection) {
      if (target !== undefined || !connection || closed.has(connection))
        throw new Error('Completion loss requires one live predecessor transport');
      target = connection;
    },
    upstreamClosed(connection) {
      closed.add(connection);
      if (connection !== target) return false;
      witnessedTargetClose = true;
      fault('upstream-close');
      return true;
    },
    dropCompletion(connection) {
      if (target === undefined) return false;
      observedControls++;
      if (connection !== target) { forwardedControls++; return false; }
      droppedControls++;
      fault('completion-command');
      // Also drop Blob-backed controls delivered after the close callback.
      return true;
    },
    snapshot() {
      return {faultSource, faultCount, observedControls, droppedControls, forwardedControls, witnessedTargetClose};
    },
  };
}

module.exports = {createCompletionLoss};
