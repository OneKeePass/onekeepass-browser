(ns onekeepass.browser.background.events.common
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [onekeepass.browser.common.message-type-names :refer [BACKGROUND_ERROR]]
   [onekeepass.browser.common.utils :as u]
   [re-frame.core :refer [reg-event-fx reg-fx]]))

;;;;;;;;;;  All 'action' values used in messages between extension and the native messaging app ;;;;;;;;;;

;; This one is returned from the proxy app itself
(def PROXY_APP_CONNECTION "PROXY_APP_CONNECTION")
(def PROXY_ERROR "PROXY_ERROR")

;; These are the messages sent to the main app thrpugh the native messaging app and the main app responds with these actions
(def INIT_SESSION_KEY "InitSessionKey")
(def ASSOCIATE "Associate")
(def ENABLED_DATABASE_MATCHED_ENTRY_LIST "EnabledDatabaseMatchedEntryList")
(def SELECTED_ENTRY "SelectedEntry")
(def DISCONNECT "DISCONNECT")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;; This should match the one declared in Native messaging manifests file
(def NATIVE_APP_ID "org.onekeepass.onekeepass_browser")
;; In dev mode we use a different native app id
(def NATIVE_APP_ID_DEV "org.onekeepass.onekeepass_browser_dev")

(defn generate-request-id []
  (js/crypto.randomUUID))

;; There is no Native messaging manifests file is found
#_(def NATIVE_MESSAGING_MANIFEST_IS_NOT_FOUND "NM_MANIFEST_IS_NOT_FOUND")

;;;;;;;;;;;

(defn transform-api-response
  "The arg 'response' is a json object returned from app"
  [response]
  (->> response js->clj (cske/transform-keys csk/->kebab-case-keyword)))

(defn parse-json-str->map
  "Parses a string to a json obj and then transforms to a cljs map "
  [message-str]
  (->> message-str (.parse js/JSON) js->clj (cske/transform-keys csk/->kebab-case-keyword)))

(defn request->json
  "Forms a js json object from a cljs map where keys are converted to be in 'snake_case'
   The arg 'request' is fully formed request map
   Returns a js object that can be sent as payload in postMessage call
   "
  [request]
  (->> request (cske/transform-keys csk/->snake_case) clj->js))

;; IMPORTANT: Need a valid tab-id and frame-id to send message to content script
;; If tab-id is nil, the message will not be sent and error is seen in console
(defn message-to-tab
  ([tab-id messge-data frame-id]
   (js/chrome.tabs.sendMessage tab-id (clj->js messge-data) (clj->js {:frameId frame-id})))

  ([tab-id messge-data]
   (js/chrome.tabs.sendMessage tab-id (clj->js messge-data))))

#_(defn- default-error-fn [error]
    (println "API returned error: " error))

#_(defn on-error
    "A common error handler for the background API call.
   The api-response map with keys [:ok :error] or [:ok] or [:error] is the first arg
   The arg 'error-fn' is called when there is an 'error' which is a map with key [:action :error-message]
   Returns true in case of error or false
  "
    ([{:keys [error]} error-fn]
     (if-not (nil? error)
       (do
         (if  (nil? error-fn)
           (default-error-fn error)
           (error-fn error))
         true)
       false))
    ([api-response]
     (on-error api-response nil)))

#_(defn on-ok
    "Receives a map with keys [:ok :error] or [:ok] or [:error].
   Returns the value of 'ok' in case there is no error or returns nil if there is an error and 
   calls the error-fn with error value
  "
    ([{:keys [ok error]} error-fn]
     (if-not (nil? error)
       (do
         (if (nil? error-fn)
           (default-error-fn error)
           (error-fn error))
         nil)
       ok))
    ([api-response]
     (on-ok api-response nil)))

#_(defn map->json-str
    "Converts a cljs map to a json str
   The arg 'message-map' is a non nil map
   Returns a string 
   "
    [message-map]
    (->> message-map (cske/transform-keys csk/->snake_case) clj->js (.stringify js/JSON)))

