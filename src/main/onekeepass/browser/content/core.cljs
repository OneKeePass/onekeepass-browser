(ns onekeepass.browser.content.core
  (:require
   [onekeepass.browser.common.message-type-names :refer [BACKGROUND_ERROR
                                                         NO_BROWSER_ENABLED_DB
                                                         NO_MATCHING_ENTRIES
                                                         NO_MATCHING_PASSKEYS
                                                         POPUP_ACTION
                                                         REDETECT_FIELDS
                                                         SELECTED_ENTRY_DETAIL
                                                         SHOW_ENTRY_LIST
                                                         SHOW_PASSKEY_CREATE_POPUP
                                                         SHOW_PASSKEY_GROUPS
                                                         SHOW_PASSKEY_LIST]]
   [onekeepass.browser.common.utils :as u :refer [is-firefox-browser?]]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.content.events.popup] ;;[onekeepass.browser.content.form-fields-2 :as form-fields]
   [onekeepass.browser.content.form-fields :as form-fields]
   [onekeepass.browser.content.iframe.popup-host :as popup-host]
   [onekeepass.browser.content.mutation-tracking :as mt]))

;; https://github.com/applied-science/js-interop?tab=readme-ov-file

#_(defn page-language
    "Attempts to detect the page's language.
   Prioritizes the 'lang' attribute on the <html> tag.
   Falls back to English if no specific language is detected or supported."
    []
    (let [html-el (js/document.querySelector "html")
          lang-attr (when html-el (j/get html-el "lang"))
          ;; Normalize lang attribute to a two-letter code (e.g., "en-US" -> "en")
          normalized-lang (when lang-attr (str/lower-case (subs lang-attr 0 (min 2 (count lang-attr)))))]
      normalized-lang))

;; Cost reduction: the content script is injected into every frame
;; (all_frames: true), including ad / pixel / blank sub-frames that can never hold
;; a usable login. Skip detection in a sub-frame whose document is blank/data, or
;; that is truly pixel-sized. We never skip by origin - legitimate SSO logins are
;; cross-origin sub-frames - and we require BOTH dimensions to be tiny, because a
;; real login widget (e.g Charles Schwab's) can be short but is never tiny in both
;; width and height. (An earlier single-dimension < 100 check wrongly skipped such
;; compact login frames.)
(defn- skippable-frame? []
  (let [sub-frame? (not (identical? js/window (.-top js/window)))
        href (.. js/document -location -href)]
    (and sub-frame?
         (or (= href "about:blank")
             (= href "about:srcdoc")
             (.startsWith href "data:")
             (and (< (.-innerWidth js/window) 50)
                  (< (.-innerHeight js/window) 50))))))

;; Observer-driven re-detection. The cheap light-DOM password check short-circuits
;; the fuller identify-page scan on login-less but churny pages (feeds, SPAs) so a
;; burst of unrelated DOM changes does not cost a full scan.
;; NOTE: querySelector does not pierce shadow DOM, so a login injected
;; *dynamically* inside shadow DOM is not re-detected by this path; such logins
;; present at load are still caught by the unconditional initial identify-page.
(defn- detect-on-mutation []
  (when (js/document.querySelector "input[type=password]")
    (form-fields/identify-page)))

;; Login-detection part of the content script. Skipped entirely when the user
;; has disabled this site (the disabled-sites list in okp-settings); since these
;; handlers are then never registered, the background's SHOW_ENTRY_LIST / fill
;; messages are simply ignored on such a page.
(defn- start-login-detection []
  (messaging-event/register-message-handlers
   {REDETECT_FIELDS form-fields/identify-page

    SHOW_ENTRY_LIST popup-host/create-entry-list-popup
    SELECTED_ENTRY_DETAIL form-fields/fill-inputs})

  ;; Register to observe any div or form nodes added dynamically that may carry
  ;; the login fields. Debounced + gated (detect-on-mutation) so a churny but
  ;; login-less page does not pay for a full scan per mutation.
  ;; TODO: Needs to check more sites as in some sites we need to use 'Redetect' action
  ;; (mt/inputs-on-dom-observation detect-on-mutation)

  (mt/inputs-on-dom-observation form-fields/identify-page)

  ;; Initial pass. Unconditional (not behind the light-DOM password check) so a
  ;; login whose inputs live in shadow DOM (e.g hbomax, troweprice) is still
  ;; detected at load.
  (form-fields/identify-page))

;; This is the content script's entry point
;; See shadow-cljs.edn where this fn is refered in ':init-fn onekeepass.browser.content/init'
;; This is called in each of the frames in a page opened in a tab as we use ' "all_frames": true ' in the manifest.json
(defn init []

  #_(u/okp-println "Content script main entry" js/document.location.href)

  ;; These are various callback fns that are called for various messages received from background script

  ;; Skip all login-detection work in tiny / blank / data sub-frames
  ;; (ad / pixel iframes). Such a frame never holds a login and is never targeted
  ;; by login messages, so there is nothing to register or observe.
  (when-not (skippable-frame?)
    (messaging-event/register-message-listener)

    ;; These handlers stay registered even when the user has disabled this site:
    ;; - the toolbar-click main popup is hosted by this content script; without
    ;;   POPUP_ACTION the user could never re-enable the site from the page
    ;; - the passkey popups stay live because per-site disable covers login-form
    ;;   detection only; passkeys are governed solely by the global
    ;;   passkeys-enabled setting (and on Firefox skipping them would hang
    ;;   navigator.credentials calls - the MAIN world intercept has no
    ;;   fallback timeout)
    (messaging-event/register-message-handlers
     {POPUP_ACTION popup-host/create-main-popup

      SHOW_PASSKEY_LIST popup-host/create-passkey-list-popup
      SHOW_PASSKEY_CREATE_POPUP popup-host/create-passkey-create-popup

      NO_BROWSER_ENABLED_DB popup-host/create-msg-popup
      NO_MATCHING_ENTRIES popup-host/create-msg-popup
      NO_MATCHING_PASSKEYS popup-host/create-msg-popup

      BACKGROUND_ERROR popup-host/create-msg-popup

      ;; SHOW_PASSKEY_GROUPS #(u/okp-println "SHOW_PASSKEY_GROUPS is clicked")

      ;; IFRAME_POPUP_ACTION_INITIATE popup-host/create-main-popup
      })

    ;; Firefox only: relay OKP_PASSKEY_CREATE/GET window messages to the background
    (when (is-firefox-browser?)
      (messaging-event/register-firefox-passkey-relay))

    ;; Notify the background which uses this to record the frame-id of the content script
    ;; The response tells whether the user has disabled this site (checked by the
    ;; background against the top-level tab url, so cross origin login sub-frames
    ;; of a disabled page are gated too)
    (messaging-event/send-content-script-loading
     (fn [site-disabled?]
       (when-not site-disabled?
         (start-login-detection))))))