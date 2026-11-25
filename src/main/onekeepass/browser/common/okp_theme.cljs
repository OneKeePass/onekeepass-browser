(ns onekeepass.browser.common.okp-theme
  "Shared look of the extension's popups (the iframes shown on web pages): one MUI
   theme with light and dark variants that follow the system color scheme, and the
   card style used as the outer box of every popup."
  (:require
   [onekeepass.browser.common.mui-components :refer [create-theme mui-theme-provider]]
   [reagent.core :as r]))

;; ── design tokens ────────────────────────────────────────────────────────────

(def accent "#2f6fed")
(def accent-dark-mode "#7aa2ff")

(def ^:private light-palette
  {:mode "light"
   :primary {:main accent}
   :success {:main "#1e8e5a"}
   :background {:default "#f5f6f8" :paper "#ffffff"}
   :text {:primary "#1b1d22" :secondary "#5d6370" :disabled "#9aa0aa"}
   :divider "rgba(27,29,34,0.10)"
   :action {:hover "rgba(47,111,237,0.07)" :selected "rgba(47,111,237,0.13)"}})

(def ^:private dark-palette
  {:mode "dark"
   :primary {:main accent-dark-mode}
   :success {:main "#4cc38a"}
   :background {:default "#141519" :paper "#1d1f25"}
   :text {:primary "#eceef2" :secondary "#a4aab5" :disabled "#6c7280"}
   :divider "rgba(255,255,255,0.10)"
   :action {:hover "rgba(122,162,255,0.10)" :selected "rgba(122,162,255,0.18)"}})

(defn- theme-options [dark?]
  {:palette (if dark? dark-palette light-palette)
   :shape {:borderRadius 10}
   :typography {:fontFamily "Roboto, system-ui, -apple-system, 'Segoe UI', sans-serif"
                :fontSize 13
                :button {:textTransform "none" :fontWeight 600}
                :subtitle1 {:fontWeight 600}
                :subtitle2 {:fontWeight 600}}
   :components {:MuiButton {:defaultProps {:disableElevation true}
                            :styleOverrides {:root {:borderRadius 8 :paddingInline 14}}}
                :MuiListItemButton {:styleOverrides {:root {:borderRadius 8}}}
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

(defn- apply-page-base! [dark?]
  ;; The iframe page itself: no default margin, transparent so the card's rounded
  ;; corners show the web page behind them
  (let [style (.. js/document -body -style)
        root (.-documentElement js/document)]
    (set! (.-margin style) "0")
    (set! (.-background style) "transparent")
    ;; color-scheme is left as is: a dark scheme in this frame would make the
    ;; browser paint an opaque backdrop behind it on light pages
    (.setProperty (.-style root) "--okp-divider"
                  (if dark? (:divider dark-palette) (:divider light-palette)))))

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

;; ── shared styles ────────────────────────────────────────────────────────────

(defn- under-drag-handle?
  "True when the popup host shows a drag handle above this iframe (it adds
   ?handle=1 to the iframe url): the card then joins the handle with flat top
   corners"
  []
  (boolean (re-find #"[?&]handle=1" (or (.. js/window -location -search) ""))))

(defn card-sx
  "The outer box of a popup"
  []
  ;; One border declaration with the color in it: the sx map has no key order, so a
  ;; separate border-color could be overridden by the border shorthand
  (merge {:bgcolor "background.paper"
          :color "text.primary"
          :border "1px solid var(--okp-divider)"
          :border-radius "12px"
          :box-shadow "0 10px 24px rgba(15,23,42,0.16), 0 2px 6px rgba(15,23,42,0.08)"
          :overflow "hidden"
          :margin "6px"}
         (when (under-drag-handle?)
           {:border-top "none"
            :border-top-left-radius 0
            :border-top-right-radius 0
            :margin "0 6px 10px 6px"})))

(def input-style
  "Plain inputs and selects inside popups"
  {:width "100%" :box-sizing "border-box" :padding "7px 9px" :font-size "13px"
   :border "1px solid var(--okp-divider)" :border-radius "8px"
   :font-family "inherit" :background "transparent" :color "inherit"
   :outline "none"})
