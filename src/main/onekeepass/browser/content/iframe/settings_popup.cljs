(ns onekeepass.browser.content.iframe.settings-popup
  "Settings popup iframe — lets users toggle per-extension preferences.
   The iframe is an extension page so it can read/write chrome.storage.local directly.
   The background reacts to storage changes via chrome.storage.onChanged."
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_SETTINGS]]
   [onekeepass.browser.common.mui-components
    :refer [mui-box mui-button mui-divider mui-stack mui-typography]]
   [onekeepass.browser.common.site-matching :as site-matching]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [reagent.core :as r]
   [reagent.dom :as rd]))

;; ── storage key ───────────────────────────────────────────────────────────────

(def ^:private STORAGE_KEY "okp-settings")

;; ── local state ───────────────────────────────────────────────────────────────

;; TODO: Need to use re-frame storage with subscription and dispatch events instead of local atoms that are used here

(def ^:private passkeys-enabled?       (r/atom true))
(def ^:private saved-passkeys-enabled? (r/atom true))  ;; tracks the persisted value
(def ^:private settings-loaded?        (r/atom false))

;; Vector of {:host "example.com" :include-subdomains true} maps
(def ^:private disabled-sites          (r/atom []))
(def ^:private saved-disabled-sites    (r/atom []))

;; 'Add a site' input state
(def ^:private new-host                (r/atom ""))
(def ^:private new-include-subdomains? (r/atom false))
(def ^:private new-host-error          (r/atom nil))

;; ── storage helpers ───────────────────────────────────────────────────────────

;; The simple (js->clj raw ...) does not convert the object returned by the
;; storage API in this embedded extension iframe (it comes from another js
;; context and is returned as-is, so 'get' on it silently yields the defaults
;; and 'assoc' throws). The JSON stringify/parse roundtrip rebuilds the object
;; in this frame's context first - same workaround as convert-message in
;; content-iframe-messaging
(defn- raw->settings [raw]
  (if raw
    (js->clj (-> raw js/JSON.stringify js/JSON.parse) :keywordize-keys true)
    {}))

(defn- load-settings! []
  (-> (js/chrome.storage.local.get STORAGE_KEY)
      (.then (fn [result]
               (let [settings (raw->settings (j/get result :okp-settings))
                     enabled? (get settings :passkeys-enabled true)
                     sites    (vec (get settings :disabled-sites []))]
                 (reset! passkeys-enabled? enabled?)
                 (reset! saved-passkeys-enabled? enabled?)
                 (reset! disabled-sites sites)
                 (reset! saved-disabled-sites sites)
                 (reset! settings-loaded? true))))))

;; Read-modify-write of the whole okp-settings object so any key written by
;; others (e.g the quick site toggle in the main popup) is not clobbered
(defn- save-settings! [on-done]
  (-> (js/chrome.storage.local.get STORAGE_KEY)
      (.then (fn [result]
               (let [settings (raw->settings (j/get result :okp-settings))
                     updated  (assoc settings
                                     :passkeys-enabled @passkeys-enabled?
                                     :disabled-sites @disabled-sites)]
                 (js/chrome.storage.local.set
                  (clj->js {STORAGE_KEY updated})))))
      (.then on-done)
      (.catch (fn [e]
                (js/console.error "Saving okp-settings failed" e)))))

;; ── disabled sites list actions ───────────────────────────────────────────────

