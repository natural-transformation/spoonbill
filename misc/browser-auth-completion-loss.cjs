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

// Select the one real response whose cookie acceptance will be faulted. A
// replacement may first deliver through either completion or checked recovery.
function createCookieDeliveryLoss() {
  let armed = false, deliveryPath;
  return {
    arm() {
      if (armed) throw new Error('Cookie delivery loss can only be armed once');
      armed = true;
    },
    route(method, path) {
      if (!armed || method !== 'POST' || !['/auth/complete', '/auth/recover'].includes(path)) return 'continue';
      if (deliveryPath === undefined) { deliveryPath = path; return 'fetch'; }
      // Never consume a second delivery. Checked recovery must remain available
      // to prove either held-cookie reuse or exhausted predecessor rejection.
      return path === '/auth/complete' ? 'abort' : 'continue';
    },
    snapshot() { return {deliveryPath}; },
  };
}

function createRequestDeliveryLoss() {
  let armed = false, deliveryPath;
  let attempts = 0, mixedPath = false, overBudget = false;
  return {
    arm() {
      if (armed) throw new Error('Request delivery loss can only be armed once');
      armed = true;
    },
    route(method, path) {
      if (!armed || method !== 'POST' || !['/auth/complete', '/auth/recover'].includes(path)) return 'continue';
      if (deliveryPath === undefined) deliveryPath = path;
      attempts++;
      mixedPath ||= path !== deliveryPath;
      overBudget ||= attempts > 3;
      if (mixedPath || overBudget || attempts < 3) return 'abort';
      // Hold the third real attempt until page departure, so automatic fallback
      // cannot deliver before the test deliberately starts a fresh bootstrap.
      return 'hold';
    },
    snapshot() { return {deliveryPath, attempts, mixedPath, overBudget}; },
  };
}

module.exports = {createCompletionLoss, createCookieDeliveryLoss, createRequestDeliveryLoss};
