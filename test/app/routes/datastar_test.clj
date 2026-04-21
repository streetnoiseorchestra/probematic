(ns app.routes.datastar-test
  (:require
   [app.engine :as engine]
   [app.settings.routes :as settings.routes]
   [clojure.test :refer [deftest is]]
   [hifi.engine.shell :as shell]))

(defn page-routes-engine [page]
  ((requiring-resolve 'app.routes.datastar/page-routes-engine) page))

(defn engine-command-handler [command-name]
  ((requiring-resolve 'app.routes.datastar/engine-command-handler) command-name))

(defn route-signature [route]
  {:path         (first route)
   :name         (get-in route [1 :name])
   :child-routes (into #{} (map (fn [[path data]]
                                  {:path path
                                   :name (:name data)}))
                       (drop 2 route))})

(deftest page-routes-engine-preserves-the-settings-route-shape
  (let [route (page-routes-engine settings.routes/page)]
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
                            {:path "/create-discount-type"
                             :name :app.settings.routes/create-discount-type}
                            {:path "/update-discount-type"
                             :name :app.settings.routes/update-discount-type}
                            {:path "/delete-discount-type"
                             :name :app.settings.routes/delete-discount-type}
                            {:path "/open-discount-type-edit"
                             :name :app.settings.routes/open-discount-type-edit}
                            {:path "/close-discount-type-edit"
                             :name :app.settings.routes/close-discount-type-edit}
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

(deftest engine-command-handler-dispatches-through-the-request-engine
  (let [calls   (atom [])
        handler (engine-command-handler ::ping)
        env     (shell/register (engine/build-env)
                                [{:effect/kind    ::record
                                  :effect/handler (fn [ctx data]
                                                    (swap! calls conj {:command-kind (get-in ctx [:command :command/kind])
                                                                       :data         data}))}
                                 {:command/kind      ::ping
                                  :command/coeffects []
                                  :command/handler   (fn [_cofx data]
                                                       {:outcome/effects [{:effect/kind ::record
                                                                           :effect/data {:received data}}]})}])
        result  (handler {:system {:engine env}
                          :params {:foo :bar}})]
    (is (= {:calls        [{:command-kind ::ping
                            :data         {:received {:command/kind ::ping}}}]
            :command-kind ::ping
            :outcome      {:outcome/effects [{:effect/kind ::record
                                              :effect/data {:received {:command/kind ::ping}}}]}}
           {:calls        @calls
            :command-kind (get-in result [:command :command/kind])
            :outcome      (select-keys (:outcome result) [:outcome/effects])}))))
