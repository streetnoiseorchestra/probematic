(ns app.insurance.coverage.views
  (:require
   [app.datastar :as d*]
   [app.insurance.coverage.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]
   [medley.core :as m]))

(def history-action-data
  {:retracted {:icon "circle-xmark"       :class "insurance-history-icon--retracted"}
   :added     {:icon "circle-plus-solid"  :class "insurance-history-icon--added"}
   :updated   {:icon "circle-exclamation" :class "insurance-history-icon--updated"}})

(def history-field-exclusions
  #{:instrument.coverage/coverage-id
    :instrument/instrument-id})

(defn- coverage-id [{:keys [parameters path-params]}]
  (let [value (or (get-in parameters [:path :coverage-id])
                  (:coverage-id path-params))]
    (cond
      (uuid? value) value
      (string? value) (parse-uuid value))))

(defn- icon [{:keys [class icon]}]
  (when icon
    [ico/Icon {::ico/library :snoico
               ::ico/name    icon
               :class        (ui2/cs "insurance-coverage-icon" class)
               :aria-hidden  true}]))

(defn- kind-badge [tr private?]
  [:wa-badge {:appearance "outlined"
              :variant    (if private? "danger" "success")
              :pill       true}
   (if private?
     (tr [:private-instrument])
     (tr [:band-instrument]))])

(defn- breadcrumb [{:keys [tr]} policy instrument]
  [breadcrumb/Breadcrumb {:class "insurance-coverage-breadcrumb"}
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
    (:insurance.policy/name policy)]
   [breadcrumb/BreadcrumbItem
    (:instrument/name instrument)]])

(defn- member-link [member]
  (if-let [member-id (:member/member-id member)]
    [:a {:href (urls/link-member member-id)} (:member/name member)]
    (:member/name member)))

(defn- page-actions [{:keys [tr]} coverage policy]
  (when (queries/policy-editable? policy)
    [[button/Button {:appearance "outlined"
                     :variant    "brand"
                     :href       (urls/link-coverage-edit coverage)}
      [ico/Icon {::ico/library :snoico
                 ::ico/name    :cog
                 :slot         "start"}]
      (tr [:action/edit])]]))

(defn- image-card [_req {:keys [thumbnail full]}]
  [:a {:href   full
       :target "_blank"
       :class  "insurance-photo-link"}
   [:img {:src     thumbnail
          :loading "lazy"
          :alt     ""}]])

(defn- photo-grid [{:keys [tr] :as req} instrument]
  (let [photo-uris (queries/image-uris req instrument)]
    (if (seq photo-uris)
      (into [:div {:class "insurance-photo-grid wa-grid wa-gap-s"}]
            (map #(image-card req %) photo-uris))
      (ui2/empty-state (tr [:instrument/images]) (tr [:insurance/no-photos])))))

(defn- instrument-section [{:keys [tr] :as req} instrument]
  (ui2/section-card
   {:class    "wa-stack insurance-coverage-section"
    :divider? true}
   [:dl {:class "particulars"}
    (ui2/detail-item (tr [:instrument/owner]) (member-link (:instrument/owner instrument)))
    (ui2/detail-item (tr [:instrument/name]) (:instrument/name instrument))
    (ui2/detail-item (tr [:instrument/category]) (get-in instrument [:instrument/category :instrument.category/name]))
    (ui2/detail-item (tr [:instrument/make]) (:instrument/make instrument))
    (ui2/detail-item (tr [:instrument/model]) (:instrument/model instrument))
    (ui2/detail-item (tr [:instrument/serial-number]) (:instrument/serial-number instrument))
    (ui2/detail-item (tr [:instrument/build-year]) (:instrument/build-year instrument))
    (ui2/detail-item (tr [:instrument/description]) (:instrument/description instrument))
    (when-let [share-url (not-empty (:instrument/images-share-url instrument))]
      (ui2/detail-item (tr [:instrument/images-share-url])
                       [:a {:href share-url :target "_blank"} share-url]))]
   (photo-grid req instrument)))

(defn- coverage-type-rows [currency {:insurance.coverage.type/keys [name cost premium-factor description]}]
  (let [description (not-empty (str/trim (str description)))]
    (cond-> [[:tr
              [:th {:scope "row"} name]
              [:td premium-factor]
              [:td (ui2/money cost currency)]]]
      description
      (conj [:tr {:data-description true}
             [:td {:colspan 3}
              [:small description]]]))))

(defn- coverage-section [{:keys [tr]} coverage policy]
  (let [currency (:insurance.policy/currency policy)]
    (ui2/section-card
     {:class    "wa-stack insurance-coverage-section"
      :divider? true
      :title    (tr [:insurance/instrument-coverage])
      :subtitle (tr [:insurance/coverage-for] [(:insurance.policy/name policy)])}
     [:dl {:class "particulars"}
      (ui2/detail-item (tr [:insurance/item-count]) (or (:instrument.coverage/item-count coverage) 1))
      (ui2/detail-item (tr [:insurance/value]) (ui2/money (:instrument.coverage/value coverage) currency))
      (ui2/detail-item (tr [:band-private]) (kind-badge tr (:instrument.coverage/private? coverage)))
      (ui2/detail-item (tr [:instrument.coverage/insurer-id]) (:instrument.coverage/insurer-id coverage))]
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
          [:td (ui2/money (:instrument.coverage/cost coverage) currency)]]]])])))

