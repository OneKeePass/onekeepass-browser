(ns onekeepass.browser.content.inject.message-popup
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.connection-states :refer [NATIVE_APP_NOT_AVAILABLE
                                                        APP_CONNECTED
                                                        APP_DISCONNECTED
                                                        PROXY_TO_APP_CONNECTED
                                                        PROXY_TO_APP_NOT_CONNECTED]]
   [onekeepass.browser.common.mui-components
    :as m
    :refer [mui-box mui-button mui-stack mui-typography]]
   [onekeepass.browser.common.utils :as u ]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.content.events.popup :as popup-event]
   [onekeepass.browser.content.inject.custom-element :as ce]
   [onekeepass.browser.content.inject.dragging-support :as dragging-support :refer [handle-drag-start]]))

(def ^:private host-element-class ce/OKP_DRAGGABLE_BOX_CLASS)

;; The maximum size for a Chrome extension popup is 800 pixels in width and 600 pixels in height.
(def ^:private POPUP_ACTION_BOX_WIDTH 400)
(def ^:private POPUP_ACTION_BOX_HEIGHT 300)

(def ^:private MESSAGE_BOX_HEIGHT 100)

(defn- remove-popup-box-host []
  ;; Need to reset the dragging state before while removing host element
  (try
    (dragging-support/remove-added-mouse-events (ce/container-root-element host-element-class))
    (catch :default _e
      nil
      #_(okp-println "Error while resetting dragging state" e)))

  (try
    (ce/remove-shadowdom-host-element-by-class host-element-class)
    (catch :default _e
      nil
      #_(okp-println "Error while removing existing popup box host element" e))))

(defn- remove-popup-box-host-with-delay []
  #_(u/okp-println "Remove popup box host with delay called")
  (js/window.setTimeout
   (fn [] (remove-popup-box-host)) 500))

(declare remove-message-box-host)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; An example to show how to close the popup automatically when user clicks on the page outside the popup. This works generally. 
;; However, this did not work, we click on another app and the popup remains open. This method is not used. 
;; By setting onBlur event on the Box componnet, the same effect is achived in all cases
#_(defn outside-clicked-listener []
    (js/document.addEventListener
     "click" (fn [e]
               #_(okp-console-log "All composepath items " (j/call e :composedPath))
               (when-not  (j/call (j/call e :composedPath) :includes (mui-support/host-element-by-class host-element-class))
                 (remove-host)))))

(defn- drag [e]
  #_(okp-println "Mouse down is called " e)
  (handle-drag-start (ce/container-root-element host-element-class) e))

(defn- mouse-leave [_e]
  (j/assoc-in! (ce/container-root-element host-element-class) [:style :cursor] "default"))

(defn- mouse-enter [_e]
  #_(u/okp-console-log "Container element" (ce/container-root-element host-element-class))
  (j/assoc-in! (ce/container-root-element host-element-class) [:style :cursor] "grabbing"))

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
                            (messaging-event/send-reconnect-app)

                            #_(u/okp-println "js/window.setTimeout before remove-popup-box-host in Connect")
                            (js/window.setTimeout
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
                            (messaging-event/send-start-association)
                            (js/window.setTimeout
                             (fn [] (remove-popup-box-host)) 500))} "Continue"]])

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

                            ;; Reset the association-rejected flag in app-db in content's app-db
                            (js/window.setTimeout
                             (fn []
                               (remove-popup-box-host)) 500))} "Ok"]])

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
                            #_(okp-console-log "on-click e is " e)
                            (j/call e :preventDefault)
                            (messaging-event/send-reconnect-app)
                            (js/window.setTimeout
                             (fn [] (remove-popup-box-host)) 500))} "Connect"]])

(defn msg-part [msg & {:keys [color]
                       :or {color "primary.main"}}]
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color color :sx {}}
    msg]])

(defn- popup-center-content []
  (let [{:keys [connection-state association-id association-rejected]} @(popup-event/popup-action-info)]

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
              {:bgcolor  "background.paper"

               :border-color "black"
               :border-style "solid"
               :border-width ".5px"
               ;;:background "background.paper" ;; This creates transparent background 
               :boxShadow 0
               :borderRadius 1
               :margin "5px"
               :padding "2px 2px 2px 2px"}

              :ref (fn [ref]
                     (when ref
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
                        (when-not (j/get e :relatedTarget)
                          (remove-popup-box-host)))};; "background.paper"
     [mui-stack  {:sx
                  ;; The min height is required
                  {:minHeight POPUP_ACTION_BOX_HEIGHT}}

      ;; Header part
      [mui-box {:component "header"
                :sx {:bgcolor "primary.main"
                     :min-height 30}}
       [mui-stack {:direction "row" :justifyContent "space-between"}

        [mui-stack {:sx {:height "100%"
                         :width "90%" :text-align "center"}
                    :onMouseEnter mouse-enter
                    :onMouseDown drag
                    :onMouseLeave mouse-leave}
         [mui-typography {:variant "h6" :sx {:color "secondary.contrastText"}}
          "OneKeePass"]]

        [m/mui-icon-close {:sx {:color "secondary.contrastText"}
                           :fontSize "medium"
                           :on-click (fn []
                                       (u/okp-println "Remove host called - on-click")
                                       (remove-popup-box-host))}]]]
      ;; Body part
      [mui-box {:component "main" :flexGrow 1  :sx {;;:bgcolor "green"

                                                    :align-content "center"}}
       [popup-center-content]
       #_[mui-stack {:sx {}}
          [mui-typography {:variant "h6"}
           "The message from backend will come here"]]]

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
                        (messaging-event/send-redetect-fields)
                        (remove-popup-box-host-with-delay))}
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

