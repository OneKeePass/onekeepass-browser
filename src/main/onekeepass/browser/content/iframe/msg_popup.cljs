(ns onekeepass.browser.content.iframe.msg-popup
  (:require
   [onekeepass.browser.common.connection-states]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [onekeepass.browser.common.mui-components
    :as m
    :refer [mui-box mui-stack mui-typography]]
   [onekeepass.browser.common.utils :as u]))

(def ^:private MESSAGE_BOX_HEIGHT 100)

(defn- no-db-avilable-info []
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color "warning.dark" :sx {}}
    ;; "No browser connection enabled database is opened. Please open a browser connection enabled database and then try"
    (u/lstr "noDbAvailable")]])

(defn- no-matching-entries-info []
  (let [url @(iframe-content-messaging/no-matching-recent-url)]
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

(defn- no-matching-passkeys-info []
  [mui-stack {:sx {:text-align "center" :align-items "center"}}
   [mui-typography {:variant "body1" :color "warning.dark" :sx {}}
    "No saved passkeys found for this site in OneKeePass."]])

(defn msg-box-main-content []
  (let [no-browser-enabled-db @(iframe-content-messaging/no-browser-enabled-db)
        no-matching-entries   @(iframe-content-messaging/no-matching-entries)
        no-matching-passkeys  @(iframe-content-messaging/no-matching-passkeys)
        background-error      @(iframe-content-messaging/background-error)]
    ;; (u/okp-println "=== no-browser-enabled-db no-matching-entries background-error" no-browser-enabled-db no-matching-entries background-error)

    (when (or no-browser-enabled-db no-matching-entries no-matching-passkeys background-error)
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
        #_[mui-box {:component "header" :sx {:bgcolor "primary.main" :min-height 30}}
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

           no-matching-passkeys
           [no-matching-passkeys-info]

           (not (nil? background-error))
           [background-error-info background-error])]]])))




(comment
  (defn msg-box-main-content []
    #_(u/okp-println "=== msg-box-main-content is called..." @(iframe-content-messaging/no-browser-enabled-db))

    (let [no-browser-enabled-db @(iframe-content-messaging/no-browser-enabled-db)
          ;; no-matching-entries @(iframe-content-messaging/no-matching-entries)
          ;; background-error @(iframe-content-messaging/background-error)
          ]
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
                 :padding "2px 2px 2px 2px"}}

       [mui-stack  {:sx
                    {:minHeight MESSAGE_BOX_HEIGHT ;; This is required
                     }}
        [mui-typography {:variant "body1" :color "error" :sx {}}
         "The txt here"]

        #_(cond

            no-browser-enabled-db
            [no-db-avilable-info]

            no-matching-entries
            [no-matching-entries-info]

            (not (nil? background-error))
            [background-error-info background-error])

        #_[mui-box {:component "main" :flexGrow 1  :sx {:align-content "center"}}
           #_(cond

               no-browser-enabled-db
               [no-db-avilable-info]

               no-matching-entries
               [no-matching-entries-info]

               (not (nil? background-error))
               [background-error-info background-error])]]])

    #_(let [no-browser-enabled-db @(iframe-content-messaging/no-browser-enabled-db)
            no-matching-entries @(iframe-content-messaging/no-matching-entries)
            background-error @(iframe-content-messaging/background-error)]
        ;; (u/okp-println "=== no-browser-enabled-db no-matching-entries background-error" no-browser-enabled-db no-matching-entries background-error)
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
          #_[mui-box {:component "header" :sx {:bgcolor "primary.main" :min-height 30}}
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
                                               :min-height 35}}]]]
        #_(when (or no-browser-enabled-db no-matching-entries background-error)
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
              #_[mui-box {:component "header" :sx {:bgcolor "primary.main" :min-height 30}}
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
                                                   :min-height 35}}]]]))))