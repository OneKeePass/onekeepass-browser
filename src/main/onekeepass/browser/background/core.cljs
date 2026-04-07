(ns onekeepass.browser.background.core
  (:require
   [applied-science.js-interop :as j]
   [re-frame.core :refer [dispatch]]
   [onekeepass.browser.background.events.messaging :as messaging-event]
   [onekeepass.browser.background.events.content]
   [onekeepass.browser.background.events.passkey]
   [onekeepass.browser.background.passkey :as passkey]
   [onekeepass.browser.background.tab-interactions :as tab-interactions]
   [onekeepass.browser.common.utils :refer [is-firefox-browser?]]
   [onekeepass.browser.common.utils :as u]))

(defn- setup-passkey-proxy
  "Attaches the Chrome WebAuthn proxy and registers all three event listeners.
   Skipped entirely on Firefox or if the API is unavailable."
  []
  (when (and (not (is-firefox-browser?)) (passkey/available?))
    (passkey/attach!)

    ;; ── Registration (create) ────────────────────────────────────────────────
    (passkey/on-create-request
     (fn [^js request-details]
       #_(u/okp-console-log "In passkey/on-create-request callback request-details" request-details)
       (let [request-id   (j/get request-details :requestId)
             options-json (j/get request-details :requestDetailsJson)
             rp-name      (let [opts (js->clj (.parse js/JSON options-json) :keywordize-keys true)]
                            (or (get-in opts [:rp :name])
                                (get-in opts [:rp :id])
                                "Passkey"))]
         
         #_(u/okp-console-log "options-json is" options-json)
         ;; Obtain the page origin from the active tab URL
         (js/chrome.tabs.query
          #js {:active true :currentWindow true}
          (fn [tabs]
            #_(u/okp-console-log "Active tabs returned " tabs )
            (let [tab     (aget tabs 0)
                  tab-id  (when tab (j/get tab :id))
                  tab-url (when tab (j/get tab :url))
                  ;; _ (u/okp-console-log "create-request Url is " tab-url)
                  origin  (when tab (.-origin (js/URL. tab-url)))]
              (dispatch [:passkey/create-request
                         {:chrome-request-id request-id
                          :options-json      options-json
                          :rp-name           rp-name
                          :origin            (or origin "")
                          :tab-url           (or tab-url "")
                          :tab-id            tab-id}])))))))

    ;; ── Authentication (get) ──────────────────────────────────────────────────
    (passkey/on-get-request
     (fn [^js request-details]
       (let [request-id   (j/get request-details :requestId)
             options-json (j/get request-details :requestDetailsJson)]
         #_(u/okp-println "In passkey/on-get-request callback fn")
         (js/chrome.tabs.query
          #js {:active true :currentWindow true}
          (fn [tabs]
            (let [tab     (aget tabs 0)
                  tab-id  (when tab (j/get tab :id))
                  tab-url (when tab (j/get tab :url))
                  ;; _ (u/okp-console-log "Url is " tab-url)
                  origin  (when tab (.-origin (js/URL. tab-url)))]
              (dispatch [:passkey/get-request
                         {:chrome-request-id request-id
                          :options-json      options-json
                          :origin            (or origin "")
                          :tab-url           (or tab-url "")
                          :tab-id            tab-id}])))))))

    ;; ── Cancellation ──────────────────────────────────────────────────────────
    (passkey/on-request-cancelled
     (fn [^js details]
       (dispatch [:passkey/request-cancelled
                  {:request-id (j/get details :requestId)}])))))

;; Entry point — called once when the background service worker is loaded.
(defn init []
  (js/console.log "Background script init is called")
  (messaging-event/start-app-session)
  (messaging-event/register-extension-message-handler)
  (tab-interactions/register-tab-listeners)

  ;; Read persisted settings before attaching the passkey proxy so the user's
  ;; choice is honoured immediately after a service-worker restart.
  (-> (js/chrome.storage.local.get "okp-settings")
      (.then (fn [result]
               (let [raw      (j/get result :okp-settings)
                     settings (if raw (js->clj raw :keywordize-keys true) {})
                     enabled? (get settings :passkeys-enabled true)]
                 (dispatch [:settings/loaded {:passkeys-enabled enabled?}])
                 (when enabled?
                   (setup-passkey-proxy))))))

  ;; React to future setting changes written by the settings popup iframe.
  (.addListener js/chrome.storage.onChanged
                (fn [changes area]
                  (when (= area "local")
                    (when-let [change (j/get changes :okp-settings)]
                      (let [new-val  (j/get change :newValue)
                            settings (if new-val (js->clj new-val :keywordize-keys true) {})
                            enabled? (get settings :passkeys-enabled true)]
                        (dispatch [:settings/passkeys-enabled-changed enabled?])))))))
