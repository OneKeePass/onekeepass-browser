(ns onekeepass.browser.content.iframe.events.iframe-content-connection
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.message-type-names :refer [INIT_PORT]]
   [onekeepass.browser.common.utils :as u]))


(def ^:private direct-port-to-content (atom nil))

(defn init-content-connection 
  "Sets up a listener to receive a MessageChannel communication port from content side. 
  This port is used then for a bidirection communication.
  The arg 'message-handler' decodes the message and takes the appropiriate action
  "
  [message-handler]
  
  #_(u/okp-println "Iframe side init-content-connection is called and message listener is added")
  
  (let [expected-token (.get (js/URLSearchParams. (.substring js/window.location.hash 1))
                            "okp-channel")
        connected? (atom false)]
   (js/window.addEventListener
   "message"
   (fn [event]
     #_(u/okp-console-log "Iframe widow message listener recived message event" event)
     ;; https://developer.mozilla.org/en-US/docs/Web/API/Window/message_event
     (let [^js data (.-data event)]
       ;; Check if this is the Handshake
       (when (and (not @connected?)
                  (seq expected-token)
                  (some? data)
                  ;; Firefox may redact privileged sources. The unguessable
                  ;; per-popup token is required even when source is available.
                  (or (nil? (.-source event))
                      (identical? (.-source event) js/window.parent))
                  (= (.-type data) INIT_PORT)
                  (= (.-channelToken data) expected-token)
                  (= 1 (.-length (.-ports event))))

         ;; Grab the transferred port
         (let [port (aget (.-ports event) 0)]
           (reset! connected? true)
           (reset! direct-port-to-content port)

           ;; Setup Listeners (Look how similar this is to chrome.runtime!)
           (j/assoc! port :onmessage message-handler)
           #_(set! (.-onmessage port)
                   (fn [msg]
                     (let [payload (.-data msg)]
                       (js/console.log "Iframe received:" payload))))

           ;; Start the port (Crucial!)
           (.start port)

           #_(u/okp-println "Iframe side port is ready")
           ;; Say Hello
           #_(.postMessage port #js {:type "HELLO" :payload "I am ready"}))))))))

(defn send-message-to-content [message]
  #_(u/okp-println "send-message-to-content is called")
  (.postMessage @direct-port-to-content (clj->js message)))
