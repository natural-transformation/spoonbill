// Measurement-only observer. Never reads request URLs/headers/bodies or frame payloads.
const fs = require('node:fs');
const path = require('node:path');
const {isDeepStrictEqual} = require('node:util');

const PHASES = Object.freeze(['bootstrap', 'begin', 'password', 'password-and-completion',
  'factor-and-completion', 'protected-action', 'protected-navigation', 'logout',
  'stale-cookie-denial', 'teardown']);
const COUNTERS = Object.freeze(['httpRequests', 'httpResponses', 'httpFinished', 'httpFailed',
  'httpCompletedExchanges', 'httpUpgradeRequestEventsExcluded', 'webSocketsOpened',
  'webSocketsClosed', 'webSocketErrors', 'webSocketFramesSent', 'webSocketFramesReceived',
  'cdpWebSocketsCreated', 'cdpWebSocketsClosed', 'cdpWebSocketFramesSent',
  'cdpWebSocketTextBinaryFramesSent', 'cdpWebSocketControlFramesSent',
  'cdpWebSocketContinuationFramesSent', 'cdpWebSocketUnknownFramesSent',
  'webkitRawSocketsCreated', 'webkitRawSocketsClosed', 'webkitRawDuplicateCloses',
  'webkitRawFramesSent', 'webkitRawTextBinaryFramesSent', 'webkitRawControlFramesSent',
  'webkitRawContinuationFramesSent', 'webkitRawUnknownFramesSent']);
const ISSUES = Object.freeze(['counter-limit', 'request-capability', 'duplicate-http-request',
  'pending-request-limit', 'response-capability', 'unmatched-http-response', 'unmatched-http-terminal',
  'finished-without-response', 'websocket-capability', 'duplicate-websocket', 'active-socket-limit',
  'workflow-incomplete', 'http-events-unobserved', 'websocket-frame-events-unobserved',
  'pending-http-at-stop', 'active-websocket-at-stop', 'cdp-attachment-failed', 'cdp-detach-failed',
  'cdp-lifecycle-mismatch', 'cdp-socket-limit', 'cdp-not-detached', 'cdp-opcode-unavailable',
  'webkit-attachment-failed', 'webkit-observation-incomplete', 'webkit-not-released']);
const SCOPE = Object.freeze({
  classification: 'correctness-fixture-diagnostic',
  http: 'Playwright page request/response/terminal events; request-start phase owns its response and terminal outcome',
  websocket: 'Playwright page WebSocket events and frame event counts, assigned to the current named phase at event receipt',
  chromiumSent: 'Distinct per-page public CDP Network.webSocketFrameSent raw event count, including events whose payload is absent from the Playwright wrapper. Opcode-only counters separate text/binary(1/2), continuation(0), control(8/9/10), and unknown. Usable sent counts use text/binary events, matching the public Playwright event opcode scope. No payload access. CDP detaches after workflow assertions, before context teardown.',
  webkitSent: 'Source-pinned local inspector Network.webSocketFrameSent metadata across at most two current/provisional/retiring sessions. Only request identities and opcode are accessed. Duplicate known closes are counted separately. Borrowed sessions are never detached/disposed or Network-disabled; owned listeners/hooks release after a Runtime.evaluate literal-zero measurement-control barrier, before context teardown.',
  exchanges: 'One finished HTTP request with a response is one completed HTTP exchange; redirects are separate requests',
  notMeasured: ['HTTP WebSocket upgrade traffic not exposed by page request events', 'TCP packets or wire round trips',
    'WebSocket protocol commands, command batches, or request/response exchanges', 'server-internal or other-client traffic'],
  excluded: ['APIRequestContext correctness/control probes', 'server readiness polling',
    'fault-routed completion-loss workflows', 'process-restart workflows'],
  contamination: 'The foreign-Origin API probe runs while real page completion delivery is paused. It is excluded from page counts but can affect server state/timing. Installing the page completion route disables HTTP resource caching for the rest of that workflow/context. These are not frozen normal-flow or warm-cache budgets.',
  privacy: 'Only enums, booleans, counters and an optional validated artifact hash are retained in output; no URLs, identities, cookies, headers, credentials, payloads or error messages.',
  capability: 'Raw event zero is not a zero-traffic assertion. Required directions unobserved during completed authentication have null usable counts and leave the observer inconclusive.',
  physicalScope: 'Usable counts remain selected-source notification counts. Physical data-frame calibration is separately scoped to synthetic cases; no whole-workload physical metric or protocol-command exchange metric is certified here.',
});

