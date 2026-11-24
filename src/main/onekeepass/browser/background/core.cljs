(ns onekeepass.browser.background.core
  (:require
   [applied-science.js-interop :as j]
   [re-frame.core :refer [dispatch]]
   [onekeepass.browser.background.events.messaging :as messaging-event]
   [onekeepass.browser.background.events.content]
   [onekeepass.browser.background.events.passkey]
   [onekeepass.browser.background.events.settings]
   [onekeepass.browser.background.tab-interactions :as tab-interactions]))

;; Entry point — called once when the background service worker is loaded.
(defn init []
  (js/console.log "Background script init is called")
  (messaging-event/start-app-session)
  (messaging-event/register-extension-message-handler)
  (tab-interactions/register-tab-listeners)

  ;; Load persisted settings when the background script starts.
  (-> (js/chrome.storage.local.get "okp-settings")
      (.then (fn [result]
               (let [raw      (j/get result :okp-settings)
                     settings (if raw (js->clj raw :keywordize-keys true) {})
                     enabled? (get settings :passkeys-enabled true)]
                 (dispatch [:settings/loaded {:passkeys-enabled enabled?
                                              :disabled-sites (get settings :disabled-sites [])}])))))

  ;; React to future setting changes written by the settings popup iframe
  ;; or by the quick site disable/enable toggle of the main popup.
  (.addListener js/chrome.storage.onChanged
                (fn [changes area]
                  (when (= area "local")
                    (when-let [change (j/get changes :okp-settings)]
                      (let [new-val  (j/get change :newValue)
                            settings (if new-val (js->clj new-val :keywordize-keys true) {})
                            enabled? (get settings :passkeys-enabled true)]
                        (dispatch [:settings/passkeys-enabled-changed enabled?])
                        (dispatch [:settings/disabled-sites-changed (get settings :disabled-sites [])])))))))
