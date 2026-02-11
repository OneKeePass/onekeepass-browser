(ns onekeepass.browser.common.okp-macros
  (:require [clojure.string]
            [clojure.pprint]
            [camel-snake-kebab.core]))

(defmacro def-reagent-classes
  "See below"
  [args def-prefix ns-prefix]
  (list 'map '(fn [x] (list 'def
                            (symbol (str def-prefix (camel-snake-kebab.core/->kebab-case x)))
                            (list 'reagent.core/adapt-react-class (symbol (str ns-prefix x))))) args))

(defmacro declare-mui-classes
  "Defines 'def's for all symbols passed in the args vector
   in the namespace where this macro is called
   e.g (def mui-icon-button (reagent.core/adapt-react-class mui/IconButton))
   args is a vector of material ui component names
   def-prefix is the prefix to use for the var name
   ns-prefix is the namespace to prefix to the 'imported' material ui component
   "
  [args def-prefix ns-prefix]
  `(do
     ~@(def-reagent-classes args def-prefix ns-prefix)))