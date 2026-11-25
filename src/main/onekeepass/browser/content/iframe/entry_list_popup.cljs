(ns onekeepass.browser.content.iframe.entry-list-popup
  "The dropdown shown under a login field (or at a fixed place for sub-frame logins
   and passkey sign-ins) listing the matching entries to fill from.

   One component for three lists, told apart by list-kind and purpose:
   - password list: entries for the site; picking one fills username + password
   - 2FA code list (purpose otp, from a one-time code field): only entries with a
     TOTP; picking one fills the current code. The entry just used to sign in on
     this tab is suggested first
   - passkey list: the site's passkeys; picking one completes the sign-in

   It can be used from the keyboard while the focus stays in the login field: the
   content script forwards ArrowUp / ArrowDown / Enter (ENTRY_LIST_KEY)."
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.message-type-names :refer [LIST_KIND_PASSKEY
                                                         RESIZE_IFRAME_ENTRY_LIST]]
   [onekeepass.browser.common.mui-components
    :refer [mui-box mui-icon-vpn-key-outlined mui-list mui-list-item-button
            mui-stack mui-typography]]
   [onekeepass.browser.common.okp-theme :as okp-theme]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.iframe.events.iframe-content-messaging :as iframe-content-messaging]
   [onekeepass.browser.content.inject.db-icons :as db-icons]
   [reagent.core :as r]
   [reagent.dom :as rd]))

(def ^:private PURPOSE_OTP "otp")

;; Above this many entries a search box is shown
(def ^:private SEARCH_THRESHOLD 5)

(defonce ^:private search-term (r/atom ""))

;; Index (in the visible rows) of the row highlighted with the arrow keys, or nil
(defonce ^:private active-index (r/atom nil))

;; The visible rows of the last render, for the keyboard handler
(defonce ^:private visible-rows (atom []))

(defn- delayed-close-popup []
  (js/window.setTimeout
   (fn [] (iframe-content-messaging/close-entry-list-popup)) 240))

;; Clickjacking hardening: act only on a genuine user gesture. A page-
;; dispatched synthetic click has isTrusted=false; a real click inside our
;; closed-shadow extension iframe has isTrusted=true. Reagent hands us the React
;; SyntheticEvent, so we also check the underlying nativeEvent. This blocks a
;; page from programmatically triggering an entry selection / fill.
(defn- trusted-event? [e]
  (boolean
   (or (j/get e :isTrusted)
       (some-> (j/get e :nativeEvent) (j/get :isTrusted)))))

;; ── data ─────────────────────────────────────────────────────────────────────

(defn- flatten-entries
  "One row per entry across the databases, in the order the app ranked them.
   Rows carry their database and whether the entry has a TOTP. For the 2FA code
   list only TOTP entries are kept and the suggested one comes first"
  [matched-entries purpose suggested]
  (let [rows (for [{:keys [db-name db-key entry-summaries otp-entry-uuids custom-icons]} matched-entries
                   :let [otp-ids (set otp-entry-uuids)]
                   summary entry-summaries]
               (assoc summary
                      :db-key db-key
                      :db-name db-name
                      :custom-icons custom-icons
                      :has-otp (contains? otp-ids (:uuid summary))
                      :suggested (and suggested
                                      (= (:db-key suggested) db-key)
                                      (= (:entry-uuid suggested) (:uuid summary)))))
        rows (if (= purpose PURPOSE_OTP) (filter :has-otp rows) rows)]
    (vec (concat (filter :suggested rows) (remove :suggested rows)))))

