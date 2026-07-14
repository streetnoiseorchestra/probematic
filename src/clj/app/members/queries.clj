(ns app.members.queries
  (:require
   [app.datomic :as d]
   [app.queries :as q]
   [clojure.string :as str]
   [taoensso.carmine :as redis]))

(defn sections [db]
  (->> (d/find-all db :section/name [:section/name])
       (mapv first)
       (sort-by :section/name)))

(defn sort-by-spec [sorting coll]
  #_(tap> {:sorting sorting
           :fields  (mapv :field sorting)
           :dir     (if (= :asc (-> sorting first :order)) :asc :desc)})
  (let [asc? (= :asc (-> sorting first :order))

        r (sort-by (fn [v]
                     (mapv (fn [s]
                             (if (string? s)
                               (str/lower-case s)
                               s))
                           ((apply juxt (map :field sorting)) v))) coll)]
    (if asc? r (reverse r))))

(def filter-preset-pred
  {"all"      (constantly true)
   "active"   #(-> % :member/active?)
   "inactive" #(not (-> % :member/active?))})

(defn search-member [q member]
  (->> (select-keys member [:member/username :member/name :member/email :member/nick :member/phone])
       (map str/lower-case)
       (some #(str/includes? % q))))

(defn search-members [in coll]
  (filter (partial search-member  (str/lower-case in)) coll))

(defn filter-by-spec [{:keys [_fields preset search]} coll]
  ;; fields NYI
  (let [preset-pred (get filter-preset-pred preset)]
    (cond->> coll
      preset-pred (filter preset-pred)
      search      (search-members search))))

(defn members-with-open-invites
  "Return the members with open invites"
  [req]
  (->> (redis/wcar (-> req :system :redis) (redis/keys "invite:*"))
       (map (fn [k]
              {:key k
               :member-id (redis/wcar (-> req :system :redis) (redis/get k))}))
       (map (fn [{:keys [member-id key]}]
              (assoc (q/retrieve-member (:db req) member-id)
                     :member/invite-code
                     (second (str/split key #":")))))))

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
         (filter-by-spec filtering)
         (sort-by-spec sorting))))
