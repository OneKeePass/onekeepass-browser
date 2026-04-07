(ns onekeepass.browser.content.iframe.events.iframe-content-messaging
  "
  All things related to send message from iframe to content script and to receive messages from content scripts
  Iframe <-> Content
 "
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.message-type-names :refer [CLOSE_ENTRY_LIST_POPUP
                                                         CLOSE_POPUP
                                                         CLOSE_SETTINGS_POPUP
                                                         ENTRY_SELECTED
                                                         MSG_BOX_MESSAGE
                                                         PASSKEY_CREATE_CANCELLED
                                                         PASSKEY_CREATE_CONFIRMED
                                                         PASSKEY_CREATE_FALLBACK
                                                         PASSKEY_CREATE_SUCCESS
                                                         PASSKEY_DB_CHOSEN
                                                         PASSKEY_GROUP_CHOSEN
                                                         POPUP_ACTION
                                                         RECONNECT_APP
                                                         REDETECT_FIELDS
                                                         SHOW_ENTRY_LIST
                                                         SHOW_PASSKEY_CREATE_POPUP
                                                         SHOW_PASSKEY_ENTRIES
                                                         SHOW_PASSKEY_GROUPS
                                                         SHOW_SETTINGS_POPUP
                                                         START_ASSOCIATION]]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.iframe.events.iframe-content-connection :as iframe-content-conn]
   [re-frame.core :refer [dispatch reg-event-fx reg-sub subscribe]]))


(defn- content-message-handler
  "This is used in iframe to handle the message received from content scripts"
  [message]
  #_(u/okp-console-log "Content message receiced" message)
  #_(u/okp-println "Message converted" (js->clj (j/get-in message [:data]) :keywordize-keys true))
  #_(u/okp-println "Message converted" (js->clj message :keywordize-keys true) (js/Object.keys message) (js->clj (j/get-in message [:data]) :keywordize-keys true))

  ;; The simple js->clj works here
  (let [{:keys [message-type] :as message-data} (js->clj (j/get-in message [:data]) :keywordize-keys true)]
    (condp = message-type
      POPUP_ACTION
      (dispatch [:iframe-popup-action/message-received message-data])

      ;; All messages are sent from content script using this message type instead of NO_BROWSER_ENABLED_DB etc
      MSG_BOX_MESSAGE
      (dispatch [:iframe-msg-box/message-received message-data])

      SHOW_ENTRY_LIST
      (dispatch [:iframe-matched-entries-loaded message-data])

      SHOW_PASSKEY_CREATE_POPUP
      (dispatch [:iframe-passkey-create/message-received message-data])

      SHOW_PASSKEY_GROUPS
      (dispatch [:iframe-passkey-groups/message-received message-data])

      SHOW_PASSKEY_ENTRIES
      (dispatch [:iframe-passkey-entries/message-received message-data])

      PASSKEY_CREATE_SUCCESS
      (dispatch [:iframe-passkey-create/success])

      ;; Else part
      (u/okp-println "Unknown message from content script received" message-data))))


(defn init-content-connection 
  "Sets up a listener to receive a MessageChannel communication port from content side. 
  This port is used then for a bidirection communication"
  []
  #_(u/okp-println "Iframe <-> Content init is called")
  (iframe-content-conn/init-content-connection content-message-handler))

;;;;;;;;;;;;;;;;;;;; Messages to content ;;;;;;;;;;;;;;;;;;;;;;;

(defn close-popup
  "Called to close the message popup. A message is sent from iframe to content for this"
  []
  (iframe-content-conn/send-message-to-content {:message-type CLOSE_POPUP}))

(defn close-entry-list-popup
  "Called to close the entry list popup. A message is sent from iframe to content for this action"
  []
  (iframe-content-conn/send-message-to-content {:message-type CLOSE_ENTRY_LIST_POPUP}))

(defn send-entry-selected
  "A message is sent from iframe to content side whinc in turn handles
  the ENTRY_SELECTED message as done previously"
  [db-key entry-uuid]
  ;; This messsage flow is iframe -> content -> background -> content
  (iframe-content-conn/send-message-to-content {:message-type ENTRY_SELECTED
                                                :db-key db-key
                                                :entry-uuid entry-uuid}))