(defn- popup-root-component [emotion-cache custom-theme]
  [m/mui-cache-provider {:value emotion-cache}
   [m/mui-theme-provider {:theme custom-theme}
    [popup-main-content]]])

;; This is registered as callback fn that is called while handling a message from background script
;; or content to content message
;; See the init fn in src/main/onekeepass/browser/content/core.cljs where it is registered as handlers

;; Expected to be called in frame id 0 from background script
(defn popup-action-show
  "Called when an appropriate message is received and required data are set in app-db. This creates a custom element based 
  shadow dom and mui root is rendered
   "
  []
  (remove-message-box-host)
  (remove-popup-box-host)
  (u/okp-println "Remove host called - popup-action-show init")
  (let [{:keys [top right]} (dragging-support/popup-initial-position)]
    (ce/create-draggable-box-shadow-dom host-element-class {:top top :right right :width POPUP_ACTION_BOX_WIDTH} popup-root-component)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private msg-box-host-element-class ce/OKP_MESSAGE_BOX_CLASS)

;; for now the dragging support is used for both popup-action-show and msg-box-show
;; TODO: May need to use a single popup instead of these two
(defn- msg-box-drag [e]
  #_(okp-println "Mouse down is called " e)
  (handle-drag-start (ce/container-root-element msg-box-host-element-class) e))

(defn-  msg-box-mouse-leave [_e]
  (j/assoc-in! (ce/container-root-element msg-box-host-element-class) [:style :cursor] "default"))

(defn-  msg-box-mouse-enter [_e]
  (j/assoc-in! (ce/container-root-element msg-box-host-element-class) [:style :cursor] "grabbing"))

(defn- remove-message-box-host []
  (try
    (ce/remove-shadowdom-host-element-by-class msg-box-host-element-class)
    (catch :default _e
      nil
      #_(okp-println "Error while removing existing message box host element" e))))

(defn- no-db-avilable-info []
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color "warning.dark" :sx {}}
    ;; "No browser connection enabled database is opened. Please open a browser connection enabled database and then try"
    (u/lstr "noDbAvailable")]])

(defn- no-matching-entries-info []
  (let [url @(messaging-event/no-matching-recent-url)]
    [mui-stack {:sx {:text-align "center" :align-items "center"}}
     [mui-typography {:variant "body1" :color "warning.dark" :sx {}}
      (u/lstr "noMatchingEntry")]

     [mui-stack {:sx {:mt 1 :mb 1}}
      [mui-typography {:variant "body1" :color "primary.main" :sx {}}
       url]
      [mui-typography {:variant "body1" :color "primary.main" :sx {}}
       "Please add this url to a relevant login entry"]]]))

(defn- background-error-info [error-message]
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color "error" :sx {}}
    error-message]])

(defn- msg-box-main-content []
  (let [no-browser-enabled-db @(messaging-event/no-browser-enabled-db)
        no-matching-entries @(messaging-event/no-matching-entries)
        background-error @(messaging-event/background-error)]
    (when (or no-browser-enabled-db no-matching-entries background-error)
      [mui-box {:sx
                {:bgcolor  "background.paper"
                 :border-color "black"
                 :border-style "solid"
                 :border-width ".5px"
                 ;;:background "background.paper" ;; This creates transparent background 
                 :boxShadow 0
                 :borderRadius 1
                 :margin "5px"
                 :padding "2px 2px 2px 2px"}}
       [mui-stack  {:sx
                    {:minHeight MESSAGE_BOX_HEIGHT ;; This is required
                     }}

        ;; Header part
        [mui-box {:component "header" :sx {:bgcolor "primary.main" :min-height 30}}
         [mui-stack {:direction "row" :justifyContent "space-between"}

          [mui-stack {:sx {:height "100%"
                           :width "90%" :text-align "center"}
                      :onMouseEnter msg-box-mouse-enter
                      :onMouseDown msg-box-drag
                      :onMouseLeave msg-box-mouse-leave}
           [mui-typography {:variant "h6" :sx {:color "secondary.contrastText"}}
            "OneKeePass"]]

          [m/mui-icon-close {:sx {:color "secondary.contrastText"}
                             :fontSize "medium"
                             :on-click (fn []
                                         #_(okp-println "Remove host called - on-click")
                                         (messaging-event/reset-background-error)
                                         (remove-message-box-host))}]]]
        ;; Body part
        [mui-box {:component "main" :flexGrow 1  :sx {:align-content "center"}}
         (cond

           no-browser-enabled-db
           [no-db-avilable-info]

           no-matching-entries
           [no-matching-entries-info]

           (not (nil? background-error))
           [background-error-info background-error])]

        ;; Footer part comes here if we need it
        #_[mui-box {:component "footer" :sx {:bgcolor m/color-grey-200
                                             :min-height 35}}]]])))

(defn- msg-box-root-component [emotion-cache custom-theme]
  [m/mui-cache-provider {:value emotion-cache}
   [m/mui-theme-provider {:theme custom-theme}
    [msg-box-main-content]]])


;; This is registered as callback fn that is called while handling a message 
;; TODO: Should we  use a single popup instead of these two separate 
;; popup-action-show and msg-box-show?
(defn msg-box-show
  "Called when an appropriate message is received and required data are set in app-db. This creates a custom element based 
  shadow dom and mui root is rendered
   "
  []
  (remove-popup-box-host)
  (remove-message-box-host)
  #_(okp-println "Remove host called - msg-box-show init")
  (let [{:keys [top right]} (dragging-support/popup-initial-position)]
    (ce/create-message-box-shadow-dom msg-box-host-element-class {:top top :right right :width POPUP_ACTION_BOX_WIDTH} msg-box-root-component)))
  