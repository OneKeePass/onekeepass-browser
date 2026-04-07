(ns onekeepass.browser.content.iframe.settings-popup
  "Settings popup iframe — lets users toggle per-extension preferences.
   The iframe is an extension page so it can read/write chrome.storage.local directly.
   The background reacts to storage changes via chrome.storage.onChanged."
  (:require
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_SETTINGS]]
   [onekeepass.browser.common.mui-components
    :refer [mui-box mui-button mui-divider mui-stack mui-typography]]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [reagent.core :as r]
   [reagent.dom :as rd]))

;; ── storage key ───────────────────────────────────────────────────────────────

(def ^:private STORAGE_KEY "okp-settings")

;; ── local state ───────────────────────────────────────────────────────────────

(def ^:private passkeys-enabled?       (r/atom true))
(def ^:private saved-passkeys-enabled? (r/atom true))  ;; tracks the persisted value
(def ^:private settings-loaded?        (r/atom false))

;; ── storage helpers ───────────────────────────────────────────────────────────

(defn- load-settings! []
  (-> (js/chrome.storage.local.get STORAGE_KEY)
      (.then (fn [result]
               (let [raw      (aget result STORAGE_KEY)
                     enabled? (if raw
                                (aget raw "passkeys-enabled")
                                true)] ;; default: passkeys enabled
                 (let [v (if (nil? enabled?) true enabled?)]
                   (reset! passkeys-enabled? v)
                   (reset! saved-passkeys-enabled? v))
                 (reset! settings-loaded? true))))))

(defn- save-settings! [on-done]
  (-> (js/chrome.storage.local.set
       (clj->js {STORAGE_KEY {"passkeys-enabled" @passkeys-enabled?}}))
      (.then on-done)))

;; ── UI ────────────────────────────────────────────────────────────────────────

(defn- settings-content []
  (r/create-class
   {:component-did-mount (fn [_] (load-settings!))
    :reagent-render
    (fn []
      [mui-box {:sx {:p 2 :min-width 360}}

       ;; Section: Passkeys
       [mui-typography {:variant "subtitle2"
                        :sx {:mb 1 :color "text.secondary"
                             :text-transform "uppercase"
                             :letter-spacing "0.08em"
                             :font-size "0.7rem"}}
        "Passkeys"]

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

       ;; Buttons
       [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end"}
        [mui-button {:variant  "outlined"
                     :size     "small"
                     :color    "inherit"
                     :on-click (fn [] (iframe-content-messaging/close-settings-popup))}
         "Cancel"]
        [mui-button {:variant  "contained"
                     :size     "small"
                     :disabled (= @passkeys-enabled? @saved-passkeys-enabled?)
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