(defn send-passkey-create-confirmed
  "Called from passkey creation popup iframe when user clicks 'Save Passkey'.
   Sends all create params to the content script, which forwards them to the background."
  [{:keys [db-key group-uuid new-group-name existing-entry-uuid new-entry-name]}]
  (iframe-content-conn/send-message-to-content {:message-type        PASSKEY_CREATE_CONFIRMED
                                                :db-key              db-key
                                                :group-uuid          group-uuid
                                                :new-group-name      new-group-name
                                                :existing-entry-uuid existing-entry-uuid
                                                :new-entry-name      new-entry-name}))

(defn send-passkey-create-cancelled
  "Called from passkey creation popup iframe when user clicks 'Cancel' or auto-closes."
  []
  (iframe-content-conn/send-message-to-content {:message-type PASSKEY_CREATE_CANCELLED}))

(defn send-passkey-create-fallback
  "Called when user chooses 'Use Browser Default' — detaches OKP proxy and rejects current request."
  []
  (iframe-content-conn/send-message-to-content {:message-type PASSKEY_CREATE_FALLBACK}))

(defn close-settings-popup
  "Called from the settings popup iframe to close itself."
  []
  (iframe-content-conn/send-message-to-content {:message-type CLOSE_SETTINGS_POPUP}))

(defn send-show-settings-popup
  "Called from the main popup footer gear icon to open the settings popup."
  []
  (iframe-content-conn/send-message-to-content {:message-type SHOW_SETTINGS_POPUP}))

(defn send-passkey-db-chosen
  "Called when user picks a database in step 1."
  [db-key]
  (iframe-content-conn/send-message-to-content {:message-type PASSKEY_DB_CHOSEN
                                                :db-key       db-key}))

(defn send-passkey-group-chosen
  "Called when user picks (or names) a group in step 2.
   group-uuid is nil when creating a new group."
  [group-uuid new-group-name]
  (iframe-content-conn/send-message-to-content {:message-type   PASSKEY_GROUP_CHOSEN
                                                :group-uuid     group-uuid
                                                :new-group-name new-group-name}))


;;;;;;;;;;; Messages to background ;;;;;;;;;;;;;;;;;;;;;;

(defn send-bg-start-association []
  (u/okp-println "send-to-bg-start-association is called ... ")
  (js/chrome.runtime.sendMessage  (clj->js {:message-type START_ASSOCIATION})))

(defn send-bg-redetect-fields
  "Called from popup box. A message is sent to the background which in turn sends 
  the redetect message to an appropriate content script belonging to the current tab"
  []
  (js/chrome.runtime.sendMessage  (clj->js {:message-type REDETECT_FIELDS})))

(defn send-bg-reconnect-app []
  (u/okp-println "send-reconnect-app is called ... ")
  (js/chrome.runtime.sendMessage  (clj->js {:message-type RECONNECT_APP})))

;;;;;;;;;;;;;;;;;;;;;;;;;;; re-frame events to store and use the data from content script ;;;;;;;;

(defn popup-action-info
  "Provides the popup action data on receiving message from content"
  []
  (subscribe [:iframe-popup-action-info]))

(defn no-browser-enabled-db []
  (subscribe [:iframe-no-browser-enabled-db]))

(defn background-error []
  (subscribe [:iframe-background-error]))

(defn no-matching-entries []
  (subscribe [:iframe-no-matching-entries]))

(defn no-matching-passkeys []
  (subscribe [:iframe-no-matching-passkeys]))

(defn no-matching-recent-url []
  (subscribe [:iframe-no-matching-recent-url]))

(defn matched-entries []
  (subscribe [:iframe-matched-entries]))

