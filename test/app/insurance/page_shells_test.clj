(ns app.insurance.page-shells-test
  (:require
   [app.insurance.coverage.create.views :as coverage-create.views]
   [app.insurance.coverage.edit.views :as coverage-edit.views]
   [app.insurance.coverage.views :as coverage.views]
   [app.insurance.index.views :as index.views]
   [app.insurance.policy.changes.views :as policy-changes.views]
   [app.insurance.policy.create.views :as policy-create.views]
   [app.insurance.policy.dashboard.views :as dashboard.views]
   [app.insurance.policy.notifications.views :as policy-notifications.views]
   [app.insurance.policy.review.views :as review.views]
   [app.insurance.policy.settings.views :as settings.views]
   [app.insurance.policy.workbench.views :as workbench.views]
   [app.insurance.survey.views :as survey.views]
   [app.insurance.test-support :as insurance-test]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.ui2.page-shell-test-support :as page-shell]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-page-shells")
        ids                      (insurance-test/seed-page-shell-fixture! conn member-id)]
    (merge ids
           {:conn conn
            :request
            {:db             (d/db conn)
             :current-locale "en"
             :session        {:session/member {:member/member-id member-id
                                               :member/name      "Ada"
                                               :member/email     "ada@example.test"}}
             :system         {:env {:app-base-url "https://example.test"}}
             :tr             tr
             ::r/router      router}})))

(deftest directory-dashboard-and-coverage-use-shared-page-shells
  (let [{:keys [conn request policy-id coverage-id]} (fixture)]
    (testing "The Insurance directory owns collection context and policy creation."
      (is (= {:width       :wide
              :breadcrumbs [:home :insurance/title]
              :mobile      {:label :home :href "/"}
              :actions     [{:label :insurance/new-policy
                             :href  "/insurance-new/"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request index.views/page page-shell/page-contract))))
    (testing "A policy dashboard owns policy context and its lifecycle actions."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026"]
              :mobile      {:label :insurance/title :href "/insurance"}
              :actions     [{:label :insurance/review
                             :href  (str "/insurance-policy/" policy-id "/review")
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    [{:label :insurance/workbench
                             :value (str "/insurance-policy/" policy-id "/workbench")}
                            {:label :insurance/add-coverage
                             :value (str "/insurance-coverage-create/" policy-id)}
                            {:label :insurance/policy-settings
                             :value (str "/insurance-policy/" policy-id "/settings")}
                            {:label :insurance/send-changes
                             :value (str "/insurance-policy-changes/" policy-id "/")}]}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 dashboard.views/page
                 page-shell/page-contract))))
    (testing "An active policy exposes its payment-request workflow."
      (let [active-db (:db-after
                       @(d/transact conn [[:db/add
                                           [:insurance.policy/policy-id policy-id]
                                           :insurance.policy/status
                                           :insurance.policy.status/active]]))]
        (is (some #{{:label :insurance/request-payments-title
                     :value (str "/insurance-policy-notify/" policy-id "/")}}
                  (-> request
                      (assoc :db active-db
                             :path-params {:policy-id policy-id})
                      dashboard.views/page
                      page-shell/page-contract
                      :overflow)))))
    (testing "Coverage detail keeps the policy and instrument in context."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" "Test Trumpet"]
              :mobile      {:label "Insurance 2026"
                            :href  (str "/insurance-policy/" policy-id "/")}
              :actions     [{:label :action/edit
                             :href  (str "/insurance-coverage-edit/" coverage-id "/")
                             :appearance "outlined"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:coverage-id coverage-id})
                 coverage.views/page
                 page-shell/page-contract))))))

(deftest policy-workflows-use-wide-contextual-surfaces
  (let [{:keys [request policy-id]} (fixture)
        policy-url (str "/insurance-policy/" policy-id "/")]
    (testing "Review keeps policy context while review commands remain with the content."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" :insurance/review]
              :mobile      {:label "Insurance 2026" :href policy-url}
              :actions     []
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 review.views/page
                 page-shell/page-contract))))
    (testing "The coverage workbench keeps its filters and bulk actions out of the page toolbar."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" :insurance/workbench]
              :mobile      {:label "Insurance 2026" :href policy-url}
              :actions     []
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 workbench.views/page
                 page-shell/page-contract))))
    (testing "Policy settings uses the same wide policy context."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" :insurance/policy-settings]
              :mobile      {:label "Insurance 2026" :href policy-url}
              :actions     []
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 settings.views/page
                 page-shell/page-contract))))))

(deftest policy-creation-uses-a-standard-form-surface
  (let [{:keys [request]} (fixture)]
    (is (= {:width       :standard
            :breadcrumbs [:insurance/title :insurance/create-title]
            :mobile      {:label :insurance/title :href "/insurance"}
            :actions     [{:label :action/cancel
                           :href  "/insurance"
                           :appearance "outlined"}
                          {:label :action/create
                           :form "insurance-policy-create-form"
                           :type "submit"
                           :appearance "filled"
                           :variant "brand"}]
            :overflow    []}
           (-> request
               policy-create.views/page
               page-shell/page-contract)))))

