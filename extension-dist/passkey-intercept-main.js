// OneKeePass Firefox passkey interceptor — MAIN world script.
// Runs at document_start in the page's global scope (world: "MAIN").
// Overrides navigator.credentials to relay WebAuthn calls to the
// isolated-world content script via window.postMessage.
//
// NOTE: This file is plain JavaScript (not an ES module) so it works
// as a MAIN world content script without any import/export issues.

(function () {
  "use strict";

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
      if (value instanceof Uint8Array)  return buf2b64url(value);
      return value;
    });
  }

  // ── options deserializer ───────────────────────────────────────────────────
  // Converts base64url strings back to ArrayBuffers for the known binary fields
  // in a parsed publicKey options object (mutates in place, returns the object).

  function deserializeOptions(opts) {
    if (!opts) return opts;
    if (opts.challenge) opts.challenge = b64url2buf(opts.challenge);
    if (opts.user && opts.user.id) opts.user.id = b64url2buf(opts.user.id);
    if (opts.excludeCredentials) {
      for (var i = 0; i < opts.excludeCredentials.length; i++) {
        var cred = opts.excludeCredentials[i];
        if (cred.id) cred.id = b64url2buf(cred.id);
      }
    }
    return opts;
  }

  // ── credential constructors ────────────────────────────────────────────────

  function makeRegistrationCredential(cred) {
    var resp = cred.response;
    var transports = (resp.transports && resp.transports.length)
      ? resp.transports
      : ["internal"];
    return {
      id:                      cred.id,
      rawId:                   b64url2buf(cred.rawId),
      type:                    cred.type,
      authenticatorAttachment: cred.authenticatorAttachment,
      response: {
        clientDataJSON:        b64url2buf(resp.clientDataJSON),
        attestationObject:     b64url2buf(resp.attestationObject),
        authenticatorData:     b64url2buf(resp.authenticatorData),
        getTransports:         function () { return transports; },
        getPublicKey:          function () { return b64url2buf(resp.publicKey); },
        getPublicKeyAlgorithm: function () { return resp.publicKeyAlgorithm || -7; }
      },
      getClientExtensionResults: function () { return {}; }
    };
  }

  function makeAuthenticationCredential(cred) {
    var resp = cred.response;
    return {
      id:                      cred.id,
      rawId:                   b64url2buf(cred.rawId),
      type:                    cred.type,
      authenticatorAttachment: cred.authenticatorAttachment,
      response: {
        clientDataJSON:    b64url2buf(resp.clientDataJSON),
        authenticatorData: b64url2buf(resp.authenticatorData),
        signature:         b64url2buf(resp.signature),
        userHandle:        resp.userHandle ? b64url2buf(resp.userHandle) : null
      },
      getClientExtensionResults: function () { return {}; }
    };
  }

  // ── pending requests ───────────────────────────────────────────────────────

  var pending = {};

  // ── persistent browser-default flag ───────────────────────────────────────
  // When true, all WebAuthn calls bypass OKP and go directly to the browser's
  // native handler. Set permanently when user chooses "Use Browser Default".

  var browserDefault = false;

  // ── original navigator.credentials functions ───────────────────────────────

  var origCreate = navigator.credentials && navigator.credentials.create
    ? navigator.credentials.create.bind(navigator.credentials)
    : null;
  var origGet = navigator.credentials && navigator.credentials.get
    ? navigator.credentials.get.bind(navigator.credentials)
    : null;

  // ── response listener (isolated world → MAIN world) ───────────────────────

  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || !data.type || !data.requestId) return;

    var entry = pending[data.requestId];
    if (!entry) return;

    if (data.type === "OKP_PASSKEY_RESOLVE_CREATE") {
      delete pending[data.requestId];
      try {
        var cred = makeRegistrationCredential(JSON.parse(data.credentialJson));
        entry.resolve(cred);
      } catch (e) {
        entry.reject(new DOMException("Failed to parse credential: " + e.message, "UnknownError"));
      }
    } else if (data.type === "OKP_PASSKEY_RESOLVE_GET") {
      delete pending[data.requestId];
      try {
        var cred = makeAuthenticationCredential(JSON.parse(data.credentialJson));
        entry.resolve(cred);
      } catch (e) {
        entry.reject(new DOMException("Failed to parse credential: " + e.message, "UnknownError"));
      }
    } else if (data.type === "OKP_PASSKEY_REJECT") {
      delete pending[data.requestId];
      entry.reject(new DOMException(
        data.message || "Operation not allowed",
        data.name    || "NotAllowedError"
      ));
    } else if (data.type === "OKP_PASSKEY_BROWSER_DEFAULT") {
      delete pending[data.requestId];
      // Persist: all future WebAuthn calls on this page bypass OKP
      browserDefault = true;
      if (origCreate && data.optionsJson) {
        var pubKey = deserializeOptions(JSON.parse(data.optionsJson));
        origCreate({ publicKey: pubKey })
          .then(entry.resolve)
          .catch(entry.reject);
      }
    }
  });

  // ── navigator.credentials override ────────────────────────────────────────

  Object.defineProperty(navigator, "credentials", {
    configurable: true,
    value: {
      create: function (options) {
        if (browserDefault) {
          return origCreate ? origCreate(options) : Promise.reject(new DOMException("Not supported", "NotSupportedError"));
        }
        if (!options || !options.publicKey) {
          return origCreate ? origCreate(options) : Promise.reject(new DOMException("Not supported", "NotSupportedError"));
        }
        return new Promise(function (resolve, reject) {
          var reqId = Math.random().toString(36) + "-" + Date.now();
          pending[reqId] = { resolve: resolve, reject: reject };
          window.postMessage({
            type:        "OKP_PASSKEY_CREATE",
            requestId:   reqId,
            optionsJson: serializeOptions(options.publicKey),
            origin:      location.origin
          }, "*");
        });
      },
      get: function (options) {
        if (browserDefault) {
          return origGet ? origGet(options) : Promise.reject(new DOMException("Not supported", "NotSupportedError"));
        }
        if (!options || !options.publicKey) {
          return origGet ? origGet(options) : Promise.reject(new DOMException("Not supported", "NotSupportedError"));
        }
        return new Promise(function (resolve, reject) {
          var reqId = Math.random().toString(36) + "-" + Date.now();
          pending[reqId] = { resolve: resolve, reject: reject };
          window.postMessage({
            type:        "OKP_PASSKEY_GET",
            requestId:   reqId,
            optionsJson: serializeOptions(options.publicKey),
            origin:      location.origin
          }, "*");
        });
      }
    }
  });
})();
