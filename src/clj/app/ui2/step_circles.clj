(ns app.ui2.step-circles
  (:require
   [app.ui2.icon :as ico]
   [clojure.string :as str]))

(defn- cs [& classes]
  (str/join " " (filter some? classes)))

(defn- state-for-step [current-step step-number]
  (cond
    (< step-number current-step) :complete
    (= step-number current-step) :current
    :else :empty))

(defn- circle-class [state]
  (cs "sno-step-circles__circle"
      (case state
        :complete "sno-step-circles__circle--complete"
        :current "sno-step-circles__circle--current"
        :empty "sno-step-circles__circle--empty")))

(defn- circle-content [state]
  (case state
    :complete [ico/Icon {::ico/library :phosphor
                         ::ico/name    :check}]
    (:current :empty) [:span {:class "sno-step-circles__dot"
                              :aria-hidden true}]))

(defn- circle [state {:keys [href label]}]
  (let [tag   (if href :a :span)
        attrs (cond-> {:class      (circle-class state)
                       :aria-label label}
                href (assoc :href href)
                (= :current state) (assoc :aria-current "step"))]
    [tag attrs (circle-content state)]))

(defn- connector [fill?]
  (list
   [:span {:class "sno-step-circles__connector"
           :aria-hidden true}
    [:span {:class "sno-step-circles__connector-line"}]]
   (when fill?
     [:span {:class "sno-step-circles__connector-fill"
             :aria-hidden true}
      [:span {:class "sno-step-circles__connector-line"}]])))

(defn- step-item [current-step step-count idx step]
  (let [step-number (inc idx)
        state       (state-for-step current-step step-number)
        last?       (= step-number step-count)]
    [:li {:class "sno-step-circles__item"}
     (when-not last?
       (connector (= :complete state)))
     (circle state step)
     [:span {:class "sno-step-circles__label"}
      (:label step)]]))

(defn StepCircles [{::keys [current-step label steps]}]
  (let [steps (vec steps)]
    [:nav {:class "sno-step-circles"
           :aria-label label}
     (into
      [:ol {:class "sno-step-circles__list"
            :role  "list"}]
      (map-indexed (partial step-item current-step (count steps)) steps))]))