(defn- matches-term? [term {:keys [title secondary-title db-name]}]
  (let [term (str/lower-case (str/trim term))]
    (or (str/blank? term)
        (some #(str/includes? (str/lower-case (or % "")) term) [title secondary-title db-name]))))

;; ── selection ────────────────────────────────────────────────────────────────

(defn- select-row! [{:keys [db-key uuid]} list-kind]
  (iframe-content-messaging/send-entry-selected db-key uuid list-kind)
  (delayed-close-popup))

(defn on-entry-list-key
  "Called with an arrow / Enter key the user pressed in the login field the list
   is open for (forwarded by the content script only for trusted key events)"
  [key list-kind]
  (let [n (count @visible-rows)]
    (when (pos? n)
      (case key
        "ArrowDown" (swap! active-index #(if (nil? %) 0 (mod (inc %) n)))
        "ArrowUp" (swap! active-index #(if (nil? %) (dec n) (mod (dec %) n)))
        "Enter" (when-let [i @active-index]
                  (when-let [row (get @visible-rows i)]
                    (select-row! row list-kind)))
        nil))))

;; ── pieces ───────────────────────────────────────────────────────────────────

(defn- badge [text]
  [mui-box {:component "span"
            :sx {:font-size "10.5px" :font-weight 600 :letter-spacing "0.02em"
                 :line-height "18px" :px 0.75 :border-radius "6px"
                 :color "primary.main" :bgcolor "action.selected"
                 :white-space "nowrap"}}
   text])

;; Clickjacking hardening: show the origin the credential will be filled
;; into, but ONLY when it differs from the top page origin - i.e the login fields
;; are in a cross-origin (embedded) iframe. For a normal same-origin login this
;; stays hidden (no noise). When they differ - the user is on one site but the
;; login frame is another - it surfaces so an embedded login (legit SSO or an
;; attacker framing a real login) is noticeable.
;; The value is an origin (scheme + host + port, no path), so it is short; we wrap
;; it in full (break-all) rather than truncate, because the registrable domain is
;; at the end of the host and clipping the tail would hide the part that matters.
(defn- fill-target-notice [origin top]
  (when (and (seq origin) (seq top) (not= origin top))
    [mui-box {:sx {:mx 1.5 :mb 1 :px 1.25 :py 0.75 :border-radius "10px"
                   :bgcolor "action.hover" :border "1px solid var(--okp-divider)"}}
     [mui-typography {:variant "caption" :color "text.secondary" :display "block"}
      (u/lstr "embeddedLoginFrom")]
     [mui-typography {:variant "body2" :color "secondary.main"
                      :title origin
                      :sx {:word-break "break-all" :font-weight 500}}
      origin]]))

(defn- search-box []
  [mui-box {:sx {:px 1.5 :pb 1}}
   [:input {:type "search"
            :placeholder "Search entries"
            :value @search-term
            :on-change (fn [e]
                         (reset! search-term (-> e .-target .-value))
                         (reset! active-index nil))
            :style okp-theme/input-style}]])

(defn- entry-avatar [{:keys [db-key icon-id custom-icon-uuid custom-icons title]} list-kind]
  (cond
    (= list-kind LIST_KIND_PASSKEY)
    [mui-box {:sx {:flex "none" :width 34 :height 34 :border-radius "9px"
                   :display "flex" :align-items "center" :justify-content "center"
                   :bgcolor "action.selected" :color "primary.main"}}
     [mui-icon-vpn-key-outlined {:sx {:font-size 18}}]]

    ;; The entry has its own icon (a custom one, or a KeePass icon other than the
    ;; default key): show it
    (or (seq custom-icon-uuid) (and icon-id (not (zero? icon-id))))
    [mui-box {:sx {:flex "none" :width 34 :height 34 :border-radius "9px"
                   :display "flex" :align-items "center" :justify-content "center"
                   :bgcolor "action.hover" :font-size "19px"}}
     [db-icons/render-entry-icon
      {:db-key db-key
       :icon-id icon-id
       :custom-icon-uuid custom-icon-uuid
       :custom-icons custom-icons
       :request-custom-icon #(iframe-content-messaging/send-get-custom-icon db-key custom-icon-uuid)}]]

    ;; Default icon: a letter avatar reads better in a short list
    :else
    [okp-theme/letter-avatar title 34]))

(defn- entry-row [{:keys [title secondary-title db-name has-otp suggested] :as row}
                  {:keys [list-kind show-db? index active?]}]
  [mui-list-item-button
   {:selected active?
    :ref (fn [el] (when (and el active?) (.scrollIntoView el #js {:block "nearest"})))
    :on-mouse-enter #(reset! active-index index)
    :on-click (fn [e]
                (when (trusted-event? e)
                  (select-row! row list-kind)))
    :sx (merge {:mx 0.75 :my 0.25 :px 1 :py 0.75 :gap 1.25
                "&:hover" {:bgcolor "action.hover"}
                "&.Mui-selected, &.Mui-selected:hover" {:bgcolor "action.selected"}}
               (okp-theme/row-in-sx index))}
   [entry-avatar row list-kind]
   [mui-box {:sx {:flex 1 :min-width 0}}
    [mui-typography {:variant "body2"
                     :sx {:font-weight 600 :overflow "hidden" :text-overflow "ellipsis"
                          :white-space "nowrap"}}
     (if (str/blank? title) "(no title)" title)]
    [mui-typography {:variant "caption" :color "text.secondary" :component "div"
                     :sx {:overflow "hidden" :text-overflow "ellipsis" :white-space "nowrap"}}
     (str/join " · " (remove str/blank? [secondary-title (when show-db? db-name)]))]]
   [mui-stack {:direction "row" :spacing 0.5 :sx {:flex "none"}}
    (when suggested [badge "Just used"])
    (cond
      (= list-kind LIST_KIND_PASSKEY) [badge "Passkey"]
      has-otp [badge "2FA"])]])

(defn- empty-state [text]
  [mui-stack {:align-items "center" :spacing 0.5 :sx {:px 2 :pt 1 :pb 2}}
   [mui-typography {:variant "body2" :color "text.secondary" :sx {:text-align "center"}}
    text]])

(defn- key-hint []
  [mui-typography {:variant "caption" :color "text.disabled" :component "div"
                   :sx {:px 1.75 :pt 0.5 :pb 0.25 :font-size "11px"}}
   "↑ ↓ to choose · Enter to fill"])

;; ── main content ─────────────────────────────────────────────────────────────

(defn- main-content []
  (let [matched-entries @(iframe-content-messaging/matched-entries)
        ;; Echoed back with the selection so the background routes it to the
        ;; flow of this list (password fill, 2FA code fill or passkey sign-in)
        list-kind @(iframe-content-messaging/list-kind)
        purpose @(iframe-content-messaging/purpose)
        suggested @(iframe-content-messaging/suggested-entry)
        fill-target-origin @(iframe-content-messaging/fill-target-origin)
        top-origin @(iframe-content-messaging/top-origin)
        rows (flatten-entries matched-entries purpose suggested)
        show-db? (> (count (set (map :db-key rows))) 1)
        visible (vec (filter (partial matches-term? @search-term) rows))
        active @active-index
        title (cond
                (= list-kind LIST_KIND_PASSKEY) "Sign in with a passkey"
                (= purpose PURPOSE_OTP) "Fill 2FA code"
                :else "OneKeePass")]
    (reset! visible-rows visible)
    [mui-box {:sx (merge (okp-theme/card-sx) {:min-width 300 :pb 0.75})}
     [okp-theme/popup-header {:title title
                              :detail (some-> top-origin (str/replace #"^https?://" ""))}]
     [fill-target-notice fill-target-origin top-origin]
     (when (> (count rows) SEARCH_THRESHOLD)
       [search-box])
     (cond
       (empty? rows)
       [empty-state (if (= purpose PURPOSE_OTP)
                      "No entry for this site has a 2FA code"
                      "No matching entries")]

       (empty? visible)
       [empty-state "No entry matches your search"]

       :else
       [:<>
        [mui-list {:dense true :disable-padding true
                   :sx {:max-height 300 :overflow "auto"}}
         (doall
          (map-indexed
           (fn [i row]
             ^{:key (str (:db-key row) (:uuid row))}
             [entry-row row {:list-kind list-kind :show-db? show-db?
                             :index i :active? (= i active)}])
           visible))]
        (when-not (okp-theme/floating?)
          [key-hint])])]))

;; ── auto-resize ──────────────────────────────────────────────────────────────

(defn- send-size-to-host
  "The iframe body's content size changes are observerd and communicated to content script
  side so that the iframe size is adjusted accordingly"
  []
  (let [body (.-body js/document)
        width  (.-scrollWidth body)
        height (.-scrollHeight body)]
    (js/window.parent.postMessage
     #js {:type RESIZE_IFRAME_ENTRY_LIST
          :width width
          :height height}
     "*")))

(defn- init-auto-resize []
  (let [observer (js/ResizeObserver. (fn [_entries] (send-size-to-host)))]
    (.observe observer (.-body js/document))
    (send-size-to-host)))

;; ── entry point ──────────────────────────────────────────────────────────────

(defn init []
  ;; Keys forwarded by the content script from the login field
  (iframe-content-messaging/set-entry-list-key-handler!
   (fn [key] (on-entry-list-key key @(iframe-content-messaging/list-kind))))

  ;; Init the Iframe <-> Content communication channel
  (iframe-content-messaging/init-content-connection)

  ;; setup the size observer when the app mounts
  (init-auto-resize)

  ;; There is 'div' element with ID 'app' in entry_list_popup.html
  (rd/render [okp-theme/themed [main-content]] (.getElementById ^js/Window js/document "app")))