function assertChoice(value, choices, label) {
  if (!choices.includes(value)) throw new TypeError(`Invalid observer ${label}`);
  return value;
}
function artifactHash(value) {
  if (value == null || value === '') return null;
  if (!/^[0-9a-f]{64}$/.test(value)) throw new TypeError('Invalid observer artifact hash');
  return value;
}
function limitsOf(overrides = {}) {
  const limits = {pendingRequests: 64, activeSockets: 16, counter: 1000000, ...overrides};
  if (Object.keys(limits).some(key => !['pendingRequests', 'activeSockets', 'counter'].includes(key)) ||
      Object.values(limits).some(value => !Number.isSafeInteger(value) || value < 1 || value > 1000000))
    throw new TypeError('Invalid observer limits');
  return Object.freeze(limits);
}
function counters() { return Object.fromEntries(COUNTERS.map(name => [name, 0])); }
function exactKeys(object, keys) {
  return object && typeof object === 'object' && !Array.isArray(object) &&
    Object.keys(object).length === keys.length && keys.every(key => Object.hasOwn(object, key));
}

class BrowserExchangeObserver {
  constructor(page, metadata, options = {}) {
    this.metadata = {
      provider: assertChoice(metadata.provider, ['memory', 'jdbc'], 'provider'),
      engine: assertChoice(metadata.engine, ['chromium', 'webkit'], 'engine'),
      flow: assertChoice(metadata.flow, ['password-only', 'challenged'], 'flow'),
      artifactSha256: artifactHash(metadata.artifactSha256),
    };
    if (typeof page?.on !== 'function' || typeof page?.off !== 'function')
      throw new TypeError('Page event observation unavailable');
    this.page = page;
    this.limits = limitsOf(options.limits);
    this.totals = counters();
    this.phases = new Map();
    this.requests = new Map();
    this.sockets = new Map();
    this.excluded = new WeakSet();
    this.seenSockets = new WeakSet();
    this.issues = new Set();
    this.peakPendingRequests = 0;
    this.peakActiveSockets = 0;
    this.cdpIds = new Set();
    this.cdpRequested = false;
    this.cdpEnabled = false;
    this.cdpDetachAcknowledged = false;
    this.cdpIdsAtDetach = 0;
    this.peakCdpIds = 0;
    this.webkitRequested = false;
    this.webkitPinVerified = false;
    this.webkitReleased = false;
    this.stopped = false;
    this.listeners = [
      ['request', request => this.onRequest(request)],
      ['response', response => this.onResponse(response)],
      ['requestfinished', request => this.onTerminal(request, false)],
      ['requestfailed', request => this.onTerminal(request, true)],
      ['websocket', socket => this.onSocket(socket)],
    ];
    this.phase('bootstrap');
    for (const [event, listener] of this.listeners) page.on(event, listener);
  }
  phase(name) {
    assertChoice(name, PHASES, 'phase');
    if (this.stopped) return;
    if (this.phases.has(name)) throw new Error('Observer phase cannot be repeated');
    this.currentPhase = name;
    this.phases.set(name, counters());
  }
  issue(code) {
    // Codes come only from fixed literals in this module, never exception text.
    assertChoice(code, ISSUES, 'issue');
    if (this.issues.size < ISSUES.length) this.issues.add(code);
  }
  async attachChromium(context, page) {
    if (this.metadata.engine !== 'chromium' || this.cdpRequested || this.stopped)
      throw new Error('Invalid CDP observation attachment');
    this.cdpRequested = true;
    try {
      const session = await context.newCDPSession(page);
      this.cdpSession = session;
      if (this.stopped) { await this.stopCdp(); return; }
      if (typeof session?.on !== 'function' || typeof session?.off !== 'function' ||
          typeof session?.send !== 'function' || typeof session?.detach !== 'function')
        throw new Error('CDP capability unavailable');
      this.cdpListeners = [
        ['Network.webSocketCreated', event => this.onCdp('created', event.requestId)],
        ['Network.webSocketFrameSent', event => this.onCdp('sent', event.requestId, event.response?.opcode)],
        ['Network.webSocketClosed', event => this.onCdp('closed', event.requestId)],
      ];
      for (const [event, listener] of this.cdpListeners) session.on(event, listener);
      await session.send('Network.enable');
      this.cdpEnabled = true;
    } catch (_) {
      this.issue('cdp-attachment-failed');
      await this.stopCdp();
    }
  }
  onCdp(kind, requestId, opcode) {
    if (this.stopped || this.cdpStopping) return;
    if (typeof requestId !== 'string' || !requestId.length || requestId.length > 256 ||
        (kind === 'created' ? this.cdpIds.has(requestId) : !this.cdpIds.has(requestId))) {
      this.issue('cdp-lifecycle-mismatch'); this.stop(); return;
    }
    if (kind === 'created') {
      if (this.cdpIds.size >= this.limits.activeSockets) { this.issue('cdp-socket-limit'); this.stop(); return; }
      if (!this.bump('cdpWebSocketsCreated')) return;
      this.cdpIds.add(requestId);
      this.peakCdpIds = Math.max(this.peakCdpIds, this.cdpIds.size);
    } else if (kind === 'sent') {
      this.bump('cdpWebSocketFramesSent');
      if (opcode === 1 || opcode === 2) this.bump('cdpWebSocketTextBinaryFramesSent');
      else if (opcode === 0) this.bump('cdpWebSocketContinuationFramesSent');
      else if ([8, 9, 10].includes(opcode)) this.bump('cdpWebSocketControlFramesSent');
      else { this.bump('cdpWebSocketUnknownFramesSent'); this.issue('cdp-opcode-unavailable'); }
    }
    else { this.cdpIds.delete(requestId); this.bump('cdpWebSocketsClosed'); }
  }
  stopCdp() {
    if (this.cdpStopping) return this.cdpStopping;
    if (!this.cdpSession) return Promise.resolve();
    const session = this.cdpSession;
    this.cdpIdsAtDetach = this.cdpIds.size;
    this.cdpIds.clear();
    this.cdpStopping = Promise.resolve().then(async () => {
      try {
        for (const [event, listener] of this.cdpListeners ?? []) session.off(event, listener);
        await session.detach();
        this.cdpDetachAcknowledged = true;
      } catch (_) { this.issue('cdp-detach-failed'); }
      finally { this.cdpSession = null; this.cdpListeners = []; }
    });
    return this.cdpStopping;
  }
  async attachWebKit(page, options = {}) {
    if (this.metadata.engine !== 'webkit' || this.webkitRequested || this.stopped)
      throw new Error('Invalid WebKit observation attachment');
    this.webkitRequested = true;
    try {
      const {createPinnedWebKitNetworkObserver} = await import('./performance/webkit-network-observer.mjs');
      if (this.stopped) { this.issue('webkit-attachment-failed'); return; }
      this.webkitObserver = createPinnedWebKitNetworkObserver(page, {...options, onEvent: (kind, opcode) => {
        if (kind === 'created') this.bump('webkitRawSocketsCreated');
        else if (kind === 'closed') this.bump('webkitRawSocketsClosed');
        else if (kind === 'duplicate-close') this.bump('webkitRawDuplicateCloses');
        else if (kind === 'sent') {
          this.bump('webkitRawFramesSent');
          this.bump(opcode === 1 || opcode === 2 ? 'webkitRawTextBinaryFramesSent' : opcode === 0 ?
            'webkitRawContinuationFramesSent' : [8, 9, 10].includes(opcode) ? 'webkitRawControlFramesSent' : 'webkitRawUnknownFramesSent');
        }
      }});
      this.webkitPinVerified = true;
    } catch (_) { this.issue('webkit-attachment-failed'); await this.stopWebKit(); }
  }
  stopWebKit() {
    if (this.webkitStopping) return this.webkitStopping;
    if (!this.webkitObserver) return Promise.resolve();
    const observer = this.webkitObserver;
    this.webkitStopping = observer.stop().then(result => {
      this.webkitResult = result; this.webkitReleased = result.released;
      if (result.status !== 'observed') this.issue('webkit-observation-incomplete');
    }, () => this.issue('webkit-observation-incomplete')).finally(() => { this.webkitObserver = null; });
    return this.webkitStopping;
  }
  stopRawSessions() { return Promise.all([this.stopCdp(), this.stopWebKit()]); }
  bump(name, phase = this.currentPhase) {
    if (this.stopped) return false;
    if (this.totals[name] >= this.limits.counter) {
      this.issue('counter-limit'); this.stop(); return false;
    }
    ++this.totals[name]; ++this.phases.get(phase)[name];
    return true;
  }
  onRequest(request) {
    if (this.stopped) return;
    if (typeof request?.resourceType !== 'function') { this.issue('request-capability'); this.stop(); return; }
    let type;
    try { type = request.resourceType(); } catch (_) { this.issue('request-capability'); this.stop(); return; }
    if (type === 'websocket') {
      this.excluded.add(request); this.bump('httpUpgradeRequestEventsExcluded'); return;
    }
    if (this.requests.has(request)) { this.issue('duplicate-http-request'); this.stop(); return; }
    if (this.requests.size >= this.limits.pendingRequests) { this.issue('pending-request-limit'); this.stop(); return; }
    if (!this.bump('httpRequests')) return;
    this.requests.set(request, {phase: this.currentPhase, response: false});
    this.peakPendingRequests = Math.max(this.peakPendingRequests, this.requests.size);
  }
  onResponse(response) {
    if (this.stopped) return;
    let request;
    try { request = response.request(); } catch (_) { this.issue('response-capability'); this.stop(); return; }
    if (this.excluded.has(request)) return;
    const record = this.requests.get(request);
    if (!record || record.response) { this.issue('unmatched-http-response'); this.stop(); return; }
    record.response = true;
    this.bump('httpResponses', record.phase);
  }
  onTerminal(request, failed) {
    if (this.stopped || this.excluded.has(request)) return;
    const record = this.requests.get(request);
    if (!record) { this.issue('unmatched-http-terminal'); this.stop(); return; }
    this.requests.delete(request);
    this.bump(failed ? 'httpFailed' : 'httpFinished', record.phase);
    if (!failed) {
      if (record.response) this.bump('httpCompletedExchanges', record.phase);
      else this.issue('finished-without-response');
    }
  }
  onSocket(socket) {
    if (this.stopped) return;
    if (typeof socket?.on !== 'function' || typeof socket?.off !== 'function') {
      this.issue('websocket-capability'); this.stop(); return;
    }
    if (this.seenSockets.has(socket)) { this.issue('duplicate-websocket'); this.stop(); return; }
    if (this.sockets.size >= this.limits.activeSockets) { this.issue('active-socket-limit'); this.stop(); return; }
    if (!this.bump('webSocketsOpened')) return;
    this.seenSockets.add(socket);
    const listeners = [
      ['framesent', () => this.bump('webSocketFramesSent')],
      ['framereceived', () => this.bump('webSocketFramesReceived')],
      ['socketerror', () => this.bump('webSocketErrors')],
      ['close', () => { this.bump('webSocketsClosed'); this.removeSocket(socket); }],
    ];
    this.sockets.set(socket, listeners);
    this.peakActiveSockets = Math.max(this.peakActiveSockets, this.sockets.size);
    for (const [event, listener] of listeners) socket.on(event, listener);
  }
  removeSocket(socket) {
    for (const [event, listener] of this.sockets.get(socket) ?? []) socket.off(event, listener);
    this.sockets.delete(socket);
  }
  stop() {
    if (this.stopped) return;
    this.pendingAtStop = this.requests.size;
    this.socketsAtStop = this.sockets.size;
    this.stopped = true;
    for (const [event, listener] of this.listeners) this.page.off(event, listener);
    for (const socket of this.sockets.keys()) this.removeSocket(socket);
    this.requests.clear();
    this.page = null;
    void this.stopRawSessions();
  }
  finish({completed}) {
    if (this.report) return this.report;
    if (typeof completed !== 'boolean') throw new TypeError('Completed workflow flag required');
    if (!completed) this.issue('workflow-incomplete');
    if (!this.totals.httpRequests || !this.totals.httpResponses) this.issue('http-events-unobserved');
    const sentSource = this.webkitPinVerified ? 'webkit-pinned-inspector-text-binary-events' :
      this.cdpEnabled ? 'chromium-cdp-text-binary-events' : 'playwright-websocket-frame-events';
    const sent = this.webkitPinVerified ? this.totals.webkitRawTextBinaryFramesSent :
      this.cdpEnabled ? this.totals.cdpWebSocketTextBinaryFramesSent : this.totals.webSocketFramesSent;
    if (!this.totals.webSocketsOpened || !sent || !this.totals.webSocketFramesReceived)
      this.issue('websocket-frame-events-unobserved');
    if (this.cdpRequested && !this.cdpDetachAcknowledged) this.issue('cdp-not-detached');
    if (this.webkitRequested && !this.webkitReleased) this.issue('webkit-not-released');
    this.stop();
    if (this.pendingAtStop) this.issue('pending-http-at-stop');
    if (this.socketsAtStop) this.issue('active-websocket-at-stop');
    const completeCapture = completed && ![...this.issues].some(code =>
      !['http-events-unobserved', 'websocket-frame-events-unobserved'].includes(code));
    this.report = {
      type: 'workflow', schemaVersion: 4, ...this.metadata,
      classification: SCOPE.classification, completedWorkflow: completed,
      observerStatus: this.issues.size ? 'inconclusive' : 'observed',
      issues: [...this.issues], limits: this.limits,
      counters: {...this.totals}, phases: Object.fromEntries(this.phases),
      sentFrameSource: sentSource,
      cdp: {requested: this.cdpRequested, enabled: this.cdpEnabled, detachAcknowledged: this.cdpDetachAcknowledged},
      webkit: {requested: this.webkitRequested, pinVerified: this.webkitPinVerified, released: this.webkitReleased},
      usableCounts: {
        httpCompletedExchanges: completeCapture && this.totals.httpRequests && this.totals.httpResponses
          ? this.totals.httpCompletedExchanges : null,
        webSocketFramesSent: completeCapture && sent ? sent : null,
        webSocketFramesReceived: completeCapture && this.totals.webSocketFramesReceived ? this.totals.webSocketFramesReceived : null,
      },
      resources: {peakPendingRequests: this.peakPendingRequests, peakActiveSockets: this.peakActiveSockets,
        pendingRequestsAtStop: this.pendingAtStop, activeSocketsAtStop: this.socketsAtStop,
        retainedRequestHandlesAfterStop: this.requests.size, retainedSocketHandlesAfterStop: this.sockets.size,
        peakCdpSocketIds: this.peakCdpIds, cdpSocketIdsAtDetach: this.cdpIdsAtDetach,
        retainedCdpSocketIdsAfterStop: this.cdpIds.size, retainedCdpSessionHandlesAfterStop: this.cdpSession ? 1 : 0,
        webkitSessionsCreated: this.webkitResult?.sessionsCreated ?? 0, webkitPeakSessions: this.webkitResult?.peakSessions ?? 0,
        webkitPeakActiveSocketIds: this.webkitResult?.peakActiveSocketIds ?? 0,
        retainedWebkitSessionRecordsAfterStop: this.webkitResult?.activeSessions ?? (this.webkitObserver ? 1 : 0),
        retainedWebkitSocketIdsAfterStop: this.webkitResult?.retainedSocketIds ?? 0,
        webkitPendingRetirementsAfterStop: this.webkitResult?.pendingRetirements ?? 0,
        webkitActiveIdsAtRetiredBoundaries: this.webkitResult?.activeIdsAtRetiredBoundaries ?? 0},
      webSocketProtocolExchanges: null,
      physicalWebSocketDataFramesSent: null,
    };
    return this.report;
  }
}

