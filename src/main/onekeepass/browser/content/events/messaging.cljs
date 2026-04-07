(ns onekeepass.browser.content.events.messaging
  (:require
   [onekeepass.browser.common.message-type-names :refer [BACKGROUND_ERROR
                                                         CONTENT_FRAME_ID
                                                         CONTENT_LOGIN_FIELDS_IDENTIFIED
                                                         CONTENT_SCRIPT_LOADING
                                                         ENTRY_SELECTED
                                                         GET_ENTRY_LIST
                                                         IFRAME_POPUP_ACTION_INITIATE
                                                         NO_BROWSER_ENABLED_DB
                                                         NO_MATCHING_ENTRIES
                                                         NO_MATCHING_PASSKEYS
                                                         OKP_PASSKEY_BROWSER_DEFAULT
                                                         OKP_PASSKEY_CREATE
                                                         OKP_PASSKEY_GET
                                                         OKP_PASSKEY_REJECT
                                                         OKP_PASSKEY_RESOLVE_CREATE
                                                         OKP_PASSKEY_RESOLVE_GET
                                                         PASSKEY_CREATE_CANCELLED
                                                         PASSKEY_CREATE_CONFIRMED
                                                         PASSKEY_CREATE_FALLBACK
                                                         PASSKEY_GET_CANCELLED
                                                         PASSKEY_CREATE_SUCCESS
                                                         PASSKEY_DB_CHOSEN
                                                         PASSKEY_GROUP_CHOSEN
                                                         POPUP_ACTION
                                                         RECONNECT_APP
                                                         REDETECT_FIELDS
                                                         SELECTED_ENTRY_DETAIL
                                                         SHOW_ENTRY_LIST
                                                         SHOW_PASSKEY_CREATE_POPUP
                                                         SHOW_PASSKEY_ENTRIES
                                                         SHOW_PASSKEY_GROUPS
                                                         SHOW_PASSKEY_LIST
                                                         START_ASSOCIATION]]
   [onekeepass.browser.common.utils :as u :refer [okp-println]]
   [onekeepass.browser.content.events.common :as cmn-event]
   [re-frame.core :refer [dispatch reg-event-fx reg-fx reg-sub subscribe]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;  Handles incoming messages from background  ;;;;;;;;;;;;;;;;
(defn register-message-handlers
  "Called to register all callback functions of messages received by 
   the content script either from background or popup scripts
   The arg 'handlers' is a map where keys are message-type and values are callback functions
   "
  [handlers]

  ;; First we store these handlers in app-db for later use in various re-frame events
  (dispatch [:register-message-handlers handlers])

  ;; Need add this listener to receive the messages from backend or popup
  (js/chrome.runtime.onMessage.addListener
   (fn [message _sender _send-response-fn]
     #_(okp-console-log "Received RAW messsage" message "from sender" sender)
     (let [{:keys [message-type] :as message-data} (js->clj message :keywordize-keys true)]
       #_(okp-println "The message-data is " message-data)

       (condp = message-type

         ;; Background sends this message on receving matched entries
         ;; message-data a map with keys [message-type matched-entries]
         ;; The 'dispatch' event in turn calls the previously registered message handler
         SHOW_ENTRY_LIST
         (dispatch [:matched-entries-loaded message-data])

         ;; Background sends this when the WebAuthn get flow has matching passkeys to display
         SHOW_PASSKEY_LIST
         (dispatch [:passkey-list-loaded message-data])

         ;; Background sends this to show the passkey creation DB-picker popup
         SHOW_PASSKEY_CREATE_POPUP
         (dispatch [:received-passkey-create-data message-data])

         ;; Background sends group list after user picked a DB
         SHOW_PASSKEY_GROUPS
         (dispatch [:received-passkey-groups message-data])

         ;; Background sends entry list after user picked a group
         SHOW_PASSKEY_ENTRIES
         (dispatch [:received-passkey-entries message-data])

         ;; Background notifies that passkey was saved successfully
         PASSKEY_CREATE_SUCCESS
         (dispatch [:received-passkey-create-success])

         NO_BROWSER_ENABLED_DB
         (dispatch [:received-no-browser-enabled-db message-data])

         NO_MATCHING_ENTRIES
         (dispatch [:received-no-matching-entries message-data])

         NO_MATCHING_PASSKEYS
         (dispatch [:received-no-matching-passkeys message-data])

         SELECTED_ENTRY_DETAIL
         (dispatch [:received-seleted-entry-basic-info message-data])

         ;; Receives any error happened on the background script
         ;; The 'dispatch' event in turn calls the previously registered error handler
         BACKGROUND_ERROR
         (dispatch [:received-background-error message-data])

         ;; User clicked the redetect button in the popup message box
         REDETECT_FIELDS
         (dispatch [:received-redetect-fields-message message-data])
         #_(okp-println "Redetect fields handler is not ready")

         ;; Content script receives the frame id
         CONTENT_FRAME_ID
         (dispatch [:received-content-frame-id (:frame-id message-data)])

         ;; Typically background script sends this message when user clicks on the browser action
         ;; Also the background may send this message without user action based various conditions
         POPUP_ACTION
         (dispatch [:popup-action/message-received message-data])
         
         IFRAME_POPUP_ACTION_INITIATE
         (dispatch [:common/execute-message-handler IFRAME_POPUP_ACTION_INITIATE {}])

         ;; Firefox passkey relay: resolve or reject the pending Promise in the MAIN world
         ;; targetOrigin is window.location.origin (not "*") so only the current page
         ;; receives the message — prevents credential interception by cross-origin scripts.
         OKP_PASSKEY_RESOLVE_CREATE
         (let [{:keys [request-id credential-json]} message-data]
           (js/window.postMessage
            (clj->js {:type          "OKP_PASSKEY_RESOLVE_CREATE"
                      :requestId     request-id
                      :credentialJson credential-json})
            (.-origin js/location)))

         OKP_PASSKEY_RESOLVE_GET
         (let [{:keys [request-id credential-json]} message-data]
           (js/window.postMessage
            (clj->js {:type          "OKP_PASSKEY_RESOLVE_GET"
                      :requestId     request-id
                      :credentialJson credential-json})
            (.-origin js/location)))

         OKP_PASSKEY_REJECT
         (let [{:keys [request-id error-name error-message]} message-data]
           (js/window.postMessage
            (clj->js {:type      "OKP_PASSKEY_REJECT"
                      :requestId request-id
                      :name      error-name
                      :message   (or error-message "Operation not allowed")})
            (.-origin js/location)))

         OKP_PASSKEY_BROWSER_DEFAULT
         (let [{:keys [request-id options-json]} message-data]
           (js/window.postMessage
            (clj->js {:type        "OKP_PASSKEY_BROWSER_DEFAULT"
                      :requestId   request-id
                      :optionsJson options-json})
            (.-origin js/location)))

         ;; else
         (okp-println "Content script received unhandled message " message-data))))))

(defn register-firefox-passkey-relay
  "Firefox only — adds a window.message listener in the isolated world that
   picks up OKP_PASSKEY_CREATE / OKP_PASSKEY_GET messages posted by the
   passkey_intercept MAIN world script and relays them to the background."
  []
  (.addEventListener
   js/window "message"
   (fn [^js event]
     (when (and (= (.-source event) js/window)
                (= (.-origin event) (.-origin js/location)))
       (let [^js data (.-data event)
             type     (when data (.-type data))]
         (cond
           (= type OKP_PASSKEY_CREATE)
           (js/chrome.runtime.sendMessage
            (clj->js {:message-type OKP_PASSKEY_CREATE
                      :request-id   (.-requestId data)
                      :options-json (.-optionsJson data)
                      :origin       (.-origin data)}))

           (= type OKP_PASSKEY_GET)
           (js/chrome.runtime.sendMessage
            (clj->js {:message-type OKP_PASSKEY_GET
                      :request-id   (.-requestId data)
                      :options-json (.-optionsJson data)
                      :origin       (.-origin data)}))))))))


;;;;;;;;;;;;;;;;;;;;;;;;  Messages from content to background script ;;;;;;;;;;;;;;;;;;;;;;;

#_(defn send-input-icon-clicked
    "Called to send a message to the backend when user clicks the okp icon.
   The backend script in turn gets the entry list matching the page url
   "
    []
    (dispatch [:send-input-icon-clicked]))

(defn send-get-entry-list
  "Called to send a message to the backend when user focuse the login page inputs.
   The backend script in turn gets the entry list matching the page url
  "
  []
  (dispatch [:send-get-entry-list]))

(defn send-entry-selected [db-key entry-uuid]
  (dispatch [:send-entry-selected db-key entry-uuid])
  #_(js/chrome.runtime.sendMessage  (clj->js {:message-type ENTRY_SELECTED
                                              :db-key db-key
                                              :entry-uuid entry-uuid})))

