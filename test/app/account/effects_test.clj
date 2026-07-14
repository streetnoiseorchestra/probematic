(ns app.account.effects-test
  (:require
   [app.account.actions :as actions]
   [app.account.test-support :as support]
   [app.filestore :as filestore]
   [app.queries :as queries]
   [app.test-common :as tc]
   [babashka.fs :as bfs]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")

(defn with-temp-filestore [f]
  (let [dir (bfs/create-temp-dir {:prefix "probematic.profile-effect-test."})]
    (try
      (let [store (filestore/start! {:store-path dir})]
        (try
          (f store)
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(defn seed-member! [conn member-id overrides]
  @(d/transact
    conn
    [(merge {:db/id [:member/member-id member-id]
             :member/name "Ada Lovelace"
             :member/nick "ada"
             :member/email "ada@example.test"
             :member/username "ada_l"
             :member/phone "+436601111111"
             :member/active? true}
            overrides)]))

(def profile
  {:name "Ada Byron"
   :nick "Countess"
   :email "ada.byron@example.test"
   :username "ada_byron"
   :phone "+436601234567"
   :current-status "Rehearsing tonight"
   :date-of-birth "1815-12-10"
   :avatar-removed? false
   :avatar nil})

(defn avatar-upload [prefix]
  (let [tempfile (bfs/create-temp-file
                  {:prefix (str "probematic." prefix ".")
                   :suffix ".jpg"})]
    (bfs/copy jpeg-path tempfile {:replace-existing true})
    {:filename (str prefix ".jpg")
     :mime-type "image/jpeg"
     :size (bfs/size tempfile)
     :tempfile (bfs/file tempfile)}))

(defn avatar-metadata-ids [db member-id]
  (let [avatar (:member/avatar (queries/retrieve-member db member-id))
        images (when avatar (cons avatar (:image/renditions avatar)))]
    {:avatar-id (:image/image-id avatar)
     :image-ids (into #{} (keep :image/image-id) images)
     :file-ids (into #{}
                     (keep #(get-in % [:image/source-file
                                       :filestore.file/file-id]))
                     images)}))

(defn metadata-retracted? [db {:keys [image-ids file-ids]}]
  (and (every? #(nil? (d/entid db [:image/image-id %])) image-ids)
       (every? #(nil? (d/entid db [:filestore.file/file-id %])) file-ids)))

(deftest profile-effect-persists-fields-original-and-all-renditions-in-one-save
  (let [save-profile! (support/public-fn 'app.account.effects/save-profile!)]
    (is (fn? save-profile!) "app.account.effects/save-profile! should exist")
    (when save-profile!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "profile-effect-upload")
                tempfile (bfs/create-temp-file
                          {:prefix "probematic.profile-upload."
                           :suffix ".jpg"})]
            (seed-member! conn member-id {})
            (bfs/copy jpeg-path tempfile {:replace-existing true})
            (let [result
                  (save-profile!
                   {:datomic {:conn conn}
                    :filestore store}
                   {:member-id member-id
                    :profile profile
                    :avatar-upload {:filename "portrait.jpg"
                                    :mime-type "image/jpeg"
                                    :size (bfs/size tempfile)
                                    :tempfile (bfs/file tempfile)}
                    :expected-avatar-id nil
                    :sync-keycloak? false})
                  member (queries/retrieve-member (d/db conn) member-id)]
              (is (= :saved (:status result)))
              (is (not (bfs/exists? tempfile)))
              (is (= "Rehearsing tonight" (:member/current-status member)))
              (is (= "1815-12-10" (:member/date-of-birth member)))
              (is (uuid? (get-in member [:member/avatar :image/image-id])))
              (is (= #{40 80 160 320}
                     (->> (get-in member [:member/avatar :image/renditions])
                          (map :image/width)
                          set)))
              (is (= member-id
                     (-> (d/q '[:find ?member-id .
                                :in $ ?avatar-id
                                :where
                                [?member :member/member-id ?member-id]
                                [?member :member/avatar ?avatar]
                                [?avatar :image/image-id ?avatar-id]]
                              (d/db conn)
                              (get-in member [:member/avatar :image/image-id]))))))))))))

(deftest profile-effect-removal-clears-profile-optionals-and-legacy-template
  (let [save-profile! (support/public-fn 'app.account.effects/save-profile!)]
    (is (fn? save-profile!) "app.account.effects/save-profile! should exist")
    (when save-profile!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "profile-effect-remove")
                old-avatar-id (random-uuid)]
            (seed-member!
             conn
             member-id
             {:member/avatar-template "/user_avatar/ada/{size}/1.png"
              :member/avatar {:image/image-id old-avatar-id
                              :image/width 160
                              :image/height 160}})
            (let [result
                  (save-profile!
                   {:datomic {:conn conn}
                    :filestore store}
                   {:member-id member-id
                    :profile (assoc profile
                                    :avatar-removed? true
                                    :current-status ""
                                    :date-of-birth "")
                    :avatar-upload nil
                    :expected-avatar-id old-avatar-id
                    :sync-keycloak? false})
                  member (queries/retrieve-member (d/db conn) member-id)]
              (is (= :saved (:status result)))
              (is (nil? (:member/avatar member)))
              (is (nil? (:member/avatar-template member)))
              (is (nil? (:member/current-status member)))
              (is (nil? (:member/date-of-birth member)))
              (is (nil? (d/entity (d/db conn)
                                  [:image/image-id old-avatar-id]))))))))))

(deftest profile-effect-replacement-and-removal-retract-all-obsolete-metadata
  (let [save-profile! (support/public-fn 'app.account.effects/save-profile!)]
    (is (fn? save-profile!))
    (when save-profile!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "profile-effect-cleanup")
                system {:datomic {:conn conn} :filestore store}]
            (seed-member! conn member-id {})
            (save-profile! system
                           {:member-id member-id
                            :profile profile
                            :avatar-upload (avatar-upload "old-avatar")
                            :expected-avatar-id nil
                            :sync-keycloak? false})
            (let [old-metadata (avatar-metadata-ids (d/db conn) member-id)]
              (is (= 5 (count (:image-ids old-metadata))))
              (is (= 5 (count (:file-ids old-metadata))))
              (save-profile! system
                             {:member-id member-id
                              :profile profile
                              :avatar-upload (avatar-upload "new-avatar")
                              :expected-avatar-id (:avatar-id old-metadata)
                              :sync-keycloak? false})
              (let [db (d/db conn)
                    new-metadata (avatar-metadata-ids db member-id)]
                (is (not= (:avatar-id old-metadata)
                          (:avatar-id new-metadata)))
                (is (metadata-retracted? db old-metadata))
                (is (= 5 (count (:image-ids new-metadata))))
                (is (= 5 (count (:file-ids new-metadata))))
                (save-profile! system
                               {:member-id member-id
                                :profile (assoc profile :avatar-removed? true)
                                :avatar-upload nil
                                :expected-avatar-id (:avatar-id new-metadata)
                                :sync-keycloak? false})
                (let [db (d/db conn)]
                  (is (nil? (:member/avatar
                             (queries/retrieve-member db member-id))))
                  (is (metadata-retracted? db new-metadata)))))))))))

