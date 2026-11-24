(ns onekeepass.browser.background.events.passkey
  (:require
   ["tldts" :refer [getDomain]]
   [clojure.string :as str]
   [onekeepass.browser.background.events.common :as bg-cmn-event
    :refer [COMPLETE_PASSKEY_ASSERTION
            CREATE_PASSKEY
            GET_DB_GROUP_ENTRIES_FOR_PASSKEY
            GET_DB_GROUPS_FOR_PASSKEY
            GET_OPENED_DATABASES_FOR_PASSKEY
            GET_PASSKEY_LIST]]
   [onekeepass.browser.common.message-type-names
    :refer [OKP_PASSKEY_BROWSER_DEFAULT OKP_PASSKEY_REJECT
            OKP_PASSKEY_RESOLVE_CREATE OKP_PASSKEY_RESOLVE_GET
            PASSKEY_CREATE_SUCCESS SHOW_PASSKEY_CREATE_POPUP SHOW_PASSKEY_ENTRIES
            SHOW_PASSKEY_GROUPS SHOW_PASSKEY_LIST]]
   [re-frame.core :refer [reg-event-db reg-event-fx]]))

(defn- request-target [request]
  (select-keys request [:tab-id :frame-id :document-id :frame-url]))

(defn- allowed-rp-id? [host rp-id]
  (let [rp-id (when (string? rp-id) (str/lower-case rp-id))]
    (boolean
     (and rp-id
          (or (= rp-id "localhost")
              (and (re-matches #"[a-z0-9.-]+" rp-id)
                   (getDomain rp-id #js {:allowPrivateDomains true :extractHostname false})))
          (or (= host rp-id) (str/ends-with? host (str "." rp-id)))))))

(defn- request-details [{:keys [type frame-url frame-origin frame-id tab-id options-json]}]
  ;; Use the browser-supplied sender, never the origin claimed by a page message.
  ;; Delegate cross-origin/embedded requests and unsupported RP IDs to WebAuthn.
  (when (and (some? tab-id) (= frame-id 0) (string? frame-url) (string? options-json))
    (try
      (let [url           (js/URL. frame-url)
            origin        (.-origin url)
            host          (.-hostname url)
            options       (js->clj (.parse js/JSON options-json) :keywordize-keys true)
            rp-id         (get-in options (if (= type :get) [:rpId] [:rp :id]) host)]
        (when (and (or (= "https:" (.-protocol url))
                       (and (= "http:" (.-protocol url)) (= "localhost" host)))
                   (or (nil? frame-origin) (= frame-origin origin))
                   (string? (:challenge options))
                   (allowed-rp-id? host rp-id)
                   (not= "cross-platform" (get-in options [:authenticatorSelection :authenticatorAttachment])))
          {:origin origin
           :rp-name (or (get-in options [:rp :name]) rp-id)}))
      (catch :default _ nil))))

(defn- content-effect [request message-type details]
  [:common/send-content-message-regfx
   (assoc (select-keys request [:tab-id :frame-id :document-id])
          :message-data (merge {:message-type message-type
                                :request-id (:request-id request)
                                :expected-passkey-origin (some-> (:frame-url request) js/URL. .-origin)}
                               details))])

(defn- finish [db pending type details]
  {:db (dissoc db :pending-webauthn)
   :fx [(content-effect pending type details)]})

(defn- reject [db pending]
  (finish db pending OKP_PASSKEY_REJECT {:error-name "NotAllowedError"}))

(defn- same-request? [pending sender request-id]
  (and pending
       (= request-id (:request-id pending))
       (= (select-keys pending [:tab-id :frame-id :document-id])
          (select-keys sender [:tab-id :frame-id :document-id]))
       (or (nil? (:frame-origin sender)) (= (:origin pending) (:frame-origin sender)))))

(defn- app-request [db pending phase action details]
  (let [request-id (bg-cmn-event/generate-request-id)]
    {:db (assoc db :pending-webauthn (assoc pending :app-request-id request-id :phase phase))
     :fx [[:dispatch [:send-app-request (assoc details :action action :request-id request-id) true]]]}))

(defn- matching-response [db response phase]
  (let [pending (:pending-webauthn db)]
    (when (and (= phase (:phase pending))
               (= (:request-id response) (:app-request-id pending)))
      pending)))

(defn- passkey-summaries->matched-entries [summaries]
  (mapv (fn [[db-key items]]
          {:db-key db-key
           :db-name (last (str/split db-key #"[/\\]"))
           :entry-summaries (mapv (fn [{:keys [entry-uuid rp-id username]}]
                                   {:uuid entry-uuid
                                    :title rp-id
                                    :secondary-title (or username "")
                                    :icon-id 0})
                                 items)})
        (group-by :db-key summaries)))

(reg-event-fx
 :passkey/request
 (fn [{:keys [db]} [_ request]]
   (when (and (= 0 (:frame-id request)) (some? (:tab-id request))
              (string? (:request-id request)) (string? (:frame-url request)))
     (let [details (request-details request)]
       (if (and details (:app-port db) (:association-id db)
                (get-in db [:settings :passkeys-enabled] false)
                (nil? (:pending-webauthn db)))
         (let [pending (merge (select-keys request [:request-id :options-json :type])
                              (request-target request) details)
               options (assoc (select-keys pending [:origin :options-json]) :tab-url (:frame-url pending))]
           (if (= :get (:type request))
             (app-request db pending :lookup GET_PASSKEY_LIST options)
             (app-request db pending :databases GET_OPENED_DATABASES_FOR_PASSKEY {})))
         {:fx [(content-effect request OKP_PASSKEY_BROWSER_DEFAULT {})]})))))

(reg-event-fx
 :passkey/list-received
 (fn [{:keys [db]} [_ {:keys [message-content] :as response}]]
   (when-let [pending (matching-response db response :lookup)]
     (let [{:keys [browser-enabled-db-available passkey-list]} message-content]
       (if (and (not= false browser-enabled-db-available) (seq passkey-list))
         (let [entries (passkey-summaries->matched-entries passkey-list)]
           {:db (assoc db :pending-webauthn (assoc pending :phase :choose :entries entries))
            :fx [(content-effect pending SHOW_PASSKEY_LIST {:matched-entries entries})]})
         (finish db pending OKP_PASSKEY_BROWSER_DEFAULT {}))))))

(reg-event-fx
 :passkey/entry-selected
 (fn [{:keys [db]} [_ sender {:keys [db-key entry-uuid passkey-request-id]}]]
   (let [pending (:pending-webauthn db)]
     (when (and (same-request? pending sender passkey-request-id)
                (= :choose (:phase pending))
                (some (fn [entry-db]
                        (and (= db-key (:db-key entry-db))
                             (some #(= entry-uuid (:uuid %)) (:entry-summaries entry-db))))
                      (:entries pending)))
       (app-request db pending :asserting COMPLETE_PASSKEY_ASSERTION
                    {:db-key db-key
                     :entry-uuid entry-uuid
                     :options-json (:options-json pending)
                     :origin (:origin pending)
                     :tab-url (:frame-url pending)})))))

(reg-event-fx
 :passkey/assertion-complete
 (fn [{:keys [db]} [_ {:keys [message-content] :as response}]]
   (when-let [pending (matching-response db response :asserting)]
     (finish db pending OKP_PASSKEY_RESOLVE_GET {:credential-json message-content}))))

(reg-event-fx
 :passkey/databases-received
 (fn [{:keys [db]} [_ {:keys [message-content] :as response}]]
   (when-let [pending (matching-response db response :databases)]
     (if (seq message-content)
       {:db (assoc db :pending-webauthn (assoc pending :phase :choose-db :databases message-content))
        :fx [(content-effect pending SHOW_PASSKEY_CREATE_POPUP
                             {:passkey-create-data {:databases message-content
                                                    :rp-name (:rp-name pending)}})]}
       (finish db pending OKP_PASSKEY_BROWSER_DEFAULT {})))))

(reg-event-fx
 :passkey/db-chosen
 (fn [{:keys [db]} [_ sender {:keys [db-key passkey-request-id]}]]
   (let [pending (:pending-webauthn db)]
     (when (and (same-request? pending sender passkey-request-id)
                (#{:choose-db :choose-group :choose-entry :groups :entries} (:phase pending))
                (some #(= db-key (:db-key %)) (:databases pending)))
       (app-request db (assoc pending :db-key db-key :group-uuid nil :new-group-name nil)
                    :groups GET_DB_GROUPS_FOR_PASSKEY {:db-key db-key})))))

(reg-event-fx
 :passkey/groups-received
 (fn [{:keys [db]} [_ {:keys [message-content] :as response}]]
   (when-let [pending (matching-response db response :groups)]
     {:db (assoc db :pending-webauthn (assoc pending :phase :choose-group))
      :fx [(content-effect pending SHOW_PASSKEY_GROUPS {:groups message-content})]})))

(reg-event-fx
 :passkey/group-chosen
 (fn [{:keys [db]} [_ sender {:keys [group-uuid new-group-name passkey-request-id]}]]
   (let [pending (:pending-webauthn db)]
     (when (and (same-request? pending sender passkey-request-id)
                (#{:choose-group :choose-entry :entries} (:phase pending)))
       (let [pending (assoc pending :group-uuid group-uuid :new-group-name new-group-name)]
         (if group-uuid
           (app-request db pending :entries GET_DB_GROUP_ENTRIES_FOR_PASSKEY
                        {:db-key (:db-key pending) :group-uuid group-uuid})
           {:db (assoc db :pending-webauthn (assoc pending :phase :choose-entry))
            :fx [(content-effect pending SHOW_PASSKEY_ENTRIES {:entries []})]}))))))

(reg-event-fx
 :passkey/entries-received
 (fn [{:keys [db]} [_ {:keys [message-content] :as response}]]
   (when-let [pending (matching-response db response :entries)]
     {:db (assoc db :pending-webauthn (assoc pending :phase :choose-entry))
      :fx [(content-effect pending SHOW_PASSKEY_ENTRIES {:entries message-content})]})))

(reg-event-fx
 :passkey/db-selected-for-create
 (fn [{:keys [db]} [_ sender {:keys [db-key group-uuid new-group-name existing-entry-uuid new-entry-name passkey-request-id]}]]
   (let [pending (:pending-webauthn db)]
     (when (and (same-request? pending sender passkey-request-id)
                (= :choose-entry (:phase pending)) (= db-key (:db-key pending)))
       (app-request db pending :creating CREATE_PASSKEY
                    {:db-key db-key
                     :options-json (:options-json pending)
                     :origin (:origin pending)
                     :tab-url (:frame-url pending)
                     :new-entry-name (or new-entry-name (:rp-name pending))
                     :group-uuid (or group-uuid (:group-uuid pending))
                     :new-group-name (or new-group-name (:new-group-name pending))
                     :existing-entry-uuid existing-entry-uuid})))))

(reg-event-fx
 :passkey/created
 (fn [{:keys [db]} [_ {:keys [message-content] :as response}]]
   (when-let [pending (matching-response db response :creating)]
     {:db (dissoc db :pending-webauthn)
      :fx [(content-effect pending PASSKEY_CREATE_SUCCESS {})
           (content-effect pending OKP_PASSKEY_RESOLVE_CREATE {:credential-json message-content})]})))

(reg-event-fx
 :passkey/use-browser
 (fn [{:keys [db]} [_ sender {:keys [passkey-request-id]}]]
   (let [pending (:pending-webauthn db)]
     (when (and (same-request? pending sender passkey-request-id)
                (not (#{:asserting :creating} (:phase pending))))
       (finish db pending OKP_PASSKEY_BROWSER_DEFAULT {})))))

(reg-event-fx
 :passkey/cancel
 (fn [{:keys [db]} [_ sender {:keys [passkey-request-id]}]]
   (let [pending (:pending-webauthn db)]
     (when (same-request? pending sender passkey-request-id)
       (reject db pending)))))

(reg-event-fx
 :passkey/desktop-failed
 (fn [{:keys [db]} [_ request-id]]
   (let [pending (:pending-webauthn db)]
     (when (and pending (or (nil? request-id) (= request-id (:app-request-id pending))))
       (if (#{:asserting :creating} (:phase pending))
         (reject db pending)
         (finish db pending OKP_PASSKEY_BROWSER_DEFAULT {}))))))

(reg-event-db
 :passkey/tab-left
 (fn [db [_ tab-id]]
   (if (= tab-id (get-in db [:pending-webauthn :tab-id]))
     (dissoc db :pending-webauthn)
     db)))

(reg-event-db
 :settings/loaded
 (fn [db [_ settings]]
   (assoc db :settings settings)))

(reg-event-fx
 :settings/passkeys-enabled-changed
 (fn [{:keys [db]} [_ enabled?]]
   (let [db (assoc-in db [:settings :passkeys-enabled] enabled?)
         pending (:pending-webauthn db)]
     (if (and (not enabled?) pending (not (#{:asserting :creating} (:phase pending))))
       (finish db pending OKP_PASSKEY_BROWSER_DEFAULT {})
       {:db db}))))
