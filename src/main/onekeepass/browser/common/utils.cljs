(ns onekeepass.browser.common.utils
  (:require
   [clojure.string :as str]))

(defn contains-val?
  "A sequential search to find a member with an early exit"
  [coll val]
  (reduce #(if (= val %2) (reduced true) %1) false coll))

(defn contains-any-keyword?
  "Checks if the input-string contains any of the keywords from the keywords-vec.
   The check is case-insensitive.

   Args:
     input-string: The string to search within.
     keywords-vec: A vector of strings (keywords) to search for.

   Returns:
     True if the input-string contains any of the keywords, false otherwise."
  [input-string keywords-vec]
  (let [lower-input (str/lower-case input-string)]
    (some #(str/includes? lower-input (str/lower-case %)) keywords-vec)))

(defn okp-println [& args]
  (apply println (cons "okp:" args)))

(defn okp-console-log [& args]
  (apply js/console.log (cons "okp:" args)))

;; TODO: Need to check this logic in other browsers
(defn get-browser-name []
  (cond
    (exists? js/browser)
    "Firefox"

    (and (exists? js/chrome) (exists? (.-brave js/navigator)))
    "Brave"

    (and (exists? js/chrome) (.includes (.-userAgent js/navigator) "Edg/"))
    "Edge"

    (and (exists? js/chrome) (.includes (.-userAgent js/navigator) "OPR"))
    "Opera"

    (exists? js/chrome)
    "Chrome"

    :else
    "Unknown"))

(defn is-firefox-browser? []
  (= (get-browser-name) "Firefox"))

(defn lstr [txt-key]
  (let [msg (js/chrome.i18n.getMessage txt-key)]
    #_(okp-println "Message for txt-key" txt-key "is" msg)
    (if-not (str/blank? msg) msg
            (str "No translation is found for the message key" txt-key))))