(ns onekeepass.browser.background.passkey
  "Pure wrappers around chrome.webAuthenticationProxy (Chrome 108+, MV3 only).
   Do NOT require this namespace in Firefox builds — it is guarded at call sites
   via `available?` / the `is-firefox-browser?` check in core.cljs."
  (:require
   [onekeepass.browser.common.utils :as u]))

;; ── availability ──────────────────────────────────────────────────────────────

(defn available?
  "Returns true when the webAuthenticationProxy API is present in this browser."
  []
  (boolean (.-webAuthenticationProxy js/chrome)))

;; ── proxy lifecycle ───────────────────────────────────────────────────────────

(defn attach!
  "Registers this extension as the WebAuthn authenticator for all tabs.
   Must be called once from the background service worker."
  []
  (js/chrome.webAuthenticationProxy.attach)
  #_(u/okp-console-log "Webauth proxy attached"))

(defn detach!
  "Releases the WebAuthn proxy registration."
  []
  (js/chrome.webAuthenticationProxy.detach))

;; ── completion helpers ────────────────────────────────────────────────────────

(defn complete-create-ok!
  "Resolves a pending navigator.credentials.create() call with a credential JSON."
  [request-id response-json]
  (js/chrome.webAuthenticationProxy.completeCreateRequest
   (clj->js {:requestId request-id :responseJson response-json})))

(defn complete-create-error!
  "Rejects a pending navigator.credentials.create() call with a DOMException name
   (e.g. \"NotAllowedError\", \"InvalidStateError\").
   Both :name and :message are required by Chrome's WebAuthn proxy API."
  [request-id error-name]
  (js/chrome.webAuthenticationProxy.completeCreateRequest
   (clj->js {:requestId request-id :error {:name error-name :message error-name}})))

(defn complete-get-ok!
  "Resolves a pending navigator.credentials.get() call with an assertion JSON."
  [request-id response-json]
  #_(u/okp-println "Chrome complete-get-ok! is called with response-json" response-json)
  (js/chrome.webAuthenticationProxy.completeGetRequest
   (clj->js {:requestId request-id :responseJson response-json})))

(defn complete-get-error!
  "Rejects a pending navigator.credentials.get() call with a DOMException name.
   Both :name and :message are required by Chrome's WebAuthn proxy API."
  [request-id error-name]
  (js/chrome.webAuthenticationProxy.completeGetRequest
   (clj->js {:requestId request-id :error {:name error-name :message error-name}})))

;; ── event listeners ───────────────────────────────────────────────────────────

(defn on-create-request
  "Registers `handler` to be called whenever a site calls
   navigator.credentials.create({publicKey: ...}).
   `handler` receives a JS object with keys requestId and requestInfo."
  [handler]
  (js/chrome.webAuthenticationProxy.onCreateRequest.addListener handler))

(defn on-get-request
  "Registers `handler` to be called whenever a site calls
   navigator.credentials.get({publicKey: ...}).
   `handler` receives a JS object with keys requestId and requestInfo."
  [handler]
  (js/chrome.webAuthenticationProxy.onGetRequest.addListener handler))

(defn on-request-cancelled
  "Registers `handler` to be called when the site aborts a pending WebAuthn
   request. `handler` receives a JS object with key requestId."
  [handler]
  (when (.-onRequestCancelled js/chrome.webAuthenticationProxy)
    (js/chrome.webAuthenticationProxy.onRequestCancelled.addListener handler)))

;; ── serialization ─────────────────────────────────────────────────────────────

(defn- buf->base64url
  "Converts an ArrayBuffer or Uint8Array to a base64url string (no padding)."
  [buf]
  (let [arr    (js/Uint8Array. buf)
        n      (.-length arr)
        chars  (loop [i 0 acc #js []]
                 (if (>= i n)
                   acc
                   (do (.push acc (.fromCharCode js/String (aget arr i)))
                       (recur (inc i) acc))))
        binary (.join chars "")]
    (-> (js/btoa binary)
        (.split "+") (.join "-")
        (.split "/") (.join "_")
        (.split "=") (.join ""))))

(defn serialize-webauthn-options
  "Serializes a WebAuthn options JS object to a JSON string.
   ArrayBuffer / Uint8Array values (challenge, user.id, credential ids) are
   encoded as base64url strings so that the Rust side can parse them."
  [^js options]
  #_(u/okp-console-log "serialize-webauthn-options called with options" options)
  (.stringify js/JSON options
              (fn [_key value]
                (cond
                  (instance? js/ArrayBuffer value) (buf->base64url value)
                  (instance? js/Uint8Array  value) (buf->base64url value)
                  :else value))))
