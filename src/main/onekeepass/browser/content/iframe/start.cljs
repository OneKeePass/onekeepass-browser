(ns onekeepass.browser.content.iframe.start
  "Implements a sample iframe use. The fns are used from conntent side. 
   The fn show-iframe-based-popup-box is called to launch a iframe based popup
  "
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [onekeepass.browser.common.utils :as u]))

(defn- remove-host-element [app-host-class]
  (when-let [host-element (j/call js/document :querySelector (str "." app-host-class))]
    ;; Remove the host element from the body, which also removes its shadow root
    (j/call host-element :remove)
    #_(u/okp-println "Removed previous host-element of shadow root")
    ;; You might also want to reset the flag on the input element
    ;; if you want to allow re-attachment later.
    ;; (j/assoc! input-element :cljsMuiAttached false)
    ))

(def  ^:private iframe-host-element-class "cljs-iframe-popup-host")

;; Here we are creating only the shadow dom in a host element with iframe element as child
;; The iframe's src will load and html page in which mui component is mounted 
;; See 'src/main/onekeepass/browser/content/inject/content_popup.cljs' for mui component
;; We need to use a separate shadow-cljs 'module' content_popup.cljs whcih has only the mui components
;; and the iframe popup launch in another 'module'. Otherwise, shdow-cljs 'shared' module fails to compile properly
;; Not sure why this happened. Needs to understand how shdow-cljs builds shared and other esm modules

(defn show-popup-box-internal []
  (remove-host-element iframe-host-element-class)
  #_(u/okp-println "The show-app-box is called ")
  (let [host-element (js/document.createElement "div")
        shadow-root (.attachShadow host-element #js {:mode "closed"})
        src (js/chrome.runtime.getURL "entry_list_popup.html")
        iframe-element (js/document.createElement "iframe")
        style (j/get iframe-element :style)]

    (j/assoc! host-element :style "position:absolute; top: 100px; left: 50px;")

    ;; IMPORTANT: The iframe src html on loading will call the init fn of 
    ;; src/main/onekeepass/browser/content/inject/content_popup.cljs to mount the mui componnent
    (j/assoc! iframe-element :src src)

    ;; (j/assoc! style :width "400px")
    ;; (j/assoc! style :height "300px")
    ;; (j/assoc! style :border "none")

    #_(u/okp-console-log "iframe-element style src is "  (j/get-in iframe-element [:src]))

    #_(okp-console-log "iframe-element style is " style)

    #_(okp-console-log "iframe-element is " iframe-element)

    (j/call (j/get host-element :classList) :add iframe-host-element-class)

    ;; Add the iframe as child to the shadow dom
    (.appendChild shadow-root iframe-element)

    ;; IMPORATNT: Host is to be added to the web page's 'body' element
    (js/document.body.appendChild host-element)))

;; Called to show a popup box using iframe based mui ui
#_(defn show-iframe-based-popup-box
    [input]
    (if input
      (show-popup-box-internal)
      (u/okp-println "Input is nil and popbox is not called")))


;;;;;;;;;;;;;;;;;;


(defn create-host-element [x y]
  (let [el (js/document.createElement "div")]
    ;; CRITICAL: Styling to ensure no impact on page layout
    (set! (.-cssText (.-style el))
          (str "position: fixed;"          ;; Remove from flow
               "left: " x "px;"            ;; Position X
               "top: " y "px;"             ;; Position Y
               "width: 0;"                 ;; Zero width to affect nothing
               "height: 0;"                ;; Zero height to affect nothing
               "overflow: visible;"        ;; Allow the iframe to be seen
               "z-index: 2147483647;"      ;; Max safe integer for z-index
               "border: none;"
               "margin: 0;"
               "padding: 0;"))
    (j/call (j/get el :classList) :add iframe-host-element-class)
    el))
;; "width: 350px;" "height: 500px;"
(defn create-iframe [src]
  (let [iframe (js/document.createElement "iframe")]
    (set! (.-src iframe) src)
    ;; Style the iframe itself (The actual visible popup)
    (set! (.-cssText (.-style iframe))
          (str
           "border: none;"
           "background: white;"
           ;; Optional: rounded corners and shadow for "popup" feel
           "border-radius: 8px;"
           "box-shadow: 0 4px 15px rgba(0,0,0,0.2);"))
    iframe))

