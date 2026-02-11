(ns onekeepass.browser.content.iframe.message-popup
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.connection-states :refer [APP_CONNECTED
                                                        APP_DISCONNECTED
                                                        NATIVE_APP_NOT_AVAILABLE
                                                        PROXY_TO_APP_CONNECTED
                                                        PROXY_TO_APP_NOT_CONNECTED]]
   [onekeepass.browser.common.message-type-names :refer [RESIZE_IFRAME_MAIN_POPUP
                                                         RESIZE_IFRAME_MSG_POPUP]]
   [onekeepass.browser.common.mui-components
    :as m
    :refer [mui-box mui-button mui-stack mui-typography]]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [onekeepass.browser.content.iframe.msg-popup :as msg-popup]
   [reagent.dom :as rd]))

;; The maximum size for a Chrome extension popup is 800 pixels in width and 600 pixels in height.
(def ^:private POPUP_ACTION_BOX_WIDTH 400)
(def ^:private POPUP_ACTION_BOX_HEIGHT 300)

(def ^:private MESSAGE_BOX_HEIGHT 100)

(defn- delayed-close-popup []
  (js/window.setTimeout
   (fn [] (iframe-content-messaging/close-popup)) 500))

(defn- reconnect-info []
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color "error" :sx {}}
    (u/lstr "appIsNotRunning")]
   [mui-button {:sx {:mt 2}
                :size "small"
                :variant "contained"
                :tabIndex 0 ;; Do we need this?
                :on-click (fn [e]
                            #_(okp-console-log "on-click e is " e)

                            (j/call e :preventDefault)
                            (iframe-content-messaging/send-bg-reconnect-app)
                            (delayed-close-popup)

                            #_(messaging-event/send-reconnect-app)
                            #_(u/okp-println "js/window.setTimeout before remove-popup-box-host in Connect")
                            #_(js/window.setTimeout
                               (fn [] (remove-popup-box-host)) 500))} "Connect"]])

(defn- start-associate-info []
  [mui-stack {:sx {;; :text-align "center" 
                   :align-items "center"}}
   #_[mui-typography {:variant "body1" :color "error" :sx {}}
      ;; "Extension requires user permission in the OneKeePass App"
      "OneKeePass browser is ready to use the OneKeePass App. You need to allow the browser extension in the OneKeePass App to proceed."
      #_(u/lstr "startAssociation")]

   [mui-typography {:variant "body1" :color "error" :sx {:ml 3}}
    ;; "Extension requires user permission in the OneKeePass App"
    "OneKeePass browser is ready to use the OneKeePass App"
    #_(u/lstr "startAssociation")]

   [mui-typography {:variant "body1" :color "error" :sx {:ml 3 :mt 1}}
    ;; "Please check the OneKeePass App for association request"
    "Please check the OneKeePass App for association request"]

   [mui-button {:sx {:mt 2}
                :size "small"
                :variant "contained"
                :tabIndex 0 ;; Do we need this?
                :on-click (fn [e]
                            #_(okp-console-log "on-click e is " e)
                            (j/call e :preventDefault)

                            (iframe-content-messaging/send-bg-start-association)
                            (delayed-close-popup))} "Continue"]])

(defn- association-rejected-info []
  #_(okp-println "In association-rejected-info")

  (js/window.focus)

  #_(okp-println "After window focus in association-rejected-info")

  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color "error" :sx {}}
    "User rejected connecting with OneKeePass App"]
   [mui-button {:sx {:mt 2}
                :size "small"
                :variant "contained"
                :tabIndex 0 ;; Do we need this?
                :on-click (fn [e]
                            #_(okp-console-log "on-click e is " e)
                            (j/call e :preventDefault)
                            (delayed-close-popup))} "Ok"]])

(defn- reconnect-info-1 []
  [mui-stack {:sx {;; :text-align "center" 
                   :align-items "center"}}
   [mui-typography {:variant "body1" :color "error" :sx {:m 1}}
    "Please install the latest platform specific OneKeePass App"]

   [mui-typography {:variant "body1" :color "error" :sx {:m 1}}
    "Need to enable the browser extension use in the app's settings"]

   [mui-typography {:variant "body1" :color "error" :sx {:m 1}}
    "Then you can connect the browser extension to the main App"]

   [mui-button {:sx {:mt 2}
                :size "small"
                :variant "contained"
                :tabIndex 0 ;; Do we need this?
                :on-click (fn [e]

                            (j/call e :preventDefault)
                            (iframe-content-messaging/send-bg-reconnect-app)
                            (delayed-close-popup)

                            #_(messaging-event/send-reconnect-app)
                            #_(js/window.setTimeout
                               (fn [] (remove-popup-box-host)) 500))} "Ok"]])

