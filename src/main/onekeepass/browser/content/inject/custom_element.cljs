(ns onekeepass.browser.content.inject.custom-element
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.content.inject.mui-support :as mui-support]
   [onekeepass.browser.common.mui-components
    :as m]
   [reagent.dom :as rdom]
   #_[shadow.cljs.modern :refer (defclass)]
   [onekeepass.browser.common.utils :as u]))

(def ^:constant OKP_INPUT_ICON_CLASS "okp-input-icon-class")

(defonce ^:constant OKP_DRAGGABLE_BOX_CLASS "okp-draggable-box-class")

(defonce ^:constant OKP_ENTRY_LIST_CLASS "okp-entry-list-class")

(defonce ^:constant OKP_MESSAGE_BOX_CLASS "okp-message-box-class")

#_(def container-ids {OKP_DRAGGABLE_BOX_CLASS "okp-draggable-box-container"})

;; Tags name for our custome elements

(def ^:constant OKP_INPUT_ICON_TAG_NAME  "okp-input-icon")

(def ^:constant OKP_ENTRY_LIST_TAG_NAME  "okp-entry-list-element")

(def ^:constant OKP_DRAGGBLE_BOX_TAG_NAME  "okp-draggable-box-element")

(def ^:constant OKP_MESSAGE_BOX_TAG_NAME "okp-message-box-element")

;;;;;;;;;;;

#_(defclass OkpInputIconElement (extends js/HTMLElement)
    ;; The constructor is where you can perform initial setup.
    ;; You must call `(super)` to initialize the parent class.
    (constructor [this]
                 (okp-console-log "In OkpInputIconElement constructor with this as ..." this)
                 (super)))

#_(defclass OkpEntryListElement (extends js/HTMLElement)
    ;; The constructor is where you can perform initial setup.
    ;; You must call `(super)` to initialize the parent class.
    (constructor [this]
                 (okp-console-log "In OkpEntryListElement constructor with this as ..." this)
                 (super)))

#_(defn- -define-custom-okp-element [class-name tag-name]
    (okp-println "window.customElements " (j/call js/window.customElements :get tag-name) class-name tag-name)
    (when (nil? (j/call js/window.customElements :get tag-name))

      ;; window.customElements.define(name, constructor, options)

      ;; We can only define one custom element for a given tag-name using contructor 'OkpEntryListElement'
      ;; To define another custom tag name, we need a separate 'defclass' 
      (js/window.customElements.define tag-name class-name)))

#_(defclass OkpDraggableBoxElement (extends js/HTMLElement)
    ;; The constructor is where you can perform initial setup.
    ;; You must call `(super)` to initialize the parent class.
    (constructor [this]
                 (super)))

