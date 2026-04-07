(ns onekeepass.browser.background.events.passkey
  "re-frame events that drive the WebAuthn proxy flows for both Chrome and Firefox.

   Create (registration) flow
   ──────────────────────────
   Chrome: Chrome fires onCreateRequest  →  [:passkey/create-request]
   Firefox: content relays OKP_PASSKEY_CREATE  →  [:passkey/create-request]
   2. Desktop returns OpenedDatabasesForPasskey  →  [:passkey/databases-received]
      • Shows DB-picker popup.
   3. User picks DB  →  [:passkey/db-chosen]  →  fetches groups.
   4. Desktop returns DbGroupsForPasskey  →  [:passkey/groups-received]
      • Shows group-picker step.
   5. User picks group  →  [:passkey/group-chosen]  →  fetches entries (or empty if new group).
   6. Desktop returns DbGroupEntriesForPasskey  →  [:passkey/entries-received]
      • Shows entry-picker step.
   7. User confirms  →  PASSKEY_CREATE_CONFIRMED  →  [:passkey/db-selected-for-create]  →  CREATE_PASSKEY.
   8. Desktop returns PasskeyCreated  →  [:passkey/created]
      • Sends PASSKEY_CREATE_SUCCESS to iframe (shows success), then resolves WebAuthn Promise.

   Get (authentication) flow
   ──────────────────────────
   Chrome: Chrome fires onGetRequest  →  [:passkey/get-request]
   Firefox: content relays OKP_PASSKEY_GET  →  [:passkey/get-request]
   2. Desktop returns PasskeyList  →  [:passkey/list-received]
      • If empty  → Chrome: completeGetRequest NotAllowedError
                    Firefox: OKP_PASSKEY_REJECT to content.
      • Otherwise → send SHOW_PASSKEY_LIST to content; user picks a passkey.
   3. User selects passkey  →  [:passkey/entry-selected]
      (Routed here from :route-entry-selected in messaging.cljs.)
   4. Desktop returns PasskeyAssertionComplete  →  [:passkey/assertion-complete]
   5. Chrome: completeGetRequest called with assertion JSON.
      Firefox: OKP_PASSKEY_RESOLVE_GET sent to content script → MAIN world resolves Promise."
  (:require
   [onekeepass.browser.background.passkey :as wap]
   [onekeepass.browser.background.events.common :as bg-cmn-event
    :refer [COMPLETE_PASSKEY_ASSERTION
            CREATE_PASSKEY
            GET_DB_GROUP_ENTRIES_FOR_PASSKEY
            GET_DB_GROUPS_FOR_PASSKEY
            GET_OPENED_DATABASES_FOR_PASSKEY
            GET_PASSKEY_LIST]]
   [onekeepass.browser.common.message-type-names :refer [BACKGROUND_ERROR
                                                         NO_BROWSER_ENABLED_DB
                                                         NO_MATCHING_PASSKEYS
                                                         OKP_PASSKEY_BROWSER_DEFAULT
                                                         OKP_PASSKEY_REJECT
                                                         OKP_PASSKEY_RESOLVE_CREATE
                                                         OKP_PASSKEY_RESOLVE_GET
                                                         PASSKEY_CREATE_SUCCESS
                                                         SHOW_PASSKEY_CREATE_POPUP
                                                         SHOW_PASSKEY_ENTRIES
                                                         SHOW_PASSKEY_GROUPS
                                                         SHOW_PASSKEY_LIST]]
   [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-fx]]
   [onekeepass.browser.common.utils :as u]))

;; ── helpers ───────────────────────────────────────────────────────────────────

(defn- filename-from-path
  "Extracts the last path component from a db-key string (used as db-name fallback)."
  [db-key]
  (let [idx (.lastIndexOf db-key "/")]
    (if (>= idx 0)
      (.substring db-key (inc idx))
      db-key)))

