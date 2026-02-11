(ns onekeepass.browser.content.iframe.events.popup-iframe-bg
  "This is not used anymore. Leaving it here as example of using for iframe <-> background direct channel example
  At this re-frame events are not enabled. Accordingly these names are used elsewhere
  This ns should be removed
  "
  (:require
   [onekeepass.browser.common.utils :as u]
   [re-frame.core :refer [reg-event-fx reg-sub subscribe]]))


(defn popup-action-info []
  (subscribe [:iframe-popup-action-info]))

;; See popup-message-handlers fn of 'onekeepass.browser.content.iframe.events.messaging'
;; Whenever we receive the popup action message CONNECTION_STATE_INFO, we store the connection-state and popup ui shown accordingly
(reg-event-fx
 :iframe-popup-action/message-received
 (fn [{:keys [db]} [_event_id {:keys [connection-state association-id association-rejected]}]]
   (u/okp-println "The connection-state is " connection-state " association-id " association-id)
   #_(let [existing-connection-state (get-in db [:connection-state])]
       (okp-println "existing-connection-state is " existing-connection-state))

   {:db  (-> db
             (assoc-in [:iframe-popup :connection-state] connection-state)
             (assoc-in [:iframe-popup :association-id] association-id)
             (assoc-in [:iframe-popup :association-rejected] association-rejected))

    ;; Execute the message handler registered for this message-type CONNECTION_STATE_INFO
    :fx [#_[:dispatch [:iframe/execute-message-handler message-type {}]]]}))

(reg-sub
 :iframe-popup-action-info
 (fn [db]
   {:connection-state (get-in db [:iframe-popup :connection-state])
    :association-id (get-in db [:iframe-popup :association-id])
    :association-rejected (get-in db [:iframe-popup :association-rejected])}))