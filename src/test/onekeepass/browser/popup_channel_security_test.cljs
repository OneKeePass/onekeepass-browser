(ns onekeepass.browser.popup-channel-security-test
  (:require
   [cljs.test :refer-macros [deftest is]]
   [onekeepass.browser.common.message-type-names :refer [INIT_PORT]]
   [onekeepass.browser.common.frame-security :as frame-security]
   [onekeepass.browser.content.iframe.events.content-iframe-connection :as host]
   [onekeepass.browser.content.iframe.events.iframe-content-connection :as connection]))

(deftest popup-rejects-unrelated-windows-and-incorrect-channel-tokens
  (let [old-window (.-window js/globalThis)
        listener (atom nil)
        parent #js {}
        starts (atom 0)
        port #js {:start #(swap! starts inc)}]
    (try
      (set! js/globalThis.window
            #js {:parent parent
                 :location #js {:hash "#okp-channel=private-token"}
                 :addEventListener (fn [_ f] (reset! listener f))})
      (connection/init-content-connection identity)
      (@listener #js {:source #js {}
                      :data #js {:type INIT_PORT :channelToken "private-token"}
                      :ports #js [port]})
      (@listener #js {:source parent
                      :data #js {:type INIT_PORT :channelToken "wrong-token"}
                      :ports #js [port]})
      (is (zero? @starts))
      (@listener #js {:source parent
                      :data #js {:type INIT_PORT :channelToken "private-token"}
                      :ports #js [port]})
      (is (= 1 @starts))
      (@listener #js {:source parent
                      :data #js {:type INIT_PORT :channelToken "private-token"}
                      :ports #js [port]})
      (is (= 1 @starts) "An established channel cannot be replaced")
      (finally (set! js/globalThis.window old-window)))))

(deftest channel-transfer-is-restricted-to-the-extension-origin
  (let [old-chrome (.-chrome js/globalThis)
        old-channel (.-MessageChannel js/globalThis)
        listener (atom nil)
        posted (atom nil)
        remote #js {}
        local #js {:start (fn [])}
        iframe #js {:src "chrome-extension://test-id/popup.html#okp-channel=private-token"
                    :contentWindow #js {:postMessage (fn [message origin ports]
                                                       (reset! posted [message origin ports]))}
                    :addEventListener (fn [event handler options]
                                        (reset! listener [event handler options]))}]
    (try
      (set! js/globalThis.chrome #js {:runtime #js {:getURL (fn [_] "chrome-extension://test-id/")}})
      (set! js/globalThis.MessageChannel (fn [] #js {:port1 local :port2 remote}))
      (host/setup-message-channel iframe identity)
      (is (= "load" (first @listener)))
      (is (true? (.-once (nth @listener 2))))
      ((second @listener))
      (is (= "chrome-extension://test-id" (second @posted)))
      (is (= "private-token" (aget (first @posted) "channelToken")))
      (is (identical? remote (aget (nth @posted 2) 0)))
      (finally
        (set! js/globalThis.chrome old-chrome)
        (set! js/globalThis.MessageChannel old-channel)))))

(deftest redacted-privileged-sources-still-require-the-popup-token
  (let [old-window (.-window js/globalThis)
        listener (atom nil)
        starts (atom 0)
        port #js {:start #(swap! starts inc)}]
    (try
      (set! js/globalThis.window
            #js {:parent #js {}
                 :location #js {:hash "#okp-channel=private-token"}
                 :addEventListener (fn [_ f] (reset! listener f))})
      (connection/init-content-connection identity)
      (@listener #js {:source nil :data #js {:type INIT_PORT :channelToken "wrong-token"}
                      :ports #js [port]})
      (is (zero? @starts))
      (@listener #js {:source nil :data #js {:type INIT_PORT :channelToken "private-token"}
                      :ports #js [port]})
      (is (= 1 @starts))
      (finally (set! js/globalThis.window old-window)))))

(deftest popup-nonces-work-without-secure-context-only-random-uuid
  (let [old-crypto (.-crypto js/globalThis)]
    (try
      (js/Object.defineProperty js/globalThis "crypto"
                                #js {:configurable true :value #js {:getRandomValues
                                                                    (fn [bytes] (.fill bytes 171))}})
      (is (= (apply str (repeat 32 "ab")) (frame-security/new-popup-channel-token)))
      (finally
        (js/Object.defineProperty js/globalThis "crypto" #js {:configurable true :value old-crypto})))))
