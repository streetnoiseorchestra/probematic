(ns app.members.index.queries
  (:require
   [app.datomic :as d]
   [app.members.queries :as members.q]
   [app.queries :as q]
   [clojure.string :as str]))

(def default-page-state
  {:search                    ""
   :filter-preset             "active"
   :sort-field                "name"
   :sort-order                "asc"
   :last-invitation-action-at nil})

(def valid-filter-presets
  #{"all" "active" "inactive"})

(defn travel-discount-sort-value [{:member/keys [travel-discounts]}]
  (->> travel-discounts
       (map (comp :travel.discount.type/discount-type-name :travel.discount/discount-type))
       (remove str/blank?)
       sort
       (str/join ", ")
       str/lower-case))

(def sort-field->fn
  {"name"            :member/name
   "travel-discount" travel-discount-sort-value
   "email"           :member/email
   "phone"           :member/phone
   "section"         (comp :section/name :member/section)
   "active"          :member/active?})

(defn normalize-page-state [page-state]
  (-> default-page-state
      (merge page-state)
      (update :search #(or % ""))
      (update :filter-preset #(if (valid-filter-presets %)
                                %
                                (:filter-preset default-page-state)))
      (update :sort-field #(if (contains? sort-field->fn %)
                             %
                             (:sort-field default-page-state)))
      (update :sort-order #(if (= % "desc") % "asc"))))

(defn filter-spec [page-state]
  (let [{:keys [search filter-preset]} (normalize-page-state page-state)
        search                         (some-> search str/trim not-empty)]
    {:fields []
     :preset filter-preset
     :search search}))

(defn sort-spec [page-state]
  (let [{:keys [sort-field sort-order]} (normalize-page-state page-state)]
    [{:field (get sort-field->fn sort-field (get sort-field->fn (:sort-field default-page-state)))
      :order (if (= sort-order "desc") :desc :asc)}]))

(defn members [db page-state]
  (let [sorting   (or (sort-spec page-state) [{:field :member/name :order :asc}])
        filtering (or (filter-spec page-state) {:fields [] :preset "active" :search nil})]
    (->> (d/find-all db :member/member-id q/member-detail-pattern)
         (mapv first)
         (members.q/filter-by-spec filtering)
         (members.q/sort-by-spec sorting))))

(defn open-invitations [req]
  (members.q/members-with-open-invites req))
