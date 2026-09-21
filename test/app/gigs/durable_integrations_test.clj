(ns app.gigs.durable-integrations-test
  (:require
   [app.gigs.detail.actions :as attendance]
   [app.gigs.detail.actions-test :as fixtures]
   [app.gigs.log-plays.actions :as plays]
   [app.gigs.probeplan.actions :as probeplan]
   [app.gigs.setlist.actions :as setlist]
   [app.jobs.integrations :as integrations]
   [app.jobs.play-stats :as play-stats]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]))

(use-fixtures :each tc/with-released-test-connections)

(deftest gig-subpage-writes-record-integration-intents-instead-of-transient-callbacks
  (let [{:keys [conn]}             (tc/new-system "gig-subpage-integration-intents")
        {:keys [gig-id member-id]} (fixtures/seed-gig-member! conn)
        song-id                    (random-uuid)
        ids                        {:gig-id (str gig-id) :member-id (str member-id) :song-id (str song-id)}]
    @(d/transact conn [{:song/song-id song-id :song/title "Integration song" :song/active? true}])
    (let [state (assoc (fixtures/state conn) :durable-jobs? true :env {:ig/system {:app.ig/profile :prod}})]
      (doseq [[action signals stats?]
              [[attendance/update-attendance-plan-action {:gig-attendance (assoc ids :plan "definitely")} false]
               [attendance/update-attendance-motivation-action {:gig-attendance (assoc ids :motivation "high")} false]
               [attendance/update-attendance-comment-action {:gig-attendance (assoc ids :comment "Hello")} false]
               [attendance/switch-attendance-comment-action
                {:gig-attendance {:comment     "Hello"      :comment-gig-id (str gig-id)    :comment-member-id (str member-id)
                                  :next-gig-id (str gig-id) :next-member-id (str member-id)}} false]
               [plays/update-rating-action {:gig-log-plays (assoc ids :rating "good")} true]
               [plays/toggle-intensive-action {:gig-log-plays (assoc ids :rating "good")} true]
               [probeplan/toggle-probeplan-song-action {:gig-probeplan (assoc ids :selected true)} false]
               [probeplan/toggle-probeplan-intensive-action {:gig-probeplan ids} false]
               [probeplan/reorder-probeplan-songs-action {:gig-probeplan (assoc ids :order [])} false]
               [setlist/toggle-setlist-song-action {:gig-setlist (assoc ids :selected true)} false]
               [setlist/reorder-setlist-songs-action {:gig-setlist (assoc ids :order [])} false]]]
        (testing (str action)
          (let [[effect tx opts] (first (action state signals))]
            (is (= :db/transact effect))
            (is (seq tx))
            (is (= (cond-> [(integrations/gig-job gig-id {:operation :updated})]
                     stats? (conj play-stats/job))
                   (:jobs opts)))
            (is (= (when (:gig-attendance signals)
                     [[:app.datastar/assoc-state attendance/attendance-error-path nil]])
                   (:on-success opts)))))))))
