(ns onekeepass.browser.background.events.credential-save
  "Offering to save a login the user submitted on a site: add it as a new entry,
   or update the password of an existing entry.

   Flow
   ────
   1. The content script sends CREDENTIAL_SUBMITTED (username, password, form kind)
      when the user submits a login, signup or change-password form.
   2. [[on-credential-submitted]] keeps it here, in memory only, keyed by tab. The
      site is taken from the browser's record of the sending tab and frame, never
      from the page, and only a same-origin https frame is accepted.
   3. The app is asked to check it (CheckLoginCredential, encrypted). The app
      compares it with the stored entries and answers with a status only - the
      stored password never comes back to the browser.
   4. The prompt is shown only once the login visibly worked: the tab loaded a new
      page that does not show a login form again, or (single page apps) the
      password field went away. A failed login (typo) is never offered for saving.
   5. The user picks Save / Update / Not now / Never for this site in the
      save-login popup. Only on Save / Update is SaveLoginCredential (encrypted)
      sent; the app re-validates the request before writing.

   The captured password is dropped when the user decides, when the tab closes,
   after a few minutes, or when the service worker stops. It is kept out of the
   re-frame app-db so it is never part of app state or its debug output, and it
   is never sent to the content script or the popup iframe."
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.background.crypto :as crypto]
   [onekeepass.browser.background.events.common :as bg-cmn-event
    :refer [CHECK_LOGIN_CREDENTIAL SAVE_LOGIN_CREDENTIAL]]
   [onekeepass.browser.common.message-type-names :refer [SAVE_LOGIN_RESULT
                                                         SHOW_SAVE_LOGIN_POPUP]]
   [onekeepass.browser.common.site-matching :as site-matching]
   [onekeepass.browser.common.utils :as u]
   [re-frame.core :refer [dispatch reg-event-fx reg-fx]]))

;; ── state ─────────────────────────────────────────────────────────────────────