(deftest profile-effect-avatar-removal-wins-over-a-submitted-file
  (let [save-profile! (support/public-fn 'app.account.effects/save-profile!)]
    (is (fn? save-profile!))
    (when save-profile!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "profile-effect-remove-wins")
                system {:datomic {:conn conn} :filestore store}]
            (seed-member! conn member-id {})
            (save-profile! system
                           {:member-id member-id
                            :profile profile
                            :avatar-upload (avatar-upload "existing-avatar")
                            :expected-avatar-id nil
                            :sync-keycloak? false})
            (let [old-metadata (avatar-metadata-ids (d/db conn) member-id)
                  submitted-upload (avatar-upload "discarded-avatar")
                  tempfile (:tempfile submitted-upload)]
              (save-profile! system
                             {:member-id member-id
                              :profile (assoc profile :avatar-removed? true)
                              :avatar-upload submitted-upload
                              :expected-avatar-id (:avatar-id old-metadata)
                              :sync-keycloak? false})
              (let [db (d/db conn)]
                (is (nil? (:member/avatar
                           (queries/retrieve-member db member-id))))
                (is (metadata-retracted? db old-metadata))
                (is (empty?
                     (d/q '[:find [?file-id ...]
                            :where [_ :filestore.file/file-id ?file-id]]
                          db)))
                (is (not (bfs/exists? tempfile)))))))))))

(deftest stale-profile-upload-cannot-replace-a-newer-avatar
  (let [save-profile! (support/public-fn 'app.account.effects/save-profile!)]
    (is (fn? save-profile!) "app.account.effects/save-profile! should exist")
    (when save-profile!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "profile-effect-cas")
                observed-id (random-uuid)
                newer-id (random-uuid)
                tempfile (bfs/create-temp-file
                          {:prefix "probematic.stale-profile-upload."
                           :suffix ".jpg"})]
            (seed-member! conn member-id
                          {:member/avatar {:image/image-id observed-id
                                           :image/width 160
                                           :image/height 160}})
            @(d/transact conn
                         [{:db/id [:member/member-id member-id]
                           :member/avatar {:image/image-id newer-id
                                           :image/width 160
                                           :image/height 160}}])
            (bfs/copy jpeg-path tempfile {:replace-existing true})
            (testing "Datomic compare-and-swap preserves the newer avatar"
              (is (thrown? Exception
                           (save-profile!
                            {:datomic {:conn conn}
                             :filestore store}
                            {:member-id member-id
                             :profile profile
                             :avatar-upload {:filename "stale.jpg"
                                             :mime-type "image/jpeg"
                                             :size (bfs/size tempfile)
                                             :tempfile (bfs/file tempfile)}
                             :expected-avatar-id observed-id
                             :sync-keycloak? false})))
              (is (= newer-id
                     (get-in (queries/retrieve-member (d/db conn) member-id)
                             [:member/avatar :image/image-id])))
              (is (not (bfs/exists? tempfile))))))))))

(deftest action-and-effect-contract-carries-the-real-multipart-file
  (let [save-profile-fx (support/public-fn
                         'app.account.effects/save-profile-fx)
        {:keys [conn member-id]} (tc/new-system "profile-effect-contract")
        tempfile (java.io.File. "/tmp/account-avatar-contract.jpg")]
    (is (fn? save-profile-fx) "app.account.effects/save-profile-fx should exist")
    (seed-member! conn member-id {})
    (let [effects
          (actions/save-profile-action
           {:db (d/db conn)
            :current-member-id member-id}
           {:account-profile profile
            :avatar-upload {:filename "portrait.jpg"
                            :mime-type "image/jpeg"
                            :size 1024
                            :tempfile tempfile}})]
      (is (= tempfile (get-in effects [0 1 :avatar-upload :tempfile])))
      (is (= member-id (get-in effects [0 1 :member-id]))))))
