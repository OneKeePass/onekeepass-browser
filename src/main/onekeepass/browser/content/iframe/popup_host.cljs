(ns onekeepass.browser.content.iframe.popup-host
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.message-type-names :refer [CLOSE_ENTRY_LIST_POPUP
                                                         CLOSE_PASSKEY_CREATE_POPUP
                                                         CLOSE_POPUP
                                                         CLOSE_SETTINGS_POPUP
                                                         PASSKEY_CREATE_SUCCESS
                                                         RESIZE_IFRAME_ENTRY_LIST
                                                         RESIZE_IFRAME_MAIN_POPUP
                                                         RESIZE_IFRAME_MSG_POPUP
                                                         RESIZE_IFRAME_PASSKEY_CREATE
                                                         RESIZE_IFRAME_SETTINGS
                                                         SHOW_PASSKEY_ENTRIES
                                                         SHOW_PASSKEY_GROUPS
                                                         SHOW_SETTINGS_POPUP]]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.context :as context]
   [onekeepass.browser.content.iframe.dragging-support :as dragging-support]
   [onekeepass.browser.content.iframe.events.content-iframe-messaging :as content-iframe-messaging]
   [onekeepass.browser.content.inject.listener-store :as listener-store]
   [re-frame.core :refer [dispatch]]))

;; Same as in onekeepass.browser.content.iframe.message-popup
;; Need to move to common
(def ^:private POPUP_ACTION_BOX_WIDTH 400)

(def ^:private ^:constant OKP_ENTRY_LIST_TAG_NAME  "okp-entry-list-element")

(def ^:private ^:constant OKP_PASSKEY_LIST_TAG_NAME "okp-passkey-list-element")

(def ^:private ^:constant OKP_PASSKEY_CREATE_TAG_NAME "okp-passkey-create-element")

(def ^:private ^:constant OKP_SETTINGS_TAG_NAME "okp-settings-element")

(def ^:private ^:constant OKP_DRAGGBLE_BOX_TAG_NAME  "okp-draggable-box-element")

(def ^:private ^:constant OKP_MESSAGE_BOX_TAG_NAME "okp-message-box-element")

(def ^:private  entry-list-popup-iframe-element-store (atom nil))

(defn- form-element-id [tag-name]
  (str tag-name "-id"))

(defn- remove-host-element-by-id [element-id]
  #_(u/okp-console-log "The existing host element" (js/document.getElementById element-id) "for id" element-id)
  (when-let [host-element (js/document.getElementById element-id)]
    #_(u/okp-console-log "Removing host element" host-element)
    (j/call host-element :remove)))

#_(defn- remove-host-element-by-tag-name [element-tag]
    (remove-host-element-by-id (form-element-id element-tag)))

(defn- close-main-popup []
  (remove-host-element-by-id (form-element-id OKP_DRAGGBLE_BOX_TAG_NAME)))

(defn- close-msg-popup []
  (remove-host-element-by-id (form-element-id OKP_MESSAGE_BOX_TAG_NAME)))

(defn- close-entry-list-popup []
  (remove-host-element-by-id (form-element-id OKP_ENTRY_LIST_TAG_NAME)))

(defn- close-passkey-list-popup []
  (remove-host-element-by-id (form-element-id OKP_PASSKEY_LIST_TAG_NAME)))

(defn- cancel-passkey-list-popup
  "Called when the user explicitly closes the passkey list popup via the × button.
   Rejects the pending WebAuthn GET request before removing the popup."
  []
  (dispatch [:send-passkey-get-cancelled])
  (close-passkey-list-popup))

(defn- close-passkey-create-popup []
  (remove-host-element-by-id (form-element-id OKP_PASSKEY_CREATE_TAG_NAME)))

(defn- cancel-passkey-create-popup
  "Called when the user explicitly closes the passkey create popup via the × button.
   Rejects the pending WebAuthn CREATE request before removing the popup."
  []
  (dispatch [:send-passkey-create-cancelled])
  (close-passkey-create-popup))

