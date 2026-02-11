(ns onekeepass.browser.content.inject.listener-store
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.utils :as u]))

(def ^:private listners-store (atom {}))

(defn add-input-named-event-listener [input listener-name new-listener]
  (let [previous-listener (get-in @listners-store [input listener-name])]
    (when-not previous-listener
      #_(u/okp-println "new-listener" new-listener "with name" listener-name "will be added")
      (j/call input :addEventListener listener-name new-listener)
      (swap! listners-store assoc-in [input listener-name] new-listener)))

  #_(let [previous-listener (get-in @listners-store [input listener-name])]
      (u/okp-println "previous-listener" previous-listener "with name" listener-name "will be removed")
      (j/call input :removeEventListener listener-name previous-listener)
      (j/call input :addEventListener listener-name new-listener)
      (swap! listners-store assoc-in [input listener-name] new-listener)))

(defn add-entry-list-outside-clicked-listener [new-listener]
  (let [previous-listener (get-in @listners-store [:entry-list :doc-click])]
    (js/document.removeEventListener "click" previous-listener)
    (js/document.addEventListener "click" new-listener)
    (swap! listners-store assoc-in [:entry-list :doc-click] new-listener)))

;;;;;;;; 

#_(defn add-focus-event-listener [input new-listener]
    (let [previous-focus-listener (get-in @listners-store [input :focus])]
      (u/okp-println "previous-focus-listener" previous-focus-listener)
      (j/call input :removeEventListener "focus" previous-focus-listener)
      (j/call input :addEventListener "focus" new-listener)

      (swap! listners-store assoc-in [input :focus] new-listener)))

#_(defn add-input-event-listener [input new-listener]
    (let [previous-listener (get-in @listners-store [input :input])]
      (u/okp-println "previous-input-listener " previous-listener)
      (j/call input :removeEventListener "input" previous-listener)
      (j/call input :addEventListener "input" new-listener)
      (swap! listners-store assoc-in [input :input] new-listener)))