(defn- normalize-single-change [field-change]
  (let [[_k v action :as f] (first field-change)]
    (if (= action :added)
      (assoc f 1 {:before nil :after v :action action})
      (assoc f 1 {:before v :after nil :action action}))))

(defn- normalize-change-pair [field-change]
  (let [actions (set (map #(nth % 2) field-change))]
    (if (= #{:retracted :added} actions)
      (let [added     (m/find-first #(= :added (nth % 2)) field-change)
            retracted (m/find-first #(= :retracted (nth % 2)) field-change)]
        [(-> (first field-change)
             (assoc 2 :updated)
             (assoc 1 {:before (second retracted)
                       :after  (second added)}))])
      (map normalize-single-change (map vector field-change)))))

(defn- normalize-field-change [field-change]
  (case (count field-change)
    1 [(normalize-single-change field-change)]
    2 (normalize-change-pair field-change)
    (map normalize-single-change (map vector field-change))))

(defn- image-value [{:keys [tr] :as req} coverage image]
  (if-let [{:keys [thumbnail full]} (queries/image-uri req (:instrument.coverage/instrument coverage) image)]
    [:a {:href full :target "_blank" :class "insurance-history-image-link"}
     [:img {:class "insurance-history-image"
            :src   thumbnail
            :alt   (tr [:instrument/images])}]]
    (tr [:history/image-deleted])))

(defn- band-or-private [tr private?]
  (kind-badge tr private?))

(defn- change-value [{:keys [tr] :as req} coverage k v]
  (cond
    (= k :instrument/category) (get v :instrument.category/name)
    (= k :instrument.coverage/value) (ui2/money v :EUR)
    (= k :instrument/owner) (member-link v)
    (= k :instrument.coverage/instrument) (:instrument/name v)
    (= k :instrument.coverage/types) (:insurance.coverage.type/name v)
    (= k :instrument.coverage/private?) (band-or-private tr v)
    (= k :instrument/images) (image-value req coverage v)
    (keyword? v) (tr [v])
    :else (str v)))

(defn- history-changes [req coverage changes]
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

(defn- history-icon [action]
  (icon (history-action-data action)))

(defn- history-head [{:keys [tr]}]
  [:div {:class "insurance-history-head"}
   [:div (tr [:history/editor])]
   [:div (tr [:history/field])]
   [:div (tr [:history/before])]
   [:div (tr [:history/after])]])

(defn- history-action-cell [tr audit-user-name action]
  [:div {:class "insurance-history-editor"}
   (history-icon action)
   [:div {:class "insurance-history-editor-text"}
    [:span (ui2/muted audit-user-name)]
    [:span {:class "wa-caption-s"}
     (tr [(keyword "history" (name action))])]]])

(defn- history-change-row [{:keys [tr]} audit-user-name {:keys [action after before field-label]}]
  [:div {:class "insurance-history-row"}
   (history-action-cell tr audit-user-name action)
   [:div {:class "insurance-history-field"} field-label]
   [:div {:class "insurance-history-before"} (ui2/muted before)]
   [:div {:class "insurance-history-after"} (ui2/muted after)]])

(defn- history-date-row [req timestamp]
  (let [label (ui2/format-date-time req :medium timestamp)]
    [:div {:class "insurance-history-date-row"}
     [:time {:class      "insurance-history-time"
             :datetime   (str timestamp)
             :title      label
             :aria-label label}
      (ui2/relative-time-value timestamp)]]))

(defn- history-entry [req coverage {:keys [audit changes timestamp]}]
  (let [change-rows     (history-changes req coverage changes)
        audit-user-name (get-in audit [:audit/member :member/name])]
    (when (seq change-rows)
      (list
       (history-date-row req timestamp)
       (for [change-row change-rows]
         (history-change-row req audit-user-name change-row))))))

(defn- history-section [req coverage]
  (let [history (queries/coverage-history (:db req) coverage)]
    (ui2/section-card
     {:class    "wa-stack insurance-coverage-section insurance-history"
      :divider? true
      :title    ((:tr req) [:history/title])
      :subtitle ((:tr req) [:history/subtitle-coverage])}
     (if (seq history)
       [:div {:class "insurance-history-table"}
        (history-head req)
        (keep #(history-entry req coverage %) history)]
       (ui2/empty-state ((:tr req) [:history/title]) ((:tr req) [:history/no-changes]))))))

(defn page [{:keys [db] :as req}]
  (let [coverage   (queries/coverage db (coverage-id req))
        policy     (:insurance.policy/_covered-instruments coverage)
        instrument (:instrument.coverage/instrument coverage)]
    (ui2/plain-page
     [:div {:class "insurance-coverage-detail-page wa-stack wa-gap-xl"}
      (breadcrumb req policy instrument)
      (ui2/page-header {:class    "insurance-coverage-page-header"
                        :title    (:instrument/name instrument)
                        :subtitle (:insurance.policy/name policy)
                        :actions  (page-actions req coverage policy)})
      (instrument-section req instrument)
      (coverage-section req coverage policy)
      (history-section req coverage)])))

(d*/refresh-all!)
