(ns app.members.queries
  (:require [app.datomic :as d]
            [app.queries :as q]
            [taoensso.carmine :as redis]
            [clojure.string :as str]))

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

(defn members [db sorting filtering]
  (let [sorting   (or sorting [{:field :member/name :order :asc}])
        filtering (or filtering {:fields [] :preset "active" :search nil})]
    (->> (d/find-all db :member/member-id q/member-pattern)
         (mapv #(first %))
         (filter-by-spec filtering)
         (sort-by-spec sorting))))

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
