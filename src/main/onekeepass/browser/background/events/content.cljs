(ns onekeepass.browser.background.events.content
  "All events that handle content script specific ones are implemented here"
  (:require
   [onekeepass.browser.background.events.common :as cmn-event]
   [onekeepass.browser.common.message-type-names :refer [CONTENT_FRAME_ID
                                                         NO_BROWSER_ENABLED_DB
                                                         NO_MATCHING_ENTRIES
                                                         SELECTED_ENTRY_DETAIL
                                                         SHOW_ENTRY_LIST]]
   #_[onekeepass.browser.common.utils :as u]
   [re-frame.core :refer [reg-event-fx]]))

;; Frame ID 0 is top-level browsing context, not in a nested <iframe>. 
;; A positive value indicates that navigation happens in a nested iframe.
;; Frame IDs are unique for a given tab and process

;; Called when a content script from each frame of a tab page sends a loading message 
(reg-event-fx
 :content/loading-initiated
 (fn [{:keys [db]} [_event_id {:keys [tab-id frame-id _tab-url]}]]
   ;; Not sure when :current-tab-id will be nil before this event
   (if (nil? tab-id) {}
       {:db (if (nil? (cmn-event/current-tab-id db)) (assoc db :current-tab-id tab-id) db)
        ;; Here the background sends back to content this message
        :fx [[:common/send-content-message-regfx {:tab-id tab-id
                                                  :frame-id frame-id
                                                  :message-data {:message-type CONTENT_FRAME_ID :frame-id frame-id}}]]})))

;; Called when the tab page identifies the login fields
;; We store the frame-id of content script which located the login fields
;; Typically the frame id is 0
;; Verify: Will the frameid will be any other positive number instead of 0?
(reg-event-fx
 :content/login-fields-identified
 (fn [{:keys [db]} [_event_id {:keys [tab-id frame-id _tab-url]}]]
   ;;    (println "content/login-fields-identified tab-id frame-id _tab-url " tab-id frame-id _tab-url)
   ;;    (println "content/login-fields-identified tab-info "  (get-in db [:tabs tab-id]))
   {:db (-> db (assoc-in [:tabs tab-id :login-field-frame] frame-id))}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(reg-event-fx
 :content/entry-list-loaded
 (fn [{:keys [_db]} [_event_id {:keys [browser-enabled-db-available matched-entries url]} request-id]]
   (let [some-matched-entries (some (fn [{:keys [entry-summaries]}] (not-empty entry-summaries)) matched-entries)]
     #_(u/okp-println "empty some-matched-entries val is " (empty? some-matched-entries) "request-id" request-id)
     (cond
       (= browser-enabled-db-available false)
       ;; Last argument ensures that the msg popup is shown in main frame
       {:fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type NO_BROWSER_ENABLED_DB} request-id true]]]}

       (empty? some-matched-entries)
       ;; Last argument ensures that the msg popup is shown in main frame
       {:fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type NO_MATCHING_ENTRIES :url url} request-id true]]]}
       :else
       {:fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type SHOW_ENTRY_LIST
                          :matched-entries matched-entries} request-id]]]}))))

(reg-event-fx
 :content/basic-entry-info-loaded
 (fn [{:keys [_db]} [_event_id {:keys [message-content request-id]}]]

   {:fx [[:dispatch [:common/send-tab-message-for-request-id
                     {:message-type SELECTED_ENTRY_DETAIL
                      :basic-entry-info message-content} request-id]]]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fires when the active tab in a window changes
;; The current-tab-id is set accordingly
(reg-event-fx
 :content/tab-activated
 (fn [{:keys [db]} [_event_id tab-id]]
   {:db (assoc db :current-tab-id tab-id)}))

(reg-event-fx
 :content/tab-removed
 (fn [{:keys [db]} [_event_id tab-id]]
   (let [tabs  (get-in db [:tabs])
         tabs (dissoc tabs tab-id)]
     #_(println "All tab ids are AFTER removal " (keys tabs))
     {:db (assoc db :tabs tabs)})))

;; Here we initialize the tab-url
;; We consider only the frmae id 0 which is for top-level browsing context frame and ignore all other 
;; frame ids
;; It is expected that this event will be called before  :content/loading-initiated
(reg-event-fx
 :content/web-navigation-committed
 (fn [{:keys [db]} [_event_id tab-id frame-id url]]
   (if (= 0 frame-id)
     {:db  (-> db (assoc-in [:tabs tab-id] {:tab-url url :login-field-frame nil}))}
     #_(do
         (println "content/web-navigation-committed  current-tab-id is " (get db :current-tab-id))
         {:db  (-> db (assoc-in [:tabs tab-id] {:tab-url url :login-field-frame nil}))})
     {})))

#_(reg-event-fx
   :content/tab-updated
   (fn [{:keys [db]} [_event_id tab-id url status]]
     (println "In :content/tab-updated tab-id url status" tab-id url status)
     (if (and (not (nil? url)) (= status "complete"))
       {:fx [[:dispatch [:content/tab-removed tab-id]]]}
       {})))