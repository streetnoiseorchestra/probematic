(ns app.layout2
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.html :as html]
   [app.icons :as icon]
   [app.queries :as queries]
   [app.secret-box :as secret-box]
   [app.ui2.button :as button]
   [app.ui2.footer-tray :as footer-tray]
   [app.ui2.icon :as ico]
   [app.ui2.jump-menu :as jump-menu]
   [app.urls :as url]
   [app.util :as util]
   [jsonista.core :as j]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(def ^:private footer-tray-shortcuts
  [{:id           "footer-tray-assignments"
    :label        [:i18n/tr :footer-tray-assignments]
    :icon-library :phosphor
    :icon         :check}
   {:id           "footer-tray-calendar"
    :label        [:i18n/tr :footer-tray-calendar]
    :icon-library :phosphor
    :icon         :calendar}
   {:id           "footer-tray-bookmarks"
    :label        [:i18n/tr :footer-tray-bookmarks]
    :icon-library :snoico
    :icon         :folder-open}
   {:id           "footer-tray-notes"
    :label        [:i18n/tr :footer-tray-notes]
    :icon-library :snoico
    :icon         :file-solid}])

(def ^:private footer-tray-notification
  {:id        "footer-tray-notifications"
   :label     [:i18n/tr :footer-tray-notifications]
   :placement :end})

(defn- footer-tray-sheet [{:keys [id label placement]}]
  (let [placement (or placement :bottom)]
    [:dialog {:id id
              :class (str "tray-sheet " (name placement))
              :open true
              :inert true
              :tabindex "-1"
              :aria-label label
              :aria-hidden "true"
              :data-class:open (->expr (=== $footerTraySheet ~id))
              :data-attr:inert (->expr (not (=== $footerTraySheet ~id)))
              :data-attr:aria-hidden (->expr
                                      (if (=== $footerTraySheet ~id) "false" "true"))
              :data-effect (->expr
                            (when (=== $footerTraySheet ~id) (.focus el)))
              :data-on:keydown "evt.key === 'Escape' && ($footerTraySheet = '')"}
     [:i18n/tr :footer-tray-placeholder]]))

(defn- footer-tray-sheets []
  (into
   [[:div {:class "tray-sheet-backdrop"
           :aria-hidden "true"
           :data-class:open "$footerTraySheet !== ''"
           :data-on:click "$footerTraySheet = ''"}]]
   (map footer-tray-sheet)
   (conj footer-tray-shortcuts footer-tray-notification)))

(def ^:private memoed-sha384-resource (memoize secret-box/sha384-resource))

(defn- sha384-resource [{:keys [system]} path]
  (if (config/prod-mode? (:env system))
    (memoed-sha384-resource path)
    (secret-box/sha384-resource path)))

(defn- cache-buster [req public-path]
  (let [hash (sha384-resource req public-path)]
    (util/url-encode (subs hash (- (count hash) 8)))))

(defn- asset-url [req path]
  (str "/"
       path
       "?v="
       (cache-buster req (str "public/" path))))

(defn- public-script [req path & extra]
  [:script (merge {:src   (asset-url req path)
                   :defer true}
                  (apply hash-map extra))])

(defn- script [req path & extra]
  (apply public-script req (str "js/" path) extra))

(defn- stylesheet [req dir path & extra]
  [:link (merge {:rel  "stylesheet"
                 :href (asset-url req (str dir "/" path))}
                (apply hash-map extra))])

(def esm-import-map
  {"squint-cljs/" "/vendor/squint@0.11.189/"
   "wa/"          "/vendor/webawesome@3.10.0/"
   "sortable"     "/vendor/sortable@1.15.7-esm.js"
   "confetti"     "vendor/canvas-confetti@1.9.4.js"})