(deftest policy-changes-use-a-standard-confirmation-surface
  (let [{:keys [request policy-id]} (fixture)
        policy-url (urls/link-policy policy-id)]
    (is (= {:width       :standard
            :breadcrumbs [:insurance/title "Insurance 2026" :insurance/send-changes]
            :mobile      {:label "Insurance 2026" :href policy-url}
            :actions     [{:label :action/cancel
                           :href  policy-url
                           :appearance "outlined"}
                          {:label :insurance/confirm-and-send
                           :data-dialog "open insurance-policy-send-changes-dialog"
                           :appearance "filled"
                           :variant "brand"}]
            :overflow    [{:label :insurance/confirm-skip-send
                           :data-dialog "open insurance-policy-confirm-changes-dialog"}]}
           (-> request
               (assoc :path-params {:policy-id policy-id})
               policy-changes.views/page
               page-shell/page-contract)))))

(deftest payment-notifications-use-a-wide-policy-surface
  (let [{:keys [request policy-id]} (fixture)
        policy     (q/retrieve-policy (:db request) policy-id)
        policy-url (urls/link-policy policy-id)]
    (is (= {:width       :wide
            :breadcrumbs [:insurance/title "Insurance 2026"
                          :insurance/request-payments-title]
            :mobile      {:label "Insurance 2026" :href policy-url}
            :actions     [{:label :action/cancel
                           :href  policy-url
                           :appearance "outlined"}
                          {:label :insurance/send-payment-notifications
                           :form "insurance-payment-notifications-form"
                           :type "submit"
                           :appearance "filled"
                           :variant "brand"}]
            :overflow    []}
           (-> request
               (assoc :path-params {:policy-id policy-id}
                      :policy policy)
               policy-notifications.views/page
               page-shell/page-contract)))))

(deftest coverage-review-uses-a-standard-context-only-surface
  (let [{:keys [conn request policy-id coverage-id]} (fixture)
        member-id (get-in request [:session :session/member :member/member-id])
        _ (insurance-test/seed-member-survey!
           conn
           {:coverage-ids [coverage-id]
            :member-id    member-id
            :policy-id    policy-id})
        policy-url (urls/link-policy policy-id)]
    (is (= {:width       :standard
            :breadcrumbs [:insurance/title "Insurance 2026"
                          :insurance/coverage-review]
            :mobile      {:label "Insurance 2026" :href policy-url}
            :actions     []
            :overflow    []}
           (-> request
               (assoc :db (d/db conn)
                      :path-params {:policy-id policy-id}
                      :policy (q/retrieve-policy (d/db conn) policy-id))
               survey.views/page
               page-shell/page-contract)))))

(deftest coverage-creation-uses-standard-step-surfaces
  (let [{:keys [request policy-id instrument-id]} (fixture)
        redirect "/return"
        step-one (urls/link-coverage-create-edit policy-id instrument-id redirect)
        step-two (urls/link-coverage-create2 policy-id instrument-id redirect)
        step-three (urls/link-coverage-create3 policy-id instrument-id redirect)]
    (testing "The instrument step can be cancelled or submitted from the toolbar."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026"
                            :insurance/add-coverage-title :insurance/instrument-step]
              :mobile      {:label "Insurance 2026"
                            :href  (str "/insurance-policy/" policy-id "/")}
              :actions     [{:label :action/cancel
                             :href  (str "/insurance-policy/" policy-id "/")
                             :appearance "outlined"}
                            {:label :action/next
                             :form "coverage-create-instrument-form"
                             :type "submit"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id}
                        :query-params {:instrument-id (str instrument-id)
                                       :redirect redirect})
                 coverage-create.views/instrument-page
                 page-shell/page-contract))))
    (testing "The photos step preserves the redirect in both directions."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026"
                            :insurance/add-coverage-title :insurance/photos-step]
              :mobile      {:label :insurance/instrument-step :href step-one}
              :actions     [{:label :action/back
                             :href step-one
                             :appearance "outlined"}
                            {:label :action/next
                             :href step-three
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id
                                      :instrument-id instrument-id}
                        :query-params {:redirect redirect})
                 coverage-create.views/photos-page
                 page-shell/page-contract))))
    (testing "The coverage step submits the final form and returns to Photos."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026"
                            :insurance/add-coverage-title :insurance/coverage-step]
              :mobile      {:label :insurance/photos-step :href step-two}
              :actions     [{:label :action/back
                             :href step-two
                             :appearance "outlined"}
                            {:label :action/save
                             :form "coverage-create-coverage-form"
                             :type "submit"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id
                                      :instrument-id instrument-id}
                        :query-params {:redirect redirect})
                 coverage-create.views/coverage-page
                 page-shell/page-contract))))))

(deftest coverage-edit-uses-form-actions-and-role-gated-overflow
  (let [{:keys [request coverage-id outsider-id]} (fixture)
        edit-request (assoc request :path-params {:coverage-id coverage-id})]
    (testing "An insurance-team member can save or delete while the instrument remains the title."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026" "Test Trumpet" :action/edit]
              :mobile      {:label "Test Trumpet"
                            :href  (str "/insurance-coverage/" coverage-id "/")}
              :actions     [{:label :action/cancel
                             :href  (str "/insurance-coverage/" coverage-id "/")
                             :appearance "plain"}
                            {:label :action/save
                             :form "coverage-edit-form"
                             :type "submit"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    [{:label :action/delete
                             :data-dialog (str "open coverage-remove-" coverage-id)
                             :variant "danger"}]}
             (-> edit-request
                 coverage-edit.views/page
                 page-shell/page-contract))))
    (testing "Delete is absent when the current member is outside the insurance team."
      (is (= []
             (-> edit-request
                 (assoc-in [:session :session/member :member/member-id] outsider-id)
                 coverage-edit.views/page
                 page-shell/page-contract
                 :overflow))))))
