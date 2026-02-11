(ns onekeepass.browser.content.signals
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.regex-utils :as regex-utils]))

(defn is-input-action-login [input]
  (j/let [^:js {:keys [formAction baseURI]} input]
    (or (.test regex-utils/login-action-combined-regex formAction)
        (.test regex-utils/login-action-combined-regex baseURI))))

(defn is-input-action-signup [input]
  (j/let [^:js {:keys [formAction baseURI]} input]
    (or (.test regex-utils/signup-action-combined-regex formAction)
        (.test regex-utils/signup-action-combined-regex baseURI))))

(defn- form-action-signals
  "Here we use regex to find login or signup keyword in the form level URI
   Some site may have uri without expected keyword (e.g BOA) or our list of key words may miss 
   the expected keyword (should be added to the list) 
  "
  [^js/HTMLFormElement form]
  (j/let [^:js {:keys [action baseURI]} form]
    {:form-action-login (or (.test regex-utils/login-action-combined-regex action)
                            (.test regex-utils/login-action-combined-regex baseURI))

     :form-action-signup (or (.test regex-utils/signup-action-combined-regex action)
                             (.test regex-utils/signup-action-combined-regex baseURI))}))

(defn- login-action-button-signals
  "We try to locate the submit button and extract info to identify login/signup "
  [form-buttons]
  (println "Buttons found size in login-action-button-signals " (count form-buttons))
  (reduce
   (fn [acc btn]
     (j/let [^:js {:keys [type innerText outerText textContent formAction baseURI tagName]} btn]
       ;; (println "DEBUG: login-action-button-signals tagName type innerText outerText " tagName type innerText outerText)
       ;;  (js/console.log "Btn considering " btn)

       (let [inner-text (.test regex-utils/login-keywords-regex innerText)
             text-content (.test regex-utils/login-keywords-regex textContent)
             outer-text (.test regex-utils/login-keywords-regex outerText)
             form-action (.test regex-utils/login-action-combined-regex formAction)
             base-uri (.test regex-utils/login-action-combined-regex baseURI)]
         (if (or inner-text text-content outer-text form-action base-uri)
           (reduced {:inner-text inner-text
                     :text-content text-content
                     :outer-text outer-text
                     :form-action form-action
                     :base-uri base-uri
                     :form-submit-button btn})
           acc))))

   {} form-buttons))

(defn- signup-action-button-signals [form-buttons]
  ;; (println "Buttons found size in signup-action-button-signals " (count form-buttons))
  (reduce
   (fn [acc btn]
     (j/let [^:js {:keys [type innerText outerText textContent formAction baseURI tagName]} btn]
       ;; (println "signup-action-button-signals tagName type innerText outerText " tagName type innerText outerText)
       (let [inner-text (.test regex-utils/signup-keywords-regex innerText)
             text-content (.test regex-utils/signup-keywords-regex textContent)
             outer-text (.test regex-utils/signup-keywords-regex outerText)
             form-action (.test regex-utils/signup-action-combined-regex formAction)
             base-uri (.test regex-utils/signup-action-combined-regex baseURI)]
         (if (or inner-text text-content outer-text form-action base-uri)
           (reduced {:inner-text inner-text
                     :text-content text-content
                     :outer-text outer-text
                     :form-action form-action
                     :base-uri base-uri
                     :form-submit-button btn})
           acc))))

   {} form-buttons))

(defn- attribute-match-any
  "Matches to the first attribute value to the pattern"
  [^js/NamedNodeMap attributes-map regex]
  (reduce
   (fn [acc attr]
     #_(println "Attribute name " (j/get attr :name)  "=" (j/get attr :value))
     (if (.test regex (j/get attr :value))
       (reduced true)
       acc))
   false
   ;; Convert the NamedNodeMap to a JavaScript Array and then use
   (js/Array.from attributes-map)))

