(ns app.members.create.commands
  (:require [app.datastar :as d*]
            [app.datomic :as d]
            [app.ledger.domain :as ledger.domain]
            [app.members.controller2 :as controller]
            [app.members.domain :as domain]
            [app.schemas :as s]
            [app.urls :as url]
            [com.yetanalytics.squuid :as sq]
            [medley.core :as medley]))

(defn- validate-new-member [req m]
  (let [unique-schema  (controller/gen-unique-constraints req)
        unique-user?   (->> m (s/explain unique-schema) s/humanize)
        valid-request? (->> m (s/explain (domain/NewMemberForm (:tr req))) s/humanize)]

    ;; if there are multiple errors per-field (because of :and) then we take the first one
    (medley/map-vals first
                     (merge valid-request? unique-user?))))

(defn- new-member-txs [member-id {:keys [phone email active
                                         name nick username section-name]}]
  (let [member-tmpid (d/tempid)]
    (concat [{:member/name      name
              :member/nick      nick
              :member/member-id member-id
              :member/phone     (domain/clean-phone-number phone)
              :member/username  username
              :member/active?   (true? active)
              :member/section   [:section/name section-name]
              :member/email     (domain/clean-email email)
              :db/id            member-tmpid}]
            (ledger.domain/txs-new-member-ledger (d/tempid) (sq/generate-squuid) member-tmpid))))

(defn- -validate-create-member! [req]
  (let [r      (validate-new-member req (-> req :parameters :body :member))
        errors (apply dissoc r (d*/untouched-fields req :member))]
    (tap> [:errors errors])
    (when errors
      errors)))

(defn- -create-member! [{:keys [tr] :as req}]
  (let [params (-> req :parameters :body :member)
        errors (validate-new-member req (-> req :parameters :body :member))
        valid? (empty? errors)]
    (if valid?
      (try
        (let [member-id  (sq/generate-squuid)
              txs        (new-member-txs member-id params)
              new-member (:member (controller/transact-member! req member-id txs))]

          (when (:create-sno-id params)
            (controller/send-user-invitation! req new-member))

          [new-member nil])

        (catch Exception e
          (cond
            (= :db.error/unique-conflict (-> e .getCause ex-data :db/error))
            [nil (controller/unique-conflict-error req e)]

            (= :invalid-phone (-> e .getCause ex-data :type))
            [nil {:phone (tr [:error/phone-number-invalid])}]

            :else [nil (d*/unhandled-form-error req e)])))
      [nil (-validate-create-member! req)])))

;; --------------------------------------------------------------------------------------------
;; Command

(defn create-member [req]
  (if  (-> req :parameters :body :member :validate-only)
    (if-let [error (-validate-create-member! req)]
      (d*/form-errors req :member error {:only :touched})
      (d*/clear-form-errors req :member))
    (let [[member error] (-create-member! req)]
      (if error
        (d*/form-errors req :member error)
        (d*/redirect req (url/link-member member))))))
