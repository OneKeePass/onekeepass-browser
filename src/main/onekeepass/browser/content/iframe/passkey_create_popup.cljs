(ns onekeepass.browser.content.iframe.passkey-create-popup
  "Iframe popup for passkey creation — 5-step state machine.

   Step 1 (:db-pick)    — user picks a database
   Step 2 (:group-pick) — user picks an existing group or types a new group name
   Step 3 (:entry-pick) — user picks an existing entry or types a new entry name
   Step 4 (:saving)     — spinner while backend saves
   Step 5 (:success)    — 'Passkey saved!' then auto-close after 1.5 s"
  (:require
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_PASSKEY_CREATE]]
   [onekeepass.browser.common.mui-components
    :refer [mui-box mui-button mui-divider mui-list mui-list-item-button
            mui-list-item-text mui-stack mui-typography]]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [reagent.core :as r]
   [reagent.dom :as rd]))

;; ── local state ───────────────────────────────────────────────────────────────

(def ^:private current-step        (r/atom :db-pick))
(def ^:private prev-step           (r/atom :db-pick))  ;; step to return to when "Go Back" clicked
(def ^:private selected-db-key     (r/atom nil))
(def ^:private selected-group-uuid (r/atom nil))
(def ^:private new-group?          (r/atom false))
(def ^:private new-group-name      (r/atom ""))
(def ^:private selected-entry-uuid (r/atom nil))
(def ^:private new-entry?          (r/atom false))
(def ^:private new-entry-name      (r/atom ""))

;; ── shared helpers ────────────────────────────────────────────────────────────

(def ^:private item-sx
  {:border-radius 1 :mb 0.5
   "&.Mui-selected" {:bgcolor "rgba(25,118,210,0.12)"}})

(defn- cancel-button []
  [mui-button {:variant "outlined" :size "small" :color "inherit"
               :on-click (fn []
                           (reset! prev-step @current-step)
                           (reset! current-step :confirm-cancel))}
   "Cancel"])

(defn- text-input [placeholder value-atom]
  [:input {:type        "text"
           :placeholder placeholder
           :value       @value-atom
           :on-change   #(reset! value-atom (-> % .-target .-value))
           :auto-focus  true
           :style       {:width "100%" :box-sizing "border-box"
                         :padding "6px 8px" :font-size "14px"
                         :border "1px solid #ccc" :border-radius "4px"
                         :margin-top "4px"}}])

;; ── Step 1: DB picker ─────────────────────────────────────────────────────────

