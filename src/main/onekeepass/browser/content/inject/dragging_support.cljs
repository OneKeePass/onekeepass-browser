(ns onekeepass.browser.content.inject.dragging-support
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.utils :as u]
   [reagent.core :as r]
   [clojure.string :as str]))

(def ^:private drag-state (r/atom {:dragging false
                                   :offset-x 0
                                   :offset-y 0}))

(def ^:private popup-start-postion (atom {:top 5 :right 5}))

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

  #_(u/okp-println "handle-drag-start is called with drag-state" @drag-state "and popup-start-postion" @popup-start-postion)


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

  (reset! drag-events-added false))