#_(defn- define-custom-okp-element [tag-name]
    (condp = tag-name
      OKP_ENTRY_LIST_TAG_NAME
      (-define-custom-okp-element OkpEntryListElement tag-name)

      OKP_DRAGGBLE_BOX_TAG_NAME
      (-define-custom-okp-element OkpDraggableBoxElement tag-name)

      OKP_INPUT_ICON_TAG_NAME
      (-define-custom-okp-element OkpInputIconElement tag-name)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Previously used where we set the styles to the host element directly

#_(defn- create-host-element
    "Returns the created host element"
    [tag-name host-element-class host-styles]

    ;; Using 'custom-okp-element' defined by 'define-custom-okp-element'  
    ;; though worked, saw console errors from the page whenever this custom defined host-element is injected to the page

    ;; Uncaught Error: Permission denied to access property "nodeType""
    ;; Uncaught Error: Permission denied to access property "classList"
    ;; Uncaught Error: Permission denied to access property "tagName"

    ;; Commenting out 'define-custom-okp-element' or using plain "div" with okp ids worked

    #_(define-custom-okp-element tag-name)

    (let [;; host-element is a custom element or may be a 'div' element

          ;; Using createElement without any call to window.customElements.define still works (Firefox). Not sure how?
          ;; Need to verify in chrome
          host-element (js/document.createElement tag-name)

          ;; host-element  (-> (js/document.createElement "div") (j/assoc! :id tag-name))

          host-element-style (j/get host-element :style)
          ;;_ (okp-println "host-styles are " host-styles)
          {:keys [position display zIndex top left right bottom width]} (merge {:position "fixed"
                                                                                :display "block"
                                                                                :zIndex "2147483647"
                                                                                :width "350px"}
                                                                               host-styles)]
      #_(okp-println "Merged styles top left right bottom width" top left right bottom width)

      ;; We use this class name to query the host element from the web page which is used to inject one time only
      (j/call (j/get host-element :classList) :add host-element-class)

      (j/assoc! host-element-style :position position)
      (j/assoc! host-element-style :zIndex zIndex)
      (j/assoc! host-element-style :display display)

      ;; The UI injected at this position
      ;; Need to have px suffix. Otherwise the styles were not set
      (when top
        (j/assoc! host-element-style :top (str top "px")))

      (when left
        (j/assoc! host-element-style :top (str left "px")))

      (when right
        (j/assoc! host-element-style :right (str right "px")))

      (when bottom
        (j/assoc! host-element-style :bottom (str bottom "px")))

      (when width
        (j/assoc! host-element-style :width (str width "px")))

      #_(okp-console-log "host-element-style obj is" host-element-style)

      ;; IMPORTANT: Host element that has the shadow root is to be added to the web page's 'body' element
      ;; appendChild returns a Node that is the appended child (child)
      (js/document.body.appendChild host-element)

      ;; We explicitly return the host-element
      host-element))

#_(defn- create-shadow-dom
    "Creates the shadow dom after creating a host element
   Returns the created host element of the shadow dom
  "
    [tag-name host-element-class host-styles mui-root-component]

    (let [host-element (create-host-element tag-name host-element-class host-styles)
          shadow-root (.attachShadow host-element #js {:mode "closed"} #_{:mode "open" :delegatesFocus true})
          ;; Mui css 
          emotion-cache (m/create-cache #js {:key "css"
                                             :prepend true
                                             :container shadow-root})
          ;; Mui component used with Shadow dom should have its own theme 
          custom-theme (mui-support/create-shadow-root-theme shadow-root)

          ;; Component root to which the injected mui based UI is mounted
          component-root (js/document.createElement "div")]
      ;; Add the component root to the shdow dom
      (.appendChild shadow-root component-root)

      ;; Add links for the roboto fonts to shadow dom 
      (mui-support/add-roboto-font-styles shadow-root)

      #_(u/okp-println "Going to render mui...")
      ;; This renders the main mui content
      (rdom/render [mui-root-component emotion-cache custom-theme] component-root)

      host-element))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The host element does not have any styles set. All style setting moved to the container root 'div'

;; Container roots are held for furthur use
(def container-roots (atom {}))

(defn- set-container-styles [container host-styles]
  (let [containert-style (j/get container :style)
        {:keys [position display zIndex top left right bottom width]}
        (merge {:position "fixed"
                :display "block"
                :zIndex "2147483647"
                :pointerEvents "auto"
                :width "350px"}
               host-styles)]

    (j/assoc! containert-style :position position)
    (j/assoc! containert-style :zIndex zIndex)
    (j/assoc! containert-style :display display)

    ;; The UI injected at this position
    ;; Need to have px suffix. Otherwise the styles were not set
    (when top
      (j/assoc! containert-style :top (str top "px")))

    (when left
      (j/assoc! containert-style :top (str left "px")))

    (when right
      (j/assoc! containert-style :right (str right "px")))

    (when bottom
      (j/assoc! containert-style :bottom (str bottom "px")))

    (when width
      (j/assoc! containert-style :width (str width "px")))))

