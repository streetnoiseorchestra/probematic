(ns app.gigs.edit.queries
  (:require
   [app.form :as form]
   [app.gigs.domain :as domain]
   [clojure.string :as str]))

(defn title-equals-gig-type? [tr title gig-type]
  (when-let [label-key (domain/gig-type-label-key gig-type)]
    (= (str/lower-case title)
       (str/lower-case (tr [label-key])))))

(defn gig->form
  [{:gig/keys [call-time contact date description end-date end-time gig-id gig-type leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time status title]
    :forum.topic/keys [topic-id]}]
  {:gig-id            (str gig-id)
   :title             (form/text-value title)
   :status            (some-> status name)
   :gig-type          (some-> gig-type name)
   :date              (form/date-value date)
   :end-date          (form/date-value end-date)
   :contact           (form/text-value (some-> contact :member/member-id str))
   :call-time         (form/time-value call-time)
   :set-time          (form/time-value set-time)
   :end-time          (form/time-value end-time)
   :location          (form/text-value location)
   :outfit            (form/text-value outfit)
   :pay-deal          (form/text-value pay-deal)
   :leader            (form/text-value leader)
   :rehearsal-leader1 (form/text-value (some-> rehearsal-leader1 :member/member-id str))
   :rehearsal-leader2 (form/text-value (some-> rehearsal-leader2 :member/member-id str))
   :post-gig-plans    (form/text-value post-gig-plans)
   :more-details      (form/text-value more-details)
   :description       (form/text-value description)
   :notify?           false
   :takeover-topic?   false
   :topic-id          (form/text-value topic-id)
   :_error            {}})

(defn create-form [tr]
  {:gig-id            ""
   :title             ""
   :status            "unconfirmed"
   :gig-type          ""
   :date              ""
   :end-date          ""
   :contact           ""
   :call-time         ""
   :set-time          ""
   :end-time          ""
   :location          ""
   :outfit            (tr [:gigs/default-outfit])
   :pay-deal          ""
   :leader            ""
   :rehearsal-leader1 ""
   :rehearsal-leader2 ""
   :post-gig-plans    ""
   :more-details      ""
   :description       ""
   :notify?           false
   :thread?           true
   :topic-id          ""
   :_error            {}})
