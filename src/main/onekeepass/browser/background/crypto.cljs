(ns onekeepass.browser.background.crypto
  (:require
   ["tweetnacl" :as nacl]
   ["tweetnacl-util" :as nacl.util]
   [applied-science.js-interop :as j]))

(def session-keys (atom {:client-keys nil :app-pub-key nil}))

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

#_(defn encrypt [message])

(defn decrypt
  "The arg encoded-message is base64 encoded string of encrypted bytes data
   The arg encoded-nonce is base64 encoded string of 24 bytes data
   "
  [encoded-message encoded-nonce]
  (let [nonce (j/call nacl.util :decodeBase64 encoded-nonce)
        sec-key (->  @session-keys :client-keys .-secretKey)
        app-pub-key (->  @session-keys :app-pub-key)
        message-bytes (j/call nacl.util :decodeBase64 encoded-message)

        ;; nonce(24-bytes),sec-key (32-bytes),app-pub-key (32-bytes) are bytes array
        plain-text-arr (j/call-in nacl [:box :open] message-bytes nonce app-pub-key sec-key)

        ;; plain-text-arr bytes need to converted to a string
        ;; another of converting bytes to string - (js/String.fromCharCode.apply nil plain-text-arr)

        plain-text (.decode (js/TextDecoder. "utf-8") plain-text-arr)]
    plain-text))

