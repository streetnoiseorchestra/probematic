(ns app.datastar
  (:refer-clojure :exclude [get])
  (:import (java.time Instant Duration))
  (:require
   [app.urls :as urls]
   [chime.core :as chime]
   [app.html :as html]
   [camel-snake-kebab.core :as csk]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [jsonista.core :as j]
   [datomic.api :as d]
   [integrant.core :as ig]
   [app.util :as util]
   [buddy.core.codecs :as codecs]
   [clojure.core.async :as a]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
   [clojure.string :as str]))

(defn ->signals [m]
  (j/write-value-as-string m))

#_(def merge-fragments! d*/merge-fragments!)
#_(def merge-fragment! d*/merge-fragment!)
(def merge-signals! d*/merge-signals!)

(defn digest
  "Digest function based on Clojure's hash."
  [data]
  (codecs/bytes->b64-str (.getBytes (str (hash data)))))

(defn throttle [<in-ch msec]
  (let [;; No buffer on the out-ch as the in-ch should be buffered
        <out-ch (a/chan)]
    (util/thread
      (util/while-some [event (a/<!! <in-ch)]
                       (a/>!! <out-ch event)
                       (Thread/sleep ^long msec)))
    <out-ch))

(def !page-state (atom {}))

(defn state-transact! [req f]
  (if-let [tab-id (-> req :body-params :tab-id)]
    (swap! !page-state update tab-id (fn [state]
                                       (-> state
                                           (f)
                                           (assoc ::modified (System/currentTimeMillis)))))

    (throw (ex-info "No tab-id in request" {}))))

(defn init-tab-state! [<ch tab-id]
  (swap! !page-state assoc tab-id {::created (System/currentTimeMillis)})
  (add-watch !page-state tab-id (fn [watch-key _ _ _]
                                  (when-not (a/>!! <ch [])
                                    (remove-watch !page-state watch-key)))))

(defn remove-tab-state!
  [tab-id]
  (swap! !page-state dissoc tab-id)
  (remove-watch !page-state tab-id))

(def STALE-THRESHOLD-HOURS 1)

(defn stale? [now created modified]
  (> (- now (or modified created)) (* STALE-THRESHOLD-HOURS 3600000)))

(defn clean-stale-page-state
  "Removes tab-ids that are stale, where stale is defined as not having been modified or created in the last 24 hours."
  [page-state]
  (let [now (System/currentTimeMillis)]
    (reduce-kv (fn [acc tab-id {:keys [::created ::modified]}]
                 (if (stale? now created modified)
                   (dissoc acc tab-id)
                   acc))
               page-state
               page-state)))

