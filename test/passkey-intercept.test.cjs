const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { readFileSync } = require("node:fs");
const { test } = require("node:test");
const vm = require("node:vm");

const interceptor = readFileSync(require.resolve("../extension-dist/passkey-intercept-main.js"), "utf8");

function page() {
  const origin = "https://example.com";
  const listeners = [];
  const messages = [];
  const nativeCalls = [];
  const credentials = {
    create(options) {
      assert.equal(this, credentials);
      nativeCalls.push(["create", options]);
      return Promise.resolve("native-create");
    },
    get(options) {
      assert.equal(this, credentials);
      nativeCalls.push(["get", options]);
      return Promise.resolve("native-get");
    },
  };
  const window = {
    isSecureContext: true,
    PublicKeyCredential: class {},
    addEventListener(type, callback) {
      assert.equal(type, "message");
      listeners.push(callback);
    },
    postMessage(data, target) {
      assert.equal(target, origin);
      messages.push(data);
    },
  };
  window.top = window;
  const deliver = (data, from = origin) => {
    for (const callback of listeners) callback({ source: window, origin: from, data });
  };
  vm.runInNewContext(interceptor, {
    window, navigator: { credentials }, location: { origin },
    PublicKeyCredential: window.PublicKeyCredential, crypto: { randomUUID },
    btoa, atob, ArrayBuffer, Uint8Array, DOMException, setTimeout, clearTimeout,
  });
  return { credentials, deliver, messages, nativeCalls };
}

test("uses the original native get with the same options only for the chosen request", async () => {
  const { credentials, deliver, messages, nativeCalls } = page();
  const controller = new AbortController();
  const options = {
    publicKey: { challenge: new Uint8Array([1]), allowCredentials: [{ id: new Uint8Array([2]), transports: ["usb", "hybrid"] }] },
    mediation: "required",
    signal: controller.signal,
  };
  const result = credentials.get(options);
  assert.equal(messages.length, 0);
  deliver({ type: "OKP_PASSKEY_READY" });
  assert.equal(messages[0].type, "OKP_PASSKEY_GET");
  const requestId = messages[0].requestId;
  deliver({ type: "OKP_PASSKEY_BROWSER_DEFAULT", requestId }, "https://another.example");
  assert.equal(nativeCalls.length, 0);
  deliver({ type: "OKP_PASSKEY_BROWSER_DEFAULT", requestId });
  assert.equal(await result, "native-get");
  assert.equal(nativeCalls[0][1], options);
  const retry = credentials.get(options);
  assert.equal(messages[1].type, "OKP_PASSKEY_GET");
  assert.notEqual(messages[1].requestId, requestId);
  deliver({ type: "OKP_PASSKEY_REJECT", requestId: messages[1].requestId });
  await assert.rejects(retry, { name: "NotAllowedError" });
  assert.equal(nativeCalls.length, 1);
});

test("delegates registration to native create, and leaves conditional requests native", async () => {
  const { credentials, deliver, messages, nativeCalls } = page();
  deliver({ type: "OKP_PASSKEY_READY" });
  const conditional = { publicKey: { challenge: new Uint8Array([1]) }, mediation: "conditional" };
  assert.equal(await credentials.get(conditional), "native-get");
  assert.equal(messages.length, 0);
  const options = { publicKey: { challenge: new Uint8Array([2]) } };
  const result = credentials.create(options);
  assert.equal(messages[0].type, "OKP_PASSKEY_CREATE");
  deliver({ type: "OKP_PASSKEY_BROWSER_DEFAULT", requestId: messages[0].requestId });
  assert.equal(await result, "native-create");
  assert.equal(nativeCalls[1][1], options);
});

test("an aborted or already-settled request cannot trigger a native prompt", async () => {
  const { credentials, deliver, messages, nativeCalls } = page();
  deliver({ type: "OKP_PASSKEY_READY" });
  const controller = new AbortController();
  const result = credentials.get({
    publicKey: { challenge: new Uint8Array([3]) }, signal: controller.signal,
  });
  const requestId = messages[0].requestId;
  controller.abort();
  await assert.rejects(result, { name: "AbortError" });
  assert.equal(messages[1].type, "OKP_PASSKEY_ABORT");
  deliver({ type: "OKP_PASSKEY_BROWSER_DEFAULT", requestId });
  assert.equal(nativeCalls.length, 0);
});
