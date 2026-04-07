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

(defn get-browser-name []
  (let [ua (.-userAgent js/navigator)
        chromium? (exists? (.-userAgentData js/navigator))
        br-name     (cond
                      ;; navigator.userAgentData is Chromium-only; Firefox does not implement it.
                      ;; Distinguish specific Chromium browsers via navigator.brave or UA string.
                      (and chromium? (exists? (.-brave js/navigator)))
                      "Brave"

                      (and chromium? (.includes ua "Edg/"))
                      "Edge"

                      (and chromium? (.includes ua "OPR"))
                      "Opera"

                      chromium?
                      "Chrome"

                      ;; Firefox: no userAgentData, and UA contains "Firefox".
                      ;; js/browser and js/chrome are no longer reliable — both now exist in Chrome 146+.
                      (.includes ua "Firefox")
                      "Firefox"

                      :else
                      "Unknown")]
    (okp-println "Browser identified as" br-name)
    br-name))

;; alternate possible implemetation 
#_(defn get-browser-name []
    (let [ua (.-userAgent js/navigator)
          ua-data (.-userAgentData js/navigator)
          chromium? (some? ua-data)
          brands (when ua-data
                   (map #(.-brand %) (js->clj (.-brands ua-data) :keywordize-keys false)))

          has-brand? (fn [name]
                       (some #(= % name) brands))

          includes? (fn [s]
                      (.includes ua s))]

      (cond
        ;; Brave exposes navigator.brave even when UA brands look like Chrome
        (exists? (.-brave js/navigator))
        "Brave"

        ;; Edge
        (or (has-brand? "Microsoft Edge")
            (includes? "Edg/"))
        "Edge"

        ;; Opera
        (or (has-brand? "Opera")
            (includes? "OPR/"))
        "Opera"

        ;; Arc currently identifies as Chrome in brands, but UA contains Arc
        (includes? "Arc/")
        "Arc"

        ;; Firefox has no userAgentData
        (includes? "Firefox/")
        "Firefox"

        ;; Safari must exclude Chromium browsers
        (and (includes? "Safari/")
             (not (includes? "Chrome/"))
             (not (includes? "Chromium/"))
             (not (includes? "Edg/"))
             (not (includes? "OPR/")))
        "Safari"

        ;; Chrome / Chromium family fallback
        (or (has-brand? "Google Chrome")
            (has-brand? "Chromium")
            chromium?)
        "Chrome"

        :else
        "Unknown")))

(defn is-firefox-browser? []
  (= (get-browser-name) "Firefox"))

(defn lstr [txt-key]
  (let [msg (js/chrome.i18n.getMessage txt-key)]
    #_(okp-println "Message for txt-key" txt-key "is" msg)
    (if-not (str/blank? msg) msg
            (str "No translation is found for the message key" txt-key))))