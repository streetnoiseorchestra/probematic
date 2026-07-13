(ns app.insurance.policy.surveys.views-test
  (:require
   [app.insurance.policy.surveys.views :as sut]
   [app.insurance.test-support :as insurance-test]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.ui2.button :as button]
   [app.ui2.page-surface :as page-surface]
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
        inputs            (l/select :input form)]
    (is (= #{"name" "closes-at"}
           (set (keep (comp :name l/attrs) inputs))))
    (is (nil? (l/select-one :wa-input surface)))
    (is (some? (:data-action (l/attrs form))))))

(deftest active-survey-renders-response-management-and-confirmation-dialogs
  (let [{:keys [conn coverage-id member-id policy-id request]} (fixture)
        {:keys [response-id]}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})
        surface (->> (assoc request
                            :db (d/db conn)
                            :policy (q/retrieve-policy (d/db conn) policy-id))
                     sut/page
                     (l/select-one page-surface/PageSurface))
        row     (l/select-one (str "#insurance-survey-response-" response-id)
                              surface)
        action  (l/select-one button/Button row)
        dialogs (l/select :wa-dialog surface)]
    (testing "each member response can be completed by the insurance team"
      (is (= (str response-id) (:data-id (l/attrs action))))
      (is (some? (:data-action (l/attrs action))))
      (is (some? (:data-attr:disabled (l/attrs action))))
      (is (some? (:data-attr:loading (l/attrs action))))
      (is (nil? (l/select-one :table surface))))
    (testing "reminding members and closing a survey require confirmation"
      (is (= #{"insurance-survey-reminders-dialog"
               "insurance-survey-close-dialog"}
             (set (map (comp :id l/attrs) dialogs)))))))

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
