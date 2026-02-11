(ns onekeepass.browser.content.common-ui
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.constants :refer [PASSWORD]]))

#_(defn contains-val?
    "A sequential search to find a member with an early exit"
    [coll val]
    (reduce #(if (= val %2) (reduced true) %1) false coll))

(set! *warn-on-infer* true)

(def ALLOWED_TYPES ["text", "password", "email"])

(def POSSIBLE_FIELD_NAMES ["user" "username" "login" "email" "email address" "memeber" "membership" "account" "phone number or email"])

(defn- check-possible-username
  "Checks whether the passed string value matches to any one of the standard field names or props 
   that are found for a typical username field"
  [input-string]
  (if (str/blank? input-string)
    false
    (let [lower-input (str/lower-case input-string)]
      (boolean (some #(str/includes? lower-input %) POSSIBLE_FIELD_NAMES)))))

(defn input-label-text
  "Extracts relevant text from an input's label."
  [input-el]
  (let [id (j/get input-el :id)
        label-el (when id (js/document.querySelector (str "label[for='" id "']")))
        label-text (if label-el (j/get label-el :textContent) "")]
    label-text))

(defn is-possible-username-input
  "Determines whether the arg 'input' is a username type field or not based on certain properties of this input field"
  [input]
  (let [[name id placeholder] (j/let [^:js {:keys [name id placeholder]} input]
                                [name id placeholder])

        autocomplete (j/call-in input [:getAttribute] "autocomplete")
        arial-label  (j/call-in input [:getAttribute] "aria-label")
        label (input-label-text input)]

    (boolean (some check-possible-username [name id placeholder autocomplete arial-label label]))))

#_(defn is-input-action-login [input]
    (j/let [^:js {:keys [formAction baseURI]} input]
      (or (.test regex-utils/login-action-combined-regex formAction)
          (.test regex-utils/login-action-combined-regex baseURI))))

(defn is-element-hidden-by-css [^js/Element element]
  ;; css is CSSStyleDeclaration obj
  ;; Throws exception if element is not valid 'Element'
  (if (instance? js/Element element)
    (let [css ^js/CSSStyleDeclaration (js/window.getComputedStyle element)

          ;; tag-info (str  "Tag: " (j/get element :tagName)  ",className: " (j/get element :className))

          visibility (j/call css :getPropertyValue "visibility")
          display (j/call css :getPropertyValue "display")

          ;; _ (when (= display "none") (u/okp-console-log "Element style when no dislay" (j/get-in element [:style :display] )))
          
          ;; Sometimes an element may be hidden just making it height 0
          height (j/call css :getPropertyValue "height")
          ;; Parse the height string to a floating-point number
          ;; parseFloat will correctly parse "0px", "0%", "0em" to 0
          height-val (js/parseFloat height)

          ;; hidden (or (= visibility "hidden") (= display "none") (zero? height-val))
          
          ;; In yahoo login page, one of the ancestor had zero height and that resulted hiden value for username
          ;; Adding not block check with zero height worked
          ;; hidden (or (= visibility "hidden") (= display "none")  (and (zero? height-val) (not= display "block")))
          
          hidden (or (= visibility "hidden") (= display "none")  #_(and (zero? height-val) (= display "block")))
          ]

      #_(when hidden
        ;; (u/okp-console-log "Style get is " (j/get element :style ))
        ;; (u/okp-console-log "Display get is " (j/call css :getPropertyValue "display"))
        ;; (u/okp-console-log "dominantBaseline get is " (j/call css :getPropertyValue "dominantBaseline"))
        ;; (u/okp-console-log "MozAnimation get is " (j/call css :getPropertyValue "MozAnimation")) 
        ;; (u/okp-console-log "In is-element-hidden-by-css computed style is" css)
        (u/okp-console-log "In is-element-hidden-by-css" "TagInfo " tag-info ","  "visibility " visibility ", display " display, "height-val" height-val   " final " hidden "element" element))
      hidden)
    false))

(defn is-any-ancestor-hidden [^js/Element element]
  (let [parent (j/get element :parentElement)]
    ;; (u/okp-console-log "In is-any-ancestor-hidden" "element" element "with parent" parent)
    (if (nil? parent) false
        (if (is-element-hidden-by-css parent)
          #_(do
            (u/okp-console-log "Ancestor hidden found" "element" element "with parent" parent)
            true)
          true
          (is-any-ancestor-hidden parent #_(j/get parent :parentElement))))))

(defn is-skipped-input [input]
  (or (.-hidden input)  (.-disabled input) (not (u/contains-val? ALLOWED_TYPES (.-type input)))))

(defn is-hidden-password-input
  [input]
  (and (= (.-type input) PASSWORD) (or (.-hidden input) (.-disabled input))))

(defn is-passord-input [input]
  (= (j/get input :type) PASSWORD))

(defn is-hidden-text-input
  [input]
  (and (or (= (.-type input) "text") (= (.-type input) "email")) (or (.-hidden input) (.-disabled input))))

(defn is-hidden-input
  [input]
  (or (.-hidden input) (.-disabled input)))

(defn matches-with-node-name? [el tag]
  (when el (= (str/upper-case tag) (str/upper-case (j/get el :nodeName)))))

(defn input-props-as-map [input]
  {:name (j/get input :name)
   :id (j/get input :id)
   :type (j/get input :type)})

(defn form-props-as-map [form]
  {;; number of controls in the form
   :length (j/get form :length)
   :action (j/get form :action)
   ;; the default method is 'get'
   :method (j/get form :method)
   :name (j/get form :name)})
