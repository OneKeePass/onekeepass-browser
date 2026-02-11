(ns onekeepass.browser.common.connection-states)

(def APP_CONNECTED "APP_CONNECTED")

(def APP_DISCONNECTED "APP_DISCONNECTED")

;; Typically this happens when the browser extension is not able to locate the native messaging manifest file
;; The reason for that is that the main app is yet not installed or 
;; installed and running but browser support is not enabled in the OneKeePass app's settings

(def NATIVE_APP_NOT_AVAILABLE  "NATIVE_APP_NOT_AVAILABLE")

;;;;;;;;;;   Proxy native app to OneKeePass App connection state 

;; These states ae based on the state of the Native messaging proxy app returning the PROXY_APP_CONNECTION message

;; This state inidcates that browser extension is able to lauch the Native messaging proxy app, but the proxy failed to connect or able to conntect state
(def PROXY_TO_APP_NOT_CONNECTED "PROXY_TO_APP_NOT_CONNECTED")

;; This state inidcates that browser extension is able to lauch the Native messaging proxy app, and the proxy app is able to connect to the OneKeePass app
(def PROXY_TO_APP_CONNECTED "PROXY_TO_APP_CONNECTED")

