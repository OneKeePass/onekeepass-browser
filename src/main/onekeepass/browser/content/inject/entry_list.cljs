(ns onekeepass.browser.content.inject.entry-list
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.mui-components
    :as m
    :refer [mui-avatar mui-box mui-list mui-list-item mui-list-item-avatar
            mui-list-item-text mui-list-subheader mui-stack
            mui-typography]]
   [onekeepass.browser.common.utils :as u :refer [okp-println]]
   [onekeepass.browser.content.context :as context]
   [onekeepass.browser.content.inject.custom-element :as ce]
   [onekeepass.browser.content.inject.db-icons :as db-icons :refer [entry-icon]]
   [reagent.core :as r]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.content.inject.listener-store :as listener-store]))

#_(defonce ^:private host-element-class "okp-entry-list")

(def ^:private entry-list-host-element-class ce/OKP_ENTRY_LIST_CLASS)

(defn- remove-host [host-element-class]
  (ce/remove-shadowdom-host-element-by-class host-element-class)
  (okp-println "Entry list tag is removed"))

;; This is based on the example mui code in 
;; https://mui.com/material-ui/react-list/#sticky-subheader
(defn- dbs-entry-list-content []
  (let [matched-entries @(messaging-event/matched-entries)
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
                          [mui-list-subheader {:sx {:background-color "rgba(25, 118, 210, 0.08)"}} [mui-typography {:variant "h6"} db-name]]
                          (for [{:keys [uuid title secondary-title icon-id]} entry-summaries]
                            ^{:key uuid} [mui-list-item {;; :divider true
                                                         :button "true"
                                                         :value uuid
                                                         :on-click (fn []
                                                                     #_(okp-println "Uuid is " uuid)
                                                                     (messaging-event/send-entry-selected db-key uuid)
                                                                     #_(remove-host))
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
  (when-not @(messaging-event/entry-selection-done)
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

(defn- root-component
  "This is called to render mui when the shadow dom is created"
  [emotion-cache custom-theme]
  [m/mui-cache-provider {:value emotion-cache}
   [m/mui-theme-provider {:theme custom-theme}
    [main-content]]])

(defn- calculate-list-postions [input-element]
  (let [rect (j/call input-element :getBoundingClientRect)
        {:keys [left width bottom]} (j/lookup rect)
        width (if (> width 550) 550 width)
        width (if (< width 350) 350 width)]
    {:top (str (+ bottom js/window.scrollY) "px")
     :left (str (+ left js/window.scrollX) "px")
     :width (str width "px")
     :display "block"}))

(defn- close-entry-list-on-input-blur
  "Called to hide the when the input element loses the focus"
  [host-element host-element-class input-element]
  (.addEventListener input-element "blur"
                     (fn []
                       (js/window.setTimeout
                        (fn []
                          (remove-host host-element-class)
                          #_(ce/hide-host-element host-element)) 150))))

(defn- set-host-position [host-element {:keys [top left width]}]
  (let [host-element-style (j/get host-element :style)]
    (j/assoc! host-element-style :top top)
    (j/assoc! host-element-style :left left)
    (j/assoc! host-element-style :width width)))

(defn- entry-list-outside-click-handler [input-element host-element-class]
  (fn [e]
    (let [cp (j/call e :composedPath)]
      (when-not (or (.includes cp (ce/host-element-by-class host-element-class)) (.includes cp input-element))
        #_(okp-println "outside-clicked-listener will call entry-list host removal for input name" (j/get input-element :name))
        (remove-host host-element-class)
        (ce/remove-shadowdom-host-element-by-class ce/OKP_INPUT_ICON_CLASS)))))

#_(defn- show-input-list [input-element]
    ;; first we remove any existing shadow dom for this list
    (remove-host)
    (let [host-styles (calculate-list-postions input-element)
          _ (u/okp-println "Calculated host-styles" host-styles)
          host-element (ce/create-entry-list-shadow-dom host-element-class host-styles root-component)]

      (listener-store/add-entry-list-outside-clicked-listener (entry-list-outside-click-handler input-element))
      (set-host-position host-element host-styles)))

(defn- show-input-list [input-element host-element-class]
  ;; first we remove any existing shadow dom for this list
  (remove-host host-element-class)
  (let [host-styles (calculate-list-postions input-element)
        ;; _ (u/okp-println "Calculated host-styles" host-styles)
        {:keys [component-root]} (ce/create-entry-list-shadow-dom host-element-class host-styles root-component)]

    (listener-store/add-entry-list-outside-clicked-listener (entry-list-outside-click-handler input-element host-element-class))
    (set-host-position component-root host-styles)))

;; Registered as a callback fn
(defn show-entry-summaries
  "A callback fn that is called when the content scripts receives the message SHOW_ENTRY_LIST from background
   The background sends this message for the content's earlier message GET_ENTRY_LISt
   "
  []
  (let [focused-input js/document.activeElement]
    (u/okp-console-log "Focused input is " focused-input)

    (when-some [username-input (context/username-in-page)]
      (when (= focused-input username-input)
        (u/okp-console-log "focused-input=username-input" (= focused-input username-input))
        (show-input-list username-input entry-list-host-element-class)))

    (when-some [password-input (context/password-in-page)]
      (when (= focused-input password-input)
        (u/okp-console-log "focused-input=password-input" (= focused-input password-input))
        (show-input-list password-input entry-list-host-element-class)))))



;;;;;;;;;;;;;;;;;;;;;;;;;  iframe based ;;;;;;;;;;;;;;;;

