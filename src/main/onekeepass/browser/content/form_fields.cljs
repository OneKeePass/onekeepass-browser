(ns onekeepass.browser.content.form-fields
  (:require
   [applied-science.js-interop :as j]
   [onekeepass.browser.common.utils :refer [okp-console-log okp-println]]
   [onekeepass.browser.content.common-ui :as common-ui :refer [input-label-text
                                                               is-any-ancestor-hidden
                                                               is-element-hidden-by-css
                                                               is-passord-input
                                                               is-possible-username-input]]
   [onekeepass.browser.content.constants :refer [LOGIN_PAGE NOT_LOGIN_PAGE
                                                 PASSWORD]]
   [onekeepass.browser.content.context :as context :refer [get-input-field-info
                                                           get-password-input
                                                           get-username-input]]
   [onekeepass.browser.content.inject.input-field-okp-icon :as input-field-okp-icon]
   [onekeepass.browser.content.signals :as signals :refer [classify-form]]
   [onekeepass.browser.content.events.messaging :as messaging-event]
   [onekeepass.browser.common.utils :as u]))

#_(def ^:private container-inputs-query
    "input[type=text], input[type=email], input[type=password]")

(def ^:private text-email-inputs-query
  "input[type=text], input[type=email]")

(defn- find-text-email-inputs
  "Caled to find all text or email type inputs under a parent element
   Returns a vec of such collected inputs which are not hidden
   "

  ;; ^js/HTMLCollection fields  (js/document.getElementsByTagName "input"
  ;; getElementsByTagName returns a live HTMLCollection of elements 

  ;; ^js/NodeList fields (j/call container :querySelectorAll container-inputs-query)
  ;; querySelectorAll returns a static (not live) NodeList  

  ;; See https://dev.to/wlytle/performance-tradeoffs-of-queryselector-and-queryselectorall-1074

  ([parent]
   ;;(u/okp-console-log "Finding text/email inputs under parent" parent)
   (let [^js/NodeList fields (j/call parent :querySelectorAll text-email-inputs-query)]
     #_(u/okp-console-log "Text/Email input Fields (NodeList) are" fields)
     (reduce (fn [acc input]
               (let [hidden (common-ui/is-hidden-text-input input)
                     hidden (if-not hidden (is-element-hidden-by-css input) false)
                     hidden (if-not hidden (is-any-ancestor-hidden input) false)]
                 (if-not hidden
                   (conj acc input)
                   acc))) [] (vec fields))))
  ([]
   ;; We try to get all text or email type inputs in the page itself using document element as parent
   (find-text-email-inputs js/document)))

(defn- is-input-visible? [input]
  (j/call input :checkVisibility #js {:visibilityProperty true
                                      :opacityProperty true}))

(defn- filter-relevant-inputs
  "Filters only the possible password and/or user name input fields from the arg  'all-inputs'"
  [all-inputs]
  (filterv (fn [input]
             (or (= (j/get input :type) "text")
                 (= (j/get input :type) "email")
                 (= (j/get input :type) "password")))
           all-inputs))

(defn- filter-visibile-text-email-inputs
  [inputs]
  (filterv (fn [input]
             (and (or (= (j/get input :type) "text")
                      (= (j/get input :type) "email")) (is-input-visible? input)))
           inputs))

(declare get-all-input-elements)

