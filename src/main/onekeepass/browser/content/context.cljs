(ns onekeepass.browser.content.context
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.utils :as u]))

(defprotocol PageStoreAccess
  (get-input-field-info [this type-kw])
  (get-username-input [this])
  (get-password-input [this])

  #_(get-username-field-info [this])
  #_(get-password-field-info [this]))

(def ^:private page-data (atom {:page-url nil
                                :identified-page-info nil}))

(defn store-page-info [page-info]
  (let [page-url (j/get-in js/window [:location :href])]
    #_(u/okp-println "store-page-info is called with page-info " page-info)
    (swap! page-data assoc :identified-page-info page-info :page-url page-url)
    #_(u/okp-println "The page info data is set with " @page-data)))

(defn get-page-info []
  (@page-data :identified-page-info))

(defn username-in-page []
  (some-> (@page-data :identified-page-info) get-username-input))

(defn password-in-page []
  (some-> (@page-data :identified-page-info) get-password-input))

#_(defn not-page-info-available []
  ;; identified-page-info is nil till stored
  (not (boolean (@page-data :identified-page-info))))

(defn is-already-found? [other-page-info]
  (= other-page-info (@page-data :identified-page-info)))