(defn- create-host-element
  "Returns the created host element"
  [tag-name host-element-class]

  ;; Using 'custom-okp-element' defined by 'define-custom-okp-element'  
  ;; though worked, saw console errors from the page whenever this custom defined host-element is injected to the page

  ;; Uncaught Error: Permission denied to access property "nodeType""
  ;; Uncaught Error: Permission denied to access property "classList"
  ;; Uncaught Error: Permission denied to access property "tagName"

  ;; Commenting out 'define-custom-okp-element' or using plain "div" with okp ids worked

  #_(define-custom-okp-element tag-name)

  (let [;; host-element is a custom element or may be a 'div' element

        ;; Using createElement without any call to window.customElements.define still works (Firefox). Not sure how?
        ;; Need to verify in chrome
        host-element (js/document.createElement tag-name)

        ;; host-element  (-> (js/document.createElement "div") #_(j/assoc! :id tag-name))

        host-element-style (j/get host-element :style)]
    ;; We use this class name to query the host element from the web page which is used to inject one time only
    (j/call (j/get host-element :classList) :add host-element-class)

    ;; All styles are now set to the container element instead of to the host-element itself
    ;; Using all: unset with injected code offers significant advantages in isolating the injected element from existing page styles
    ;; The following was done hoping that the injected popoup will not interfere with the web page's layout.
    ;; But did not work as expected. Still saw the same issue. 
    (j/assoc! host-element-style :all "unset")
    (j/assoc! host-element :popover "manual")


    ;; IMPORTANT: Host element that has the shadow root is to be added to the web page's 'body' element
    ;; appendChild returns a Node that is the appended child (child)
    (js/document.body.appendChild host-element)

    ;; document.documentElement gives the html element
    #_(js/document.documentElement.appendChild host-element)

    ;; We explicitly return the host-element
    host-element))

(defn- create-shadow-dom
  "Creates the shadow dom after creating a host element
   Mui css and fonts are set and mui-root-component is used for rendering
   Returns a map with the created host element of the shadow dom and the container root 'component-root'
   "
  [tag-name host-element-class host-styles mui-root-component]

  (let [host-element (create-host-element tag-name host-element-class)

        shadow-root (.attachShadow host-element #js {:mode "closed"})

        ;; Mui css 
        emotion-cache (m/create-cache #js {:key "css"
                                           :prepend true
                                           :container shadow-root})
        ;; Mui component used with Shadow dom should have its own theme 
        custom-theme (mui-support/create-shadow-root-theme shadow-root)

        ;; Component root to which the injected mui based UI is mounted
        component-root (js/document.createElement "div")

        ;; emotion-cache (m/create-cache #js {:key "css"
        ;;                                    :prepend true
        ;;                                    :container component-root})
        ]


    (set-container-styles component-root host-styles)

    ;; Set an id to this container so that we can access if we make shadow-root "open". Otherwise no use
    #_(j/assoc! component-root :id (get container-ids host-element-class))

    ;; The shadow-root mode is "closed" and so we cannot access this 'component-root' for later use (e.g dragging support)
    ;; Instead we keep a reference to that for later use
    (swap! container-roots assoc host-element-class component-root)

    ;; Add the component root to the shdow dom
    (.appendChild shadow-root component-root)

    ;; Add links for the roboto fonts to shadow dom 
    (mui-support/add-roboto-font-styles shadow-root)

    #_(okp-println "Going to render mui...")
    ;; This renders the main mui content
    (rdom/render [mui-root-component emotion-cache custom-theme] component-root)

    {:host-element host-element
     :component-root component-root}))

