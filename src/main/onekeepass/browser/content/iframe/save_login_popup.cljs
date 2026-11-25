(ns onekeepass.browser.content.iframe.save-login-popup
  "Iframe popup offering to save a login the user just submitted on the site.

   - New login (NoEntryForSite / NewUsername): Save adds a Login entry; the user
     can change the title and pick the database and group.
   - Changed password (PasswordChanged): Update writes the new password to the
     chosen existing entry; its old password stays in the entry's history. Save
     as new adds a separate entry instead.

   The popup never sees the password: it shows the site and username, and sends
   back only the user's choice. The background holds the captured login and
   checks the choice against what the app offered."
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_SAVE_LOGIN]]
   [onekeepass.browser.common.mui-components
    :refer [mui-box mui-button mui-divider mui-list mui-list-item-button
            mui-list-item-text mui-stack mui-typography]]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [reagent.core :as r]
   [reagent.dom :as rd]))

(def ^:private STATUS_PASSWORD_CHANGED "PasswordChanged")

;; ── local state ───────────────────────────────────────────────────────────────

;; :choose (initial), :new (adding as a new entry), :saving
(def ^:private step (r/atom :choose))
(def ^:private selected-candidate (r/atom nil)) ;; {:db-key :entry-uuid}
(def ^:private selected-db-key (r/atom nil))
(def ^:private selected-group-uuid (r/atom nil))
(def ^:private title (r/atom nil))

;; Clickjacking hardening: act only on a genuine user click (see entry_list_popup)
(defn- trusted-event? [e]
  (boolean
   (or (j/get e :isTrusted)
       (some-> (j/get e :nativeEvent) (j/get :isTrusted)))))

(defn- on-trusted-click [f]
  (fn [e]
    (when (trusted-event? e)
      (f))))

;; ── helpers ───────────────────────────────────────────────────────────────────