(defn clean-stale-watches!
  "Removes watches for tab-ids that are no longer in the page state."
  []
  (let [watches       (-> !page-state .getWatches keys)
        stale-watches (remove #(clojure.core/get @!page-state %) watches)]
    (doseq [watch-key stale-watches]
      (remove-watch !page-state watch-key))))

(defn start-clean-page-state-job
  "Starts a job that cleans stale page state every 10 seconds."
  []
  (chime/chime-at (chime/periodic-seq (Instant/now) (Duration/ofSeconds 60))
                  (fn [_]
                    (swap! !page-state clean-stale-page-state)
                    (clean-stale-watches!))))

(comment
  (reset! !page-state {})
  (name (keyword (str (random-uuid))))
  @!page-state
  (swap! !page-state update "1a874961-16c7-40d8-9b44-b83273a82afa" assoc ::created 0)
  (swap! !page-state update "1a874961-16c7-40d8-9b44-b83273a82afa" dissoc :current-edit-id)
  (swap! !page-state dissoc "c9222db8-78ce-4919-9f2f-28bfc1aac41f")
  (let [watch-key :fake]
    (add-watch !page-state watch-key (fn [watch-key _ _ _])))
  (-> !page-state .getWatches keys)
  (clean-stale-watches!)

  (swap! !page-state clean-stale-page-state)
  ;;
  )

(defn wrap-req [req tab-id]
  (-> req
      (assoc :request-method :get)
      (assoc :page-state (clojure.core/get @!page-state tab-id {}))
      (assoc :db (d/db (:datomic-conn req)))))

(defn render-handler [render-fn & {:keys [on-close on-open wrap-req] :or {wrap-req wrap-req} :as _opts}]
  (fn handler [req]
    (assert (::refresh-mult req))
    (let [tab-id  (str (random-uuid))
          ;; Dropping buffer is used here as we don't want a slow handler
          ;; blocking other handlers. Mult distributes each event to all
          ;; taps in parallel and synchronously, i.e. each tap must
          ;; accept before the next item is distributed.
          <ch     (a/tap (::refresh-mult req) (a/chan (a/dropping-buffer 1)))
          ;; Ensures at least one render on connect
          _       (a/>!! <ch :refresh-event)
          ;; poison pill for work cancelling
          <cancel (a/chan)]
      (hk-gen/->sse-response  req
                              {:headers {"X-Accel-Buffering" "no"
                                         "Cache-Control"     "no-cache"}
                               hk-gen/on-open
                               (fn hk-on-open [sse-gen]
                                 (init-tab-state! <ch tab-id)
                                 (util/thread
                                   (try
                                     (d*/merge-signals! sse-gen (j/write-value-as-string {:tab-id tab-id}))
                                     (loop [last-view-hash (get-in req [:headers "last-event-id"])]
                                       (a/alt!!
                                         [<cancel] (do (a/close! <ch)
                                                       (a/close! <cancel))
                                         [<ch]     (let [new-view      (render-fn (wrap-req req tab-id))
                                                         new-view-hash (digest new-view)]
                                                     ;; (tap> [:render-change :new-view-hash new-view-hash  :last-view-hash last-view-hash new-view])
                                                     ;; only send an event if the view has changed
                                                     (when (not= last-view-hash new-view-hash)
                                                       (d*/merge-fragment! sse-gen (html/->str new-view) {d*/id                  new-view-hash
                                                                                                          d*/use-view-transition true}))
                                                     (recur new-view-hash))
                                         ;; we want work cancelling to have higher priority
                                         :priority true))
                                     (catch Exception e
                                       (tap> [:sse-resp-thread-ex e]))))

                                 (when on-open (on-open req)))
                               hk-gen/on-close (fn hk-on-close [_ _]
                                                 (remove-tab-state! tab-id)
                                                 (try
                                                   (a/>!! <cancel :cancel)
                                                   (when on-close (on-close req))
                                                   (catch Exception e
                                                     (tap> [:sse-response-on-close-ex e])
                                                     nil)))}))))
(defonce ^:private refresh-ch_ (atom nil))

(defn refresh-all! [& args]
  (when-let [<refresh-ch @refresh-ch_]
    (a/>!! <refresh-ch (or args []))))

(defn- install-report-queue
  "On a separate thread, take values from the `tx-report-queue` over `conn` and
  put them onto channel `c`. "
  [conn c]
  (a/thread
    (try
      (let [queue (d/tx-report-queue conn)]
        (while true
          (let [report (.take queue)]
            (a/>!! c report))))
      (catch InterruptedException _)
      (catch Exception e
        (tap> [:datomic-tx-queue-ex e])
        (throw e)))))

(defn react-on-datomic-tx! [{:keys [db-conn buffer-size]
                             :or   {buffer-size 1}}]
  (assert db-conn)
  (let [tx-report-ch (a/chan (a/sliding-buffer buffer-size))]
    (install-report-queue db-conn tx-report-ch)
    {:db-conn      db-conn
     :tx-report-ch tx-report-ch}))

(defn stop-react-datomic-tx [{:keys [db-conn tx-report-ch]}]
  (a/close! tx-report-ch)
  (d/remove-tx-report-queue db-conn))

(defn start-refresh-mult [db-conn {:keys [max-refresh-ms on-refresh]
                                   :or   {max-refresh-ms 100}}]
  (assert db-conn)
  (let [<refresh-ch  (a/chan (a/dropping-buffer 1))
        _            (reset! refresh-ch_ <refresh-ch)
        refresh-mult (-> (throttle <refresh-ch max-refresh-ms)
                         (a/pipe
                          (a/chan 1
                                  (map
                                   (fn [args]
                                     ;; (tap> :render)
                                     ;; cache is only invalidate at most
                                     ;; every X msec and only if state has change
                                     ;; (cache/invalidate-cache!)
                                     ;; run on-refresh
                                     (when (and on-refresh (seq args))
                                       (apply on-refresh args))
                                     ;; No point sending the args past here
                                     :refresh-event))))
                         a/mult)
        datomic      (react-on-datomic-tx! {:db-conn db-conn})]

    (a/go-loop [ch (:tx-report-ch datomic)]
      (when-let [tx (a/<! ch)]
        (refresh-all! tx)
        (recur ch)))

    {::<refresh-ch    <refresh-ch
     ::chime-schedule (start-clean-page-state-job)
     ::refresh-mult   refresh-mult
     ::datomic        datomic}))

(defn stop-refresh-mult [{::keys [datomic <refresh-ch chime-schedule]}]
  (when chime-schedule
    (.close chime-schedule))
  (when datomic
    (stop-react-datomic-tx datomic))
  (when <refresh-ch
    (a/close! <refresh-ch)
    (reset! refresh-ch_ nil)))

(defn datastar-refresh-interceptor [sys]
  (let [refresh-mult (get-in sys [:datastar-refresh-mult ::refresh-mult])]
    (assert refresh-mult)
    {:name  ::refresh-ch-interceptor
     :enter (fn [ctx]
              (assoc-in ctx [:request ::refresh-mult] refresh-mult))}))

(defn respond-and-close [request on-open & {:keys [on-close]}]
  (hk-gen/->sse-response request
                         {hk-gen/on-open  (fn [sse-gen]
                                            (on-open sse-gen)
                                            (d*/close-sse! sse-gen))
                          hk-gen/on-close on-close}))

;; I must not fragment. Fragmentation is the simplicity-killer. ... LITANY.md
#_(defn respond-fragment [request fragment]
    (respond-and-close request (fn [sse-gen]
                                 (d*/merge-fragment! sse-gen (html/->str fragment)))))

(defn respond-signals
  ([request & {:keys [merge remove]}]
   (respond-and-close request (fn [sse-gen]
                                (when merge
                                  (d*/merge-signals! sse-gen (->signals merge)))
                                (when remove
                                  (d*/remove-signals! sse-gen remove))))))

(defn respond [request on-open  & {:keys [on-close]}]
  (hk-gen/->sse-response request {hk-gen/on-open  on-open
                                  hk-gen/on-close on-close}))

(defmethod ig/init-key ::refresh-mult
  [_ sys]
  (start-refresh-mult (-> sys :datomic :conn) {}))

(defmethod ig/halt-key! ::refresh-mult
  [_ i]
  (stop-refresh-mult i))

(def camelCaseMapper
  (j/object-mapper
   {:encode-key-fn csk/->camelCaseString
    :decode-key-fn csk/->kebab-case-keyword}))

(defn datastar-params-interceptor []
  {:name  ::datastar-params-interceptor
   :enter (fn [ctx]
            (if (= :get (-> ctx :request :request-method))
              (assoc-in ctx [:request :datastar-params]
                        (when-let [dps (get-in ctx [:request :params "datastar"])]
                          (j/read-value dps camelCaseMapper)))
              ctx))})

;; ------------------------------------------------------------
;; Helpers

(def ActionOptsSchema
  [:map {:closed true}
   [:content-type {:optional true :default :json}
    [:enum :json :form]]
   [:include-local {:optional true :default false}
    :boolean]
   [:selector {:optional true :default nil}
    [:maybe :string]]
   [:headers {:optional true}
    [:maybe [:map-of :string :any]]]
   [:open-when-hidden {:optional true :default false}
    :boolean]
   [:retry-interval {:optional true :default 1000}
    :int]
   [:retry-scaler {:optional true :default 2}
    :double]
   [:retry-max-wait-ms {:optional true :default 30000}
    :int]
   [:retry-max-count {:optional true :default 10}
    :int]
   [:abort {:optional true}
    [:maybe :any]]])

(def Actions [:enum :get :put :patch :post :delete])

(>defn action
       ([method url]
        [:keyword :string => :string]
        (action method url nil))
       ([method url opts]
        [:keyword :string [:maybe ActionOptsSchema] => :string]
        (if opts
          (str "@" (name method) "('" url "', " (j/write-value-as-string opts camelCaseMapper) ")")
          (str "@" (name method) "('" url "')"))))

(def get (partial action :get))
(def put (partial action :put))
(def patch (partial action :patch))
(def post (partial action :post))
(def delete (partial action :delete))

(>defn expr [& stmts]
       [[:* [:maybe :string]] => :string]
       (str/join "; " (filter identity stmts)))

(>defn assign [signal-name value]
       [:string :any => :string]
       (format "$%s=%s" signal-name (j/write-value-as-string value)))

(defn dispatch
  ([req cmd]
   (action :post (urls/url-for req cmd)))
  ([req cmd opts]
   (action :post (urls/url-for req cmd) opts)))
