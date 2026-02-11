(ns onekeepass.browser.common.regex-utils
  (:require
   [clojure.string :as str]))

(def ^:private login-action-vocabs
  ["login"
   "log on"
   "sign in"
   "signin"
   "logon"
   "log-on"
   "sign-in"
   "log into"
   "sign-on"
   "sign on"
   "sign_in"
   "sign_on"
   "signon"
   "log in"
   "log_in"
   "log_on"])

(def ^:private next-vocabs
  ["next"
   "continue"
   "proceed"])

(def ^:private login-multilanguage-vocabs
  ["giriş yap"
   "ulogujse"
   "даромадан"
   "влизане"
   "autenticar"
   "ingresa"
   "inloggen"
   "prihlásiť"
   "kirjaudu"
   "identifiant"
   "logga"
   "ဆိုင်းအင်လုပ်ခြင်း"
   "логин"
   "ielogoties"
   "logar"
   "ເຂົ້າ​ສູ່​ລະ​ບົບ"
   "உள்நுழை"
   "logginn"
   "conectare"
   "ล็อคอิน"
   "identificarse"
   "ingia"
   "войдите"
   "logar"
   "логирај"
   "connecte"
   "登录"
   "galaan"
   "logga"
   "авторизоваться"
   "واردشدن"
   "logon"
   "belépek"
   "belép"
   "سائنان"
   "вхід"
   "லாగిன்"
   "bejelentkezés"
   "identifiez"
   "ಸೈನ್ಇನ್"
   "maglog"
   "identifier"
   "ავტორიზაცია"
   "wọle"
   "prisijungti"
   "인증하기"
   "είσοδος"
   "authenticate"
   "ввійти"
   "accedi"
   "авторизация"
   "登錄"
   "میںلاگ"
   "התחברות"
   "вход"
   "ieiet"
   "الدخول"
   "ログインする"
   "connecté"
   "entrar"
   "உள்நுழைக"
   "লগইন"
   "prihlásenie"
   "giris"
   "ننوزئ"
   "hyni"
   "přihlášení"
   "увайсці"
   "會員登入"
   "ورود"
   "firmàlu"
   "เข้าสู่ระบบ"
   "intră"
   "登入"
   "ienākt"
   "acessar"
   "пријава"
   "signin"
   "masuk"
   "logare"
   "loggpå"
   "logowanie"
   "siyennan"
   "signon"
   "přihlásit"
   "loggain"
   "εισοδοσ"
   "loguj"
   "પ્રવેશકરો"
   "кіру"
   "साइनइन"
   "लागान"
   "સાઇનઇન"
   "サインイン"
   "دخول"
   "magsignin"
   "vpiši"
   "acceder"
   "մուտք"
   "têketin"
   "identifícate"
   "입력"
   "assinarem"
   "היכנס"
   "ログイン"
   "войти"
   "登陆"
   "iniciar"
   "ลงชื่อเข้าใช้"
   "увійти"
   "ล็อกอิน"
   "кирүү"
   "लॉगइन"
   "логін"
   "intra"
   "einloggen"
   "පුරන්න"
   "autentificați"
   "skráðuinn"
   "takiuru"
   "autentificare"
   "loggingin"
   "立即登录"
   "ലോഗിൻ"
   "conectar"
   "로그인"
   "സൈൻഇൻ"
   "connecter"
   "প্রবেশকর"
   "შესვლა"
   "giriş"
   "zaloguj"
   "зайти"
   "लॉगिन"
   "pieslēgties"
   "prijavi"
   "entrer"
   "logmasuk"
   "connectezvous"
   "ngena"
   "회원로그인"
   "oturumaç"
   "login"
   "సైన్ఇన్"
   "prijava"
   "belépés"
   "ログオン"
   "logind"
   "เข้าระบบ"
   "entra"
   "συνδεθείτε"
   "ensaluti"
   "התחבר"
   "idħol"
   "conectarse"
   "כניסה"
   "logasteach"
   "logpå"
   "log-in"
   "logon"
   "log-on"
   "Войти"
   "signin"
   "sign\"n"
   "sign in"
   "sign-in"
   "signon"
   "sign-on"
   "send[a-zA-Z\\s]+otp" ;; Note: Escaped backslash for regex
   "ورود"
   "登录"
   "Přihlásit se"
   "Přihlaste"
   "Авторизоваться"
   "Авторизация"
   "entrar"
   "accedi"
   "ログオン"
   "Giriş Yap"
   "登入"
   "connecter"
   "ログイン"
   "inloggen"
   "Συνδέσου"
   "connectez-vous"
   "Connexion"
   "Вход"
   "Anmelden"])

