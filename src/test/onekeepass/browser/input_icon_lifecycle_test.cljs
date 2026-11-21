(ns onekeepass.browser.input-icon-lifecycle-test
  (:require
   [cljs.test :refer-macros [deftest is]]
   [onekeepass.browser.content.inject.custom-element :as element]
   [onekeepass.browser.content.inject.input-field-okp-icon :as icon]))

(defn with-page [test-fn]
  (let [old-window (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-input-class (.-HTMLInputElement js/globalThis)
        old-observer (.-MutationObserver js/globalThis)
        listeners (atom {})
        observers (atom [])
        mounted (atom nil)
        visible? (atom true)
        input-class (fn [])
        input (new input-class)
        host #js {:isConnected true}
        root #js {:style #js {} :setAttribute (fn [_ _]) :addEventListener (fn [_ _])}
        window #js {:addEventListener (fn [name handler & _] (swap! listeners assoc name handler))
                    :removeEventListener (fn [name handler & _]
                                           (when (identical? handler (get @listeners name))
                                             (swap! listeners dissoc name)))}
        document #js {:documentElement #js {}
                      :defaultView window
                      :body #js {:appendChild #(reset! mounted %)}}]
    (set! (.-isConnected input) true)
    (set! (.-ownerDocument input) document)
    (set! (.-getRootNode input) (fn [] document))
    (set! (.-checkVisibility input) (fn [_] @visible?))
    (set! (.-getBoundingClientRect input)
          (fn [] #js {:left 10 :top 20 :width 240 :height 32}))
    (try
      (set! js/globalThis.window window)
      (set! js/globalThis.document document)
      (set! js/globalThis.HTMLInputElement input-class)
      (set! js/globalThis.MutationObserver
            (fn [callback]
              (let [disconnected? (atom false)
                    targets (atom [])
                    observer #js {:observe (fn [target options] (swap! targets conj [target options]))
                                  :disconnect #(reset! disconnected? true)}]
                (swap! observers conj {:callback callback :disconnected? disconnected? :targets targets})
                observer)))
      (with-redefs [element/create-input-icon-shadow-dom
                    (fn [_ _] {:host-element host :component-root root})
                    element/remove-shadowdom-host-element-by-class
                    (fn [_] (reset! mounted nil))]
        (set! (.-remove host) (fn [] (reset! mounted nil)))
        (try
          (test-fn {:input input :host host :visible? visible? :listeners listeners
                    :observers observers :mounted mounted :document document})
          (finally (icon/remove-input-okp-icon!))))
      (finally
        (set! js/globalThis.window old-window)
        (set! js/globalThis.document old-document)
        (set! js/globalThis.HTMLInputElement old-input-class)
        (set! js/globalThis.MutationObserver old-observer)))))

(deftest detached-login-field-does-not-leave-a-floating-icon
  (with-page
    (fn [{:keys [input mounted listeners]}]
      (icon/show-input-okp-icon input)
      (is (some? @mounted))
      (set! (.-isConnected input) false)
      ((get @listeners "resize"))
      (is (nil? @mounted) "The icon is removed when its login field leaves the page")
      (is (empty? @listeners) "Position listeners are released with the stale icon"))))

(deftest hidden-login-field-does-not-leave-a-floating-icon
  (with-page
    (fn [{:keys [input mounted visible? listeners]}]
      (icon/show-input-okp-icon input)
      (reset! visible? false)
      ((get @listeners "scroll"))
      (is (nil? @mounted) "Hiding a login form also removes its icon")
      (is (empty? @listeners)))))

(deftest removing-a-form-cleans-up-without-a-scroll-or-resize
  (with-page
    (fn [{:keys [input mounted observers listeners]}]
      (icon/show-input-okp-icon input)
      (set! (.-isConnected input) false)
      ((:callback (first @observers)) #js [] nil)
      (is (nil? @mounted))
      (is (empty? @listeners))
      (is @(:disconnected? (first @observers))))))

(deftest hiding-an-ancestor-cleans-up-without-removing-the-input
  (with-page
    (fn [{:keys [input visible? mounted observers]}]
      (icon/show-input-okp-icon input)
      (reset! visible? false)
      ((:callback (first @observers)) #js [] nil)
      (is (nil? @mounted))
      (let [[_ options] (first @(:targets (first @observers)))]
        (is (true? (.-subtree options)))
        (is (true? (.-childList options)))
        (is (= ["class" "style" "hidden"] (vec (.-attributeFilter options))))))))

(deftest a-visible-form-remains-usable-after-unrelated-page-changes
  (with-page
    (fn [{:keys [input mounted observers listeners]}]
      (icon/show-input-okp-icon input)
      ((:callback (first @observers)) #js [] nil)
      (is (some? @mounted) "An error or other DOM update must not suppress a still-visible login")
      (is (= 2 (count @listeners)))
      (is (false? @(:disconnected? (first @observers)))))))

(deftest replacing-an-icon-releases-its-old-observer-and-position-listeners
  (with-page
    (fn [{:keys [input mounted observers listeners]}]
      (icon/show-input-okp-icon input)
      (let [old-observer (first @observers)]
        (icon/show-input-okp-icon input)
        (is @(:disconnected? old-observer))
        (is (= 2 (count @listeners)) "Only the current icon's listeners remain")
        (set! (.-isConnected input) false)
        ((:callback old-observer) #js [] nil)
        (is (some? @mounted) "A retired callback cannot remove a replacement icon")
        ((:callback (second @observers)) #js [] nil)
        (is (nil? @mounted))))))

(deftest nested-shadow-roots-are-observed-along-with-the-document
  (with-page
    (fn [{:keys [input document observers]}]
      (let [outer #js {:host #js {:getRootNode (fn [] document)}}
            inner #js {:host #js {:getRootNode (fn [] outer)}}]
        (set! (.-getRootNode input) (fn [] inner))
        (icon/show-input-okp-icon input)
        (let [targets (mapv first @(:targets (first @observers)))]
          (is (= 3 (count targets)))
          (is (identical? (.-documentElement document) (nth targets 0)))
          (is (identical? inner (nth targets 1)))
          (is (identical? outer (nth targets 2))))))))
