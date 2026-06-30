(ns app.insurance.ui
  (:require
   [app.insurance.coverage.queries :as coverage.queries]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]
   [medley.core :as m]))

(def todo-metric-order
  [:needs-review :changed :new :removed])

(def todo-metric-data
  {:needs-review {:icon-name   "circle-question-outline"
                  :tooltip-key [:insurance/total-needs-review-tooltip]}
   :changed      {:icon-name   "circle-exclamation"
                  :tooltip-key [:insurance/total-total-changed-tooltip]}
   :new          {:icon-name   "circle-plus-solid"
                  :tooltip-key [:insurance/total-total-new-tooltip]}
   :removed      {:icon-name   "circle-xmark"
                  :tooltip-key [:insurance/total-total-removed-tooltip]}})

(defn todo-metric
  [tr {:keys [class-prefix count id-prefix policy-id status]}]
  (when (pos? (or count 0))
    (let [{:keys [icon-name tooltip-key]} (todo-metric-data status)
          status-name                     (name status)
          metric-id                       (str id-prefix
                                               "-"
                                               (ui2/safe-dom-id policy-id)
                                               "-"
                                               status-name)]
      (list
       [:span {:id    metric-id
               :class (ui2/cs (str class-prefix "-metric")
                              (str class-prefix "-metric--" status-name))}
        [ico/Icon {::ico/library :snoico
                   ::ico/name    icon-name}]
        [:span {:class (str class-prefix "-count")} count]]
       [:wa-tooltip {:for metric-id :class "wa-cloak"}
        (tr tooltip-key)]))))

(def workflow-status-data
  {:instrument.coverage.status/needs-review    {:icon    :circle-question-outline
                                                :variant "warning"}
   :instrument.coverage.status/reviewed        {:icon    :circle-dot-outline
                                                :variant "neutral"}
   :instrument.coverage.status/coverage-active {:icon    :circle-check-outline
                                                :variant "success"}})

(def change-status-data
  {:instrument.coverage.change/changed {:icon    :circle-exclamation
                                        :variant "warning"}
   :instrument.coverage.change/new     {:icon    :circle-plus-solid
                                        :variant "success"}
   :instrument.coverage.change/removed {:icon    :circle-xmark-outline
                                        :variant "danger"}
   :instrument.coverage.change/none    {:icon    :minus
                                        :variant "neutral"}})

(def history-action-data
  {:retracted {:icon "circle-xmark"       :class "insurance-history-icon--retracted"}
   :added     {:icon "circle-plus-solid"  :class "insurance-history-icon--added"}
   :updated   {:icon "circle-exclamation" :class "insurance-history-icon--updated"}})

(def history-field-exclusions
  #{:instrument.coverage/coverage-id
    :instrument/instrument-id})

