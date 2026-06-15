(ns onekeepass.browser.content.events.popup
  (:require
   [onekeepass.browser.common.utils :refer [okp-println]]
   [re-frame.core :refer [reg-event-fx reg-sub subscribe]]))

(defn popup-action-info []
  (subscribe [:popup-action-info]))

;; Whenever we receive the popup action message POPUP_ACTION, we store the connection-state and popup ui shown accordingly
(reg-event-fx
 :popup-action/message-received
 (fn [{:keys [db]} [_event_id {:keys [message-type connection-state association-id association-rejected
                                      page-host site-disabled matched-disabled-host
                                      matched-include-subdomains]}]]
   #_(okp-println "The connection-state is " connection-state " association-id " association-id)
   #_(let [existing-connection-state (get-in db [:connection-state])]
       (okp-println "existing-connection-state is " existing-connection-state))

   {:db  (-> db
             (assoc-in [:connection-state] connection-state)
             (assoc-in [:association-id] association-id)
             (assoc-in [:association-rejected] association-rejected)
             ;; Site disable info used by the main popup's quick toggle
             (assoc-in [:page-host] page-host)
             (assoc-in [:site-disabled] site-disabled)
             (assoc-in [:matched-disabled-host] matched-disabled-host)
             (assoc-in [:matched-include-subdomains] matched-include-subdomains))

    ;; Execute the message handler registered for this message-type POPUP_ACTION
    :fx [[:dispatch [:common/execute-message-handler message-type {}]]]}))

(reg-sub
 :popup-action-info
 (fn [db]
   {:connection-state (get-in db [:connection-state])
    :association-id (get-in db [:association-id])
    :association-rejected (get-in db [:association-rejected])}))