(defn send-content-script-loading
  []
  #_(okp-println "send-content-script-loading is called ... ")
  (js/chrome.runtime.sendMessage  (clj->js {:message-type CONTENT_SCRIPT_LOADING})))

(defn send-login-fields-identified [fields-info]
  ;; fields-info is empty at this time
  (js/chrome.runtime.sendMessage  (clj->js {:message-type CONTENT_LOGIN_FIELDS_IDENTIFIED
                                            :fields-info fields-info})))

(defn send-redetect-fields 
  "Called from popup box. A message is sent to the background which in turn sends 
  the redetect message to an appropriate content script belonging to the current tab" 
  []
  (js/chrome.runtime.sendMessage  (clj->js {:message-type REDETECT_FIELDS})))

(defn send-reconnect-app []
  (okp-println "send-reconnect-app is called ... ")
  (js/chrome.runtime.sendMessage  (clj->js {:message-type RECONNECT_APP})))

(defn send-start-association []
  (okp-println "send-start-association is called ... ")
  (js/chrome.runtime.sendMessage  (clj->js {:message-type START_ASSOCIATION})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; content re-frame events   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hide-entry-list []
  (dispatch [:hide-entry-list]))

(defn background-error []
  (subscribe [:background-error]))

(defn no-browser-enabled-db []
  (subscribe [:no-browser-enabled-db]))

(defn no-matching-entries []
  (subscribe [:no-matching-entries]))

(defn no-matching-recent-url []
  (subscribe [:no-matching-recent-url]))

(defn matched-entries []
  (subscribe [:matched-entries]))

(defn entry-selection-done
  "Flag determines whether to show the popup entry list or not"
  []
  (subscribe [:entry-selection-done]))

(defn reset-background-error []
  (dispatch [:reset-background-error]))

;; The arg 'handlers' is a map where keys are message-type and values are callback functions
(reg-event-fx
 :register-message-handlers
 (fn [{:keys [db]} [_event-id handlers]]
   {:db (-> db (assoc :message-handlers handlers))}))

#_(reg-event-fx
   :send-input-icon-clicked
   (fn [{:keys [_db]} [_event-id]]
     {:fx [[:common/send-bg-message-regfx {:message-type INPUT_ICON_CLICKED}]]}))

