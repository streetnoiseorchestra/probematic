(ns app.features
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def available #{:feat/reminders})

(def features (atom #{}))

(defn feature?
  [kw]
  (some #{kw} @features))

(defn enable-feat! [f]
  (swap! features conj f))

(defn disable-feat! [f]
  (swap! features disj f))

(defn list-features-handler [_req]
  {:status 200
   :body
   (merge
    (into {}
          (map (fn [f] [f false])
               (set/difference available @features)))
    (into {}
          (map (fn [f] [f true])
               @features)))})

(defn coerce-to-kw [f]
  (when (and (some? f) (not (str/blank? f)))
    (let [f-kw (keyword "feat" f)]
      (when (contains? available f-kw)
        f-kw))))

(defn enable-feature-handler [req]
  (if-let [f-kw (coerce-to-kw (-> req :body-params :feature))]
    (do
      (enable-feat! f-kw)
      {:status 200
       :body {:feature (name f-kw) :value true}})
    {:status 404
     :body {:msg "no such flag"}}))

(defn disable-feature-handler [req]
  (if-let [f-kw (coerce-to-kw (-> req :body-params :feature))]
    (do
      (disable-feat! f-kw)
      {:status 200
       :body {:feature (name f-kw) :value false}})
    {:status 404
     :body {:msg "no such flag"}}))