(defn head [req {:keys [extra-head title]}]
  (into
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
    [:link {:rel "shortcut icon" :href "/img/megaphone-icon.png"}]
    [:title (or title "SNOrga")]
    [:script {:id "appearance-initializer" :blocking "render"}
     (html/raw
      "(() => { const root = document.documentElement; try { const key = 'streetnoise.appearance'; const stored = localStorage.getItem(key); const value = ['light', 'dark', 'system'].includes(stored) ? stored : 'system'; const dark = value === 'dark' || (value === 'system' && matchMedia('(prefers-color-scheme: dark)').matches); root.dataset.accountAppearance = value; root.classList.add(dark ? 'wa-dark' : 'wa-light'); } catch (_error) { const value = 'system'; root.dataset.accountAppearance = value; root.classList.add(matchMedia('(prefers-color-scheme: dark)').matches ? 'wa-dark' : 'wa-light'); } })();")]
    (stylesheet req "css/compiled" "main2.css")
    [:script {:type :importmap} (html/raw (j/write-value-as-string
                                           {:imports esm-import-map}))]
    (public-script req "vendor/bprogress@1.3.4/index.global.js")
    [:script {:type "module" :blocking "render"}
     (html/raw
      "
      import { allDefined, setBasePath, startLoader } from 'wa/webawesome.js';
      // These imports ensure Web Awesome custom elements are defined before Datastar
      // initializes so d* can interact with their value and change attrs.
      // Keep wa-icon defined because some Web Awesome components render internal icons.
      import 'wa/components/icon/icon.js';
      import 'wa/components/button/button.js';
      import 'wa/components/divider/divider.js';
      import 'wa/components/relative-time/relative-time.js';
      import 'wa/components/textarea/textarea.js';
      import 'wa/components/tab-group/tab-group.js';
      import 'wa/components/tab/tab.js';
      import 'wa/components/tab-panel/tab-panel.js';
      import 'wa/components/tooltip/tooltip.js';
      import 'wa/components/badge/badge.js';
      import 'wa/components/select/select.js';
      import 'wa/components/combobox/combobox.js';
      import 'wa/components/dropdown/dropdown.js';
      import 'wa/components/dropdown-item/dropdown-item.js';
      import 'wa/components/popover/popover.js';
      import 'wa/components/checkbox/checkbox.js';
      import 'wa/components/switch/switch.js';
      import 'wa/components/input/input.js';
      setBasePath('/vendor/webawesome@3.10.0');
      startLoader();
      Promise.race([
        new Promise(resolve => {document.addEventListener('wa-discovery-complete', resolve)}),
        new Promise(resolve => setTimeout(() => resolve, 2000)),
      ]).then(() => {
        document.querySelectorAll('.wa-cloak').forEach(el => el.classList.remove('wa-cloak'));
      });
      await allDefined();")]
    (script req "account-settings.js")
    (script req "datastar@1.0.1.js" :type "module")
    (when (config/dev-mode? (-> req :system :env))
      (script req "datastar-inspector@1.1.4.js" :type "module"))]
   (conj extra-head [:script {:blocking "render"} "let FF_FOUC_FIX;"])))

(defn- html-lang [req]
  (name (or (:current-locale req) "en")))

(defn html5
  [req opts body]
  (html/->str
   (:tr req)
   [html/doctype-html5
    [:html {:lang  (html-lang req)
            :class "wa-theme-active wa-palette-rudimentary wa-brand-orange"}
     (head req opts)
     body]]))

(defn html5-response
  ([req body] (html5-response req nil body))
  ([req opts body]
   {:status 200
    :headers {"Content-Type" "text/html"}
    :body (html5 req opts body)}))

(def on-load-js
  ;; Quirk with browsers is that cache settings are per URL not per
  ;; URL + METHOD this means that GET and POST cache headers can
  ;; mess with each other. To get around this an unused query param
  ;; is added to the url.

  ;; Retry Infinity means we always try to reconnect. The other defaults
  ;; mean that this will at most take 30s (default max backoff).
  "@post(window.location.pathname + (window.location.search + '&u=').replace(/^&/,'?'), {retryMaxCount: Infinity, openWhenHidden: false, retry: 'error'})")

(def tab-id-js
  ;; Higher collision risk is acceptable here as it only needs to be
  ;; unique against a given users other tabs.
  "self.crypto.randomUUID().substring(0,8)")

(def ^:private datastar-fetch-progress-js
  "evt.detail.el.id !== 'long-lived-sse' && (evt.detail.type === 'started' ? BProgressJS.BProgress.start() : (evt.detail.type === 'finished' || evt.detail.type === 'error') && BProgressJS.BProgress.done()); ")

(defn- datastar-page-body [content]
  [:body {:data-on:datastar-fetch datastar-fetch-progress-js}
   [:div {:data-signals:tab-id__case.kebab tab-id-js}]
   [:div {:data-init on-load-js
          :id        "long-lived-sse"}]
   content])

(defn shim-html
  [req opts]
  (html5 req (merge {:title "SNOrga"} opts)
         (datastar-page-body [:main {:id "morph"}])))

(defn app-shell-body
  [req body]
  (let [session-member (auth/get-current-member req)
        member-id      (:member/member-id session-member)
        member         (or (when (and (:db req) member-id)
                             (queries/retrieve-member (:db req) member-id))
                           session-member)
        home-page? (= :app.dashboard.routes/index
                      (-> req :reitit.core/match :data :name))
        body       (if (string? body) (html/raw body) body)]
    (into
     [:div {:id "morph"}
      [:app-shell
       [:header
        (jump-menu/JumpMenu
         {::jump-menu/logotype
          (icon/logotype {:class "logotype"
                          :aria-hidden "true"})})]
       (when-not home-page?
         [button/Button {:class      "home-button wa-gap-2xs"
                         :href       (url/link-dashboard)
                         :appearance "plain"
                         :size       "small"
                         :style      "--wa-form-control-padding-inline: var(--wa-space-xs)"
                         :aria-label "Home"}
          [ico/Icon {::ico/library :snoico
                     ::ico/name :home}]
          [:span {:class "home-label"} "Home"]])
       [:app-shell-content
        body]]
      (footer-tray/FooterTray
       {::footer-tray/member member
        ::footer-tray/shortcuts footer-tray-shortcuts
        ::footer-tray/notification footer-tray-notification})
      #_(when (config/dev-mode? (-> req :system :env))
          [:datastar-inspector])]
     (footer-tray-sheets))))

(defn datastar-page-html
  [req opts body]
  (html5 req (merge {:title "SNOrga"} opts)
         (datastar-page-body (app-shell-body req body))))

(defn app-shell-html
  ([req body]
   (app-shell-html req body nil))
  ([req body opts]
   (html5
    req (merge {:title "SNOrga"} opts)
    [:body
     (app-shell-body req body)])))

(defn app-shell
  ([req body]
   (app-shell req body nil))
  ([req body opts]
   {:status 200
    :headers {"Content-Type" "text/html"}
    :body (app-shell-html req body opts)}))
