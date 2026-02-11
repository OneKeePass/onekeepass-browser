(ns onekeepass.browser.content.core
  (:require
   [onekeepass.browser.common.message-type-names :refer [BACKGROUND_ERROR
                                                         NO_BROWSER_ENABLED_DB
                                                         NO_MATCHING_ENTRIES
                                                         POPUP_ACTION
                                                         IFRAME_POPUP_ACTION_INITIATE
                                                         REDETECT_FIELDS
                                                         SELECTED_ENTRY_DETAIL
                                                         SHOW_ENTRY_LIST]]
   [onekeepass.browser.common.utils :as u]
   [onekeepass.browser.content.iframe.popup-host :as popup-host]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.content.events.popup]
   ;;[onekeepass.browser.content.form-fields-2 :as form-fields]
   [onekeepass.browser.content.form-fields :as form-fields]
   [onekeepass.browser.content.inject.entry-list :as entry-list]
   [onekeepass.browser.content.inject.message-popup :as message-box]
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

;; This is the content script's entry point
;; See shadow-cljs.edn where this fn is refered in ':init-fn onekeepass.browser.content/init'
;; This is called in each of the frames in a page opened in a tab as we use ' "all_frames": true ' in the manifest.json
(defn init []

  #_(u/okp-println "Content script main entry" js/document.location.href)

  ;; These are various callback fns that are called for various messages received from background script
  #_(messaging-event/register-message-handlers
   {REDETECT_FIELDS form-fields/identify-page

    SHOW_ENTRY_LIST entry-list/show-entry-summaries
    SELECTED_ENTRY_DETAIL form-fields/fill-inputs

    NO_BROWSER_ENABLED_DB message-box/msg-box-show
    NO_MATCHING_ENTRIES message-box/msg-box-show

    BACKGROUND_ERROR message-box/msg-box-show

    ;;POPUP_ACTION message-box/popup-action-show
    
    IFRAME_POPUP_ACTION_INITIATE popup-host/create-main-popup})
  
  
  (messaging-event/register-message-handlers
   {
    REDETECT_FIELDS form-fields/identify-page
    
    SHOW_ENTRY_LIST popup-host/create-entry-list-popup
    SELECTED_ENTRY_DETAIL form-fields/fill-inputs
    
    POPUP_ACTION popup-host/create-main-popup
    
    NO_BROWSER_ENABLED_DB popup-host/create-msg-popup
    NO_MATCHING_ENTRIES popup-host/create-msg-popup
    
    BACKGROUND_ERROR popup-host/create-msg-popup
    
    ;; IFRAME_POPUP_ACTION_INITIATE popup-host/create-main-popup
    
    })

  ;; Notify the background which uses this to record the frame-id of the content script
  (messaging-event/send-content-script-loading)

  ;; Register to observe any div or form nodes are added dynamically that may have the login fields
  ;; We are using the same fn 'identify-page' here and in the page itself
  (mt/inputs-on-dom-observation form-fields/identify-page)

  ;; Start to look for the login fields
  (form-fields/identify-page))