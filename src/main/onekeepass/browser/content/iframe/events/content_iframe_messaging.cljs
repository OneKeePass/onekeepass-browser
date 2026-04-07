(ns onekeepass.browser.content.iframe.events.content-iframe-messaging
  "
    All things related to send message from content script to iframe and to receive messages from iframe
    Content script  <-> Iframe
   "
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.message-type-names :refer [CLOSE_ENTRY_LIST_POPUP
                                                         CLOSE_PASSKEY_CREATE_POPUP
                                                         CLOSE_POPUP
                                                         CLOSE_SETTINGS_POPUP
                                                         ENTRY_SELECTED
                                                         PASSKEY_CREATE_CANCELLED
                                                         PASSKEY_CREATE_CONFIRMED
                                                         PASSKEY_CREATE_FALLBACK
                                                         PASSKEY_CREATE_SUCCESS
                                                         PASSKEY_DB_CHOSEN
                                                         PASSKEY_GROUP_CHOSEN
                                                         POPUP_ACTION
                                                         SHOW_ENTRY_LIST
                                                         SHOW_PASSKEY_CREATE_POPUP
                                                         SHOW_PASSKEY_ENTRIES
                                                         SHOW_PASSKEY_GROUPS
                                                         SHOW_SETTINGS_POPUP]]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.iframe.events.content-iframe-connection :as content-iframe-conn]
   [re-frame.core :refer [dispatch reg-event-fx reg-fx]]))

(defn register-iframe-message-handlers
  "Called to registers all handler fns that is called when a message is received from iframe
   The arg 'handlers' is a map where keys are the message types and values are the handler fns
   "
  [handlers]
  (dispatch [:register-iframe-message-handlers handlers]))

(reg-event-fx
 :register-iframe-message-handlers
 (fn [{:keys [db]} [_event-id handlers]]
   {:db (-> db (assoc :iframe-message-handlers handlers))}))

(reg-event-fx
 :close-popup
 (fn [{:keys [db]} [_event-id {:keys [message-type]}]]
   {:fx [[:execute-iframe-message-handler-regfx [(get-in db [:iframe-message-handlers message-type])]]]}))

(reg-event-fx
 :execute-iframe-message-type
 (fn [{:keys [db]} [_event-id {:keys [message-type]}]]
   {:fx [[:execute-iframe-message-handler-regfx [(get-in db [:iframe-message-handlers message-type])]]]}))

(reg-fx
 :execute-iframe-message-handler-regfx
 (fn [[handler args]]
   #_(okp-console-log "Going to call handler" handler)

   ;; args is an optional map to pass any arguments to the handler
   ;; The structure of this map is specific to the handler
   (handler args)))


;;;;;;;;;;;;;;


;; https://stackoverflow.com/questions/32467299/clojurescript-convert-arbitrary-javascript-object-to-clojure-script-map

;; https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/runtime/onMessage#sendresponse
;; https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm

(defn- convert-message [message]
  ;; In firefox the (js->clj response :keywordize-keys true) gives #object[Object [object Object]] instead of a cljs map
  ;; This may be something to do with firefox's structured cloning alogrithm(?)
  ;; The following way of conversion works
  (try
    (let [msg (js->clj (-> (j/get message :data) js/JSON.stringify js/JSON.parse) :keywordize-keys true)]
      #_(u/okp-println "Converted message" msg)
      msg)
    (catch :default e
      (u/okp-console-log "Error while converting message from iframe in content script" e))))

(defn- iframe-message-receiver
  "Handles the message received in content script from iframe "
  [message]

  (let [{:keys [message-type] :as message-data} (convert-message message)]
    #_(u/okp-println "Message from iframe" message-data)
    (condp = message-type

      CLOSE_POPUP
      (dispatch [:close-popup message-data])
      
      CLOSE_ENTRY_LIST_POPUP
      (dispatch [:execute-iframe-message-type message-data])
      
      ENTRY_SELECTED
      (dispatch [:send-entry-selected (:db-key message-data) (:entry-uuid message-data)])

      ;; Step 1: user picked a DB — relay to background to fetch groups
      PASSKEY_DB_CHOSEN
      (dispatch [:send-passkey-db-chosen (:db-key message-data)])

      ;; Step 2: user picked/named a group — relay to background to fetch entries
      PASSKEY_GROUP_CHOSEN
      (dispatch [:send-passkey-group-chosen (:group-uuid message-data) (:new-group-name message-data)])

      ;; Step 3: user confirmed — relay full create params to background
      PASSKEY_CREATE_CONFIRMED
      (dispatch [:send-passkey-create-confirmed (select-keys message-data [:db-key :group-uuid :new-group-name
                                                                           :existing-entry-uuid :new-entry-name])])

      PASSKEY_CREATE_CANCELLED
      (do
        (dispatch [:execute-iframe-message-type {:message-type CLOSE_PASSKEY_CREATE_POPUP}])
        (dispatch [:send-passkey-create-cancelled]))

      PASSKEY_CREATE_FALLBACK
      (do
        (dispatch [:execute-iframe-message-type {:message-type CLOSE_PASSKEY_CREATE_POPUP}])
        (dispatch [:send-passkey-create-fallback]))

      ;; Main popup requests settings popup to open
      SHOW_SETTINGS_POPUP
      (dispatch [:execute-iframe-message-type message-data])

      ;; Settings popup requests itself to close
      CLOSE_SETTINGS_POPUP
      (dispatch [:execute-iframe-message-type message-data])

      ;;Else
      (u/okp-console-log "Unknown messsage received from iframe"))))


(defn init-content-iframe-message-channel 
  "Called in content script side to establish a bidirectional communication channel between the content and iframe"
  [iframe-element]
  (content-iframe-conn/setup-message-channel iframe-element iframe-message-receiver))

