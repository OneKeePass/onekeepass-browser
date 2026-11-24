(ns onekeepass.browser.autofill-security-test
  (:require
   [cljs.test :refer-macros [deftest is testing use-fixtures]]
   [onekeepass.browser.background.events.content]
   [onekeepass.browser.background.events.messaging]
   [onekeepass.browser.content.events.messaging :as content-messaging]
   [onekeepass.browser.common.message-type-names :refer [SELECTED_ENTRY_DETAIL]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

(use-fixtures :each
  (fn [f]
    (let [restore (rf/make-restore-fn)]
      (try (f) (finally (restore))))))

(defn capture-dispatches [db event]
  (let [dispatched (atom [])]
    (reset! rf-db/app-db db)
    (rf/clear-fx :dispatch)
    (rf/reg-fx :dispatch #(swap! dispatched conj %))
    (rf/dispatch-sync event)
    {:db @rf-db/app-db :dispatched @dispatched}))

(def selection
  {:selection-id "selection-1"
   :tab-id 4
   :frame-id 0
   :popup-frame-id 0
   :frame-url "https://login.example.test/"
   :tab-url "https://login.example.test/"
   :document-id "document-1"
   :entry-ids #{["vault" "entry-1"]}})

(def sender
  {:tab-id 4 :frame-id 0
   :tab-url "https://login.example.test/"
   :frame-url "https://login.example.test/"})

(def selected-entry
  {:db-key "vault" :entry-uuid "entry-1" :selection-id "selection-1"})

(deftest another-frame-cannot-redirect-a-selected-credential
  (let [{:keys [db]}
        (capture-dispatches
         {:tabs {4 {:login-field-frame 19 :autofill-selection selection}}}
         [:load-selected-entry-detail sender selected-entry])
        request (first (vals (:reques-ids db)))]
    (is (= 0 (:frame-id request))
        "The selected popup's frame remains the destination when another frame detects a login")))

(deftest stale-or-unoffered-selections-do-not-request-credentials
  (doseq [entry [(assoc selected-entry :selection-id "old-popup")
                 (assoc selected-entry :entry-uuid "not-offered")]]
    (let [{:keys [dispatched]}
          (capture-dispatches
           {:tabs {4 {:login-field-frame 0 :autofill-selection selection}}}
           [:load-selected-entry-detail sender entry])]
      (is (empty? dispatched)))))

(deftest main-frame-popup-can-select-only-its-original-embedded-login
  (let [embedded (assoc selection :frame-id 7
                                 :frame-url "https://embedded.example.test/login")
        {:keys [db dispatched]}
        (capture-dispatches
         {:tabs {4 {:login-field-frame 19 :autofill-selection embedded}}}
         [:load-selected-entry-detail sender selected-entry])
        target (first (vals (:reques-ids db)))]
    (is (= 7 (:frame-id target)))
    (is (= "document-1" (:document-id target)))
    (is (= "https://embedded.example.test/login" (:frame-url target)))
    (is (= "SelectedEntry" (:action (second (first dispatched)))))
    (is (nil? (get-in db [:tabs 4 :autofill-selection])))))

(deftest other-tabs-and-frames-cannot-select-from-a-popup
  (doseq [unrelated [(assoc sender :tab-id 8)
                     (assoc sender :frame-id 12)
                     (assoc sender :frame-url "https://different.example.test/")]]
    (let [{:keys [dispatched]}
          (capture-dispatches
           {:tabs {4 {:autofill-selection selection}
                   8 {:autofill-selection selection}}}
           [:load-selected-entry-detail unrelated selected-entry])]
      (is (empty? dispatched)))))

(deftest matching-uses-the-login-frame-origin
  (let [{:keys [dispatched]}
        (capture-dispatches
         {:app-port #js {} :association-id "association"}
         [:get-entry-list (assoc sender
                                 :frame-id 7
                                 :frame-url "https://embedded.example.test/login")])
        request (second (first dispatched))]
    (is (= "https://embedded.example.test" (:form-url request)))))

(deftest navigation-invalidates-open-popups-and-pending-fills
  (let [{:keys [db]}
        (capture-dispatches
         {:tabs {4 {:autofill-selection (assoc selection :frame-id 7)}}
          :reques-ids {"pending-fill" (assoc selection :frame-id 7)
                       "other-frame" (assoc selection :frame-id 12)}}
         [:content/web-navigation-committed 4 7 "https://different.example.test/"])]
    (is (nil? (get-in db [:tabs 4 :autofill-selection])))
    (is (nil? (get-in db [:reques-ids "pending-fill"])))
    (is (some? (get-in db [:reques-ids "other-frame"])))))

(deftest entry-list-pins-the-frame-and-the-offered-entries
  (let [{:keys [db dispatched]}
        (capture-dispatches
         {:tabs {4 {:login-field-frame 19}}
          :reques-ids {"selection-1" (assoc selection :frame-id 7)}}
         [:content/entry-list-loaded
          {:browser-enabled-db-available true
           :matched-entries [{:db-key "vault" :entry-summaries [{:uuid "entry-1"}]}]}
          "selection-1"])
        target (get-in db [:tabs 4 :autofill-selection])
        message (second (first dispatched))]
    (is (= 7 (:frame-id target)))
    (is (= 0 (:popup-frame-id target)))
    (is (= #{["vault" "entry-1"]} (:entry-ids target)))
    (is (= "selection-1" (:selection-id message)))))

(deftest credentials-are-rejected-after-cross-origin-navigation
  (let [old-window (.-window js/globalThis)
        old-chrome (.-chrome js/globalThis)
        listener (atom nil)
        dispatched (atom [])]
    (try
      (set! js/globalThis.window #js {:location #js {:origin "https://different.example.test"}})
      (set! js/globalThis.chrome
            #js {:runtime #js {:onMessage #js {:addListener #(reset! listener %)}}})
      (content-messaging/register-message-listener)
      (with-redefs [rf/dispatch #(swap! dispatched conj %)]
        (@listener #js {:message-type SELECTED_ENTRY_DETAIL
                        :expected-fill-origin "https://login.example.test"
                        :basic-entry-info #js {:username "dummy" :password "dummy"}}
                   nil nil))
      (is (empty? @dispatched))
      (with-redefs [rf/dispatch #(swap! dispatched conj %)]
        (@listener #js {:message-type SELECTED_ENTRY_DETAIL} nil nil))
      (is (empty? @dispatched) "Credential replies without a bound origin are rejected")
      (set! js/globalThis.window #js {:location #js {:origin "https://login.example.test"}})
      (with-redefs [rf/dispatch #(swap! dispatched conj %)]
        (@listener #js {:message-type SELECTED_ENTRY_DETAIL
                        :expected-fill-origin "https://login.example.test"} nil nil))
      (is (= :received-seleted-entry-basic-info (ffirst @dispatched)))
      (finally
        (set! js/globalThis.window old-window)
        (set! js/globalThis.chrome old-chrome)))))

(deftest credential-replies-use-the-pinned-document-and-origin
  (let [sent (atom [])
        old-chrome (.-chrome js/globalThis)]
    (try
      (set! js/globalThis.chrome
            #js {:tabs #js {:sendMessage (fn [tab message options]
                                          (swap! sent conj [tab (js->clj message :keywordize-keys true)
                                                            (js->clj options :keywordize-keys true)]))}})
      (reset! rf-db/app-db {:reques-ids {"fill" (assoc selection :frame-id 7)}})
      (rf/dispatch-sync [:common/send-tab-message-for-request-id
                         {:message-type SELECTED_ENTRY_DETAIL} "fill"])
      (is (= [[4 {:message-type SELECTED_ENTRY_DETAIL
                  :expected-fill-origin "https://login.example.test"}
               {:frameId 7 :documentId "document-1"}]] @sent))
      (is (empty? (:reques-ids @rf-db/app-db)))
      (reset! sent [])
      (rf/dispatch-sync [:common/send-tab-message-for-request-id
                         {:message-type SELECTED_ENTRY_DETAIL} "fill"])
      (is (empty? @sent) "A consumed or invalidated request cannot deliver credentials")
      (finally (set! js/globalThis.chrome old-chrome)))))

(deftest password-selection-is-not-routed-to-an-unrelated-passkey-request
  (let [{:keys [dispatched]}
        (capture-dispatches {:pending-webauthn {:type :get :tab-id 4}}
                            [:route-entry-selected sender selected-entry])]
    (is (= :load-selected-entry-detail (ffirst dispatched))))
  (let [{:keys [dispatched]}
        (capture-dispatches {:pending-webauthn {:type :get :tab-id 4}}
                            [:route-entry-selected sender (-> selected-entry
                                                             (dissoc :selection-id)
                                                             (assoc :passkey-request-id "passkey"))])]
    (is (= :passkey/entry-selected (ffirst dispatched)))))