(defn- close-settings-popup []
  (remove-host-element-by-id (form-element-id OKP_SETTINGS_TAG_NAME)))

(defn- create-close-button [on-close-fn]
  (let [btn (js/document.createElement "span")]
    (set! (.-innerText btn) "×")
    (set! (.-cssText (.-style btn))
          (str "cursor: pointer;"
               "float: right;"
               "font-weight: bold;"
               "font-size: 18px;"
               "color: #ccc;"
               "margin-right: 10px;"
               "line-height: 30px;" ;; Match handle height
               "user-select: none;"))

    ;; Add hover effect for better UX
    (.addEventListener btn "mouseover" #(set! (.-color (.-style btn)) "#fff"))
    (.addEventListener btn "mouseout"  #(set! (.-color (.-style btn)) "#ccc"))

    ;; The Click Handler
    (.addEventListener btn "click"
                       (fn [e]
                         ;; CRITICAL: Stop propagation so clicking 'X' doesn't start a Drag event
                         (.stopPropagation e)
                         (on-close-fn)))
    btn))

(defn- create-host-element 
  "Creates a host element for the shadowdom use"
  [tag-name host-styles]
  (let [;; {:keys [left top]} host-styles
        {:keys [right left top]} host-styles
        _ (when (and right left) (u/okp-console-log "Warning: Both left and right positions are passed"))
        host-element (js/document.createElement tag-name)
        host-element-id (form-element-id tag-name)]
    
    #_(u/okp-println "host-styles is" host-styles)
    
    ;; Following way of doing ensures that our host element styles are not affected by web sites styles.
    ;; Also this ensures injecting the OKP's host element do not affect the layouts of the web site's page
    ;; Before this way (using 'all: initial' etc )of doing, the injected UI affected the layouts of few sites 
    ;; e.g Minted, Coursera, Reddit etc
    (set! (.-cssText (.-style host-element))
          (str
           ;; RESET FIRST (The Nuclear Option)
           "all: initial;"

           ;; RE-APPLY STRUCTURAL ESSENTIALS
           ;; 'all: initial' sets display to 'inline', which breaks positioning.
           ;; We must set it back to block.
           "display: block;"

           ;; Now apply the ghost layer logic
           "position: fixed;"
           (when right (str "right: " right "px;"))
           (when left  (str "left: " left "px;"))
           "top: " top "px;"
           "width: 0;"
           "height: 0;"
           "overflow: visible;"
           ;; Z-INDEX
           "z-index: 2147483647;"))
    #_(j/call (j/get el :classList) :add host-element-class)
    (j/assoc! host-element :id host-element-id)
    host-element))

(defn- create-iframe [{:keys [src width height]}]
  (let [iframe (js/document.createElement "iframe")]
    (j/assoc! iframe :src src)
    (j/assoc! iframe :scrolling "no")
    (j/assoc-in! iframe
                 [:style :cssText]
                 (str
                  "width: " width  "px;"
                  (when height (str "height:" height "px;"))
                  "overflow: hidden;"
                  "border: none;"
                  "background: white;"
                  "border-bottom-left-radius: 8px;"
                  "border-bottom-right-radius: 8px;"
                  "box-shadow: 0 4px 15px rgba(0,0,0,0.2);"))
    iframe))

(defn- create-drag-handle []
  (let [handle (js/document.createElement "div")]

    (j/assoc! handle :innerText "OneKeePass")

    ;; Using style 'text-align:center;' ensures that the title is in the center
    ;; The style 'background' is same as the mui primary main color
    (j/assoc-in! handle [:style :cssText]
                 (str
                  "text-align:center;"
                  "width: " POPUP_ACTION_BOX_WIDTH  "px;"
                  "height: 30px;"
                  "background: #1976d2;"
                  ;;"background: #333;"
                  "color: #fff;"
                  "font-family: sans-serif;"
                  "font-size: 12px;"
                  "line-height: 30px;"
                  "padding-left: 10px;"
                  "box-sizing: border-box;"
                  "cursor: move;"
                  "border-top-left-radius: 8px;"
                  "border-top-right-radius: 8px;"
                  "user-select: none;")) ;; Prevent text highlighting while dragging
    ;; This also works showing title
    #_(let [txt-el (js/document.createElement "span")]
        (j/assoc! txt-el :innerText "OneKeePass")
        (.appendChild handle txt-el))
    
    handle))

(defn- create-popup-internal [{:keys [iframe-src tag-name box-width box-height close-popup dragging-support ]}]
  (let [iframe-src (js/chrome.runtime.getURL iframe-src)
        ;; Here 'right' value is the px that determines the x coordinate of the popup from right
        ;; Looks like this is diffrent from viewport right as per the diagram in the pagehttps://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect
        ;; Is it so?.
        ;; https://developer.mozilla.org/en-US/docs/Glossary/Viewport

        host-element    (create-host-element tag-name {:right (+ box-width 5)  :top 5})
        iframe-element  (create-iframe {:src iframe-src :width box-width :height box-height})
        handle-element  (when dragging-support (create-drag-handle))
        shadow-root (.attachShadow host-element #js {:mode "closed"})

        ;; Define the close action
        ;; close-action close-popup

        close-btn (when dragging-support (create-close-button close-popup))]

    (when dragging-support
      (.appendChild handle-element close-btn)
      ;; Handle goes on top
      (.appendChild shadow-root handle-element))


    (.appendChild shadow-root iframe-element)
    (.appendChild js/document.body host-element)

    #_(u/okp-println "2 Iframp popup: create-popup-internal custom element for iframe created and appended")

    {:host-element host-element
     :handle-element handle-element
     :iframe-element iframe-element}))

#_(defn- create-main-popup-internal []
  (create-popup-internal {:iframe-src "main_popup.html"
                          :tag-name OKP_DRAGGBLE_BOX_TAG_NAME
                          :box-width POPUP_ACTION_BOX_WIDTH
                          :box-height 330
                          :dragging-support true
                          :close-popup close-main-popup}))


(def ^:private last-body-width (atom {}))

(defn- body-width
  "Called to consider only the largest width seen so far"
  [source width]
  #_(u/okp-println "=== source width" source width "last-body-width" @last-body-width)
  (let [stored-width (get @last-body-width source)
        greater-width? (> width stored-width)]
    (if greater-width?
      (do
        (swap! last-body-width assoc source width)
        width)
      stored-width)))

(defn- adjust-width-height
  "Based on the iframe's body content's size changes, the launched Iframe's size is adjusted accordingly"
  [{:keys [source received-width received-height iframe-element handle-element] :as _data}]
  #_(u/okp-println "In adjust-width-height with data" data)
  (let [;; Use only the largest width when multiple width values are received
        width (body-width source received-width)

        ;;_ (u/okp-println "=== In adjust-width-height stored width" @last-body-width)

        ;; Use received-height directly so iframe can shrink when content becomes shorter
        height received-height

        ;; Add a little buffer for the handle (30px) if measuring just body
        total-h (+ height 15)
        total-w width #_(+ w 15)]

    #_(u/okp-println "... In the body" total-h)

    ;; We see that multiple messages are received with 
    ;; decreasing width. The (body-width w) may be used to get the maxium width received
    ;; For now not setting also works
    
    ;; Apply new dimensions to Iframe
    ;; (set! (.-width (.-style iframe-el)) (str w "px"))
    
    #_(u/okp-println "Setting iframe width" total-w)
    (set! (.-width (.-style iframe-element)) (str total-w "px"))

    #_(u/okp-println "Setting iframe height" total-h)
    #_(set! (.-height (.-style iframe-el)) (str h "px"))
    (set! (.-height (.-style iframe-element)) (str total-h "px"))

    ;; Update Drag Handle width to match
    (when handle-element
      (set! (.-width (.-style handle-element)) (str total-w "px")))))

(defn- iframe-resize-message-listener
  "Adds a resize listener to receive resize data from ResizeObserver of iframe content. 
  The iframe element size is adjusted dynamically"
  [{:keys [iframe-element handle-element message-type]}]
  (js/window.addEventListener "message"
                              (fn [e]
                                (let [data (.-data e)
                                      msg-type (.-type data)]
                                  #_(u/okp-console-log "=== Data received" data)
                                  (when (= msg-type message-type)
                                    (adjust-width-height {:source message-type
                                                          :received-width (.-width data)
                                                          :received-height (.-height data)
                                                          :iframe-element iframe-element
                                                          :handle-element handle-element}))))))

(defn remove-settings-popup []
  (close-settings-popup))

(defn create-settings-popup
  "Launches the settings popup iframe (same dimensions as main popup).
   Closes the main popup first so only the settings popup is visible."
  [_args]
  (close-main-popup)
  (remove-host-element-by-id (form-element-id OKP_SETTINGS_TAG_NAME))
  (let [{:keys [host-element iframe-element handle-element]}
        (create-popup-internal {:iframe-src       "settings_popup.html"
                                :tag-name         OKP_SETTINGS_TAG_NAME
                                :box-width        POPUP_ACTION_BOX_WIDTH
                                :box-height       330
                                :close-popup      close-settings-popup
                                :dragging-support true})]

    (dragging-support/add-drag-support host-element iframe-element handle-element)

    (iframe-resize-message-listener
     {:iframe-element iframe-element
      :handle-element handle-element
      :message-type   RESIZE_IFRAME_SETTINGS})

    (content-iframe-messaging/register-iframe-message-handlers
     {CLOSE_SETTINGS_POPUP close-settings-popup})

    (content-iframe-messaging/init-content-iframe-message-channel iframe-element)))

(defn create-main-popup
  "Launches the iframe based main popup window when the content script receives the popup action message.
   This is fn is registred as a callback 
   "
  []

  #_(u/okp-println "1 Iframp popup: create-main-popup is called ")

  ;; First remove any existing shadow host element
  (remove-host-element-by-id (form-element-id OKP_DRAGGBLE_BOX_TAG_NAME))

  (let [{:keys [host-element iframe-element handle-element]} 
        (create-popup-internal {:iframe-src "main_popup.html"
                                :tag-name OKP_DRAGGBLE_BOX_TAG_NAME
                                :box-width POPUP_ACTION_BOX_WIDTH
                                :box-height 330
                                :dragging-support true
                                :close-popup close-main-popup})]

    ;; Iframe drag and drop 
    (dragging-support/add-drag-support host-element iframe-element handle-element)

    ;; Ensures a proper iframe size based on content
    #_(iframe-resize-message-listener iframe-element handle-element)
    (iframe-resize-message-listener {:iframe-element iframe-element 
                                     :handle-element handle-element 
                                     :message-type RESIZE_IFRAME_MAIN_POPUP})

    ;; Message listener to receive close popup call
    (content-iframe-messaging/register-iframe-message-handlers {CLOSE_POPUP          close-main-popup
                                                                SHOW_SETTINGS_POPUP  create-settings-popup})

    ;; Establishes the communication channel between content and the launched iframe
    (content-iframe-messaging/init-content-iframe-message-channel iframe-element)

    #_(u/okp-println "3 Iframp popup: In create-main-popup content-iframe-messaging/init-content-iframe-message-channel done")

    ;; Send the popup action message to the launched iframe
    (content-iframe-messaging/send-popup-action-message)
    #_(u/okp-println "6 Iframp popup: In create-main-popup content-iframe-messaging/init-content-iframe-message-channel done")))

(defn create-msg-popup []
  (remove-host-element-by-id (form-element-id OKP_MESSAGE_BOX_TAG_NAME))
  (let [{:keys [host-element iframe-element handle-element]}
        (create-popup-internal {:iframe-src "msg_popup.html"
                                :tag-name OKP_MESSAGE_BOX_TAG_NAME
                                :box-width POPUP_ACTION_BOX_WIDTH
                                :box-height 100
                                :dragging-support true
                                :close-popup close-msg-popup})]
    ;; Iframe drag and drop 
    (dragging-support/add-drag-support host-element iframe-element handle-element)

    ;; Ensures a proper iframe size based on content
    #_(iframe-resize-message-listener iframe-element handle-element)
    (iframe-resize-message-listener {:iframe-element iframe-element 
                                     :handle-element handle-element 
                                     :message-type RESIZE_IFRAME_MSG_POPUP})

    ;; Message listener to receive close popup call
    (content-iframe-messaging/register-iframe-message-handlers {CLOSE_POPUP close-msg-popup})

    ;; Establishes the communication channel between content and the launched iframe
    (content-iframe-messaging/init-content-iframe-message-channel iframe-element)

    ;; Need to send the msg box message information from content's re-frame db to iframe
    (content-iframe-messaging/send-mg-box-message)))

;;;;;;;;;;;

(defn- create-entry-list-popup-internal [{:keys [iframe-src tag-name host-left host-top width dragging-support close-popup]}]
  (let [iframe-src (js/chrome.runtime.getURL iframe-src)
        ;; Here 'right' value is the px that determines the x coordinate of the popup from right
        ;; Looks like this is diffrent from viewport right as per the diagram in the pagehttps://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect
        ;; Is it so?.
        ;; https://developer.mozilla.org/en-US/docs/Glossary/Viewport

        host-element    (create-host-element tag-name {:left host-left :top host-top})
        iframe-element  (create-iframe {:src iframe-src :width width :height 100})
        handle-element  (when dragging-support (create-drag-handle))
        close-btn       (when dragging-support (create-close-button close-popup))
        shadow-root (.attachShadow host-element #js {:mode "closed"})]

    (when dragging-support
      (.appendChild handle-element close-btn)
      (.appendChild shadow-root handle-element))

    (.appendChild shadow-root iframe-element)
    (.appendChild js/document.body host-element)

    #_(u/okp-println "2 Iframp popup: create-entry-list-popup-internal custom element for iframe created and appended")

    {:host-element   host-element
     :handle-element handle-element
     :iframe-element iframe-element}))

(defn- calculate-list-positions [input-element]
  (let [rect (j/call input-element :getBoundingClientRect)
        {:keys [left width bottom]} (j/lookup rect)
        width (if (> width 550) 550 width)
        width (if (< width 350) 350 width)]
    {:top (+ bottom js/window.scrollY)
     :left (+ left js/window.scrollX)
     :width width}))

(defn- entry-list-outside-click-handler [input-element]
  (fn [e]
    (let [cp (j/call e :composedPath)]
      (when-not (or (.includes cp @entry-list-popup-iframe-element-store) (.includes cp input-element))
        #_(u/okp-println "outside-clicked-listener will call entry-list host removal for input name" (j/get input-element :name))
        (close-entry-list-popup)))))

(defn create-entry-list-popup 
  "Called to lauch iframe based entry list"
  []
  #_(u/okp-println "1 Iframp popup: create-entry-list-popup is called ")

  (let [focused-input js/document.activeElement]
    #_(u/okp-console-log "Focused input is " focused-input)
    (when (or (= focused-input (context/username-in-page))
              (= focused-input (context/password-in-page)))
      (let [{:keys [top left width]} (calculate-list-positions focused-input)
            {:keys [iframe-element]} (create-entry-list-popup-internal
                                      {:iframe-src "entry_list_popup.html"
                                       :tag-name OKP_ENTRY_LIST_TAG_NAME
                                       :host-left left
                                       :host-top top
                                       :width width})]

        (reset! entry-list-popup-iframe-element-store iframe-element)

        (listener-store/add-entry-list-outside-clicked-listener (entry-list-outside-click-handler focused-input))
        ;; 
        #_(iframe-resize-message-listener-1 iframe-element nil) ;; No handle-element
        (iframe-resize-message-listener {:iframe-element iframe-element :message-type RESIZE_IFRAME_ENTRY_LIST})

        ;; Message listener to receive close popup call
        (content-iframe-messaging/register-iframe-message-handlers {CLOSE_ENTRY_LIST_POPUP close-entry-list-popup})

        ;; Establishes the communication channel between content and the launched iframe
        (content-iframe-messaging/init-content-iframe-message-channel iframe-element)

        ;; Need to send this message with matched entries data to the launched iframe which then shows the mui entry list
        (content-iframe-messaging/send-matched-entries-loaded-message)))))

(defn create-passkey-list-popup
  "Launches the passkey selection popup for the WebAuthn get (authentication) flow.
   Unlike create-entry-list-popup, this does NOT require a focused input element;
   it positions itself at a fixed screen location so it works during WebAuthn
   interception where there may be no login form on the page."
  []
  (remove-host-element-by-id (form-element-id OKP_PASSKEY_LIST_TAG_NAME))
  (let [{:keys [host-element iframe-element handle-element]}
        (create-entry-list-popup-internal
         {:iframe-src       "entry_list_popup.html"
          :tag-name         OKP_PASSKEY_LIST_TAG_NAME
          :host-left        20
          :host-top         100
          :width            420
          :dragging-support true
          :close-popup      cancel-passkey-list-popup})]

    (reset! entry-list-popup-iframe-element-store iframe-element)

    (dragging-support/add-drag-support host-element iframe-element handle-element)

    (iframe-resize-message-listener
     {:iframe-element iframe-element
      :handle-element handle-element
      :message-type   RESIZE_IFRAME_ENTRY_LIST})

    (content-iframe-messaging/register-iframe-message-handlers
     {CLOSE_ENTRY_LIST_POPUP close-passkey-list-popup})

    (content-iframe-messaging/init-content-iframe-message-channel iframe-element)

    ;; Sends the passkey entries (stored as :matched-entries in content db) to the iframe
    (content-iframe-messaging/send-matched-entries-loaded-message)))

(defn create-passkey-create-popup
  "Launches the passkey creation popup for the WebAuthn create (registration) flow.
   Draggable popup (matching main-popup style) with multi-step DB → Group → Entry form."
  []
  (remove-host-element-by-id (form-element-id OKP_PASSKEY_CREATE_TAG_NAME))
  (let [{:keys [host-element iframe-element handle-element]}
        (create-popup-internal {:iframe-src       "passkey_create_popup.html"
                                :tag-name         OKP_PASSKEY_CREATE_TAG_NAME
                                :box-width        POPUP_ACTION_BOX_WIDTH
                                :close-popup      cancel-passkey-create-popup
                                :dragging-support true})]

    (dragging-support/add-drag-support host-element iframe-element handle-element)

    (iframe-resize-message-listener
     {:iframe-element iframe-element
      :handle-element handle-element
      :message-type   RESIZE_IFRAME_PASSKEY_CREATE})

    (content-iframe-messaging/register-iframe-message-handlers
     {CLOSE_POPUP                close-passkey-create-popup
      CLOSE_PASSKEY_CREATE_POPUP close-passkey-create-popup
      SHOW_PASSKEY_GROUPS        content-iframe-messaging/send-passkey-groups-message
      SHOW_PASSKEY_ENTRIES       content-iframe-messaging/send-passkey-entries-message
      PASSKEY_CREATE_SUCCESS     content-iframe-messaging/send-passkey-create-success-message})

    (content-iframe-messaging/init-content-iframe-message-channel iframe-element)

    ;; Sends databases + rp-name (stored as :passkey-create-data in content db) to the iframe
    (content-iframe-messaging/send-passkey-create-data-message)))