(defn- passkey-summaries->matched-entries
  "Transforms a seq of PasskeySummary maps (kebab-case keywords, from Rust) into
   the matched-entries shape expected by the entry-list iframe:
   [{:db-key, :db-name, :entry-summaries [{:uuid :title :secondary-title :icon-id}]}]"
  [summaries]
  (->> summaries
       (group-by :db-key)
       (map (fn [[db-key items]]
              {:db-key         db-key
               :db-name        (filename-from-path db-key)
               :entry-summaries
               (mapv (fn [{:keys [entry-uuid rp-id username]}]
                       {:uuid            entry-uuid
                        :title           (or rp-id "Unknown Site")
                        :secondary-title (or username "")
                        :icon-id         0})
                     items)}))))

(defn- firefox? [pending]
  (= (:platform pending) :firefox))

;; ── Create (registration) flow ────────────────────────────────────────────────

(reg-event-fx
 :passkey/create-request
 (fn [{:keys [db]} [_event-id {:keys [chrome-request-id options-json rp-name origin tab-url tab-id platform]}]]
   (if (get-in db [:settings :passkeys-enabled] true)
     (cond
       (nil? (get-in db [:app-port]))
       ;; App not running / not connected — inform user and reject
       {:fx [(if (= platform :firefox)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_REJECT
                                :request-id   chrome-request-id
                                :error-name   "NotAllowedError"}
                 :tab-id tab-id :frame-id 0}]
               [:passkey/complete-create-error-regfx
                {:request-id chrome-request-id :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type BACKGROUND_ERROR
                              :message      "OneKeePass app is not running or not connected. Please start the app and try again."}
               :tab-id tab-id :frame-id 0}]]}

       (nil? (get db :association-id))
       ;; App connected but browser extension not yet authorized — inform user and fall back
       {:fx [(if (= platform :firefox)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_BROWSER_DEFAULT
                                :request-id   chrome-request-id
                                :options-json options-json}
                 :tab-id tab-id :frame-id 0}]
               [:passkey/complete-create-error-regfx
                {:request-id chrome-request-id :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type BACKGROUND_ERROR
                              :message      "OneKeePass browser extension is not yet authorized. Please check the OneKeePass app and allow the browser extension access."}
               :tab-id tab-id :frame-id 0}]]}

       :else
       ;; App connected and associated — proceed normally
       {:db (assoc db :pending-webauthn {:type              :create
                                         :chrome-request-id chrome-request-id
                                         :options-json      options-json
                                         :rp-name           rp-name
                                         :origin            origin
                                         :tab-url           tab-url
                                         :tab-id            tab-id
                                         :platform          platform})
        :fx [[:dispatch [:send-app-request
                         {:action     GET_OPENED_DATABASES_FOR_PASSKEY
                          :request-id (bg-cmn-event/generate-request-id)}
                         true]]]})
     ;; Passkeys disabled — bypass to browser native (Firefox only;
     ;; Chrome proxy is already detached when the setting is turned off).
     (when (= platform :firefox)
       {:fx [[:common/send-content-message-regfx
              {:message-data {:message-type OKP_PASSKEY_BROWSER_DEFAULT
                              :request-id   chrome-request-id
                              :options-json options-json}
               :tab-id       tab-id
               :frame-id     0}]]}))))

(reg-event-fx
 :passkey/databases-received
 (fn [{:keys [db]} [_event-id {:keys [message-content]}]]
   (let [databases message-content          ;; cljs vec of {:db-key :db-name}
         pending   (get db :pending-webauthn)
         tab-id    (or (:tab-id pending) (bg-cmn-event/current-tab-id db))]
     (if (empty? databases)
       ;; No open databases — inform user and reject
       {:db (dissoc db :pending-webauthn)
        :fx [(if (firefox? pending)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_REJECT
                                :request-id   (:chrome-request-id pending)
                                :error-name   "NotAllowedError"}
                 :tab-id       (:tab-id pending)
                 :frame-id     0}]
               [:passkey/complete-create-error-regfx
                {:request-id (:chrome-request-id pending)
                 :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type BACKGROUND_ERROR
                              :message      "No database is open in OneKeePass. Please open a database and try again."}
               :tab-id       tab-id
               :frame-id     0}]]}
       ;; Show DB-picker popup in the content script
       {:db (assoc-in db [:pending-webauthn :databases] databases)
        :fx [[:common/send-content-message-regfx
              {:message-data {:message-type        SHOW_PASSKEY_CREATE_POPUP
                              :passkey-create-data {:databases databases
                                                    :rp-name   (or (:rp-name pending) "Passkey")}}
               :tab-id       tab-id
               :frame-id     0}]]}))))

