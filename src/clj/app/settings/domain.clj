(ns app.settings.domain
  (:require
   [app.schemas :as s]
   [tick.core :as t]))

(def team-types #{:team.type/insurance})
(def str->team-type (zipmap (map name team-types) team-types))

(def TravelDiscountTypeEntity
  [:map {:name :app.entity/travel.discount.type}
   [:travel.discount.type/discount-type-id :uuid]
   [:travel.discount.type/discount-type-name ::s/non-blank-string]
   [:travel.discount.type/enabled? :boolean]])

(def TravelDiscountEntity
  [:map {:name :app.entity/travel.discount}
   [:travel.discount/discount-id :uuid]
   [:travel.discount/discount-type ::s/datomic-ref]
   [:travel.discount/expiry-date ::s/instdate]])

(defn discount-type->db [travel-discount-type]
  (when-not (s/valid? TravelDiscountTypeEntity travel-discount-type)
    (throw
     (ex-info "Travel Discount Type not valid" {:travel.discount.type travel-discount-type
                                                :schema TravelDiscountTypeEntity
                                                :error (s/explain TravelDiscountTypeEntity travel-discount-type)
                                                :human (s/explain-human TravelDiscountTypeEntity travel-discount-type)})))
  (s/encode-datomic TravelDiscountTypeEntity travel-discount-type))

(defn db->discount-type [ent]
  (s/decode-datomic TravelDiscountTypeEntity ent))

(defn discount->db [travel-discount]
  (when-not (s/valid? TravelDiscountEntity travel-discount)
    (throw
     (ex-info "Travel Discount  not valid" {:travel.discount.type travel-discount
                                            :schema TravelDiscountEntity
                                            :error (s/explain TravelDiscountEntity travel-discount)
                                            :human (s/explain-human TravelDiscountEntity travel-discount)})))
  (s/encode-datomic TravelDiscountEntity travel-discount))

(defn db->discount [ent]
  (s/decode-datomic TravelDiscountEntity ent))

(defn expired? [{:travel.discount/keys [expiry-date]}]
  (t/> (t/date expiry-date) (t/date)))

(defn db->team [ent]
  ent)
