(ns onekeepass.browser.content.iframe.events.content-iframe-messaging
  "
    All things related to send message from content script to iframe and to receive messages from iframe
    Content script  <-> Iframe
   "
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.message-type-names :refer [CLOSE_ENTRY_LIST_POPUP
                                                         CLOSE_POPUP
                                                         ENTRY_SELECTED
                                                         POPUP_ACTION
                                                         SHOW_ENTRY_LIST]]
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
   (let [message-data (select-keys db [:no-browser-enabled-db :no-matching-entries :no-matching-recent-url :background-error])
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