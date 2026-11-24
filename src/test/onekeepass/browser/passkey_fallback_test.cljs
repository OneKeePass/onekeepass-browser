(ns onekeepass.browser.passkey-fallback-test
  (:require
   [cljs.test :refer-macros [deftest is use-fixtures]]
   [onekeepass.browser.background.events.passkey]
   [onekeepass.browser.common.message-type-names
    :refer [OKP_PASSKEY_BROWSER_DEFAULT OKP_PASSKEY_REJECT OKP_PASSKEY_RESOLVE_GET SHOW_PASSKEY_LIST]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

(def sender
  {:tab-id 1 :frame-id 0 :document-id "original"
   :frame-url "https://login.example.com/path" :frame-origin "https://login.example.com"})

(defn request [rp-id]
  (assoc sender
         :type :get
         :request-id "web-request"
         :options-json (.stringify js/JSON #js {:rpId rp-id :challenge "aGVsbG8"})))

(def connected {:app-port #js {} :association-id "test" :settings {:passkeys-enabled true}})
(def matches [{:db-key "vault.kdbx" :entry-uuid "entry" :rp-id "example.com" :username "test"}])
(def selection {:passkey-request-id "web-request" :db-key "vault.kdbx" :entry-uuid "entry"})

(def messages (atom []))
(def desktop (atom []))

(use-fixtures :each
  (fn [f]
    (let [restore (rf/make-restore-fn)]
      (try
        (reset! rf-db/app-db connected)
        (reset! messages [])
        (reset! desktop [])
        (rf/clear-fx :common/send-content-message-regfx)
        (rf/clear-fx :dispatch)
        (rf/reg-fx :common/send-content-message-regfx #(swap! messages conj %))
        (rf/reg-fx :dispatch #(swap! desktop conj %))
        (f)
        (finally (restore))))))

(defn send-list [browser-enabled? passkeys]
  (rf/dispatch-sync
   [:passkey/list-received
    {:request-id (get-in @rf-db/app-db [:pending-webauthn :app-request-id])
     :message-content {:browser-enabled-db-available browser-enabled? :passkey-list passkeys}}]))

(defn last-message []
  (:message-type (:message-data (peek @messages))))

(deftest no-matching-passkey-or-database-uses-browser
  (doseq [available? [true false]]
    (rf/dispatch-sync [:passkey/request (request "example.com")])
    (is (= "GetPasskeyList" (get-in (peek @desktop) [1 :action])))
    (send-list available? [])
    (is (= OKP_PASSKEY_BROWSER_DEFAULT (last-message)))
    (is (nil? (:pending-webauthn @rf-db/app-db)))
    (is (= {:tab-id 1 :frame-id 0 :document-id "original"}
           (dissoc (peek @messages) :message-data)))))

(deftest native-choice-is-bound-to-the-request-and-document
  (rf/dispatch-sync [:passkey/request (request "example.com")])
  (send-list true matches)
  (is (= SHOW_PASSKEY_LIST (last-message)))
  (rf/dispatch-sync [:passkey/use-browser (assoc sender :document-id "different") selection])
  (rf/dispatch-sync [:passkey/use-browser sender (assoc selection :passkey-request-id "older")])
  (is (= 1 (count @messages)))
  (rf/dispatch-sync [:passkey/use-browser sender selection])
  (is (= OKP_PASSKEY_BROWSER_DEFAULT (last-message)))
  (is (nil? (:pending-webauthn @rf-db/app-db))))

(deftest choosing-an-offered-passkey-still-signs-in-with-onekeepass
  (rf/dispatch-sync [:passkey/request (request "example.com")])
  (send-list true matches)
  (rf/dispatch-sync [:passkey/entry-selected sender (assoc selection :entry-uuid "different")])
  (is (= 1 (count @desktop)))
  (rf/dispatch-sync [:passkey/entry-selected sender selection])
  (is (= "CompletePasskeyAssertion" (get-in (peek @desktop) [1 :action])))
  (rf/dispatch-sync [:passkey/assertion-complete {:request-id "stale" :message-content "old"}])
  (is (= SHOW_PASSKEY_LIST (last-message)))
  (rf/dispatch-sync [:passkey/assertion-complete
                     {:request-id (get-in @rf-db/app-db [:pending-webauthn :app-request-id])
                      :message-content "credential"}])
  (is (= OKP_PASSKEY_RESOLVE_GET (last-message)))
  (is (= "credential" (get-in (peek @messages) [:message-data :credential-json]))))

(deftest native-handles-disabled-and-unsupported-requests
  (doseq [invalid [(request "unrelated.example") (request "com")
                   (-> (request "github.io")
                       (assoc :frame-origin "https://test.github.io"
                              :frame-url "https://test.github.io/"))
                   (assoc (request "example.com") :frame-origin "null")]]
    (rf/dispatch-sync [:passkey/request invalid])
    (is (= OKP_PASSKEY_BROWSER_DEFAULT (last-message))))
  (reset! rf-db/app-db (assoc connected :settings {:passkeys-enabled false}))
  (rf/dispatch-sync [:passkey/request (request "example.com")])
  (is (= OKP_PASSKEY_BROWSER_DEFAULT (last-message)))
  (is (empty? @desktop)))

(deftest concurrent-request-and-cancellation-do-not-overwrite-the-chooser
  (rf/dispatch-sync [:passkey/request (request "example.com")])
  (send-list true matches)
  (rf/dispatch-sync [:passkey/request (assoc (request "example.com") :tab-id 2 :request-id "other")])
  (is (= OKP_PASSKEY_BROWSER_DEFAULT (last-message)))
  (is (= 1 (:tab-id (:pending-webauthn @rf-db/app-db))))
  (rf/dispatch-sync [:passkey/cancel sender selection])
  (is (= OKP_PASSKEY_REJECT (last-message)))
  (is (nil? (:pending-webauthn @rf-db/app-db))))

(deftest registration-uses-the-browser-when-no-vault-is-open
  (rf/dispatch-sync [:passkey/request
                     (assoc (request "example.com") :type :create
                            :options-json "{\"challenge\":\"aGVsbG8\",\"rp\":{\"id\":\"example.com\"}}")])
  (is (= "GetOpenedDatabasesForPasskey" (get-in (peek @desktop) [1 :action])))
  (rf/dispatch-sync [:passkey/databases-received
                     {:request-id (get-in @rf-db/app-db [:pending-webauthn :app-request-id])
                      :message-content []}])
  (is (= OKP_PASSKEY_BROWSER_DEFAULT (last-message))))
