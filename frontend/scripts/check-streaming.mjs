import assert from 'node:assert/strict';
import { build } from 'esbuild';
import vm from 'node:vm';

const modules = {};
for (const name of ['directModel', 'request']) {
  const result = await build({
    entryPoints: [`src/api/${name}.ts`],
    bundle: true,
    write: false,
    format: 'cjs',
    platform: 'node',
    plugins: [{
      name: 'stream-test-dependencies',
      setup(builder) {
        builder.onResolve({ filter: /^@\// }, ({ path }) => ({ path, namespace: 'stub' }));
        builder.onLoad({ filter: /.*/, namespace: 'stub' }, ({ path }) => ({
          contents: path.endsWith('difyConfig')
            ? `export const getChatConfig = () => ({ apiUrl: '/gateway/chat', apiKey: 'server-side' });
               export const ensureLoaded = async () => {};
               export const gatewayAuthHeaders = () => ({});
               export const getModelsGatewayBase = () => '/gateway/models';`
            : `export const debug = { log() {}, warn() {}, error() {} };`,
        }));
      },
    }],
  });
  modules[name] = result.outputFiles[0].text;
}

const settle = async () => {
  for (let turn = 0; turn < 12; turn += 1) await Promise.resolve();
};

function createHarness(name) {
  let now = 0;
  let nextTimer = 1;
  const timers = new Map();
  const queue = [];
  let pendingRead;
  let signal;
  const events = { chunks: [], errors: [], completed: 0 };
  const context = {
    module: { exports: {} },
    AbortController,
    TextDecoder,
    console: { log() {}, debug() {}, warn() {}, error() {} },
    setTimeout(callback, delay) {
      const timerId = nextTimer++;
      timers.set(timerId, { at: now + delay, callback });
      return timerId;
    },
    clearTimeout(timerId) {
      timers.delete(timerId);
    },
    fetch: async (_url, options) => {
      signal = options.signal;
      return {
        ok: true,
        headers: { get: () => null },
        body: {
          getReader: () => ({
            read: () => {
              if (signal.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
              if (queue.length) return Promise.resolve(queue.shift());
              return new Promise((resolve, reject) => {
                pendingRead = resolve;
                signal.addEventListener('abort', () => {
                  pendingRead = undefined;
                  reject(new DOMException('Aborted', 'AbortError'));
                }, { once: true });
              });
            },
          }),
        },
      };
    },
  };
  vm.runInNewContext(modules[name], context);
  const options = {
    onChunk: (text) => events.chunks.push(text),
    onComplete: () => { events.completed += 1; },
    onError: (error) => events.errors.push(error.message),
  };
  const stop = name === 'directModel'
    ? context.module.exports.streamDirectChat('1', [], options)
    : context.module.exports.streamingRequest('/gateway/chat/chat-messages', {}, options);
  return {
    events,
    timers,
    stop,
    get aborted() { return signal?.aborted; },
    async advance(duration) {
      const target = now + duration;
      while (true) {
        const next = [...timers.entries()]
          .filter(([, timer]) => timer.at <= target)
          .sort((left, right) => left[1].at - right[1].at)[0];
        if (!next) break;
        now = next[1].at;
        timers.delete(next[0]);
        next[1].callback();
        await settle();
      }
      now = target;
      await settle();
    },
    async push(text, done = false) {
      const result = { done, value: new TextEncoder().encode(text) };
      if (pendingRead) {
        const resolve = pendingRead;
        pendingRead = undefined;
        resolve(result);
      } else {
        queue.push(result);
      }
      await settle();
    },
  };
}

let checks = 0;
for (const name of Object.keys(modules)) {
  {
    const harness = createHarness(name);
    await settle();
    await harness.advance(30000);
    assert.equal(harness.events.errors.length, 1, `${name}: headers alone must not disable timeout`);
    assert.match(harness.events.errors[0], /30/);
    assert.equal(harness.aborted, true);
    assert.equal(harness.events.completed, 0);
    assert.equal(harness.timers.size, 0);
    checks += 1;
  }
  {
    const harness = createHarness(name);
    await settle();
    await harness.advance(29000);
    const chunk = name === 'directModel'
      ? 'data: {"choices":[{"delta":{"content":"你好"}}]}\n\n'
      : 'data: {"event":"message","answer":"你好"}\n\n';
    await harness.push(chunk);
    assert.deepEqual(harness.events.chunks, ['你好']);
    await harness.advance(89999);
    assert.equal(harness.events.errors.length, 0, `${name}: first data must replace initial deadline`);
    await harness.advance(1);
    assert.equal(harness.events.errors.length, 1);
    assert.match(harness.events.errors[0], /90/);
    assert.equal(harness.events.completed, 0);
    assert.equal(harness.timers.size, 0);
    checks += 1;
  }
  {
    const harness = createHarness(name);
    await settle();
    await harness.push('');
    await harness.advance(30000);
    assert.equal(harness.events.errors.length, 1, `${name}: empty chunks are not first response bytes`);
    checks += 1;
  }
  {
    const harness = createHarness(name);
    await settle();
    harness.stop();
    assert.equal(harness.timers.size, 0, `${name}: cancel must clear timers synchronously`);
    await harness.advance(120000);
    assert.equal(harness.events.errors.length, 0, `${name}: user cancellation is not a timeout`);
    checks += 1;
  }
  {
    const harness = createHarness(name);
    await settle();
    await harness.push('', true);
    await harness.advance(120000);
    assert.equal(harness.events.completed, 1);
    assert.equal(harness.events.errors.length, 0);
    assert.equal(harness.timers.size, 0);
    checks += 1;
  }
  {
    const harness = createHarness(name);
    await settle();
    const terminal = name === 'directModel'
      ? 'data: [DONE]\n\n'
      : 'data: {"event":"message_end","conversation_id":"conversation-1"}\n\n';
    await harness.push(terminal);
    await harness.advance(120000);
    assert.equal(harness.events.completed, 1);
    assert.equal(harness.events.errors.length, 0);
    assert.equal(harness.timers.size, 0);
    checks += 1;
  }
  {
    const harness = createHarness(name);
    await settle();
    const error = name === 'directModel'
      ? 'data: {"error":{"message":"upstream failed"}}\n\n'
      : 'data: {"event":"error","message":"upstream failed"}\n\ndata: {"event":"error","message":"duplicate failure"}\n\n';
    await harness.push(error);
    assert.equal(harness.events.errors.length, 1);
    assert.equal(harness.timers.size, 0, `${name}: terminal errors must clear timeouts`);
    await harness.advance(120000);
    assert.equal(harness.events.errors.length, 1);
    assert.equal(harness.events.completed, 0);
    checks += 1;
  }
}
console.log(`Streaming: ${checks} regression checks passed`);