;; User picked a database — fetch that DB's groups
(reg-event-fx
 :passkey/db-chosen
 (fn [{:keys [db]} [_event-id {:keys [db-key]}]]
   {:db (assoc-in db [:pending-webauthn :db-key] db-key)
    :fx [[:dispatch [:send-app-request
                     {:action     GET_DB_GROUPS_FOR_PASSKEY
                      :request-id (bg-cmn-event/generate-request-id)
                      :db-key     db-key}
                     true]]]}))

;; Desktop returned the group list for the chosen DB
(reg-event-fx
 :passkey/groups-received
 (fn [{:keys [db]} [_event-id {:keys [message-content]}]]
   (let [pending (get db :pending-webauthn)
         tab-id  (or (:tab-id pending) (bg-cmn-event/current-tab-id db))]
     {:fx [[:common/send-content-message-regfx
            {:message-data {:message-type SHOW_PASSKEY_GROUPS
                            :groups       message-content}
             :tab-id       tab-id
             :frame-id     0}]]})))

;; User picked (or named) a group — fetch its entries, or emit empty list for new groups
(reg-event-fx
 :passkey/group-chosen
 (fn [{:keys [db]} [_event-id {:keys [group-uuid new-group-name]}]]
   (let [pending (get db :pending-webauthn)
         tab-id  (or (:tab-id pending) (bg-cmn-event/current-tab-id db))]
     (if group-uuid
       ;; Existing group — ask desktop for its entries
       {:db (assoc-in db [:pending-webauthn :group-uuid] group-uuid)
        :fx [[:dispatch [:send-app-request
                         {:action     GET_DB_GROUP_ENTRIES_FOR_PASSKEY
                          :request-id (bg-cmn-event/generate-request-id)
                          :db-key     (get-in db [:pending-webauthn :db-key])
                          :group-uuid group-uuid}
                         true]]]}
       ;; New group — no entries yet; emit empty list immediately
       {:db (assoc-in db [:pending-webauthn :new-group-name] new-group-name)
        :fx [[:common/send-content-message-regfx
              {:message-data {:message-type SHOW_PASSKEY_ENTRIES :entries []}
               :tab-id       tab-id
               :frame-id     0}]]}))))

;; Desktop returned the entry list for the chosen group
(reg-event-fx
 :passkey/entries-received
 (fn [{:keys [db]} [_event-id {:keys [message-content]}]]
   (let [pending (get db :pending-webauthn)
         tab-id  (or (:tab-id pending) (bg-cmn-event/current-tab-id db))]
     {:fx [[:common/send-content-message-regfx
            {:message-data {:message-type SHOW_PASSKEY_ENTRIES
                            :entries      message-content}
             :tab-id       tab-id
             :frame-id     0}]]})))

;; User clicked "Save Passkey" — send full create request to desktop
(reg-event-fx
 :passkey/db-selected-for-create
 (fn [{:keys [db]} [_event-id {:keys [db-key group-uuid new-group-name existing-entry-uuid new-entry-name]}]]
   (let [pending (get db :pending-webauthn)]
     {:fx [[:dispatch [:send-app-request
                       {:action              CREATE_PASSKEY
                        :request-id          (bg-cmn-event/generate-request-id)
                        :db-key              db-key
                        :options-json        (:options-json pending)
                        :origin              (:origin pending)
                        :tab-url             (:tab-url pending)
                        :new-entry-name      (or new-entry-name (:rp-name pending) "Passkey")
                        :group-uuid          (or group-uuid (get-in pending [:group-uuid]))
                        :new-group-name      (or new-group-name (get-in pending [:new-group-name]))
                        :existing-entry-uuid existing-entry-uuid}
                       true]]]})))

