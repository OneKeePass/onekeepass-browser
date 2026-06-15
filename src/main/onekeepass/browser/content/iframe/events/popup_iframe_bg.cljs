(ns onekeepass.browser.content.iframe.events.popup-iframe-bg
  "Direct iframe -> background calls.

   A popup iframe is itself an extension page, so it can call
   chrome.runtime.sendMessage directly to the background without relaying
   through the content script. This ns collects such popup -> background
   calls. For now it has a single fn; more can be added here later."
  (:require
   [onekeepass.browser.common.message-type-names :refer [TOGGLE_SITE_DISABLED]]
   #_[re-frame.core :refer [reg-event-fx reg-sub subscribe]]))

(defn send-bg-toggle-site-disabled
  "Called from the main popup's quick 'Disable/Enable on this site' action.
   The background updates the disabled-sites list in okp-settings and then
   reloads this tab so the change takes effect immediately"
  [{:keys [host include-subdomains disable?]}]
  (js/chrome.runtime.sendMessage (clj->js {:message-type TOGGLE_SITE_DISABLED
                                           :host host
                                           :include-subdomains (boolean include-subdomains)
                                           :disable? (boolean disable?)})))

;; ────────────────────────────────────────────────────────────────────────────
;; Earlier popup-action re-frame events/subs that lived here are no longer used
;; (these names are now defined in
;; onekeepass.browser.content.iframe.events.iframe-content-messaging).
;; Kept commented as an example of the iframe re-frame wiring.
;; ────────────────────────────────────────────────────────────────────────────

(comment
  (defn popup-action-info []
    (subscribe [:iframe-popup-action-info]))

  ;; Whenever we receive the popup action message, we store the connection-state
  ;; and the popup ui is shown accordingly
  (reg-event-fx
   :iframe-popup-action/message-received
   (fn [{:keys [db]} [_event_id {:keys [connection-state association-id association-rejected]}]]
     {:db (-> db
              (assoc-in [:iframe-popup :connection-state] connection-state)
              (assoc-in [:iframe-popup :association-id] association-id)
              (assoc-in [:iframe-popup :association-rejected] association-rejected))}))

  (reg-sub
   :iframe-popup-action-info
   (fn [db]
     {:connection-state (get-in db [:iframe-popup :connection-state])
      :association-id (get-in db [:iframe-popup :association-id])
      :association-rejected (get-in db [:iframe-popup :association-rejected])})))