(defn- create-plain-shadow-dom
  "Creates the shadow dom after creating a host element
   No mui css and fonts are set 
   Returns a map with the created host element of the shadow dom and the container root 'component-root' 
   "
  [tag-name host-element-class host-styles]

  (let [host-element (create-host-element tag-name host-element-class)
        shadow-root (.attachShadow host-element #js {:mode "closed"})

        ;; Component root to which the injected mui based UI is mounted
        component-root (js/document.createElement "div")]

    (set-container-styles component-root host-styles)

    ;; Add the component root to the shdow dom
    (.appendChild shadow-root component-root)

    {:host-element host-element
     :component-root component-root}))

(defn create-entry-list-shadow-dom
  "Creates a shadow dom with a custom host element to show entry list
   Returns the created host element of the shadow dom
   "
  [host-element-class host-styles mui-root-component]
  (create-shadow-dom OKP_ENTRY_LIST_TAG_NAME host-element-class host-styles mui-root-component))

(defn create-draggable-box-shadow-dom
  "Creates a shadow dom with a custom host element to show a draggable box
   Returns the created host element of the shadow dom
   "
  [host-element-class host-styles mui-root-component]
  (create-shadow-dom OKP_DRAGGBLE_BOX_TAG_NAME host-element-class host-styles mui-root-component))

(defn create-message-box-shadow-dom
  "Creates a shadow dom with a custom host element to show a message box
     Returns the created host element of the shadow dom
     "
  [host-element-class host-styles mui-root-component]
  (create-shadow-dom OKP_MESSAGE_BOX_TAG_NAME host-element-class host-styles mui-root-component))

(defn create-input-icon-shadow-dom
  "Creates a shadow dom with a custom host element to show an icon in an identified input
   Returns the created host element of the shadow dom
   "
  [host-element-class host-styles]
  (create-plain-shadow-dom OKP_INPUT_ICON_TAG_NAME host-element-class host-styles))

#_(defn create-input-icon-host-element
    "Creates a custom host element that will be used to host a shadow dom by the caller
   Returns the created host element
   "
    [host-element-class host-styles]
    (create-host-element OKP_INPUT_ICON_TAG_NAME host-element-class host-styles))

(defn remove-shadowdom-host-element-by-class
  "Removes the shadow dom's host element selected by using a okp specific class "
  [app-host-class]
  (when-let [host-element (j/call js/document :querySelector (str "." app-host-class))]
    ;; Remove the host element from the body, which also removes its shadow root
    (j/call host-element :remove)
    #_(u/okp-println "Removed previous host-element of shadow root for class" app-host-class)))

;; TODO: If we use 'id' with our host element, then using (js/document.getElementById "unique_id")
;; can be used which will be fast
(defn host-element-by-class [app-host-class]
  (j/call js/document :querySelector (str "." app-host-class)))

(defn container-root-element [app-host-class]
  (get @container-roots app-host-class)

  ;; Following did not work because shadowRoot is closed one and will be null
  #_(let [host (j/call js/document :querySelector (str "." app-host-class))
          _ (u/okp-console-log "Host is" host)
          id (get container-ids app-host-class)]
      (j/call-in host [:shadowRoot :getElementById] id)))

(defn hide-host-element-by-class [host-element-class]
  #_(u/okp-println "The hide-host-element is called")
  (when-let [host-element (host-element-by-class host-element-class)]
    ;; (okp-println "Setting host element style display none")
    (j/assoc-in! host-element [:style :display] "none")))

(defn show-host-element-by-class [host-element-class]
  ;; (okp-println "The hide-host-element is called")
  (when-let [host-element (host-element-by-class host-element-class)]
    (j/assoc-in! host-element [:style :display] "block")))

(defn hide-host-element [host-element]
  (j/assoc-in! host-element [:style :display] "none"))

(defn show-host-element [host-element]
  (j/assoc-in! host-element [:style :display] "block"))

(defn remove-host-element-by-class-with-delay [host-element-class]
  (js/window.setTimeout
   (fn [] (remove-shadowdom-host-element-by-class host-element-class)) 500))

(defn remove-host-element-with-delay [host-element]
  (js/window.setTimeout
   (fn [] (j/call host-element :remove)) 500))


;; An example using setting styles using setProperty with important flag

