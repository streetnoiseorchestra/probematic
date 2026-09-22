(ns app.interactions-test
  (:require [app.datastar :as datastar]
            [app.game-loop :as game]
            [app.gigs.detail.actions :as actions]
            [app.gigs.detail.actions-test :as attendance]
            [app.nexus :as nexus]
            [app.queries :as q]
            [app.test-common :as tc]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [jsonista.core :as json]
            [starfederation.datastar.clojure.api :as sse]))

(def ^:dynamic *test-tabs* nil)

(use-fixtures :each tc/with-released-test-connections
  (fn [f]
    (binding [*test-tabs* (atom [])]
      (try
        (f)
        (finally
          (swap! datastar/!page-state #(apply dissoc % @*test-tabs*)))))))

(deftest supersession-is-per-interaction-and-opt-in
  (let [a      {:key "plan-a" :revision 2 :replace? true}
        ledger {"plan-a" {:revision 2 :outcome :failed}}]
    (is (= :execute (datastar/interaction-decision {} a)))
    (is (= :superseded (datastar/interaction-decision ledger (assoc a :revision 1))))
    (is (= :failed (datastar/interaction-decision ledger a)))
    (is (= :execute (datastar/interaction-decision ledger (assoc a :key "motivation-a" :revision 1))))
    (is (= :execute (datastar/interaction-decision ledger (assoc a :key "plan-b" :revision 1))))
    (is (= :rejected (datastar/interaction-decision ledger (assoc a :revision 1 :replace? false))))
    (is (= :failed (datastar/interaction-decision ledger (assoc a :replace? false))))))

(defn setup []
  (let [{:keys [conn]}             (tc/new-system "interaction-admission")
        {:keys [gig-id member-id]} (attendance/seed-gig-member! conn)
        token                      (random-uuid)
        tab                        (str (random-uuid))
        queued                     (atom [])
        runtime                    {:clients       (atom {tab {:token  token :member-id    member-id :revision 0
                                                               :events []    :interactions (atom {})}})
                                    :stopped?      (atom false)
                                    ::game/submit! (fn [work] (swap! queued conj work) true)}
        config                     (nexus/nexus)
        system                     {:datomic {:conn conn} :nexus config :frame-loop runtime}]
    (swap! *test-tabs* conj tab)
    (swap! datastar/!page-state assoc tab {::datastar/state-token token})
    {:conn   conn   :gig-id  gig-id  :member-id member-id :token  token  :tab tab
     :queued queued :runtime runtime :system    system    :config config}))

(defn admit! [{:keys [system runtime tab token gig-id member-id]} action revision values]
  (let [params  (merge {:gig-id (str gig-id) :member-id (str member-id)} values)
        signals {:gig-attendance params
                 :interaction    {:key      (datastar/interaction-key (actions/interaction-policies action) params)
                                  :revision revision
                                  :conn-id  (str token)}}
        request {:system      system                                          :tr attendance/tr :body-params (assoc signals :tab-id tab)
                 :app/session {:session/member {:member/member-id member-id}}}]
    (nexus/queue-actions! runtime request [[action signals]])))

(defn acknowledgments [{:keys [runtime tab]}]
  (->> (get-in @(:clients runtime) [tab :events])
       (mapcat second)
       (mapcat #(get-in % [1 :_acks]))))

(deftest reversed-arrival-noops-and-validation-all-resolve
  (let [{:keys [conn gig-id member-id queued config system runtime] :as ctx} (setup)]
    (doseq [[revision value] [[2 "definitely-not"] [1 "definitely"] [3 "definitely-not"] [4 "invalid"]]]
      (is (= 204 (:status (admit! ctx ::actions/update-attendance-plan revision {:plan value})))))
    (doseq [work @queued] (nexus/process-queued! config system runtime work))
    (is (= :plan/definitely-not (:attendance/plan (q/attendance-for-gig (d/db conn) gig-id member-id))))
    (is (= [{:revision 2 :outcome "completed"} {:revision 1 :outcome "superseded"}
            {:revision 3 :outcome "completed"} {:revision 4 :outcome "completed"}]
           (mapv #(select-keys % [:revision :outcome]) (acknowledgments ctx))))
    (is (= "Invalid attendance plan."
           (get-in @datastar/!page-state [(:tab ctx) :gig-detail :attendance :_error :error])))
    (is (= 204 (:status (admit! ctx ::actions/update-attendance-plan 5 {:plan "unknown"}))))
    (nexus/process-queued! config system runtime (last @queued))
    (is (nil? (get-in @datastar/!page-state [(:tab ctx) :gig-detail :attendance :_error])))
    (swap! datastar/!page-state dissoc (:tab ctx))))

(deftest replacement-connections-reject-old-requests-and-do-not-receive-old-feedback
  (let [{:keys [runtime tab queued config system conn gig-id member-id] :as ctx} (setup)]
    (is (= 204 (:status (admit! ctx ::actions/update-attendance-plan 1 {:plan "definitely"}))))
    (swap! (:clients runtime) update tab assoc :token (random-uuid) :interactions (atom {}))
    (is (= 409 (:status (admit! ctx ::actions/update-attendance-plan 2 {:plan "unknown"}))))
    (nexus/process-queued! config system runtime (first @queued))
    (is (= :plan/definitely (:attendance/plan (q/attendance-for-gig (d/db conn) gig-id member-id))))
    (is (empty? (acknowledgments ctx)))
    (swap! datastar/!page-state dissoc tab)))

(deftest rejected-admission-does-not-claim-a-revision
  (let [{:keys [runtime tab] :as ctx} (setup)
        stopped                       (assoc ctx :runtime (assoc runtime ::game/submit! (constantly false)))]
    (is (= 503 (:status (admit! stopped ::actions/update-attendance-plan 1 {:plan "definitely"}))))
    (is (= {} @(get-in @(:clients runtime) [tab :interactions])))
    (is (= 400 (:status (admit! ctx ::actions/update-attendance-plan 0 {:plan "definitely"}))))))

(deftest execution-errors-acknowledge-without-global-loading-effects
  (let [{:keys [queued config system runtime] :as ctx} (setup)]
    (admit! ctx ::actions/update-attendance-plan 1 {:plan "definitely"})
    (nexus/process-queued! (assoc-in config [:nexus/actions ::actions/update-attendance-plan]
                                     (fn [_ _] (throw (ex-info "Expected failure" {}))))
                           system runtime (first @queued))
    (nexus/process-queued! config system runtime (first @queued))
    (is (= [{:revision 1 :outcome "failed"} {:revision 1 :outcome "failed"}]
           (mapv #(select-keys % [:revision :outcome]) (acknowledgments ctx))))))

(deftest nonsetter-history-is-explicit-and-scoped-to-the-action-target
  (let [{:keys [queued config system runtime] :as ctx} (setup)
        other-member-id                                (random-uuid)]
    (doseq [[member-id revision] [[(:member-id ctx) 2] [other-member-id 1] [(:member-id ctx) 1]]]
      (is (= 204 (:status (admit! ctx ::actions/open-attendance-comment revision
                                  {:member-id (str member-id) :comment "draft"}))))
      (nexus/process-queued! config system runtime (last @queued)))
    (is (= ["completed" "completed" "rejected"]
           (mapv :outcome (acknowledgments ctx))))
    (is (= (str other-member-id)
           (get-in @datastar/!page-state [(:tab ctx) :gig-detail :attendance :comment-edit :member-id])))))

(deftest repeated-interactions-retain-one-terminal-record-per-key
  (let [{:keys [queued config system runtime tab] :as ctx} (setup)]
    (doseq [revision (range 1 33)]
      (admit! ctx ::actions/open-attendance-comment revision {:comment "draft"})
      (nexus/process-queued! config system runtime (last @queued)))
    (let [ledger @(get-in @(:clients runtime) [tab :interactions])]
      (is (= 1 (count ledger)))
      (is (= [{:revision 32 :outcome :completed}] (vec (vals ledger)))))))

(deftest frame-acknowledgments-follow-html-and-survive-hash-suppression
  (let [writes  (atom [])
        accept? (atom false)
        client  {:token  :connection                                                                 :revision 1
                 :events [[1 [[:app.datastar.sse/merge-signals {:_acks [{:key "a" :revision 1}]}]]]]}
        runtime {:clients (atom {"tab" client})}
        render  (#'datastar/frame-connection-render runtime {} "tab" :connection nil (constantly "<main>Saved</main>"))
        frame   {:clients {"tab" client}}]
    (with-redefs [sse/patch-elements! (fn [_ html _] (swap! writes conj [:html html]) @accept?)
                  sse/patch-signals!  (fn [_ payload _] (swap! writes conj [:signals (json/read-value payload)]) true)]
      (render frame)
      (is (= [[:html "<main>Saved</main>"]] @writes))
      (is (= (:events client) (get-in @(:clients runtime) ["tab" :events])))
      (reset! accept? true)
      (render frame)
      (render frame)
      (is (= [:html :html :signals :signals] (mapv first @writes)))
      (is (empty? (get-in @(:clients runtime) ["tab" :events]))))))
