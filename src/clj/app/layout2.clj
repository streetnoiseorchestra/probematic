(ns app.layout2
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.html :as html]
   [app.i18n :as i18n]
   [app.secret-box :as secret-box]
   [app.ui2 :as ui2]
   [app.urls :as url]
   [app.util :as util]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [jsonista.core :as j]))

(defn- nav-items
  [tr]
  [{:label (tr [:nav/home])          :icon :home                 :href "/"                                       :route-name :app/dashboard}
   {:label (tr [:nav/gigs])          :icon :trumpet              :href (url/link-gigs-home)                      :route-name :app/gigs}
   {:label (tr [:nav/songs])         :icon :music-note-outline   :href "/songs"                                  :route-name :app/songs}
   {:label (tr [:nav/members])       :icon :users-outline        :href "/members"                                :route-name :app/members}
   {:label (tr [:nav/insurance])     :icon :shield-check-outline :href "/insurance"                              :route-name :app/insurance}
   {:label (tr [:nav/probeplan])     :icon :calendar             :href "/probeplan"                              :route-name :app/probeplan}
   {:label (tr [:nav/polls])         :icon :question             :href "/polls"                                  :route-name :app/polls}
   {:label (tr [:nav/forum])         :icon :snomegaphone         :href "https://forum.streetnoise.at"            :route-name :app/forum}
   {:label (tr [:nav/nextcloud])     :icon :folder-open          :href "https://data.streetnoise.at/apps/files/" :route-name :app/nextcloud}
   {:label (tr [:nav/chat])          :icon :comments             :href "https://chat.streetnoise.at"             :route-name :app/chat}
   {:label (tr [:nav/stats])         :icon :chart-bar-square     :href "/stats"                                  :route-name :app/stats}
   {:label (tr [:nav/band-settings]) :icon :cog                  :href "/band-settings"                          :route-name :app/band-settings}])

(defn- active? [req route-name]
  (= route-name (-> req :reitit.core/match :data :app.route/name)))

(defn- nav-button [req {:keys [label icon href route-name]}]
  [:wa-button (cond-> {:href       href
                       :appearance "plain"}
                (active? req route-name) (assoc :variant "brand"
                                                :aria-current "page"))
   [:wa-icon {:library "snoico"
              :name    (name icon)
              :slot    "start"
              :class   "nav-icon"}]
   label])

(defn navigation [req]
  (let [tr (i18n/tr-from-req req)]
    (into [:div {:class "wa-stack wa-gap-0"}]
          (map (partial nav-button req) (nav-items tr)))))

(defn- avatar-src [member]
  (when-let [tpl (:member/avatar-template member)]
    (str "https://forum.streetnoise.at"
         (str/replace tpl "{size}" "200"))))

(def ^:private menu-icon-opts {:slot "icon" :class "menu-icon"})

(defn brand-link []
  [:a {:href "/" :class "brand-link logotype-dark"}
   [:wa-icon {:library    "snoico"
              :name       "logotype"
              :class      "brand-logotype"
              :auto-width true}]])

(defn navigation-header [req member]
  (let [tr  (i18n/tr-from-req req)
        src (avatar-src member)]
    [:wa-dropdown {:distance "4"}
     [:div {:slot "trigger"}
      [:wa-button {:id         "account-dropdown-button"
                   :appearance "plain"
                   :with-caret true}
       [:wa-avatar (cond-> {:slot  "start"
                            :label (ui2/member-nick member)
                            :shape "rounded"
                            :style "--size: 2rem"}
                     src (assoc :image src))
        (when-not src
          [:wa-icon {:library "snoico"
                     :name    "user"
                     :slot    "icon"}])]
       [:span {:class "member-nick"} (ui2/member-nick member)]]]
     [:wa-dropdown-item {:value   (url/link-member member)
                         :onclick "window.location = this.value"}
      [:wa-icon (merge {:library "snoico"
                        :name    "user"}
                       menu-icon-opts)]
      (tr [:my-profile])]
     [:wa-dropdown-item {:value   "/band-settings"
                         :onclick "window.location = this.value"}
      [:wa-icon (merge {:library "snoico"
                        :name    "cog"}
                       menu-icon-opts)]
      (tr [:nav/band-settings])]
     [:wa-divider]
     [:wa-dropdown-item {:value   (url/link-logout)
                         :variant "danger"
                         :onclick "window.location = this.value"}
      [:wa-icon (merge {:library "snoico"
                        :name    "xmark"}
                       menu-icon-opts)]
      (tr [:nav/logout])]]))

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

(def ^:private snoico-sprite-path "img/sprites/snoico.svg")
(defn- snoico-viewboxes []
  (let [snoico-viewboxes-resource "public/img/sprites/snoico-viewboxes.edn"]
    (if-let [resource (io/resource snoico-viewboxes-resource)]
      (edn/read-string (slurp resource))
      (throw (ex-info "Cannot load snoico viewBox manifest"
                      {:path snoico-viewboxes-resource})))))

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

