(ns onekeepass.browser.common.site-matching
  "Pure helpers to match a page url against the user's disabled-sites list.
   Used by the background (gating, quick toggle), and the popup iframes
   (settings popup validation, main popup display).

   A site-rule is a map {:host \"example.com\" :include-subdomains true}
   stored under the key 'disabled-sites' of the 'okp-settings' object in
   chrome.storage.local. (Named site-rule, not 'entry', to avoid confusion
   with the app's Entry concept.)"
  (:require
   [clojure.string :as str]))

(defn url->host
  "Returns the lowercased hostname of the url or nil when the url is not parseable
   (e.g about:newtab) or has no host"
  [url]
  (when-not (str/blank? url)
    (try
      (let [host (.-hostname (js/URL. url))]
        (when-not (str/blank? host)
          (str/lower-case host)))
      (catch :default _e
        nil))))

(defn host-matches-site-rule?
  "Returns true when the page host matches a site-rule.
   Exact host match always applies; when the rule has include-subdomains,
   any subdomain also matches. The subdomain check requires the '.' label
   boundary so 'notexample.com' never matches the rule 'example.com'"
  [page-host {:keys [host include-subdomains]}]
  (boolean
   (and (not (str/blank? page-host))
        (not (str/blank? host))
        (let [page-host (str/lower-case page-host)
              rule-host (str/lower-case host)]
          (or (= page-host rule-host)
              (and include-subdomains
                   (str/ends-with? page-host (str "." rule-host))))))))

(defn find-matching-site-rule
  "Returns the first site-rule matching the page host or nil"
  [page-host site-rules]
  (when-not (str/blank? page-host)
    (first (filter #(host-matches-site-rule? page-host %) site-rules))))

(defn site-disabled?
  "Returns true when the url's host matches any of the site-rules"
  [url site-rules]
  (boolean
   (when (seq site-rules)
     (find-matching-site-rule (url->host url) site-rules))))

(defn normalize-host-input
  "Normalizes a user entered host - trims, lowercases and strips any
   scheme, port and path parts. Returns nil when no valid host can be derived"
  [s]
  (when-not (str/blank? s)
    (let [trimmed (str/trim s)
          ;; Prefix a scheme so js/URL can parse bare hosts like 'example.com/path'
          with-scheme (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" trimmed)
                        trimmed
                        (str "https://" trimmed))
          host (url->host with-scheme)]
      ;; Single labels like 'localhost' are accepted; reject anything js/URL
      ;; produced that still has characters outside a valid hostname
      (when (and host (re-matches #"[a-z0-9._-]+" host))
        host))))