(defn- find-password-input-infos
  "Finds one or more  'password' type inputs from a page. 
   Typically a login page will have one or more password inputs.
   There is a possibilty we may pick up password inputs that are not meant user login!. 
   For example the page may some account number or ssn etc which may be of 'password' type input

   Returns all collected password input infos as vec - a vec of maps
   "
  [all-inputs]
  (let [pwd-fields (atom [])]
    #_(okp-console-log "find-password-input-infos: All input elements found including Shadow DOMs:" (clj->js all-inputs))
    (doseq [input all-inputs]
      #_(okp-console-log "Checking input element:" input)

      ;; Only input is of 'password' type is considered
      (when (= (j/get input :type) PASSWORD)

        ;; Sometimes the password input may be hidden in the first page of login showing only 
        ;; the username field and in the next page accepting password  

        ;; checkVisibility call returns true if visible, false if hidden (display: none, visibility: hidden, etc.)
        ;; checkVisibility is supported in Chrome 105+, Firefox 106+
        ;; This is used instead of previous way detecting an input password field is hidden or not

        ;; :contentVisibilityAuto true identifies the password input hidden though it is not so (e.g onedrive login page)
        (let [visible? (is-input-visible? input) #_(j/call input :checkVisibility #js {:visibilityProperty true
                                                                                       :opacityProperty true})]
          #_(u/okp-console-log "Found password input" input ", hidde?"  (not visible?))
          (swap! pwd-fields conj {:password-input input
                                  :hidden (not visible?)}))))
    #_(show-password-inputs-info @pwd-fields)
    #_(u/okp-println "Collected password input infos:" @pwd-fields)
    @pwd-fields))

(defn- create-input-info
  "Called to form a map from js input props
   Returns a map
   "
  [input]
  #_(okp-console-log "In create-input-info for input" input)

  ;; checkVisibility call returns true if visible, false if hidden (display: none, visibility: hidden, etc.)
  ;; checkVisibility is supported in Chrome 105+, Firefox 106+
  ;; This is used instead of previous way detecting an input field is hidden or not
  (let [visible? (is-input-visible? input)
        input-type (cond
                     (is-passord-input input)
                     :password

                     (is-possible-username-input input)
                     :username

                     :else
                     :unknown)
        label  (input-label-text input)]
    #_(okp-console-log "Found input element:" input "and visibility is " visible?)
    {:input input
     :label label
     :input-type input-type
     :hidden (not visible?)}))

(defn find-relevant-input-infos [all-inputs]
  (let [fields-infos  (map (fn [input] (create-input-info input)) all-inputs)]
    (->> fields-infos (filterv (fn [{:keys [input-type]}] (or (= input-type :username) (= input-type :password)))))))

(declare username-only-page)

(defn classify-input-non-form-page
  "This is called to identify a page in which we found a password input.
   We need to get username field by finding this password input's parent or parent's parent and so on
   Returns a map as retured by 'username-only-page'
   "
  [password-input]
  ;; 
  (loop [parent (j/get password-input :parentElement) fields []]
    ;; We break the loop when there is no more parent or when find some text/email inputs for a parent
    (if (or (nil? parent) (> (count fields) 0))
      ;; TODO: Need to do something similar to 'classify-form' instead of using fn username-only-page
      (username-only-page fields)
      ;; We go one level above 
      (recur (j/get parent :parentElement) (find-text-email-inputs parent)))))

;; This did not work
#_(defn- classify-input-non-form-page [all-inputs]
    (let [user-name-inputs (filter-visibile-text-email-inputs all-inputs)]
      (u/okp-console-log "In classify-input-non-form-page"  (clj->js user-name-inputs))
      (username-only-page user-name-inputs)))

(defn- classify-input-page
  "Identifies the page whether it is login or signup page or neither based on the input password field
   The arg 'password-input' is a password type input
   Returns a map with keys [container-page-type relevant-inputs] where relevant-inputs is vec of [usernameInfo passwordInfo]
   "
  [password-input all-relevant-inputs]
  (let [form  (j/get password-input :form)
        ;;base_uri (j/get password-input :baseURI)
        form-action (j/get password-input :formAction)]
    (cond
      (not (nil? form))
      (let [;; At this only the value of 'container-page-type' is used ignoring the other keys 
            {:keys [container-page-type] :as _form-classification} (classify-form form)
            relevant-inputs (find-relevant-input-infos all-relevant-inputs)]

        (okp-console-log "Form signals detected " (clj->js _form-classification))

        {:container-page-type container-page-type
         :relevant-inputs relevant-inputs})

      ;; In case of reddit, password and user name inputs are under diffrent shadow root parent
      ;; So we need to to this way. Here we are assuming, the page is login page as find a password input
      (= form-action "https://www.reddit.com/")
      {:container-page-type :login
       :relevant-inputs (find-relevant-input-infos all-relevant-inputs)}

      :else
      ;; E.g BOA
      ;; There are some sites that may not use 'form' as parent to relevant inputs and for now a simple fn is used to determine 
      ;; the page type by just using user name input.      
      (let [{:keys [container-page-type username-input _message] :as _form-classification} (classify-input-non-form-page password-input) #_(classify-input-non-form-page all-inputs)]

        #_(when-not (nil? _message)
            (okp-println "classify-input-non-form-page message " _message))

        (okp-console-log "Non form signals detected "  (clj->js _form-classification))

        {:container-page-type container-page-type
         :relevant-inputs (if-not (nil? username-input)
                            [(create-input-info username-input) (create-input-info password-input)] [(create-input-info password-input)])}))))

