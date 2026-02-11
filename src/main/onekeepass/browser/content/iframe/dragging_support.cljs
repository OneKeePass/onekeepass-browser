(ns onekeepass.browser.content.iframe.dragging-support
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.utils :as u]
   [reagent.core :as r]
   [clojure.string :as str]))

(def ^:private drag-state (r/atom {:dragging false
                                   :offset-x 0
                                   :offset-y 0}))

#_(defn on-mouse-up [e]
    (when (:dragging? @drag-state)
      (swap! drag-state assoc :dragging? false)
      ;; CRITICAL: Re-enable iframe interaction
      (set! (.-pointerEvents (.-style iframe-el)) "auto")
      ;; Clean up global listeners to save memory
      (js/window.removeEventListener "mousemove" on-mouse-move)
      (js/window.removeEventListener "mouseup" on-mouse-up)))


(defn add-drag-support [host-el iframe-el handle-el]
  (letfn [(on-mouse-move [e]
            (let [{:keys [dragging? offset-x offset-y]} @drag-state]
              (when dragging?
                (let [new-x (- (.-clientX e) offset-x)
                      new-y (- (.-clientY e) offset-y)]
                  ;; Update host position
                  (set! (.-left (.-style host-el)) (str new-x "px"))
                  (set! (.-top (.-style host-el)) (str new-y "px"))))))

          (on-mouse-up [e]
            (when (:dragging? @drag-state)
              (swap! drag-state assoc :dragging? false)
              ;; CRITICAL: Re-enable iframe interaction
              (set! (.-pointerEvents (.-style iframe-el)) "auto")
              ;; Clean up global listeners to save memory
              (js/window.removeEventListener "mousemove" on-mouse-move)
              (js/window.removeEventListener "mouseup" on-mouse-up)))

          (on-mouse-down [e]
            (let [rect (.getBoundingClientRect host-el)
                  off-x (- (.-clientX e) (.-left rect))
                  off-y (- (.-clientY e) (.-top rect))]

              (reset! drag-state {:dragging? true
                                  :offset-x off-x
                                  :offset-y off-y})

              ;; CRITICAL: Disable iframe interaction so mouse doesn't "fall in"
              (set! (.-pointerEvents (.-style iframe-el)) "none")

              ;; Attach global listeners (on window, not the element)
              (js/window.addEventListener "mousemove" on-mouse-move)
              (js/window.addEventListener "mouseup" on-mouse-up)))]
    
    
    ;; Attach Start Listener to the Handle only
    (.addEventListener handle-el "mousedown" on-mouse-down)
    
    ))


#_(defn- add-mouse-move [host-element]
    ()
    (defn on-mouse-move [e]
      (let [{:keys [dragging? offset-x offset-y]} @drag-state]
        (when dragging?
          (let [new-x (- (.-clientX e) offset-x)
                new-y (- (.-clientY e) offset-y)]
            ;; Update host position
            (set! (.-left (.-style host-el)) (str new-x "px"))
            (set! (.-top (.-style host-el)) (str new-y "px")))))))




(comment

  (def ^:private popup-start-postion (atom {:top 5 :right 405}))

  (defn popup-initial-position []
    @popup-start-postion)

  (defn- add-mouse-move-handler [host-element]

    (js/document.addEventListener "mousemove"
                                  (fn [mouse-event]
                                    #_(okp-println "document.addEventListener mousemove is called with @drag-state " @drag-state)
                                    (let [;; The X and Y coordinates of the mouse pointer when mouse is moved in viewport coordinates
                                          {:keys [clientX clientY]} (j/lookup mouse-event)
                                          ;; Previously stored drag state 
                                          {:keys [dragging offset-x offset-y]} @drag-state]

                                      (when dragging
                                        (let [rect (j/call host-element :getBoundingClientRect)
                                              ;; Get the size of an element and its position relative to the viewport.
                                              {:keys [right width height]} (j/lookup rect)

                                              ;; Need to ensure that the popup box is within the viewport
                                              max-left (- js/document.documentElement.clientWidth width)
                                              max-top (- js/document.documentElement.clientHeight height)

                                              left (- clientX offset-x)
                                              left (if (< left 0) 0 left)
                                              left (if (> left max-left) max-left left)

                                              top (- clientY offset-y)
                                              top (if (< top 0) 0 top)
                                              top (if (> top max-top) max-top top)]

                                          #_(u/okp-println "Movetime box right " right "doc width" js/document.documentElement.clientWidth "min" (min right js/document.documentElement.clientWidth))

                                          ;; The element's 'right' position is set from right edge of viewport 
                                          (reset! popup-start-postion {:right (- js/document.documentElement.clientWidth (min right js/document.documentElement.clientWidth))
                                                                       :top top})
                                          (j/assoc-in! host-element [:style :left] (str left "px"))
                                          (j/assoc-in! host-element [:style :top] (str top "px"))))))))

  (defn- add-move-up-handler [_host-element]
    (js/document.addEventListener "mouseup"
                                  (fn []
                                    #_(okp-println "document.addEventListener mouseup is called ")
                                    (reset! drag-state {:dragging false})
                                    #_(j/assoc-in! host-element [:style :cursor] "grab"))))

  (def ^:private drag-events-added (atom false))

  (defn handle-drag-start [host-element mouse-event]

    (u/okp-println "handle-drag-start is called with drag-state" @drag-state "and popup-start-postion" @popup-start-postion)


    (let [rect (j/call host-element :getBoundingClientRect)
          ;; getBoundingClientRect gets the size of an element and its position relative to the viewport.
          {:keys [left top]} (j/lookup rect)

          ;; The X and Y coordinates of the mouse pointer in viewport coordinates
          {:keys [clientX clientY]} (j/lookup mouse-event)

          offset-x (- clientX left)

          offset-y (- clientY top)]

      #_(u/okp-println "host-element" host-element  "left" left  "top" top "offset-x" offset-x "offset-y" offset-y)

      (reset! drag-state {:dragging true
                          :offset-x offset-x
                          :offset-y offset-y})

      (j/assoc-in! host-element [:style :cursor] "grabbing")

      #_(okp-println "drag-events-added value of handle-drag-start "  @drag-events-added)

      (when-not @drag-events-added
        (reset! drag-events-added true)
        (add-mouse-move-handler host-element)
        (add-move-up-handler host-element))))

  (defn remove-added-mouse-events [_host-element]
    (reset! drag-state {:dragging false
                        :offset-x 0
                        :offset-y 0})

    ;;(last-popup-box-position host-element)
    (js/document.removeEventListener "mousemove" add-mouse-move-handler)
    (js/document.removeEventListener "mouseup" add-move-up-handler)

    (reset! drag-events-added false)))