(def comments-dummy
  [{:comment/comment-id #uuid "11111111-1111-4111-8111-111111111111"
    :comment/body       "I checked the source photo and the instrument details look consistent with the policy export."
    :comment/author     {:member/name "Robert Fox"
                         :member/nick "RF"}
    :comment/created-at #inst "2026-06-18T09:17:00.000-00:00"
    :comment/replies    [{:comment/comment-id #uuid "22222222-2222-4222-8222-222222222222"
                          :comment/body       "The value matches the latest list. The owner name still uses an old spelling in the source system."
                          :comment/author     {:member/name "Virginia Woolf"
                                               :member/nick "VW"}
                          :comment/created-at #inst "2026-06-18T12:32:00.000-00:00"}
                         {:comment/comment-id #uuid "33333333-3333-4333-8333-333333333333"
                          :comment/body       "I added a note to check that spelling during the next policy update."
                          :comment/author     {:member/name "Clarissa Vaughan"
                                               :member/nick "CV"}
                          :comment/created-at #inst "2026-06-19T07:48:00.000-00:00"}]}])

(defn icon
  [{:keys [class icon]}]
  (when icon
    [ico/Icon {::ico/library :snoico
               ::ico/name    icon
               :class        (ui2/cs "insurance-coverage-icon" class)
               :aria-hidden  true}]))

(defn status-badge
  [tr status]
  (when-let [{:keys [icon variant]} (workflow-status-data status)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     [ico/Icon {::ico/library :snoico
                ::ico/name    icon
                :aria-hidden  true}]
     (tr [status])]))

(defn change-badge
  [tr change]
  (when-let [{:keys [icon variant]} (change-status-data change)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     [ico/Icon {::ico/library :snoico
                ::ico/name    icon
                :aria-hidden  true}]
     (tr [change])]))

(defn kind-badge
  [tr private?]
  [:wa-badge {:appearance "outlined"
              :variant    (if private? "warning" "success")
              :pill       true}
   (if private?
     (tr [:private-instrument])
     (tr [:band-instrument]))])

(defn member-link
  [member]
  (if-let [member-id (:member/member-id member)]
    [:a {:href (urls/link-member member-id)} (:member/name member)]
    (:member/name member)))

(defn detail-row
  [label value]
  [:div {:class "wa-split wa-gap-m"}
   [:span {:class "wa-caption-s wa-color-text-quiet"} label]
   [:span {:style "min-inline-size: 0; overflow-wrap: anywhere; text-align: end;"}
    (ui2/muted value)]])

(defn divided-rows
  [rows]
  (mapcat (fn [row]
            [row [divider/Divider]])
          (keep identity rows)))

(defn photo-card
  [_req {:keys [full thumbnail]}]
  [:div {:class "wa-frame:portrait wa-border-radius-s"
         :style "aspect-ratio: 4 / 3; max-width: 300px;"}
   [:a {:href   full
        :target "_blank"
        :style  "display: block;"}
    [:img {:src     thumbnail
           :loading "lazy"
           :alt     ""}]]])

(defn photo-gallery
  [{:keys [tr] :as req} instrument]
  (let [photo-uris (coverage.queries/image-uris req instrument)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} (tr [:instrument/images])]
     (if (seq photo-uris)
       (into [:div {:class "wa-grid wa-gap-s" :style "--min-column-size: 10rem;"}]
             (map #(photo-card req %) photo-uris))
       (ui2/empty-state (tr [:instrument/images]) (tr [:insurance/no-photos])))]))

(defn instrument-details
  [{:keys [tr]} coverage]
  (let [instrument (:instrument.coverage/instrument coverage)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} (tr [:instrument/instrument])]
     (into [:div {:class "wa-stack wa-gap-xs"}]
           (divided-rows
            [(detail-row (tr [:instrument/owner]) (member-link (:instrument/owner instrument)))
             (detail-row (tr [:instrument/category]) (get-in instrument [:instrument/category :instrument.category/name]))
             (detail-row (tr [:instrument/make]) (:instrument/make instrument))
             (detail-row (tr [:instrument/model]) (:instrument/model instrument))
             (detail-row (tr [:instrument/serial-number]) (:instrument/serial-number instrument))
             (detail-row (tr [:instrument/build-year]) (:instrument/build-year instrument))
             (detail-row (tr [:instrument/description]) (:instrument/description instrument))
             (when-let [share-url (not-empty (:instrument/images-share-url instrument))]
               (detail-row (tr [:instrument/images-share-url]) (ui2/link-copy share-url)))]))]))

(defn coverage-type-rows
  [currency {:insurance.coverage.type/keys [name cost premium-factor description]}]
  (let [description (not-empty (str/trim (str description)))]
    (cond-> [[:tr
              [:th {:scope "row"} name]
              [:td premium-factor]
              [:td (ui2/money cost currency)]]]
      description
      (conj [:tr {:data-description true}
             [:td {:colspan 3}
              [:small description]]]))))

(defn coverage-details
  [{:keys [tr]} coverage policy]
  (let [currency (:insurance.policy/currency policy)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} (tr [:insurance/instrument-coverage])]
     (into [:div {:class "wa-stack wa-gap-xs"}]
           (divided-rows
            [(detail-row (tr [:insurance/item-count]) (or (:instrument.coverage/item-count coverage) 1))
             (detail-row (tr [:insurance/value]) (ui2/money (:instrument.coverage/value coverage) currency))
             (detail-row (tr [:instrument.coverage/insurer-id]) (:instrument.coverage/insurer-id coverage))
             (detail-row (tr [:band-private]) (kind-badge tr (:instrument.coverage/private? coverage)))]))
     [:div {:class "insurance-coverage-types"}
      (ui2/table-shell
       [:table
        [:thead
         [:tr
          [:th {:scope "col"} (tr [:insurance/coverage-types])]
          [:th {:scope "col"} (tr [:insurance/premium-factor])]
          [:th {:scope "col"} (tr [:instrument.coverage/cost])]]]
        (into
         [:tbody]
         (mapcat #(coverage-type-rows currency %) (:instrument.coverage/types coverage)))
        [:tfoot
         [:tr
          [:th {:scope "row" :colspan 2} (tr [:insurance/total])]
          [:td (ui2/money (:instrument.coverage/cost coverage) currency)]]]])]]))

(defn coverage-detail-card
  [{:keys [tr] :as req} {:keys [actions coverage error-message policy subtitle]}]
  (let [instrument (:instrument.coverage/instrument coverage)]
    [:wa-card {:appearance "plain"
               :style      "background: var(--wa-color-surface-default);"}
     [:div {:class "wa-stack wa-gap-l"}
      [:div {:class "wa-stack wa-gap-xs"}
       [:div {:class "wa-cluster wa-gap-xs"}
        (status-badge tr (:instrument.coverage/status coverage))
        (change-badge tr (:instrument.coverage/change coverage))
        (kind-badge tr (:instrument.coverage/private? coverage))]
       [:h2 {:class "wa-heading-xl"} (:instrument/name instrument)]
       (when-let [subtitle (or subtitle
                               (get-in instrument [:instrument/owner :member/name]))]
         [:div {:class "wa-caption-s wa-color-text-quiet"} subtitle])]
      (when error-message
        [:wa-callout {:appearance "outlined" :variant "danger"}
         error-message])
      actions
      [divider/Divider]
      [:div {:class "wa-grid wa-align-items-start" :style "--min-column-size: 24rem;"}
       (instrument-details req coverage)
       (coverage-details req coverage policy)]
      (photo-gallery req instrument)]]))

(defn instant-value
  [value]
  (cond
    (instance? java.time.Instant value)
    (str value)

    (instance? java.util.Date value)
    (str (.toInstant ^java.util.Date value))

    :else
    (str value)))

(defn author-name
  [comment]
  (or (get-in comment [:comment/author :member/name])
      (get-in comment [:comment/author :member/nick])
      ""))

(defn author-initials
  [comment]
  (or (not-empty (get-in comment [:comment/author :member/nick]))
      (->> (str/split (str/trim (author-name comment)) #"\\s+")
           (keep first)
           (take 2)
           (apply str)
           str/upper-case)))

(defn comment-item
  [{:keys [tr]} comment]
  [:li {:class "wa-stack wa-gap-2xs"}
   [:div {:class "wa-flank"}
    [:wa-avatar {:initials (author-initials comment)
                 :label    (author-name comment)}]
    [:div {:class "wa-cluster"}
     [:strong (author-name comment)]
     [:span {:class "wa-caption-s"}
      (tr [:insurance.review/commented])
      " "
      [:wa-relative-time {:date (instant-value (:comment/created-at comment))}]]]]
   [:p (:comment/body comment)]])

(defn reply-link
  [{:keys [tr]}]
  [:li {:class "wa-cluster"}
   [ico/Icon {::ico/library :snoico
              ::ico/name    :comment-outline
              :aria-hidden  true}]
   [:a {:href          "#"
        :data-on:click "evt.preventDefault()"}
    (tr [:insurance.review/leave-reply])]])

(defn comments-thread
  [req replies]
  (when (seq replies)
    [:div {:class "wa-flank"}
     [divider/Divider {::divider/orientation :vertical
                       :style                 "height: auto; align-self: stretch;"}]
     (into [:ul {:class "wa-stack"
                 :style "list-style: none; padding-inline-start: 0; margin: 0;"}]
           (concat (map #(comment-item req %) replies)
                   [(reply-link req)]))]))

(defn comment-with-thread
  [req comment]
  [:div {:class "wa-stack"}
   (comment-item req comment)
   (comments-thread req (:comment/replies comment))])

(defn comments-card
  [{:keys [tr] :as req} comments]
  [:wa-card {:appearance "plain"
             :style      "background: var(--wa-color-surface-default);"}
   [:div {:class "wa-stack"}
    [:h2 {:class "wa-heading-l"} (tr [:insurance.review/comments])]
    [:wa-textarea {:aria-label  (tr [:insurance.review/comments])
                   :placeholder (tr [:insurance.review/comment-placeholder])}]
    [button/Button {:appearance "filled"
                    :variant    "brand"
                    :disabled   true}
     (tr [:insurance.review/add-comment])]
    [divider/Divider]
    (into [:ul {:class "wa-stack"
                :style "list-style: none; padding-inline-start: 0; margin: 0;"}]
          (map #(comment-with-thread req %) comments))]])

(defn comments-aside
  ([req]
   (comments-aside req comments-dummy))
  ([req comments]
   [:aside
    (comments-card req comments)]))

(defn normalize-single-change
  [field-change]
  (let [[_k v action :as f] (first field-change)]
    (if (= action :added)
      (assoc f 1 {:before nil :after v :action action})
      (assoc f 1 {:before v :after nil :action action}))))

(defn normalize-change-pair
  [field-change]
  (let [actions (set (map #(nth % 2) field-change))]
    (if (= #{:retracted :added} actions)
      (let [added     (m/find-first #(= :added (nth % 2)) field-change)
            retracted (m/find-first #(= :retracted (nth % 2)) field-change)]
        [(-> (first field-change)
             (assoc 2 :updated)
             (assoc 1 {:before (second retracted)
                       :after  (second added)}))])
      (map normalize-single-change (map vector field-change)))))

(defn normalize-field-change
  [field-change]
  (case (count field-change)
    1 [(normalize-single-change field-change)]
    2 (normalize-change-pair field-change)
    (map normalize-single-change (map vector field-change))))

(defn image-value
  [{:keys [tr] :as req} coverage image]
  (if-let [{:keys [thumbnail full]} (coverage.queries/image-uri req (:instrument.coverage/instrument coverage) image)]
    [:a {:href full :target "_blank" :class "insurance-history-image-link"}
     [:img {:class "insurance-history-image"
            :src   thumbnail
            :alt   (tr [:instrument/images])}]]
    (tr [:history/image-deleted])))

(defn band-or-private
  [tr private?]
  (kind-badge tr private?))

(defn coverage-currency
  [coverage]
  (or (get-in coverage [:insurance.policy/_covered-instruments :insurance.policy/currency])
      :EUR))

(defn change-value
  [{:keys [tr] :as req} coverage k v]
  (cond
    (= k :instrument/category) (get v :instrument.category/name)
    (= k :instrument.coverage/value) (ui2/money v (coverage-currency coverage))
    (= k :instrument/owner) (member-link v)
    (= k :instrument.coverage/instrument) (:instrument/name v)
    (= k :instrument.coverage/types) (:insurance.coverage.type/name v)
    (= k :instrument.coverage/private?) (band-or-private tr v)
    (= k :instrument/images) (image-value req coverage v)
    (keyword? v) (tr [v])
    :else (str v)))

(defn history-changes
  [req coverage changes]
  (->> changes
       (group-by first)
       vals
       (mapcat normalize-field-change)
       (map (fn [[k {:keys [before after]} action]]
              {:field-key   k
               :field-label ((:tr req) [k])
               :action      action
               :before      (when before (change-value req coverage k before))
               :after       (when after (change-value req coverage k after))}))
       (remove #(history-field-exclusions (:field-key %)))
       (sort-by :field-label)))

(defn history-icon
  [action]
  (icon (history-action-data action)))

(defn history-head
  [{:keys [tr]}]
  [:thead
   [:tr
    [:th {:scope "col"} (tr [:history/field])]
    [:th {:scope "col"} (tr [:history/before])]
    [:th {:scope "col"} (tr [:history/after])]]])

(defn history-action-cell
  [tr audit-user-name action]
  [:div {:class "insurance-history-editor"}
   (history-icon action)
   [:div {:class "insurance-history-editor-text"}
    [:span (ui2/muted audit-user-name "SNOrga")]
    [:span {:class "wa-caption-s"}
     (tr [(keyword "history" (name action))])]]])

(defn history-change-row
  [{:keys [tr]} audit-user-name {:keys [action after before field-label]}]
  [:tr
   [:th {:scope "row"}
    [:span field-label]
    (history-action-cell tr audit-user-name action)]
   [:td (ui2/muted before)]
   [:td (ui2/muted after)]])

(defn history-date-row
  [req timestamp]
  (let [label (ui2/format-date-time req :medium timestamp)]
    [:tr
     [:th {:scope "rowgroup" :colspan 3}
      [:time {:class      "insurance-history-time"
              :datetime   (str timestamp)
              :title      label
              :aria-label label}
       (ui2/relative-time-value timestamp)]]]))

(defn history-entry
  [req coverage {:keys [audit changes timestamp]}]
  (let [change-rows     (history-changes req coverage changes)
        audit-user-name (get-in audit [:audit/member :member/name])]
    (when (seq change-rows)
      [:tbody
       (history-date-row req timestamp)
       (for [change-row change-rows]
         (history-change-row req audit-user-name change-row))])))

(defn history-section
  [req coverage]
  (let [history (coverage.queries/coverage-history (:db req) coverage)]
    (ui2/section-card
     {:class    "wa-stack insurance-coverage-section insurance-history"
      :divider? true
      :title    ((:tr req) [:history/title])
      :subtitle ((:tr req) [:history/subtitle-coverage])}
     (if (seq history)
       [:div {:class "insurance-history-table"}
        (ui2/table-shell
         [:table
          (history-head req)
          (keep #(history-entry req coverage %) history)])]
       (ui2/empty-state ((:tr req) [:history/title]) ((:tr req) [:history/no-changes]))))))
