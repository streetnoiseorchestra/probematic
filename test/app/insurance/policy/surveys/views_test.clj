(ns app.insurance.policy.surveys.views-test
  (:require
   [app.insurance.policy.surveys.views :as sut]
   [app.insurance.test-support :as insurance-test]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router [["/act" {:name :app.routes.datastar/act}]]))

(defn tr
  ([path]
   (name (last path)))
  ([path _vars]
   (tr path)))

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-policy-surveys-views")
        ids                      (insurance-test/seed-page-shell-fixture! conn member-id)]
    (merge ids
           {:conn      conn
            :member-id member-id
            :request   {:current-locale "en"
                        :db             (d/db conn)
                        :path-params    {:policy-id (:policy-id ids)}
                        :policy         (q/retrieve-policy (d/db conn) (:policy-id ids))
                        :session        {:session/member {:member/member-id member-id}}
                        :tr             tr
                        ::r/router      router}})))

(deftest survey-management-renders-native-form-controls
  (let [{:keys [request]} (fixture)
        surface           (l/select-one page-surface/PageSurface (sut/page request))
        form              (l/select-one "#insurance-survey-admin-form" surface)
        inputs            (l/select :input form)
        contract          (page-shell/page-contract (sut/page request))]
    (is (= #{"closes-at"}
           (set (keep (comp :name l/attrs) inputs))))
    (is (nil? (l/select-one :wa-input surface)))
    (is (re-find #"start-survey" (:data-action (l/attrs form))))
    (is (= [{:label      :insurance/start-survey
             :form       "insurance-survey-admin-form"
             :type       "submit"
             :appearance "filled"
             :variant    "brand"}]
           (:actions contract)))
    (is (empty? (:overflow contract)))))

(deftest active-survey-renders-toolbar-actions-and-filterable-response-table
  (let [{:keys [conn coverage-id member-id policy-id request]} (fixture)
        {:keys [response-id survey-id]}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})
        completed-response-id (random-uuid)
        _ @(d/transact
            conn
            [{:db/id                                         "completed-response"
              :insurance.survey.response/response-id          completed-response-id
              :insurance.survey.response/member               [:member/member-id member-id]
              :insurance.survey.response/completed-at         insurance-test/created-at}
             [:db/add
              [:insurance.survey/survey-id survey-id]
              :insurance.survey/responses
              "completed-response"]])
        view     (sut/page
                  (assoc request
                         :db (d/db conn)
                         :policy (q/retrieve-policy (d/db conn) policy-id)))
        surface  (l/select-one page-surface/PageSurface view)
        contract (page-shell/page-contract view)
        table    (l/select-one "table.insurance-survey-responses" surface)
        rows     (l/select :tr (l/select-one :tbody table))
        row      (l/select-one (str "#insurance-survey-response-" response-id)
                               table)
        action   (l/select-one button/Button row)
        completed-row (l/select-one
                       (str "#insurance-survey-response-"
                            completed-response-id)
                       table)
        mark-incomplete-action (l/select-one button/Button completed-row)
        save-status-spans (l/select :span
                                    (l/select-one "#insurance-survey-closes-at-status"
                                                  surface))
        filters  (l/select button/Button
                           (l/select-one :wa-button-group surface))
        close    (first (l/select :wa-dropdown-item
                                  (-> surface
                                      l/attrs
                                      ::page-surface/toolbar
                                      l/attrs
                                      :app.ui2.page-toolbar/overflow-items)))
        dialogs  (l/select :wa-dialog surface)]
    (testing "the page toolbar has one primary action and destructive overflow"
      (is (= [{:label      :insurance/survey-send-reminders
               :appearance "filled"
               :variant    "brand"
               :disabled   false
               :data-dialog "open insurance-survey-reminders-dialog"}]
             (:actions contract)))
      (is (= [{:label       :insurance/survey-close
               :data-dialog "open insurance-survey-close-dialog"
               :variant     "danger"}]
             (:overflow contract)))
      (is (= {:name :xmark :slot "icon"}
             (let [icon (l/select-one ico/Icon close)]
               {:name (::ico/name (l/attrs icon))
                :slot (:slot (l/attrs icon))}))))
    (testing "member responses use a dense native two-column table"
      (is (some? table))
      (is (= 2 (count (l/select :th (l/select-one :thead table)))))
      (is (= 2 (count rows)))
      (is (= (str response-id) (:data-id (l/attrs action))))
      (is (some? (:data-action (l/attrs action))))
      (is (some? (:data-attr:disabled (l/attrs action))))
      (is (some? (:data-attr:loading (l/attrs action))))
      (is (= :insurance/survey-mark-complete
             (page-shell/translation-key (:aria-label (l/attrs action)))))
      (is (= :insurance/survey-mark-complete
             (page-shell/translation-key (:title (l/attrs action)))))
      (is (= :insurance/survey-mark-complete
             (page-shell/translation-key (l/select-one ".label" action))))
      (is (= {:library :snoico :name :xmark}
             (let [icon (l/select-one ico/Icon mark-incomplete-action)]
               {:library (::ico/library (l/attrs icon))
                :name    (::ico/name (l/attrs icon))}))))
    (testing "all, incomplete, and complete filters are page-local"
      (is (= [:insurance/survey-filter-all
              :insurance/survey-filter-incomplete
              :insurance/survey-filter-completed]
             (mapv page-shell/translation-key filters)))
      (is (every? (comp some? :data-on:click l/attrs) filters))
      (is (every? (comp some? :data-show l/attrs) rows)))
    (testing "live-save feedback is hidden until Datastar initializes"
      (is (= #{"display: none;"}
             (set (map (comp :style l/attrs) save-status-spans))))
      (is (= [#{"saving"} #{"saved"} nil]
             (mapv (comp :class l/attrs) save-status-spans)))
      (is (= "$insuranceSurveyAdmin.saveStatus = 'idle'"
             (:data-on:animationend
              (l/attrs (second save-status-spans))))))
    (testing "reminding members and closing a survey require confirmation"
      (is (= #{"insurance-survey-reminders-dialog"
               "insurance-survey-close-dialog"}
             (set (map (comp :id l/attrs) dialogs)))))))

(deftest reminders-are-disabled-when-every-response-is-complete
  (let [{:keys [conn coverage-id member-id policy-id request]} (fixture)
        _ (insurance-test/seed-member-survey!
           conn
           {:coverage-ids          [coverage-id]
            :member-id             member-id
            :policy-id             policy-id
            :response-completed-at insurance-test/created-at})
        view     (-> request
                     (assoc :db (d/db conn))
                     sut/page)
        contract (page-shell/page-contract view)
        empty-row (l/select-one "#insurance-survey-filter-empty-incomplete"
                                view)]
    (is (= true (-> contract :actions first :disabled)))
    (is (= 2 (-> empty-row l/first-child l/attrs :colspan)))
    (is (some? (:data-show (l/attrs empty-row))))))

(deftest unauthorized-members-do-not-receive-survey-actions-or-dialogs
  (let [{:keys [conn coverage-id outsider-id member-id policy-id request]}
        (fixture)
        _ (insurance-test/seed-member-survey!
           conn
           {:coverage-ids [coverage-id]
            :member-id    member-id
            :policy-id    policy-id})
        surface (-> request
                    (assoc :db (d/db conn)
                           :session {:session/member
                                     {:member/member-id outsider-id}})
                    sut/page
                    (l/select-one page-surface/PageSurface))]
    (is (empty? (l/select :wa-dialog surface)))
    (is (nil? (l/select-one "#insurance-survey-admin-form" surface)))))