#_(defn- create-host-element
    "Returns the created host element"
    [tag-name host-element-class host-styles]

    ;; Using 'custom-okp-element' defined by 'define-custom-okp-element'  
    ;; though worked, saw console errors from the page whenever this custom defined host-element is injected to the page

    ;; Uncaught Error: Permission denied to access property "nodeType""
    ;; Uncaught Error: Permission denied to access property "classList"
    ;; Uncaught Error: Permission denied to access property "tagName"

    ;; Commenting out 'define-custom-okp-element' or using plain "div" with okp ids worked

    #_(define-custom-okp-element tag-name)

    (let [;; host-element is a custom element or may be a 'div' element

          ;; Using createElement without any call to window.customElements.define still works (Firefox). Not sure how?
          ;; Need to verify in chrome
          host-element (js/document.createElement tag-name)

          ;;host-element  (-> (js/document.createElement "div") (j/assoc! :id tag-name))

          host-element-style (j/get host-element :style)
          ;;_ (okp-println "host-styles are " host-styles)
          {:keys [position display zIndex top left right bottom width]} (merge {:position "fixed"
                                                                                :display "block"
                                                                                :zIndex "2147483647"
                                                                                :width "350px"}
                                                                               host-styles)]
      (okp-println "Merged styles position display zIndex top left right bottom width" position display zIndex top left right bottom width)

      ;; We use this class name to query the host element from the web page which is used to inject one time only
      (j/call (j/get host-element :classList) :add host-element-class)

      ;; (j/assoc! host-element-style :position position)
      ;; (j/assoc! host-element-style :zIndex zIndex)
      ;; (j/assoc! host-element-style :display display)

      (j/call host-element-style :setProperty "position" position "important")
      (j/call host-element-style :setProperty "z-index" zIndex "important")
      (j/call host-element-style :setProperty "display" display "important")

      ;; The UI injected at this position
      ;; Need to have px suffix. Otherwise the styles were not set
      ;; (when top
      ;;   (j/assoc! host-element-style :top (str top "px")))

      ;; (when left
      ;;   (j/assoc! host-element-style :top (str left "px")))

      ;; (when right
      ;;   (j/assoc! host-element-style :right (str right "px")))

      ;; (when bottom
      ;;   (j/assoc! host-element-style :bottom (str bottom "px")))

      ;; (when width
      ;;   (j/assoc! host-element-style :width (str width "px")))


      (when top
        (j/call host-element-style :setProperty "top" (str top "px")  "important"))

      ;; (when left
      ;;   (j/call host-element-style :setProperty :left (str left "px") "important"))

      (when right
        (j/call host-element-style :setProperty "right" (str right "px") "important"))

      ;; (when bottom
      ;;   (j/call host-element-style :setProperty :bottom (str bottom "px") "important"))

      (when width
        (j/call host-element-style :setProperty "width" (str width "px") "important"))

      (okp-console-log "host-element-style obj is" host-element-style)

      ;; IMPORTANT: Host element that has the shadow root is to be added to the web page's 'body' element
      ;; appendChild returns a Node that is the appended child (child)
      (js/document.body.appendChild host-element)

      ;; We explicitly return the host-element
      host-element))

;;;;;;;;;;;;;;;  iframe related ;;;;;;;;;;;






;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


#_(defn- create-shadow-dom-2
    "Creates the shadow dom after creating a host element
    Returns the created host element of the shadow dom
   "
    [tag-name host-element-class host-styles mui-root-component]

    (let [host-element (create-host-element-1 tag-name host-element-class)
          shadow-root (.attachShadow host-element #js {:mode "closed"})

          ;; Mui css 
          emotion-cache (m/create-cache #js {:key "css"
                                             :prepend true
                                             :container shadow-root})
          ;; Mui component used with Shadow dom should have its own theme 
          custom-theme (mui-support/create-shadow-root-theme shadow-root)

          ;; Component root to which the injected mui based UI is mounted
          component-root (js/document.createElement "div")]

      (set-container-styles component-root host-styles)

      ;; Add the component root to the shdow dom
      (.appendChild shadow-root component-root)

      ;; Add links for the roboto fonts to shadow dom 
      (mui-support/add-roboto-font-styles shadow-root)

      (okp-println "Going to render mui...")
      ;; This renders the main mui content
      (rdom/render [mui-root-component emotion-cache custom-theme] component-root)

      [host-element component-root]))

