(ns onekeepass.browser.content.iframe.entry-list-popup
  "Sample one"
  (:require
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_ENTRY_LIST]]
   [onekeepass.browser.common.mui-components
    :as m
    :refer [mui-avatar mui-box mui-list mui-list-item mui-list-item-avatar
            mui-list-item-text mui-list-subheader mui-stack mui-typography]]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [onekeepass.browser.content.inject.db-icons :as db-icons :refer [entry-icon]]
   [reagent.core :as r]
   [reagent.dom :as rd]))

(defn- delayed-close-popup []
  (js/window.setTimeout
   (fn [] (iframe-content-messaging/close-entry-list-popup)) 500))


;; This is based on the example mui code in 
;; https://mui.com/material-ui/react-list/#sticky-subheader
(defn- dbs-entry-list-content []
  (let [matched-entries @(iframe-content-messaging/matched-entries)
        ;; At least we should have one db with non empty 'entry-summaries' as this is taken care in background
        non-empty-matched-entries (filter (fn [{:keys [entry-summaries]}] (not-empty entry-summaries)) matched-entries)]
    [mui-list {:sx {:width "100%"
                    :maxWidth "600px"
                    :maxHeight "300px"
                    :bgcolor "background.paper"
                    :position "relative"
                    :overflow "auto"
                    "& ul" {:padding 0}}
               :subheader (r/as-element [:li])}
     (doall
      (for [{:keys [db-name db-key entry-summaries]} non-empty-matched-entries]
        ^{:key db-name} [:li
                         [:ul {}
                          [mui-list-subheader {:sx {:background-color "rgba(25, 118, 210, 0.08)"}}
                           [mui-typography {:variant "h6"} db-name]]

                          (for [{:keys [uuid title secondary-title icon-id]} entry-summaries]
                            ^{:key uuid} [mui-list-item {;; :divider true
                                                         :button "true"
                                                         :value uuid
                                                         :on-click (fn []
                                                                     (iframe-content-messaging/send-entry-selected db-key uuid)
                                                                     (delayed-close-popup))
                                                         ;; :selected (if (= @selected-id history-index) true false)
                                                         ;; :secondaryAction (when (= @selected-id (:uuid item)) (r/as-element [mui-icon-button {:edge "end"} [mui-icon-more-vert]]))
                                                         }
                                          [mui-list-item-avatar
                                           [mui-avatar [entry-icon icon-id]]]
                                          [mui-list-item-text
                                           {:primary title
                                            :secondary  secondary-title}]])]]))]))


(defn- main-content  []
  #_(okp-println "@(messaging-event/entry-selection-done) is " @(messaging-event/entry-selection-done))
  (when-not false #_@(messaging-event/entry-selection-done)
            [mui-box {:sx {:min-height 100
                           :bgcolor  "background.paper"
                           :border-color "black"
                           :border-style "solid"
                           :border-width ".5px"
                           ;;:background "background.paper" ;; This creates transparent background 
                           :boxShadow 0
                           :borderRadius 1
                           :margin "5px"
                           :padding "2px 2px 2px 2px"}
                      ;; :tabIndex 0
                      ;; :ref (fn [ref]
                      ;;        (when ref
                      ;;          (okp-console-log "Calling mui box of Entry list focus"  (j/get ref :focus))
                      ;;          (j/call ref :focus)))
                      ;; :onBlur (fn [e]
                      ;;           (u/okp-println "In Entry list onblur...")
                      ;;           #_(u/okp-console-log "entry list box onbur e is " (j/get e :relatedTarget))
                      ;;           (when-not (j/get e :relatedTarget)
                      ;;             (remove-host)))
                      }
             [mui-stack {:sx {}}
              [mui-stack {:sx {}}
               [mui-stack {:sx {:width "100%"}}
                [dbs-entry-list-content]]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Need to move to a share ns

(defn- send-size-to-host
  "The iframe body's content size changes are observerd and communicated to content script 
  side so that the iframe size is adjusted accordingly
  "
  []
  (let [body (.-body js/document)
        ;; _ (u/okp-console-log "== body is " body)
        ;; Measure the full scrollable content
        width  (.-scrollWidth body)
        height (.-scrollHeight body)]

    #_(u/okp-println "Body scrollWidth" width ",scrollHeight" height)

    ;; Send to parent window (the host page)
    (js/window.parent.postMessage
     #js {:type RESIZE_IFRAME_ENTRY_LIST
          ;; :source "entry-list"
          :width width
          :height height}
     "*")))

(defn- init-auto-resize []
  ;; Setup a ResizeObserver to watch the body for changes
  (let [observer (js/ResizeObserver.
                  (fn [_entries]
                    ;; Debounce slightly if your UI updates rapidly
                    (send-size-to-host)))]

    (.observe observer (.-body js/document))

    ;; Also trigger once on load just in case
    (send-size-to-host)))

;;;;;;;;

(defn init []
  #_(u/okp-println "onekeepass.browser.content.iframe.entry-list-popup")

  ;; Init the Iframe <-> Content communication channel 
  (iframe-content-messaging/init-content-connection)

  ;; setup the size observer when the app mounts
  (init-auto-resize)

  ;; There is 'div' element with ID 'app' in entry_list_popup.html
  ;; Mount the mui
  (rd/render [main-content] (.getElementById  ^js/Window js/document "app")))











;;;;;;;;;;;;;;;;;
(comment
  ;; We use this flag to hide the complete popup window showing
  (def hide-flag (r/atom false))

  ;; This component is mounted and shown inside the iframe loaded html page. 
  ;; See the html file 'extension-dist/content_popup.html' where this ns 'init' is called
  ;; in '<script type="module" src="js/content-popup.js"></script>'

  (defn- main-comp  []
    [mui-box {:sx {:display (if @hide-flag "none" "block")}}
     [mui-paper {:sx {:width "100%" :maxWidth 360 :min-height 100 :bgcolor "background.paper"}}

      [mui-stack
       [mui-typography {:variant "h6"}  " IFRAME  message  will come here"]


       ;; send-remove-host-elemment-message - not yet implemented
       ;; To remove the shadow dom that hosts the iframe, 
       ;; we need  to send a remove message to backend and which in turn sends another message to the content script
       ;; which can then remove the iframe popup by removing the host element after class based query

       [mui-button {:sx {:mt 2}
                    :size "small"
                    :variant "contained"
                    :on-click (fn []
                                (js/window.parent.postMessage #js {:type "CLOSE_POPUP"} "*")
                                #_(reset! hide-flag true)
                                #_(send-remove-host-elemment-message))} "Close"]]]])

  ;; When user clicks "Done" inside the popup
  #_(defn on-click-done []
      (js/window.parent.postMessage #js {:type "CLOSE_POPUP"} "*"))

  ;; Called when the compiled 'js/entry-list-popup.js' is called
  (defn init []
    (u/okp-println "onekeepass.browser.content.iframe.entry-list-popup init is called")
    (rd/render [main-comp] (.getElementById  ^js/Window js/document "app")))

;;;;;;;;;;;;;;;;;;;;
  )