(defn- step-db-pick [{:keys [databases rp-name]}]
  (let [dbs (or databases [])]
    (when (and (= 1 (count dbs)) (nil? @selected-db-key))
      (reset! selected-db-key (:db-key (first dbs))))
    [mui-stack {:spacing 1}
     [mui-stack {:sx {:text-align "center" :align-items "center"}}
      [mui-typography {:variant "subtitle1" :sx {:font-weight "bold"}} "Save Passkey"]]

     (when rp-name
       [mui-typography {:variant "body2" :color "text.secondary"} (str "Site: " rp-name)])
     [mui-divider]
     [mui-typography {:variant "subtitle1" :sx {:font-weight "bold"}} "Select database:"]
     [mui-list {:dense true :sx {:width "100%" :bgcolor "background.paper"}}
      (doall
       (for [{:keys [db-key db-name]} dbs]
         ^{:key db-key}
         [mui-list-item-button
          {:selected (= @selected-db-key db-key)
           :on-click #(reset! selected-db-key db-key)
           :sx       item-sx}
          [mui-list-item-text {:primary db-name}]]))]
     [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end" :sx {:mt 1}}
      [cancel-button]
      [mui-button {:variant "contained" :size "small"
                   :disabled (nil? @selected-db-key)
                   :on-click #(iframe-content-messaging/send-passkey-db-chosen @selected-db-key)}
       "Next"]]]))

;; ── Step 2: Group picker ──────────────────────────────────────────────────────

(defn- step-group-pick [groups]
  [mui-stack {:spacing 1}
   [mui-stack {:sx {:text-align "center" :align-items "center"}} [mui-typography {:variant "subtitle1" :sx {:font-weight "bold"}} "Choose Group"]]
   [mui-divider]
   [mui-stack  [mui-typography
                {:variant "body1" :sx {:font-weight "bold"}}
                #_{:variant "body2" :color "text.secondary"}
                "Select or create a group:"]]

   ;; "+ New Group" block at the top, outside the list
   [mui-box {:sx       {:border        (if @new-group? "1px solid #1976d2" "1px solid #e0e0e0")
                        :border-radius 1
                        :padding       "6px 12px"
                        :cursor        "pointer"
                        :bgcolor       (if @new-group? "rgba(25,118,210,0.08)" "transparent")}
             :on-click (fn []
                         (reset! new-group? true)
                         (reset! selected-group-uuid nil))}
    [mui-typography {:variant "body2" :color "primary"} "+ New group…"]]
   (when @new-group?
     [text-input "Group name" new-group-name])
   [mui-divider]
   [mui-list {:dense true :sx {:width "100%" :bgcolor "background.paper"
                               :max-height 160 :overflow-y "auto"}}
    (doall
     (for [{:keys [group-uuid name]} (or groups [])]
       ^{:key group-uuid}
       [mui-list-item-button
        {:selected (and (not @new-group?) (= @selected-group-uuid group-uuid))
         :on-click (fn []
                     (reset! new-group? false)
                     (reset! selected-group-uuid group-uuid))
         :sx       item-sx}
        [mui-list-item-text {:primary name}]]))]
   [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end" :sx {:mt 1}}
    [cancel-button]
    [mui-button {:variant  "contained" :size "small"
                 :disabled (and (nil? @selected-group-uuid)
                                (not @new-group?))
                 :on-click (fn []
                             (if @new-group?
                               (iframe-content-messaging/send-passkey-group-chosen
                                nil (if (seq @new-group-name) @new-group-name "Passkeys"))
                               (iframe-content-messaging/send-passkey-group-chosen
                                @selected-group-uuid nil)))}
     "Next"]]])

;; ── Step 3: Entry picker ──────────────────────────────────────────────────────

(defn- step-entry-pick [entries rp-name]
  (let [entry-list (or entries [])]
    ;; Auto-select "new entry" when group has no entries
    (when (and (empty? entry-list) (not @new-entry?) (nil? @selected-entry-uuid))
      (reset! new-entry? true))
    [mui-stack {:spacing 1}
     [mui-stack {:sx {:text-align "center" :align-items "center"}}
      [mui-typography {:variant "subtitle1" :sx {:font-weight "bold"}} "Choose Entry"]]
     [mui-divider]
     [mui-stack  [mui-typography
                  {:variant "body1" :sx {:font-weight "bold"}}
                  "Select or create an entry:"]]
     #_[mui-typography {:variant "body2" :color "text.secondary"} "Select or create an entry:"]
     ;; "+ New Entry" block at the top, outside the list
     [mui-box {:sx       {:border        (if @new-entry? "1px solid #1976d2" "1px solid #e0e0e0")
                          :border-radius 1
                          :padding       "6px 12px"
                          :cursor        "pointer"
                          :bgcolor       (if @new-entry? "rgba(25,118,210,0.08)" "transparent")}
               :on-click (fn []
                           (reset! new-entry? true)
                           (reset! selected-entry-uuid nil))}
      [mui-typography {:variant "body2" :color "primary"} "+ New entry…"]]
     (when @new-entry?
       [text-input "Entry name" new-entry-name])
     [mui-list {:dense true :sx {:width "100%" :bgcolor "background.paper"
                                 :max-height 160 :overflow-y "auto"}}
      (doall
       (for [{:keys [entry-uuid title]} entry-list]
         ^{:key entry-uuid}
         [mui-list-item-button
          {:selected (and (not @new-entry?) (= @selected-entry-uuid entry-uuid))
           :on-click (fn []
                       (reset! new-entry? false)
                       (reset! selected-entry-uuid entry-uuid))
           :sx       item-sx}
          [mui-list-item-text {:primary title}]]))]
     [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end" :sx {:mt 1}}
      [cancel-button]
      [mui-button {:variant  "contained" :size "small"
                   :disabled (and (nil? @selected-entry-uuid) (not @new-entry?))
                   :on-click (fn []
                               (reset! current-step :saving)
                               (iframe-content-messaging/send-passkey-create-confirmed
                                {:db-key              @selected-db-key
                                 :group-uuid          (when-not @new-group? @selected-group-uuid)
                                 :new-group-name      (when @new-group?
                                                        (if (seq @new-group-name)
                                                          @new-group-name
                                                          "Passkeys"))
                                 :existing-entry-uuid (when-not @new-entry? @selected-entry-uuid)
                                 :new-entry-name      (when @new-entry?
                                                        (if (seq @new-entry-name)
                                                          @new-entry-name
                                                          (or rp-name "Passkey")))}))}
       "Save Passkey"]]]))

;; ── Step 4: Confirm cancel ────────────────────────────────────────────────────

(defn- step-confirm-cancel []
  [mui-stack {:spacing 2 :align-items "center" :sx {:py 2}}
   [mui-typography {:variant "body1" :sx {:font-weight "bold"}} "Cancel passkey creation?"]
   [mui-stack {:direction "row" :spacing 1 :justify-content "center" :sx {:flex-wrap "wrap"}}
    [mui-button {:variant "outlined" :size "small"
                 :on-click #(reset! current-step @prev-step)}
     "Go Back"]
    [mui-button {:variant "contained" :size "small" :color "error"
                 :on-click #(iframe-content-messaging/send-passkey-create-cancelled)}
     "Cancel"]
    #_[mui-button {:variant "outlined" :size "small" :color "warning"
                 :on-click #(iframe-content-messaging/send-passkey-create-fallback)}
     "Use Browser Default"]]])

;; ── Step 5: Saving ────────────────────────────────────────────────────────────

(defn- step-saving []
  [mui-stack {:spacing 2 :align-items "center" :sx {:py 4}}
   [mui-typography {:variant "body1" :color "text.secondary"} "Saving passkey…"]])

;; ── Step 5: Success ───────────────────────────────────────────────────────────

(defn- step-success []
  [mui-stack {:spacing 1 :align-items "center" :sx {:py 4}}
   [mui-typography {:variant "h5" :color "success.main"} "✓"]
   [mui-typography {:variant "body1"} "Passkey saved!"]])

;; ── main content ──────────────────────────────────────────────────────────────

(defn- main-content []
  (let [{:keys [databases rp-name]} @(iframe-content-messaging/passkey-create-data)
        groups   @(iframe-content-messaging/passkey-groups)
        entries  @(iframe-content-messaging/passkey-entries)
        success? @(iframe-content-messaging/passkey-create-success?)]
    ;; Reactive step transitions — guards ensure each fires only once
    (when (and (= @current-step :db-pick) (some? groups))
      (reset! current-step :group-pick))
    (when (and (= @current-step :group-pick) (some? entries))
      (reset! current-step :entry-pick))
    (when (and (not= @current-step :success) success?)
      (reset! current-step :success)
      (js/setTimeout #(iframe-content-messaging/send-passkey-create-cancelled) 1500))
    [mui-box {:sx {:min-height 80
                   :bgcolor    "background.paper"
                   :border-color "black"
                   :border-style "solid"
                   :border-width ".5px"
                   :box-shadow 0
                   :border-radius 1
                   :margin "5px"
                   :padding "8px 10px 8px 10px"}}
     (case @current-step
       :db-pick        [step-db-pick {:databases databases :rp-name rp-name}]
       :group-pick     [step-group-pick groups]
       :entry-pick     [step-entry-pick entries rp-name]
       :confirm-cancel [step-confirm-cancel]
       :saving         [step-saving]
       :success        [step-success])]))

;; ── auto-resize ───────────────────────────────────────────────────────────────

(defn- send-size-to-host []
  (let [body   (.-body js/document)
        width  (.-scrollWidth body)
        height (.-scrollHeight body)]
    (js/window.parent.postMessage
     #js {:type   RESIZE_IFRAME_PASSKEY_CREATE
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
  (rd/render [main-content] (.getElementById ^js/Window js/document "app")))
