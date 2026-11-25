(ns onekeepass.browser.content.iframe.save-login-popup
  "Iframe popup offering to save a login the user just submitted on the site.

   - New login (NoEntryForSite / NewUsername): Save adds a Login entry; the user
     can correct the username, change the title and pick the database and group.
   - Changed password (PasswordChanged): Update writes the new password to the
     chosen existing entry; its old password stays in the entry's history. Save
     as new adds a separate entry instead.

   The popup never sees the password: it shows the site and username (the
   password only as dots), and sends back only the user's choice. The background
   holds the captured login and checks the choice against what the app offered."
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_SAVE_LOGIN]]
   [onekeepass.browser.common.mui-components
    :refer [mui-box mui-button mui-list mui-list-item-button mui-stack mui-typography]]
   [onekeepass.browser.common.okp-theme :as okp-theme]
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
(def ^:private username (r/atom nil))

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

(defn- init-new-entry-choices! [{:keys [host save-targets] captured :username}]
  (let [target (or (target-for save-targets @selected-db-key) (first save-targets))]
    (reset! selected-db-key (:db-key target))
    (reset! selected-group-uuid (:group-uuid (root-group (:groups target))))
    (when (nil? @title)
      (reset! title (or host "")))
    (when (nil? @username)
      (reset! username (or captured "")))))

(defn- field [label control]
  [mui-box {:sx {:mt 1}}
   [mui-typography {:variant "caption" :color "text.secondary" :component "label"
                    :sx {:display "block" :mb 0.5 :font-weight 500}}
    label]
   control])

(defn- text-input [value-atom & {:keys [placeholder max-length]}]
  [:input {:type "text" :value (or @value-atom "") :placeholder placeholder
           :max-length (or max-length 256) :spell-check false
           :on-change #(reset! value-atom (-> % .-target .-value))
           :style okp-theme/input-style}])

;; ── sections ──────────────────────────────────────────────────────────────────

(defn- login-preview
  "What is about to be saved: site avatar, site, username and the password as dots"
  [{:keys [host]} shown-username]
  [mui-stack {:direction "row" :spacing 1.25 :align-items "center"
              :sx {:p 1.25 :border-radius "12px" :bgcolor "action.hover"
                   :border "1px solid var(--okp-divider)"}}
   [okp-theme/letter-avatar host 36]
   [mui-box {:sx {:min-width 0 :flex 1}}
    [mui-typography {:variant "body2" :sx {:font-weight 600 :overflow "hidden"
                                          :text-overflow "ellipsis" :white-space "nowrap"}}
     (if (str/blank? shown-username) "(no username)" shown-username)]
    [mui-typography {:variant "caption" :color "text.secondary" :component "div"
                     :sx {:letter-spacing "0.12em"}}
     "••••••••••"]]])

(defn- secondary-actions []
  [mui-stack {:direction "row" :justify-content "space-between" :align-items "center"
              :sx {:mt 1.5}}
   [mui-button {:size "small" :variant "text"
                :sx {:color "text.secondary" :font-weight 500 :px 1 :min-width 0}
                :on-click (on-trusted-click iframe-content-messaging/send-save-login-never-for-site)}
    "Never for this site"]
   [mui-button {:size "small" :variant "text"
                :sx {:color "text.secondary" :font-weight 500 :px 1 :min-width 0}
                :on-click (on-trusted-click iframe-content-messaging/send-save-login-dismissed)}
    "Not now"]])

(defn- update-choice [{:keys [update-candidates username] :as data}]
  (when (and (nil? @selected-candidate) (seq update-candidates))
    (reset! selected-candidate (select-keys (first update-candidates) [:db-key :entry-uuid])))
  [mui-stack {:spacing 1.25 :sx {:px 1.75 :pb 1.5}}
   [login-preview data username]
   [mui-typography {:variant "body2" :color "text.secondary"}
    (if (> (count update-candidates) 1)
      "Choose the entry to update:"
      "The new password replaces the one in:")]
   [mui-list {:dense true :disable-padding true :sx {:max-height 168 :overflow-y "auto"}}
    (doall
     (map-indexed
      (fn [i {:keys [db-key db-name entry-uuid title username]}]
        (let [selected? (= @selected-candidate {:db-key db-key :entry-uuid entry-uuid})]
          ^{:key (str db-key entry-uuid)}
          [mui-list-item-button
           {:selected selected?
            :on-click #(reset! selected-candidate {:db-key db-key :entry-uuid entry-uuid})
            :sx (merge {:mb 0.5 :px 1 :py 0.75 :gap 1.25
                        :border (if selected? "1px solid var(--okp-accent)" "1px solid transparent")
                        "&.Mui-selected, &.Mui-selected:hover" {:bgcolor "action.selected"}}
                       (okp-theme/row-in-sx i))}
           [okp-theme/letter-avatar title 30]
           [mui-box {:sx {:min-width 0 :flex 1}}
            [mui-typography {:variant "body2" :sx {:font-weight 600 :overflow "hidden"
                                                  :text-overflow "ellipsis" :white-space "nowrap"}}
             (if (str/blank? title) "(no title)" title)]
            [mui-typography {:variant "caption" :color "text.secondary" :component "div"
                             :sx {:overflow "hidden" :text-overflow "ellipsis" :white-space "nowrap"}}
             (str/join " · " (remove str/blank? [username db-name]))]]]))
      update-candidates))]
   [mui-typography {:variant "caption" :color "text.secondary"}
    "The old password is kept in the entry's history."]
   [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end"}
    [mui-button {:variant "outlined"
                 :on-click (on-trusted-click (fn []
                                               (init-new-entry-choices! data)
                                               (reset! step :new)))}
     "Save as new"]
    [mui-button {:variant "contained"
                 :disabled (nil? @selected-candidate)
                 :on-click (on-trusted-click
                            (fn []
                              (reset! step :saving)
                              (iframe-content-messaging/send-save-login-confirmed
                               (assoc @selected-candidate :choice "update"))))}
     "Update"]]
   [secondary-actions]])

