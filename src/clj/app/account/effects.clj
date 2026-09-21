(ns app.account.effects
  "Side-effecting account profile persistence.

  Profile validation remains in [[app.account.actions]].
  This namespace owns uploaded file preparation, the atomic Datomic write, and
  optional identity-provider synchronization intent, committed with the profile."
  (:require
   [app.datomic :as datomic]
   [app.filestore.controller :as filestore.controller]
   [app.jobs.identity :as identity-jobs]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.write-runner :as writer]
   [babashka.fs :as bfs]
   [datomic.api :as d]))

(defn- optional-attribute-tx [entity attr value]
  [(if (some? value)
     [:db/add entity attr value]
     [:db/retract entity attr])])

(defn- profile-tx [member-id profile]
  (let [member-ref [:member/member-id member-id]]
    (into [{:db/id           member-ref
            :member/name     (:name profile)
            :member/email    (:email profile)
            :member/username (:username profile)}]
          (concat
           (optional-attribute-tx member-ref :member/nick
                                  (not-empty (:nick profile)))
           (optional-attribute-tx member-ref :member/phone
                                  (not-empty (:phone profile)))
           (optional-attribute-tx member-ref :member/current-status
                                  (not-empty (:current-status profile)))
           (optional-attribute-tx member-ref :member/date-of-birth
                                  (not-empty (:date-of-birth profile)))))))

(defn- avatar-file-eids [db avatar-eid]
  (when avatar-eid
    (let [avatar (d/pull db
                         '[{:image/source-file [:db/id]}
                           {:image/renditions
                            [{:image/source-file [:db/id]}]}]
                         avatar-eid)]
      (into []
            (comp
             (keep #(get-in % [:image/source-file :db/id]))
             (distinct))
            (cons avatar (:image/renditions avatar))))))

(defn- retract-file-tx [file-eids]
  (mapv #(vector :db/retractEntity %) file-eids))

(defn- avatar-change-tx
  [member-id current-avatar-eid avatar-upload
   avatar-removed? stored-avatar obsolete-file-eids]
  (when (or avatar-upload avatar-removed?)
    (let [member           [:member/member-id member-id]
          new-avatar       (:image-tempid stored-avatar)
          file-retractions (retract-file-tx obsolete-file-eids)]
      (if avatar-upload
        (cond-> (into [[:db/add member :member/avatar new-avatar]]
                      file-retractions)
          current-avatar-eid
          (conj [:db/retractEntity current-avatar-eid]))
        (cond-> (into [[:db/retract member :member/avatar]
                       [:db/retract member :member/avatar-template]]
                      file-retractions)
          current-avatar-eid
          (conj [:db/retractEntity current-avatar-eid]))))))

(defn- save-tx
  [db {:keys [member-id profile avatar-upload sync-keycloak?] :as params} stored-avatar]
  (let [member             (d/entity db [:member/member-id member-id])
        current-avatar-eid (some-> member :member/avatar :db/id)
        keycloak-id        (:member/keycloak-id member)
        profile-data       (vec (concat
                                 (profile-tx member-id profile)
                                 (:tx-data stored-avatar)
                                 (avatar-change-tx member-id current-avatar-eid
                                                   (when-not (:avatar-removed? profile) avatar-upload)
                                                   (:avatar-removed? profile) stored-avatar
                                                   (avatar-file-eids db current-avatar-eid))))]
    (into [[:member.invite/transact-profile-if-not-in-flight member-id profile-data]]
          (when (and sync-keycloak? keycloak-id)
            (log-dispatch/intent-tx
             [(identity-jobs/job params member-id keycloak-id {:metadata? true})])))))

(defn prepare-profile!
  "Stores avatar bytes and returns profile parameters for [[save-prepared-profile!]].

  Deletes the multipart tempfile on success and failure. Does not write Datomic.
  Call outside the application writer. A later rejected save can leave
  unreferenced content-addressed files; preparation is not a business commit.

  | Key | Description |
  | --- | --- |
  | `:profile` | Normalized profile, including `:avatar-removed?` |
  | `:avatar-upload` | Server-parsed multipart file and metadata, if present |"
  [system {:keys [profile avatar-upload] :as params}]
  (let [tempfile (:tempfile avatar-upload)]
    (try
      (assoc params ::stored-avatar
             (when (and avatar-upload (not (:avatar-removed? profile)))
               (filestore.controller/store-avatar!
                {:filestore (:filestore system)}
                {:file-name (:filename avatar-upload)
                 :file      tempfile
                 :mime-type (:mime-type avatar-upload)})))
      (finally
        (when tempfile
          (bfs/delete-if-exists tempfile))))))

(defn save-prepared-profile!
  "Commits parameters returned by [[prepare-profile!]] using writer-current state.

  Does not read the upload tempfile. Identity synchronization intent commits
  with the profile.
  Returns `{:status :saved :tx-result report}` only after the commit."
  [system params]
  (when-not (contains? params ::stored-avatar)
    (throw (ex-info "Profile upload has not been prepared" {})))
  (let [conn     (-> system :datomic :conn)
        persist! (fn []
                   (datomic/transact
                    conn
                    {:tx-data (save-tx (d/db conn) params (::stored-avatar params))
                     :audit   {:audit/action :app.account.actions/save-profile
                               :audit/origin :app.origin/browser
                               :audit/user   [:member/member-id (:member-id params)]}}))]
    (assert conn "profile persistence requires a Datomic connection")
    (let [tx-result (if-let [control (get-in system [:frame-loop :write-runner])]
                      (writer/call! control persist!)
                      (persist!))]
      {:status :saved :tx-result tx-result})))

(defn save-profile!
  "Prepares an upload and synchronously commits one validated profile.

  See [[prepare-profile!]] and [[save-prepared-profile!]] for the separate
  preparation and persistence boundaries. Avatar changes are last-write-wins.

  | Key | Description |
  | --- | --- |
  | `:member-id` | Authenticated member UUID |
  | `:profile` | Validated normalized profile |
  | `:avatar-upload` | Optional server-parsed multipart file and metadata |
  | `:sync-keycloak?` | Request identity synchronization after persistence |"
  [system params]
  (save-prepared-profile! system (prepare-profile! system params)))

(defn discard-upload-fx
  "Deletes a rejected multipart upload tempfile, if present."
  [_coeffects _context tempfile]
  (when tempfile
    (bfs/delete-if-exists tempfile)))

(defn save-profile-fx
  "Persists a validated profile save."
  [_coeffects {:keys [system request]} params]
  (if-let [prepared (::prepared-profile request)]
    (save-prepared-profile! system (merge prepared params))
    (save-profile! system params))
  nil)
