(ns app.ui2.button
  (:require
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-button
  {:examples ["[button/Button {:appearance \"filled\" :variant \"brand\"} \"Save\"]"
              "[button/Button {:href \"/members\" :appearance \"plain\"} \"Members\"]"
              "[button/Button {:appearance \"plain\" :aria-label \"Download\"} [ico/Icon {::ico/name :download ::ico/library :phosphor}]]"]
   :ns       *ns*
   :as       'button
   :name     'Button
   :desc     "Renders a Web Awesome-styled button, using native HTML for basic buttons and `wa-button` for advanced buttons."
   :alias    ::button
   :schema   [:map {}]})

(def ^{:doc (uic/generate-docstring doc-button)} Button
  ::button)

(defn- present-attr? [attrs k]
  (and (contains? attrs k)
       (some? (get attrs k))))

(defn- token-value [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    :else           nil))

(defn- appearance-classes [appearance]
  (case (token-value appearance)
    "accent"          ["wa-accent"]
    "filled"          ["wa-filled"]
    "outlined"        ["wa-outlined"]
    "filled-outlined" ["wa-filled" "wa-outlined"]
    "plain"           ["wa-plain"]
    nil               []
    []))

(defn- variant-class [variant]
  (when-let [variant (token-value variant)]
    (str "wa-" variant)))

(defn- size-class [size]
  (when-let [size (token-value size)]
    (str "wa-size-"
         (case size
           "small"  "s"
           "medium" "m"
           "large"  "l"
           size))))

(defn- native-classes [attrs link?]
  (apply uic/cs
         (concat
          (when link? ["wa-button"])
          (appearance-classes (:appearance attrs))
          [(variant-class (:variant attrs))
           (size-class (:size attrs))
           (when (:pill attrs) "wa-pill")]
          [(:class attrs)])))

(defn- native-attrs [attrs link?]
  (let [classes (native-classes attrs link?)]
    (cond-> (dissoc attrs :appearance :variant :size :pill :class :with-caret)
      link?       (dissoc :type)
      (not link?) (update :type #(or % "button"))
      (seq classes) (assoc :class classes))))

(defn- icon-child? [child]
  (and (vector? child)
       (#{:wa-icon ico/Icon} (first child))))

(defn- basic-label-child? [child]
  (and (some? child)
       (not (vector? child))))

(defn- basic-shape? [children]
  (let [children    (filter some? children)
        icon-count  (count (filter icon-child? children))
        label-count (count (filter basic-label-child? children))]
    (and (= (count children) (+ icon-count label-count))
         (<= icon-count 1)
         (or (and (= 1 label-count)
                  (<= icon-count 1))
             (and (= 0 label-count)
                  (= 1 icon-count))))))

(defn- advanced? [attrs children]
  (or (present-attr? attrs :loading)
      (present-attr? attrs :data-attr:loading)
      (true? (:with-caret attrs))
      (not (basic-shape? children))))

(defn- native-children [children]
  (let [children (filter some? children)
        has-icon? (some icon-child? children)]
    (map (fn [child]
           (if (and has-icon? (basic-label-child? child))
             [:span child]
             child))
         children)))

(defmethod c/resolve-alias ::button
  [_ attrs children]
  (uic/validate-opts! doc-button attrs)
  (let [attrs    (or attrs {})
        children (filter some? children)]
    (cc/compile
     (if (advanced? attrs children)
       (into [:wa-button attrs] children)
       (let [link? (present-attr? attrs :href)
             tag   (if link? :a :button)]
         (into [tag (native-attrs attrs link?)]
               (native-children children)))))))
