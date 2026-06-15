(ns onekeepass.browser.background.events.popup
  (:require
   [onekeepass.browser.background.events.common :as cmn-event]
   [onekeepass.browser.common.connection-states :refer [APP_DISCONNECTED
                                                        NATIVE_APP_NOT_AVAILABLE]]
   [onekeepass.browser.common.message-type-names :refer [POPUP_ACTION]]
   [onekeepass.browser.common.site-matching :as site-matching]
   [re-frame.core :refer [dispatch reg-event-fx]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;; Popup related re-frame events  ;;;;;;;;;;;;

(defn popup-onclick-action
  "Called to send the popup action message to the content side"
  [tab-id tab-url]
  (dispatch [:popup-onclick-action tab-id tab-url]))

(reg-event-fx
 :popup-onclick-action
 (fn [{:keys [db]} [_event-id tab-id tab-url]]
   #_(println "popup/onclick-action app-db is " db)
   (let [state (cmn-event/proxy-connection-state db)
         association-id (get-in db [:association-id])
         ;; Site disable info for the popup's quick 'Disable/Enable on this site' action
         page-host (site-matching/url->host tab-url)
         matched-rule (site-matching/find-matching-site-rule
                       page-host (get-in db [:settings :disabled-sites]))]

     ;; Send the current connection state to the content script of the current tab which in turn injects a suitable
     {:fx [[:common/send-content-message-regfx {:message-data
                                                {:message-type POPUP_ACTION
                                                 ;; :message ""
                                                 :association-id association-id
                                                 :connection-state state
                                                 :page-host page-host
                                                 :site-disabled (boolean matched-rule)
                                                 :matched-disabled-host (:host matched-rule)
                                                 :matched-include-subdomains (:include-subdomains matched-rule)}
                                                :tab-id tab-id
                                                :frame-id 0}]]})))

(reg-event-fx
 :popup/association-rejected
 (fn [{:keys [db]} [_event-id]]
   (let [state (cmn-event/proxy-connection-state db)]
     {:fx [[:common/send-content-message-regfx {:message-data
                                                {:message-type POPUP_ACTION
                                                 :association-rejected true
                                                 :message "Association is rejected by the user in the OneKeePass App"
                                                 :connection-state state}
                                                ;; For now we use current tab id to send this message
                                                ;; Later we can store the tab id in app-db when the association request is sent
                                                ;; Or add request id to ASSOCIATE request to track the tab id
                                                :tab-id (cmn-event/current-tab-id db)
                                                :frame-id 0}]]})))

;; Sends a message to the content script of current tab that the app is not connected
;; The ids tab-id and frame-id are retreived from the app-db
;; For now we use current tab-id and frame-id 0
(reg-event-fx
 :popup/app-is-not-connected
 (fn [{:keys [db]} [_event-id]]
   (let [connection-state (get-in db [:app-connection :state])]
     (if (= connection-state NATIVE_APP_NOT_AVAILABLE)
       {:fx [[:common/send-content-message-regfx {:message-data
                                                  {:message-type POPUP_ACTION
                                                   ;; User has not enabled this browser extension in Main app's settings
                                                   :connection-state connection-state}
                                                  ;; For now we use current tab id to send this message   
                                                  :tab-id (cmn-event/current-tab-id db)
                                                  :frame-id 0}]]}

       {:fx [[:common/send-content-message-regfx {:message-data
                                                  {:message-type POPUP_ACTION
                                                   ;; :message "extension is not connected to the app"
                                                   :connection-state APP_DISCONNECTED}
                                                  ;; For now we use current tab id to send this message   
                                                  :tab-id (cmn-event/current-tab-id db)
                                                  :frame-id 0}]]}))))

;; Sends the reconnection result to the content side of the given tab-id
(reg-event-fx
 :popup/app-reconnected
 (fn [{:keys [db]} [_event-id tab-id]]

   (let [state (cmn-event/proxy-connection-state db)]
     {:fx [[:common/send-content-message-regfx {:message-data
                                                {:message-type POPUP_ACTION
                                                 :association-id nil
                                                 :connection-state state}
                                                :tab-id tab-id
                                                :frame-id 0}]]})))