(defn- group-inputs-by-form
  "Groups a collection of HTML input elements by their containing form.

   Args:
     input-elements: A ClojureScript sequence of HTMLInputElement objects.

   Returns:
     A map where keys are HTMLFormElement objects (or :no-form for inputs
     without a form) and values are vectors of input elements belonging to that form."
  [input-elements]
  ;; (println "input-elements count " (count input-elements))
  (reduce (fn [acc input-el]
            #_(okp-console-log "input-el is .." input-el)
            (let [form-el (j/get input-el :form)
                  ;; _ (js/console.log "Form in group-inputs-by-form " form-el)
                  ;; Use :no-form keyword if form-el is nil
                  group-key (if (nil? form-el) :no-form form-el)]
              ;; Update the accumulator map:
              ;; If group-key already exists, conj the input-el to its vector.
              ;; If not, create a new vector with input-el.
              (update acc group-key (fnil conj []) input-el)))
          {}
          input-elements))

(defn- username-only-page
  "Called to determine whether the page is login page with a user name only field 
   The arg 'fields' are previously filtered text or email inputs
   Returns a map identifying the page type and any relevant input
  "
  ([filtered-text-input-fields]
   (u/okp-console-log "User name only page "  (clj->js filtered-text-input-fields))
   (cond
     (= (count filtered-text-input-fields) 0)
     {:container-page-type NOT_LOGIN_PAGE
      :username-input nil
      :message "No valid username field is found"}

     ;; We found one potential user name field
     (= (count filtered-text-input-fields) 1)
     (let [input (nth filtered-text-input-fields 0)
           login? (signals/is-input-action-login input)
           signup? (signals/is-input-action-signup input)
           username-field? (or (is-possible-username-input input) login?)
           container-type (if (and (not signup?) username-field?) LOGIN_PAGE NOT_LOGIN_PAGE)]
       {:container-page-type container-type
        :username-input input})

     ;; When we find mutiple text/email type inputs, need to find any one that can be used as login user name
     ;; e.g Coursera
     :else
     (let [input (first (filter (fn [input] (is-possible-username-input input)) filtered-text-input-fields))]
       (if input
         (let [login? (signals/is-input-action-login input)
               container-type (if login? LOGIN_PAGE NOT_LOGIN_PAGE)]
           {:container-page-type container-type
            :username-input input})
         {:container-page-type NOT_LOGIN_PAGE
          :username-input nil
          ;; This means we do not know which input field a username field among the many text inputs
          :message "More than one input is found"}))))

  #_([]
     ;; Look for text or email inputs in this page
     (let [fields (find-text-email-inputs)]
       (username-only-page fields))))