(defn- add-disabled-site! []
  (let [host (site-matching/normalize-host-input @new-host)]
    (cond
      (nil? host)
      (reset! new-host-error "Enter a valid host like example.com")

      (some #(= (:host %) host) @disabled-sites)
      (reset! new-host-error (str host " is already in the list"))

      :else
      (do
        (swap! disabled-sites conj {:host host
                                    :include-subdomains @new-include-subdomains?})
        (reset! new-host "")
        (reset! new-include-subdomains? false)
        (reset! new-host-error nil)))))

(defn- remove-disabled-site! [host]
  (swap! disabled-sites (fn [sites] (vec (remove #(= (:host %) host) sites)))))

(defn- settings-changed? []
  (or (not= @passkeys-enabled? @saved-passkeys-enabled?)
      (not= @disabled-sites @saved-disabled-sites)))

;; ── UI ────────────────────────────────────────────────────────────────────────

(defn- section-header [text]
  [mui-typography {:variant "subtitle2"
                   :sx {:mb 1 :color "text.secondary"
                        :text-transform "uppercase"
                        :letter-spacing "0.08em"
                        :font-size "0.7rem"}}
   text])

(defn- disabled-site-row [{:keys [host include-subdomains]}]
  [mui-box {:sx {:display "flex" :align-items "center"
                 :justify-content "space-between" :mb 0.5}}
   [mui-typography {:variant "body2" :sx {:font-size "13px"}}
    (str host (when include-subdomains " (+ subdomains)"))]
   [mui-button {:size "small"
                :color "inherit"
                :sx {:min-width "28px" :font-size "14px" :line-height 1}
                :on-click (fn [] (remove-disabled-site! host))}
    "✕"]])

(defn- disabled-sites-section []
  [:<>
   [section-header "Disabled Sites"]

   [mui-typography {:variant "caption"
                    :sx {:color "text.disabled" :display "block" :mb 1}}
    "OneKeePass will not detect login fields on these sites. Changes apply when the page is next loaded"]

   ;; Add a new site
   [mui-box {:sx {:display "flex" :align-items "center" :gap 1 :mb 0.5}}
    [:input {:type "text"
             :placeholder "example.com"
             :value @new-host
             :disabled (not @settings-loaded?)
             :on-change (fn [e]
                          (reset! new-host (-> e .-target .-value))
                          (reset! new-host-error nil))
             :on-key-down (fn [e]
                            (when (= "Enter" (.-key e))
                              (add-disabled-site!)))
             :style {:flex 1 :padding "4px 6px" :font-size "13px"
                     :font-family "Roboto, sans-serif"}}]
    [mui-button {:size "small"
                 :variant "outlined"
                 :disabled (not @settings-loaded?)
                 :on-click (fn [] (add-disabled-site!))}
     "Add"]]

   [mui-box {:sx {:display "flex" :align-items "center" :gap 1 :mb 1}}
    [:input {:type "checkbox"
             :id "new-include-subdomains-cb"
             :checked @new-include-subdomains?
             :on-change #(swap! new-include-subdomains? not)
             :style {:width "14px" :height "14px" :cursor "pointer"
                     :accent-color "#1976d2"}}]
    [:label {:html-for "new-include-subdomains-cb"
             :style {:cursor "pointer" :font-size "13px"
                     :font-family "Roboto, sans-serif"
                     :user-select "none"}}
     "Include subdomains"]]

   (when-let [error @new-host-error]
     [mui-typography {:variant "caption" :sx {:color "error.main" :display "block" :mb 1}}
      error])

   ;; Current list
   (if (seq @disabled-sites)
     [mui-box {:sx {:mb 1 :max-height 160 :overflow "auto"}}
      (for [{:keys [host] :as site} @disabled-sites]
        ^{:key host} [disabled-site-row site])]
     [mui-typography {:variant "caption"
                      :sx {:color "text.disabled" :display "block" :mb 1}}
      "No disabled sites"])])

(defn- settings-content []
  (r/create-class
   {:component-did-mount (fn [_] (load-settings!))
    :reagent-render
    (fn []
      [mui-box {:sx {:p 2 :min-width 360}}

       ;; Section: Passkeys
       [section-header "Passkeys"]

       [mui-box {:sx {:display "flex"
                      :align-items "center"
                      :gap 1.5
                      :mb 0.5}}
        [:input {:type      "checkbox"
                 :id        "passkeys-enabled-cb"
                 :checked   @passkeys-enabled?
                 :disabled  (not @settings-loaded?)
                 :on-change #(swap! passkeys-enabled? not)
                 :style     {:width "16px" :height "16px" :cursor "pointer"
                             :accent-color "#1976d2"}}]
        [:label {:html-for "passkeys-enabled-cb"
                 :style    {:cursor "pointer" :font-size "14px"
                            :font-family "Roboto, sans-serif"
                            :user-select "none"}}
         "Enable passkey support"]]

       [mui-typography {:variant "caption"
                        :sx {:color "text.disabled" :display "block" :mb 2 :ml 3.5}}
        "Intercept and manage WebAuthn/passkey operations"]

       [mui-divider {:sx {:mb 2}}]

       ;; Section: Disabled sites
       [disabled-sites-section]

       [mui-divider {:sx {:mb 2}}]

       ;; Buttons
       [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end"}
        [mui-button {:variant  "outlined"
                     :size     "small"
                     :color    "inherit"
                     :on-click (fn [] (iframe-content-messaging/close-settings-popup))}
         "Cancel"]
        [mui-button {:variant  "contained"
                     :size     "small"
                     :disabled (not (settings-changed?))
                     :on-click (fn []
                                 (save-settings!
                                  (fn [] (iframe-content-messaging/close-settings-popup))))}
         "Save"]]])}))

(defn- main-content []
  [settings-content])

;; ── auto-resize ───────────────────────────────────────────────────────────────

(defn- send-size-to-host []
  (let [body   (.-body js/document)
        width  (.-scrollWidth body)
        height (.-scrollHeight body)]
    (js/window.parent.postMessage
     #js {:type   RESIZE_IFRAME_SETTINGS
          :width  width
          :height height}
     "*")))

(defn- init-auto-resize []
  (let [observer (js/ResizeObserver. (fn [_] (send-size-to-host)))]
    (.observe observer (.-body js/document))
    (send-size-to-host)))

;; ── entry point ───────────────────────────────────────────────────────────────

(defn init []
  (iframe-content-messaging/init-content-connection)
  (init-auto-resize)
  (rd/render [main-content] (.getElementById ^js/Window js/document "settings_popup")))