;;;;;;;;;;;;;;;;;;

(defn send-popup-action-message
  "Called to resend the popup action message from content script to iframe after its launch"
  []
  (dispatch [:content-iframe/popup-action-message]))

#_(defn send-no-browser-enabled-db-message
    "Called to resend no db available message from content script to iframe after its launch"
    []
    (dispatch [:content-iframe/received-no-browser-enabled-db]))

(defn send-mg-box-message
  "Called to send the all messages from content script to iframe after the launch of the msg box iframe"
  []
  (dispatch [:content-iframe/received-msg-box-message]))

(defn send-matched-entries-loaded-message
  "Called to send the matched entries data from content script to iframe after the launch of the 'entry_list_popup.html' iframe"
  []
  (dispatch [:content-iframe/matched-entries-loaded]))

(defn send-passkey-create-data-message
  "Called to send databases + rp-name to the passkey creation popup iframe after launch"
  []
  (dispatch [:content-iframe/passkey-create-data]))

(defn send-passkey-groups-message
  "Called to forward the group list (stored as :passkey-groups in content db) to the iframe"
  []
  (dispatch [:content-iframe/passkey-groups]))

(defn send-passkey-entries-message
  "Called to forward the entry list (stored as :passkey-entries in content db) to the iframe"
  []
  (dispatch [:content-iframe/passkey-entries]))

(defn send-passkey-create-success-message
  "Called to notify the iframe that the passkey was saved successfully"
  []
  (dispatch [:content-iframe/passkey-create-success]))

(reg-event-fx
 :content-iframe/popup-action-message
 (fn [{:keys [db]} [_event_id]]
   (let [message-data  (select-keys db [:connection-state :association-id :association-rejected])
         message-data (merge {:message-type POPUP_ACTION} message-data)]
     {:fx [[:common/send-iframe-message-regfx message-data]]})))

#_(reg-event-fx
   :content-iframe/received-no-browser-enabled-db
   (fn [{:keys [db]} [_event-id]]
     (let [message-data (select-keys db [:no-browser-enabled-db :no-matching-entries :no-matching-recent-url :background-error])
           message-data (merge {:message-type NO_BROWSER_ENABLED_DB} message-data)]
       {:fx [[:common/send-iframe-message-regfx message-data]]})))

;; "MSG_BOX_MESSAGE"
(reg-event-fx
 :content-iframe/received-msg-box-message
 (fn [{:keys [db]} [_event-id]]
   (let [message-data (select-keys db [:no-browser-enabled-db :no-matching-entries :no-matching-recent-url
                                       :background-error :no-matching-passkeys])
         message-data (merge {:message-type "MSG_BOX_MESSAGE"} message-data)]
     {:fx [[:common/send-iframe-message-regfx message-data]]})))

;; SHOW_ENTRY_LIST from bg -> content -> iframe
(reg-event-fx
 :content-iframe/matched-entries-loaded
 (fn [{:keys [db]} [_event-id]]
   (let [message-data (select-keys db [:matched-entries])
         message-data (merge {:message-type SHOW_ENTRY_LIST} message-data)]
     #_(u/okp-println "Sending entries data of SHOW_ENTRY_LIST" message-data "to iframe")
     {:fx [[:common/send-iframe-message-regfx message-data]]})))

;; SHOW_PASSKEY_CREATE_POPUP from bg -> content -> iframe
(reg-event-fx
 :content-iframe/passkey-create-data
 (fn [{:keys [db]} [_event-id]]
   (let [message-data (select-keys db [:passkey-create-data])
         message-data (merge {:message-type SHOW_PASSKEY_CREATE_POPUP} message-data)]
     {:fx [[:common/send-iframe-message-regfx message-data]]})))

;; SHOW_PASSKEY_GROUPS from bg -> content -> iframe
(reg-event-fx
 :content-iframe/passkey-groups
 (fn [{:keys [db]} [_event-id]]
   (let [message-data (merge {:message-type SHOW_PASSKEY_GROUPS} (select-keys db [:passkey-groups]))]
     {:fx [[:common/send-iframe-message-regfx message-data]]})))

;; SHOW_PASSKEY_ENTRIES from bg -> content -> iframe
(reg-event-fx
 :content-iframe/passkey-entries
 (fn [{:keys [db]} [_event-id]]
   (let [message-data (merge {:message-type SHOW_PASSKEY_ENTRIES} (select-keys db [:passkey-entries]))]
     {:fx [[:common/send-iframe-message-regfx message-data]]})))

;; PASSKEY_CREATE_SUCCESS from bg -> content -> iframe
(reg-event-fx
 :content-iframe/passkey-create-success
 (fn [{:keys [_db]} [_event-id]]
   {:fx [[:common/send-iframe-message-regfx {:message-type PASSKEY_CREATE_SUCCESS}]]}))


#_(reg-event-fx
   :common/send-iframe-message
   (fn [{:keys [db]} [_event_id message-data]]
     {:fx [[:common/send-iframe-message-regfx message-data]]}))

(reg-fx
 :common/send-iframe-message-regfx
 (fn [message-data]
   #_(u/okp-println "Sending message frame to message:" message-data)
   #_(u/okp-println "4 Iframp popup: Sending message frame to message in regfx")
   ;; message-data is map with key [message-type]
   (content-iframe-conn/send-message-to-iframe message-data)))


#_(reg-fx
   :execute-message-handler-regfx
   (fn [[handler args]]
     #_(okp-console-log "Going to call handler" handler)

     ;; args is an optional map to pass any arguments to the handler
     ;; The structure of this map is specific to the handler
     (handler args)))