(defn- identify-containing-page
  "Need to determine whether the page is for login or for signup or neither 
   using the collected password type inputs
   The arg password-inputs is a vec of password inputs (HtmlInputElements)
   The arg 'all-relevant-inputs' is a vec of all relevant inputs 
   Returns a map or vec of maps (in case of both login and signup forms) - TODO fix this
   "
  [password-inputs all-relevant-inputs]
  (okp-println "Number of password inputs found " (count password-inputs))
  (cond

    ;; No password input and there is a possibility that this may be a login page with just username field only
    ;; Or any other application page which we need not consider at all
    (= (count password-inputs) 0)
    (let [filtered-text-input-fields (filter-visibile-text-email-inputs all-relevant-inputs)
          {:keys [container-page-type username-input _message]} (username-only-page filtered-text-input-fields)]

      #_(when-not (nil? _message)
          (okp-println "username-only-page message " _message))

      {:container-page-type container-page-type
       :relevant-inputs (if-not (nil? username-input)
                          [(create-input-info username-input)] [])})

    ;; We identified one password input. This is may be a login or a signup page or neither
    (= (count password-inputs) 1)
    (classify-input-page (nth password-inputs 0) all-relevant-inputs)

    ;; Mostly a Sign up form or also there is possibilty of having both Signup form and Login 
    ;; forms on the same page
    ;; Saw an example where password inputs belonging to two separate parent form. So we group 
    ;; inputs accordingly and then classify
    (> (count password-inputs) 1)
    (let [grouped  (group-inputs-by-form password-inputs)
          ;; grouped is map where the 'containing form' is the key and value is a vec of all password inputs in that form
          ;; For now we consider only 'form' tag by using :no-form for other container
          grouped (filter (fn [[k _v]] (not= k :no-form)) grouped)]

      ;; We classify the page based on the first password input for each form
      (mapv (fn [[_form inputs]] (classify-input-page (nth inputs 0) all-relevant-inputs)) grouped))))


;; See create-input-info fn where input-info is formed
(defrecord PageInfo [container-page-type relevant-inputs]
  context/PageStoreAccess

  (get-input-field-info [_ type-kw]
    (when (and (= container-page-type LOGIN_PAGE) (-> relevant-inputs empty? boolean not))
      ;; Some sites (e.g bf) may have more than one password or user name fields under the login form. We need to 
      ;; select only first non hidden one
      (let [input-detail-info (first (filter (fn [{:keys [input-type hidden]}]
                                               (and (= input-type type-kw) (not hidden))) relevant-inputs))]
        input-detail-info)))

  ;; Non hidden field is returned; Otherwise returns nil
  (get-username-input [this]
    (when-let [{:keys [input hidden]} (get-input-field-info this :username)]
      ;; Checking this 'hidden' flag is reduntant as it it already filtered in get-input-field-info call
      (when-not hidden
        input)))
  ;; Non hidden field is returned; Otherwise returns nil
  (get-password-input [this]
    (when-let [{:keys [input hidden]} (get-input-field-info this :password)]
      ;; Checking this 'hidden' flag is reduntant as it it already filtered in get-input-field-info call
      (when-not hidden
        input))))

;; A page may be one of the following:
;; A login page 
;; A signup page 
;; Both login and signup forms are in the same page
;; None of the above - just the application's other pages