#_(defn message-to-current-tab
    "Called to send message to the current tab 
   The arg messge-data is a map with keys [message-type other-message-specific-data]
   "
    [messge-data]
    (js-await [^js tabs (js/chrome.tabs.query #js {:active true :currentWindow true})]
              (when-not (= (j/get tabs :length) 0)
                (js/chrome.tabs.sendMessage (j/get-in tabs [0 :id]) (clj->js messge-data)))))


;;;;;;;;;;;;;;;;;;  re-frame events ;;;;;;;;;;;;;;;;

(defn current-tab-id [app-db]
  ;; See ns background/events/content.cljs
  ;; current-tab-id is expected to be all the time non nil value!
  (get app-db :current-tab-id))

(defn proxy-connection-state [app-db]
  (get-in app-db [:app-connection :state]))

(defn login-page-content-frame-id [app-db]
  ;; See ns background/events/content.cljs
  ;; We use the frame-id of login fields detected frame in this current tab. 
  ;; If frame-id is nil, then we use 0
  (get-in app-db [:tabs (current-tab-id app-db) :login-field-frame] 0))

;; Called to send a message to the content script 
;; The ids tab-id and frame-id are retreived from the app-db
(reg-event-fx
 :common/send-message-to-current-tab
 (fn [{:keys [db]} [_event_id message-data use-main-frame-id]]
   (let [tab-id (current-tab-id db)
         frame-id (if use-main-frame-id 0 (login-page-content-frame-id db))]
     #_(println "common/send-message-to-current-tab tab-id frame-id message-data" tab-id frame-id message-data)
     (if (nil? tab-id)
       {}
       {:fx [[:common/send-content-message-regfx {:message-data message-data
                                                  :tab-id tab-id
                                                  :frame-id frame-id}]]}))))

;; Called to send an error message to the content script of current tab
;; The ids tab-id and frame-id are retreived from the app-db
;; TODO: Need to use request-id specific storage to get tab-id and frame-id if available
;; For now we use current tab-id and login page frame-id
(reg-event-fx
 :common/send-error-message-to-current-tab
 (fn [{:keys [db]} [_event_id {:keys [error-message connection-state]} use-main-frame-id]]

   (let [tab-id (current-tab-id db)
         frame-id (if use-main-frame-id 0 (login-page-content-frame-id db))
         ;; If the caller does not provide connection-state, then we use the current connection state from app-db if available
         connection-state (if (nil? connection-state) (get-in db [:app-connection :state]) connection-state)
         association-id (get-in db [:app-connection :association-id])]
     #_(u/okp-println "common/send-error-message-to-current-tab tab-id frame-id error-message" tab-id frame-id error-message)
     (if (nil? tab-id)
       {}
       {:fx [[:common/send-content-message-regfx {:message-data
                                                  {:message-type BACKGROUND_ERROR
                                                   :message error-message
                                                   :connection-state connection-state
                                                   :association-id association-id}

                                                  :tab-id tab-id
                                                  :frame-id frame-id}]]}))))

;; Gets the current tab id and frame id using request-id specific storage
;; TODO: 
;; IMPORTANT: Need to clear all old request-id specific cache storage 
;; Should be a timeout based cache clearing or web navigation based clearing
;; For now we remove the cache on use
(reg-event-fx
 :common/send-tab-message-for-request-id
 (fn [{:keys [db]} [_event_id message-data request-id use-main-frame-id]]
   #_(u/okp-println "In :common/send-tab-message-for-request-id before db keys " (keys db) (get-in db [:reques-ids request-id]))
   (let [{:keys [tab-id frame-id]} (get-in db [:reques-ids request-id])
         ;; Need to ensure that any error message popup should be shown in the main frame
         ;; In some site, the frame id may belong to iframe of the input element (e.g Charles schwab)
         ;; And that will block the input element
         frame-id (if use-main-frame-id  0 frame-id)
         db (if (nil? tab-id) db (update-in db [:reques-ids] dissoc request-id))]
     #_(u/okp-println "In :common/send-tab-message-for-request-id After db keys " (keys db) (get-in db [:reques-ids request-id]))
     #_(u/okp-println "In :common/send-tab-message-for-request-id" "tab-id" tab-id "frame-id"  frame-id "request-id"  request-id)
     (if (nil? tab-id)
       {}
       {:db db
        :fx [[:common/send-content-message-regfx {:message-data message-data
                                                  :tab-id tab-id
                                                  :frame-id frame-id}]]}))))

;; Called to send a message to the content script when we have tab-id and frame-id
;; Typically we have these ids from runtime.MessageSender
(reg-fx
 :common/send-content-message-regfx
 (fn [{:keys [tab-id frame-id message-data] :as _args}]
   #_(u/okp-println "common/send-content-message-regfx args are " _args)
   ;;(u/okp-println "common/send-content-message-regfx tab-id frame-id message-data are " tab-id message-data)
   (message-to-tab tab-id message-data frame-id)))

#_(reg-event-fx
   :common/send-tab-message-for-request-id
   (fn [{:keys [db]} [_event_id message-data request-id]]
     (let [{:keys [tab-id frame-id]} (get-in db [:reques-ids request-id])
           tab-id (if tab-id tab-id (current-tab-id db))
           frame-id (if frame-id frame-id (login-page-content-frame-id db))]
       (u/okp-println "In :common/send-tab-message-for-request-id" "tab-id" tab-id "frame-id"  frame-id "request-id"  request-id)
       {:fx [[:common/send-content-message-regfx {:message-data message-data
                                                  :tab-id tab-id
                                                  :frame-id frame-id}]]})))