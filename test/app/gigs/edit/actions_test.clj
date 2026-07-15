(ns app.gigs.edit.actions-test
  (:require
   [app.gigs.domain :as domain]
   [app.gigs.edit.actions :as actions]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr [[k] & [args]]
  (case k
    :error/is-required (str (first args) " is required.")
    :gig/title "Title"
    :gig/date "Date"
    :gig/location "Location"
    :gig/gig-type "Type"
    :gig/status "Status"
    :gig/call-time "Call Time"
    :error/gig-end-date-before-date "End date must be on or after date."
    :error/gig-set-time-before-call-time "Set time must be at or after call time."
    :error/gig-end-time-before-set-time "End time must be at or after set time."
    :error/gig-end-time-before-call-time "End time must be at or after call time."
    :error/gig-rehearsal-leaders-same "Probeleitung 2 must be different from Probeleitung 1."
    :error/gig-edit-not-allowed "You are not allowed to edit this gig."
    :error/gig-edit-not-found "Gig not found."
    :error/form-has-errors "Please fix the errors in the form."
    (name k)))

(defn valid-signals [gig-id]
  {:gig-id       (str gig-id)
   :title        "Street Gig"
   :date         "2026-05-01"
   :location     "Somewhere"
   :gig-type     "gig"
   :status       "confirmed"
   :call-time    "18:00"
   :set-time     "19:00"
   :end-time     "20:00"
   :notify?      "true"
   :more-details "Details"
   :tab-id       "ignored"})

(defn seed-gig! [conn gig-id date]
  @(d/transact conn [(domain/gig->db {:gig/gig-id   gig-id
                                      :gig/title    "Existing Gig"
                                      :gig/status   :gig.status/confirmed
                                      :gig/gig-type :gig.type/gig
                                      :gig/date     date
                                      :gig/location "Somewhere"
                                      :gig/call-time (t/time "18:00")})]))

(defn action-state [conn]
  {:tr tr
   :db (d/db conn)
   :current-user-roles #{:admin}})

(deftest update-gig-action-test
  (testing "returns a Datomic transaction effect and redirects to the gig detail page"
    (let [{:keys [conn]} (tc/new-system "gig-edit-update-action")
          gig-id         (random-uuid)]
      (seed-gig! conn gig-id (t/date "2026-05-01"))
      (is (= [[:db/transact
               [{:gig/gig-id             gig-id
                 :gig/title              "Street Gig"
                 :gig/date               (-> (t/date "2026-05-01") (t/at (t/midnight)) (t/in "UTC") t/inst)
                 :gig/location           "Somewhere"
                 :gig/gig-type           :gig.type/gig
                 :gig/status             :gig.status/confirmed
                 :gig/call-time          "18:00"
                 :gig/set-time           "19:00"
                 :gig/end-time           "20:00"
                 :gig/more-details       "Details"
                 :gig/end-date           nil
                 :gig/contact            nil
                 :gig/leader             nil
                 :gig/rehearsal-leader1  nil
                 :gig/rehearsal-leader2  nil
                 :gig/pay-deal           nil
                 :gig/outfit             nil
                 :gig/setlist            nil
                 :gig/description        nil
                 :gig/post-gig-plans     nil
                 :forum.topic/topic-id   nil}]
               {:transact-w-nils? true
                :on-success       [[:app.gigs/trigger-gig-details-edited gig-id true false]]}]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/redirect (urls/link-gig gig-id)]]]]
             (actions/update-gig-action
              (action-state conn)
              (valid-signals gig-id))))))

  (testing "encodes member select fields as Datomic lookup refs"
    (let [gig-id     (random-uuid)
          contact-id (random-uuid)
          leader1-id (random-uuid)
          leader2-id (random-uuid)]
      (is (= {:gig/contact           [:member/member-id contact-id]
              :gig/rehearsal-leader1 [:member/member-id leader1-id]
              :gig/rehearsal-leader2 [:member/member-id leader2-id]}
             (-> (valid-signals gig-id)
                 (assoc :contact (str contact-id)
                        :rehearsal-leader1 (str leader1-id)
                        :rehearsal-leader2 (str leader2-id))
                 actions/update-gig-tx-data
                 first
                 (select-keys [:gig/contact
                               :gig/rehearsal-leader1
                               :gig/rehearsal-leader2]))))))

  (testing "returns a top error when the current user cannot edit an archived gig"
    (let [{:keys [conn]} (tc/new-system "gig-edit-update-archived-action")
          gig-id         (random-uuid)]
      (seed-gig! conn gig-id (t/date "2020-01-01"))
      (is (= [[:app.datastar/respond-sse [[:app.datastar.sse/merge-signals {:loading false :targetid false}]]]
              [:app.datastar/assoc-state
               [:gig-edit]
               {:gig-id       (str gig-id)
                :title        "Street Gig"
                :date         "2026-05-01"
                :location     "Somewhere"
                :gig-type     "gig"
                :status       "confirmed"
                :call-time    "18:00"
                :set-time     "19:00"
                :end-time     "20:00"
                :notify?      true
                :more-details "Details"
                :_error       {:_top {:error "You are not allowed to edit this gig."}}}]]
             (actions/update-gig-action
              {:tr tr
               :db (d/db conn)
               :current-user-roles #{}}
              (valid-signals gig-id))))))

  (testing "returns field errors for invalid date, time, and probe leader order"
    (let [{:keys [conn]} (tc/new-system "gig-edit-update-invalid-action")
          gig-id         (random-uuid)]
      (seed-gig! conn gig-id (t/date "2026-05-01"))
      (is (= [[:app.datastar/respond-sse [[:app.datastar.sse/merge-signals {:loading false :targetid false}]]]
              [:app.datastar/assoc-state
               [:gig-edit]
               {:gig-id            (str gig-id)
                :title             "Street Gig"
                :date              "2026-05-02"
                :end-date          "2026-05-01"
                :location          "Somewhere"
                :gig-type          "gig"
                :status            "confirmed"
                :call-time         "18:00"
                :set-time          "17:00"
                :end-time          "16:00"
                :rehearsal-leader1 "00000000-0000-0000-0000-000000000001"
                :rehearsal-leader2 "00000000-0000-0000-0000-000000000001"
                :notify?           false
                :more-details      ""
                :_error            {:_top             {:error "Please fix the errors in the form."}
                                    :end-date          {:error "End date must be on or after date."}
                                    :set-time          {:error "Set time must be at or after call time."}
                                    :end-time          {:error "End time must be at or after set time."}
                                    :rehearsal-leader2 {:error "Probeleitung 2 must be different from Probeleitung 1."}}}]]
             (actions/update-gig-action
              (action-state conn)
              (merge (valid-signals gig-id)
                     {:date              "2026-05-02"
                      :end-date          "2026-05-01"
                      :set-time          "17:00"
                      :end-time          "16:00"
                      :rehearsal-leader1 "00000000-0000-0000-0000-000000000001"
                      :rehearsal-leader2 "00000000-0000-0000-0000-000000000001"
                      :notify?           nil
                      :more-details      nil
                      :tab-id            "ignored"}))))))

  (testing "checks end time against call time when set time is absent"
    (let [{:keys [conn]} (tc/new-system "gig-edit-update-end-time-action")
          gig-id         (random-uuid)]
      (seed-gig! conn gig-id (t/date "2026-05-01"))
      (is (= {:_top     {:error "Please fix the errors in the form."}
              :end-time {:error "End time must be at or after call time."}}
             (-> (actions/update-gig-action
                  (action-state conn)
                  (merge (valid-signals gig-id)
                         {:set-time ""
                          :end-time "17:00"}))
                 second
                 last
                 :_error)))))

  (testing "validates one field for blur and keydown"
    (is (= [[:app.datastar/merge-state
             [:gig-edit]
             {:title          "Street Gig"
              :date           "2026-05-01"
              :location       "Somewhere"
              :gig-type       "gig"
              :status         "confirmed"
              :call-time      "18:00"
              :set-time       "17:00"}]
            [:app.datastar/assoc-state
             [:gig-edit :_error :set-time]
             {:error "Set time must be at or after call time."}]]
           (actions/validate-gig-field-action
            {:tr tr}
            {:gig-edit {:title          "Street Gig"
                        :date           "2026-05-01"
                        :location       "Somewhere"
                        :gig-type       "gig"
                        :status         "confirmed"
                        :call-time      "18:00"
                        :set-time       "17:00"
                        :validate-field "set-time"}})))))

(deftest create-gig-action-test
  (testing "returns a Datomic transaction effect, creation side effect, and redirect"
    (let [[transact redirect :as effects] (actions/create-gig-action
                                           {:tr tr}
                                           (assoc (valid-signals "00000000-0000-0000-0000-000000000000")
                                                  :thread? true))
          [_ [tx] opts] transact
          gig-id (:gig/gig-id tx)]
      (is (= :db/transact (first transact)))
      (is (uuid? gig-id))
      (is (= "Street Gig" (:gig/title tx)))
      (is (= :gig.type/gig (:gig/gig-type tx)))
      (is (= :gig.status/confirmed (:gig/status tx)))
      (is (= {:on-success [[:app.gigs/trigger-gig-created gig-id true true]]}
             opts))
      (is (= [:app.datastar/respond-sse
              [[:app.datastar.sse/redirect (urls/link-gig gig-id)]]]
             redirect))
      (is (= 2 (count effects)))))

  (testing "returns validation errors"
    (is (= [[:app.datastar/respond-sse [[:app.datastar.sse/merge-signals {:loading false :targetid false}]]]
            [:app.datastar/assoc-state
             [:gig-edit]
             {:gig-id    "00000000-0000-0000-0000-000000000000"
              :title     ""
              :date      ""
              :location  ""
              :gig-type  ""
              :status    ""
              :call-time ""
              :_error    {:_top     {:error "Please fix the errors in the form."}
                          :title    {:error "Title is required."}
                          :date     {:error "Date is required."}
                          :location {:error "Location is required."}
                          :gig-type {:error "Type is required."}
                          :status   {:error "Status is required."}
                          :call-time {:error "Call Time is required."}}}]]
           (actions/create-gig-action
            {:tr tr}
            {:gig-id    "00000000-0000-0000-0000-000000000000"
             :title     ""
             :date      ""
             :location  ""
             :gig-type  ""
             :status    ""
             :call-time ""})))))

(deftest delete-gig-action-test
  (testing "returns a Datomic retract transaction effect and redirects to the gigs list"
    (let [{:keys [conn]} (tc/new-system "gig-edit-delete-action")
          gig-id         (random-uuid)]
      @(d/transact conn [(domain/gig->db {:gig/gig-id    gig-id
                                          :gig/title     "Delete Me"
                                          :gig/status    :gig.status/confirmed
                                          :gig/gig-type  :gig.type/gig
                                          :gig/date      (t/date "2026-05-01")
                                          :gig/location  "Somewhere"
                                          :gig/call-time (t/time "18:00")})])
      (is (= [[:db/transact
               [[:db/retractEntity [:setlist/gig [:gig/gig-id gig-id]]]
                [:db/retractEntity [:probeplan/gig [:gig/gig-id gig-id]]]
                [:db/retractEntity [:gig/gig-id gig-id]]]
               {:on-success [[:app.gigs/trigger-gig-deleted gig-id false]]}]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/redirect (urls/link-gigs-home)]]]]
             (actions/delete-gig-action
              (action-state conn)
              {:gig-id (str gig-id)
               :tab-id "ignored"})))))

  (testing "returns a top error when the current user cannot delete an archived gig"
    (let [{:keys [conn]} (tc/new-system "gig-edit-delete-archived-action")
          gig-id         (random-uuid)]
      (seed-gig! conn gig-id (t/date "2020-01-01"))
      (is (= [[:app.datastar/respond-sse [[:app.datastar.sse/merge-signals {:loading false :targetid false}]]]
              [:app.datastar/assoc-state
               [:gig-edit :_error :_top]
               {:error "You are not allowed to edit this gig."}]]
             (actions/delete-gig-action
              {:tr tr
               :db (d/db conn)
               :current-user-roles #{}}
              {:gig-id (str gig-id)
               :tab-id "ignored"}))))))
