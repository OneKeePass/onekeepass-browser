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


;;;;;;;;;;;;;;;;;;;;; Version 2 ;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Needs testing on more sites to determine whether is it worth to use this version

;; After a relevant mutation we wait this long, then run one detection pass. This
;; is a *throttle*, not a reset-debounce: once a pass is armed it fires after the
;; window even if more mutations keep arriving, then re-arms. A reset-debounce
;; would be starved on a continuously churning page (ads / SPA hydration) and
;; never detect a login form that appears there.
#_(def ^:private detect-throttle-ms 400)

;; A mutation is worth a detection pass only when it adds a <form> or a (non-okp)
;; <div> - the containers a login form may appear in. Text nodes and our own
;; okp-* hosts are ignored. (We intentionally skip our own shadow-dom host divs.)
#_(defn- relevant-added-node? [node]
    (when-let [tag (j/get node :tagName)]
      (let [tag (str/lower-case tag)]
        (cond
          (= tag "form") true
          (= tag "div")  (not (str/starts-with? (or (j/get node :id) "") "okp-"))
          :else false))))

#_(defn- relevant-mutations? [mutation-list]
    (some (fn [mutation]
            (and (= (j/get mutation :type) "childList")
                 (some relevant-added-node? (array-seq (j/get mutation :addedNodes)))))
          (array-seq mutation-list)))

#_(defn inputs-on-dom-observation
    "Observes the body subtree for dynamically added login containers and calls
   'callback-on-observe' (with no args) after a quiet window. The callback is
   debounced and fired only when a relevant node was added, so a login-less but
   churny page (news feed, SPA) does not trigger a detection pass per mutation."
    [callback-on-observe]
    ;; body may be HTMLBodyElement or HTMLFrameSetElement or nil
    (let [body (j/get js/document :body)]
      (when (instance? js/HTMLBodyElement body)
        (let [timer (atom nil)
              run (fn []
                    (reset! timer nil)
                    (callback-on-observe))
              on-mutations (fn [mutation-list _observer]
                             ;; Throttle: arm a pass only when one is not already
                             ;; pending. Mutations during the window are absorbed,
                             ;; the pass still fires, then re-arms on the next
                             ;; relevant mutation - never starved by constant churn.
                             (when (and (nil? @timer)
                                        (relevant-mutations? mutation-list))
                               (reset! timer (js/setTimeout run detect-throttle-ms))))
              observer (js/MutationObserver. on-mutations)]
          (.observe observer body #js {:childList true :subtree true})))))

