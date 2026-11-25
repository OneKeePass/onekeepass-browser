(ns onekeepass.browser.common.okp-theme
  "Shared look and behaviour of the extension's popups (the iframes shown on web
   pages):
   - one MUI theme with light and dark variants that follow the system color scheme
   - the card every popup is drawn in, with entrance / exit animations
   - the header inside the card (logo, title, close button). On floating popups it
     is also the drag area: the iframe tells its host page script how far it was
     dragged (window.postMessage; the host checks the message comes from this
     iframe) and the host moves the popup
   - avatars, inputs and the animated check used by several popups"
  (:require
   [clojure.string :as str]
   [onekeepass.browser.common.message-type-names :refer [POPUP_FRAME_MESSAGE]]
   [onekeepass.browser.common.mui-components
    :refer [create-theme mui-box mui-icon-button mui-icon-close mui-stack
            mui-theme-provider mui-tooltip mui-typography]]
   [reagent.core :as r]))

;; ── design tokens ────────────────────────────────────────────────────────────

(def accent "#2f6fed")
(def accent-dark-mode "#7aa2ff")

(def ^:private light-palette
  {:mode "light"
   :primary {:main accent}
   :success {:main "#1e8e5a"}
   :background {:default "#f5f6f8" :paper "#ffffff"}
   :text {:primary "#16181d" :secondary "#5d6370" :disabled "#9aa0aa"}
   :divider "rgba(22,24,29,0.09)"
   :action {:hover "rgba(47,111,237,0.06)" :selected "rgba(47,111,237,0.12)"}})

(def ^:private dark-palette
  {:mode "dark"
   :primary {:main accent-dark-mode}
   :success {:main "#4cc38a"}
   :background {:default "#141519" :paper "#1c1e24"}
   :text {:primary "#eceef2" :secondary "#a4aab5" :disabled "#6c7280"}
   :divider "rgba(255,255,255,0.09)"
   :action {:hover "rgba(122,162,255,0.09)" :selected "rgba(122,162,255,0.17)"}})

(def ^:private ease "cubic-bezier(0.2, 0.8, 0.2, 1)")

(defn- theme-options [dark?]
  {:palette (if dark? dark-palette light-palette)
   :shape {:borderRadius 10}
   :typography {:fontFamily "Roboto, system-ui, -apple-system, 'Segoe UI', sans-serif"
                :fontSize 13
                :button {:textTransform "none" :fontWeight 600 :letterSpacing 0}
                :subtitle1 {:fontWeight 600}
                :subtitle2 {:fontWeight 600}}
   :transitions {:easing {:easeOut ease}}
   :components {:MuiButton {:defaultProps {:disableElevation true :disableRipple true}
                            :styleOverrides {:root {:borderRadius 9
                                                    :paddingInline 16
                                                    :minHeight 34
                                                    :transition (str "background-color 140ms " ease
                                                                     ", box-shadow 140ms " ease
                                                                     ", transform 80ms " ease)
                                                    "&:active" {:transform "scale(0.98)"}}}}
                :MuiIconButton {:defaultProps {:disableRipple true}}
                :MuiListItemButton {:defaultProps {:disableRipple true}
                                    :styleOverrides {:root {:borderRadius 10
                                                            :transition (str "background-color 120ms " ease)}}}
                :MuiDivider {:styleOverrides {:root {:borderColor "var(--okp-divider)"}}}}})

(defonce ^:private themes (atom {}))

(defn- theme [dark?]
  (or (get @themes dark?)
      (let [t (create-theme (clj->js (theme-options dark?)))]
        (swap! themes assoc dark? t)
        t)))

(defn- dark-query []
  (when (.-matchMedia js/window)
    (.matchMedia js/window "(prefers-color-scheme: dark)")))

(defn- system-dark? []
  (boolean (some-> (dark-query) .-matches)))

;; Keyframes and a few global rules for the popup page. Motion is dropped when the
;; user asks the system for reduced motion.
(def ^:private global-css
  (str
   "@keyframes okp-pop-in{from{opacity:0;transform:translateY(-6px) scale(.98)}to{opacity:1;transform:none}}"
   "@keyframes okp-slide-in{from{opacity:0;transform:translateX(14px)}to{opacity:1;transform:none}}"
   "@keyframes okp-pop-out{from{opacity:1;transform:none}to{opacity:0;transform:translateY(-4px) scale(.98)}}"
   "@keyframes okp-row-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}"
   "@keyframes okp-draw{to{stroke-dashoffset:0}}"
   "@keyframes okp-scale-in{from{transform:scale(.6);opacity:0}to{transform:none;opacity:1}}"
   "*{box-sizing:border-box}"
   ;; flow-root keeps the card's margin inside the body, so the iframe is sized
   ;; with room for the card's shadow
   "html,body{margin:0;background:transparent}body{display:flow-root}"
   "::-webkit-scrollbar{width:8px}::-webkit-scrollbar-thumb{background:var(--okp-divider);border-radius:8px}"
   "input:focus,select:focus{border-color:var(--okp-accent)!important;box-shadow:0 0 0 3px var(--okp-focus)}"
   "@media (prefers-reduced-motion: reduce){*{animation:none!important;transition:none!important}}"))

