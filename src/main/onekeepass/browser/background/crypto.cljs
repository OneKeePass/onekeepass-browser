(ns onekeepass.browser.background.crypto
  (:require
   ["tweetnacl" :as nacl]
   ["tweetnacl-util" :as nacl.util]
   [applied-science.js-interop :as j]))

;; :recv-seq tracks the last accepted sequence number from the native app.
;; Used to reject replayed encrypted messages (M-1 replay-attack protection).
(def session-keys (atom {:client-keys nil :app-pub-key nil :recv-seq 0}))

(defn- create-keypair []
  (j/call-in nacl [:box :keyPair]))

(defn- get-encoded-public-key
  "Extracts the public key part from the js object keyPair, encodes the binary and returns as string "
  [session-key-pair]
  (->> (j/get session-key-pair :publicKey)
       (j/call nacl.util :encodeBase64)))

(defn create-session-key-pair []
  ;; client-keys is js object with props publicKey and secretKey
  (let [client-keys (create-keypair)]
    #_(js/console.log "kp is " client-keys)
    (swap! session-keys assoc :client-keys client-keys)))

(defn encoded-client-public-key []
  (let [client-key-pair (:client-keys @session-keys)
        cpk (get-encoded-public-key client-key-pair)]
    cpk))

(defn store-session-app-public-key
  "Called to store the app side public key.
   The arg 'encoded-app-session-pub-key' is base64 encoded
   IMPORTANT: The app side pub key should be available before any decryption and encryption
   "
  [encoded-app-session-pub-key]
  (swap! session-keys assoc
         :app-pub-key (j/call nacl.util :decodeBase64 encoded-app-session-pub-key)))

(defn reset-recv-seq!
  "Resets the receive sequence counter to 0. Call this on disconnect so that
   messages captured during a previous session cannot be replayed into the next."
  []
  (swap! session-keys assoc :recv-seq 0))

#_(defn encrypt [message])

(defn decrypt
  "Decrypts an app→extension message. Returns the inner message string on success,
   or nil if decryption fails or a replay is detected (seq not strictly increasing).

   The app wraps every plaintext in a replay-protection envelope before encrypting:
     {\"seq\": <monotonic-counter>, \"msg\": \"<original-json-string>\"}
   This function validates the envelope and returns only the inner :msg on success."
  [encoded-message encoded-nonce]
  (let [nonce         (j/call nacl.util :decodeBase64 encoded-nonce)
        sec-key       (-> @session-keys :client-keys .-secretKey)
        app-pub-key   (-> @session-keys :app-pub-key)
        message-bytes (j/call nacl.util :decodeBase64 encoded-message)

        ;; nonce(24-bytes),sec-key (32-bytes),app-pub-key (32-bytes) are bytes array
        ;; Returns nil if decryption/authentication fails
        plain-text-arr (j/call-in nacl [:box :open] message-bytes nonce app-pub-key sec-key)]
    (when plain-text-arr
      (let [plain-text (.decode (js/TextDecoder. "utf-8") plain-text-arr)
            envelope   (js->clj (js/JSON.parse plain-text) :keywordize-keys true)
            recv-seq   (:seq envelope)
            last-seq   (:recv-seq @session-keys)]
        (if (and (integer? recv-seq) (> recv-seq last-seq))
          (do
            (swap! session-keys assoc :recv-seq recv-seq)
            (:msg envelope))
          (do
            (js/console.warn "Replay or out-of-order message rejected: seq"
                             recv-seq "<= last" last-seq)
            nil))))))
