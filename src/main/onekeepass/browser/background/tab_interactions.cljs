(ns onekeepass.browser.background.tab-interactions
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.background.events.popup :as popup-event]
   #_[onekeepass.browser.background.iframe.events.popup :as iframe-popup-event]
   [re-frame.core :as rf]
   #_[shadow.cljs.modern :refer [js-await]]))

(defn tab-activated [active-info]
  ;; e.g active-info #js {:tabId 6, :previousTabId 1, :windowId 3}
  ;; #js {:tabId 1, :previousTabId nil, :windowId 3} when previousTab is closed
  ;; (println "tab-activated with active-info: " active-info)
  (let [tab-id (j/get active-info :tabId)]
    (rf/dispatch [:content/tab-activated tab-id])
    ;; use chrome.tabs.get
    #_(js-await [^js tab (js/chrome.tabs.get tab-id)]
                (println "tab-activated Tab url is " (j/get tab :url)))))

(defn add-tab-activated-listener []
  (js/chrome.tabs.onActivated.addListener tab-activated))

(defn add-web-navigation-committed-listener []
  (js/chrome.webNavigation.onCommitted.addListener
   (fn [details]
     (let [{:keys [tabId frameId url]} (j/lookup details)]
       #_(println "webNavigation COMMITTED tabId frameId url " tabId frameId url)
       (rf/dispatch [:content/web-navigation-committed tabId frameId url])))))

(defn add-tab-onremoved-listener []
  (js/chrome.tabs.onRemoved.addListener
   ;; remove-info obj props are windowId,isWindowClosing
   (fn [tab-id _remove-info]
     (rf/dispatch [:content/tab-removed tab-id]))))

(defn add-popup-action-listener
  "Popup action listener when the extension icon is clicked in the toolbar"
  []
  (js/chrome.action.onClicked.addListener
   (fn [tab]
     #_(okp-console-log "Tab popup action is " tab)
     ;; when a web page is opned the url is "about:newtab" in firefox 
     ;; Need to check in other browsers
     ;; No content is script would have been injected in those pages yet. 
     ;; So this call will fail "Error: Could not establish connection. Receiving end does not exist."
     ;; To avoid that. One Solution: We do not call send message if there is no tab-id in :tabs (not yet done)
     ;; Or we can check the url for the new page etc
     
     (popup-event/popup-onclick-action (j/get tab :id))
     #_(iframe-popup-event/popup-onclick-action (j/get tab :id))
     )))

(defn register-tab-listeners 
  "Called onetime to add various tab related listeners"
  []
  (add-popup-action-listener)
  (add-tab-activated-listener)
  (add-tab-onremoved-listener)
  #_(add-tab-updated-listener)
  (add-web-navigation-committed-listener)
  #_(add-web-navigation-completed-listener))


;; Does not do anything at the moment
#_(defn add-tab-updated-listener []
  #_(js/chrome.tabs.onUpdated.addListener
     (fn [tab-id change-info tab]
       ;; (println "tab-activated with change-info: " change-info)
       (j/let [^:js {:keys [url status title]} change-info]
         (println "tab-id " tab-id " url " url " status " status " title " title)
         #_(rf/dispatch [:content/tab-updated tab-id url status]))

       #_(println "tabs.onUpdated tab-id " tab-id  " url " (j/get change-info :url)  " tab status "  (j/get change-info :status)))))

#_(defn add-web-navigation-completed-listener []
  (js/chrome.webNavigation.onCompleted.addListener
   (fn [details]
     (let [{:keys [tabId url frameId]} (j/lookup details)]
       #_(println "webNavigation COMPLETED tabId frameId url " tabId frameId url)))))