(defn- new-entry-choice [{:keys [save-targets status] :as data}]
  (when (nil? @selected-db-key)
    (init-new-entry-choices! data))
  (let [target (target-for save-targets @selected-db-key)]
    [mui-stack {:spacing 0.25 :sx {:px 1.75 :pb 1.5}}
     (if (empty? save-targets)
       [:<>
        [login-preview data @username]
        [mui-typography {:variant "body2" :color "error.main" :sx {:pt 1}}
         "No database enabled for the browser is open in OneKeePass."]]
       [:<>
        [field "Username" [text-input username :max-length 4096 :placeholder "(none)"]]
        ;; The password itself never reaches this popup: shown as dots only
        [field "Password"
         [mui-box {:sx (merge okp-theme/input-style
                              {:color "text.secondary" :letter-spacing "0.18em"
                               :bgcolor "action.hover" :user-select "none"})}
          "••••••••••"]]
        [field "Title" [text-input title]]
        (when (> (count save-targets) 1)
          [field "Database"
           [:select {:value (or @selected-db-key "")
                     :on-change (fn [e]
                                  (reset! selected-db-key (-> e .-target .-value))
                                  (reset! selected-group-uuid
                                          (:group-uuid (root-group (:groups (target-for save-targets @selected-db-key))))))
                     :style okp-theme/input-style}
            (for [{:keys [db-key db-name]} save-targets]
              ^{:key db-key} [:option {:value db-key} (if (str/blank? db-name) db-key db-name)])]])
        [field "Group"
         [:select {:value (or @selected-group-uuid "")
                   :on-change #(reset! selected-group-uuid (-> % .-target .-value))
                   :style okp-theme/input-style}
          (for [{:keys [group-uuid name]} (sort-by #(str/lower-case (or (:name %) "")) (:groups target))]
            ^{:key group-uuid} [:option {:value group-uuid} name])]]])
     [mui-stack {:direction "row" :spacing 1 :justify-content "flex-end" :sx {:pt 1.5}}
      (when (= status STATUS_PASSWORD_CHANGED)
        [mui-button {:variant "outlined"
                     :on-click (on-trusted-click #(reset! step :choose))}
         "Back"])
      [mui-button {:variant "contained"
                   :disabled (or (empty? save-targets) (nil? @selected-db-key))
                   :on-click (on-trusted-click
                              (fn []
                                (reset! step :saving)
                                (iframe-content-messaging/send-save-login-confirmed
                                 {:choice "add"
                                  :db-key @selected-db-key
                                  :group-uuid @selected-group-uuid
                                  :title @title
                                  :username @username})))}
       "Save"]]
     [secondary-actions]]))

(defn- saving-view []
  [mui-stack {:spacing 1 :align-items "center" :sx {:py 3}}
   [mui-typography {:variant "body2" :color "text.secondary"} "Saving…"]])

(def ^:private close-scheduled? (atom false))

(defn- result-view [{:keys [ok created error-message]}]
  (if ok
    (do
      (when-not @close-scheduled?
        (reset! close-scheduled? true)
        (js/setTimeout okp-theme/request-close! 1600))
      [mui-stack {:spacing 1 :align-items "center" :sx {:pt 1 :pb 3 :color "success.main"}}
       [okp-theme/success-check 52]
       [mui-typography {:variant "body1" :sx {:font-weight 600 :color "text.primary"}}
        (if created "Login saved" "Password updated")]
       [mui-typography {:variant "caption" :color "text.secondary"} "in OneKeePass"]])
    [mui-stack {:spacing 1 :sx {:px 1.75 :pb 1.5}}
     [mui-typography {:variant "body1" :color "error.main" :sx {:font-weight 600}}
      "The login was not saved"]
     (when-not (str/blank? error-message)
       [mui-typography {:variant "body2" :color "text.secondary"} error-message])
     [mui-stack {:direction "row" :justify-content "flex-end"}
      [mui-button {:variant "outlined" :on-click okp-theme/request-close!} "Close"]]]))

;; ── main content ──────────────────────────────────────────────────────────────

(defn- main-content []
  (let [{:keys [status host] :as data} @(iframe-content-messaging/save-login-data)
        result @(iframe-content-messaging/save-login-result)
        updating? (and (= status STATUS_PASSWORD_CHANGED) (= @step :choose))]
    [mui-box {:sx (merge (okp-theme/card-sx :notification) {:min-width 340})}
     [okp-theme/popup-header {:title (cond
                                       (some? result) "OneKeePass"
                                       updating? "Update password?"
                                       :else "Save login?")
                              :detail host}]
     (cond
       (nil? data) [mui-typography {:variant "body2" :color "text.secondary" :sx {:px 1.75 :pb 2}} "Loading…"]
       (some? result) [result-view result]
       (= @step :saving) [saving-view]
       updating? [update-choice data]
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
  (rd/render [okp-theme/themed [main-content]] (.getElementById ^js/Window js/document "app")))
