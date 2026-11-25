(ns onekeepass.browser.content.otp-fields
  "Finds a page's one-time code (2FA / TOTP) field so the extension can fill the
   current code of an entry into it.

   A field counts as a one-time code field when it is a visible, editable text
   (or tel / number) input and either
   - is marked autocomplete=\"one-time-code\", or
   - its name, id, placeholder, aria-label or label reads like a one-time /
     verification / 2FA code (and not like a promo, zip or other code), with a
     length limit, if any, of 4 to 10
   A code split into one box per digit (4 to 8 inputs of maxlength 1 next to each
   other) is found as one field.

   Focusing the field shows the OneKeePass icon and the entry list limited to
   entries that have a 2FA code; picking one fills the code."
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]
   [onekeepass.browser.common.message-type-names :refer [FILL_PURPOSE_OTP]]
   [onekeepass.browser.content.context :as context]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.content.inject.input-field-okp-icon :as input-field-okp-icon]
   [onekeepass.browser.content.inject.listener-store :as listener-store]))

;; (?<![a-z]) / (?![a-z]) instead of \b so that otp_code, mfaCode etc. match too
(def ^:private code-words
  #"(?i)one[-_ ]?time|(?<![a-z])otp(?![a-z])|totp|2fa|(?<![a-z])mfa(?![a-z])|two[-_ ]?factor|2[-_ ]?step|verification|verify|security[-_ ]?code|auth(?:entication|enticator)?[-_ ]?code|passcode|sms[-_ ]?code|(?<![a-z])code(?![a-z])")

;; Codes that are not a login's one-time code
(def ^:private other-codes
  #"(?i)promo|coupon|voucher|gift|discount|referral|invite|zip|postal|post[-_ ]?code|country|area[-_ ]?code|captcha|cvv|cvc|card|phone|mobile|email")

(defn- visible? [el]
  (boolean
   (and el (.-isConnected el)
        (j/call el :checkVisibility #js {:visibilityProperty true :opacityProperty true}))))

(defn- text-like? [^js input]
  (contains? #{"text" "tel" "number" ""} (str/lower-case (or (.-type input) ""))))

(defn- editable? [^js input]
  (not (or (.-disabled input) (.-readOnly input))))

(defn- max-length [^js input]
  (let [m (.-maxLength input)]
    (when (and (number? m) (pos? m)) m)))

(defn- label-text [^js input]
  (let [labels (.-labels input)]
    (str/join " " (when labels (map #(.-innerText %) (array-seq labels))))))

(defn- describing-text [^js input]
  (str/join " " (remove str/blank? [(.-name input) (.-id input)
                                    (.getAttribute input "placeholder")
                                    (.getAttribute input "aria-label")
                                    (label-text input)])))

(defn- autocomplete-otp? [^js input]
  (str/includes? (str/lower-case (or (.getAttribute input "autocomplete") "")) "one-time-code"))

(defn otp-like-input?
  "True when a text-like input is marked or named like a one-time code field.
   Used by the login detection too, so a 2FA code box is not taken as a username"
  [^js input]
  (and (instance? js/HTMLInputElement input)
       (text-like? input)
       (or (autocomplete-otp? input)
           (let [text (describing-text input)
                 m (max-length input)]
             (and (re-find code-words text)
                  (not (re-find other-codes text))
                  (or (nil? m) (<= 4 m 10)))))))

(defn- single-otp-input? [^js input]
  (and (editable? input) (visible? input) (otp-like-input? input)))

(defn- digit-box? [^js input]
  (and (text-like? input) (editable? input) (visible? input) (= 1 (max-length input))))

(defn- split-code-group
  "The inputs of a code split into one box per digit, or nil. The boxes are
   siblings, or each wrapped in sibling elements, under one container"
  [inputs]
  (let [boxes (filter digit-box? inputs)
        by-container (group-by (fn [^js i]
                                 (let [p (.-parentElement i)]
                                   ;; Each box alone in its own wrapper: group by the
                                   ;; wrapper's parent instead
                                   (if (and p (= 1 (.-childElementCount p)))
                                     (.-parentElement p)
                                     p)))
                               boxes)]
    (some (fn [[_container group]]
            (when (<= 4 (count group) 8) (vec group)))
          by-container)))

(defn- find-otp-inputs
  "The page's one-time code field as a vec of inputs, or nil"
  [all-inputs]
  ;; The password field is never a code field
  (let [inputs (remove #(identical? % (context/password-in-page)) all-inputs)]
    (or (when-let [i (first (filter single-otp-input? inputs))] [i])
        (split-code-group inputs))))

;; ── listeners ────────────────────────────────────────────────────────────────

(defn- on-otp-focus [^js e]
  (let [input (.-target e)]
    (input-field-okp-icon/show-input-okp-icon input)
    ;; The entry list asked for from this field fills a 2FA code
    (messaging-event/set-fill-purpose FILL_PURPOSE_OTP)
    (when (str/blank? (.-value input))
      (messaging-event/send-get-entry-list))))

(defonce ^:private wired (atom #{}))

(defn identify-otp-fields
  "Looks for a one-time code field among all-inputs (the page's inputs, including
   those inside shadow roots). Called with the login detection, at load and on DOM
   changes"
  [all-inputs]
  (when-let [inputs (find-otp-inputs all-inputs)]
    (when-not (= inputs (context/otp-inputs-in-page))
      (context/store-otp-inputs inputs)
      ;; For a split code, the first box gets the icon and the list
      (let [first-input (first inputs)]
        (when-not (contains? @wired first-input)
          (swap! wired conj first-input)
          (listener-store/add-input-named-event-listener first-input "focus" on-otp-focus)
          ;; Already focused by the page (autofocus): show the list right away
          (when (and (.hasFocus js/document) (identical? first-input (.-activeElement js/document)))
            (on-otp-focus #js {:target first-input})))))))
