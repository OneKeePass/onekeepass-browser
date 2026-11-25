(ns onekeepass.browser.content.credential-capture
  "Notices when the user submits a login, signup or change-password form and hands
   the submitted username and password to the background, which may then offer to
   save them (see background ns credential-save).

   Security notes
   - Only genuine user actions count: the event must be trusted and the user must
     have pressed a key or a pointer on the page within the last few seconds. A
     submit the page fires on its own (requestSubmit, or a script clicking the
     submit button) is a trusted event too, so isTrusted alone is not enough.
   - Only visible password fields with a value are read. The values are sent to
     the background with chrome.runtime.sendMessage, which the page cannot see,
     and are not kept here beyond a short duplicate-suppression window.
   - The background, not this script, decides whether the site may be saved
     (https, same-origin frame, not disabled, not a private window).
   - Installed only on sites where login detection is enabled."
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.message-type-names :refer [CREDENTIAL_FORM_GONE
                                                         CREDENTIAL_SUBMITTED
                                                         CREDENTIAL_USERNAME_SUBMITTED]]
   [onekeepass.browser.common.regex-utils :as regex-utils]
   [onekeepass.browser.content.context :as context]))

;; ── DOM helpers ──────────────────────────────────────────────────────────────

(defn- visible? [el]
  (boolean
   (and el (.-isConnected el)
        (j/call el :checkVisibility #js {:visibilityProperty true :opacityProperty true}))))

(defn- event-target
  "The element the event really happened on, inside any shadow root"
  [^js e]
  (let [path (when (.-composedPath e) (.composedPath e))]
    (if (and path (pos? (.-length path)))
      (aget path 0)
      (.-target e))))

(defn- parent-across-shadow [^js node]
  (or (.-parentNode node)
      ;; A shadow root's parent is its host
      (.-host node)))

(defn- closest-form
  "The form an element belongs to, looking across shadow root boundaries"
  [^js el]
  (or (when (instance? js/HTMLInputElement el) (.-form el))
      (when (instance? js/HTMLButtonElement el) (.-form el))
      (loop [node el]
        (cond
          (nil? node) nil
          (instance? js/HTMLFormElement node) node
          :else (recur (parent-across-shadow node))))))

(defn- inputs-in
  "Inputs of a form (its elements, including those tied to it by the form
   attribute), or of the whole document when scope is nil. The document case
   also takes in the login inputs the page detection found inside shadow roots"
  [scope]
  (if scope
    (filterv #(instance? js/HTMLInputElement %) (array-seq (.-elements scope)))
    (let [light (array-seq (js/document.querySelectorAll "input"))
          detected (remove nil? [(context/username-in-page) (context/password-in-page)])]
      (vec (distinct (concat light detected))))))

(defn- input-type [^js input]
  (str/lower-case (or (.-type input) "text")))

(defn- password-input? [input]
  (= "password" (input-type input)))

(defn- username-like-input? [input]
  (contains? #{"text" "email" "tel" ""} (input-type input)))

(defn- has-value? [^js input]
  (not (str/blank? (.-value input))))

(defn- before?
  "True when node a comes before node b in document order"
  [^js a ^js b]
  (pos? (bit-and (.compareDocumentPosition a b) js/Node.DOCUMENT_POSITION_FOLLOWING)))

(defn- autocomplete-of [^js input]
  (str/lower-case (or (.getAttribute input "autocomplete") "")))

(defn- find-username
  "The username submitted with the password field: the field the page detection
   identified when it is filled, otherwise the last filled visible text/email field
   before the password field, preferring one marked as username/email"
  [inputs first-password]
  (let [detected (context/username-in-page)]
    (if (and detected (visible? detected) (has-value? detected)
             (or (nil? first-password) (some #(identical? % detected) inputs)))
      (.-value detected)
      (let [candidates (->> inputs
                            (filter username-like-input?)
                            (filter visible?)
                            (filter has-value?)
                            (filter #(or (nil? first-password) (before? % first-password))))
            marked (filter #(re-find #"username|email" (autocomplete-of %)) candidates)]
        (some-> (or (last marked) (last candidates)) .-value)))))

;; ── classification ───────────────────────────────────────────────────────────

(defn- classify
  "From the filled visible password fields of the submitted form, works out what
   was submitted. Returns {:form-kind :password} or nil when it is unclear:
   - one field:     a login, or a signup when it is marked autocomplete=new-password
   - two fields:    equal -> signup (new + confirm); different -> change (current + new)
   - three fields:  current + new + confirm; the last two must be equal"
  [passwords]
  (let [values (mapv #(.-value %) passwords)]
    (case (count values)
      1 {:form-kind (if (= "new-password" (autocomplete-of (first passwords))) "signup" "login")
         :password (first values)}
      2 (if (= (first values) (second values))
          {:form-kind "signup" :password (first values)}
          {:form-kind "change-password" :password (second values)})
      3 (when (= (nth values 1) (nth values 2))
          {:form-kind "change-password" :password (nth values 2)})
      nil)))

;; ── user activity ────────────────────────────────────────────────────────────

;; Time of the last trusted key press or pointer press on the page. Events a page
;; script dispatches are not trusted and do not move it
(defonce ^:private last-user-input-at (atom 0))

(def ^:private USER_INPUT_WINDOW_MS 5000)

(defn- note-user-input! [^js e]
  (when (.-isTrusted e)
    (reset! last-user-input-at (.now js/Date))))

(defn- recent-user-input? []
  (< (- (.now js/Date) @last-user-input-at) USER_INPUT_WINDOW_MS))

;; ── sending ──────────────────────────────────────────────────────────────────

;; The same submit is often seen several times (click, Enter, submit event).
;; The last sent value is kept only for this short window.
(def ^:private DUPLICATE_WINDOW_MS 2000)

(defonce ^:private last-sent (atom nil))

(defn- duplicate? [fingerprint]
  (let [{:keys [fp at]} @last-sent]
    (and (= fp fingerprint) (< (- (.now js/Date) at) DUPLICATE_WINDOW_MS))))

(defn- remember-sent! [fingerprint]
  (reset! last-sent {:fp fingerprint :at (.now js/Date)})
  (js/setTimeout (fn []
                   (when (= fingerprint (:fp @last-sent))
                     (reset! last-sent nil)))
                 DUPLICATE_WINDOW_MS))

(defn- send-to-background [message]
  (try
    (js/chrome.runtime.sendMessage (clj->js message)
                                   ;; Touching lastError avoids console noise when
                                   ;; the background is restarting
                                   (fn [_] (let [_ js/chrome.runtime.lastError])))
    (catch :default _e
      ;; Extension context gone (extension reloaded): nothing to do
      nil)))

(defn- any-visible-password-field? []
  (or (visible? (context/password-in-page))
      (some visible? (array-seq (js/document.querySelectorAll "input[type=password]")))))

(defn- watch-form-gone!
  "Single page apps often log in without loading a new page. When every password
   field has left the page shortly after the submit, tell the background the login
   went through. A failed login leaves (or re-renders) the password field"
  []
  (let [sent? (atom false)]
    (doseq [delay-ms [1000 2500 5000]]
      (js/setTimeout (fn []
                       (when (and (not @sent?) (not (any-visible-password-field?)))
                         (reset! sent? true)
                         (send-to-background {:message-type CREDENTIAL_FORM_GONE})))
                     delay-ms))))

(defn- capture-scope!
  "Reads the filled password fields of scope and sends what was submitted"
  [scope]
  (let [inputs (inputs-in scope)
        passwords (->> inputs (filter password-input?) (filter visible?) (filter has-value?) vec)]
    (if (empty? passwords)
      ;; First step of a two-step login: only the username is on this page
      (when (and (context/username-in-page) (nil? (context/password-in-page)))
        (when-let [username (find-username inputs nil)]
          (when-not (duplicate? (str "u:" username))
            (remember-sent! (str "u:" username))
            (send-to-background {:message-type CREDENTIAL_USERNAME_SUBMITTED
                                 :username username}))))
      (when-let [{:keys [form-kind password]} (classify passwords)]
        (let [username (or (find-username inputs (first passwords)) "")
              fingerprint (str form-kind ":" username ":" password)]
          (when-not (duplicate? fingerprint)
            (remember-sent! fingerprint)
            (send-to-background {:message-type CREDENTIAL_SUBMITTED
                                 :username username
                                 :password password
                                 :form-kind form-kind})
            (watch-form-gone!)))))))

(defn- capture!
  "Reads the submitted login in scope (a form, or the whole page when nil) and
   sends it to the background. Does nothing unless the user just used the page"
  [scope]
  (when (recent-user-input?)
    (capture-scope! scope)))

;; ── triggers ─────────────────────────────────────────────────────────────────

(defn- button-text [^js el]
  (str/join " " (remove str/blank? [(.-innerText el) (.-value el)
                                    (.getAttribute el "aria-label")])))

(defn- submit-control
  "The submit-like control a click landed on, or nil. Inside a form, a submit
   button (a <button> without a type is one). Outside a form (script driven
   logins), a button whose text reads like log in / sign up / next"
  [^js target]
  (let [control (loop [node target depth 0]
                  (cond
                    (or (nil? node) (> depth 6)) nil
                    (and (instance? js/Element node)
                         (or (instance? js/HTMLButtonElement node)
                             (and (instance? js/HTMLInputElement node)
                                  (contains? #{"submit" "image" "button"} (input-type node)))
                             (= "button" (.getAttribute node "role"))))
                    node
                    :else (recur (parent-across-shadow node) (inc depth))))]
    (when control
      (let [form (closest-form control)
            submit-type? (contains? #{"submit" "image"} (str/lower-case (or (.-type control) "")))]
        (cond
          (and form submit-type?) control
          form nil
          (or submit-type?
              (.test regex-utils/login-keywords-regex (button-text control))
              (.test regex-utils/signup-keywords-regex (button-text control)))
          control
          :else nil)))))

(defn- on-submit [^js e]
  (when (.-isTrusted e)
    (let [form (event-target e)]
      (when (instance? js/HTMLFormElement form)
        (capture! form)))))

(defn- on-click [^js e]
  (when (.-isTrusted e)
    (when-let [control (submit-control (event-target e))]
      (capture! (closest-form control)))))

(defn- on-key-down [^js e]
  (note-user-input! e)
  (when (and (.-isTrusted e) (= "Enter" (.-key e)))
    (let [target (event-target e)]
      (when (and (instance? js/HTMLInputElement target)
                 (or (password-input? target) (username-like-input? target)))
        (capture! (closest-form target))))))

(defonce ^:private installed? (atom false))

(defn install!
  "Called once from the content script when login detection starts on this page.
   Listens in the capture phase so the values are read before the page's own
   handlers can clear the fields"
  []
  (when-not @installed?
    (reset! installed? true)
    (.addEventListener js/window "pointerdown" note-user-input! #js {:capture true})
    (.addEventListener js/window "submit" on-submit #js {:capture true})
    (.addEventListener js/window "click" on-click #js {:capture true})
    (.addEventListener js/window "keydown" on-key-down #js {:capture true})))
