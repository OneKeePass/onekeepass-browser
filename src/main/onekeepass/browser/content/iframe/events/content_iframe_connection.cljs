(ns onekeepass.browser.content.iframe.events.content-iframe-connection
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.frame-security :as frame-security]
   [onekeepass.browser.common.message-type-names :refer [INIT_PORT]]
   [onekeepass.browser.common.utils :as u]))

(def ^:private content-side-port (atom nil))

(defn setup-message-channel
  "Called to set up the communication channel between the content script and the iframe that is launched"
  [iframe-element message-handlers]
  ;; Create the Channel
  (let [channel (js/MessageChannel.)
        local-port (.-port1 channel)
        remote-port (.-port2 channel)
        token (frame-security/popup-channel-token (.-src iframe-element))
        target-origin (.replace (js/chrome.runtime.getURL "/") #"/$" "")]

    ;; Keep our end (local-port)
    ;; Setup listeners just like chrome.runtime.connect
    (j/assoc! local-port :onmessage message-handlers)

    #_(set! (.-onmessage local-port)
            (fn [msg]
              (u/okp-console-log "Host received:" (.. msg -data -payload))
              ;; Example: Reply back
              (.postMessage local-port #js {:type "PONG_FROM_HOST"})))

    ;; Send the other end (remote-port) to the Iframe
    ;; CRITICAL: We must wait for iframe to load, or the message is lost.
    (.addEventListener iframe-element "load"
                       (fn []
                         ;; We send the port as a "Transferable" (3rd argument)
                         (.postMessage (.-contentWindow iframe-element)
                                       #js {:type INIT_PORT :channelToken token}
                                       target-origin
                                       #js [remote-port]))
                       #js {:once true})

    ;; Start the port (Required for MessageChannel)
    (.start local-port)

    (reset! content-side-port local-port)))

(defn send-message-to-iframe
  "Sends the message from content script side to iframe"
  [message]
  #_(u/okp-println "5 Iframp popup: send-message-to-iframe is called")
  #_(u/okp-println "send-message-to-iframe is called and postMessage is done through content side port" "Message:" message)
  (.postMessage @content-side-port (clj->js message)))
