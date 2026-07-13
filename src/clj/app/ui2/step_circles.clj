(ns app.ui2.step-circles
  (:require
   [app.ui2.icon :as ico]))

(defn- state-for-step [current-step step-number]
  (cond
    (< step-number current-step) :complete
    (= step-number current-step) :current
    :else :empty))

(defn- circle-content [state]
  (case state
    :complete [ico/Icon {::ico/library :phosphor
                         ::ico/name    :check}]
    (:current :empty) [:span {:aria-hidden true}]))

(defn- circle [state {:keys [href label]}]
  (let [tag   (if href :a :span)
        attrs (cond-> {:class      "circle"
                       :aria-label label}
                href (assoc :href href)
                (= :current state) (assoc :aria-current "step"))]
    [tag attrs (circle-content state)]))

(defn- connector [fill?]
  (cond-> [[:hr {:aria-hidden true}]]
    fill? (conj [:hr {:aria-hidden true}])))

(defn- step-item [current-step step-count idx step]
  (let [step-number (inc idx)
        state       (state-for-step current-step step-number)
        last?       (= step-number step-count)]
    (into
     [:li {:class (name state)}]
     (concat
      (when-not last?
        (connector (= :complete state)))
      [(circle state step)
       [:small (:label step)]]))))

(defn StepCircles [{::keys [current-step label steps]}]
  (let [steps (vec steps)]
    [:nav {:class                   "sno-step-circles"
           :aria-label              label
           :data-init__delay.10ms    "el.querySelector('[aria-current=step]')?.scrollIntoView({block: 'nearest', inline: 'center'})"}
     (into
      [:ol {:role "list"}]
      (map-indexed (partial step-item current-step (count steps)) steps))]))