(reg-event-fx
 :send-get-entry-list
 (fn [{:keys [_db]} [_event-id]]
   {:fx [[:common/send-bg-message-regfx {:message-type GET_ENTRY_LIST}]]}))

;; Sends a message to the background to get basic entry detail for the selected entry uuid
(reg-event-fx
 :send-entry-selected
 (fn [{:keys [db]} [_event-id db-key entry-uuid]]
   {:db (-> db (assoc-in [:entry-selected-done] true))
    :fx [[:common/send-bg-message-regfx {:message-type ENTRY_SELECTED
                                         :db-key db-key
                                         :entry-uuid entry-uuid}]]}))

;; Stores passkey-create-data in app-db and calls the registered SHOW_PASSKEY_CREATE_POPUP handler
(reg-event-fx
 :received-passkey-create-data
 (fn [{:keys [db]} [_event-id {:keys [message-type passkey-create-data]}]]
   {:db (-> db (assoc :passkey-create-data passkey-create-data))
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}))

(reg-event-fx
 :send-passkey-create-confirmed
 (fn [{:keys [_db]} [_event-id {:keys [db-key group-uuid new-group-name existing-entry-uuid new-entry-name]}]]
   {:fx [[:common/send-bg-message-regfx {:message-type        PASSKEY_CREATE_CONFIRMED
                                         :db-key              db-key
                                         :group-uuid          group-uuid
                                         :new-group-name      new-group-name
                                         :existing-entry-uuid existing-entry-uuid
                                         :new-entry-name      new-entry-name}]]}))

