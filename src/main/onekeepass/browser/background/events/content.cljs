(ns onekeepass.browser.background.events.content
  "All events that handle content script specific ones are implemented here"
  (:require
   [onekeepass.browser.background.events.common :as cmn-event]
   [onekeepass.browser.common.frame-security :as frame-security]
   [onekeepass.browser.common.message-type-names :refer [BACKGROUND_ERROR
                                                         CONTENT_FRAME_ID
                                                         FILL_PURPOSE_OTP
                                                         LIST_KIND_PASSWORD
                                                         NO_BROWSER_ENABLED_DB
                                                         NO_MATCHING_ENTRIES
                                                         SELECTED_ENTRY_DETAIL
                                                         SELECTED_ENTRY_OTP
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

;; How long the entry just used to sign in on a tab is suggested first for that
;; tab's 2FA code field
(def ^:private SUGGEST_FOR_OTP_MS (* 5 60 1000))

(defn- suggested-otp-entry [db tab-id]
  (let [{:keys [at] :as last-filled} (get-in db [:tabs tab-id :last-filled])]
    (when (and at (< (- (.now js/Date) at) SUGGEST_FOR_OTP_MS))
      (select-keys last-filled [:db-key :entry-uuid]))))

(reg-event-fx
 :content/entry-list-loaded
 (fn [{:keys [db]} [_event_id {:keys [browser-enabled-db-available matched-entries url]} request-id]]
   (let [some-matched-entries (some (fn [{:keys [entry-summaries]}] (not-empty entry-summaries)) matched-entries)
         req (get-in db [:reques-ids request-id])
         ;; When the login fields are inside a nested iframe (frame-id is a positive
         ;; value), rendering the entry list popup in that frame gets clipped by the
         ;; small cross-origin iframe (e.g Charles schwab). In that case we render the
         ;; popup in the main frame (frame 0) at a fixed position instead.
         in-sub-frame? (let [fid (:frame-id req)]
                         (and fid (not (zero? fid))))
         ;; The fill-target-origin is the origin of the frame the credential will be
         ;; typed into (the login-field frame); top-origin is the top page. Equal
         ;; for top-frame logins, different for cross-origin sub-frame logins. Shown
         ;; in the popup so the user can notice when a login is embedded.
         fill-target-origin (cmn-event/url->origin (:frame-url req))
         top-origin (cmn-event/url->origin (:tab-url req))
         selection (when (and req (frame-security/web-origin (:frame-url req)))
                     (assoc req
                            :selection-id request-id
                            :popup-frame-id (if in-sub-frame? 0 (:frame-id req))
                            :entry-ids (frame-security/offered-entry-ids matched-entries)))
         db (if req
              (update-in db [:tabs (:tab-id req)] dissoc :autofill-selection)
              db)]
     #_(u/okp-println "Matched entries" some-matched-entries)
     #_(u/okp-println "empty some-matched-entries val is " (empty? some-matched-entries) "request-id" request-id)
     (cond
       (= browser-enabled-db-available false)
       ;; Last argument ensures that the msg popup is shown in main frame
       {:db db :fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type NO_BROWSER_ENABLED_DB} request-id true]]]}

       (empty? some-matched-entries)
       ;; Last argument ensures that the msg popup is shown in main frame
       {:db db :fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type NO_MATCHING_ENTRIES :url url} request-id true]]]}
       (nil? selection)
       {}

       :else
       ;; When login is in a sub-frame, route to the main frame (last arg true) and
       ;; flag the popup to use a fixed position since there is no focused input there
       {:db (assoc-in db [:tabs (:tab-id req) :autofill-selection] selection)
        :fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type SHOW_ENTRY_LIST
                          :list-kind LIST_KIND_PASSWORD
                          :purpose (:purpose req)
                          :suggested-entry (when (= (:purpose req) FILL_PURPOSE_OTP)
                                             (suggested-otp-entry db (:tab-id req)))
                          :matched-entries matched-entries
                          :selection-id request-id
                          :fixed-position in-sub-frame?
                          :fill-target-origin fill-target-origin
                          :top-origin top-origin} request-id in-sub-frame?]]]}))))

;; The app returned the picked entry's username, password and, when it has one,
;; its current 2FA code. Each kind of field gets only what it fills:
;; - pick from a 2FA code field: only the code (SELECTED_ENTRY_OTP)
;; - pick from a login field: username and password, never the code
(reg-event-fx
 :content/basic-entry-info-loaded
 (fn [{:keys [db]} [_event_id {:keys [message-content request-id]}]]
   (let [{:keys [purpose tab-id db-key entry-uuid]} (get-in db [:reques-ids request-id])
         otp (:otp message-content)
         has-otp? (seq (str (:token otp)))]
     (if (= purpose FILL_PURPOSE_OTP)
       {:fx [[:dispatch [:common/send-tab-message-for-request-id
                         (if has-otp?
                           {:message-type SELECTED_ENTRY_OTP :otp otp}
                           {:message-type BACKGROUND_ERROR
                            :message "No 2FA code for this entry. The entry may have no one-time password, or the OneKeePass app may need an update"})
                         request-id (not has-otp?)]]]}
       ;; Remembered so the tab's next 2FA code field suggests this entry first
       {:db (cond-> db
              (and tab-id has-otp?)
              (assoc-in [:tabs tab-id :last-filled] {:db-key db-key
                                                     :entry-uuid entry-uuid
                                                     :at (.now js/Date)}))
        :fx [[:dispatch [:common/send-tab-message-for-request-id
                         {:message-type SELECTED_ENTRY_DETAIL
                          :basic-entry-info (dissoc message-content :otp)} request-id]]]}))))

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
   (let [selection (get-in db [:tabs tab-id :autofill-selection])
         db (if (or (zero? frame-id) (= frame-id (:frame-id selection)))
              (update-in db [:tabs tab-id] dissoc :autofill-selection)
              db)
         db (update db :reques-ids
                    (fn [requests]
                      (into {} (remove (fn [[_ target]]
                                         (and (= tab-id (:tab-id target))
                                              (or (zero? frame-id)
                                                  (= frame-id (:frame-id target))))))
                            requests)))]
     {:db (if (zero? frame-id)
            ;; Keeps the tab's :last-filled (the entry just used to sign in) for
            ;; the next page's 2FA code field
            (assoc-in db [:tabs tab-id] (merge (select-keys (get-in db [:tabs tab-id]) [:last-filled])
                                               {:tab-url url :login-field-frame nil}))
            db)})))

#_(reg-event-fx
   :content/tab-updated
   (fn [{:keys [db]} [_event_id tab-id url status]]
     (println "In :content/tab-updated tab-id url status" tab-id url status)
     (if (and (not (nil? url)) (= status "complete"))
       {:fx [[:dispatch [:content/tab-removed tab-id]]]}
       {})))
