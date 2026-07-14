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
   [app.ui2.page-header :as page-header]
   [app.ui2.page-shell-test-support :as page-shell]
   [app.ui2.page-surface :as page-surface]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

;; TODO: Make pure view setup convenient and migrate presentation tests to plain
;; data. Use Datomic fixtures only when a test exercises database-backed behavior.

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (name (last path)))
  ([path _vars]
   (tr path)))

(defn fixture [survey-opts]
  (let [report-count             (get survey-opts :report-count 1)
        survey-opts              (dissoc survey-opts :report-count)
        {:keys [conn member-id]} (tc/new-system "insurance-survey-views")
        {:keys [coverage-id policy-id] :as ids}
        (insurance-test/seed-page-shell-fixture! conn member-id)
        survey-ids (insurance-test/seed-member-survey!
                    conn
                    (merge {:coverage-ids (vec (repeat report-count coverage-id))
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
  (testing "an incomplete response opens with progress and a card for every remaining item"
    (let [{:keys [request]} (fixture {:report-count 4})
          view       (sut/page request)
          surface    (l/select-one page-surface/PageSurface view)
          workflow   (l/select-one ".insurance-survey-workflow" surface)
          stage      (l/select-one ".insurance-survey-stage" surface)
          progress   (l/select-one "wa-progress-bar#insurance-survey-progress"
                                   workflow)
          question   (l/select-one "#insurance-survey-question" stage)
          question-form (l/select-one :form question)
          deck       (l/select-one ".insurance-survey-card-deck" stage)
          layers     (l/select ".insurance-survey-card-layer" deck)
          instrument (l/select-one "#insurance-survey-instrument" deck)
          media      (l/select-one ".insurance-survey-card-media" instrument)
          facts      (l/select-one ".insurance-survey-card-facts" instrument)
          layer-transition-names
          (mapv #(get-in (l/attrs %) [:style "view-transition-name"]) layers)]
      (is (= {:width       :compact
              :breadcrumbs [:home :insurance/review-title]
              :mobile      {:label :home
                            :href  "/"}
              :actions     []
              :overflow    []}
             (page-shell/page-contract view)))
      (is (nil? (l/select-one page-header/PageHeader surface)))
      (is (= "none" (:data-transition-kind (l/attrs workflow))))
      (is (= {:data-next-value 25.0
              :id              "insurance-survey-progress"
              :label           [:i18n/tr :insurance/review-progress]
              :style           {"--insurance-survey-progress-value" "6.25%"}
              :value           6.25}
             (select-keys (l/attrs progress)
                          [:data-next-value :id :label :style :value])))
      (is (empty? (l/children progress)))
      (is (nil? (l/select-one ".sno-step-circles" workflow)))
      (is (= :insurance/review-used-at-gig
             (-> (l/select-one :h2 question) page-shell/translation-key)))
      (is (str/includes? (:data-on:submit (l/attrs question-form))
                         "InsuranceSurveyMotion.submit"))
      (is (= "Test Trumpet"
             (-> (l/select-one :h2 instrument) l/text)))
      (is (= "media" (:slot (l/attrs media))))
      (is (some? (l/select-one ".insurance-survey-card-image-fallback" media)))
      (is (= [:instrument/make :insurance/value :insurance/item-count
              :insurance/coverage-types]
             (mapv (comp page-shell/translation-key l/first-child)
                   (l/children facts))))
      (is (nil? (l/select-one card/Card question)))
      (is (= {"--deck-count" 3}
             (:style (l/attrs deck))))
      (is (= 3 (count layers)))
      (is (every? true? (map #(-> % l/attrs :aria-hidden) layers)))
      (is (every? true? (map #(-> % l/attrs :inert) layers)))
      (is (= 4 (count (l/select card/Card deck))))
      (is (every? #(some? (l/select-one card/Card %)) layers))
      (is (= 3 (count (distinct layer-transition-names))))
      (is (every? #(str/starts-with? % "insurance-survey-layer-")
                  layer-transition-names))
      (is (some? (get-in (l/attrs instrument)
                         [:style "--insurance-survey-category-tint"])))
      (is (every? #(some? (get-in (l/attrs %)
                                  [:style "--insurance-survey-category-tint"]))
                  layers))
      (is (= ["insurance-survey-card-deck" "insurance-survey-question"]
             (->> (l/children stage)
                  (keep (comp :id l/attrs))
                  vec)))))

  (testing "completed instruments fill one continuous progress bar"
    (let [{:keys [request]} (fixture {:completed-report-count 2
                                      :report-count           4})
          progress (->> (sut/page request)
                        (l/select-one
                         "wa-progress-bar#insurance-survey-progress"))]
      (is (= {:data-next-value 75.0
              :style           {"--insurance-survey-progress-value" "56.25%"}
              :value           56.25}
             (select-keys (l/attrs progress)
                          [:data-next-value :style :value])))))

  (testing "the compact card uses the instrument's first uploaded photo"
    (let [{:keys [conn instrument-id request]} (fixture {})
          image-id (random-uuid)
          _ @(d/transact conn
                         [{:db/id "image"
                           :image/image-id image-id}
                          [:db/add [:instrument/instrument-id instrument-id]
                           :instrument/images "image"]])
          request (assoc request :db (d/db conn))
          image   (->> (sut/page request)
                       (l/select-one ".insurance-survey-card-media")
                       (l/select-one :img))]
      (is (= "Test Trumpet" (:alt (l/attrs image))))
      (is (str/includes? (:src (l/attrs image)) (str image-id)))))

  (testing "the compact card uses coverage icons and keeps its description in the scrolling facts"
    (let [{:keys [conn coverage-id coverage-type-id instrument-id policy-id request]}
          (fixture {})
          description "A long instrument description that belongs with the other card facts."
          _ @(d/transact
              conn
              [[:db/add [:insurance.coverage.type/type-id coverage-type-id]
                :insurance.coverage.type/name "Grundschutz"]
               {:db/id                                  "night-car-coverage"
                :insurance.coverage.type/type-id        (random-uuid)
                :insurance.coverage.type/name           "Nachzeit im Auto"
                :insurance.coverage.type/premium-factor 1.0M}
               {:db/id                                  "rehearsal-room-coverage"
                :insurance.coverage.type/type-id        (random-uuid)
                :insurance.coverage.type/name           "Proberaum"
                :insurance.coverage.type/premium-factor 1.0M}
               [:db/add [:instrument.coverage/coverage-id coverage-id]
                :instrument.coverage/types "night-car-coverage"]
               [:db/add [:instrument.coverage/coverage-id coverage-id]
                :instrument.coverage/types "rehearsal-room-coverage"]
               [:db/add [:insurance.policy/policy-id policy-id]
                :insurance.policy/coverage-types "night-car-coverage"]
               [:db/add [:insurance.policy/policy-id policy-id]
                :insurance.policy/coverage-types "rehearsal-room-coverage"]
               [:db/add [:instrument/instrument-id instrument-id]
                :instrument/description description]])
          db            (d/db conn)
          request       (assoc request
                               :db db
                               :policy (q/retrieve-policy db policy-id))
          view          (sut/page request)
          heading       (->> view
                             (l/select-one "#insurance-survey-instrument")
                             (l/select-one :h2))
          facts         (l/select-one ".insurance-survey-card-facts" view)
          fact-items    (l/children facts)
          coverage-item (some #(when (= :insurance/coverage-types
                                        (-> % l/first-child
                                            page-shell/translation-key))
                                 %)
                              fact-items)
          description-item (last fact-items)
          icons         (l/select "[data-insurance-coverage-type-icon]"
                                  coverage-item)]
      (is (= {:coverage-icons
              #{{:kind "grundschutz" :label "Grundschutz"}
                {:kind "nachzeit-im-auto" :label "Nachzeit im Auto"}
                {:kind "proberaum" :label "Proberaum"}}
              :coverage-tooltips
              #{"Grundschutz" "Nachzeit im Auto" "Proberaum"}
              :heading-trimmed? true
              :facts-trimmed?   true
              :description
              {:class #{"wa-span-grid"}
               :label :instrument/description
               :text  description}}
             {:coverage-icons
              (set (map (fn [icon]
                          (let [attrs (l/attrs icon)]
                            {:kind  (:data-insurance-coverage-type-icon attrs)
                             :label (:aria-label attrs)}))
                        icons))
              :coverage-tooltips
              (set (map l/text (l/select 'wa-tooltip coverage-item)))
              :heading-trimmed?
              (contains? (:class (l/attrs heading)) "trim-none")
              :facts-trimmed?
              (every? #(contains? (:class (l/attrs %)) "trim-none")
                      (concat (l/select :dt facts) (l/select :dd facts)))
              :description
              {:class (:class (l/attrs description-item))
               :label (-> description-item l/first-child
                          page-shell/translation-key)
               :text  (-> description-item l/children second l/text)}}))))

  (testing "the transition kind is exposed to CSS without changing the active card"
    (let [{:keys [member-id policy-id request]} (fixture {})
          report-id (:insurance.survey.report/report-id
                     (:active-report
                      (queries/survey-data (:db request) policy-id member-id)))
          workflow (->> (assoc request :page-state
                               {actions/form-key
                                {:answered-count   1
                                 :current-flow-key :keep-insured
                                 :decisions        [:confirm-band]
                                 :mode             :question
                                 :report-id        report-id
                                 :transition-kind  :question}})
                        sut/page
                        (l/select-one ".insurance-survey-workflow"))]
      (is (= "question" (:data-transition-kind (l/attrs workflow))))
      (is (= {:style {"--insurance-survey-progress-value" "50.0%"}
              :value 50.0}
             (select-keys
              (l/attrs
               (l/select-one "wa-progress-bar#insurance-survey-progress"
                             workflow))
              [:style :value])))))

  (testing "the development animation lab remains disabled"
    (let [{:keys [request]} (fixture {:report-count 3})
          production-view (sut/page request)
          development-view (sut/page (assoc request :dev? true))]
      (is (some #(some-> %
                         l/attrs
                         :src
                         (str/starts-with?
                          "/js/insurance-survey-motion.js?v="))
                (l/select :script production-view)))
      (is (= {:production-lab nil
              :development-lab nil
              :development-script nil}
             {:production-lab (l/select-one "#insurance-survey-animation-lab"
                                            production-view)
              :development-lab (l/select-one "#insurance-survey-animation-lab"
                                             development-view)
              :development-script
              (some #(when (some-> %
                                   l/attrs
                                   :src
                                   (str/starts-with?
                                    "/js/insurance-survey-animation-lab.js?v="))
                       %)
                    (l/select :script development-view))}))))

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

  (testing "a completed response renders the staged celebration and final actions"
    (let [{:keys [request]} (fixture {:response-completed-at
                                      #inst "2026-03-15T00:00:00.000-00:00"})
          surface (l/select-one page-surface/PageSurface (sut/page request))
          complete (some #(when (= "insurance-survey-complete" (:id (l/attrs %))) %)
                         (l/select :div surface))
          stages (l/select "[data-celebration-stage]" complete)
          celebrate (some #(when (= "insurance-survey-celebrate" (:id (l/attrs %))) %)
                          (l/select button/Button complete))]
      (is (= :insurance/review-complete-title
             (-> (l/select-one :h1 complete)
                 page-shell/translation-key)))
      (is (= :insurance/review-celebrate
             (page-shell/translation-key celebrate)))
      (is (= ["1" "2" "3"]
             (mapv #(-> % l/attrs :data-celebration-stage) stages)))
      (is (every? true? (map #(-> % l/attrs :hidden) stages)))
      (is (= 2 (count (l/select button/Button (last stages)))))))

  (testing "a milestone briefly overlays the next card without interrupting the workflow"
    (let [{:keys [member-id policy-id request]} (fixture {})
          report-id (:insurance.survey.report/report-id
                     (:active-report
                      (queries/survey-data (:db request) policy-id member-id)))
          view (->> (assoc request :page-state
                           {actions/form-key {:current-flow-key :used
                                              :decisions        []
                                              :milestone?       true
                                              :mode             :question
                                              :report-id        report-id
                                              :transition-kind  :item}})
                    sut/page
                    (l/select-one page-surface/PageSurface))
          milestone (l/select-one "#insurance-survey-milestone" view)]
      (is (= [:insurance/review-good-job :insurance/review-milestone]
             (mapv l/first-child (l/select :i18n/tr milestone))))
      (is (some? (l/select-one "#insurance-survey-card-deck" view)))
      (is (nil? (l/select-one "#insurance-survey-continue" view)))))

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
