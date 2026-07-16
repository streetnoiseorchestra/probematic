(ns app.poll.ui
  (:require
   [app.datastar :as d*]
   [app.poll.domain :as domain]
   [app.ui2 :as ui2]
   [app.ui2.card :as card]
   [app.urls :as urls]
   [clojure.string :as str]
   [nextjournal.markdown :as md]
   [nextjournal.markdown.transform :as md.transform]
   [tick.core :as t]))

(defn status-badge
  ([status]
   (status-badge status nil))
  ([status attrs]
   [:wa-badge (merge attrs
                     (cond-> {:appearance "outlined"
                              :pill       true}
                       (= :poll.status/draft status)  (assoc :variant "neutral")
                       (= :poll.status/open status)   (assoc :variant "success")
                       (= :poll.status/closed status) (assoc :variant "brand")))
    [:i18n/tr (domain/status-label-key status)]]))

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

(defn poll-row [req {:poll/keys [title poll-status closes-at] :as poll}]
  [:div {:class "polls-row"}
   [:a {:class "polls-row-title"
        :href  (urls/link-poll poll)}
    [:span title]]
   [:a {:class       "wa-link-plain"
        :href        (urls/link-poll poll)
        :aria-hidden "true"
        :tabindex    "-1"}]
   (status-badge poll-status {:class "polls-row-status"})
   (poll-index-stat [:i18n/tr :polls/total-voters]
                    (or (:poll/voter-count poll) 0))
   (poll-index-stat [:i18n/tr :polls/total-votes]
                    (or (:poll/votes-count poll) 0))
   (poll-index-stat [:i18n/tr :polls/closes]
                    (ui2/date-display req :short closes-at))])

(defn poll-section [req {:keys [empty-message polls title]}]
  [:section {:class "wa-stack wa-gap-xs"}
   (ui2/section-divider title)
   [card/Card {:class "polls-list-card"}
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

(def ^:private result-colors
  ["var(--wa-color-success-fill-loud)"
   "var(--wa-color-warning-fill-loud)"
   "var(--wa-color-purple-60)"
   "var(--wa-color-danger-fill-loud)"
   "var(--wa-color-yellow-60)"
   "var(--wa-color-brand-fill-loud)"])

(defn- vote-percent [votes total-voters]
  (if (pos? total-voters)
    (* 100.0 (/ votes total-voters))
    0.0))

(defn- rounded-percent [value]
  (/ (Math/round (* (double value) 10.0)) 10.0))

(defn- format-percent-value [value]
  (let [tenths  (Math/round (* (double value) 10.0))
        whole   (quot tenths 10)
        decimal (mod tenths 10)]
    (if (zero? decimal)
      (str whole)
      (str whole "." decimal))))

(defn- format-percent [value]
  (str (format-percent-value value) "%"))

(defn- result-color [idx]
  (nth result-colors (mod idx (count result-colors))))

(defn- result-summary [percentage votes]
  (str (format-percent percentage) " (" votes ")"))

(defn- result-bar [total-voters idx {:keys [label votes]}]
  (let [percentage (rounded-percent (vote-percent votes total-voters))
        percent-value (format-percent-value percentage)
        summary       (result-summary percentage votes)]
    [:li {:class "poll-result-row"}
     [:div {:class "poll-result-row-header"}
      [:span {:class "poll-result-label"} label]
      [:span {:class "poll-result-value"} summary]]
     [:wa-progress-bar {:label (str label " " summary)
                        :style {"--indicator-color" (result-color idx)
                                "--poll-result-progress-value" (str percent-value "%")}
                        :value percent-value}]]))

(defn result-bars [poll]
  (let [total-voters (total-voters poll)]
    (into [:ol {:class "poll-result-bars"}]
          (map-indexed (partial result-bar total-voters) (result-rows poll)))))

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

(d*/refresh-all!)
