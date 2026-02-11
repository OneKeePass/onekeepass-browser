(ns onekeepass.browser.content.inject.input-field-okp-icon
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.context :refer [get-password-input
                                               get-username-input]]
   [onekeepass.browser.content.iframe.start :as ifs]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.content.inject.custom-element :as ce]
   [onekeepass.browser.content.inject.listener-store :as listener-store]))

(def ^:private host-element-class ce/OKP_INPUT_ICON_CLASS)

(def ^:private okp-svg-icon
  "<svg viewBox=\"0 0 52.916668 52.916668\" xmlns=\"http://www.w3.org/2000/svg\">
    <g transform=\"translate(-80.468228,-118.26624)\">
      <rect
         style=\"fill:#1831e7;fill-opacity:0.933333;stroke:#0e6824;stroke-width:0.457037;stroke-dasharray:none;stroke-opacity:1\"
         width=\"52.459629\" height=\"52.459629\" x=\"80.696747\" y=\"118.49476\" />
      <ellipse
         style=\"fill:#ffffff;fill-opacity:0.933333;stroke:none;stroke-width:0.0533029\"
         cx=\"98.098114\" cy=\"143.59682\" rx=\"11.471445\" ry=\"11.783965\" />
      <path
         style=\"fill:#ffffff;fill-opacity:0.933333;stroke:none;stroke-width:0.0435804\"
         d=\"m 109.5423,131.84822 h 15.57309 l -9.27168,11.81801 9.27168,11.75984 H 109.5423 Z\" />
      <rect
         style=\"fill:#1831e7;fill-opacity:0.933333;stroke:#00299c;stroke-width:0.0352785;stroke-dasharray:none;stroke-opacity:1\"
         width=\"3.7004764\" height=\"14.463715\" x=\"96.247879\" y=\"136.36496\" />
    </g>
  </svg>")

(defn- set-icon-position [input host size]
  (let [rect (j/call input :getBoundingClientRect)

        {:keys [left top width height]} (j/lookup rect)

        icon-left (+ left width (- size) -2)
        icon-top (+ top (/ (- height size) 2))

        host-style (.-style host)]

    (-> host-style
        (j/assoc! :left (str icon-left "px"))
        (j/assoc! :top (str icon-top "px"))
        (j/assoc! :width (str size "px"))
        (j/assoc! :height (str size "px")))))

#_(defn- add-icon-to-input [input]

    (when input

      ;; IMPORTANT: We can't use removing and then adding the 'div' host element
      ;; as it triggeres the  mutation tracking by 'js/MutationObserver'.

      ;; If we want to use remove and add, then we need to exclude the tracking of this 'div' host element 
      ;; in mutation tracking or use a custom element that extends 'div'. Instead we are using
      ;; a custom tag element which we can add and remove as we desire

      ;; Do we need to remove all added event listeners to avoid leak when remove the host element?

      (let [rect (j/call input :getBoundingClientRect)
            {:keys [width height]} (j/lookup rect)

            size (-> (- height 4) (max 14) (min 24))

            icon-too-small? (or (< width (* 1.5 size))
                                (< height size))]

        (when-not icon-too-small?
          (let [;; The host-element-class is set host-element while creating 
                host-element (ce/create-input-icon-host-element host-element-class {})

                ;; If we use div host element, then we need 'host-element-class' class to host-element
                ;; Also we can not remove and add this host-element. See the notes above
                ;; host-element (js/document.createElement "div")
                ]

            (j/call host-element :setAttribute "style"
                    (str "position:fixed;"
                         "left:0px;"
                         "top:0px;"
                         "width:" size "px;"
                         "height:" size "px;"
                         "z-index:10000000;"
                         "pointer-events:auto;"
                         "display:flex;"
                         "align-items:center;"
                         "justify-content:center;"))

            ;; We use this class name to query the host element from the web page which is used to inject one time only
            #_(j/call (j/get host-element :classList) :add host-element-class)

            (let [shadow (j/call host-element :attachShadow #js {:mode "closed"})]

              (set! (.-innerHTML shadow)
                    (str "<style>
                              svg {
                                width: 100%;
                                height: 100%;
                                display: block;
                                border-radius: 50%;
                                background: white;
                                box-shadow: 0 0 2px #bbb;
                                cursor: pointer;
                              }
                              svg:hover {
                                filter: brightness(1.2);
                              }
                            </style>
                            " okp-svg-icon)))

            ;; IMPORTANT: host-element of shadow dom should be added as child to web page's body element
            (js/document.body.appendChild host-element)

            (set-icon-position input host-element size)

            ;; Reposition on scroll/resize
            (let [handler (fn []
                            (set-icon-position input host-element size))]

              ;; Uses EventTarget: addEventListener() method
              (doto js/window
                (.addEventListener "resize" handler)
                (.addEventListener "scroll" handler))))))))

(defn- add-icon-to-input [input]

  (when input

    ;; IMPORTANT: We can't use removing and then adding the 'div' host element
    ;; as it triggeres the  mutation tracking by 'js/MutationObserver'.

    ;; If we want to use remove and add, then we need to exclude the tracking of this 'div' host element 
    ;; in mutation tracking or use a custom element that extends 'div'. Instead we are using
    ;; a custom tag element which we can add and remove as we desire

    ;; Do we need to remove all added event listeners to avoid leak when remove the host element?

    (let [rect (j/call input :getBoundingClientRect)
          {:keys [width height]} (j/lookup rect)

          size (-> (- height 4) (max 14) (min 24))

          icon-too-small? (or (< width (* 1.5 size))
                              (< height size))]

      (when-not icon-too-small?
        (let [;; The host-element-class is set host-element while creating 
              ;; host-element (ce/create-input-icon-host-element host-element-class {})

              {:keys [host-element component-root]} (ce/create-input-icon-shadow-dom host-element-class {})
              ;; If we use div host element, then we need 'host-element-class' class to host-element
              ;; Also we can not remove and add this host-element. See the notes above
              ;; host-element (js/document.createElement "div")
              ]

          (j/call component-root :setAttribute "style"
                  (str "position:fixed;"
                       "left:0px;"
                       "top:0px;"
                       "width:" size "px;"
                       "height:" size "px;"
                       "z-index:10000000;"
                       "pointer-events:auto;"
                       "display:flex;"
                       "align-items:center;"
                       "justify-content:center;"))

          (set! (.-innerHTML component-root)
                (str "<style>
                              svg {
                                width: 100%;
                                height: 100%;
                                display: block;
                                border-radius: 50%;
                                background: white;
                                box-shadow: 0 0 2px #bbb;
                                cursor: pointer;
                              }
                              svg:hover {
                                filter: brightness(1.2);
                              }
                            </style>
                            " okp-svg-icon))

          ;; IMPORTANT: host-element of shadow dom should be added as child to web page's body element
          (js/document.body.appendChild host-element)

          (set-icon-position input component-root size)

          ;; Reposition on scroll/resize
          (let [handler (fn []
                          (set-icon-position input component-root size))]

            ;; Uses EventTarget: addEventListener() method
            (doto js/window
              (.addEventListener "resize" handler)
              (.addEventListener "scroll" handler))))))))


(defn show-input-okp-icon [input]
  ;; (js/console.log "Received username input " input)
  #_(u/okp-console-log "In show-input-okp-icon:Received username input obj is .. " input)

  (when (and (not (nil? input)) (instance? js/HTMLInputElement input))
    (ce/remove-shadowdom-host-element-by-class host-element-class)
    (add-icon-to-input input)))


;; To toggle click and focus event handlers of input
(def ^:private input-focus-info (atom {}))

(defn input-focus-listener
  "Triggers the background entry list loading if required"
  [evt]
  #_(u/okp-console-log "input-focus-listener: evt is" evt)
  #_(u/okp-println "input-focus-listener: input is focused and event target is input?" (instance? js/HTMLInputElement (j/get-in evt [:target])))

  (j/call evt :stopPropagation)

  (show-input-okp-icon (j/get-in evt [:target]))

  ;; Keep a flag so that we do not repeat click and focus events
  (swap! input-focus-info assoc (j/get-in evt [:target]) true)

  (when (str/blank? (j/get-in evt [:target :value]))
    #_(u/okp-println "Sending get entry list in input focus....")
    ;; Send a request to the background 
    (messaging-event/send-get-entry-list)))
(defn input-entry-listener
  "Monitors user data entry to this input and based on the value it triggers the entry list or hides the list"
  [evt]
  #_(u/okp-console-log "input-entry-listener is called with event" evt)

  #_(j/call evt :stopPropagation)
  (j/call evt :preventDefault)

  (let [value (j/get-in evt [:target :value])]
    #_(u/okp-console-log "input-entry-listener value from evt is" value (str/blank? value))
    (if-not (str/blank? value)
      ;; Hide
      (messaging-event/hide-entry-list)
      ;; Show
      (messaging-event/send-get-entry-list))))

(defn input-click-listener [evt]
  (u/okp-console-log "input-click-listener: INPUT CLICK is called with event" evt)
  (j/call evt :stopPropagation)

  #_(u/okp-println "Input focus state is" (get @input-focus-info (j/get-in evt [:target])))

  (when-not (get @input-focus-info (j/get-in evt [:target]))
    (u/okp-console-log "Input is not focused and will call focus for input" (->  evt (j/get :target)))

    ;; We cannot call the focus method as the input is already focused
    #_(-> evt (j/get :target) (j/call :focus))

    ;; Toggle the flag so that we do not call this when the entry list is still showing
    (swap! input-focus-info assoc (j/get-in evt [:target]) true)
    (messaging-event/send-get-entry-list)))

(defn input-blur-listener
  [evt]
  #_(u/okp-console-log "INPUT BLUR is called with event" evt)
  ;; Need to remove the flag
  (swap! input-focus-info assoc (j/get-in evt [:target]) false))

(defn add-page-inputs-event-listeners [page-info]
  (when-some [input (some-> page-info get-username-input)]
    
    #_(u/okp-console-log "Adding username input listeners for username input" input)

    ;; (listener-store/add-focus-event-listener input input-focus-listener)
    ;; (listener-store/add-input-event-listener input input-entry-listener)
    
    #_(listener-store/add-input-named-event-listener input "focus" input-focus-listener-ifs)
    (listener-store/add-input-named-event-listener input "focus" input-focus-listener)
    (listener-store/add-input-named-event-listener input "input" input-entry-listener)

    #_(listener-store/add-input-named-event-listener input "click" input-click-listener)
    #_(listener-store/add-input-named-event-listener input "blur" input-blur-listener))

  (when-some [input (some-> page-info get-password-input)]
    #_(u/okp-console-log  "Adding password input listeners for password input" input)
    
    ;; (listener-store/add-focus-event-listener input input-focus-listener)
    ;; (listener-store/add-input-event-listener input input-entry-listener)
    
    #_(listener-store/add-input-named-event-listener input "focus" input-focus-listener-ifs)
    (listener-store/add-input-named-event-listener input "focus" input-focus-listener)
    (listener-store/add-input-named-event-listener input "input" input-entry-listener)))