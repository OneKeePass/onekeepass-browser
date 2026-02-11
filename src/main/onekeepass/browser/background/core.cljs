(ns onekeepass.browser.background.core
  (:require
   [onekeepass.browser.background.events.messaging :as messaging-event]
   [onekeepass.browser.background.events.content]
   [onekeepass.browser.background.tab-interactions :as tab-interactions]))

;; This is the background script's entry point and it is called onetime when the extension is loaded
(defn init []
  (js/console.log "Background script init is called")
  (messaging-event/start-app-session)
  (messaging-event/register-extension-message-handler)
  (tab-interactions/register-tab-listeners))