function validWorkflow(value) {
  const exact = exactKeys;
  const keys = ['type', 'schemaVersion', 'provider', 'engine', 'flow', 'artifactSha256', 'classification',
    'completedWorkflow', 'observerStatus', 'issues', 'limits', 'counters', 'phases', 'sentFrameSource', 'cdp', 'webkit', 'usableCounts', 'resources', 'webSocketProtocolExchanges', 'physicalWebSocketDataFramesSent'];
  if (!exact(value, keys) || value.type !== 'workflow' || value.schemaVersion !== 4 ||
      !['memory', 'jdbc'].includes(value.provider) || !['chromium', 'webkit'].includes(value.engine) ||
      !['password-only', 'challenged'].includes(value.flow) || value.classification !== SCOPE.classification ||
      typeof value.completedWorkflow !== 'boolean' || !['observed', 'inconclusive'].includes(value.observerStatus) ||
      !Array.isArray(value.issues) || value.issues.length > ISSUES.length || value.issues.some(issue => !ISSUES.includes(issue)) ||
      value.webSocketProtocolExchanges !== null || value.physicalWebSocketDataFramesSent !== null) return false;
  if (!['chromium-cdp-text-binary-events', 'webkit-pinned-inspector-text-binary-events', 'playwright-websocket-frame-events'].includes(value.sentFrameSource) ||
      !exact(value.cdp, ['requested', 'enabled', 'detachAcknowledged']) ||
      Object.values(value.cdp).some(field => typeof field !== 'boolean') ||
      (value.cdp.requested && value.engine !== 'chromium') ||
      (value.cdp.enabled && !value.cdp.requested) || (!value.cdp.requested && value.cdp.detachAcknowledged) ||
      (value.sentFrameSource === 'chromium-cdp-text-binary-events') !== value.cdp.enabled) return false;
  if (!exact(value.webkit, ['requested', 'pinVerified', 'released']) ||
      Object.values(value.webkit).some(field => typeof field !== 'boolean') ||
      (value.webkit.requested && value.engine !== 'webkit') ||
      (!value.webkit.requested && (value.webkit.pinVerified || value.webkit.released)) ||
      (value.sentFrameSource === 'webkit-pinned-inspector-text-binary-events') !== value.webkit.pinVerified) return false;
  try {
    if (artifactHash(value.artifactSha256) !== value.artifactSha256) return false;
    limitsOf(value.limits);
  } catch (_) { return false; }
  if (!exact(value.limits, ['pendingRequests', 'activeSockets', 'counter'])) return false;
  const validCounters = object => exact(object, COUNTERS) && Object.values(object).every(number =>
    Number.isSafeInteger(number) && number >= 0 && number <= value.limits.counter);
  if (!validCounters(value.counters) || !value.phases || typeof value.phases !== 'object' || Array.isArray(value.phases) ||
      Object.keys(value.phases).length > PHASES.length || Object.entries(value.phases).some(([phase, counts]) =>
        !PHASES.includes(phase) || !validCounters(counts))) return false;
  if (!Object.hasOwn(value.phases, 'bootstrap') || COUNTERS.some(name =>
    Object.values(value.phases).reduce((sum, phase) => sum + phase[name], 0) !== value.counters[name])) return false;
  if (value.observerStatus === 'observed' && Object.values(value.phases).some(phase =>
    phase.httpRequests !== phase.httpFinished + phase.httpFailed ||
    phase.httpCompletedExchanges !== phase.httpFinished ||
    phase.httpResponses < phase.httpFinished || phase.httpResponses > phase.httpRequests)) return false;
  if (value.counters.cdpWebSocketFramesSent !== value.counters.cdpWebSocketTextBinaryFramesSent +
      value.counters.cdpWebSocketControlFramesSent + value.counters.cdpWebSocketContinuationFramesSent +
      value.counters.cdpWebSocketUnknownFramesSent) return false;
  if (value.counters.webkitRawFramesSent !== value.counters.webkitRawTextBinaryFramesSent +
      value.counters.webkitRawControlFramesSent + value.counters.webkitRawContinuationFramesSent +
      value.counters.webkitRawUnknownFramesSent) return false;
  const sentCounter = value.webkit.pinVerified ? 'webkitRawTextBinaryFramesSent' :
    value.cdp.enabled ? 'cdpWebSocketTextBinaryFramesSent' : 'webSocketFramesSent';
  if (value.observerStatus === 'observed' && (!value.completedWorkflow || value.issues.length ||
      !value.counters.httpRequests || !value.counters.httpResponses || !value.counters.webSocketsOpened ||
      !value.counters[sentCounter] ||
      !value.counters.webSocketFramesReceived ||
      (value.cdp.requested && (!value.cdp.enabled || !value.cdp.detachAcknowledged || !value.counters.cdpWebSocketsCreated)) ||
      (value.webkit.requested && (!value.webkit.pinVerified || !value.webkit.released || !value.counters.webkitRawSocketsCreated)) ||
      value.counters.httpRequests !== value.counters.httpFinished + value.counters.httpFailed)) return false;
  if (!exact(value.usableCounts, ['httpCompletedExchanges', 'webSocketFramesSent', 'webSocketFramesReceived']) ||
      Object.entries(value.usableCounts).some(([name, count]) => count !== null &&
        (!Number.isSafeInteger(count) || count < 0 || count !== value.counters[
          name === 'webSocketFramesSent' ? sentCounter : name]))) return false;
  if ((!value.counters[sentCounter] && value.usableCounts.webSocketFramesSent !== null) ||
      (!value.counters.webSocketFramesReceived && value.usableCounts.webSocketFramesReceived !== null)) return false;
  const resources = ['peakPendingRequests', 'peakActiveSockets', 'pendingRequestsAtStop', 'activeSocketsAtStop',
    'retainedRequestHandlesAfterStop', 'retainedSocketHandlesAfterStop', 'peakCdpSocketIds',
    'cdpSocketIdsAtDetach', 'retainedCdpSocketIdsAfterStop', 'retainedCdpSessionHandlesAfterStop',
    'webkitSessionsCreated', 'webkitPeakSessions', 'webkitPeakActiveSocketIds', 'retainedWebkitSessionRecordsAfterStop',
    'retainedWebkitSocketIdsAfterStop', 'webkitPendingRetirementsAfterStop', 'webkitActiveIdsAtRetiredBoundaries'];
  if (!exact(value.resources, resources) || Object.values(value.resources).some(number =>
    !Number.isSafeInteger(number) || number < 0 || number > 1000000)) return false;
  if (value.resources.peakPendingRequests > value.limits.pendingRequests ||
      value.resources.peakActiveSockets > value.limits.activeSockets ||
      value.resources.peakCdpSocketIds > value.limits.activeSockets ||
      value.resources.cdpSocketIdsAtDetach > value.limits.activeSockets || value.resources.webkitPeakSessions > 2 ||
      value.resources.webkitSessionsCreated > 64 || value.resources.webkitPeakActiveSocketIds > 32) return false;
  if (value.observerStatus === 'observed' && (
      Object.values(value.usableCounts).some(count => count === null) ||
      ['pendingRequestsAtStop', 'activeSocketsAtStop', 'retainedRequestHandlesAfterStop',
        'retainedSocketHandlesAfterStop', 'retainedCdpSocketIdsAfterStop', 'retainedCdpSessionHandlesAfterStop',
        'retainedWebkitSessionRecordsAfterStop', 'retainedWebkitSocketIdsAfterStop', 'webkitPendingRetirementsAfterStop',
        'webkitActiveIdsAtRetiredBoundaries']
        .some(name => value.resources[name] !== 0) ||
      value.counters.httpCompletedExchanges !== value.counters.httpFinished ||
      value.counters.httpResponses < value.counters.httpFinished || value.counters.httpResponses > value.counters.httpRequests ||
      value.counters.webSocketsOpened !== value.counters.webSocketsClosed ||
      value.counters.cdpWebSocketUnknownFramesSent !== 0 ||
      value.counters.webkitRawUnknownFramesSent !== 0 || value.counters.webkitRawSocketsCreated !== value.counters.webkitRawSocketsClosed ||
      value.counters.cdpWebSocketsCreated !== value.counters.cdpWebSocketsClosed + value.resources.cdpSocketIdsAtDetach)) return false;
  return true;
}

