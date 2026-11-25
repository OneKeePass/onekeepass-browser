(ns onekeepass.browser.common.message-type-names)


;; Used as message types for the message send from content to background

#_(def INPUT_ICON_CLICKED "INPUT_ICON_CLICKED")

(def GET_ENTRY_LIST "Sends this message to get entry summaries for the url" "GET_ENTRY_LIST")

(def CONTENT_SCRIPT_LOADING "CONTENT_SCRIPT_LOADING")

(def CONTENT_LOGIN_FIELDS_IDENTIFIED "CONTENT_LOGIN_FIELDS_IDENTIFIED")

(def ENTRY_SELECTED "The selected entry uuid is sent to get details" "ENTRY_SELECTED")

(def GET_CUSTOM_ICON "GET_CUSTOM_ICON")

(def START_ASSOCIATION "START_ASSOCIATION")


;; Used as message types for the message send from background to content

(def SHOW_ENTRY_LIST "SHOW_ENTRY_LIST")

(def BACKGROUND_ERROR "BACKGROUND_ERROR")

(def POPUP_ACTION "POPUP_ACTION")

(def CONTENT_FRAME_ID "CONTENT_FRAME_ID")

(def NO_BROWSER_ENABLED_DB "NO_BROWSER_ENABLED_DB")

(def NO_MATCHING_ENTRIES "NO_MATCHING_ENTRIES")

(def SELECTED_ENTRY_DETAIL "SELECTED_ENTRY_DETAIL")

(def CUSTOM_ICON_LOADED "CUSTOM_ICON_LOADED")

(def IFRAME_POPUP_ACTION_INITIATE "IFRAME_POPUP_ACTION_INITIATE")

;; Passkey (WebAuthn) message types — background <-> content
(def SHOW_PASSKEY_LIST "SHOW_PASSKEY_LIST")

(def NO_MATCHING_PASSKEYS "NO_MATCHING_PASSKEYS")

;; Passkey creation popup — background -> content -> iframe -> content -> background
(def SHOW_PASSKEY_CREATE_POPUP "SHOW_PASSKEY_CREATE_POPUP")
(def CLOSE_PASSKEY_CREATE_POPUP "CLOSE_PASSKEY_CREATE_POPUP")
(def RESIZE_IFRAME_PASSKEY_CREATE "RESIZE_IFRAME_PASSKEY_CREATE")
(def PASSKEY_CREATE_CONFIRMED "PASSKEY_CREATE_CONFIRMED")
(def PASSKEY_CREATE_CANCELLED "PASSKEY_CREATE_CANCELLED")
(def PASSKEY_CREATE_FALLBACK  "PASSKEY_CREATE_FALLBACK")
(def PASSKEY_GET_CANCELLED    "PASSKEY_GET_CANCELLED")    ;; content→bg: user dismissed passkey-list popup via × button  ;; iframe→content→bg: detach proxy + reject, fall back to browser default

;; Passkey creation multi-step flow — DB → Group → Entry
(def PASSKEY_DB_CHOSEN      "PASSKEY_DB_CHOSEN")      ;; iframe→content→bg: DB selected, fetch groups
(def SHOW_PASSKEY_GROUPS    "SHOW_PASSKEY_GROUPS")    ;; bg→content→iframe: group list for chosen DB
(def PASSKEY_GROUP_CHOSEN   "PASSKEY_GROUP_CHOSEN")   ;; iframe→content→bg: group selected, fetch entries
(def SHOW_PASSKEY_ENTRIES   "SHOW_PASSKEY_ENTRIES")   ;; bg→content→iframe: entry list for chosen group
(def PASSKEY_CREATE_SUCCESS "PASSKEY_CREATE_SUCCESS") ;; bg→content→iframe: passkey saved successfully

;; Firefox passkey relay — MAIN world <-> isolated world <-> background
;; MAIN world → isolated world → background (via window.postMessage then chrome.runtime.sendMessage)
(def OKP_PASSKEY_CREATE "OKP_PASSKEY_CREATE")
(def OKP_PASSKEY_GET "OKP_PASSKEY_GET")
;; background → isolated world → MAIN world (via tabs.sendMessage then window.postMessage)
(def OKP_PASSKEY_RESOLVE_CREATE "OKP_PASSKEY_RESOLVE_CREATE")
(def OKP_PASSKEY_RESOLVE_GET "OKP_PASSKEY_RESOLVE_GET")
(def OKP_PASSKEY_REJECT "OKP_PASSKEY_REJECT")
;; background → isolated world → MAIN world: delegate the pending request to the original navigator.credentials.create()
(def OKP_PASSKEY_BROWSER_DEFAULT "OKP_PASSKEY_BROWSER_DEFAULT")

