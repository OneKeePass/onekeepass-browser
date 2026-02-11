(ns onekeepass.browser.content.mutation-tracking
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.utils :as u]))

(defn- dom-mutation-tracking-callback [callback-on-observe mutation-list, _observer]
  ;; mutation-list is an array of MutationRecord
  (doseq [mutation (vec mutation-list)]
    #_(u/okp-println " +++ mutation is " (j/get mutation :type))
    (when (= (j/get mutation :type) "childList")
      ;; addedNodes prop returns NodeList
      (doseq [node (j/get mutation :addedNodes)]
        #_(u/okp-println " tag is " (j/get node :tagName))
        #_(println " node is " (str/lower-case (j/get node :tagName)))
        #_(u/okp-console-log " In dom-mutation-tracking-callback, added node is " node)
        (when-let [tag (j/get node :tagName)]

          (let [tag (str/lower-case tag)]

            ;; Only these div and form elements are considered
            ;; We need to use this if use "div" as  host for the shadow dom assuming we use the proper id prop
            ;; This also works when we use okp custom tag as host
            (condp = tag
              "form"
              (callback-on-observe {:node node})

              "div"
              (let [okp-div? (str/starts-with? (j/get node :id) "okp-")]
                ;; Need to skip observing our own div element addition
                (when-not okp-div?
                  (callback-on-observe {:node node})))

              ;; else
              nil)

            ;; If we use okp custom elements to host shadow dom instead of div, we can use this
            ;; Only these two elements are considered
            #_(when (or (= tag "div") (= tag "form"))

                ;; node is an instance of div element (HtmlDivElement) or form element (HtmlFormElement)
                ;; This tracking is triggered even for the extension's content script itself adds the "div"
                ;; host element of shadowdom
                
                (okp-console-log "In dom-mutation-tracking-callback Node is ", node)

                ;; Pass the node (not yet used in the callback fn)
                (callback-on-observe {:node node}))))))))

(defn- resgister-dom-mutation-tracking [body callback-on-observe]
  (let  [conf  #js {:childList true, :subtree true}
         observer (js/MutationObserver. (partial dom-mutation-tracking-callback callback-on-observe))]
    (.observe observer body conf)))

(defn inputs-on-dom-observation [callback-on-observe]
  
  #_(okp-console-log "inputs-on-dom-observation is called")

  ;; body may be HTMLBodyElement or HTMLFrameSetElement or nil
  (let [body (j/get js/document :body)]
    (when (instance? js/HTMLBodyElement body)
      (resgister-dom-mutation-tracking body callback-on-observe))))