(defn identify-page [& {:keys [root]}]

  (okp-println "Trying to identify the page as Login or not")

  (let [root (or root js/document)
        all-inputs  (get-all-input-elements root)
        all-relevant-inputs (filter-relevant-inputs all-inputs)]
    (when-not (empty? all-relevant-inputs)
      (let [;; Here we are assuming a page that has one or more password inputs may be a login page
            ;; So we extract the passwo
            ;; password-input-infos is a vec of maps ( each map has keys [password-input hidden ancestor-hidden])
            password-input-infos (find-password-input-infos all-relevant-inputs)

            _ (u/okp-println "Found password-input-infos" password-input-infos)

            password-inputs (mapv
                             (fn [{:keys [password-input]}] password-input)
                             password-input-infos)
            ;; 'password-inputs' now is just a vec of PASSWODRD input fields (type HTMLInputElement)

            ;; A map or a vec of maps 
            result (identify-containing-page password-inputs all-relevant-inputs)
            {:keys [container-page-type _relevant-inputs] :as result} (if (vector? result) (first result) result)
            ;; st-result (map->PageInfo result)
            ;; unknown-input-type-found (some (fn [{:keys [input-type]}] (= input-type :unknown)) relevant-inputs)
            ]

        #_(doseq [{:keys [input input-type]} _relevant-inputs]
            (okp-console-log "The input of relevant-inputs in identify-page" input " input-type " (str input-type)))

        (when (= container-page-type LOGIN_PAGE)
          (u/okp-console-log "Indentified the page as Login and the raw result map is" (clj->js result))
          (let [page-info (map->PageInfo result)
                ;; _ (u/okp-console-log "Page-info is " (clj->js page-info))
                user-field-found (-> page-info get-username-input boolean)
                pwd-field-found  (-> page-info get-password-input boolean)]
            ;; identify-page is called multiple times 
            ;; It is called in each frame
            ;; It may be  also called multiple times in a frame because of dynamic dom loading of the page
            ;; The last page-info formed that is not yet stored and that should be fine I think
            (when-not (context/is-already-found? page-info)
              ;; At least one of the relevant field should be visible to store
              (when (or user-field-found pwd-field-found)

                (okp-println "Storing the identified page-info " page-info)

                (context/store-page-info page-info)

                (messaging-event/send-login-fields-identified {})

                (input-field-okp-icon/add-page-inputs-event-listeners page-info)))))))))

