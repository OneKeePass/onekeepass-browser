(ns onekeepass.browser.content.inject.mui-support
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.mui-components :as m]
   [onekeepass.browser.common.utils :refer [okp-println]]))


;; See https://mui.com/material-ui/customization/shadow-dom/#how-to-use-the-shadow-dom-with-material-ui
;; for recommentations to use mui with shadow dom and that is followed

;; Used in the injected mui code
(defn create-shadow-root-theme

  [shadow-root]
  (let [host-html js/document.documentElement  #_(j/get js/document :documentElement)
        ;; Sometimes font size gets influenced by the web page's html element's font setting
        ;; and we need to adjust mui font accordingly. Otherwise it appears very small
        ;; See https://mui.com/material-ui/customization/typography/#html-font-size

        computed-style (js/window.getComputedStyle host-html) #_(j/call js/window :getComputedStyle host-html)
        font-size-str (j/get computed-style :fontSize)
        font-size-num (if (and font-size-str (not (str/blank? font-size-str)))
                        (js/parseFloat font-size-str)
                        16)]

    (m/create-theme
     (clj->js
      {;; Tells Material UI what the font-size on the html element is 
       :typography {:htmlFontSize font-size-num}
       :components
       {:MuiPopover {:defaultProps {:container shadow-root}}
        :MuiPopper  {:defaultProps {:container shadow-root}}
        ;;:MuiTooltip  {:defaultProps {:container shadow-root}}
        :MuiModal   {:defaultProps {:container shadow-root}}
        :MuiButton
        {:defaultProps
         {:variant "contained"
          :color "secondary"
          :disableElevation true
          :disableRipple false
          :size "small"}}}}))))

(def ^:private roboto-font-stylesheets
  ["fonts/roboto/300.css"
   "fonts/roboto/300-italic.css"
   "fonts/roboto/400.css"
   "fonts/roboto/400-italic.css"
   "fonts/roboto/500.css"
   "fonts/roboto/500-italic.css"
   "fonts/roboto/700.css"
   "fonts/roboto/700-italic.css"])

;; Used in the injected mui code
(defn add-roboto-font-styles
  "Adds the roboto font loading links to the shadow root as stylesheet links"
  [shadow-root]
  (doseq [stylesheet-path roboto-font-stylesheets]
    (let [link-el (js/document.createElement "link")]
      ;; Creates stylesheet links like this
      ;; e.g <link rel="stylesheet" href="moz-extension://7b0eed2d-03d5-4045-981e-52782a6b08bb/fonts/roboto/300.css">

      (j/assoc! link-el :rel "stylesheet")
      (j/assoc! link-el :href (js/chrome.runtime.getURL stylesheet-path))
      (j/call shadow-root :appendChild link-el))))


;;; Should be removed as these are moved to custom-element ns

(defn remove-shadowdom-host-element
  "Removes the shadow dom's host element selected by using a okp specific class "
  [app-host-class]
  (when-let [host-element (j/call js/document :querySelector (str "." app-host-class))]
    ;; Remove the host element from the body, which also removes its shadow root
    (j/call host-element :remove)
    (okp-println "Removed previous host-element of shadow root")))

(defn host-element-by-class [app-host-class]
  (j/call js/document :querySelector (str "." app-host-class)))