(defn- root-group
  "The root group of a database: the one whose parent is not among its groups"
  [groups]
  (let [ids (set (map :group-uuid groups))]
    (or (first (remove #(contains? ids (:parent-group-uuid %)) groups))
        (first groups))))

(defn- target-for [save-targets db-key]
  (some #(when (= (:db-key %) db-key) %) save-targets))

(defn- init-new-entry-choices! [{:keys [host save-targets]}]
  (let [target (or (target-for save-targets @selected-db-key) (first save-targets))]
    (reset! selected-db-key (:db-key target))
    (reset! selected-group-uuid (:group-uuid (root-group (:groups target))))
    (when (nil? @title)
      (reset! title (or host "")))))

(def ^:private select-style
  {:width "100%" :box-sizing "border-box" :padding "5px 6px" :font-size "13px"
   :border "1px solid #ccc" :border-radius "4px" :font-family "Roboto, sans-serif"
   :background "white"})

(defn- field-label [text]
  [mui-typography {:variant "caption" :sx {:color "text.secondary" :display "block" :mt 1}}
   text])

;; ── sections ──────────────────────────────────────────────────────────────────

(defn- header [{:keys [status host username]}]
  [mui-stack {:spacing 0.5}
   [mui-typography {:variant "subtitle1" :sx {:font-weight "bold"}}
    (if (= status STATUS_PASSWORD_CHANGED) "Update saved password?" "Save this login?")]
   [mui-typography {:variant "body2" :color "text.secondary" :sx {:word-break "break-all"}}
    (str "Site: " host)]
   (when-not (str/blank? username)
     [mui-typography {:variant "body2" :color "text.secondary" :sx {:word-break "break-all"}}
      (str "Username: " username)])])

(defn- footer-secondary-actions []
  [mui-stack {:direction "row" :spacing 1 :justify-content "space-between" :sx {:mt 1.5}}
   [mui-button {:size "small" :color "inherit"
                :on-click (on-trusted-click iframe-content-messaging/send-save-login-never-for-site)}
    "Never for this site"]
   [mui-button {:size "small" :variant "outlined" :color "inherit"
                :on-click (on-trusted-click iframe-content-messaging/send-save-login-dismissed)}
    "Not now"]])

(defn- update-choice [{:keys [update-candidates] :as data}]
  (when (and (nil? @selected-candidate) (seq update-candidates))
    (reset! selected-candidate (select-keys (first update-candidates) [:db-key :entry-uuid])))
  [mui-stack {:spacing 1}
   [header data]
   [mui-divider]
   [mui-typography {:variant "body2"}
    (if (> (count update-candidates) 1)
      "Choose the entry to update:"
      "The new password will be saved to:")]
   [mui-list {:dense true :sx {:max-height 160 :overflow-y "auto" :py 0}}
    (doall
     (for [{:keys [db-key db-name entry-uuid title username]} update-candidates]
       ^{:key (str db-key entry-uuid)}
       [mui-list-item-button
        {:selected (= @selected-candidate {:db-key db-key :entry-uuid entry-uuid})
         :on-click #(reset! selected-candidate {:db-key db-key :entry-uuid entry-uuid})
         :sx {:border-radius 1 :mb 0.5
              "&.Mui-selected" {:bgcolor "rgba(25,118,210,0.12)"}}}
        [mui-list-item-text {:primary (if (str/blank? title) "(no title)" title)
                             :secondary (str/join " · " (remove str/blank? [username db-name]))}]]))]
   [mui-typography {:variant "caption" :color "text.secondary"}
    "The old password is kept in the entry's history."]
   [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end"}
    [mui-button {:size "small" :variant "outlined"
                 :on-click (on-trusted-click (fn []
                                               (init-new-entry-choices! data)
                                               (reset! step :new)))}
     "Save as new"]
    [mui-button {:size "small" :variant "contained"
                 :disabled (nil? @selected-candidate)
                 :on-click (on-trusted-click
                            (fn []
                              (reset! step :saving)
                              (iframe-content-messaging/send-save-login-confirmed
                               (assoc @selected-candidate :choice "update"))))}
     "Update"]]
   [footer-secondary-actions]])

(defn- new-entry-choice [{:keys [save-targets status] :as data}]
  (when (nil? @selected-db-key)
    (init-new-entry-choices! data))
  (let [target (target-for save-targets @selected-db-key)]
    [mui-stack {:spacing 0.5}
     [header data]
     [mui-divider {:sx {:my 0.5}}]
     (if (empty? save-targets)
       [mui-typography {:variant "body2" :color "error.main"}
        "No database enabled for the browser is open in OneKeePass."]
       [:<>
        [field-label "Title"]
        [:input {:type "text" :value (or @title "") :max-length 256
                 :on-change #(reset! title (-> % .-target .-value))
                 :style select-style}]
        (when (> (count save-targets) 1)
          [:<>
           [field-label "Database"]
           [:select {:value (or @selected-db-key "")
                     :on-change (fn [e]
                                  (reset! selected-db-key (-> e .-target .-value))
                                  (reset! selected-group-uuid
                                          (:group-uuid (root-group (:groups (target-for save-targets @selected-db-key))))))
                     :style select-style}
            (for [{:keys [db-key db-name]} save-targets]
              ^{:key db-key} [:option {:value db-key} (if (str/blank? db-name) db-key db-name)])]])
        [field-label "Group"]
        [:select {:value (or @selected-group-uuid "")
                  :on-change #(reset! selected-group-uuid (-> % .-target .-value))
                  :style select-style}
         (for [{:keys [group-uuid name]} (sort-by #(str/lower-case (or (:name %) "")) (:groups target))]
           ^{:key group-uuid} [:option {:value group-uuid} name])]])
     [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end" :sx {:mt 1.5}}
      (when (= status STATUS_PASSWORD_CHANGED)
        [mui-button {:size "small" :variant "outlined"
                     :on-click (on-trusted-click #(reset! step :choose))}
         "Back"])
      [mui-button {:size "small" :variant "contained"
                   :disabled (or (empty? save-targets) (nil? @selected-db-key))
                   :on-click (on-trusted-click
                              (fn []
                                (reset! step :saving)
                                (iframe-content-messaging/send-save-login-confirmed
                                 {:choice "add"
                                  :db-key @selected-db-key
                                  :group-uuid @selected-group-uuid
                                  :title @title})))}
       "Save"]]
     [footer-secondary-actions]]))

(defn- saving-view []
  [mui-stack {:spacing 2 :align-items "center" :sx {:py 3}}
   [mui-typography {:variant "body1" :color "text.secondary"} "Saving…"]])

(def ^:private close-scheduled? (atom false))

(defn- result-view [{:keys [ok created error-message]}]
  (if ok
    (do
      (when-not @close-scheduled?
        (reset! close-scheduled? true)
        (js/setTimeout iframe-content-messaging/close-save-login-popup 1500))
      [mui-stack {:spacing 1 :align-items "center" :sx {:py 3}}
       [mui-typography {:variant "h5" :color "success.main"} "✓"]
       [mui-typography {:variant "body1"} (if created "Login saved" "Password updated")]])
    [mui-stack {:spacing 1 :sx {:py 2}}
     [mui-typography {:variant "body1" :color "error.main"} "The login was not saved"]
     (when-not (str/blank? error-message)
       [mui-typography {:variant "body2" :color "text.secondary"} error-message])
     [mui-stack {:direction "row" :justify-content "flex-end"}
      [mui-button {:size "small" :variant "outlined"
                   :on-click iframe-content-messaging/close-save-login-popup}
       "Close"]]]))

;; ── main content ──────────────────────────────────────────────────────────────

(defn- main-content []
  (let [{:keys [status] :as data} @(iframe-content-messaging/save-login-data)
        result @(iframe-content-messaging/save-login-result)]
    [mui-box {:sx {:min-height 80
                   :bgcolor "background.paper"
                   :border-color "black"
                   :border-style "solid"
                   :border-width ".5px"
                   :border-radius 1
                   :margin "5px"
                   :padding "8px 10px 8px 10px"}}
     (cond
       (nil? data) [mui-typography {:variant "body2" :color "text.secondary"} "Loading…"]
       (some? result) [result-view result]
       (= @step :saving) [saving-view]
       (and (= status STATUS_PASSWORD_CHANGED) (= @step :choose)) [update-choice data]
       :else [new-entry-choice data])]))

;; ── auto-resize ───────────────────────────────────────────────────────────────

(defn- send-size-to-host []
  (let [body   (.-body js/document)
        width  (.-scrollWidth body)
        height (.-scrollHeight body)]
    (js/window.parent.postMessage
     #js {:type   RESIZE_IFRAME_SAVE_LOGIN
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
