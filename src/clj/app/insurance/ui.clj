(ns app.insurance.ui
  (:require
   [app.icons :as icons]
   [app.insurance.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.avatar :as avatar]
   [app.ui2.card :as card]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]
   [medley.core :as m]
   [tick.core :as t]))

(def todo-metric-order
  [:needs-review :changed :new :removed])

(def todo-metric-data
  {:needs-review {:icon-name   "circle-question-outline"
                  :tooltip-key :insurance/total-needs-review-tooltip}
   :changed      {:icon-name   "circle-exclamation"
                  :tooltip-key :insurance/total-total-changed-tooltip}
   :new          {:icon-name   "circle-plus-solid"
                  :tooltip-key :insurance/total-total-new-tooltip}
   :removed      {:icon-name   "circle-xmark"
                  :tooltip-key :insurance/total-total-removed-tooltip}})

(defn todo-metric
  [{:keys [class-prefix count id-prefix policy-id status]}]
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
        [:i18n/tr tooltip-key]]))))

(def workflow-status-data
  {:instrument.coverage.status/needs-review    {:icon      :circle-question-outline
                                                :color     "var(--sno-dashboard-insurance-todo-needs-review-color, var(--wa-color-warning-fill-loud))"
                                                :label-key :insurance/coverage-status-needs-review
                                                :variant   "warning"}
   :instrument.coverage.status/reviewed        {:icon      :circle-dot-outline
                                                :color     "var(--sno-gig-row-gray-400)"
                                                :label-key :insurance/coverage-status-reviewed
                                                :variant   "neutral"}
   :instrument.coverage.status/coverage-active {:icon      :circle-check-outline
                                                :color     "var(--wa-color-success-fill-loud)"
                                                :label-key :insurance/coverage-status-active
                                                :variant   "success"}})

(def change-status-data
  {:instrument.coverage.change/changed {:icon      :circle-exclamation
                                        :color     "var(--wa-color-warning-fill-loud)"
                                        :label-key :insurance/coverage-change-modified
                                        :variant   "warning"}
   :instrument.coverage.change/new     {:icon      :circle-plus-solid
                                        :color     "var(--wa-color-success-fill-loud)"
                                        :label-key :insurance/coverage-change-added
                                        :variant   "success"}
   :instrument.coverage.change/removed {:icon      :circle-xmark-outline
                                        :color     "var(--wa-color-danger-fill-loud)"
                                        :label-key :insurance/coverage-change-removed
                                        :variant   "danger"}
   :instrument.coverage.change/none    {:icon      :minus
                                        :color     "var(--wa-color-neutral-fill-loud)"
                                        :label-key :insurance/coverage-change-none
                                        :variant   "neutral"}})

(defn status-color
  [status]
  (:color (workflow-status-data status)))

(defn change-color
  [change]
  (:color (change-status-data change)))

(defn status-label-key
  [status]
  (:label-key (workflow-status-data status)))

(defn change-label-key
  [change]
  (:label-key (change-status-data change)))

(def policy-status-label-keys
  {:insurance.policy.status/active :insurance/policy-status-active
   :insurance.policy.status/draft  :insurance/policy-status-draft
   :insurance.policy.status/sent   :insurance/policy-status-sent})

(defn policy-status-label-key
  [status]
  (policy-status-label-keys status))

(defn- status-icon
  ([status-data]
   (status-icon status-data nil))
  ([{:keys [color icon]} attrs]
   [ico/Icon (cond-> (merge {::ico/library :snoico
                             ::ico/name    icon
                             :aria-hidden  true}
                            attrs)
               color (assoc :style (str "color: " color ";")))]))

(defn workflow-status-icon
  ([status]
   (workflow-status-icon status nil))
  ([status attrs]
   (when-let [status-data (workflow-status-data status)]
     (status-icon status-data attrs))))

(defn change-status-icon
  ([change]
   (change-status-icon change nil))
  ([change attrs]
   (when-let [status-data (change-status-data change)]
     (status-icon status-data attrs))))

(defn- status-label*
  [data status]
  (when-let [{:keys [label-key] :as status-data} (data status)]
    [:span {:class "wa-cluster wa-gap-2xs wa-align-items-center"}
     (status-icon status-data)
     [:span [:i18n/tr label-key]]]))

(defn status-label
  [status]
  (status-label* workflow-status-data status))

(defn change-label
  [change]
  (status-label* change-status-data change))

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

