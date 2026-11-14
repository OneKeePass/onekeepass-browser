(ns onekeepass.browser.content.inject.input-icon-lifecycle)

(defn track-input-icon!
  "Keep an icon tied to its live input, and retire it when the input disappears.
   Returns a cleanup function for replacing the icon. No polling is needed."
  [input host on-move on-stale]
  (let [document (.-ownerDocument input)
        window (.-defaultView document)
        active? (atom true)
        observer (atom nil)]
    (letfn [(stop! []
              (when (compare-and-set! active? true false)
                (.disconnect @observer)
                (.removeEventListener window "resize" move!)
                (.removeEventListener window "scroll" move! true)))
            (validate! []
              (when @active?
                (if (and (.-isConnected input)
                         (.-isConnected host)
                         (.checkVisibility input #js {:visibilityProperty true
                                                      :opacityProperty true}))
                  true
                  (do (stop!) (on-stale) false))))
            (move! []
              (when (validate!)
                (on-move)))]
      (reset! observer (js/MutationObserver. (fn [_ _] (validate!))))
      (let [options #js {:childList true :subtree true :attributes true
                        :attributeFilter #js ["class" "style" "hidden"]}]
        (.observe @observer (.-documentElement document) options)
        ;; Document observers do not cross shadow boundaries. Watch each root
        ;; containing the field too, including nested shadow hosts.
        (loop [root (.getRootNode input)]
          (when-let [shadow-host (.-host root)]
            (.observe @observer root options)
            (recur (.getRootNode shadow-host)))))
      (.addEventListener window "resize" move!)
      ;; Capture also receives scrolling inside nested page containers.
      (.addEventListener window "scroll" move! true)
      stop!)))
