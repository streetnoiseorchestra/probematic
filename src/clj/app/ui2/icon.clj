(ns app.ui2.icon
  (:require
   [app.icons :as icons]
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-icon
  {:examples ["[ico/Icon {::ico/name :home}]"
              "[ico/Icon {::ico/name :info ::ico/library :phosphor}]"
              "[ico/Icon {::ico/name :bars :slot \"start\"}]"]
   :ns       *ns*
   :as       'ico
   :name     'Icon
   :desc     "Renders a native SVG icon from a runtime-generated sprite."
   :alias    ::icon
   :schema
   [:map {}
    [::name {:doc "The registered icon name to display."}
     [:or :keyword :string]]
    [::library {:optional true
                :default  :snoico
                :doc      "The registered icon library to use."}
     [:or :keyword :string]]
    [::auto-width {:optional true
                   :doc      "When true, lets the SVG use its intrinsic width instead of the standard fixed icon width."}
     :boolean]
    [::flip {:optional true
             :doc      "Flips the icon on the x-axis, y-axis, or both axes."}
     [:or [:enum :x :y :both] [:enum "x" "y" "both"]]]
    [::label {:optional true
              :doc      "Accessible label for non-decorative icons."}
     :string]
    [::rotate {:optional true
               :doc      "Rotates the icon by the given number of degrees."}
     [:or :int :double :string]]]})

(def ^{:doc (uic/generate-docstring doc-icon)} Icon
  ::icon)

(def ^:private consumed-props
  #{::name ::library ::auto-width ::flip ::label ::rotate})

(defn- option [attrs namespaced-key default]
  (if (contains? attrs namespaced-key)
    (get attrs namespaced-key)
    default))

(defn- svg-style [style rotate]
  (if rotate
    (str (when (seq (str style))
           (str style "; "))
         "--rotate-angle: " rotate "deg")
    style))

(defn- accessible-attrs [attrs label]
  (let [label (or label (:aria-label attrs))]
    (if label
      (-> attrs
          (dissoc :aria-hidden)
          (assoc :role "img"
                 :aria-label label
                 :focusable "false"))
      (assoc attrs
             :aria-hidden "true"
             :focusable "false"))))

(defn- icon-attrs [attrs]
  (let [label      (option attrs ::label nil)
        rotate     (option attrs ::rotate nil)
        flip       (option attrs ::flip nil)
        auto-width (option attrs ::auto-width false)
        dom-attrs  (apply dissoc attrs consumed-props)]
    (cond-> (-> dom-attrs
                (uic/merge-attrs :class "sno-icon")
                (update :style svg-style rotate)
                (accessible-attrs label))
      auto-width (assoc :auto-width true)
      flip       (assoc :flip (name flip))
      rotate     (assoc :rotate rotate))))

(defmethod c/resolve-alias ::icon
  [_ attrs _children]
  (let [attrs   (or attrs {})
        library (option attrs ::library :snoico)
        name    (option attrs ::name nil)]
    (uic/validate-opts! doc-icon attrs)
    (when-not name
      (throw (ex-info "Icon name is required" {:attrs attrs})))
    (cc/compile
     [:svg (icon-attrs attrs)
      [:use {:href (icons/sprite-href library name)}]])))
