// Protocol smoke worker. No Monocle imports and no Minecraft controls.
// Node 26+; used only by HostServiceTest against a loopback test host.
import { randomUUID } from 'node:crypto';

const { RWP_URL, RWP_TOKEN, RWP_WORKER_ID, RWP_SERVER, RWP_DIMENSION } = process.env;
if (![RWP_URL, RWP_TOKEN, RWP_WORKER_ID, RWP_SERVER, RWP_DIMENSION].every(Boolean))
  throw new Error('Missing mock-worker configuration');
const endpoint = new URL(RWP_URL);
if (endpoint.protocol !== 'ws:' || endpoint.hostname !== '127.0.0.1')
  throw new Error('Mock worker only connects to a loopback test host');

let socket, sequence, current, remainingMs = 0, deadline = 0, timer, stopping = false;
let position = { x: 0, y: 116, z: 0 };
const scope = { server: RWP_SERVER, dimension: RWP_DIMENSION };

function send(type, payload) {
  if (socket?.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify({
    apiVersion: 'rwp/1-draft', type, messageId: randomUUID(),
    correlationId: randomUUID(), workerId: RWP_WORKER_ID, sequence: sequence++,
    sentAt: new Date().toISOString(), payload
  }));
}
function observe() {
  send('worker.observation', { observedAt: new Date().toISOString(), scope, position });
}
function finish() {
  if (!current || socket?.readyState !== WebSocket.OPEN) return;
  send('execution.completed', current.ref);
  current = undefined;
  remainingMs = 0;
}
function resumeWait() {
  if (!current || current.action.type !== 'workers.wait.v1') return;
  deadline = Date.now() + remainingMs;
  timer = setTimeout(finish, remainingMs);
}
function cancel(ref) {
  clearTimeout(timer);
  current = undefined;
  remainingMs = 0;
  send('execution.cancelled', { ...ref, cleanup: 'acknowledged' });
}
function assignment(payload) {
  const { action, scope: targetScope, ...ref } = payload;
  if (current && current.ref.executionId === ref.executionId) return;
  if (targetScope.server !== scope.server || targetScope.dimension !== scope.dimension
      || !['workers.wait.v1', 'workers.travel.v1'].includes(action.type)) {
    send('execution.rejected', { ...ref, code: 'unsupported_assignment' });
    return;
  }
  current = { action, ref };
  send('execution.accepted', ref);
  send('execution.started', ref);
  if (action.type === 'workers.wait.v1') {
    remainingMs = action.arguments.ticks * 50;
    resumeWait();
  } else {
    // Deliberately simulated arrival; this process has no Minecraft authority.
    position = { x: action.arguments.x, y: action.arguments.y, z: action.arguments.z };
    observe();
    finish();
  }
}
function connect() {
  sequence = 0;
  socket = new WebSocket(endpoint, { headers: { Authorization: `Bearer ${RWP_TOKEN}` } });
  socket.addEventListener('open', () => send('session.hello', {
    workerId: RWP_WORKER_ID, supportedVersions: ['rwp/1-draft'],
    implementation: { id: 'dev.monocle.mock', version: '1' },
    capabilities: [{ id: 'workers.wait.v1' }, { id: 'workers.travel.v1' }],
    lastHostSequence: 0, lastWorkerSequence: 0
  }));
  socket.addEventListener('message', event => {
    const { type, payload } = JSON.parse(event.data);
    switch (type) {
      case 'session.accepted':
        observe();
        send('state.reconcile', { lastHostSequence: 0, lastWorkerSequence: sequence - 1,
          activeExecutions: current ? [current.ref] : [] });
        break;
      case 'state.reconciled':
        for (const result of payload.decisions) {
          if (result.decision === 'cancel') cancel(result);
          else if (result.decision === 'continue') resumeWait();
          else if (result.decision === 'inspect') current = undefined;
        }
        break;
      case 'execution.assign': assignment(payload); break;
      case 'execution.cancel': cancel(payload); break;
      case 'protocol.error': stopping = true; throw new Error(payload.code);
    }
  });
  socket.addEventListener('close', () => {
    clearTimeout(timer);
    if (current?.action.type === 'workers.wait.v1') remainingMs = Math.max(0, deadline - Date.now());
    if (!stopping) setTimeout(connect, 200);
  });
  socket.addEventListener('error', () => {}); // The close event handles bounded reconnect.
}
process.on('SIGTERM', () => { stopping = true; clearTimeout(timer); socket?.close(); });
connect();
