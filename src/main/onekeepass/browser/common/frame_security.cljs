(ns onekeepass.browser.common.frame-security)

(defn web-origin [url]
  (try
    (let [parsed (js/URL. url)]
      (when (#{"http:" "https:"} (.-protocol parsed))
        (.-origin parsed)))
    (catch :default _ nil)))

(defn offered-entry-ids [matched-entries]
  (into #{}
        (mapcat (fn [{:keys [db-key entry-summaries]}]
                  (map (fn [{:keys [uuid]}] [db-key uuid]) entry-summaries)))
        matched-entries))

(defn selected-target
  "A popup may select only an entry it was offered, for its original frame.
   A later login-field detection in another frame must not change this target."
  [selection sender {:keys [selection-id db-key entry-uuid]}]
  (when (and (string? selection-id) (seq selection-id)
             (= selection-id (:selection-id selection))
             (= (:tab-id sender) (:tab-id selection))
             (= (:frame-id sender) (:popup-frame-id selection))
             (some? (web-origin (:frame-url selection)))
             (= (web-origin (:frame-url sender))
                (web-origin (if (zero? (:popup-frame-id selection))
                              (:tab-url selection)
                              (:frame-url selection))))
             (contains? (:entry-ids selection) [db-key entry-uuid]))
    (select-keys selection [:tab-id :frame-id :frame-url :document-id])))

(defn popup-channel-token [url]
  (try
    (.get (js/URLSearchParams. (.substring (.-hash (js/URL. url)) 1))
          "okp-channel")
    (catch :default _ nil)))

(defn new-popup-channel-token []
  ;; getRandomValues is available even on HTTP pages, unlike randomUUID.
  (let [bytes (js/Uint8Array. 32)]
    (.getRandomValues js/crypto bytes)
    (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bytes)))))
