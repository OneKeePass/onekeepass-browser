(ns onekeepass.browser.background.events.messaging
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.background.crypto :as cryto]
   [onekeepass.browser.background.events.common :as bg-cmn-event :refer [ASSOCIATE
                                                                         DISCONNECT
                                                                         ENABLED_DATABASE_MATCHED_ENTRY_LIST
                                                                         INIT_SESSION_KEY
                                                                         NATIVE_APP_ID
                                                                         NATIVE_APP_ID_DEV
                                                                         PROXY_APP_CONNECTION
                                                                         PROXY_ERROR
                                                                         SELECTED_ENTRY]]
   [onekeepass.browser.common.connection-states :refer [APP_CONNECTED
                                                        APP_DISCONNECTED
                                                        NATIVE_APP_NOT_AVAILABLE
                                                        PROXY_TO_APP_CONNECTED
                                                        PROXY_TO_APP_NOT_CONNECTED]]
   [onekeepass.browser.common.message-type-names :refer [CONTENT_LOGIN_FIELDS_IDENTIFIED
                                                         CONTENT_SCRIPT_LOADING
                                                         ENTRY_SELECTED
                                                         GET_ENTRY_LIST
                                                         LAUNCHING_POPUP
                                                         RECONNECT_APP
                                                         REDETECT_FIELDS
                                                         START_ASSOCIATION]]
   [onekeepass.browser.common.utils :as u :refer [get-browser-name
                                                  is-firefox-browser?
                                                  okp-console-log]]
   [re-frame.core :refer [dispatch reg-event-fx reg-fx reg-sub]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; All re-frame events related to native messaging interactions ;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-app-port [db]
  (get-in db [:app-port]))

(defn start-app-session
  "Called onetime when background script is intialized"
  []
  (dispatch [:start-app-session]))

#_(defn find-matched-entry-summaries
    "Gets all matching entry summaries from all databases for the given url"
    [form-url]
    (dispatch [:find-matching-entries form-url])
    #_(dispatch [:send-app-request {:action ENABLED_DATABASE_MATCHED_ENTRY_LIST
                                    :form-url form-url} true]))

#_(defn app-port-not-connected? []
    (not (boolean @(subscribe [:app-port]))))

(declare initiate-native-app-connection)

;; Called onetime when background script is intialized 'app-port' will be set after this call
(reg-event-fx
 :start-app-session
 (fn [{:keys [db]} [_event-id]]
   (let [app-port (get-app-port db)]
     ;; app-port is not nil, should we close and reinitiate the connection?
     ;;  (when (nil? app-port)
     ;;    (initiate-native-app-connection))
     (if (nil? app-port)
       {:fx [[:initiate-session-regfx]]}
       {}))))

;; The app-port is stored for later use
(reg-event-fx
 :app-connected
 (fn [{:keys [db]} [_event-id app-port]]
   ;;  (println "App is connected and the app port is " app-port)
   {:db (-> db (assoc :app-port app-port)
            (assoc-in [:app-connection :state] APP_CONNECTED))}))

;; Called from runtime app port disconnected event
#_(reg-event-fx
   :app-disconnected
   (fn [{:keys [db]} [_event-id _error]]
     ;; TODO: Reset crypto session in ns 'onekeepass.browser.background.crypto' ?
     ;;  (println "The app-disconnected error.. " error "(get-browser-name) " (get-browser-name))
     {:db (-> db
              (assoc :app-port nil)
              (assoc :association-id nil)
              (assoc-in [:app-connection :state] APP_DISCONNECTED))}))