;; Desktop confirmed passkey created — notify iframe then resolve the WebAuthn Promise
(reg-event-fx
 :passkey/created
 (fn [{:keys [db]} [_event-id {:keys [message-content]}]]
   #_(u/okp-console-log "In :passkey/created fx - message-content" message-content)
   (let [pending (get db :pending-webauthn)
         tab-id  (or (:tab-id pending) (bg-cmn-event/current-tab-id db))]
     {:db (dissoc db :pending-webauthn)
      :fx [;; 1. Notify iframe — show "Passkey saved!" success message
           [:common/send-content-message-regfx
            {:message-data {:message-type PASSKEY_CREATE_SUCCESS}
             :tab-id       tab-id
             :frame-id     0}]
           ;; 2. Complete the WebAuthn proxy (Chrome or Firefox)
           (if (firefox? pending)
             [:common/send-content-message-regfx
              {:message-data {:message-type    OKP_PASSKEY_RESOLVE_CREATE
                              :request-id      (:chrome-request-id pending)
                              :credential-json message-content}
               :tab-id       (:tab-id pending)
               :frame-id     0}]
             [:passkey/complete-create-ok-regfx
              {:request-id    (:chrome-request-id pending)
               :response-json message-content}])]})))

;; ── Get (authentication) flow ─────────────────────────────────────────────────

(reg-event-fx
 :passkey/get-request
 (fn [{:keys [db]} [_event-id {:keys [chrome-request-id options-json origin tab-url tab-id platform]}]]
   (if (get-in db [:settings :passkeys-enabled] true)
     (cond
       (nil? (get-in db [:app-port]))
       ;; App not running / not connected — inform user and reject
       {:fx [(if (= platform :firefox)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_REJECT
                                :request-id   chrome-request-id
                                :error-name   "NotAllowedError"}
                 :tab-id tab-id :frame-id 0}]
               [:passkey/complete-get-error-regfx
                {:request-id chrome-request-id :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type BACKGROUND_ERROR
                              :message      "OneKeePass app is not running or not connected. Please start the app and try again."}
               :tab-id tab-id :frame-id 0}]]}

       (nil? (get db :association-id))
       ;; App connected but browser extension not yet authorized — inform user and fall back
       {:fx [(if (= platform :firefox)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_BROWSER_DEFAULT
                                :request-id   chrome-request-id
                                :options-json options-json}
                 :tab-id tab-id :frame-id 0}]
               [:passkey/complete-get-error-regfx
                {:request-id chrome-request-id :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type BACKGROUND_ERROR
                              :message      "OneKeePass browser extension is not yet authorized. Please check the OneKeePass app and allow the browser extension access."}
               :tab-id tab-id :frame-id 0}]]}

       :else
       ;; App connected and associated — proceed normally
       {:db (assoc db :pending-webauthn {:type              :get
                                         :chrome-request-id chrome-request-id
                                         :options-json      options-json
                                         :origin            origin
                                         :tab-url           tab-url
                                         :tab-id            tab-id
                                         :platform          platform})
        :fx [[:dispatch [:send-app-request
                         {:action       GET_PASSKEY_LIST
                          :request-id   (bg-cmn-event/generate-request-id)
                          :options-json options-json
                          :origin       origin
                          :tab-url      tab-url}
                         true]]]})
     ;; Passkeys disabled — bypass to browser native (Firefox only).
     (when (= platform :firefox)
       {:fx [[:common/send-content-message-regfx
              {:message-data {:message-type OKP_PASSKEY_BROWSER_DEFAULT
                              :request-id   chrome-request-id
                              :options-json options-json}
               :tab-id       tab-id
               :frame-id     0}]]}))))

