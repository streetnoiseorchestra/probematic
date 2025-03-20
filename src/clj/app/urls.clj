(ns app.urls
  (:import [java.net URLEncoder])
  (:require [app.config :as config]
            [reitit.core :as r]
            [ring.util.codec :as codec]
            [clojure.string :as str]))

(defn params->query-string [m]
  (codec/form-encode m))

(defn entity-id [id-key maybe-id]
  (if (map? maybe-id) (id-key maybe-id)
      maybe-id))

(defn link-helper
  ([prefix id-key maybe-id]
   (link-helper prefix id-key maybe-id "/"))
  ([prefix id-key maybe-id suffix]
   (let [real-id (entity-id id-key maybe-id)]
     (str prefix real-id suffix))))

(defn url-encode
  [string]
  (some-> string str (URLEncoder/encode "UTF-8") (.replace "+" "%20")))

(defn link-dashboard [] "/")
(def link-member (partial link-helper "/member/" :member/member-id))
(def link-member-ledger #(link-member % "/#member-ledger-panel"))
(def link-member-ledger-table #(link-member % "/#member-ledger-table"))

(def link-gig (partial link-helper "/gig/" :gig/gig-id))
(def link-song (partial link-helper "/song/" :song/song-id))

(def link-policy (partial link-helper "/insurance-policy/" :insurance.policy/policy-id))
(def link-policy-send-notifications (partial link-helper "/insurance-policy-notify/" :insurance.policy/policy-id))
(def link-policy-changes (partial link-helper "/insurance-policy-changes/" :insurance.policy/policy-id))

(defn link-policy-changes-download-excel [policy preview-type attachment-filename]
  (str
   (link-helper "/insurance-changes-excel-download/" :insurance.policy/policy-id policy)
   "?preview-type=" preview-type
   "&attachment-filename=" (url-encode attachment-filename)))

(def link-policy-changes-confirm (partial link-helper "/insurance-changes-excel/" :insurance.policy/policy-id))
(defn link-policy-table-member [policy-or-policy-id member-or-member-id]
  (link-policy policy-or-policy-id (str "/#coverages-" (entity-id :member/member-id member-or-member-id))))
(def link-poll (partial link-helper "/poll/" :poll/poll-id))
(def link-instrument (partial link-helper "/instrument/" :instrument/instrument-id))
(def link-coverage (partial link-helper "/insurance-coverage/" :instrument.coverage/coverage-id))

(def link-coverage-edit (partial link-helper "/insurance-coverage-edit/" :instrument.coverage/coverage-id))

(defn link-gigs-home [] "/gigs/")
(defn link-polls-home [] "/polls/")
(defn link-songs-home [] "/songs/")
(defn link-calendar [] "/calendar/")
(defn link-probeplan-home [] "/probeplan")
(defn link-gig-create [] "/gigs/new/")
(defn link-gig-archive [] "/gigs/archive/")
(defn link-polls-create [] "/polls/new/")
(defn link-insurance [] "/insurance/")

(defn link-insurance-survey-start [policy-id]
  (str "/insurance-survey/" policy-id "/"))

(defn link-faq-insurance-team [] "/insurance/#faq10")

(defn append-qps
  "Given a map of query parameters, return a string of query parameters (starting with ?) to append to a URL.
  nil or blank values will be omittted. If the map is empty, an empty string is returned."
  [m]
  (let [encoded
        (->> m
             (filter #(not (or
                            (when (string? (val %)) (str/blank? (val %)))
                            (nil? (val %)))))
             (into {})
             (params->query-string))]
    (if (str/blank? encoded)
      ""
      (str "?" encoded))))

(defn link-coverage-create-edit
  "The link in the create coverage flow where the instrument can be edited"
  ([policy-id instrument-id]
   (link-coverage-edit policy-id instrument-id nil))
  ([policy-id instrument-id redirect]
   (str "/insurance-coverage-create/" policy-id "/"
        (append-qps {:instrument-id instrument-id
                     :redirect redirect}))))

(defn link-coverage-create
  ([policy-id]
   (link-coverage-create policy-id nil))
  ([policy-id redirect-url]
   (str "/insurance-coverage-create/" policy-id "/" (append-qps {:redirect redirect-url}))))

(defn link-coverage-create2
  ([policy-id instrument-id]
   (link-coverage-create2 policy-id instrument-id nil))
  ([policy-id instrument-id redirect-url]
   (str "/insurance-coverage-create2/" policy-id "/" instrument-id "/"
        (append-qps {:redirect redirect-url}))))

(defn link-coverage-create3
  ([policy-id instrument-id]
   (link-coverage-create3 policy-id instrument-id nil))
  ([policy-id instrument-id redirect-url]
   (str "/insurance-coverage-create3/" policy-id "/" instrument-id "/"
        (append-qps {:redirect redirect-url}))))

(defn link-instrument-image-upload [instrument-id]
  (format "/instrument-image/%s"  instrument-id))

(defn link-file-download [path]
  (str "/nextcloud-fetch?path=" (url-encode path)))

(defn link-song-image-upload [song-id]
  (format "/song-media/%s" (str song-id)))

(defn absolute-link-gig [env gig-id]
  (str (config/app-base-url env) "/gig/" gig-id "/"))

(defn absolute-link-poll [env poll-id]
  (str (config/app-base-url env) "/poll/" poll-id "/"))

(defn absolute-link-song [env song-id]
  (str (config/app-base-url env) "/song/" song-id "/"))

(defn absolute-link-member [env member-id]
  (str (config/app-base-url env) "/member/" member-id "/"))

(defn absolute-link-member-ledger [env member-id]
  (str (absolute-link-member env member-id) "#member-ledger-table"))

(defn absolute-link-insurance-policy-table [env policy-id]
  (str (config/app-base-url env) (link-policy policy-id "/#coverages-table")))

(defn absolute-link-insurance-survey-start [env policy-id]
  (str (config/app-base-url env) (link-insurance-survey-start policy-id)))

(defn absolute-gig-answer-link-base [env]
  (str (config/app-base-url env) "/answer-link"))

(defn absolute-gig-answer-link-undo [env]
  (str (config/app-base-url env) "/answer-link-undo"))

(defn absolute-link-new-user-invite [env invite-code]
  (str (config/app-base-url env) "/invite-accept?code=" invite-code))

(defn absolute-link-instrument-image-full [env instrument-id image-id]
  (format "%s/instrument-image/%s/%s?mode=full" (config/app-base-url env) instrument-id image-id))

(defn absolute-link-instrument-image-thumbnail [env instrument-id image-id]
  (format "%s/instrument-image/%s/%s?mode=thumbnail" (config/app-base-url env) instrument-id image-id))

(defn absolute-link-song-image [env song-id filename]
  (format "%s/song-media/%s/%s" (config/app-base-url env) song-id filename))

(defn absolute-link-instrument-public [env instrument-id]
  (format "%s/instrument-public/%s" (config/app-base-url env) instrument-id))

(defn link-logout [] "/logout")
(defn link-login [] "/login")
(defn absolute-link-login [env]
  (str (config/app-base-url env) "/login"))

(defn url-for
  "Return a url string given a route name and optional arg and query params. This
  functions uses the router for the current request bound to *request*.

  If you need to use a different router then temporary bind *router* before
  calling this function."
  ([req name-or-path]
   (url-for req name-or-path nil nil))
  ([req name-or-path args]
   (url-for req name-or-path args nil))
  ([req name-or-path args query-params]
   (if (string? name-or-path)
     name-or-path
     (-> (::r/router req)
         (r/match-by-name name-or-path args)
         (r/match->path query-params)))))

(comment
  ;;
  )