;; Called from runtime app port disconnected event
(reg-event-fx
 :app-disconnected
 (fn [{:keys [db]} [_event-id error]]
   (let [cs (get-in db [:app-connection :state])]
     ;; TODO: Reset crypto session in ns 'onekeepass.browser.background.crypto' ?
     ;;  (println "The app-disconnected error.. " error "(get-browser-name) " (get-browser-name))


     (if (and (nil? error) (= cs NATIVE_APP_NOT_AVAILABLE))
       ;; DISCONNECT is sent from the Main App when the browser extension is connected and user disables the 
       ;; a browser in 'Browser Integration' setting of the Main App

       ;; As DISCONNECT action is called before the extension onDisconnect, this would have set 
       ;; NATIVE_APP_NOT_AVAILABLE before ':app-disconnected' call. In that case, we leave state in 'NATIVE_APP_NOT_AVAILABLE'
       {}
       ;; All other cases
       {:db (-> db
                (assoc :app-port nil)
                (assoc :association-id nil)
                (assoc-in [:app-connection :state] APP_DISCONNECTED))}))))

(reg-event-fx
 :native-app-not-available
 (fn [{:keys [db]} [_event-id]]
   #_(u/okp-println "Setting state as NATIVE_APP_NOT_AVAILABLE")
   {:db (-> db
            (assoc :app-port nil)
            (assoc :association-id nil)
            (assoc-in [:app-connection :state] NATIVE_APP_NOT_AVAILABLE))}))

#_(reg-event-fx
   :browser-connection-diabled
   (fn [{:keys [db]} [_event-id]]
     (let [ap (get db :app-port)]
       (.disconnect ap)
       {:db (-> db
                (assoc :app-port nil)
                (assoc :association-id nil)
                (assoc-in [:app-connection :state] NATIVE_APP_NOT_AVAILABLE))})))

(defn- form-url-to-match [tab-url]
  (let [u (js/URL.  tab-url)
        port (.-port u)
        port (if (str/blank? port) nil (str ":" port))]
    (str/join "" [(.-protocol u) "//" (.-host u) port (.-pathname u)])))