;; This is called when we receive message PASSKEY_LIST from the main app
(reg-event-fx
 :passkey/list-received
 (fn [{:keys [db]} [_event-id {:keys [message-content]}]]
   (let [browser-enabled-db-available (:browser-enabled-db-available message-content)
         passkey-list                 (:passkey-list message-content)
         pending                      (get db :pending-webauthn)
         tab-id                       (or (:tab-id pending) (bg-cmn-event/current-tab-id db))]
     (cond
       (= browser-enabled-db-available false)
       ;; No database is open — inform user and reject
       {:db (dissoc db :pending-webauthn)
        :fx [(if (firefox? pending)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_REJECT
                                :request-id   (:chrome-request-id pending)
                                :error-name   "NotAllowedError"}
                 :tab-id       tab-id
                 :frame-id     0}]
               [:passkey/complete-get-error-regfx
                {:request-id (:chrome-request-id pending)
                 :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type NO_BROWSER_ENABLED_DB}
               :tab-id       tab-id
               :frame-id     0}]]}

       (empty? passkey-list)
       ;; DB open but no matching passkeys for this site — inform user and reject
       {:db (dissoc db :pending-webauthn)
        :fx [(if (firefox? pending)
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_REJECT
                                :request-id   (:chrome-request-id pending)
                                :error-name   "NotAllowedError"}
                 :tab-id       tab-id
                 :frame-id     0}]
               [:passkey/complete-get-error-regfx
                {:request-id (:chrome-request-id pending)
                 :error-name "NotAllowedError"}])
             [:common/send-content-message-regfx
              {:message-data {:message-type NO_MATCHING_PASSKEYS}
               :tab-id       tab-id
               :frame-id     0}]]}

       :else
       ;; Show selection popup in the content script
       (let [matched-entries (passkey-summaries->matched-entries passkey-list)]
         {:fx [[:common/send-content-message-regfx
                {:message-data {:message-type   SHOW_PASSKEY_LIST
                                :matched-entries matched-entries}
                 :tab-id       tab-id
                 :frame-id     0}]]})))))

(reg-event-fx
 :passkey/entry-selected
 (fn [{:keys [db]} [_event-id {:keys [db-key entry-uuid]}]]
   (let [pending (get db :pending-webauthn)]
     {:fx [[:dispatch [:send-app-request
                       {:action       COMPLETE_PASSKEY_ASSERTION
                        :request-id   (bg-cmn-event/generate-request-id)
                        :db-key       db-key
                        :entry-uuid   entry-uuid
                        :options-json (:options-json pending)
                        :origin       (:origin pending)
                        :tab-url      (:tab-url pending)}
                       true]]]})))

(reg-event-fx
 :passkey/assertion-complete
 (fn [{:keys [db]} [_event-id {:keys [message-content]}]]
   (let [pending (get db :pending-webauthn)]
     {:db (dissoc db :pending-webauthn)
      :fx [(if (firefox? pending)
             [:common/send-content-message-regfx
              {:message-data {:message-type   OKP_PASSKEY_RESOLVE_GET
                              :request-id     (:chrome-request-id pending)
                              :credential-json message-content}
               :tab-id       (:tab-id pending)
               :frame-id     0}]
             [:passkey/complete-get-ok-regfx
              {:request-id    (:chrome-request-id pending)
               :response-json message-content}])]})))

(reg-event-fx
 :passkey/request-cancelled
 (fn [{:keys [db]} [_event-id {:keys [request-id]}]]
   (let [pending (get db :pending-webauthn)]
     (if (= (:chrome-request-id pending) request-id)
       {:db (dissoc db :pending-webauthn)}
       {}))))

;; User chose "Use Browser Default" — detach OKP proxy then reject the current intercepted request.
;; After detaching, Chrome handles all future WebAuthn calls natively.
;; The site receives NotAllowedError and must retry to get Chrome's built-in passkey UI.
(reg-event-fx
 :passkey/create-fallback
 (fn [{:keys [db]} [_event-id]]
   (let [pending (get db :pending-webauthn)]
     (if (nil? pending)
       {}
       {:db (dissoc db :pending-webauthn)
        :fx [(if (firefox? pending)
               ;; Firefox: delegate to the original navigator.credentials.create() saved
               ;; by the MAIN world relay script before it patched navigator.credentials.
               [:common/send-content-message-regfx
                {:message-data {:message-type OKP_PASSKEY_BROWSER_DEFAULT
                                :request-id   (:chrome-request-id pending)
                                :options-json (:options-json pending)}
                 :tab-id       (:tab-id pending)
                 :frame-id     0}]
               [:passkey/complete-create-fallback-regfx
                {:request-id (:chrome-request-id pending)}])]}))))

