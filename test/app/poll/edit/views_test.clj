(ns app.poll.edit.views-test
  (:require
   [app.poll.edit.views :as views]
   [app.poll.view-test-support :as support]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

(defn- save-action []
  {:label      :action/save
   :form       "poll-edit-form"
   :type       "submit"
   :appearance "filled"
   :variant    "brand"})

(deftest create-poll-page-surface
  (testing "New Poll uses a standard editor surface with visible Cancel and Save actions."
    (let [system (support/new-system "poll-create-surface")]
      (is (= {:contract {:width       :standard
                         :breadcrumbs [:polls/title :polls/new-poll]
                         :mobile      {:label :polls/title :href "/polls"}
                         :actions     [{:label      :action/cancel
                                        :href       "/polls"
                                        :appearance "plain"}
                                       (save-action)]
                         :overflow    []}
              :heading  :polls/new-poll
              :subtitle nil
              :form-id  "poll-edit-form"
              :last-tag :app.ui2.page-surface/page-surface
              :last-id  nil}
             (-> system support/request views/page support/page-structure))))))

(deftest edit-poll-page-surface
  (testing "The poll title identifies the editor while Delete remains secondary."
    (let [system             (support/new-system "poll-edit-surface")
          {:keys [poll-id]} (support/seed-poll! system :poll.status/draft)]
      (is (= {:contract {:width       :standard
                         :breadcrumbs [:polls/title "Existing Poll" :action/edit]
                         :mobile      {:label "Existing Poll"
                                       :href  (str "/poll/" poll-id)}
                         :actions     [{:label      :action/cancel
                                        :href       (str "/poll/" poll-id)
                                        :appearance "plain"}
                                       (save-action)]
                         :overflow    [{:label       :action/delete
                                        :data-dialog (str "open poll-remove-" poll-id)
                                        :variant     "danger"}]}
              :heading  "Existing Poll draft"
              :subtitle "single"
              :form-id  "poll-edit-form"
              :last-tag :wa-dialog
              :last-id  (str "poll-remove-" poll-id)}
             (-> system
                 (support/request {:path-params {:poll/poll-id poll-id}})
                 views/page
                 support/page-structure))))))

(deftest choices-section-heading
  (testing "The choices section stays subordinate to the poll title."
    (let [view (-> (support/new-system "poll-create-choices-heading")
                   support/request
                   views/page)
          node (l/select-one 'h2 view)]
      (is (= {:text    "choices-title"
              :classes #{"wa-heading-l"}}
             {:text    (l/text node)
              :classes (:class (l/attrs node))})))))
