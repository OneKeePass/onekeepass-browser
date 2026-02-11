(ns onekeepass.browser.common.mui-components
  (:require-macros [onekeepass.browser.common.okp-macros
                    :refer  [declare-mui-classes]])
  (:require
   ["@emotion/react" :as emotion-react]
   ["@mui/icons-material/VpnKeyOutlined" :default VpnKeyOutlined]
   ["@mui/icons-material/Close" :default Close]
   ["@mui/icons-material/SettingsOutlined" :default SettingsOutlined]
   ["@mui/icons-material/RedoSharp" :default RedoSharp]
   ["@mui/icons-material/Cyclone" :default Cyclone]
   ["@mui/material/Avatar" :default Avatar]
   ["@mui/material/Box" :default Box]
   ["@mui/material/Button" :default Button]
   ["@mui/material/Divider" :default Divider]
   ["@mui/material/List" :default List]
   ["@mui/material/ListItem" :default ListItem]
   ["@mui/material/ListItemAvatar" :default ListItemAvatar]
   ["@mui/material/ListItemButton" :default ListItemButton]
   ["@mui/material/ListItemIcon" :default ListItemIcon]
   ["@mui/material/ListItemSecondaryAction" :default ListItemSecondaryAction]
   ["@mui/material/ListItemText" :default ListItemText]
   ["@mui/material/IconButton" :default IconButton]
   ["@mui/material/Paper" :default Paper]
   ["@mui/material/Stack" :default Stack]
   ["@mui/material/styles" :as mui-mat-styles]
   ["@mui/material/SvgIcon" :default SvgIcon]
   ["@mui/material/Typography" :default Typography]
   ["@mui/material/Tooltip" :default Tooltip]
   ["@mui/material/colors" :refer [grey]]
   ["@emotion/cache" :default createCache]
   ["@mui/material/ListSubheader" :default ListSubheader]
   [applied-science.js-interop :as j]
   [reagent.core :as r]
   ;; Following is not used anymore. But if we plan to use, 
   ;; then need to add the dependency in package.json, intstall and then uncomment here
   #_["react-virtualized-auto-sizer" :as vas]
   #_["react-window" :as react-window]))

;;;; Colors

;; ["@mui/material/colors" :as mui-colors]
#_(def color-grey-200 (j/get-in mui-colors [:grey 200]))

(def color-grey-200 (j/get grey 200))

#_(def color-red-600 (j/get red 900))

;; Instead of importing all colors using ["@mui/material/colors" :as mui-colors], 
;; here we copy only some specific colors from the color arrays defined in node_modules/@mui/material/colors
;; e.g In node_modules/@mui/material/colors/grey.js, we can find an array of all shades of grey colors 
#_(def color-grey-200 "#eeeeee")

;; https://mui.com/material-ui/customization/palette/#color-tokens
;; Palette colors are represented by four tokens
;; main, light, dark, contrastText

;; The theme exposes the following default palette colors
;; primary, secondary,error, warning,info, success

;; primary, secondary,error, warning,info, success ( Each has light,main,dark,contrastText color tokens)
;; Standard colors like primary.light, primary.main, primary.dark, secondary.light, secondary.main, secondary.dark, error.light,... can be 
;; found here
;; https://mui.com/material-ui/customization/palette/

;; This macro will create "def" as mui-button, ... etc which are reagent component from react component 
(declare-mui-classes [Avatar
                      Box
                      Button
                      Divider
                      List
                      ListItem
                      ListItemButton
                      ListItemSecondaryAction
                      ListItemText
                      ListItemAvatar
                      ListItemIcon
                      ListSubheader
                      IconButton
                      Paper
                      Stack
                      SvgIcon
                      Tooltip
                      Typography] "mui-" "")

(declare-mui-classes [Close SettingsOutlined VpnKeyOutlined RedoSharp Cyclone] "mui-icon-" "")

;;;;

(def create-theme mui-mat-styles/createTheme)

(def mui-theme-provider
  "A reagent component for ThemeProvider"
  (reagent.core/adapt-react-class mui-mat-styles/ThemeProvider))

;;;

(def create-cache createCache)

(def mui-cache-provider
  "A reagent component for CacheProvider of emotion/react"
  (reagent.core/adapt-react-class emotion-react/CacheProvider))

;;;;;;;;;;; Not used react-window and react-virtualized-auto-sizer for now ;;;;;;;

;; When used ["@mui/material/" :as mui] way, the shadows release build js file size increased 
;; But using something like  ["@mui/material/List" :default List] for List and similar :default refer  reduced size

#_(def ^js/FixedSizeListObj my-fix-list ^js/FixedSizeList (.-FixedSizeList react-window))

#_(def fixed-size-list
    "A reagent component formed from react componet FixedSizeList"
    (reagent.core/adapt-react-class my-fix-list))

;; vas is #js{:default #object[AutoSizer]}
#_(def auto-sizer
    "A reagent component formed from react componet AutoSizer"
    (reagent.core/adapt-react-class ^js/VirtualAutoSizer (.-default vas)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;