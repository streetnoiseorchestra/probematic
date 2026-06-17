(ns app.poll.ui
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [clojure.string :as str]
   [jsonista.core :as j]
   [nextjournal.markdown :as md]
   [nextjournal.markdown.transform :as md.transform]
   [tick.core :as t]))

(defn status-badge
  ([tr status]
   (status-badge tr status nil))
  ([tr status attrs]
   [:wa-badge (merge attrs
                     (cond-> {:appearance "outlined"
                              :pill       true}
                       (= :poll.status/draft status)  (assoc :variant "neutral")
                       (= :poll.status/open status)   (assoc :variant "success")
                       (= :poll.status/closed status) (assoc :variant "brand")))
    (tr [status])]))

(defn date-time-input-value [value]
  (when value
    (subs (str (t/truncate (t/date-time value) :minutes)) 0 16)))

(defn default-closes-at []
  (date-time-input-value (t/>> (t/date-time) (t/new-period 7 :days))))

(defn description-markdown [text]
  [:div {:class "poll-markdown"}
   (when-not (str/blank? text)
     (md.transform/->hiccup md.transform/default-hiccup-renderers (md/parse text)))])

(defn- poll-index-stat [label value]
  [:span {:class "polls-row-stat"}
   [:span label]
   [:strong value]])

(defn poll-row [{:keys [tr] :as req} {:poll/keys [title poll-status closes-at] :as poll}]
  [:a {:class "polls-row"
       :href  (urls/link-poll poll)}
   [:span {:class "polls-row-title"} title]
   (status-badge tr poll-status {:class "polls-row-status"})
   (poll-index-stat (tr [:poll/total-voters])
                    (or (:poll/voter-count poll) 0))
   (poll-index-stat (tr [:poll/total-votes])
                    (or (:poll/votes-count poll) 0))
   (poll-index-stat (tr [:poll/closes])
                    (ui2/date-display req :short closes-at))])

(defn poll-section [req {:keys [empty-message polls title]}]
  [:section {:class "wa-stack wa-gap-xs"}
   (ui2/section-divider title)
   [:wa-card {:class "polls-list-card"}
    (if (seq polls)
      (for [poll polls]
        (poll-row req poll))
      [:div {:class "polls-empty"} empty-message])]])

(defn result-rows [{:poll/keys [options votes]}]
  (let [counts (frequencies (map #(get-in % [:poll.vote/poll-option :poll.option/poll-option-id]) votes))]
    (mapv (fn [{:poll.option/keys [poll-option-id position value]}]
            {:id       poll-option-id
             :position position
             :label    value
             :votes    (get counts poll-option-id 0)})
          (sort-by :poll.option/position options))))

(defn total-voters [{:poll/keys [votes]}]
  (count (distinct (map #(get-in % [:poll.vote/author :member/member-id]) votes))))

(defn total-votes [{:poll/keys [votes]}]
  (count votes))

(defn chart-data [poll]
  (let [rows (result-rows poll)]
    {:labels      (mapv :label rows)
     :totalVoters (total-voters poll)
     :values      (mapv :votes rows)}))

(defn chart [poll]
  (let [data-id (str "poll-values-" (:poll/poll-id poll))]
    [:div {:class "poll-chart-panel"}
     [:script {:id   data-id
               :type "application/json"}
      (html/raw (j/write-value-as-string (chart-data poll)))]
     [:div {:class "poll-chart-container"}
      [:canvas {:class             "poll-chart"
                :data-poll-values  (str "#" data-id)
                :aria-hidden       "true"}]]]))

(defn chart-scripts []
  [[:script {:src "/vendor/chart.js@4.4.0/chart.umd.js"}]
   [:script {:src "/vendor/chartjs-plugin-datalabels@2.2.0/chartjs-plugin-datalabels.min.js"}]
   [:script {:src "/js/widgets/poll-chart.js" :type "module"}]])

(defn poll->form [poll]
  {:poll-id     (some-> (:poll/poll-id poll) str)
   :title       (:poll/title poll)
   :description (:poll/description poll)
   :poll-type   (or (some-> (:poll/poll-type poll) name) "single")
   :min-choice  (or (some-> (:poll/min-choice poll) str) "1")
   :max-choice  (or (some-> (:poll/max-choice poll) str) "2")
   :closes-at   (or (date-time-input-value (:poll/closes-at poll)) (default-closes-at))
   :autoremind? (boolean (:poll/autoremind? poll))
   :options     (if (seq (:poll/options poll))
                  (mapv (fn [option]
                          {:value (:poll.option/value option)})
                        (sort-by :poll.option/position (:poll/options poll)))
                  [{:value ""}
                   {:value ""}])})

(defn create-form-state []
  (poll->form {:poll/poll-type :poll.type/single
               :poll/options   [{:poll.option/value ""}
                                {:poll.option/value ""}]}))

(defn loading-attrs [id]
  {:data-attr:disabled (str "!!$loading && $loading !== '" id "'")
   :data-attr:loading  (str "$loading === '" id "'")})

(defn action-button-attrs [req action id]
  (merge {:data-id     id
          :data-action (d*/act req action)}
         (loading-attrs id)))