(reg-event-fx
 :get-entry-list
 (fn [{:keys [db]} [_event-id {:keys [tab-id frame-id tab-url _frame-url] :as _sender-info}]]
   #_(u/okp-println "Form url formed is" (form-url-to-match tab-url))
   #_(u/okp-println "get-entry-list form-url " form-url " tab-id " tab-id " frame-id " frame-id)
   (let [request-id (bg-cmn-event/generate-request-id)
         association-id (get db :association-id)]
     (if (nil? association-id)
       {:fx [[:dispatch [:send-assoc-message]]]}
       ;; Need to remove request-id stored after use
       {:db (-> db (assoc-in [:reques-ids request-id] {:tab-id tab-id :frame-id frame-id}))
        :fx [[:dispatch [:send-app-request {:action ENABLED_DATABASE_MATCHED_ENTRY_LIST
                                            :request-id request-id
                                            ;; The form-url from tab-url instead of frame-url is used by the app to match the entries
                                            :form-url (form-url-to-match tab-url) #_frame-url} true]]]}))))

(reg-event-fx
 :load-selected-entry-detail
 (fn [{:keys [db]} [_event-id {:keys [tab-id frame-id]} {:keys [db-key entry-uuid]}]]
   (let [request-id (bg-cmn-event/generate-request-id)]
     {:db (-> db (assoc-in [:reques-ids request-id] {:tab-id tab-id :frame-id frame-id}))
      :fx [[:dispatch [:send-app-request {:action SELECTED_ENTRY
                                          :request-id request-id
                                          :db-key db-key
                                          :entry-uuid entry-uuid} true]]]})))

;; Sends a request to the proxy app through the app-port by native messaging
(reg-event-fx
 :send-app-request
 (fn [{:keys [db]} [_event-id request include-association-id]]
   ;; Ensure that we have a valid "app-port" to the native messaging proxy app before sending any request
   (let [app-port (get-in db [:app-port])]
     (when (nil? app-port)
       (js/console.error "App port is nil"))
     (if (nil? app-port)
       {:fx [[:dispatch [:popup/app-is-not-connected]]
             #_[:dispatch [:common/send-error-message-to-current-tab {:error-message "App port is not connected"
                                                                      :connection-state connection-state}]]

             #_[:dispatch  [:common/send-message-to-current-tab  {:message-type BACKGROUND_ERROR
                                                                  :message "App port is NOT connected"
                                                                  :connection-state connection-state}]]]}
       ;; Add association-id if required to each request map data
       (let [req (if include-association-id
                   (assoc request :association-id (get db :association-id))
                   request)]
         {:fx [[:post-message-regfx [(get-app-port db) req]]]})))))

;; Called onetime when background script is intialized  
(reg-fx
 :initiate-session-regfx
 (fn []
   (initiate-native-app-connection)))

;; Calls the proxy app to send a payload to the proxy app and then to the main app
(reg-fx
 :post-message-regfx
 (fn [[app-port payload]]
   #_(u/okp-println "Payload before conversion  " payload)
   ;; (okp-console-log "Sending native message for action " (:action payload))
   ;; TODO: Encrypt 'message' field if required. Should it be done here or in :send-app-request?
   (let [payload (bg-cmn-event/request->json payload)]
     ;;(u/okp-println "The converted payload json that will be send to proxy is" payload)
     (if-not (nil? app-port)
       (.postMessage app-port payload)
       (okp-console-log "There is no connection to the native app")))))

;; Indicates that Proxy to app is fine and state is set to PROXY_TO_APP_CONNECTED accordingly
(reg-event-fx
 :proxy-to-app-connection-ok
 (fn [{:keys [db]} [_event-id]]
   ;; The association-id is set to nil here. It will be set when we get the ASSOCIATE ok response from the app
   {:db (-> db (assoc-in [:app-connection :state] PROXY_TO_APP_CONNECTED)
            (assoc :association-id nil))}))

;; Called to send 'ASSOCIATE' message to the main app
(reg-event-fx
 :send-assoc-message
 (fn [{:keys [db]} [_event-id]]
   (let [connection-state (get-in db [:app-connection :state])]
     (if (= connection-state PROXY_TO_APP_CONNECTED)
       ;; We send this message only if the native app is connected
       {:fx [[:dispatch [:send-app-request {:action ASSOCIATE :client-id (get-browser-name)}]]]}
       {:fx [[:dispatch [:popup/app-is-not-connected]]]}))))

;; Proxy returns an ok association message
(reg-event-fx
 :on-associate-ok-response
 (fn [{:keys [db]} [_event-id {:keys [association-id app-version]}]]
   (u/okp-println "Association reply returns app-version" app-version)
   (let [next-msg {:action INIT_SESSION_KEY
                   :association-id  association-id
                   :client-session-pub-key (cryto/encoded-client-public-key)}]
     {:db (assoc db :association-id association-id)
      ;; Next we send the init session key message to the app as user has allowed the association
      :fx [[:post-message-regfx [(get-app-port db) next-msg]]]})))

;; Called when we receive the app side pub key when the session is started
(reg-event-fx
 :on-init-session-ok-response
 (fn [{:keys [_db]} [_event-id {:keys [app-session-pub-key _nonce _test-message]}]]
   ;; TODO: Store session key in app-db ?
   (cryto/store-session-app-public-key app-session-pub-key)
   {}))

;; Called when app returns a list of all matched entries by database
;; The key "message-content" in passed org ok-resp should have a valid cljs data 

(reg-event-fx
 :on-entry-list-ok-response
 (fn [{:keys [_db]} [_event-id {:keys [message-content request-id]}]]
   ;; message-content is a map from struct AllMatchedEntries
   {:fx [[:dispatch [:content/entry-list-loaded message-content request-id]]]}))

;; Called when an error is received from proxy app itself
;; TODO: Need to handle various error cases that can be received from the main app too
(reg-event-fx
 :on-app-response-error
 (fn [{:keys [db]} [_event-id {:keys [action error-message] :as error-resp}]]
   (u/okp-println "Received error response" error-resp)
   (cond
     (= action PROXY_APP_CONNECTION)
     {:db (-> db (assoc-in [:app-connection :state] PROXY_TO_APP_NOT_CONNECTED))
      :fx [[:dispatch  [:common/send-error-message-to-current-tab {:error-message error-message} true]]]}

     (and  (= action ASSOCIATE)
           (= error-message "ASSOCIATION_REJECTED"))
     {:db (-> db (assoc :association-id nil))
      ;; Show error popup in the content script 
      :fx [[:dispatch  [:popup/association-rejected]]]}

     (and  (= action ASSOCIATE)
           (= error-message "BROWSER_NOT_SUPPORTED"))
     {:db (-> db (assoc :association-id nil))
      ;; Show error error popup in the content script 
      :fx [[:dispatch  [:common/send-error-message-to-current-tab {:error-message "Browser is not supported by OneKeePass App"}]]]}

     ;; This may never be sent to the content script side as when the proxy app after sending this error, it closes the connection
     ;; This results in app-ports onDisconnect event getting called and handled in :app-disconnected event handler
     ;; Should we just log this error  and not to send to content script ?
     (= action PROXY_ERROR)
     (dispatch [:common/send-error-message-to-current-tab {:error-message error-message}])

     :else
     (do
       (u/okp-println "Unhandled error" error-resp)
       {}))))

(reg-sub
 :app-port
 (fn [db _query-vec]
   (get db :app-port)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Native Messaging ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;  All extension <-> Poxy interactions using native messaging ;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-proxy-ok-response
  "Called with ok part of proxy response"
  [{:keys [action nonce message-content _request-id] :as ok-resp}]
  ;; The app response is expected to have a field "message" which is typically a encrypted json str
  ;; TODO: Is there any time non json string is passed in "message"
  (let [message-content (if (and  message-content nonce)
                          (bg-cmn-event/parse-json-str->map (cryto/decrypt message-content nonce))
                          message-content)
        ;; expected that message-content is a cljs map
        ok-resp (if-not (nil? message-content)
                  (assoc ok-resp :message-content message-content)
                  ok-resp)]
    #_(println "The ok-resp (after decryption) is " ok-resp)

    ;; here 'action' is a reponse 'action' expected to be non nil and one of the defined action constants 
    ;; defined in extenstion side and in main app side
    (condp = action

      ;; Proxy app connection response 
      ;; This is the first response we get from the proxy app when the connection is established
      ;; This indicates that the connection to proxy app and also the connection from proxy app to main app is fine
      ;; An error response will be handled in :on-app-response-error event handler and means that the connection to main app is not fine
      PROXY_APP_CONNECTION
      (dispatch [:proxy-to-app-connection-ok])

      ;; The app has accepted the association request and returned an association id in response
      ASSOCIATE
      (dispatch [:on-associate-ok-response ok-resp])

      ;; The app session is now established by creation of session keys on both sides
      INIT_SESSION_KEY
      (dispatch [:on-init-session-ok-response ok-resp])

      ;; The app has returned a list of all matched entries by database for the given url
      ENABLED_DATABASE_MATCHED_ENTRY_LIST
      (dispatch [:on-entry-list-ok-response ok-resp])

      ;; The app has returned the basic details of the selected entry
      SELECTED_ENTRY
      (dispatch [:content/basic-entry-info-loaded ok-resp #_(:message-content ok-resp)])

      ;; This is sent when user disables a particular browser in the Main App's browser integration settingss
      ;; The disconnect message from proxy. The proxy is exits after sending this message so onDiscoonect is also called
      DISCONNECT
      (dispatch [:native-app-not-available])

      (u/okp-println "Unknown ok response received from app" ok-resp))))

(defn- handle-porxy-response-from-app
  "Called when we receive any response from proxy. 
  The arg reponse is a js object"
  [response]
  #_(println "The raw native meresponse " response)
  (let [{:keys [ok error]} (bg-cmn-event/transform-api-response response)]
    (if-not (nil? error)
      ;; The error is dispatched to send it to the frontend
      (dispatch [:on-app-response-error error])
      (handle-proxy-ok-response ok))))

(defn- get-native-app-id []
  (let [manifest (js/chrome.runtime.getManifest)
        ;;_  (okp-console-log "Extension Manifest is " manifest)
        name (j/get manifest :name)
        native-app-id (if (= name "OneKeePass-Browser") NATIVE_APP_ID NATIVE_APP_ID_DEV)]
    (u/okp-println "The native app id is" native-app-id)
    native-app-id))

;; TODO: Need a chrome specific error handling to be added
(defn- initiate-native-app-connection
  "Called to establish the connection to the native message connection to the proxy"
  []
  (let [^js/Port port (js/chrome.runtime.connectNative (get-native-app-id))]


    ;; The connectNative will launch the proxy app and that returns a port object to communicate with the proxy app
    ;; And we expect a non nil port object here

    (if-not (nil? port)
      (do
        ;; First we need to create the extension side session key pairs
        (cryto/create-session-key-pair)

        (dispatch [:app-connected port])

        ;; Register 'onMessage' listener to handle the responses from the native app
        (j/call-in port [:onMessage :addListener]
                   ;; The added listener with the response each time when proxy responds
                   ;; Proxy app responses are of the form {:ok {...}} or {:error {...}} and mostly routes from the main app's response
                   ;; except for the PROXY_APP_CONNECTION action response which is sent by the proxy app itself
                   (fn [response]
                     (handle-porxy-response-from-app response)))

        ;; Register 'onDisconnect' listener
        ;; The added listener fn is called when the native app side disconnection happens for whatever reason
        (j/call-in port [:onDisconnect :addListener]
                   (fn [port]
                     ;; in Google Chrome port.error is not supported: instead, use runtime.lastError to get the error message.


                     ;; When the main app is not yet installed or installed but the browser extension support is not enabled in the main app 
                     ;; We get the error   -  Error: No such native application org.onekeepass.onekeepass_browser 

                     ;; This indicates that the native messaging manifest json file 'org.onekeepass.onekeepass_browser.json' is not 
                     ;; yet available in the expected platform specific location

                     (let [;; Used in Firefox. For chrome, need to use js/chrome.runtime.lastError.
                           ;; error is a Error obj https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Error
                           error (if (is-firefox-browser?)
                                   (j/get port :error)
                                   js/chrome.runtime.lastError)

                           ;; Firefox returns undefined when there is no error
                           error-message (j/get error :message)]

                       ;; (j/call error :toString) gives Error: No such native application org.onekeepass.onekeepass_browser
                       ;; (j/get error :message) No such native application org.onekeepass.onekeepass_browser

                       #_(js/console.log "DISCONNECTED" " Error:" , error "type" (type error))
                       #_(js/console.log "DISCONNECTED" "LastError:" , js/chrome.runtime.lastError)

                       (js/console.log "DISCONNECTED Called with error:" error  "message:" error-message (undefined? error-message))

                       (when error
                         (js/console.error (j/call error :toString) "message" (j/get error :message)))

                       (cond

                         (and (not (undefined? error-message))
                              (or
                               ;; Firefox specific check
                               (str/includes? error-message "No such native application")
                               ;; Chrome specific check
                               (str/includes? error-message "Specified native messaging host not found")))
                         (dispatch [:native-app-not-available])

                         :else
                         (dispatch [:app-disconnected error]))))))

      ;; Send an error to the content script ?
      (js/console.error "Connection to native app failed"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Native Messaging related END ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;   extension side message handling with re-frame ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-sender-keys [sender]
  (let [{:keys [url frameId tab]} (j/lookup sender)
        tab-id (j/get tab :id)
        tab-url (j/get tab :url)]
    {:tab-id tab-id
     :tab-url tab-url
     :frame-id frameId
     :frame-url url}))

(defn- received-content-loading [sender]
  (let [{:keys [url frameId tab]} (j/lookup sender)
        tab-id (j/get tab :id)
        tab-url (j/get tab :url)]
    ;; (println "tab-id frameId url tab-url  " tab-id frameId url tab-url)

    ;; Stores the info in app-db for later use
    (dispatch [:content/loading-initiated {:tab-id tab-id :tab-url tab-url :frame-id frameId}])

    ;; Sends this frame-id to the content script so that each frame's content script knows its frame-id
    ;; This is only way the content script injected to various frames know their frame-id
    ;; Frame id 0 is top-level browsing context, not in a nested <iframe>. 
    ;; A positive value indicates that navigation happens in a nested iframe.
    ;; Frame IDs are unique for a given tab and process   
    #_(message-to-tab tab-id {:message-type CONTENT_FRAME_ID :frame-id frameId} frameId)))

(defn- login-fields-identified [sender _args]
  (let [{:keys [_url frameId tab]} (j/lookup sender)
        tab-id (j/get tab :id)
        tab-url (j/get tab :url)]
    #_(u/okp-println "tab-id frameId url tab-url LOGIN FILEDS" tab-id frameId url tab-url)

    (dispatch [:content/login-fields-identified {:tab-id tab-id :tab-url tab-url :frame-id frameId}])))

(defn- app-reconnect [{:keys [tab-id]}]
  (dispatch [:start-app-session])

  ;; We cannot use js/window in background script (though it worked in firefox, an error was thrown in chrome) as it is not available
  ;; TODO: In content script side we need to wait for some time using js/window.setTimeout there
  ;; and then check the connection state to show the appropriate popup message


  ;; For now, we wait 3 sec and then send message to content side
  ;; about the connection state
  #_(js/window.setTimeout
     (fn [] (dispatch [:popup/app-reconnected tab-id])) 3000))


;;;;;;;;;;;;;;;;;;;;;;;;;  Messages from content script ;;;;;;;;;;;;;;;;;;;;;;;;;

(defn register-extension-message-handler
  "Called onetime whenever background script is initialized to add listeners to receive messages from 
   content scripts or from popup scripts"
  []
  (js/chrome.runtime.onMessage.addListener
   (fn [message ^js/runtime.MessageSender sender send-response]
     #_(u/okp-console-log "Sender is " sender)

     (let [{:keys [message-type] :as message-data} (js->clj message :keywordize-keys true)
           sender-info (select-sender-keys sender)]

       #_(u/okp-println "Selected sender info in bg message listener" sender-info)
       #_(u/okp-println "Received runtime message" message-data ",from tab-id" (:tab-id sender-info) ",frame-id" (:frame-id sender-info) (type message))

       ;; Listens to various message types from content script
       (condp = message-type

         START_ASSOCIATION
         (dispatch [:send-assoc-message])

         ;; Background gets this message from content script to get the all entries of all opened databases
         ;; that match the tab url from the main app
         GET_ENTRY_LIST
         (dispatch [:get-entry-list sender-info])

         CONTENT_SCRIPT_LOADING
         (received-content-loading sender)

         CONTENT_LOGIN_FIELDS_IDENTIFIED
         (login-fields-identified sender (:fields-info message-data))

         RECONNECT_APP
         (app-reconnect sender-info)

         ;; Background gets this message from content script to get the basic entry info from the main app
         ENTRY_SELECTED
         (dispatch [:load-selected-entry-detail sender-info (select-keys message-data [:db-key :entry-uuid])])

         REDETECT_FIELDS
         (dispatch [:common/send-message-to-current-tab
                    {:message-type REDETECT_FIELDS}])

         LAUNCHING_POPUP
         (do
           #_(send-response #js {:status "success"})
           (dispatch [:iframe/popup-connection-info-response send-response])
           true)

         ;;Else 
         (js/console.error "Unhandled message object " message-data))))))