(defonce ^:private css-injected? (atom false))

(defn- apply-page-base! [dark?]
  (when-not @css-injected?
    (reset! css-injected? true)
    (let [style (js/document.createElement "style")]
      (set! (.-textContent style) global-css)
      (.appendChild (.-head js/document) style)))
  ;; color-scheme is left as is: a dark scheme in this frame would make the browser
  ;; paint an opaque backdrop behind it on light pages
  (let [root-style (.. js/document -documentElement -style)]
    (.setProperty root-style "--okp-divider" (if dark? (:divider dark-palette) (:divider light-palette)))
    (.setProperty root-style "--okp-accent" (if dark? accent-dark-mode accent))
    (.setProperty root-style "--okp-focus" (if dark? "rgba(122,162,255,0.25)" "rgba(47,111,237,0.18)"))))

(defn themed
  "Wraps a popup's root component in the extension theme. Follows changes of the
   system color scheme while the popup is open"
  [_child]
  (let [dark (r/atom (system-dark?))]
    (apply-page-base! @dark)
    (when-let [q (dark-query)]
      (.addEventListener q "change" (fn [e]
                                      (reset! dark (.-matches e))
                                      (apply-page-base! (.-matches e)))))
    (fn [child]
      [mui-theme-provider {:theme (theme @dark)} child])))

;; ── floating popups: close and drag through the host page script ─────────────

