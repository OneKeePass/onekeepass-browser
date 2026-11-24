// OneKeePass passkey interceptor — MAIN world script.
// Runs at document_start in the page's global scope (world: "MAIN").
// Overrides navigator.credentials to relay WebAuthn calls to the
// isolated-world content script via window.postMessage.
//
// NOTE: This file is plain JavaScript (not an ES module) so it works
// as a MAIN world content script without any import/export issues.

(function () {
  "use strict";

  // Embedded documents and browser-specific mediation stay with WebAuthn.
  if (!window.isSecureContext || window.top !== window || !navigator.credentials || !window.PublicKeyCredential) return;

  // ── base64url helpers ──────────────────────────────────────────────────────

  function buf2b64url(buf) {
    var arr = new Uint8Array(buf);
    var binary = "";
    for (var i = 0; i < arr.length; i++) {
      binary += String.fromCharCode(arr[i]);
    }
    return btoa(binary)
      .split("+").join("-")
      .split("/").join("_")
      .split("=").join("");
  }

  function b64url2buf(b64url) {
    if (!b64url) return null;
    var b64 = b64url.split("-").join("+").split("_").join("/");
    var pad = b64.length % 4;
    if (pad === 2) b64 += "==";
    else if (pad === 3) b64 += "=";
    var binary = atob(b64);
    var buf = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      buf[i] = binary.charCodeAt(i);
    }
    return buf.buffer;
  }

  // ── options serializer ─────────────────────────────────────────────────────

  function serializeOptions(pubKey) {
    return JSON.stringify(pubKey, function (_key, value) {
      if (value instanceof ArrayBuffer) return buf2b64url(value);
      if (ArrayBuffer.isView(value)) return buf2b64url(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
      return value;
    });
  }

  // ── credential constructors ────────────────────────────────────────────────

  function makeRegistrationCredential(cred) {
    var resp = cred.response;
    var transports = (resp.transports && resp.transports.length)
      ? resp.transports
      : ["internal"];
    return Object.setPrototypeOf({
      id:                      cred.id,
      rawId:                   b64url2buf(cred.rawId),
      type:                    cred.type,
      authenticatorAttachment: cred.authenticatorAttachment,
      response: Object.setPrototypeOf({
        clientDataJSON:        b64url2buf(resp.clientDataJSON),
        attestationObject:     b64url2buf(resp.attestationObject),
        authenticatorData:     b64url2buf(resp.authenticatorData),
        getTransports:         function () { return transports; },
        getPublicKey:          function () { return b64url2buf(resp.publicKey); },
        getPublicKeyAlgorithm: function () { return resp.publicKeyAlgorithm || -7; }
      }, AuthenticatorAttestationResponse.prototype),
      getClientExtensionResults: function () { return cred.clientExtensionResults || {}; },
      toJSON: function () { return cred; }
    }, PublicKeyCredential.prototype);
  }

  function makeAuthenticationCredential(cred) {
    var resp = cred.response;
    return Object.setPrototypeOf({
      id:                      cred.id,
      rawId:                   b64url2buf(cred.rawId),
      type:                    cred.type,
      authenticatorAttachment: cred.authenticatorAttachment,
      response: Object.setPrototypeOf({
        clientDataJSON:    b64url2buf(resp.clientDataJSON),
        authenticatorData: b64url2buf(resp.authenticatorData),
        signature:         b64url2buf(resp.signature),
        userHandle:        resp.userHandle ? b64url2buf(resp.userHandle) : null
      }, AuthenticatorAssertionResponse.prototype),
      getClientExtensionResults: function () { return cred.clientExtensionResults || {}; },
      toJSON: function () { return cred; }
    }, PublicKeyCredential.prototype);
  }

  const credentials = navigator.credentials;
  const pending = new Map();
  const origin = location.origin;
  let relayReady = false;

  function post(type, data) {
    window.postMessage({ type, ...data }, origin);
  }

  function finish(entry) {
    pending.delete(entry.id);
    clearTimeout(entry.timer);
    entry.options.signal?.removeEventListener("abort", entry.abort);
  }

  function useNative(entry) {
    finish(entry);
    // Adopt the native promise and pass all original options, including signal,
    // authenticator transports and mediation. This only affects this request.
    try {
      entry.resolve(entry.native(entry.options));
    } catch (error) {
      entry.reject(error);
    }
  }

  function relay(entry) {
    clearTimeout(entry.timer);
    entry.sent = true;
    const timeout = entry.options.publicKey.timeout;
    // Bound waiting if the extension unloads or its desktop process stops replying.
    const maxWait = Number.isFinite(timeout) ? Math.max(1000, Math.min(timeout, 120000)) : 120000;
    entry.timer = setTimeout(() => {
      finish(entry);
      post("OKP_PASSKEY_ABORT", { requestId: entry.id });
      entry.reject(new DOMException("The passkey request timed out", "NotAllowedError"));
    }, maxWait);
    post(entry.get ? "OKP_PASSKEY_GET" : "OKP_PASSKEY_CREATE", {
      requestId: entry.id,
      optionsJson: entry.optionsJson
    });
  }

  window.addEventListener("message", (event) => {
    if (event.source !== window || event.origin !== origin || !event.data) return;
    const data = event.data;
    if (data.type === "OKP_PASSKEY_READY") {
      relayReady = true;
      for (const entry of pending.values()) {
        if (!entry.sent) relay(entry);
      }
      return;
    }
    const entry = pending.get(data.requestId);
    if (!entry) return;
    if (data.type === "OKP_PASSKEY_BROWSER_DEFAULT") {
      useNative(entry);
      return;
    }
    if (data.type === "OKP_PASSKEY_REJECT") {
      finish(entry);
      entry.reject(new DOMException(data.message || "Operation not allowed", data.name || "NotAllowedError"));
      return;
    }
    if (data.type === (entry.get ? "OKP_PASSKEY_RESOLVE_GET" : "OKP_PASSKEY_RESOLVE_CREATE")) {
      finish(entry);
      try {
        const credential = JSON.parse(data.credentialJson);
        entry.resolve(entry.get ? makeAuthenticationCredential(credential) : makeRegistrationCredential(credential));
      } catch (error) {
        entry.reject(new DOMException("Failed to parse credential: " + error.message, "UnknownError"));
      }
    }
  });

  function wrap(native, get) {
    return function (options) {
      if (!options?.publicKey ||
          (options.mediation && !["optional", "required"].includes(options.mediation)) ||
          options.password || options.otp || options.identity || options.federated) {
        return native(options);
      }
      if (options.signal?.aborted) return Promise.reject(options.signal.reason);
      const id = crypto.randomUUID();
      return new Promise((resolve, reject) => {
        const entry = { id, options, optionsJson: serializeOptions(options.publicKey), native, get, resolve, reject };
        entry.abort = () => {
          finish(entry);
          if (entry.sent) post("OKP_PASSKEY_ABORT", { requestId: id });
          reject(options.signal.reason);
        };
        pending.set(id, entry);
        options.signal?.addEventListener("abort", entry.abort, { once: true });
        if (relayReady) relay(entry);
        else entry.timer = setTimeout(() => useNative(entry), 2000);
      });
    };
  }

  Object.defineProperties(credentials, {
    create: { configurable: true, writable: true, value: wrap(credentials.create.bind(credentials), false) },
    get: { configurable: true, writable: true, value: wrap(credentials.get.bind(credentials), true) }
  });
})();