(reg-event-fx
 :send-passkey-create-cancelled
 (fn [{:keys [_db]} [_event-id]]
   {:fx [[:common/send-bg-message-regfx {:message-type PASSKEY_CREATE_CANCELLED}]]}))

(reg-event-fx
 :send-passkey-get-cancelled
 (fn [{:keys [_db]} [_event-id]]
   {:fx [[:common/send-bg-message-regfx {:message-type PASSKEY_GET_CANCELLED}]]}))

(reg-event-fx
 :send-passkey-create-fallback
 (fn [{:keys [_db]} [_event-id]]
   {:fx [[:common/send-bg-message-regfx {:message-type PASSKEY_CREATE_FALLBACK}]]}))

(reg-event-fx
 :send-passkey-db-chosen
 (fn [{:keys [_db]} [_event-id db-key]]
   {:fx [[:common/send-bg-message-regfx {:message-type PASSKEY_DB_CHOSEN
                                         :db-key       db-key}]]}))

(reg-event-fx
 :send-passkey-group-chosen
 (fn [{:keys [_db]} [_event-id group-uuid new-group-name]]
   {:fx [[:common/send-bg-message-regfx {:message-type   PASSKEY_GROUP_CHOSEN
                                         :group-uuid     group-uuid
                                         :new-group-name new-group-name}]]}))

;; Stores passkey groups in app-db and calls the registered SHOW_PASSKEY_GROUPS handler
(reg-event-fx
 :received-passkey-groups
 (fn [{:keys [db]} [_event-id {:keys [message-type groups]}]]
   {:db (assoc db :passkey-groups groups)
    :fx [[:execute-message-handler-regfx [(get-in db [:iframe-message-handlers message-type]) nil]]]}))

;; Stores passkey entries in app-db and calls the registered SHOW_PASSKEY_ENTRIES handler
(reg-event-fx
 :received-passkey-entries
 (fn [{:keys [db]} [_event-id {:keys [message-type entries]}]]
   {:db (assoc db :passkey-entries entries)
    :fx [[:execute-message-handler-regfx [(get-in db [:iframe-message-handlers message-type]) nil]]]}))

;; Calls the registered PASSKEY_CREATE_SUCCESS handler (forwards success notification to iframe)
(reg-event-fx
 :received-passkey-create-success
 (fn [{:keys [db]} [_event-id]]
   {:fx [[:execute-message-handler-regfx [(get-in db [:iframe-message-handlers PASSKEY_CREATE_SUCCESS]) nil]]]}))

#_(reg-event-fx
   :send-input-icon-clicked
   (fn [{:keys [db]} [_event-id]]
     (let [previous-no-matching-entries (get-in db [:no-matching-entries])]
       ;; Need to ensure that we do not popup again and again when there is no matching entries
       ;; We use this flag that was set in the last call
       (if-not previous-no-matching-entries
         {:fx [[:common/send-bg-message-regfx {:message-type INPUT_ICON_CLICKED}]]}
         {}))))

;; Called on receiving the message-type SHOW_ENTRY_LIST which in turn calls the callback fn
(reg-event-fx
 :matched-entries-loaded
 (fn [{:keys [db]} [_event-id {:keys [message-type matched-entries]}]]
   ;; matched-entries is a vec of map from vec of struct 'MatchedDbEntries'
   {:db (-> db
            (assoc-in [:entry-selected-done] false)
            (assoc-in [:matched-entries] matched-entries)
            (assoc-in [:no-browser-enabled-db] false)
            (assoc :no-matching-entries false)
            (assoc-in [:no-matching-recent-url] nil))
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}))

;; Called on receiving SHOW_PASSKEY_LIST from the background (WebAuthn get flow).
;; Stores passkey summaries under the shared :matched-entries key and calls the
;; registered SHOW_PASSKEY_LIST handler (create-passkey-list-popup).
(reg-event-fx
 :passkey-list-loaded
 (fn [{:keys [db]} [_event-id {:keys [message-type matched-entries]}]]
   {:db (-> db (assoc-in [:matched-entries] matched-entries))
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}))

;; Entry list mui box is hidden (shadow dom host removed)
(reg-event-fx
 :hide-entry-list
 (fn [{:keys [db]} [_event_id]]
   {:db (-> db (assoc-in [:entry-selected-done] true))}))

