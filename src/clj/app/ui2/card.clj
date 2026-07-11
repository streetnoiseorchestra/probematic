(ns app.ui2.card
  (:require
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-card
  {:examples ["[card/Card \"Card content\"]"
              "[card/Card {:appearance \"filled\"} [:h2 {:slot \"header\"} \"Summary\"] \"Card content\"]"
              "[card/Card {:orientation \"horizontal\"} media content actions]"]
   :ns       *ns*
   :as       'card
   :name     'Card
   :desc     "Renders a Web Awesome-styled card with native HTML sections for card content, media, headers, footers, and actions."
   :alias    ::card
   :schema   [:map {}
              [:appearance {:optional true
                            :default  "outlined"
                            :doc      "The card's visual appearance."}
               [:enum "accent" "filled" "outlined" "filled-outlined" "plain"]]
              [:orientation {:optional true
                             :default  "vertical"
                             :doc      "The card's layout orientation."}
               [:enum "horizontal" "vertical"]]]})

(def ^{:doc (uic/generate-docstring doc-card)} Card
  ::card)

(defn- token-value [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    :else            nil))

(defn- child-attrs [child]
  (when (and (vector? child) (map? (second child)))
    (second child)))

(defn- child-slot [child]
  (some-> (child-attrs child) :slot token-value))

(defn- slot-key [child]
  (case (child-slot child)
    "media"          :media
    "header"         :header
    "header-actions" :header-actions
    "footer"         :footer
    "footer-actions" :footer-actions
    "actions"        :actions
    :body))

(defn- sections [children]
  (reduce (fn [sections child]
            (if (nil? child)
              sections
              (update sections (slot-key child) conj child)))
          {:media          []
           :header         []
           :header-actions []
           :body           []
           :footer         []
           :footer-actions []
           :actions        []}
          children))

(defn- root-attrs [attrs]
  (let [appearance  (or (token-value (:appearance attrs)) "outlined")
        orientation (or (token-value (:orientation attrs)) "vertical")]
    (-> (dissoc attrs :class :appearance :orientation)
        (assoc :appearance appearance
               :orientation orientation)
        (uic/merge-attrs :class (uic/cs "sno-card" (:class attrs))))))

(defn- section [tag class children]
  (into [tag {:class class}] children))

(defn- vertical-card [attrs {:keys [media header header-actions body footer footer-actions]}]
  (let [header? (or (seq header) (seq header-actions))
        footer? (or (seq footer) (seq footer-actions))]
    (cond-> [:div attrs]
      (seq media) (conj (section :div "media" media))
      header?     (conj (section :header
                                 (uic/cs "header" (when (seq header-actions) "has-actions"))
                                 (concat header header-actions)))
      true        (conj (section :div "body" body))
      footer?     (conj (section :footer
                                 (uic/cs "footer" (when (seq footer-actions) "has-actions"))
                                 (concat footer footer-actions))))))

(defn- horizontal-card [attrs {:keys [media body actions]}]
  (cond-> [:div attrs]
    (seq media)   (conj (section :div "media" media))
    true          (conj (section :div "body" body))
    (seq actions) (conj (section :div "actions" actions))))

(defmethod c/resolve-alias ::card
  [_ attrs children]
  (let [attrs       (or attrs {})
        orientation (or (token-value (:orientation attrs)) "vertical")]
    (uic/validate-opts! doc-card attrs)
    (cc/compile
     ((if (= orientation "horizontal") horizontal-card vertical-card)
      (root-attrs attrs)
      (sections children)))))