(defn floating?
  "True when the popup is a floating one (the host adds ?floating=1 to the iframe
   url): its header closes and drags the popup"
  []
  (boolean (re-find #"[?&]floating=1" (or (.. js/window -location -search) ""))))

(defn- post-to-host [message]
  ;; The host checks event.source is this iframe; the data carries no secret
  (js/window.parent.postMessage (clj->js (assoc message :type POPUP_FRAME_MESSAGE)) "*"))

(defonce ^:private closing? (r/atom false))

(def ^:private EXIT_MS 140)

(defn request-close!
  "Plays the exit animation, then asks the host to close this popup the same way
   its × button does (for the save-login and passkey popups that also tells the
   background the user dismissed it)"
  []
  (when-not @closing?
    (reset! closing? true)
    (js/setTimeout #(post-to-host {:action "close"}) EXIT_MS)))

(defn- drag-handlers
  "Pointer handlers that turn a drag on the header into move messages to the host.
   Screen coordinates are used: the iframe moves under the pointer while dragging"
  []
  (let [last-pos (atom nil)]
    {:on-pointer-down (fn [^js e]
                        (when (and (floating?) (= 0 (.-button e))
                                   ;; Not when pressing a button inside the header
                                   (not (.closest (.-target e) "button")))
                          (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                          (reset! last-pos [(.-screenX e) (.-screenY e)])))
     :on-pointer-move (fn [^js e]
                        (when-let [[x y] @last-pos]
                          (let [nx (.-screenX e) ny (.-screenY e)]
                            (reset! last-pos [nx ny])
                            (post-to-host {:action "drag" :dx (- nx x) :dy (- ny y)}))))
     :on-pointer-up (fn [^js e]
                      (reset! last-pos nil)
                      (when (.hasPointerCapture (.-currentTarget e) (.-pointerId e))
                        (.releasePointerCapture (.-currentTarget e) (.-pointerId e))))}))

;; ── shared styles ────────────────────────────────────────────────────────────

(def CARD_MARGIN
  "Room around the card inside the iframe for its shadow (popup-host offsets the
   anchored list by it so the card lines up with the field)"
  12)

(defn card-sx
  "The outer box of a popup. variant :notification slides in from the right (the
   save-login and passkey prompts at the top right); others pop in"
  ([] (card-sx nil))
  ([variant]
   ;; One border declaration with the color in it: the sx map has no key order, so a
   ;; separate border-color could be overridden by the border shorthand
   {:bgcolor "background.paper"
    :color "text.primary"
    :border "1px solid var(--okp-divider)"
    :border-radius "14px"
    ;; The shadow must fit in the margin: the iframe clips what goes beyond it
    :box-shadow "0 6px 16px rgba(15,23,42,0.14), 0 1px 3px rgba(15,23,42,0.08)"
    :overflow "hidden"
    :margin (str CARD_MARGIN "px")
    :animation (if @closing?
                 (str "okp-pop-out " EXIT_MS "ms " ease " both")
                 (str (if (= variant :notification) "okp-slide-in" "okp-pop-in")
                      " 200ms " ease " both"))}))

(defn row-in-sx
  "Staggered entrance of the n-th list row (capped so long lists do not lag)"
  [n]
  {:animation (str "okp-row-in 220ms " ease " both")
   :animation-delay (str (* 28 (min n 8)) "ms")})

(def input-style
  "Plain inputs and selects inside popups"
  {:width "100%" :box-sizing "border-box" :padding "8px 10px" :font-size "13px"
   :border "1px solid var(--okp-divider)" :border-radius "9px"
   :font-family "inherit" :background "transparent" :color "inherit"
   :outline "none" :transition "border-color 120ms, box-shadow 120ms"})

;; ── pieces ───────────────────────────────────────────────────────────────────

(defn logo
  "The OneKeePass logo (an extension page, so the icon is reachable by path)"
  [size]
  [:img {:src "icons/onekeepass_32x32.png" :alt "" :width size :height size
         :style {:display "block" :border-radius "6px" :flex "none"}}])

(defn popup-header
  "The header row at the top of a popup card: logo, title, an optional detail on
   the right, and on floating popups a close button and the drag area"
  [{:keys [title detail]}]
  (let [handlers (drag-handlers)]
    (fn [{:keys [title detail]}]
      (let [float? (floating?)]
        [mui-stack (merge {:direction "row" :align-items "center" :spacing 1
                           :sx {:px 1.75 :pt 1.5 :pb 1
                                :cursor (if float? "grab" "default")
                                :user-select "none" :touch-action "none"
                                "&:active" {:cursor (if float? "grabbing" "default")}}}
                          (when float? handlers))
         [logo 20]
         [mui-typography {:variant "subtitle2"
                          :sx {:flex 1 :font-size "13.5px" :letter-spacing "-0.005em"
                               :white-space "nowrap" :overflow "hidden" :text-overflow "ellipsis"}}
          title]
         (when-not (str/blank? detail)
           [mui-typography {:variant "caption" :color "text.secondary" :title detail
                            :sx {:max-width "50%" :overflow "hidden" :text-overflow "ellipsis"
                                 :white-space "nowrap"}}
            detail])
         (when float?
           [mui-tooltip {:title "Close" :enterDelay 600}
            [mui-icon-button {:size "small" :aria-label "Close"
                              :on-click request-close!
                              :sx {:ml 0.25 :mr -0.75 :color "text.secondary"
                                   "&:hover" {:bgcolor "action.hover" :color "text.primary"}}}
             [mui-icon-close {:sx {:font-size 18}}]]])]))))

(def ^:private avatar-colors
  ["#2f6fed" "#7c4dff" "#0f9d58" "#e8710a" "#d93025" "#1a73e8" "#a142f4" "#12a4af"])

(defn letter-avatar
  "A rounded square with the first letter of text, colored from the text so the
   same site always gets the same color"
  [text size]
  (let [t (str/trim (or text ""))
        ;; The u flag makes \p{L} / \p{N} mean any letter / digit
        letter (or (some-> (re-find (js/RegExp. "[\\p{L}\\p{N}]" "u") t) str/upper-case) "•")
        color (nth avatar-colors (mod (reduce #(+ (* 31 %1) (.charCodeAt %2 0)) 7 t)
                                      (count avatar-colors)))]
    [mui-box {:sx {:flex "none" :width size :height size :border-radius "9px"
                   :display "flex" :align-items "center" :justify-content "center"
                   :bgcolor color :color "#fff" :font-weight 700
                   :font-size (str (js/Math.round (* size 0.45)) "px")}}
     letter]))

(defn success-check
  "An animated check mark in a circle"
  [size]
  [:svg {:width size :height size :view-box "0 0 52 52"
         :style {:animation (str "okp-scale-in 260ms " ease " both")}}
   [:circle {:cx 26 :cy 26 :r 24 :fill "none" :stroke "currentColor" :stroke-width 3
             :opacity 0.25}]
   [:path {:d "M15 27 l7 7 l15 -16" :fill "none" :stroke "currentColor" :stroke-width 4
           :stroke-linecap "round" :stroke-linejoin "round"
           :style {:stroke-dasharray 40 :stroke-dashoffset 40
                   :animation (str "okp-draw 320ms " ease " 120ms forwards")}}]])