function createExchangeOutput(destination, metadata, io = fs) {
  if (!path.isAbsolute(destination)) throw new TypeError('Observer output must be an absolute NEW file');
  const provider = assertChoice(metadata.provider, ['memory', 'jdbc'], 'provider');
  const artifactSha256 = artifactHash(metadata.artifactSha256);
  const fd = io.openSync(destination, 'wx', 0o600);
  let closed = false, records = 0, poisoned = false;
  const workflows = new Set();
  function write(value) {
    if (closed || poisoned) throw new Error('Observer output is closed or failed');
    const bytes = Buffer.from(JSON.stringify(value) + '\n');
    if (bytes.length > 65536) { poisoned = true; throw new Error('Observer record size limit'); }
    try {
      let offset = 0;
      while (offset < bytes.length) {
        const written = io.writeSync(fd, bytes, offset, bytes.length - offset);
        if (!Number.isSafeInteger(written) || written <= 0 || written > bytes.length - offset)
          throw new Error('Observer write did not progress');
        offset += written;
      }
    } catch (error) { poisoned = true; throw error; }
  }
  try { write({type: 'observer-start', schemaVersion: 4, provider, artifactSha256, scope: SCOPE,
    maximumWorkflowRecords: 4, maximumRecordBytes: 65536}); }
  catch (error) { closed = true; io.closeSync(fd); throw error; }
  return {
    record(value) {
      const identity = value?.engine + ':' + value?.flow;
      if (records >= 4 || !validWorkflow(value) || value.provider !== provider ||
          value.artifactSha256 !== artifactSha256 || workflows.has(identity))
        throw new Error('Invalid observer workflow record');
      write(value); workflows.add(identity); ++records;
    },
    close({completed}) {
      if (closed) return;
      try {
        if (!poisoned) write({type: 'observer-end', schemaVersion: 4,
          completedSuite: completed === true, workflowRecords: records});
        io.fsyncSync(fd);
      } finally { closed = true; io.closeSync(fd); }
    },
  };
}

