(ns app.account.effects
  "Side-effecting account profile persistence.

  Profile validation remains in [[app.account.actions]].
  This namespace owns uploaded file preparation, the atomic Datomic write, and
  the optional identity-provider synchronization. With durable jobs enabled, its
  intent commits with the profile; otherwise synchronization runs after commit."
  (:require
   [app.filestore.controller :as filestore.controller]
   [app.members.effects :as members.effects]
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
  [db {:keys [member-id profile avatar-upload sync-keycloak?] :as params} stored-avatar durable?]
  (let [member             (d/entity db [:member/member-id member-id])
        current-avatar-eid (some-> member :member/avatar :db/id)
        keycloak-id        (:member/keycloak-id member)
        profile-data       (vec (concat
                                 (profile-tx member-id profile)
                                 (:tx-data stored-avatar)
                                 (avatar-change-tx member-id current-avatar-eid
                                                   (when-not (:avatar-removed? profile) avatar-upload)
                                                   (:avatar-removed? profile) stored-avatar
                                                   (avatar-file-eids db current-avatar-eid))
                                 [[:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]))]
    (into [[:member.invite/transact-profile-if-not-in-flight member-id profile-data]]
          (when durable?
            (concat
             [{:db/id        "datomic.tx"        :audit/action :app.account.actions/save-profile
               :audit/origin :app.origin/browser}]
             (when (and sync-keycloak? keycloak-id)
               (log-dispatch/intent-tx
                [(identity-jobs/job params member-id keycloak-id {:metadata? true})])))))))

(defn save-profile!
  "Persists one validated profile and optional avatar upload atomically.

  The upload's stripped original and four fixed renditions are stored before a
  single Datomic transaction attaches their metadata to the authenticated
  member.
  Avatar changes use last-write-wins semantics.
  The temporary multipart file is deleted on success and failure.

  Options:

  | key                   | description
  |-----------------------|-------------
  | `:member-id`          | Authenticated member UUID
  | `:profile`            | Validated normalized profile map
  | `:avatar-upload`      | Optional multipart file metadata and tempfile
  | `:sync-keycloak?`     | Request identity synchronization; durable mode queues it atomically with the profile |"
  [system {:keys [member-id profile avatar-upload sync-keycloak?] :as params}]
  (let [conn     (-> system :datomic :conn)
        tempfile (:tempfile avatar-upload)
        durable? (get-in system [:frame-loop :durable-jobs?])]
    (assert conn "profile persistence requires a Datomic connection")
    (try
      (let [stored-avatar (when (and avatar-upload (not (:avatar-removed? profile)))
                            (filestore.controller/store-avatar!
                             {:filestore (:filestore system)}
                             {:file-name (:filename avatar-upload)
                              :file      tempfile
                              :mime-type (:mime-type avatar-upload)}))
            persist!      (fn [] @(d/transact conn (save-tx (d/db conn) params stored-avatar durable?)))
            tx-result     (if-let [control (get-in system [:frame-loop :write-runner])]
                            (writer/call! control persist!)
                            (persist!))]
        (when (and sync-keycloak? (not durable?))
          (members.effects/update-keycloak-meta!
           {:system system :datomic-conn conn} member-id))
        {:status :saved :tx-result tx-result})
      (finally
        (when tempfile
          (bfs/delete-if-exists tempfile))))))

(defn discard-upload-fx
  "Deletes a rejected multipart upload tempfile, if present."
  [_coeffects _context tempfile]
  (when tempfile
    (bfs/delete-if-exists tempfile)))

(defn save-profile-fx
  "Persists a validated profile save."
  [_coeffects {:keys [system]} params]
  (save-profile! system params)
  nil)