(defn- coverage-type-label
  [coverage-type]
  (let [label (some-> coverage-type :insurance.coverage.type/name str str/trim)]
    (when-not (str/blank? label)
      label)))

(defn coverage-type-token
  [id-prefix coverage-id index coverage-type]
  (when-let [label (coverage-type-label coverage-type)]
    (let [icon (:insurance.coverage.type/icon coverage-type)]
      (if (contains? (set (icons/catalog)) icon)
        (let [icon-id (str id-prefix
                           "-"
                           (ui2/safe-dom-id coverage-id)
                           "-"
                           index)]
          [[:span {:id                                icon-id
                   :data-insurance-coverage-type-icon (str (namespace icon) "/" (name icon))
                   :role                              "img"
                   :aria-label                        label
                   :tabindex                          0}
            [ico/Icon {::ico/library (keyword (namespace icon))
                       ::ico/name    (keyword (name icon))}]]
           [:wa-tooltip {:for           icon-id
                         :placement     "top"
                         :trigger       "click hover focus"
                         :without-arrow true}
            label]])
        [[:span {:data-insurance-coverage-type-label true} label]]))))

(defn- status-badge*
  [data status]
  (when-let [{:keys [label-key variant] :as status-data} (data status)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     (status-icon status-data {:slot "start"})
     [:i18n/tr label-key]]))

(defn status-badge
  [status]
  (status-badge* workflow-status-data status))

(defn change-badge
  [change]
  (status-badge* change-status-data change))

(defn- ownership-badge*
  [private? label-keys]
  [:wa-badge {:appearance "outlined"
              :variant    (if private? "warning" "success")
              :pill       true}
   [:i18n/tr (if private?
               (:private label-keys)
               (:band label-keys))]])

(defn ownership-badge
  [private?]
  (ownership-badge* private?
                    {:private :insurance/ownership-private
                     :band    :insurance/ownership-band}))

(defn ownership-badge-short
  [private?]
  (ownership-badge* private?
                    {:private :insurance/workbench-ownership-private
                     :band    :insurance/workbench-ownership-band}))

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
  [{:keys [full thumbnail]}]
  [:div {:class "wa-frame:portrait wa-border-radius-s"
         :style "aspect-ratio: 4 / 3; max-width: 300px;"}
   [:a {:href   full
        :target "_blank"
        :style  "display: block;"}
    [:img {:src     thumbnail
           :loading "lazy"
           :alt     ""}]]])

(defn photo-gallery
  [req instrument]
  (let [photo-uris (queries/image-uris req instrument)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} [:i18n/tr :insurance/photos]]
     (if (seq photo-uris)
       (into [:div {:class "wa-grid wa-gap-s" :style "--min-column-size: 10rem;"}]
             (map photo-card photo-uris))
       (ui2/empty-state [:i18n/tr :insurance/photos] [:i18n/tr :insurance/no-photos]))]))

(defn instrument-details
  [coverage]
  (let [instrument (:instrument.coverage/instrument coverage)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} [:i18n/tr :instrument/instrument]]
     (into [:div {:class "wa-stack wa-gap-xs"}]
           (divided-rows
            [(detail-row [:i18n/tr :instrument/owner] (member-link (:instrument/owner instrument)))
             (detail-row [:i18n/tr :instrument/category] (get-in instrument [:instrument/category :instrument.category/name]))
             (detail-row [:i18n/tr :instrument/make] (:instrument/make instrument))
             (detail-row [:i18n/tr :instrument/model] (:instrument/model instrument))
             (detail-row [:i18n/tr :instrument/serial-number] (:instrument/serial-number instrument))
             (detail-row [:i18n/tr :instrument/build-year] (:instrument/build-year instrument))
             (detail-row [:i18n/tr :instrument/description] (:instrument/description instrument))
             (when-let [share-url (not-empty (:instrument/images-share-url instrument))]
               (detail-row [:i18n/tr :instrument/images-share-url] (ui2/link-copy share-url)))]))]))

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
  [coverage policy]
  (let [currency (:insurance.policy/currency policy)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} [:i18n/tr :insurance/instrument-coverage]]
     (into [:div {:class "wa-stack wa-gap-xs"}]
           (divided-rows
            [(detail-row [:i18n/tr :insurance/item-count] (or (:instrument.coverage/item-count coverage) 1))
             (detail-row [:i18n/tr :insurance/value] (ui2/money (:instrument.coverage/value coverage) currency))
             (detail-row [:i18n/tr :insurance/insurer-id] (:instrument.coverage/insurer-id coverage))
             (detail-row [:i18n/tr :insurance/ownership] (ownership-badge (:instrument.coverage/private? coverage)))]))
     [:div {:class "insurance-coverage-types"}
      (ui2/table-shell
       [:table
        [:thead
         [:tr
          [:th {:scope "col"} [:i18n/tr :insurance/coverage-types]]
          [:th {:scope "col"} [:i18n/tr :insurance/premium-factor]]
          [:th {:scope "col"} [:i18n/tr :insurance/cost]]]]
        (into
         [:tbody]
         (mapcat #(coverage-type-rows currency %) (:instrument.coverage/types coverage)))
        [:tfoot
         [:tr
          [:th {:scope "row" :colspan 2} [:i18n/tr :insurance/total]]
          [:td (ui2/money (:instrument.coverage/cost coverage) currency)]]]])]]))