;; Callback fn that is called when the content script receives the basic entry detail from the background
(defn fill-inputs [{:keys [username password] :as _args}]
  #_(okp-println "In fill-inputs received args" _args)

  (when-some [input (context/username-in-page)]

    ;; First we set the input's value prop
    (j/assoc! input :value username)

    ;; https://stackoverflow.com/questions/40894637/how-to-programmatically-fill-input-elements-built-with-react
    ;; https://stackoverflow.com/questions/66536154/changing-input-text-of-a-react-app-using-javascript

    ;; Manually trigger the input and change events on the element. This mimics the events that 
    ;; fire when a user types into an input field.
    ;; The bubbles: true option ensures that the events propagate up the DOM tree, allowing any 
    ;; parent elements or event listeners attached to them to react to the change
    (j/call input :dispatchEvent (js/Event. "input" #js {:bubbles true}))
    (j/call input :dispatchEvent (js/Event. "change" #js {:bubbles true})))

  (when-some [input (context/password-in-page)]
    (j/assoc! input :value password)
    (j/call input :dispatchEvent (js/Event. "input" #js {:bubbles true}))
    (j/call input :dispatchEvent (js/Event. "change" #js {:bubbles true}))))

;; Few sites have relevant inputs inside shadowdoms
;; e.g hbomax, troweprice etc. The following ensures that we get them also
(defn get-all-input-elements
  "Finds all <input> elements in the Light DOM and nested Shadow DOMs
   using a high-performance iterative TreeWalker strategy."
  [parent-element]
  ;; Use a transient vector for high-performance accumulation
  (let [inputs (transient [])
        ;; Use a native JS array as a stack/queue for roots to process.
        ;; We start with the parent-element.
        roots-queue #js [parent-element]]

    ;; Process every root (Light DOM and discovered Shadow Roots)
    (loop []
      (when (> (.-length roots-queue) 0)
        (let [current-root (.pop roots-queue)

              ;; Create a TreeWalker for the current root.
              ;; NodeFilter.SHOW_ELEMENT ensures we only look at Elements (ignoring text/comments).
              walker (js/document.createTreeWalker
                      current-root
                      js/NodeFilter.SHOW_ELEMENT
                      nil
                      false)]

          ;; Walk the DOM tree of the current root
          (loop []
            (when-let [node (.nextNode walker)]
              ;; CHECK A: Is this node an INPUT?
              ;; Using .tagName is faster than checking types.
              (when (= "INPUT" (.-tagName node))
                (conj! inputs node))

              ;; CHECK B: Does this node have a Shadow DOM?
              ;; If yes, add the shadow root to the queue to be processed in the outer loop.
              (when-let [sr (.-shadowRoot node)]
                (.push roots-queue sr))

              ;; Continue walking
              (recur)))

          ;; Continue to the next root in the queue
          (recur))))

    ;; Return the final persistent vector
    (persistent! inputs)))


;;;;;;;;;;

#_(defn- filter-password-inputs [inputs]
    (filterv (fn [input] (= (j/get input :type) "password")) inputs))

;; fields is a HTMLCollection interface that represents a generic collection (array-like object similar to arguments) of elements 
#_(defn- find-password-input-infos_old
    "Finds one or more  'password' type inputs from a page. 
   Typically a login page will have one or more password inputs.
   There is a possibilty we may pick up password inputs that are not meant user login!. 
   For example the page may some account number or ssn etc which may be of 'password' type input

   Returns all collected password input infos as vec - a vec of maps
   "
    [_dynamically-added-node]
    (let [^js/HTMLCollection fields (js/document.getElementsByTagName "input")

          ;; To avoid iterating over the "getElementsByTagName" for each dynamically-added-node from js/document
          ;; tried the following. But it did not find all inputs properly. So we get all the inputs from js/document
          ;; each time when a dynamiclly "div" or "form" is added
          #_(cond
              (or (instance? js/HTMLDivElement dynamically-added-node)
                  (instance? js/HTMLFormElement dynamically-added-node))
              (j/call dynamically-added-node :getElementsByTagName "input")

              :else
              (js/document.getElementsByTagName "input"))

          pwd-fields (atom [])]
      (doseq [input (vec fields)]
        ;; Only input is of 'password' type is considered
        (when (= (j/get input :type) PASSWORD)
          ;; Sometimes the password input may be hidden in the first page of login showing only 
          ;; the username field and in the next page accepting password  
          (let [pwd-hidden (common-ui/is-hidden-password-input input)
                pwd-hidden (if-not pwd-hidden (is-element-hidden-by-css input) false)
                ancestor-hidden (if-not pwd-hidden (is-any-ancestor-hidden input) false)]

            #_(println "find-password-inputs ancestor-hidden " ancestor-hidden)

            ;; Do we need this vec of map or should we just return a vec of password inputs?
            ;; Here 'input' is of type HTMLInputElement
            (swap! pwd-fields conj {:password-input input
                                    :hidden pwd-hidden
                                    :ancestor-hidden ancestor-hidden}))))
      #_(show-password-inputs-info @pwd-fields)
      @pwd-fields))


#_(defn- filter-text-email-inputs [inputs]
    (filterv (fn [input]
               (or (= (j/get input :type) "text")
                   (= (j/get input :type) "email")))
             inputs))

#_(defn- get-filtered-all-input-elements
    "Filters all input elements by type
   Returns a vec of such inputs
   "
    [parent-element]
    (let [all-inputs (get-all-input-elements parent-element)]
      (okp-console-log "get-filtered-all-input-elements: all-inputs" (clj->js all-inputs))
      (filterv (fn [input]
                 (or (= (j/get input :type) "text")
                     (= (j/get input :type) "email")
                     (= (j/get input :type) "password")))
               all-inputs)))

#_(defn- find-all-inputs
    [container]
    #_(u/okp-console-log "+++++ In find-all-inputs for container " container)

    (let [;; ^js/NodeList fields (j/call container :querySelectorAll container-inputs-query)
          fields (get-filtered-all-input-elements container)

          ;;_ (okp-console-log "Input Fields (NodeList) are" fields)

          ;; Need to convert js array to cljs vec to use map fn if fields is ^js/NodeList
          fields (if (vector? fields) fields (vec fields))

          ;;_ (u/okp-console-log "Input Fields are" fields)

          input-fields-info  (map (fn [input] (create-input-info input)) fields #_(vec fields))]
      input-fields-info))

#_(defn find-relevant-inputs [container]
    ;; (okp-println "+++++ In find-relevant-inputs ")
    (->> container find-all-inputs
         (filterv (fn [{:keys [input-type]}] (or (= input-type :username) (= input-type :password))))))