#_(defn- create-shadow-dom-3
    "Creates the shadow dom after creating a host element
    Returns the created host element of the shadow dom
   "
    [tag-name host-element-class host-styles]

    (let [host-element (create-host-element-1 tag-name host-element-class)
          shadow-root (.attachShadow host-element #js {:mode "closed"})

          ;; Component root to which the injected mui based UI is mounted
          component-root (js/document.createElement "div")]

      (set-container-styles component-root host-styles)

      ;; Add the component root to the shdow dom
      (.appendChild shadow-root component-root)

      [host-element component-root]))


;; en based

#_(defn apply-fortress-styles! [element]
    (let [style (.-style element)]
      ;; Reset all inherited properties from the site (e.g. * { box-sizing... })
      (set! (.-all style) "initial")

      ;; Take out of document flow to prevent layout shifts
      (set! (.-position style) "fixed")
      (set! (.-top style) "0px")
      (set! (.-left style) "0px")
      (set! (.-width style) "100%")
      (set! (.-height style) "100%")

      ;; Ensure it sits on top of everything
      (set! (.-zIndex style) "2147483647")

      ;; CRITICAL: Let clicks pass through the container to the website below
      (set! (.-pointerEvents style) "none")))

#_(defn- create-host-element-2
    "Returns the created host element"
    [tag-name host-element-class]
    (let [;; host-element is a custom element or may be a 'div' element

          ;; Using createElement without any call to window.customElements.define still works (Firefox). Not sure how?
          ;; Need to verify in chrome
          host-element (js/document.createElement tag-name)

          ;; host-element  (-> (js/document.createElement "div") #_(j/assoc! :id tag-name))

          host-element-style (j/get host-element :style)]

      (j/call (j/get host-element :classList) :add host-element-class)

      #_(j/assoc! host-element-style :all "unset")

      (apply-fortress-styles! host-element)

      (j/assoc! host-element :popover "auto")

      ;; IMPORTANT: Host element that has the shadow root is to be added to the web page's 'body' element
      ;; appendChild returns a Node that is the appended child (child)
      (js/document.body.appendChild host-element)

      ;; document.documentElement gives the html element
      #_(js/document.documentElement.appendChild host-element)

      ;; We explicitly return the host-element
      host-element))

#_(defn- create-shadow-dom-4
    "Creates the shadow dom after creating a host element
    Returns the created host element of the shadow dom
   "
    [tag-name host-element-class host-styles mui-root-component]

    (let [host-element (create-host-element-2 tag-name host-element-class)
          shadow-root (.attachShadow host-element #js {:mode "closed"})

          ;; Mui css 
          emotion-cache (m/create-cache #js {:key "css"
                                             :prepend true
                                             :container shadow-root})
          ;; Mui component used with Shadow dom should have its own theme 
          custom-theme (mui-support/create-shadow-root-theme shadow-root)

          ;; Component root to which the injected mui based UI is mounted
          component-root (js/document.createElement "div")]

      (set-container-styles component-root host-styles)

      ;; Add the component root to the shdow dom
      (.appendChild shadow-root component-root)

      ;; Add links for the roboto fonts to shadow dom 
      (mui-support/add-roboto-font-styles shadow-root)

      (okp-println "Going to render mui...")
      ;; This renders the main mui content
      (rdom/render [mui-root-component emotion-cache custom-theme] component-root)

      [host-element component-root]))

#_(defn create-entry-list-shadow-dom-2
    "Creates a shadow dom with a custom host element to show entry list
     Returns the created host element of the shadow dom
     "
    [host-element-class host-styles mui-root-component]
    (create-shadow-dom-4 OKP_ENTRY_LIST_TAG_NAME host-element-class host-styles mui-root-component))