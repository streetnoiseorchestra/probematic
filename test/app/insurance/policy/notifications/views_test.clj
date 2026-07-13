(ns app.insurance.policy.notifications.views-test
  (:require
   [app.insurance.policy.notifications.views :as sut]
   [app.insurance.test-support :as insurance-test]
   [app.queries :as q]
   [app.test-common :as tc]
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

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-notification-views")
        {:keys [policy-id] :as ids}
        (insurance-test/seed-page-shell-fixture! conn member-id)]
    (merge ids
           {:conn      conn
            :member-id member-id
            :request
            {:current-locale "en"
             :db             (d/db conn)
             :path-params    {:policy-id policy-id}
             :policy         (q/retrieve-policy (d/db conn) policy-id)
             :session        {:session/member {:member/member-id member-id
                                               :member/name      "Ada"
                                               :member/email     "ada@example.test"}}
             :system         {:env {:app-base-url "https://example.test"}}
             :tr             tr
             ::r/router      router}})))

(deftest unavailable-private-cost-cannot-be-selected
  (let [{:keys [conn coverage-id policy-id request]} (fixture)
        policy             (q/retrieve-policy (d/db conn) policy-id)
        category-factor-id (-> policy
                               :insurance.policy/category-factors
                               first
                               :insurance.category.factor/category-factor-id)
        _                  @(d/transact
                             conn
                             [[:db/add
                               [:instrument.coverage/coverage-id coverage-id]
                               :instrument.coverage/private?
                               true]
                              [:db/retract
                               [:insurance.policy/policy-id policy-id]
                               :insurance.policy/category-factors
                               [:insurance.category.factor/category-factor-id
                                category-factor-id]]])
        db                 (d/db conn)
        view               (sut/page
                            (assoc request
                                   :db db
                                   :policy (q/retrieve-policy db policy-id)))
        surface            (l/select-one page-surface/PageSurface view)
        member-checkbox    (->> (l/select "input[type=checkbox]" surface)
                                (filter #(some? (:value (l/attrs %))))
                                first)]
    (testing "unavailable costs disable the member and omit the email preview"
      (is (= {:disabled? true
              :cost      "cost-unavailable"
              :preview?  false}
             {:disabled? (:disabled (l/attrs member-checkbox))
              :cost      (-> (l/select-one "span[id^=payment-cost-unavailable]"
                                           surface)
                             l/text)
              :preview?  (boolean (some #(= "payment-email-preview-title"
                                            (l/text %))
                                        (l/select :h2 surface)))})))))

(deftest payment-page-empty-state
  (let [{:keys [conn coverage-id policy-id request]} (fixture)
        _       @(d/transact
                  conn
                  [[:db/retract
                    [:insurance.policy/policy-id policy-id]
                    :insurance.policy/covered-instruments
                    [:instrument.coverage/coverage-id coverage-id]]])
        db      (d/db conn)
        surface (l/select-one
                 page-surface/PageSurface
                 (sut/page
                  (assoc request
                         :db db
                         :policy (q/retrieve-policy db policy-id))))]
    (is (= {:empty-title "no-private-payments-title"
            :table?      false}
           {:empty-title (-> (l/select-one :strong surface) l/text)
            :table?      (boolean (l/select-one :table surface))}))))