(defn msg-part [msg & {:keys [color]
                       :or {color "primary.main"}}]
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color color :sx {}}
    msg]])

(defn- popup-center-content []
  (let [{:keys [connection-state association-id association-rejected]} @(iframe-content-messaging/popup-action-info)]

    (u/okp-println "Popup action info is " connection-state association-id association-rejected)

    ;; The order of the cond clauses is important
    (cond

      (= connection-state NATIVE_APP_NOT_AVAILABLE)
      [reconnect-info-1]

      (or (= connection-state APP_DISCONNECTED)
          (= connection-state PROXY_TO_APP_NOT_CONNECTED))
      [reconnect-info]

      ;; If the connection to the app is established but the user has rejected the association request
      (and (= connection-state PROXY_TO_APP_CONNECTED) association-rejected)
      [association-rejected-info]

      ;; Association id is not set means the user has not yet granted permission in the OneKeePass App to the browser extension
      (and (= connection-state PROXY_TO_APP_CONNECTED) (nil? association-id))
      [start-associate-info]

      (or (= connection-state PROXY_TO_APP_CONNECTED)
          (= connection-state APP_CONNECTED))
      [msg-part (u/lstr "connectedToApp")]

      :else
      [msg-part
       (str "Unknown state" connection-state)])))

(defn- popup-main-content []
  #_(okp-println "Red is " m/color-red-600)
  (let [timer (atom nil)]
    [mui-box {:sx
              {:bgcolor "background.paper"
               ;;:width "400px"
               :border-color "black"
               :border-style "solid"
               :border-width ".5px"
               ;;:background "background.paper" ;; This creates transparent background 
               :boxShadow 0
               :borderRadius 1
               :margin "5px"
               :padding "2px 2px 2px 2px"}

              :ref (fn [ref]
                     #_(when ref
                         #_(okp-console-log "Calling mui box focus"  (j/get ref :focus))
                         ;; This way of setting did not allow to enter any input to username or password field. 
                         ;; This is particularly problem when we do not have any credential entry in OKP app
                         ;; Instead we close the popup box by timeout
                         (reset! timer (js/window.setTimeout
                                        (fn []
                                          #_(okp-console-log "remove-popup-box-host is called one timeout")
                                          (remove-popup-box-host))
                                        7000))
                         #_(j/call ref :focus)))
              ;;  By default, a Box component is not focusable. To make it focusable, we need to set the tabIndex attribute
              :tabIndex 0

              ;; We need to remove the timer if user clicks on the popup box. 
              ;; The popup box will then be closed because of onBlur event
              :onFocus (fn [_e]
                         #_(okp-println "Box onFocus called, clearing timeout" @timer)
                         (when-not (nil? @timer)
                           (js/window.clearTimeout @timer)
                           (reset! timer nil)))
              :onBlur (fn [e]
                        ;; The onBlur event in a MUI Box component can fire when a contained button is pressed if the button 
                        ;; causes the Box to lose focus. This typically happens because the button is an interactive element 
                        ;; that takes focus away from the Box.
                        ;; we use the relatedTarget property of the event object to determine which element is gaining focus
                        ;; relatedTarget is not nil if the button is pressed
                        #_(when-not (j/get e :relatedTarget)
                            (remove-popup-box-host)))};; "background.paper"
     [mui-stack  {:sx
                  ;; The min height is required
                  {:minHeight POPUP_ACTION_BOX_HEIGHT}}

      ;; Header part
      #_[mui-box {:component "header"
                  :sx {:bgcolor "primary.main"
                       :min-height 30}}
         [mui-stack {:direction "row" :justifyContent "space-between"}

          [mui-stack {:sx {:height "100%"
                           :width "90%" :text-align "center"}
                      ;; :onMouseEnter mouse-enter
                      ;; :onMouseDown drag
                      ;; :onMouseLeave mouse-leave
                      }
           [mui-typography {:variant "h6" :sx {:color "secondary.contrastText"}}
            "OneKeePass"]]

          [m/mui-icon-close {:sx {:color "secondary.contrastText"}
                             :fontSize "medium"
                             :on-click (fn []
                                         (u/okp-println "Remove host called - on-click")
                                         #_(remove-popup-box-host))}]]]
      ;; Body part
      [mui-box {:component "main" :flexGrow 1  :sx {;;:bgcolor "green"

                                                    :align-content "center"}}
       [popup-center-content]]

      ;; Footer part if we need 
      ;; Add some action icons ?
      [mui-box {:component "footer" :sx {:bgcolor m/color-grey-200
                                         :min-height 35}}
       [m/mui-divider]
       [mui-stack {}
        [mui-stack {:direction "row" :spacing 2 :sx {:mr 1 :ml 1}}
         #_[m/mui-icon-settings-outlined]
         ;; Using tootip resulted the following waring in page console
         ;; Warning: Failed prop type: Invalid prop `container` supplied to `ForwardRef`, expected one of type [function].
         [m/mui-tooltip {:title "Redetect" :enterDelay 1000}
          [m/mui-icon-button
           {:edge "start" :color "inherit"
            :on-click (fn []
                        (iframe-content-messaging/send-bg-redetect-fields)
                        (delayed-close-popup))}
           [m/mui-icon-cyclone]]
          #_[:<>
             [m/mui-icon-button
              {:edge "start" :color "inherit"
               :on-click (fn []
                           (messaging-event/send-redetect-fields)
                           (remove-popup-box-host-with-delay))}
              [m/mui-icon-cyclone]]]
          #_[m/mui-icon-cyclone {:on-click (fn []
                                             (messaging-event/send-redetect-fields)
                                             (remove-popup-box-host-with-delay))}]]]]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Need to move to a share ns

(defn- send-size-to-host
  "The iframe body's content size changes are observerd and communicated to content script 
  side so that the iframe size is adjusted accordingly
  "
  [message-type]
  (let [body (.-body js/document)
        ;; Measure the full scrollable content
        width  (.-scrollWidth body)
        height (.-scrollHeight body)]

    ;; Send to parent window (the host page)
    (js/window.parent.postMessage
     #js {:type message-type
          ;;:source "message-popup"
          :width width
          :height height}
     "*")))

(defn- init-auto-resize [message-type]
  ;; Setup a ResizeObserver to watch the body for changes
  (let [observer (js/ResizeObserver.
                  (fn [_entries]
                    ;; Debounce slightly if your UI updates rapidly
                    (send-size-to-host message-type)))]

    (.observe observer (.-body js/document))

    ;; Also trigger once on load just in case
    (send-size-to-host message-type)))

;;;;;;;;

(defn init []
  #_(u/okp-println "onekeepass.browser.content.iframe.message-popup init is called")
  #_(u/okp-console-log "The mount point id is" (js/document.getElementById "app1"))

  (iframe-content-messaging/init-content-connection)
  ;; setup the size observer when the app mounts
  ;; (init-auto-resize)

  ;; The same init fn is used for both main message popup as well as for msg box popup

  ;; There is 'div' element with ID 'main_popup' in main_popup.html
  ;; There is 'div' element with ID 'msg_popup' in msg_popup.html

  (let [main-popup-mount (js/document.getElementById "main_popup")
        msg-popup-mount (js/document.getElementById "msg_popup")]
    #_(u/okp-console-log "main-popup-mount" main-popup-mount "msg-popup-mount" msg-popup-mount)
    (cond
      (and main-popup-mount msg-popup-mount)
      (u/okp-console-log "Error: Both main and msg popup mount points are found")

      main-popup-mount
      (do
        ;; setup the size observer when the app mounts
        (init-auto-resize RESIZE_IFRAME_MAIN_POPUP)
        ;; App UI is mounted 
        (rd/render [popup-main-content] main-popup-mount))

      msg-popup-mount
      (do
        ;; setup the size observer when the app mounts
        (init-auto-resize RESIZE_IFRAME_MSG_POPUP)
        ;; App UI is mounted 
        (rd/render [msg-popup/msg-box-main-content] msg-popup-mount))

      :else
      (u/okp-console-log "Error: Valid mount point element is not found")))

  #_(rd/render [popup-main-content] (.getElementById  ^js/Window js/document "app")))