(defn coverage-detail-card
  [req {:keys [actions coverage error-message policy subtitle]}]
  (let [instrument (:instrument.coverage/instrument coverage)]
    [card/Card {:appearance "plain"
                :style      "background: var(--wa-color-surface-default);"}
     [:div {:class "wa-stack wa-gap-l"}
      [:div {:class "wa-stack wa-gap-xs"}
       [:div {:class "wa-cluster wa-gap-xs"}
        (status-badge (:instrument.coverage/status coverage))
        (change-badge (:instrument.coverage/change coverage))
        (ownership-badge (:instrument.coverage/private? coverage))]
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
       (instrument-details coverage)
       (coverage-details coverage policy)]
      (photo-gallery req instrument)]]))

(defn instant-value
  [value]
  (cond
    (t/instant? value)
    (str value)

    (inst? value)
    (str (t/instant value))

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
      (avatar/initials (author-name comment))))

(defn comment-item
  [comment]
  [:li {:class "wa-stack wa-gap-2xs"}
   [:div {:class "wa-flank"}
    [avatar/Avatar {::avatar/name (author-name comment)
                    ::avatar/initials (author-initials comment)}]
    [:div {:class "wa-cluster"}
     [:strong (author-name comment)]
     [:span {:class "wa-caption-s"}
      [:i18n/tr :insurance/review-queue-commented]
      " "
      [:wa-relative-time {:date (instant-value (:comment/created-at comment))}]]]]
   [:p (:comment/body comment)]])

(defn reply-link
  []
  [:li {:class "wa-cluster"}
   [ico/Icon {::ico/library :snoico
              ::ico/name    :comment-outline
              :aria-hidden  true}]
   [:a {:href          "#"
        :data-on:click "evt.preventDefault()"}
    [:i18n/tr :insurance/review-queue-leave-reply]]])

(defn comments-thread
  [replies]
  (when (seq replies)
    [:div {:class "wa-flank"}
     [divider/Divider {::divider/orientation :vertical
                       :style                 "height: auto; align-self: stretch;"}]
     (into [:ul {:class "wa-stack"
                 :style "list-style: none; padding-inline-start: 0; margin: 0;"}]
           (concat (map comment-item replies)
                   [(reply-link)]))]))

(defn comment-with-thread
  [comment]
  [:div {:class "wa-stack"}
   (comment-item comment)
   (comments-thread (:comment/replies comment))])

(defn comments-card
  [comments]
  [card/Card {:appearance "plain"
              :style      "background: var(--wa-color-surface-default);"}
   [:div {:class "wa-stack"}
    [:h2 {:class "wa-heading-l"} [:i18n/tr :insurance/review-queue-comments]]
    [:wa-textarea {:aria-label  [:i18n/tr :insurance/review-queue-comments]
                   :placeholder [:i18n/tr :insurance/review-queue-comment-placeholder]}]
    [button/Button {:appearance "filled"
                    :variant    "brand"
                    :disabled   true}
     [:i18n/tr :insurance/review-queue-add-comment]]
    [divider/Divider]
    (into [:ul {:class "wa-stack"
                :style "list-style: none; padding-inline-start: 0; margin: 0;"}]
          (map comment-with-thread comments))]])