;;;;

;; Settings popup message types — main popup iframe → content → popup-host
(def SHOW_SETTINGS_POPUP "SHOW_SETTINGS_POPUP")
(def CLOSE_SETTINGS_POPUP "CLOSE_SETTINGS_POPUP")
(def RESIZE_IFRAME_SETTINGS "RESIZE_IFRAME_SETTINGS")

;; Main popup iframe → background: add or remove the current site in the
;; disabled-sites list of okp-settings
(def TOGGLE_SITE_DISABLED "TOGGLE_SITE_DISABLED")

(def REDETECT_FIELDS "REDETECT_FIELDS")

;; Which list an entry-list popup shows. Sent with SHOW_ENTRY_LIST / SHOW_PASSKEY_LIST
;; and echoed back with ENTRY_SELECTED, so a pick in the password list always fills a
;; password and a pick in the passkey list always completes the passkey sign-in,
;; whatever else is pending in the tab.
(def LIST_KIND_PASSWORD "password")
(def LIST_KIND_PASSKEY "passkey")

;; What a password-list request is for, sent with GET_ENTRY_LIST and ENTRY_SELECTED:
;; filling a login (username + password) or a site's 2FA one-time code field
(def FILL_PURPOSE_LOGIN "login")
(def FILL_PURPOSE_OTP "otp")

;; bg -> content: the current one-time code of the entry picked for a 2FA field
(def SELECTED_ENTRY_OTP "SELECTED_ENTRY_OTP")

;; Saving a submitted login — content → background
(def CREDENTIAL_SUBMITTED "CREDENTIAL_SUBMITTED")                   ;; username + password submitted on the page
(def CREDENTIAL_USERNAME_SUBMITTED "CREDENTIAL_USERNAME_SUBMITTED") ;; username step of a two-step login
(def CREDENTIAL_FORM_GONE "CREDENTIAL_FORM_GONE")                   ;; the submitted password field went away (SPA login succeeded)

;; Save-login popup — background → content → iframe, and back
(def SHOW_SAVE_LOGIN_POPUP "SHOW_SAVE_LOGIN_POPUP")
(def CLOSE_SAVE_LOGIN_POPUP "CLOSE_SAVE_LOGIN_POPUP")
(def RESIZE_IFRAME_SAVE_LOGIN "RESIZE_IFRAME_SAVE_LOGIN")
(def SAVE_LOGIN_CONFIRMED "SAVE_LOGIN_CONFIRMED")         ;; iframe→content→bg: user chose add / update
(def SAVE_LOGIN_DISMISSED "SAVE_LOGIN_DISMISSED")         ;; iframe→content→bg: user closed the popup
(def SAVE_LOGIN_NEVER_FOR_SITE "SAVE_LOGIN_NEVER_FOR_SITE") ;; iframe→content→bg: do not offer again on this site
(def SAVE_LOGIN_RESULT "SAVE_LOGIN_RESULT")               ;; bg→content→iframe: saved, or why not

(def RECONNECT_APP "RECONNECT_APP")

;; Message types for content <-> iframe communication
(def CLOSE_POPUP "CLOSE_POPUP")

(def CLOSE_ENTRY_LIST_POPUP "CLOSE_ENTRY_LIST_POPUP")

(def RESIZE_IFRAME_ENTRY_LIST "RESIZE_IFRAME_ENTRY_LIST")

(def RESIZE_IFRAME_MAIN_POPUP "RESIZE_IFRAME_MAIN_POPUP")

(def RESIZE_IFRAME_MSG_POPUP "RESIZE_IFRAME_MSG_POPUP")

(def INIT_PORT "INIT_PORT")

(def MSG_BOX_MESSAGE "MSG_BOX_MESSAGE")


;; Deprecate
;; Messages from iframe to background

(def LAUNCHING_POPUP "LAUNCHING_POPUP")

(def CONNECTION_STATE_INFO "CONNECTION_STATE_INFO")




