(ns app.engine.coeffects
  "Probematic-specific hifi-engine coeffects derived from the Ring request context.")

(def request-coeffect
  {:coeffect/kind    :app/request
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/request (:request _ctx)))})

(def db-coeffect
  {:coeffect/kind    :app/db
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/db (get-in _ctx [:request :db])))} )

(def datomic-conn-coeffect
  {:coeffect/kind    :app/datomic-conn
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/datomic-conn
                              (get-in _ctx [:request :datomic-conn])))} )

(def tr-coeffect
  {:coeffect/kind    :app/tr
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/tr (get-in _ctx [:request :tr])))} )

(def system-coeffect
  {:coeffect/kind    :app/system
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/system (get-in _ctx [:request :system])))} )

(def page-state-coeffect
  {:coeffect/kind    :app/page-state
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/page-state (get-in _ctx [:request :page-state])))} )

(def params-coeffect
  {:coeffect/kind    :app/params
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/params (get-in _ctx [:request :params])))} )

(def body-params-coeffect
  {:coeffect/kind    :app/body-params
   :coeffect/handler (fn [_ctx coeffects _]
                       (assoc coeffects :app/body-params (get-in _ctx [:request :parameters :body])))} )

(defn coeffects []
  [request-coeffect
   db-coeffect
   datomic-conn-coeffect
   tr-coeffect
   system-coeffect
   page-state-coeffect
   params-coeffect
   body-params-coeffect])
