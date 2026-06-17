(ns app.ui2.divider
  (:require
   [app.ui2.core :as uic]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-divider
  {:examples ["[divider/Divider]"
              "[divider/Divider {::divider/orientation :vertical}]"
              "[divider/Divider {::divider/width \"4px\" ::divider/color \"tomato\" ::divider/spacing \"2rem\"}]"]
   :ns       *ns*
   :as       'divider
   :name     'Divider
   :desc     "Dividers visually separate or group adjacent elements with a horizontal or vertical line."
   :alias    ::divider
   :schema
   [:map {}
    [::orientation {:optional true
                    :default  :horizontal
                    :doc      "Sets the divider's orientation."}
     [:or [:enum :horizontal :vertical]
      [:enum "horizontal" "vertical"]]]
    [::color {:optional true
              :doc      "The divider color. Sets the `--color` CSS custom property."}
     :string]
    [::width {:optional true
              :doc      "The divider width. Sets the `--width` CSS custom property."}
     :string]
    [::spacing {:optional true
                :doc      "The divider spacing. Sets the `--spacing` CSS custom property."}
     :string]]})

(def ^{:doc (uic/generate-docstring doc-divider)} Divider
  ::divider)

(def ^:private consumed-props
  #{::orientation ::color ::width ::spacing :orientation :color :width :spacing})

(defn- option [attrs namespaced-key plain-key default]
  (cond
    (contains? attrs namespaced-key) (get attrs namespaced-key)
    (contains? attrs plain-key)      (get attrs plain-key)
    :else                           default))

(defn- token-value [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    :else           nil))

(defn- custom-property-declarations [attrs]
  (keep (fn [[namespaced-key plain-key css-property]]
          (when-let [value (option attrs namespaced-key plain-key nil)]
            (str css-property ": " value)))
        [[::color :color "--color"]
         [::width :width "--width"]
         [::spacing :spacing "--spacing"]]))

(defn- style-with-custom-properties [style attrs]
  (let [style        (when-not (str/blank? (str style))
                       (str/replace (str style) #";+\\s*$" ""))
        declarations (seq (custom-property-declarations attrs))]
    (cond
      (and style declarations) (str style "; " (str/join "; " declarations))
      declarations             (str/join "; " declarations)
      :else                    style)))

(defn- divider-orientation [attrs]
  (or (token-value (option attrs ::orientation :orientation :horizontal))
      "horizontal"))

(defn- divider-attrs [attrs]
  (let [orientation (divider-orientation attrs)
        dom-attrs   (apply dissoc attrs consumed-props)
        classes     (uic/cs "sno-divider" (:class dom-attrs))
        style       (style-with-custom-properties (:style dom-attrs) attrs)]
    (assoc (cond-> (dissoc dom-attrs :class :style)
             (seq classes) (assoc :class classes)
             style         (assoc :style style))
           :role "separator"
           :orientation orientation
           :aria-orientation orientation)))

(defmethod c/resolve-alias ::divider
  [_ attrs _children]
  (let [attrs (or attrs {})]
    (uic/validate-opts! doc-divider attrs)
    (cc/compile
     [:hr (divider-attrs attrs)])))
