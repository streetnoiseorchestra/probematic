(ns app.account.test-support
  (:require
   [app.settings.views-test-support :as settings-support]
   [app.test-common :as tc]
   [clojure.string :as str]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defonce ^:private default-member-system
  (delay (tc/new-system "account-view-request")))

(defn resolve-public [symbol]
  (try
    (requiring-resolve symbol)
    (catch java.io.FileNotFoundException _exception
      nil)))

(defn public-fn [symbol]
  (some-> (resolve-public symbol) deref))

(defn public-value [symbol]
  (some-> (resolve-public symbol) deref))

(defn request
  ([]
   (request {}))
  ([overrides]
   (let [{:keys [conn member-id]} @default-member-system]
     (merge {:tr         settings-support/legacy-tr
             :system     {:env {:name "Test Instance"}}
             :db         (d/db conn)
             :session    {:session/member {:member/member-id member-id}}
             :page-state {}
             ::r/router  router}
            overrides))))

(defn translation-keys [hiccup]
  (settings-support/translation-keys hiccup))

(defn translation-node [message-id hiccup]
  (some #(when (and (vector? %)
                    (= :i18n/tr (first %))
                    (= message-id (second %)))
           %)
        (tree-seq coll? seq hiccup)))

(defn elements [tag hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter vector?)
       (filter #(= tag (first %)))))

(defn attrs [element]
  (when (and (vector? element)
             (map? (second element)))
    (second element)))

(defn element-by-id [id hiccup]
  (some #(when (= id (:id (attrs %))) %) (tree-seq coll? seq hiccup)))

(defn child-tags [element]
  (mapv first (filter vector? (l/children element))))

(defn action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(defn actions-in [hiccup]
  (into #{}
        (comp
         (filter vector?)
         (keep attrs)
         (mapcat vals)
         (keep action-keyword))
        (tree-seq coll? seq hiccup)))

(defn native-control-summary [tag hiccup]
  (mapv #(select-keys (attrs %)
                      [:id :name :type :value :form :required :checked
                       :data-bind :data-show :data-preserve-attr])
        (elements tag hiccup)))

(defn class-tokens [element]
  (some-> element attrs :class (str/split #"\s+") set))