function inspectExchangeOutput(text) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > 6 * 65536 || !text.endsWith('\n'))
    return {complete: false, reason: 'missing-or-truncated-output'};
  let values;
  try { values = text.trimEnd().split('\n').map(line => JSON.parse(line)); }
  catch (_) { return {complete: false, reason: 'invalid-jsonl'}; }
  const first = values[0], last = values.at(-1), workflows = values.slice(1, -1);
  if (!exactKeys(first, ['type', 'schemaVersion', 'provider', 'artifactSha256', 'scope', 'maximumWorkflowRecords', 'maximumRecordBytes']) ||
      !exactKeys(last, ['type', 'schemaVersion', 'completedSuite', 'workflowRecords']) ||
      first.type !== 'observer-start' || first.schemaVersion !== 4 || last.type !== 'observer-end' || last.schemaVersion !== 4 ||
      first.maximumWorkflowRecords !== 4 || first.maximumRecordBytes !== 65536 ||
      !isDeepStrictEqual(first.scope, SCOPE) || typeof last.completedSuite !== 'boolean' ||
      last.workflowRecords !== workflows.length || workflows.length !== 4 ||
      workflows.some(value => !validWorkflow(value) || value.provider !== first.provider || value.artifactSha256 !== first.artifactSha256) ||
      new Set(workflows.map(value => value.engine + ':' + value.flow)).size !== 4)
    return {complete: false, reason: 'missing-or-inconsistent-records'};
  if (!last.completedSuite || workflows.some(value => !value.completedWorkflow || value.observerStatus !== 'observed'))
    return {complete: false, reason: 'incomplete-observation'};
  return {complete: true, records: workflows.length};
}

module.exports = {BrowserExchangeObserver, createExchangeOutput, inspectExchangeOutput, artifactHash, SCOPE};