(defn inject-popup! [x y]
  (let [;; 1. Get the extension URL for the iframe content
        iframe-src (js/chrome.runtime.getURL "entry_list_popup.html")

        ;; 2. Create the host (The "Zero Layout Impact" anchor)
        host-el (create-host-element x y)

        ;; 3. Create the iframe
        iframe-el (create-iframe iframe-src)]

    ;; 4. Attach Shadow DOM to the host
    ;;    mode: "open" allows us to inspect it, "closed" prevents access.
    (let [shadow-root (.attachShadow host-el #js {:mode "open"})]

      ;; 5. Append iframe to Shadow DOM
      (.appendChild shadow-root iframe-el)

      ;; 6. Append Host to the actual Document Body
      (.appendChild js/document.body host-el))))

;; Usage: Inject at 100px from top, 100px from left
;; (inject-popup! 100 100)

#_(defn show-iframe-based-popup-box
    [input]
    (if input
      (inject-popup! 500 100)
      (u/okp-println "Input is nil and popbox is not called")))



;;;;;;;;;;;;;;;;;;;;;;


;; Global state to track dragging
(def drag-state (atom {:dragging? false
                       :offset-x 0
                       :offset-y 0}))

(defn create-close-button [on-close-fn]
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

(defn create-draggable-host-element [x y]
  (let [el (js/document.createElement "div")]
    (set! (.-cssText (.-style el))
          (str
           ;; 1. RESET FIRST (The Nuclear Option)
           "all: initial;"
          
           ;; 2. RE-APPLY STRUCTURAL ESSENTIALS
           ;; 'all: initial' sets display to 'inline', which breaks positioning.
           ;; We must set it back to block.
           "display: block;"
          
           ;; Now apply the ghost layer logic
           "position: fixed;"
           "left: " x "px;"
           "top: " y "px;"
           "width: 0;"
           "height: 0;"
           "overflow: visible;"
          
           ;; 3. Z-INDEX
           "z-index: 2147483647;")
          #_(str "position: fixed;"
                 "left: " x "px;"
                 "top: " y "px;"
                 "width: 0; height: 0;"
                 "overflow: visible;"
                 "z-index: 2147483647;"
                 
                 ;; --- The Safety Lock ---
                 ;; "all: initial;"        ;; 1. Strip all inherited site styles
                 "display: block;"      ;; 2. 'all: initial' sets display to inline, so set it back to block
                 "font-family: sans-serif;" ;; 3. Set a safe default font for the handle text
                 
                 ))
    (j/call (j/get el :classList) :add iframe-host-element-class)
    el))

(defn create-draggable-iframe [src]
  (let [iframe (js/document.createElement "iframe")]
    (set! (.-src iframe) src)
    (set! (.-cssText (.-style iframe))
          (str
           "width: 350px;"
           "border: none;"
           "background: white;"
           "border-bottom-left-radius: 8px;"
           "border-bottom-right-radius: 8px;"
           "box-shadow: 0 4px 15px rgba(0,0,0,0.2);"))
    iframe))

;; --- New Component: The Drag Handle ---
(defn create-drag-handle []
  (let [handle (js/document.createElement "div")]
    (set! (.-innerText handle) "⋮ Drag me")
    (set! (.-cssText (.-style handle))
          (str
           "width: 350px;"
           "height: 30px;"
           "background: #333;"
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
    handle))

(defn inject-draggable-popup! [start-x start-y]
  
  (remove-host-element iframe-host-element-class)
  
  ;;TODO: Will this  add multiple times? How to avoid that?
  (js/window.addEventListener "message"
                              (fn [event]
                                (let [data (.-data event)]
                                  (when (= (.-type data) "CLOSE_POPUP")
                                    (remove-host-element iframe-host-element-class)
                                    ;; Find and remove your host element
                                    ;; If you support multiple popups, you might need to pass an ID
                                    #_(when-let [host (js/document.querySelector "div-with-specific-id")]
                                        (.remove host))))))
  
  (let [iframe-src (js/chrome.runtime.getURL "entry_list_popup.html")
        host-el    (create-draggable-host-element start-x start-y)
        iframe-el  (create-draggable-iframe iframe-src)
        handle-el  (create-drag-handle)
        shadow-root (.attachShadow host-el #js {:mode "closed"})

        ;; Define the close action
        close-action (fn []
                       ;; Removing the host kills the Shadow DOM and Iframe
                       (.remove host-el))

        close-btn (create-close-button close-action)]
    (.appendChild handle-el close-btn)
    ;; --- Drag Event Handlers ---
    
    (defn on-mouse-move [e]
      (let [{:keys [dragging? offset-x offset-y]} @drag-state]
        (when dragging?
          (let [new-x (- (.-clientX e) offset-x)
                new-y (- (.-clientY e) offset-y)]
            ;; Update host position
            (set! (.-left (.-style host-el)) (str new-x "px"))
            (set! (.-top (.-style host-el)) (str new-y "px"))))))

    (defn on-mouse-up [e]
      (when (:dragging? @drag-state)
        (swap! drag-state assoc :dragging? false)
        ;; CRITICAL: Re-enable iframe interaction
        (set! (.-pointerEvents (.-style iframe-el)) "auto")
        ;; Clean up global listeners to save memory
        (js/window.removeEventListener "mousemove" on-mouse-move)
        (js/window.removeEventListener "mouseup" on-mouse-up)))

    (defn on-mouse-down [e]
      (let [rect (.getBoundingClientRect host-el)
            off-x (- (.-clientX e) (.-left rect))
            off-y (- (.-clientY e) (.-top rect))]

        (reset! drag-state {:dragging? true
                            :offset-x off-x
                            :offset-y off-y})

        ;; CRITICAL: Disable iframe interaction so mouse doesn't "fall in"
        (set! (.-pointerEvents (.-style iframe-el)) "none")

        ;; Attach global listeners (on window, not the element)
        (js/window.addEventListener "mousemove" on-mouse-move)
        (js/window.addEventListener "mouseup" on-mouse-up)))

    ;; Attach Start Listener to the Handle only
    (.addEventListener handle-el "mousedown" on-mouse-down)

    ;; --- Assembly ---
    (.appendChild shadow-root handle-el) ;; Handle goes on top
    (.appendChild shadow-root iframe-el)
    (.appendChild js/document.body host-el)))


(defn show-iframe-based-popup-box
  "This is called to launch an iframe based sample (entry list) popup when user enters an input"
  [input]
  (if input
    (inject-draggable-popup! 500 100)
    (u/okp-println "Input is nil and popbox is not called")))