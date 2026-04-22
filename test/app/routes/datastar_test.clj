(ns app.routes.datastar-test
  (:require
   [app.settings.routes :as settings.routes]
   [clojure.test :refer [deftest is]]))

(defn page-routes-mixed [page]
  ((requiring-resolve 'app.routes.datastar/page-routes-mixed)
   {:path             (:path page)
    :page-name        (:page-name page)
    :view-ns          (:view-ns page)
    :command-ns       (:command-ns page)
    :nexus-command-ns (:nexus-command-ns page)
    :cmds             (:direct-cmds page)
    :nexus-cmds       (:nexus-cmds page)}))

(defn nexus-command-handler [command-ns command-name]
  ((requiring-resolve 'app.routes.datastar/nexus-command-handler) command-ns command-name))

(defn route-signature [route]
  {:path         (first route)
   :name         (get-in route [1 :name])
   :child-routes (into #{} (map (fn [[path data]]
                                  {:path path
                                   :name (:name data)}))
                       (drop 2 route))})

(deftest page-routes-mixed-preserves-the-settings-route-shape
  (let [route (page-routes-mixed settings.routes/page)]
    (is (= {:path         "/band-settings"
            :name         :app.settings.routes/band-settings
            :child-routes #{{:path ""
                             :name nil}
                            {:path "/create-team"
                             :name :app.settings.routes/create-team}
                            {:path "/update-team"
                             :name :app.settings.routes/update-team}
                            {:path "/delete-team"
                             :name :app.settings.routes/delete-team}
                            {:path "/remove-team-member"
                             :name :app.settings.routes/remove-team-member}
                            {:path "/add-team-member"
                             :name :app.settings.routes/add-team-member}
                            {:path "/open-team-edit"
                             :name :app.settings.routes/open-team-edit}
                            {:path "/close-team-edit"
                             :name :app.settings.routes/close-team-edit}
                            {:path "/update-discount-type"
                             :name :app.settings.routes/update-discount-type}
                            {:path "/delete-discount-type"
                             :name :app.settings.routes/delete-discount-type}
                            {:path "/open-discount-type-edit"
                             :name :app.settings.routes/open-discount-type-edit}
                            {:path "/close-discount-type-edit"
                             :name :app.settings.routes/close-discount-type-edit}
                            {:path "/create-discount-type"
                             :name :app.settings.routes/create-discount-type}
                            {:path "/create-section"
                             :name :app.settings.routes/create-section}
                            {:path "/update-section"
                             :name :app.settings.routes/update-section}
                            {:path "/open-section-edit"
                             :name :app.settings.routes/open-section-edit}
                            {:path "/close-section-edit"
                             :name :app.settings.routes/close-section-edit}
                            {:path "/open-section-reorder"
                             :name :app.settings.routes/open-section-reorder}
                            {:path "/close-section-reorder"
                             :name :app.settings.routes/close-section-reorder}
                            {:path "/update-section-order"
                             :name :app.settings.routes/update-section-order}}}
           (route-signature route)))))

(defn ping [_req]
  [[::ping]])

(deftest nexus-command-handler-returns-the-ring-response-produced-by-effects
  (let [calls   (atom [])
        handler (nexus-command-handler 'app.routes.datastar-test ::ping)
        config  {:nexus/system->state identity
                 :nexus/effects       {::record  (fn [_ctx _system data]
                                                   (swap! calls conj [:record data])
                                                   nil)
                                       ::respond (fn [_ctx _system data]
                                                   (swap! calls conj [:respond data])
                                                   {:status 200
                                                    :body   data})}
                 :nexus/actions       {::ping (fn [_state]
                                                [[::record {:received true}]
                                                 [::respond {:ok true}]])}}
        result  (handler {:system {:nexus config}
                          :params {:foo :bar}})]
    (is (= [[:record {:received true}]
            [:respond {:ok true}]]
           @calls))
    (is (= {:status 200
            :body   {:ok true}}
           result))))
