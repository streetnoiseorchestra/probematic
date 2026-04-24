(ns app.ui2.icon
  (:require
   [app.ui2.core :as uic]
   [clojure.java.io :as io]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def phosphor
  {"regular" {:path "icons/phosphor-regular.edn"}
   "bold"    {:path "icons/phosphor-bold.edn"}
   "duotone" {:path "icons/phosphor-duotone.edn"}
   "fill"    {:path "icons/phosphor-fill.edn"}
   "light"   {:path "icons/phosphor-light.edn"}
   "thin"    {:path "icons/phosphor-thin.edn"}})

(defn read-set [v]
  (-> v io/resource slurp read-string))

(defn load-phosphor-iconset []
  (apply merge (map (comp read-set :path) (vals phosphor))))

(defonce iconsets_
  (delay
    {"phosphor" (load-phosphor-iconset)}))

(def ^:dynamic *default-iconset* "phosphor")

(def plus
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 448 512"}
   [:path {:fill "currentColor"
           :d    "M256 80c0-17.7-14.3-32-32-32s-32 14.3-32 32V224H48c-17.7 0-32 14.3-32 32s14.3 32 32 32H192V432c0 17.7 14.3 32 32 32s32-14.3 32-32V288H400c17.7 0 32-14.3 32-32s-14.3-32-32-32H256V80z"}]])

(def bars
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :fill  "none"
         :viewbox "0 0 24 24"
         :stroke-width "1.5"
         :stroke "currentColor"
         :aria-hidden "true"}
   [:path {:stroke-linecap  "round"
           :stroke-linejoin "round"
           :d               "M3.75 5.25h16.5m-16.5 4.5h16.5m-16.5 4.5h16.5m-16.5 4.5h16.5"}]])

(def custom-iconset
  {:plus plus
   :bars bars})

(defn ico
  [ico-name]
  (if-let [iconset (namespace ico-name)]
    (get-in @iconsets_ [iconset (keyword (name ico-name))])
    (or (get custom-iconset (keyword ico-name))
        (get-in @iconsets_ [*default-iconset* (keyword ico-name)]))))

(def doc-icon
  {:examples ["[icon/Icon {::icon/name :plus}]"
              "[icon/Icon {::icon/name :bars :class \"size-5\"}]"
              "[icon/Icon {::icon/name :phosphor/cloud-thin}]"]
   :ns       *ns*
   :name     'Icon
   :desc     "Renders an icon from the available icon collections"
   :alias    ::icon
   :schema
   [:map {}
    [::name {:doc "The name of the icon to display. Can be namespaced with iconset or be a local custom icon keyword."}
     :keyword]]})

(def ^{:doc (uic/generate-docstring doc-icon)} Icon
  ::icon)

(defmethod c/resolve-alias ::icon
  [_ {::keys [name]
      :as    attrs} _children]
  (uic/validate-opts! doc-icon attrs)
  (when-let [icon (ico name)]
    (cc/compile
     (update-in icon [1]
                uic/merge-attrs* attrs))))

(def doc-spinner
  {:examples ["[icon/Spinner]"
              "[icon/Spinner {:class \"size-5\"}]"]
   :ns       *ns*
   :name     'Spinner
   :alias    ::spinner
   :desc     "Renders an animated svg spinner"
   :schema   [:map {}]})

(def ^{:doc (uic/generate-docstring doc-spinner)} Spinner
  ::spinner)

(defmethod c/resolve-alias ::spinner
  [_ attrs _children]
  (cc/compile
   [:svg (uic/merge-attrs attrs :class "spinner animate-spin")
    [:use {:href "#svg-sprite-spinner"}]]))
