(ns onekeepass.browser.content.events.common
  (:require
   #_[onekeepass.browser.common.utils :as u]
   [re-frame.core :refer [reg-event-fx reg-fx]]))


(defn set-app-db-connection-state [app-db connection-state association-id]
  (-> app-db (assoc-in [:connection-state] connection-state)
      (assoc-in [:association-id] association-id)))

(reg-event-fx
 :common/execute-message-handler
 (fn [{:keys [db]} [_event_id message-type args]]
   {:fx [[:execute-message-handler-regfx [(get-in db [:message-handlers message-type]) args]]]}))

#_(defn get-app-connection-state []
    (subscribe [:common/get-app-connection-state]))

#_(defn get-association-id []
    (subscribe [:common/get-association-id]))

#_(reg-event-fx
   :common/set-app-connection-state
   (fn [{:keys [db]} [_event_id connection-state]]
     {:db (-> db (assoc-in [:connection-state] connection-state))}))

#_(reg-sub
   :common/get-app-connection-state
   (fn [db]
     (get-in db [:connection-state])))

#_(reg-sub
   :common/get-association-id
   (fn [db]
     (get-in db [:association-id])))


;;;;;;;;  Message to background script ;;;;;;;;;;;;;;;

(reg-fx
 :common/send-bg-message-regfx
 (fn [message-data]
   ;; message-data is map with key [message-type]
   (js/chrome.runtime.sendMessage  (clj->js message-data))))

#_(reg-event-fx
   :common/send-bg-message
   (fn [{:keys [db]} [_event_id message-data]]
     {:fx [[:common/send-bg-message-regfx message-data]]}))



