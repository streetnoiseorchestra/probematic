(ns app.discourse
  (:require
   [app.config :as config]
   [app.datomic :as d]
   [app.gigs.domain :as domain]
   [app.queries :as q]
   [app.ui :as ui]
   [app.urls :as url]
   [clojure.set :as set]
   [clojure.string :as str]
   [app.datomic.shim :as datomic]
   [jsonista.core :as j]
   [martian.core :as martian]
   [martian.httpkit :as martian-http]
   [medley.core :as m]
   [org.httpkit.client :as client]
   [selmer.parser :as selmer]))

(defn add-authentication-header [api-key username]
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (-> ctx
                (assoc-in [:request :headers "Content-Type"] "multipart/form-data;")
                (assoc-in [:request :headers "Api-Key"] api-key)
                (assoc-in [:request :headers "Api-Username"] username)))})

(def url-discourse-open-api "https://docs.discourse.org/openapi.json")

(defn list-users [m]
  (let [{:keys [status body] :as r}
        @(martian/response-for m :admin-list-users {:flag "active" :show_emails true})]
    (if (= 200 status)
      (j/read-value body j/keyword-keys-object-mapper)
      {:error r})))
(defn sync-avatars! [{:keys [env conn]}]
  (let [db (datomic/db conn)
        {:keys [api-key username forum-url]} (:discourse env)
        m (martian-http/bootstrap-openapi url-discourse-open-api {:server-url forum-url
                                                                  :interceptors (concat martian/default-interceptors
                                                                                        [(add-authentication-header api-key username)]
                                                                                        [martian-http/perform-request])})
        user-list (list-users m)
        members (->>
                 (d/find-all db :member/member-id [:member/name :member/email :member/member-id])
                 (map first)
                 (map #(update % :member/email str/lower-case)))
        joined (set/join user-list members {:email :member/email})
        txs (->> joined
                 (map #(select-keys % [:member/member-id :avatar_template :id :username]))
                 (map #(update % :id str))
                 (map #(set/rename-keys % {:avatar_template :member/avatar-template :id :member/discourse-id :username :member/nick})))]
    (d/transact conn {:tx-data txs})))
(defn wrap-auth [req {:keys [discourse] :as env}]
  (-> req
      (assoc-in [:headers "Api-Key"] (:api-key discourse))
      (assoc-in [:headers "Api-Username"] (:username discourse))))

(defn wrap-api-url [req {:keys [discourse]}]
  (update-in req [:url] (fn [path] (str (:forum-url discourse) path))))

(defn request! [env req]
  (let [{:keys [status body] :as r} @(client/request
                                      (-> req
                                          (wrap-auth env)
                                          (wrap-api-url env)))]

    (if (= 200 status)
      (j/read-value body j/keyword-keys-object-mapper)
      (throw (ex-info "Discourse Error" {:resp r})))))

(defn markdown-quote [v]
  (when-not (str/blank? v)
    (->> (str/split-lines v)
         (map (fn [line]
                (str "> " line)))
         (str/join "\n"))))

(defn gig->markdown-post [env {:gig/keys [attendance-summary status planned-songs title more-details location contact leader date end-date] :as gig}]
  (selmer/render
   "
<!--- DO NOT EDIT THIS POST,

HEY YOU


.. YEA.. YOU!


DONT EDIT THIS POST!!


WHY?


SNORGA AUTOMATICALLY UPDATES IT.


IF YOU WANT TO CHANGE SOMETHING..

EDIT IT IN SNORGA



SO... STOP EDITING IT NOW


.... k thx













WHY ARE YOU STILL HERE?







GO TO SNORGA!!


{{probematic-link}}

--->
**Bestätigt: {{confirmed}}**


**Datum:** {{time}} {{date}}

**Ort:** {{location}}

{% if details|not-empty %}**Programm:**
{{details|safe}}{% endif %}

{% if not leader|empty? %}**Gig-Master:** {{leader}}{% endif %}

{% if counts|not-empty %}
**Attendance**:
{% for item in counts %}{{item.icon}} {{item.value}}
{% endfor %}
{% endif %}
{% if planned-songs|not-empty %}
**{{planned-songs-title}}**:

{% for item in planned-songs %}{{item.position}}. [{{item.label}}]({{item.href}}) {% if item.extra %}{{item.extra}}{% endif %}
{% endfor %}
{% endif %}


[**:arrow_right: More info on SNOrga**]({{probematic-link}})

"
   {:confirmed
    (case status
      :gig.status/confirmed ":gig_confirmed:"
      :gig.status/unconfirmed ":gig_unconfirmed:"
      :gig.status/cancelled ":gig_cancelled:")
    :probematic-link (url/absolute-link-gig env (:gig/gig-id gig))
    :date (ui/gig-date-plain gig)
    :time (ui/gig-time gig)
    :location location
    :details  (markdown-quote more-details)
    :leader leader
    :planned-songs  planned-songs
    :planned-songs-title (if (domain/setlist-gig? gig) "Setlist" "Probeplan")
    :counts  (->> attendance-summary
                  (map (fn [[plan count]]
                         (when-let [icon (get
                                          {:plan/no-response "**—**"
                                           :plan/definitely ":gruener_kreis:"
                                           :plan/probably ":gruener_ringel:"
                                           :plan/unknown ":fragezeichen:"
                                           :plan/probably-not ":roter_quadratischer_umriss:"
                                           :plan/definitely-not ":rotes_quadrat:"
                                           :plan/not-interested ":schwarz_kreuz:"} plan)]
                           {:icon icon
                            :value (str count)})))
                  (remove nil?))}))

(defn topic-for-gig [{:keys [env]} gig-id]
  (try
    (request! env
              {:method :get
               :url (format "/t/external_id/%s.json" gig-id)})
    (catch Throwable e
      (if (= 404 (-> (ex-data e) :resp :status))
        nil
        (throw e)))))

(defn first-post-for-topic [topic]
  (m/find-first (fn [{:keys [post_number]}]
                  (= 1 post_number))
                (-> topic
                    :post_stream
                    :posts)))

(defn update-post-for-gig [env gig post-id]
  (request! env
            {:method :put
             :url (format "/posts/%s.json" post-id)
             :headers {"content-type" "application/json"}
             :body (j/write-value-as-string
                    {:raw (gig->markdown-post env gig)
                     :post_type "small_action"
                     :edit_reason "something changed in snorga"})})
  nil)

(defn reset-bump-date! [env topic-id]
  (request! env
            {:method :put
             :url (format "/topics/bulk")
             :headers {"content-type" "application/x-www-form-urlencoded; charset=UTF-8"
                       "accept" "application/json"}
             :form-params {"topic_ids[]" topic-id
                           "operation[type]" "reset_bump_dates"}}))
(defn delete-topic! [env topic-id]
  (request! env
            {:method :delete
             :url (format "/t/%s.json" topic-id)
             :headers {"content-type" "application/json"}}))

(defn format-topic-title [gig]
  (str (:gig/title gig) " " (ui/gig-date-plain gig)))

(defn category-for [dev-mode? {:gig/keys [gig-type gig-id]}]
  (if-let [[_ v] (find {:gig.type/probe 7
                        :gig.type/extra-probe 7
                        :gig.type/meeting 9
                        :gig.type/gig 6} gig-type)]

    (if dev-mode?
      4                                 ;; technik admin
      ;; 21; PROBEMATIC beta test
      v)
    (throw (ex-info "Unknown discourse category for gig type" {:gig-type gig-type
                                                               :gig-id gig-id}))))

(defn update-topic-for-gig [env gig topic]
  (let [topic-title (format-topic-title gig)
        category-id (category-for (config/dev-mode? env) gig)]
    (when (or
           (not= category-id (:category_id topic))
           (not= topic-title (:title topic)))
      (request! env {:method :put
                     :url (format "/t/-/%s.json" (:id topic))
                     :headers {"content-type" "application/json"}
                     :body (j/write-value-as-string
                            {:title topic-title
                             :category_id category-id})}))))

(defn form-params-for-gig [env {:gig/keys [gig-id] :as gig}]
  {:title (format-topic-title gig)
   :raw (gig->markdown-post env gig)
   :category (category-for (config/dev-mode? env) gig)
   :embed_url (url/absolute-link-gig env gig-id)
   :external_id gig-id})

(defn summarize-attendance [gig {:keys [db]}]
  (assert db)
  (assert gig)
  (assoc gig :gig/attendance-summary
         (->> (q/attendances-for-gig db (:gig/gig-id gig))
              (remove #(nil? (:attendance/plan %)))
              (group-by :attendance/plan)
              (m/map-vals count))))

(defn planned-songs [gig {:keys [env db]}]
  (assoc gig :gig/planned-songs
         (map (fn [{:song/keys [title song-id] :keys [emphasis position]}]
                {:href (url/absolute-link-song env song-id)
                 :label title
                 :position (when position (inc position))
                 :extra (when (= :probeplan.emphasis/intensive emphasis) (str " (intensive)"))})
              (q/planned-songs-for-gig db (:gig/gig-id gig)))))
(defn create-topic-for-gig!
  "Creates a new topic for the gig, returns the topic id."
  [{:keys [env db] :as sys} gig-id]
  (let [gig  (-> (q/retrieve-gig db gig-id)
                 (summarize-attendance sys)
                 (planned-songs sys))
        topic-id
        (str (:topic_id (request! env
                                  {:method :post
                                   :url "/posts.json"
                                   :form-params (form-params-for-gig env gig)})))]
    (datomic/transact (-> sys :datomic :conn) {:tx-data [[:db/add (d/ref gig)
                                                          :forum.topic/topic-id topic-id]]})))

(defn we-own-topic? [our-username topic]
  (= (-> topic :details :created_by :username) our-username))

(defn update-topic-for-gig!
  [{:keys [env db] :as sys} gig-id takeover-topic?]
  (assert db)
  (assert env)
  (assert gig-id)
  (let [gig (-> (q/retrieve-gig db gig-id)
                (summarize-attendance sys)
                (planned-songs sys))
        topic (topic-for-gig {:env env} (:gig/gig-id gig))]
    (cond
      (and (not topic) takeover-topic?)
      (create-topic-for-gig! sys gig-id)
      ;; create topic
      (or (we-own-topic? (-> env :discourse :username) topic) takeover-topic?)
      (when-let [post-id (:id (first-post-for-topic topic))]
        (update-topic-for-gig env gig topic)
        (update-post-for-gig env gig post-id)
        (reset-bump-date! env (:id topic)))
      :else nil)))

(defn parse-topic-id [v]
  (if-let [[_ topic-id] (re-matches #".*/(\d+)+ *$" v)]
    topic-id
    (when-let [[_ topic-id] (re-matches #"(\d+)+ *$" v)]
      topic-id)))

(defn should-delete-topic? [our-username topic]
  (let [{:keys [highest_post_number details]} topic
        {:keys [username]} (:created_by details)]
    (and
     ;; we created it
     (= username our-username)
     ;; only one post.. our post!
     (= highest_post_number 1))))

(defn maybe-delete-topic-for-gig! [{:keys [env db] :as sys} gig-id]
  (let [topic (topic-for-gig sys gig-id)]
    (when (and topic (should-delete-topic? (-> env :discourse :username) topic))
      (delete-topic! env (:id topic)))))

(comment
  (parse-topic-id "/3009")
  (parse-topic-id "3009")
  (parse-topic-id "https://forum.streetnoise.at/t/probe-22-2-2023-abstimmung-programm-2023/2729")
  ;;
  )

(comment

  (do
    (require '[integrant.repl.state :as state])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def env (-> state/system :app.ig/env))
    (def db  (datomic/db conn)) ;; rcf
    (let [{:keys [api-key username forum-url]} (:discourse env)]
      (def m (martian-http/bootstrap-openapi url-discourse-open-api {:server-url "https://forum.streetnoise.at"
                                                                     :interceptors (concat martian/default-interceptors
                                                                                           [(add-authentication-header api-key username)]
                                                                                           [martian-http/perform-request])})))) ;; rcf

  (sort-by first (martian/explore m))
  (martian/explore m :admin-list-users)
  (d/find-all db :member/member-id [:member/name :member/email :member/member-id :member/avatar-template :member/discourse-id :member/nick])

  (def g (q/retrieve-gig db "01860302-7e21-8c75-915a-ab04fc38d0c0"))
  (def g2 (q/retrieve-gig db "01860c2a-5c88-8b92-9c42-0c6a1f42ae5c"))
  (def g3 (q/retrieve-gig db "01860c2a-5c88-8b92-9c42-0c6a1f42ae65"))
  (:id (first-post-for-topic (topic-for-gig {:env env} g)))

  (should-delete-topic? (-> env :discourse :username)
                        (topic-for-gig {:env env} (:gig/gig-id g3)))

  ;;
  )
