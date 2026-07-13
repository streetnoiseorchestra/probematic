(ns app.insurance.survey.views-test
  (:require
   [app.insurance.survey.actions :as actions]
   [app.insurance.survey.queries :as queries]
   [app.insurance.survey.views :as sut]
   [app.insurance.test-support :as insurance-test]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.page-shell-test-support :as page-shell]
   [app.ui2.page-surface :as page-surface]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (name (last path)))
  ([path _vars]
   (tr path)))

(defn fixture [survey-opts]
  (let [{:keys [conn member-id]} (tc/new-system "insurance-survey-views")
        {:keys [coverage-id policy-id] :as ids}
        (insurance-test/seed-page-shell-fixture! conn member-id)
        survey-ids (insurance-test/seed-member-survey!
                    conn
                    (merge {:coverage-ids [coverage-id]
                            :member-id    member-id
                            :policy-id    policy-id}
                           survey-opts))]
    (merge ids survey-ids
           {:conn conn
            :member-id member-id
            :request {:current-locale "en"
                      :db             (d/db conn)
                      :path-params    {:policy-id policy-id}
                      :policy         (q/retrieve-policy (d/db conn) policy-id)
                      :session        {:session/member {:member/member-id member-id}}
                      :system         {:env {:app-base-url "https://example.test"}}
                      :tr             tr
                      ::r/router      router}})))

(deftest survey-page-renders-each-preserved-state
  (testing "an incomplete response starts with the first question and instrument"
    (let [{:keys [request]} (fixture {})
          surface (l/select-one page-surface/PageSurface (sut/page request))
          question (some #(when (= "insurance-survey-question" (:id (l/attrs %))) %)
                         (l/select card/Card surface))
          instrument (some #(when (= "insurance-survey-instrument" (:id (l/attrs %))) %)
                           (l/select card/Card surface))]
      (is (= :insurance/review-used-at-gig
             (-> (l/select-one :h2 question) page-shell/translation-key)))
      (is (= "Test Trumpet"
             (-> (l/select-one :h2 instrument) l/text)))))

  (testing "a response with no reports offers add coverage and dismissal"
    (let [{:keys [request]} (fixture {:coverage-ids []})
          view (->> request sut/page (l/select-one page-surface/PageSurface))
          empty-state (some #(when (= "insurance-survey-empty" (:id (l/attrs %))) %)
                            (l/select :div view))]
      (is (= :insurance/review-no-items-title
             (-> (l/select-one :strong empty-state)
                 page-shell/translation-key)))
      (is (some? (l/select-one "[data-action]" view)))))

  (testing "a closed survey explains that the review can no longer be changed"
    (let [{:keys [request]} (fixture {:closed-at #inst "2026-03-15T00:00:00.000-00:00"})
          surface (l/select-one page-surface/PageSurface (sut/page request))
          closed (some #(when (= "insurance-survey-closed" (:id (l/attrs %))) %)
                       (l/select :div surface))]
      (is (= :insurance/review-closed-title
             (-> (l/select-one :strong closed)
                 page-shell/translation-key)))))

  (testing "a completed response renders the completion state"
    (let [{:keys [request]} (fixture {:response-completed-at
                                      #inst "2026-03-15T00:00:00.000-00:00"})
          surface (l/select-one page-surface/PageSurface (sut/page request))
          complete (some #(when (= "insurance-survey-complete" (:id (l/attrs %))) %)
                         (l/select :div surface))]
      (is (= :insurance/review-complete-title
             (-> (l/select-one :strong complete)
                 page-shell/translation-key)))))

  (testing "the server-owned encouragement state offers a continue action"
    (let [{:keys [member-id policy-id request]} (fixture {})
          report-id (:insurance.survey.report/report-id
                     (:active-report
                      (queries/survey-data (:db request) policy-id member-id)))
          view (->> (assoc request :page-state
                           {actions/form-key {:mode      :encouragement
                                              :report-id report-id}})
                    sut/page
                    (l/select-one page-surface/PageSurface))
          encouragement (some #(when (= "insurance-survey-encouragement"
                                        (:id (l/attrs %))) %)
                              (l/select :div view))
          continue (some #(when (= "insurance-survey-continue" (:id (l/attrs %))) %)
                         (l/select button/Button view))]
      (is (= :insurance/review-good-job
             (-> (l/select-one :strong encouragement)
                 page-shell/translation-key)))
      (is (some? continue))))

  (testing "the data correction step renders a native edit form"
    (let [{:keys [member-id policy-id request]} (fixture {})
          report-id (:insurance.survey.report/report-id
                     (:active-report
                      (queries/survey-data (:db request) policy-id member-id)))
          view (->> (assoc request :page-state
                           {actions/form-key {:current-flow-key :data-edit
                                              :decisions        []
                                              :mode             :edit
                                              :report-id        report-id}})
                    sut/page
                    (l/select-one page-surface/PageSurface))
          edit-form (some #(when (= "insurance-survey-edit-form" (:id (l/attrs %))) %)
                          (l/select :form view))]
      (is (some? edit-form))
      (is (some #(when (= "instrument-name" (:name (l/attrs %))) %)
                (l/select :input edit-form)))
      (is (nil? (l/select-one "wa-input" view))))))

(deftest unavailable-survey-cost-remains-renderable
  (let [{:keys [conn member-id policy-id request]} (fixture {})
        policy         (q/retrieve-policy (d/db conn) policy-id)
        category-factor-id
        (-> policy
            :insurance.policy/category-factors
            first
            :insurance.category.factor/category-factor-id)
        _              @(d/transact
                         conn
                         [[:db/retract
                           [:insurance.policy/policy-id policy-id]
                           :insurance.policy/category-factors
                           [:insurance.category.factor/category-factor-id
                            category-factor-id]]])
        db             (d/db conn)
        data           (queries/survey-data db policy-id member-id)
        report-id      (get-in data
                               [:active-report
                                :insurance.survey.report/report-id])
        view           (sut/page
                        (assoc request
                               :db db
                               :policy (q/retrieve-policy db policy-id)
                               :page-state
                               {actions/form-key
                                {:current-flow-key :confirm-go-private
                                 :decisions        [:confirm-not-band]
                                 :mode             :question
                                 :report-id        report-id}}))
        translations   (l/select :i18n/tr view)
        cost-node      (some #(when (= :insurance/review-confirm-private-cost
                                       (l/first-child %))
                                %)
                             translations)]
    (testing "missing category factors show an explicit fallback instead of crashing"
      (is (some #(= :insurance/cost-unavailable (l/first-child %))
                translations))
      (is (= {:cost "—"}
             (l/last-child cost-node))))))