;; Called when message NO_BROWSER_ENABLED_DB from background is received
(reg-event-fx
 :received-no-browser-enabled-db
 (fn [{:keys [db]} [_event-id {:keys [message-type]}]]
   {:db (-> db (assoc :no-browser-enabled-db true)
            ;; Reset any previous flag of no matching to be sure we handle only no db error
            (assoc :no-matching-entries false)
            (assoc-in [:no-matching-recent-url] nil))
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}))

;; Called when message NO_MATCHING_ENTRIES is received from background
(reg-event-fx
 :received-no-matching-entries
 (fn [{:keys [db]} [_event-id {:keys [message-type url]}]]

   {:db (-> db (assoc :no-matching-entries true)
            (assoc-in [:no-matching-recent-url] url)
            (assoc-in [:no-browser-enabled-db] false))
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}

   #_(let [recent-url (get-in db [:no-matching-recent-url])]
       ;; We use no-matching-recent-url so that we do not call message popup
       ;; more than once if an entry matching this url is not found
       (if (= recent-url url)
         {}
         {:db (-> db (assoc :no-matching-entries true)
                  (assoc-in [:no-matching-recent-url] url)
                  (assoc-in [:no-browser-enabled-db] false))
          :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}))))

;; Called when NO_MATCHING_PASSKEYS is received from background (no saved passkeys for this site)
(reg-event-fx
 :received-no-matching-passkeys
 (fn [{:keys [db]} [_event-id {:keys [message-type]}]]
   {:db (assoc db :no-matching-passkeys true)
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) nil]]]}))

;; {:message-type SELECTED_ENTRY_DETAIL, :basic-entry-info {:username jim12@gmail.com, :password _7K$m{8/}}
(reg-event-fx
 :received-seleted-entry-basic-info
 (fn [{:keys [db]} [_event-id {:keys [message-type basic-entry-info]}]]
   {;; :db (-> db (assoc :seleted-entry-basic-info basic-entry-info))
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) basic-entry-info]]]}))

;; Not yet used

;; The content script calls the background script to do some action and 
;; this event is called when there is some error in the background script 
(reg-event-fx
 :received-background-error
 (fn [{:keys [db]} [_event-id  {:keys [message-type message connection-state association-id]}]]
   (okp-println "received-background-error message is " message)
   ;; TODO The error should be reset to nil after use's action on that error
   {:db (-> db
            (cmn-event/set-app-db-connection-state connection-state association-id)
            (assoc-in [:background-error] message))
    ;; Show error popup in the content script 
    :fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type])]]]}))

(reg-event-fx
 :reset-background-error
 (fn [{:keys [db]} [_event-id]]
   {:db (-> db (assoc-in [:background-error] nil))}))

;; (def content-frame-id-holding (atom nil))

(reg-event-fx
 :received-content-frame-id
 (fn [{:keys [db]} [_event_id frame-id]]
   #_(okp-println "Setting content-frame-id-holding " frame-id)
   ;;  (reset! content-frame-id-holding frame-id)
   {:db (assoc db :content-frame-id frame-id)}))

(reg-event-fx
 :received-redetect-fields-message
 (fn [{:keys [db]} [_event-id  {:keys [message-type  _connection-state]}]]
   {:fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type])]]]}))

;; Calls the previously registered handler(callback fns) for a message-type
(reg-fx
 :execute-message-handler-regfx
 (fn [[handler args]]
   #_(okp-console-log "Going to call handler" handler)

   ;; args is an optional map to pass any arguments to the handler
   ;; The structure of this map is specific to the handler
   (handler args)))

(reg-sub
 :no-browser-enabled-db
 (fn [db [_event-id]]
   (-> db (get :no-browser-enabled-db))))

(reg-sub
 :no-matching-entries
 (fn [db [_event-id]]
   (-> db (get :no-matching-entries))))

(reg-sub
 :no-matching-recent-url
 (fn [db [_event-id]]
   (-> db (get :no-matching-recent-url))))

(reg-sub
 :matched-entries
 (fn [db [_event-id]]
   (-> db (get-in [:matched-entries]))))

(reg-sub
 :background-error
 (fn [db [_event-id]]
   (-> db (get-in [:background-error]))))

(reg-sub
 :entry-selection-done
 (fn [db [_event-id]]
   (get-in db [:entry-selected-done])))