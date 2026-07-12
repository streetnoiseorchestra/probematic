(ns app.everything.views-test
  (:require
   [app.everything.routes :as routes]
   [app.everything.views :as views]
   [app.ui2.page-surface :as page-surface]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

(defn- page-view []
  (views/page {:tr (fn
                     ([path] (name (last path)))
                     ([path _] (name (last path))))}))

(defn- translation-keys [node]
  (mapv l/first-child (l/select :i18n/tr node)))

(defn- destination-summary [destination]
  (merge {:copy (translation-keys destination)}
         (select-keys (l/attrs destination)
                      [:href :target :rel :disabled])))

(deftest everything-directory-page
  (testing "Everything groups the full application map by member intent."
    (let [view         (page-view)
          surface      (l/select-one page-surface/PageSurface view)
          toolbar      (some-> surface l/attrs ::page-surface/toolbar)
          groups       (l/select ".group" view)
          destinations (l/select ".destination" view)]
      (is (= {:surface-width :standard
              :toolbar-label :everything/toolbar-label
              :group-labels  [:everything/gig-work
                              :everything/band
                              :everything/collaboration
                              :everything/administration]
              :destinations
              [{:copy [:everything/gigs-dashboard
                       :everything/gigs-dashboard-description]
                :href "/"}
               {:copy [:everything/all-gigs
                       :everything/all-gigs-description]
                :href "/gigs"}
               {:copy [:everything/calendar
                       :everything/calendar-description]
                :href "/calendar"}
               {:copy [:everything/probeplan
                       :everything/probeplan-description]
                :href "/probeplan"}
               {:copy [:everything/repertoire
                       :everything/repertoire-description]
                :href "/songs"}
               {:copy     [:everything/activity
                           :everything/coming-soon
                           :everything/activity-description]
                :disabled true}
               {:copy [:everything/members
                       :everything/members-description]
                :href "/members"}
               {:copy [:everything/polls
                       :everything/polls-description]
                :href "/polls"}
               {:copy [:everything/insurance
                       :everything/insurance-description]
                :href "/insurance"}
               {:copy [:everything/statistics
                       :everything/statistics-description]
                :href "/stats"}
               {:copy   [:everything/forum
                         :everything/forum-description]
                :href   "https://forum.streetnoise.at"
                :target "_blank"
                :rel    "noopener noreferrer"}
               {:copy   [:everything/files
                         :everything/files-description]
                :href   "https://data.streetnoise.at/apps/files/"
                :target "_blank"
                :rel    "noopener noreferrer"}
               {:copy   [:everything/chat
                         :everything/chat-description]
                :href   "https://chat.streetnoise.at"
                :target "_blank"
                :rel    "noopener noreferrer"}
               {:copy [:everything/band-settings
                       :everything/band-settings-description]
                :href "/band-settings"}]}
             {:surface-width (some-> surface l/attrs ::page-surface/width)
              :toolbar-label (some-> toolbar l/attrs :aria-label l/first-child)
              :group-labels  (mapv #(-> (l/select-one 'h2 %)
                                        translation-keys
                                        first)
                                   groups)
              :destinations  (mapv destination-summary destinations)})))))

(deftest everything-route
  (testing "Everything is available through an authenticated Datastar page route."
    (let [route (routes/routes)]
      (is (= {:path "/everything"
              :name :app.everything.routes/index}
             {:path (first route)
              :name (get-in route [1 :name])})))))