;; tab-id -> {:capture-id :origin :username :password :form-kind :created-at
;;            :check (app's check answer, no secrets) :succeeded
;;            :failed :prompted :saving}
(defonce ^:private pending-logins (atom {}))

;; tab-id -> {:origin :username :created-at}: the username typed on the first step
;; of a two-step login, used when the password step has no username field
(defonce ^:private pending-usernames (atom {}))

;; How long a captured login waits for the user's decision
(def ^:private PENDING_TTL_MS (* 3 60 1000))

(def ^:private USERNAME_TTL_MS (* 5 60 1000))

;; After the tab loads the next page, how long to wait for that page to report a
;; login form again (the login failed) before treating the login as successful
(def ^:private FAILED_LOGIN_GRACE_MS 2500)

(def ^:private STATUS_UNCHANGED "Unchanged")
(def ^:private STATUS_PASSWORD_CHANGED "PasswordChanged")

(defn- now [] (.now js/Date))

(defn- drop-pending! [tab-id]
  (swap! pending-logins dissoc tab-id))

(defn- pending-for-capture [capture-id]
  (some (fn [[tab-id p]] (when (= (:capture-id p) capture-id) [tab-id p])) @pending-logins))

;; ── site checks ───────────────────────────────────────────────────────────────

(def ^:private loopback-hosts #{"localhost" "127.0.0.1" "[::1]"})

(defn- capture-origin
  "Returns the origin to save a login under, or nil when a login submitted from
   this tab/frame must not be offered for saving:
   - the frame must be same-origin with the top page. A login typed into a
     cross-origin embedded frame (another site's login framed by this page) is
     never saved under this page's site
   - the page must be https (plain http only for the local machine)"
  [{:keys [tab-url frame-url]}]
  (try
    (let [top   (js/URL. tab-url)
          frame (js/URL. (or frame-url tab-url))]
      (when (and (= (.-origin top) (.-origin frame))
                 (or (= "https:" (.-protocol top))
                     (and (= "http:" (.-protocol top))
                          (contains? loopback-hosts (.-hostname top)))))
        (.-origin top)))
    (catch :default _e
      nil)))

(defn- origin-host [origin]
  (site-matching/url->host origin))

(defn- saving-allowed? [db {:keys [tab-url]} origin]
  (let [settings (get db :settings)]
    (and (some? origin)
         (some? (get db :app-port))
         (some? (get db :association-id))
         (crypto/session-ready?)
         (not (false? (get settings :save-logins-enabled true)))
         (not (site-matching/site-disabled? tab-url (get settings :disabled-sites)))
         (not (some #(= % (origin-host origin)) (get settings :never-save-sites))))))

;; ── capture (called from the runtime message listener) ───────────────────────

(defn on-username-submitted
  "Remembers the username typed on the first step of a two-step login"
  [{:keys [tab-id incognito] :as sender-info} {:keys [username]}]
  (let [origin (capture-origin sender-info)]
    (when (and tab-id origin (not incognito) (not (str/blank? username)))
      (swap! pending-usernames assoc tab-id {:origin origin
                                             :username (str/trim username)
                                             :created-at (now)}))))

(defn- remembered-username [tab-id origin]
  (let [{:keys [username created-at] :as r} (get @pending-usernames tab-id)]
    (when (and r (= origin (:origin r)) (< (- (now) created-at) USERNAME_TTL_MS))
      username)))

(defn on-credential-submitted
  "Keeps a submitted login in memory and starts its check. The password goes into
   the private pending-logins atom only; the re-frame event gets the capture id"
  [{:keys [tab-id incognito] :as sender-info} {:keys [username password form-kind]}]
  (let [origin (capture-origin sender-info)]
    (when (and tab-id origin (not incognito)
               (string? password) (not (str/blank? password)))
      (let [capture-id (bg-cmn-event/generate-request-id)
            username (if (str/blank? username)
                       (or (remembered-username tab-id origin) "")
                       (str/trim username))]
        ;; A new submit in the tab replaces the previous one (e.g. a retry after a typo)
        (swap! pending-logins assoc tab-id {:capture-id capture-id
                                            :origin origin
                                            :tab-url (:tab-url sender-info)
                                            :username username
                                            :password password
                                            :form-kind (or form-kind "login")
                                            :created-at (now)})
        ;; Forget the captured password after the TTL whatever happens
        (js/setTimeout (fn []
                         (when (= capture-id (get-in @pending-logins [tab-id :capture-id]))
                           (drop-pending! tab-id)))
                       PENDING_TTL_MS)
        (dispatch [:credential/submitted tab-id capture-id])))))

(defn on-form-gone
  "The content script saw every password field leave the page (single page app
   login that does not load a new page): treat the login as successful. A short
   wait lets a login form that is rendered anew (failed login) be reported first"
  [{:keys [tab-id] :as sender-info}]
  (let [origin (capture-origin sender-info)
        {:keys [capture-id] :as p} (get @pending-logins tab-id)]
    (when (and p (= origin (:origin p)))
      (js/setTimeout (fn []
                       (when (= capture-id (get-in @pending-logins [tab-id :capture-id]))
                         (swap! pending-logins assoc-in [tab-id :succeeded] true)
                         (dispatch [:credential/maybe-prompt tab-id])))
                     1000))))

(defn on-login-form-seen
  "A content script of the tab found a login form. After a login was submitted in
   the tab, a login form found again on the same site (the next page, or the form
   rendered anew) means the login failed. Not used for signup / change-password
   forms: after those a site often shows its sign-in form for the new password.
   The content script of a new page may report this before the tab's navigation
   completed event, so it does not wait for that event"
  [{:keys [tab-id] :as sender-info}]
  (let [p (get @pending-logins tab-id)]
    (when (and p (not (:prompted p))
               (= "login" (:form-kind p))
               (> (now) (:created-at p))
               (= (:origin p) (bg-cmn-event/url->origin (:tab-url sender-info))))
      (swap! pending-logins assoc-in [tab-id :failed] true))))

(defn on-tab-navigation-completed
  "The top frame of a tab finished loading a page. If a login was submitted in
   this tab, give the new page a moment to show a login form again (failed login)
   and otherwise treat the login as successful"
  [tab-id]
  (when-let [{:keys [capture-id]} (get @pending-logins tab-id)]
    (js/setTimeout (fn []
                     (let [p (get @pending-logins tab-id)]
                       (when (= capture-id (:capture-id p))
                         (if (:failed p)
                           (drop-pending! tab-id)
                           (do
                             (swap! pending-logins assoc-in [tab-id :succeeded] true)
                             (dispatch [:credential/maybe-prompt tab-id]))))))
                   FAILED_LOGIN_GRACE_MS)))

(defn on-tab-removed [tab-id]
  (drop-pending! tab-id)
  (swap! pending-usernames dissoc tab-id))

;; ── encrypted requests ───────────────────────────────────────────────────────

(defn- encrypted-request
  "Forms an app request whose payload map is encrypted with the session keys.
   Returns nil when the session keys are not yet exchanged"
  [action request-id payload]
  (when-let [{:keys [message nonce]} (crypto/encrypt
                                      (js/JSON.stringify (bg-cmn-event/request->json payload)))]
    {:action action
     :request-id request-id
     :message message
     :nonce nonce}))

;; ── re-frame events ───────────────────────────────────────────────────────────

;; Validates the capture against the current settings and connection, then asks
;; the app to check it
(reg-event-fx
 :credential/submitted
 (fn [{:keys [db]} [_event-id tab-id capture-id]]
   (let [{:keys [origin username password tab-url] :as p} (get @pending-logins tab-id)]
     (if (and p (= capture-id (:capture-id p))
              (saving-allowed? db {:tab-url tab-url} origin))
       (if-let [request (encrypted-request CHECK_LOGIN_CREDENTIAL capture-id
                                           {:url origin :username username :password password})]
         {:fx [[:dispatch [:send-app-request request true]]]}
         {:fx [[:credential/drop-pending-regfx tab-id]]})
       {:fx [[:credential/drop-pending-regfx tab-id]]}))))

;; App answered CheckLoginCredential. message-content is the LoginCredentialCheck
;; map: status, site, update-candidates, save-targets (titles and names only)
(reg-event-fx
 :credential/check-received
 (fn [{:keys [_db]} [_event-id {:keys [message-content request-id]}]]
   (when-let [[tab-id _p] (pending-for-capture request-id)]
     (let [status (:status message-content)]
       (if (or (nil? message-content) (= status STATUS_UNCHANGED))
         ;; Nothing new to save
         {:fx [[:credential/drop-pending-regfx tab-id]]}
         (do
           (swap! pending-logins assoc-in [tab-id :check] message-content)
           {:fx [[:dispatch [:credential/maybe-prompt tab-id]]]}))))))

;; Shows the save-login popup once both the check answer and the success signal
;; are in, and only once per capture
(reg-event-fx
 :credential/maybe-prompt
 (fn [{:keys [_db]} [_event-id tab-id]]
   (let [{:keys [check succeeded prompted failed username form-kind origin] :as p}
         (get @pending-logins tab-id)]
     (when (and p check succeeded (not prompted) (not failed))
       (swap! pending-logins assoc-in [tab-id :prompted] true)
       {:fx [[:common/send-content-message-regfx
              {:tab-id tab-id
               :frame-id 0
               :message-data {:message-type SHOW_SAVE_LOGIN_POPUP
                              ;; No password in here: the popup never needs it
                              :save-login-data {:status (:status check)
                                                :site (or (:site check) origin)
                                                :host (origin-host origin)
                                                :username username
                                                :form-kind form-kind
                                                :update-candidates (:update-candidates check)
                                                :save-targets (:save-targets check)}}}]]}))))

(defn- valid-choice?
  "Checks the popup's choice against the app's own check answer, so only an entry
   the app offered can be updated and only an offered database/group written to"
  [{:keys [update-candidates save-targets status]} {:keys [choice db-key entry-uuid group-uuid]}]
  (case choice
    "update" (and (= status STATUS_PASSWORD_CHANGED)
                  (some #(and (= (:db-key %) db-key) (= (:entry-uuid %) entry-uuid))
                        update-candidates))
    "add" (when-let [target (some #(when (= (:db-key %) db-key) %) save-targets)]
            (or (nil? group-uuid)
                (some #(= (:group-uuid %) group-uuid) (:groups target))))
    false))

;; User confirmed Save / Update in the popup
(reg-event-fx
 :credential/save-confirmed
 (fn [{:keys [db]} [_event-id tab-id {:keys [choice db-key entry-uuid group-uuid title] :as choice-m}]]
   (let [{:keys [capture-id origin username password check prompted saving] :as p}
         (get @pending-logins tab-id)]
     (cond
       (or (nil? p) (not prompted) saving)
       {}

       (not (and (valid-choice? check choice-m)
                 (saving-allowed? db {:tab-url (:tab-url p)} origin)))
       {:fx [[:credential/drop-pending-regfx tab-id]
             [:credential/send-result-regfx {:tab-id tab-id :ok false
                                             :error-message "This login can no longer be saved"}]]}

       :else
       (if-let [request (encrypted-request SAVE_LOGIN_CREDENTIAL capture-id
                                           {:db-key db-key
                                            :url origin
                                            :username username
                                            :password password
                                            :entry-uuid (when (= choice "update") entry-uuid)
                                            :group-uuid (when (= choice "add") group-uuid)
                                            :title (when (= choice "add")
                                                     (some-> title str/trim not-empty))})]
         (do
           (swap! pending-logins assoc-in [tab-id :saving] true)
           {:fx [[:dispatch [:send-app-request request true]]]})
         {:fx [[:credential/drop-pending-regfx tab-id]
               [:credential/send-result-regfx {:tab-id tab-id :ok false
                                               :error-message "OneKeePass is not connected"}]]})))))

;; App answered SaveLoginCredential
(reg-event-fx
 :credential/saved
 (fn [{:keys [_db]} [_event-id {:keys [message-content request-id]}]]
   (when-let [[tab-id _p] (pending-for-capture request-id)]
     {:fx [[:credential/drop-pending-regfx tab-id]
           [:credential/send-result-regfx {:tab-id tab-id
                                           :ok true
                                           :created (boolean (:created message-content))}]]})))

;; The app returned an error for CheckLoginCredential or SaveLoginCredential
(reg-event-fx
 :credential/app-error
 (fn [{:keys [_db]} [_event-id {:keys [action error-message request-id]}]]
   (u/okp-println "Login save request failed" action error-message)
   (let [[tab-id p] (or (when request-id (pending-for-capture request-id))
                        ;; Error responses may not echo the request id: fall back to
                        ;; the capture waiting on this kind of request
                        (some (fn [[t p]]
                                (when (if (= action SAVE_LOGIN_CREDENTIAL)
                                        (:saving p)
                                        (and (nil? (:check p)) (not (:saving p))))
                                  [t p]))
                              @pending-logins))]
     (when p
       {:fx (cond-> [[:credential/drop-pending-regfx tab-id]]
              (:saving p)
              (conj [:credential/send-result-regfx {:tab-id tab-id
                                                    :ok false
                                                    :error-message (or error-message "Saving failed")}]))}))))

(reg-event-fx
 :credential/dismissed
 (fn [{:keys [_db]} [_event-id tab-id]]
   {:fx [[:credential/drop-pending-regfx tab-id]]}))

;; 'Never for this site': remember the host so the popup is not offered there again
(reg-event-fx
 :credential/never-for-site
 (fn [{:keys [_db]} [_event-id tab-id]]
   (let [host (some-> (get @pending-logins tab-id) :origin origin-host)]
     {:fx (cond-> [[:credential/drop-pending-regfx tab-id]]
            host (conj [:credential/add-never-save-site-regfx host]))})))

(reg-fx
 :credential/drop-pending-regfx
 (fn [tab-id]
   (drop-pending! tab-id)))

(reg-fx
 :credential/send-result-regfx
 (fn [{:keys [tab-id ok created error-message]}]
   (bg-cmn-event/message-to-tab tab-id
                                {:message-type SAVE_LOGIN_RESULT
                                 :save-login-result {:ok ok
                                                     :created created
                                                     :error-message error-message}}
                                0)))

;; Read-modify-write of the whole okp-settings object so other settings are kept
(reg-fx
 :credential/add-never-save-site-regfx
 (fn [host]
   (-> (js/chrome.storage.local.get "okp-settings")
       (.then (fn [result]
                (let [raw (j/get result :okp-settings)
                      settings (if raw (js->clj raw :keywordize-keys true) {})
                      hosts (vec (get settings :never-save-sites []))]
                  (when-not (some #(= % host) hosts)
                    (js/chrome.storage.local.set
                     (clj->js {"okp-settings" (assoc settings :never-save-sites (conj hosts host))}))))))
       (.catch (fn [e]
                 (js/console.error "Updating never-save-sites failed" e))))))