(defn- forgot-password-link-signal [container]
  (let [descendant-elements (vec (j/call container "querySelectorAll" "a, span, button"))]
    (reduce (fn [acc el]
              (j/let [^:js {:keys [textContent attributes nodeName]} el]

                ;; (js/console.log "The attributes " attributes " and node name .." nodeName " textContent " textContent)
                ;; (println "attribute test " (attributes-match  attributes regex-utils/forgot-action-regex))

                (cond
                  (.test regex-utils/forgot-action-regex textContent)
                  (reduced true)

                  (attribute-match-any attributes regex-utils/forgot-action-regex)
                  (reduced true)

                  :else
                  acc)))

            false descendant-elements)))

#_(def form-button-query
    "button[type=submit],input[type=submit], button[type=button], input[type=button],button:not([type]), div[role=button]")

;; Saw few cases where type=undefined
;; If the type attribute is omitted or set to an invalid value (like undefined), 
;; the button defaults to the submit type, especially if it's within a <form> element
;; We need this query on those cases
(def ^:private form-submit-button-other-query1 "button:not([type]),button[type=undefined]")

(def ^:private form-submit-button-query
  "button[type=submit], input[type=submit]")

;; Should this be here or in form_fields ns ?
;; We may do the signals extraction part here and moving any ui element handling to form_fields ns

(defn classify-form 
  "called to determine whether the page that contains this 'form' is a login or a signup or both or none 
   using various signals"
  [^js/HTMLFormElement form]
  (let [;; First find form action URI based signal
        {:keys [form-action-login form-action-signup] :as form-signals} (form-action-signals form)

        ;; Collect all submit buttons
        buttons (array-seq (j/call form :querySelectorAll form-submit-button-query))

        buttons (if-not (empty? buttons)
                  buttons
                  (array-seq (j/call form :querySelectorAll form-submit-button-other-query1)))

        ;; Possible login signals
        login-btn-signals (login-action-button-signals buttons)

        ;; Possible signup signals
        signup-btn-signals  (signup-action-button-signals buttons) #_(when form-action-signup (signup-action-button-signals buttons))

        ;; Sum up all login signals
        login-signals-count (count (filter (fn [[k v]] v) (dissoc login-btn-signals :form-submit-button)))
        login-signals-count (if form-action-login (+ 1 login-signals-count) login-signals-count)

        ;; _ (println "Forgot password signal " (forgot-password-link-signal form))
        login-forgot-password-link-signal (forgot-password-link-signal form)

        login-signals-count (if login-forgot-password-link-signal (+ 1 login-signals-count) login-signals-count)

        ;; Sum up all signup signals
        signup-signals-count (count (filter (fn [[k v]] v) (dissoc signup-btn-signals :form-submit-button)))
        signup-signals-count (if form-action-signup (+ 1 signup-signals-count) signup-signals-count)

        ;; Collect all relevant signals
        ;; merged-signals (merge form-signals
        ;;                       {:login-forgot-password-link-signal login-forgot-password-link-signal
        ;;                        :login-button-signals login-btn-signals
        ;;                        :signup-button-signals signup-btn-signals})

        container-page-type (cond
                              ;; We could not identify any of login or signup signals
                              ;; This may be the form may not be for login ( can this happen ?)
                              ;; Or our logic of gathering signals is not working for this site
                              (and (= login-signals-count 0) (= signup-signals-count 0))
                              :not-login-or-signup

                              ;; Login
                              (or (and (> login-signals-count 0)
                                       (= signup-signals-count 0))
                                  (> login-signals-count signup-signals-count))
                              :login

                              ;; Signup
                              (or (and (= login-signals-count 0) (> signup-signals-count 0))
                                  (> signup-signals-count login-signals-count))
                              :signup

                              ;; Both login and signup forms active?
                              (= signup-signals-count login-signals-count)
                              :login-signup)]


    ;; (println "merged-signals " merged-signals)
    ;; (println "login-signals-count " login-signals-count ", signup-signals-count " signup-signals-count)
    
    {:container-page-type container-page-type

     ;; Do we need this?  May be required for autocomplete and submit?
     ;; Login submit identified
     :login-submit-button (:form-submit-button login-btn-signals)

     ;; Do we need this?
     ;; Signup submit identified
     :signup-submit-button (:form-submit-button login-btn-signals)}))