(defn comments-aside
  ([]
   (comments-aside comments-dummy))
  ([comments]
   [:aside
    (comments-card comments)]))

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
  [req coverage image]
  (if-let [{:keys [thumbnail full]} (queries/image-uri req (:instrument.coverage/instrument coverage) image)]
    [:a {:href full :target "_blank" :class "insurance-history-image-link"}
     [:img {:class "insurance-history-image"
            :src   thumbnail
            :alt   [:i18n/tr :insurance/photos]}]]
    [:i18n/tr :insurance/history-image-deleted]))

(defn band-or-private
  [private?]
  (ownership-badge private?))

(def history-field-label-keys
  {:instrument/category                 :instrument/category
   :instrument/description              :instrument/description
   :instrument/images                   :insurance/photos
   :instrument/make                     :instrument/make
   :instrument/model                    :instrument/model
   :instrument/name                     :instrument/name
   :instrument/owner                    :instrument/owner
   :instrument/serial-number            :instrument/serial-number
   :instrument/build-year               :instrument/build-year
   :instrument.coverage/change          :insurance/coverage-change-status
   :instrument.coverage/cost            :insurance/cost
   :instrument.coverage/insurer-id      :insurance/insurer-id
   :instrument.coverage/instrument      :instrument/instrument
   :instrument.coverage/item-count      :insurance/item-count
   :instrument.coverage/private?        :insurance/ownership
   :instrument.coverage/status          :insurance/coverage-status
   :instrument.coverage/types           :insurance/coverage-types
   :instrument.coverage/value           :insurance/value})

(def history-action-label-keys
  {:added     :insurance/history-action-added
   :retracted :insurance/history-action-retracted
   :updated   :insurance/history-action-updated})

(defn coverage-currency
  [coverage]
  (or (get-in coverage [:insurance.policy/_covered-instruments :insurance.policy/currency])
      :EUR))

(defn change-value
  [req coverage k v]
  (cond
    (= k :instrument/category) (get v :instrument.category/name)
    (= k :instrument.coverage/value) (ui2/money v (coverage-currency coverage))
    (= k :instrument/owner) (member-link v)
    (= k :instrument.coverage/instrument) (:instrument/name v)
    (= k :instrument.coverage/types) (:insurance.coverage.type/name v)
    (= k :instrument.coverage/private?) (band-or-private v)
    (= k :instrument/images) (image-value req coverage v)
    (keyword? v) [:i18n/tr (or (status-label-key v)
                               (change-label-key v)
                               v)]
    :else (str v)))

(defn history-changes
  [req coverage changes]
  (->> changes
       (group-by first)
       vals
       (mapcat normalize-field-change)
       (map (fn [[k {:keys [before after]} action]]
              {:field-key   k
               :field-label [:i18n/tr (get history-field-label-keys k k)]
               :action      action
               :before      (when before (change-value req coverage k before))
               :after       (when after (change-value req coverage k after))}))
       (remove #(history-field-exclusions (:field-key %)))
       (sort-by (comp str :field-key))))

(defn history-icon
  [action]
  (icon (history-action-data action)))

(defn history-head
  []
  [:thead
   [:tr
    [:th {:scope "col"} [:i18n/tr :insurance/history-field]]
    [:th {:scope "col"} [:i18n/tr :insurance/history-before]]
    [:th {:scope "col"} [:i18n/tr :insurance/history-after]]]])

(defn history-action-cell
  [audit-user-name action]
  [:div {:class "insurance-history-editor"}
   (history-icon action)
   [:div {:class "insurance-history-editor-text"}
    [:span (ui2/muted audit-user-name "SNOrga")]
    [:span {:class "wa-caption-s"}
     [:i18n/tr (history-action-label-keys action)]]]])

(defn history-change-row
  [audit-user-name {:keys [action after before field-label]}]
  [:tr
   [:th {:scope "row"}
    [:span field-label]
    (history-action-cell audit-user-name action)]
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
         (history-change-row audit-user-name change-row))])))

(defn history-section
  [req coverage]
  (let [history (queries/coverage-history (:db req) coverage)]
    (ui2/section-card
     {:class    "wa-stack insurance-coverage-section insurance-history"
      :divider? true
      :title    [:i18n/tr :insurance/history-title]
      :subtitle [:i18n/tr :insurance/history-subtitle-coverage]}
     (if (seq history)
       [:div {:class "insurance-history-table"}
        (ui2/table-shell
         [:table
          (history-head)
          (keep #(history-entry req coverage %) history)])]
       (ui2/empty-state [:i18n/tr :insurance/history-title]
                        [:i18n/tr :insurance/history-no-changes])))))
