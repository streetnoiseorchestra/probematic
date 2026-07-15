(ns app.account.effects
  "Side-effecting account profile persistence.

  Profile validation remains in [[app.account.actions]].
  This namespace owns uploaded file preparation, the atomic Datomic write, and
  the optional identity-provider synchronization performed after a successful
  transaction."
  (:require
   [app.filestore.controller :as filestore.controller]
   [app.members.effects :as members.effects]
   [babashka.fs :as bfs]
   [datomic.api :as d]))

(defn- optional-attribute-tx [entity attr value]
  [(if (some? value)
     [:db/add entity attr value]
     [:db/retract entity attr])])

(defn- profile-tx [member-id profile]
  (let [member-ref [:member/member-id member-id]]
    (into [{:db/id member-ref
            :member/name (:name profile)
            :member/email (:email profile)
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
    (let [member [:member/member-id member-id]
          new-avatar (:image-tempid stored-avatar)
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
  | `:sync-keycloak?`     | Synchronize identity metadata after commit"
  [system {:keys [member-id profile avatar-upload sync-keycloak?]}]
  (let [conn (-> system :datomic :conn)]
    (assert conn "profile persistence requires a Datomic connection")
    (let [tempfile (:tempfile avatar-upload)
          avatar-removed? (:avatar-removed? profile)
          effective-avatar-upload (when-not avatar-removed? avatar-upload)
          db (d/db conn)
          current-avatar-eid
          (some-> (d/entity db [:member/member-id member-id])
                  :member/avatar
                  :db/id)
          obsolete-file-eids (avatar-file-eids db current-avatar-eid)]
      (try
        (let [stored-avatar
              (when effective-avatar-upload
                (filestore.controller/store-avatar!
                 {:filestore (:filestore system)}
                 {:file-name (:filename effective-avatar-upload)
                  :file tempfile
                  :mime-type (:mime-type effective-avatar-upload)}))
              tx-data
              (vec
               (concat
                (profile-tx member-id profile)
                (:tx-data stored-avatar)
                (avatar-change-tx member-id
                                  current-avatar-eid
                                  effective-avatar-upload
                                  avatar-removed?
                                  stored-avatar
                                  obsolete-file-eids)
                [[:db/add "datomic.tx" :audit/user
                  [:member/member-id member-id]]]))
              tx-result @(d/transact conn tx-data)]
          (when sync-keycloak?
            (members.effects/update-keycloak-meta!
             {:system system :datomic-conn conn}
             member-id))
          {:status :saved
           :tx-result tx-result})
        (finally
          (when tempfile
            (bfs/delete-if-exists tempfile)))))))

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
