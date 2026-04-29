(ns app.gigs.answer-link.views
  (:require
   [app.auth :as auth]
   [app.gigs.answer-link.service :as service]
   [app.html :as html]
   [app.i18n :as i18n]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.urls :as urls]))

(defn- tr [req]
  (or (:tr req)
      (i18n/tr-from-req req)))

(defn- redirect [url]
  {:status 302
   :headers {"Location" url}
   :body ""})

(defn- page-shell [req & body]
  (apply ui2/standalone-page
         {:title       "SNOrga"
          :description ((tr req) [:gig/answer-link-submitted])}
         body))

(defn- redirect-to-gig-snippet [gig]
  [:script
   (html/raw
    (str "window.setTimeout(function(){ window.location.assign("
         (pr-str (urls/link-gig gig))
         "); }, 1000);"))])

(defn- success-page
  ([req]
   (success-page req nil))
  ([req gig]
   (let [tr (tr req)]
     (apply page-shell
            req
            (cond-> [[:header
                      [:p "SNOrga"]
                      [:h1 (tr [:gig/answer-link-submitted])]]]
              gig (conj (redirect-to-gig-snippet gig)))))))

(defn- invalid-page [req]
  (let [tr (tr req)]
    (page-shell
     req
     [:header {:class "danger"}
      [:p "SNOrga"]
      [:h1 (tr [:email/invite-expired])]])))

(defn answer-link [req]
  (try
    (if-let [{:keys [gig]} (service/submit-answer! req)]
      (if (auth/get-current-member req)
        (redirect (urls/link-gig gig))
        (success-page req gig))
      (invalid-page req))
    (catch Throwable e
      (if (#{:answer-link-invalid :code-expired} (-> e ex-data :reason))
        (invalid-page req)
        (throw e)))))

(defn- preview-gig [db]
  (first (or (seq (q/gigs-future db))
             (seq (q/find-all-gigs db)))))

(defn answer-link-preview [req]
  (success-page req (some-> req :db preview-gig)))