;; Whenever we receive the popup action message POPUP_ACTION from background -> content -> iframe, we store the connection-state and popup ui shown accordingly
(reg-event-fx
 :iframe-popup-action/message-received
 (fn [{:keys [db]} [_event_id {:keys [connection-state association-id association-rejected]}]]
   #_(u/okp-println "The connection-state is " connection-state " association-id " association-id)
   #_(let [existing-connection-state (get-in db [:connection-state])]
       (okp-println "existing-connection-state is " existing-connection-state))

   {:db  (-> db
             (assoc-in [:iframe :connection-state] connection-state)
             (assoc-in [:iframe :association-id] association-id)
             (assoc-in [:iframe :association-rejected] association-rejected))

    ;; Execute the message handler registered for this message-type CONNECTION_STATE_INFO
    :fx [#_[:dispatch [:iframe/execute-message-handler message-type {}]]]}))

#_(reg-event-fx
   :iframe-no-browser-enabled-db/message-received
   (fn [{:keys [db]} [_event_id {:keys [no-browser-enabled-db
                                        no-matching-recent-url
                                        no-matching-entries
                                        background-error]}]]
     {:db (-> db
              (assoc-in [:iframe :no-browser-enabled-db] no-browser-enabled-db)
              (assoc-in [:iframe :no-matching-entries] no-matching-entries)
              (assoc-in [:iframe :no-matching-recent-url] no-matching-recent-url)
              (assoc-in [:iframe :background-error] background-error))}))

(reg-event-fx
 :iframe-msg-box/message-received
 (fn [{:keys [db]} [_event_id {:keys [no-browser-enabled-db
                                      no-matching-recent-url
                                      no-matching-entries
                                      background-error
                                      no-matching-passkeys] :as _messagae-data}]]
   {:db (-> db
            (assoc-in [:iframe :no-browser-enabled-db] no-browser-enabled-db)
            (assoc-in [:iframe :no-matching-entries] no-matching-entries)
            (assoc-in [:iframe :no-matching-recent-url] no-matching-recent-url)
            (assoc-in [:iframe :background-error] background-error)
            (assoc-in [:iframe :no-matching-passkeys] no-matching-passkeys))}))

(reg-event-fx
 :iframe-matched-entries-loaded
 (fn [{:keys [db]} [_event_id {:keys [matched-entries]}]]
   {:db (-> db (assoc-in [:iframe :matched-entries] matched-entries))}))

(reg-sub
 :iframe-matched-entries
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :matched-entries]))))

(reg-sub
 :iframe-no-browser-enabled-db
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :no-browser-enabled-db]))))

(reg-sub
 :iframe-no-matching-entries
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :no-matching-entries]))))

(reg-sub
 :iframe-no-matching-recent-url
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :no-matching-recent-url]))))

(reg-sub
 :iframe-background-error
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :background-error]))))

(reg-sub
 :iframe-no-matching-passkeys
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :no-matching-passkeys]))))

(reg-sub
 :iframe-popup-action-info
 (fn [db]
   {:connection-state (get-in db [:iframe :connection-state])
    :association-id (get-in db [:iframe :association-id])
    :association-rejected (get-in db [:iframe :association-rejected])}))

(reg-event-fx
 :iframe-passkey-create/message-received
 (fn [{:keys [db]} [_event-id {:keys [passkey-create-data]}]]
   {:db (-> db (assoc-in [:iframe :passkey-create-data] passkey-create-data))}))

(defn passkey-create-data []
  (subscribe [:iframe-passkey-create-data]))

(reg-sub
 :iframe-passkey-create-data
 (fn [db [_event-id]]
   (-> db (get-in [:iframe :passkey-create-data]))))

(reg-event-fx
 :iframe-passkey-groups/message-received
 (fn [{:keys [db]} [_event-id {:keys [passkey-groups]}]]
   {:db (assoc-in db [:iframe :passkey-groups] passkey-groups)}))

(reg-sub
 :iframe-passkey-groups
 (fn [db [_event-id]]
   (get-in db [:iframe :passkey-groups])))

(defn passkey-groups []
  (subscribe [:iframe-passkey-groups]))

(reg-event-fx
 :iframe-passkey-entries/message-received
 (fn [{:keys [db]} [_event-id {:keys [passkey-entries]}]]
   {:db (assoc-in db [:iframe :passkey-entries] passkey-entries)}))

(reg-sub
 :iframe-passkey-entries
 (fn [db [_event-id]]
   (get-in db [:iframe :passkey-entries])))

(defn passkey-entries []
  (subscribe [:iframe-passkey-entries]))

(reg-event-fx
 :iframe-passkey-create/success
 (fn [{:keys [db]} [_event-id]]
   {:db (assoc-in db [:iframe :passkey-create-success] true)}))

(reg-sub
 :iframe-passkey-create-success
 (fn [db [_event-id]]
   (get-in db [:iframe :passkey-create-success])))

(defn passkey-create-success? []
  (subscribe [:iframe-passkey-create-success]))