(def ^:private all-login-keywords (into [] (concat login-action-vocabs next-vocabs login-multilanguage-vocabs)))

(def ^:private signup-action-vocabs
  ["\\b(create).*?(account)\\b"
   "\\b(create).*?(user)\\b"
   "\\b(create).*?(profile)\\b"
   "\\b(reg).*?(user)\\b"
   "\\b(activate).*?(account)\\b"
   "\\b(free).*?(account)\\b"
   "create_profile"
   "create_customer"
   "newuser"
   "new-reg"
   "new-form"
   "new_membership"
   "createaccount"
   "createAcct"
   "create-account"
   "reg-form"
   "signup"
   "register"
   "regform"
   "registration"
   "new_user"
   "accountcreate"
   "create-account"
   "sign/up"
   "membership/create"
   "new_account"
   "sign up"
   "sign-up"
   "sign_up"
   "account/create"
   "user/create"])

(def ^:private username-vocabs
  ["user.name"
   "user.id"
   "userid"
   "screen.name"
   "screenname"
   "benutzername"
   "benutzer.name"
   "username"
   "display.name"
   "displayname"
   "nickname"
   "profile.name"
   "profilename"
   "signInName"])

(def ^:private forgot-action-vocabs
  ["forgot"
   "reset"
   "find"
   "retrieve"
   "resend"
   "request"
   "recover"
   "change"
   "lost"
   "remind"
   "restore"])

(def ^:private signup-keywords
  ["signup" "sign up" "register" "registration" "create account" "create new account" "enroll"])

(def all-signup-keywords (into [] (concat signup-keywords next-vocabs)))

(defn create-exact-match-regexp-from-src
  "Creates a JavaScript RegExp object that matches the given source string
   as a whole word, case-insensitively.

   Equivalent to JavaScript: new RegExp(`\\b(${source})\\b`, 'i');

   Args:
     source: The string pattern to match (e.g., 'user', 'login').

   Returns:
     A JavaScript RegExp object."
  [source]
  (let [;; Construct the pattern string.
        ;; Need to escape backslashes for the ClojureScript string literal
        ;; so that the JS RegExp constructor receives `\b` not `b`.
        pattern-str (str "\\b(" source ")\\b")]
    ;; Create and return a new JavaScript RegExp object
    (js/RegExp. pattern-str "i")))

(defn create-regexp-from-src
  "Creates a JavaScript RegExp object that matches the given source string
   anywhere within a target string, case-insensitively.

   Equivalent to JavaScript: new RegExp(source, 'i');

   Args:
     source: The string pattern to match (e.g., 'user', 'login').

   Returns:
     A JavaScript RegExp object."
  [source]
  (js/RegExp. source "i"))

(defn concat-regex-str
  "Concatenates a vector of vocabulary strings into a single regex source string
   suitable for creating a RegExp that matches any of the vocabs.
   The result is wrapped in parentheses and vocabs are joined by '|'.

   Equivalent to JavaScript:
   function concatRegexStr(vocabs) {
     let regexSource = '(';
     regexSource += vocabs.join('|');
     return regexSource + ')';
   }

   Args:
     vocabs: A ClojureScript vector of strings (e.g., ['user', 'username']).

   Returns:
     A string representing the regex source (e.g., '(user|username)')."
  [vocabs]
  (str "(" (str/join "|" vocabs) ")"))


(def login-action-combined-regex (create-regexp-from-src (concat-regex-str login-action-vocabs)))

(def login-keywords-regex (create-regexp-from-src (concat-regex-str all-login-keywords)))

(def signup-action-combined-regex (create-regexp-from-src (concat-regex-str signup-action-vocabs)))

(def signup-keywords-regex (create-regexp-from-src (concat-regex-str all-signup-keywords)))

(def username-regex (create-regexp-from-src (concat-regex-str username-vocabs)))

(def forgot-action-regex (create-regexp-from-src (concat-regex-str forgot-action-vocabs))) 