(ns onekeepass.browser.content.passkey-intercept
  "Firefox-only MAIN world script — overrides navigator.credentials to intercept
   WebAuthn calls and relay them through the isolated-world content script to the
   background service worker.

   Communication flow
   ──────────────────
   create/get call
     │  (MAIN world)
     ▼
   window.postMessage {type: OKP_PASSKEY_CREATE|GET, requestId, optionsJson, origin}
     │  (isolated-world content script picks it up)
     ▼
   chrome.runtime.sendMessage → background
     │
     ▼  (background processes, gets credential from desktop app)
   chrome.tabs.sendMessage → isolated-world content script
     │
     ▼
   window.postMessage {type: OKP_PASSKEY_RESOLVE_CREATE|GET|REJECT, requestId, ...}
     │  (MAIN world picks it up)
     ▼
   original Promise resolved / rejected with synthetic credential object")

;; ── base64url helpers ─────────────────────────────────────────────────────────

(defn- buf->base64url [buf]
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

(defn- base64url->buf
  "Decodes a base64url string back to a JS ArrayBuffer."
  [b64url]
  (when b64url
    (let [b64     (-> b64url
                      (.split "-") (.join "+")
                      (.split "_") (.join "/"))
          padded  (case (mod (.-length b64) 4)
                    2 (str b64 "==")
                    3 (str b64 "=")
                    b64)
          binary  (js/atob padded)
          n       (.-length binary)
          buf     (js/Uint8Array. n)]
      (loop [i 0]
        (when (< i n)
          (aset buf i (.charCodeAt binary i))
          (recur (inc i))))
      (.-buffer buf))))

;; ── options serializer ────────────────────────────────────────────────────────

(defn- serialize-options
  "Serializes a publicKey options object to a JSON string, converting ArrayBuffer
   and Uint8Array values to base64url so they survive structured-clone / JSON."
  [pub-key]
  (.stringify js/JSON pub-key
              (fn [_key value]
                (cond
                  (instance? js/ArrayBuffer value) (buf->base64url value)
                  (instance? js/Uint8Array  value) (buf->base64url value)
                  :else value))))

;; ── options deserializer ─────────────────────────────────────────────────────

(defn- deserialize-pub-key-options
  "Converts base64url strings back to ArrayBuffers for the known binary fields
   in a parsed publicKey options JS object (mutates in place, returns the object)."
  [^js opts]
  (when opts
    (when-let [ch (.-challenge opts)]
      (set! (.-challenge opts) (base64url->buf ch)))
    (when-let [u (.-user opts)]
      (when-let [uid (.-id u)]
        (set! (.-id u) (base64url->buf uid))))
    (when-let [excl (.-excludeCredentials opts)]
      (dotimes [i (.-length excl)]
        (let [cred (aget excl i)]
          (when-let [cid (.-id cred)]
            (set! (.-id cred) (base64url->buf cid))))))
    opts))

;; ── credential constructors ───────────────────────────────────────────────────

(defn- make-registration-credential
  "Constructs a synthetic PublicKeyCredential-like JS object for a registration
   ceremony from the JSON map returned by the desktop app."
  [cred]
  (let [resp    (get cred "response")
        raw-id  (base64url->buf (get cred "rawId"))
        transports (or (get resp "transports") #js ["internal"])]
    (js-obj
     "id"                      (get cred "id")
     "rawId"                   raw-id
     "type"                    (get cred "type")
     "authenticatorAttachment" (get cred "authenticatorAttachment")
     "response"
     (js-obj
      "clientDataJSON"        (base64url->buf (get resp "clientDataJSON"))
      "attestationObject"     (base64url->buf (get resp "attestationObject"))
      "authenticatorData"     (base64url->buf (get resp "authenticatorData"))
      "getTransports"         (fn [] transports)
      "getPublicKey"          (fn [] (base64url->buf (get resp "publicKey")))
      "getPublicKeyAlgorithm" (fn [] (get resp "publicKeyAlgorithm" -7)))
     "getClientExtensionResults" (fn [] #js {}))))

(defn- make-authentication-credential
  "Constructs a synthetic PublicKeyCredential-like JS object for an authentication
   ceremony from the JSON map returned by the desktop app."
  [cred]
  (let [resp        (get cred "response")
        user-handle (base64url->buf (get resp "userHandle"))]
    (js-obj
     "id"                      (get cred "id")
     "rawId"                   (base64url->buf (get cred "rawId"))
     "type"                    (get cred "type")
     "authenticatorAttachment" (get cred "authenticatorAttachment")
     "response"
     (js-obj
      "clientDataJSON"    (base64url->buf (get resp "clientDataJSON"))
      "authenticatorData" (base64url->buf (get resp "authenticatorData"))
      "signature"         (base64url->buf (get resp "signature"))
      "userHandle"        user-handle)
     "getClientExtensionResults" (fn [] #js {}))))

;; ── pending-request store ─────────────────────────────────────────────────────

;; Maps requestId → {:resolve fn :reject fn :options-json str}
(def ^:private pending (atom {}))

;; Holds the original navigator.credentials.create bound function so that
;; handle-window-response (a top-level defn- outside install's let) can call it
;; when delegating to the browser's native passkey handler.
(def ^:private orig-create-fn (atom nil))

;; When true, all WebAuthn calls are passed directly to the original browser
;; implementation — set permanently when user chooses "Use Browser Default".
(def ^:private browser-default? (atom false))

;; ── response handler (MAIN world listens for isolated-world relay) ────────────

(defn- handle-window-response [^js event]
  (when (= (.-source event) js/window)
    (let [^js data  (.-data event)
          type      (when data (.-type data))
          req-id    (when data (.-requestId data))]
      (when-let [{:keys [resolve reject]} (get @pending req-id)]
        (cond
          (= type "OKP_PASSKEY_RESOLVE_CREATE")
          (do
            (swap! pending dissoc req-id)
            (let [cred-map (js->clj (.parse js/JSON (.-credentialJson data)))]
              (resolve (make-registration-credential cred-map))))

          (= type "OKP_PASSKEY_RESOLVE_GET")
          (do
            (swap! pending dissoc req-id)
            (let [cred-map (js->clj (.parse js/JSON (.-credentialJson data)))]
              (resolve (make-authentication-credential cred-map))))

          (= type "OKP_PASSKEY_REJECT")
          (do
            (swap! pending dissoc req-id)
            (reject (js/DOMException.
                     (or (.-message data) "Operation not allowed")
                     (or (.-name data) "NotAllowedError"))))

          (= type "OKP_PASSKEY_BROWSER_DEFAULT")
          (when-let [{:keys [resolve reject options-json]} (get @pending req-id)]
            (swap! pending dissoc req-id)
            ;; Persist: all future WebAuthn calls on this page bypass OKP
            (reset! browser-default? true)
            (when (and @orig-create-fn options-json)
              (let [pub-key (-> (js/JSON.parse options-json)
                                (deserialize-pub-key-options))]
                (-> (@orig-create-fn #js {:publicKey pub-key})
                    (.then resolve)
                    (.catch reject))))))))))

;; ── navigator.credentials override ───────────────────────────────────────────

(defn- install
  "Overrides navigator.credentials with a proxy that intercepts WebAuthn calls
   and relays them to the isolated-world content script via window.postMessage.
   Non-WebAuthn calls (no publicKey) are forwarded to the original implementation."
  []
  (let [creds      ^js (.-credentials js/navigator)
        orig-create (when creds (.bind (.-create creds) creds))
        orig-get    (when creds (.bind (.-get creds) creds))]

    (reset! orig-create-fn orig-create)
    (.addEventListener js/window "message" handle-window-response)

    (js/Object.defineProperty
     js/navigator "credentials"
     #js {:value
          (js-obj
           "create"
           (fn [^js options]
             (if @browser-default?
               (when orig-create (orig-create options))
               (if-let [pub-key (and options (.-publicKey options))]
                 (js/Promise.
                  (fn [resolve reject]
                    (let [req-id    (str (js/Math.random) "-" (js/Date.now))
                          opts-json (serialize-options pub-key)]
                      (swap! pending assoc req-id {:resolve resolve :reject reject :options-json opts-json})
                      (js/window.postMessage
                       #js {:type        "OKP_PASSKEY_CREATE"
                            :requestId   req-id
                            :optionsJson opts-json
                            :origin      (.-origin js/location)}
                       (.-origin js/location)))))
                 (when orig-create (orig-create options)))))

           "get"
           (fn [^js options]
             (if @browser-default?
               (when orig-get (orig-get options))
               (if-let [pub-key (and options (.-publicKey options))]
                 (js/Promise.
                  (fn [resolve reject]
                    (let [req-id    (str (js/Math.random) "-" (js/Date.now))
                          opts-json (serialize-options pub-key)]
                      (swap! pending assoc req-id {:resolve resolve :reject reject})
                      (js/window.postMessage
                       #js {:type        "OKP_PASSKEY_GET"
                            :requestId   req-id
                            :optionsJson opts-json
                            :origin      (.-origin js/location)}
                       (.-origin js/location)))))
                 (when orig-get (orig-get options))))))
          :configurable true})))

(defn init []
  (install))
