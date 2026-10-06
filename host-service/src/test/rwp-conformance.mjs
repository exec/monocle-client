// Draft RWP/1 host probe. Node 26+, no Monocle libraries or Minecraft session.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const { RWP_URL, RWP_TOKEN, RWP_WORKER_ID, RWP_SERVER, RWP_DIMENSION } = process.env;
assert([RWP_URL, RWP_TOKEN, RWP_WORKER_ID, RWP_SERVER, RWP_DIMENSION].every(Boolean),
  'Set RWP_URL, RWP_TOKEN, RWP_WORKER_ID, RWP_SERVER, and RWP_DIMENSION');
const endpoint = new URL(RWP_URL);
assert(['ws:', 'wss:'].includes(endpoint.protocol), 'Use a WebSocket endpoint');

const unknown = { jobId: randomUUID(), executionId: randomUUID(), generation: 1 };
let sequence = 0, stage = 0;
await new Promise((resolve, reject) => {
  const socket = new WebSocket(endpoint, { headers: { Authorization: `Bearer ${RWP_TOKEN}` } });
  const timeout = setTimeout(() => { socket.close(); reject(new Error('Host probe timed out')); }, 8_000);
  function send(type, payload, overrideSequence) {
    socket.send(JSON.stringify({
      apiVersion: 'rwp/1-draft', type, messageId: randomUUID(),
      correlationId: randomUUID(), workerId: RWP_WORKER_ID,
      sequence: overrideSequence ?? sequence++, sentAt: new Date().toISOString(), payload
    }));
  }
  socket.addEventListener('open', () => send('session.hello', {
    workerId: RWP_WORKER_ID, supportedVersions: ['rwp/1-draft'],
    implementation: { id: 'dev.monocle.probe', version: '1' },
    capabilities: [{ id: 'workers.wait.v1' }], lastHostSequence: 0, lastWorkerSequence: 0
  }));
  socket.addEventListener('message', event => {
    try {
      const frame = JSON.parse(event.data);
      assert.equal(frame.apiVersion, 'rwp/1-draft');
      assert.equal(frame.workerId, RWP_WORKER_ID);
      assert.equal(frame.sequence, stage);
      if (stage === 0) {
        assert.equal(frame.type, 'session.accepted');
        assert.equal(frame.payload.version, 'rwp/1-draft');
        assert.equal(frame.payload.implementation.id, 'dev.monocle.host');
        send('worker.observation', {
          observedAt: new Date().toISOString(),
          scope: { server: RWP_SERVER, dimension: RWP_DIMENSION },
          position: { x: 0, y: 64, z: 0 }
        });
        send('state.reconcile', {
          lastHostSequence: 0, lastWorkerSequence: 1, activeExecutions: [unknown]
        });
      } else if (stage === 1) {
        assert.equal(frame.type, 'state.reconciled');
        assert.equal(frame.payload.decisions.length, 1);
        assert.equal(frame.payload.decisions[0].decision, 'inspect');
        assert.equal(frame.payload.decisions[0].executionId, unknown.executionId);
        send('worker.observation', {}, sequence + 1); // Intentionally skip a session sequence.
      } else {
        assert.equal(frame.type, 'protocol.error');
        assert.equal(frame.payload.code, 'invalid_session');
      }
      stage++;
    } catch (error) { clearTimeout(timeout); socket.close(); reject(error); }
  });
  socket.addEventListener('close', () => {
    clearTimeout(timeout);
    if (stage === 3) resolve(); else reject(new Error(`Connection closed at stage ${stage}`));
  });
  socket.addEventListener('error', error => { clearTimeout(timeout); reject(error.error ?? error); });
});
console.log('RWP/1 host probe passed: hello, observation, reconciliation, and sequence rejection.');