(reg-fx
 :passkey/complete-create-fallback-regfx
 (fn [{:keys [request-id]}]
   ;; Detach so Chrome handles the site's retry natively.
   ;; OKP will reattach naturally when the MV3 service worker next restarts.
   (wap/detach!)
   (wap/complete-create-error! request-id "NotAllowedError")))

;; User dismissed the passkey creation popup — reject the pending request.
;; Guard: if pending is nil the request was already resolved (e.g. auto-close
;; after a successful save) — do nothing to avoid calling the Chrome/Firefox
;; WebAuthn API with a nil requestId.
(reg-event-fx
 :passkey/create-cancelled
 (fn [{:keys [db]} [_event-id]]
   (let [pending (get db :pending-webauthn)]
     (if (nil? pending)
       {}
       {:db (dissoc db :pending-webauthn)
        :fx [(if (firefox? pending)
               [:common/send-content-message-regfx
                {:message-data {:message-type  OKP_PASSKEY_REJECT
                                :request-id    (:chrome-request-id pending)
                                :error-name    "NotAllowedError"
                                :error-message "User cancelled passkey creation"}
                 :tab-id       (:tab-id pending)
                 :frame-id     0}]
               [:passkey/complete-create-error-regfx
                {:request-id (:chrome-request-id pending)
                 :error-name "NotAllowedError"}])]}))))

;; User dismissed the passkey list popup via × — reject the pending GET request.
;; Guard: if pending is nil the request was already resolved — do nothing.
(reg-event-fx
 :passkey/get-cancelled
 (fn [{:keys [db]} [_event-id]]
   (let [pending (get db :pending-webauthn)]
     (if (nil? pending)
       {}
       {:db (dissoc db :pending-webauthn)
        :fx [(if (firefox? pending)
               [:common/send-content-message-regfx
                {:message-data {:message-type  OKP_PASSKEY_REJECT
                                :request-id    (:chrome-request-id pending)
                                :error-name    "NotAllowedError"
                                :error-message "User cancelled passkey authentication"}
                 :tab-id       (:tab-id pending)
                 :frame-id     0}]
               [:passkey/complete-get-error-regfx
                {:request-id (:chrome-request-id pending)
                 :error-name "NotAllowedError"}])]}))))

;; ── reg-fx side-effect handlers ───────────────────────────────────────────────

(reg-fx
 :passkey/complete-create-ok-regfx
 (fn [{:keys [request-id response-json]}]
   (wap/complete-create-ok! request-id response-json)))

(reg-fx
 :passkey/complete-create-error-regfx
 (fn [{:keys [request-id error-name]}]
   (wap/complete-create-error! request-id error-name)))

(reg-fx
 :passkey/complete-get-ok-regfx
 (fn [{:keys [request-id response-json]}]
   (wap/complete-get-ok! request-id response-json)))

(reg-fx
 :passkey/complete-get-error-regfx
 (fn [{:keys [request-id error-name]}]
   (wap/complete-get-error! request-id error-name)))

;; ── Settings events ────────────────────────────────────────────────────────────

(reg-event-db
 :settings/loaded
 (fn [db [_ settings]]
   (assoc db :settings settings)))

(reg-event-fx
 :settings/passkeys-enabled-changed
 (fn [{:keys [db]} [_ enabled?]]
   {:db (assoc-in db [:settings :passkeys-enabled] enabled?)
    :fx [(when-not (u/is-firefox-browser?)
           (if enabled?
             [:passkey/attach-regfx nil]
             [:passkey/detach-regfx nil]))]}))

(reg-fx
 :passkey/attach-regfx
 (fn [_]
   (when (wap/available?)
     (wap/attach!))))

(reg-fx
 :passkey/detach-regfx
 (fn [_]
   (when (wap/available?)
     (wap/detach!))))