(defn head [req {:keys [extra-head title]}]
  (into
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
    [:link {:rel "shortcut icon" :href "/img/megaphone-icon.png"}]
    [:title (or title "SNOrga")]
    (stylesheet req "vendor/webawesome@3.8.0/styles/themes" "active.css")
    (stylesheet req "vendor/webawesome@3.8.0/styles" "native.css")
    (stylesheet req "vendor/webawesome@3.8.0/styles" "utilities.css")
    (stylesheet req "css/compiled" "main2.css")
    [:style
     (html/raw
      ":root {
        --wa-font-weight-body: 400;
        --wa-font-weight-heading: 650;
        --wa-font-weight-code: 400;
        --wa-font-weight-longform: 400;
        --wa-border-radius-scale: 1.75;
        --wa-border-width-scale: 1;
        --wa-space-scale: 1;
      }")]
    [:script {:type :importmap} (html/raw (j/write-value-as-string
                                           {:imports {"squint-cljs/" "/vendor/squint@0.11.189/"
                                                      "wa/"          "/vendor/webawesome@3.8.0/"
                                                      "sortable"     "/vendor/sortable@1.15.7-esm.js"}}))]
    [:script {:type "module" :src "/vendor/webawesome@3.8.0/webawesome.loader.js"}]
    [:script {:type "module"}
     (let [snoico-sprite-url (asset-url req snoico-sprite-path)
           snoico-viewboxes  (j/write-value-as-string (snoico-viewboxes))]
       (html/raw
        (str "
  import { registerIconLibrary } from 'wa/webawesome.js';
  // these imports ensure that webcomonents custom elements are defined
  // before datastar inits so that d* can properly interact with their value and change attrs
  import 'wa/components/page/page.js';
  import 'wa/components/icon/icon.js';
  import 'wa/components/button/button.js';
  import 'wa/components/input/input.js';
  import 'wa/components/avatar/avatar.js';
  import 'wa/components/dialog/dialog.js';
  import 'wa/components/checkbox/checkbox.js';
  import 'wa/components/select/select.js';
  import 'wa/components/switch/switch.js';
  import 'wa/components/callout/callout.js';
  import 'wa/components/divider/divider.js';
  import 'wa/components/badge/badge.js';
  import 'wa/components/dropdown/dropdown.js';
  import 'wa/components/tooltip/tooltip.js';
  const snoicoSpriteUrl = " (j/write-value-as-string snoico-sprite-url) ";
  const snoicoViewBoxes = " snoico-viewboxes ";
  registerIconLibrary('default', {
    resolver: (name, family, variant) => `/img/iconoir/${name}.svg`,
    //mutator: svg => svg.setAttribute('fill', 'currentColor'),
  });
  registerIconLibrary('snoico', {
    resolver: name => `${snoicoSpriteUrl}#${name}`,
    mutator: (svg, icon) => {
      const viewBox = snoicoViewBoxes[icon?.name];
      if (viewBox) {
        svg.setAttribute('viewBox', viewBox);
      }
    },
    spriteSheet: true,
  });")))]
    (script req "datastar@1.0.1.js" :type "module")
    (when (config/dev-mode? (-> req :system :env))
      (script req "datastar-inspector@1.1.4.js" :type "module"))]
   extra-head))

(defn- html-lang [req]
  (name (or (:current-locale req) "en")))

(defn html5
  [req opts body]
  (html/->str
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

(def tabid-js
  ;; Higher collision risk is acceptable here as it only needs to be
  ;; unique against a given users other tabs.
  "self.crypto.randomUUID().substring(0,8)")

(defn shim-html
  [req opts]
  (html5 req (merge {:title "SNOrga"} opts)
         [:body
          [:div {:data-init on-load-js
                 :id        "long-lived-sse"}]
          [:div {:data-signals:tabid tabid-js}]
          [:main {:id "morph"}]]))

(defn app-shell-body
  [req body]
  (let [member (auth/get-current-member req)
        body   (if (string? body) (html/raw body) body)]
    [:div {:id "morph"}
     [:wa-page {:mobile-breakpoint         "1152"
                :disable-navigation-toggle true}
      [:header {:slot "navigation-header"}
       (brand-link)]

      [:div {:slot "navigation" :style "padding-top: 0"}
       [:div {:class "wa-desktop-only"}
        (navigation-header req member)]
       (navigation req)]

      [:div {:slot "subheader" :class "page-subheader wa-split wa-mobile-only"}
       [:wa-button {:data-toggle-nav true
                    :appearance       "plain"
                    :aria-label       "Toggle navigation"}
        [:wa-icon {:library "snoico"
                   :name    "bars"
                   :slot    "start"
                   :class   "nav-toggle-icon"}]]
       [:a {:href "/" :class "subheader-logo" :aria-label "Home"}
        [:wa-icon {:library "snoico"
                   :name    "snoman"
                   :class   "subheader-brand-icon"}]
        #_[:wa-icon {:library    "snoico"
                     :name       "sno-trumpet"
                     :class      "subheader-brand-icon"
                     :auto-width true}]]
       [:div {:class "subheader-user"}
        (navigation-header req member)]]

      body]
     (when (config/dev-mode? (-> req :system :env))
       [:datastar-inspector])]))

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
