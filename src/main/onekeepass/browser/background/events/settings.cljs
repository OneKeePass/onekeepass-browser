(ns onekeepass.browser.background.events.settings
  "Background events for the per-site disable feature.
   The disabled-sites list lives in the 'okp-settings' object of
   chrome.storage.local; all quick-toggle writes are done here (serialized
   through the background) and the app-db copy is refreshed by the
   chrome.storage.onChanged listener set in background core"
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.site-matching :as site-matching]
   [re-frame.core :refer [reg-event-db reg-event-fx reg-fx]]))

(def ^:private STORAGE_KEY "okp-settings")

;; Called from the storage onChanged listener whenever okp-settings changes
(reg-event-db
 :settings/disabled-sites-changed
 (fn [db [_event-id sites]]
   (assoc-in db [:settings :disabled-sites] sites)))

;; Called when TOGGLE_SITE_DISABLED is received from the main popup iframe
;; arg-m keys: host include-subdomains disable? tab-id
(reg-event-fx
 :settings/toggle-site-disabled
 (fn [{:keys [_db]} [_event-id arg-m]]
   {:fx [[:settings-update-disabled-sites-regfx arg-m]]}))

;; Read-modify-write of the whole okp-settings object so other settings
;; (e.g passkeys-enabled) are preserved. After the write the hosting tab is
;; reloaded so the disable/enable takes effect immediately - the content
;; script's gate is evaluated on the next load
(reg-fx
 :settings-update-disabled-sites-regfx
 (fn [{:keys [host include-subdomains disable? tab-id]}]
   (-> (js/chrome.storage.local.get STORAGE_KEY)
       (.then (fn [result]
                (let [raw (j/get result :okp-settings)
                      settings (if raw (js->clj raw :keywordize-keys true) {})
                      site-rules (get settings :disabled-sites [])
                      updated-rules (if disable?
                                      ;; Replace any existing rule for the same host
                                      (conj (vec (remove #(= (:host %) host) site-rules))
                                            {:host host :include-subdomains (boolean include-subdomains)})
                                      ;; Remove every rule that matches this host so
                                      ;; e.g a subdomain rule covering it goes too
                                      (vec (remove #(site-matching/host-matches-site-rule? host %) site-rules)))
                      updated-settings (assoc settings :disabled-sites updated-rules)]
                  (js/chrome.storage.local.set
                   (clj->js {STORAGE_KEY updated-settings})))))
       (.then (fn [_]
                (when tab-id
                  (js/chrome.tabs.reload tab-id))))
       (.catch (fn [e]
                 (js/console.error "Updating disabled-sites failed" e))))))
