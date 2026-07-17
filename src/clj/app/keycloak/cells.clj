(ns app.keycloak.cells
  "Reusable Mycelium cells for Keycloak administration operations."
  (:require
   [app.schemas :as schemas]
   [mycelium.core :as myc]))

(def ^:private non-blank-string-schema
  (schemas/schema :app.schemas/non-blank-string))

(def ^:private attributes-schema
  (schemas/schema
   [:map-of
    :app.schemas/non-blank-string
    [:vector :app.schemas/non-blank-string]]))

(def ^:private user-schema
  (schemas/schema
   [:map
    [:id :app.schemas/non-blank-string]
    [:username {:optional true} :app.schemas/non-blank-string]
    [:email {:optional true} :app.schemas/email-address]
    [:enabled? :boolean]
    [:attributes attributes-schema]]))

(defn- adapter-operation [keycloak operation]
  (or (get keycloak operation)
      (throw (ex-info "Keycloak adapter operation is missing"
                      {:operation operation}))))

(defn- adapter-outcome [operation result]
  (or (:outcome result)
      (throw (ex-info "Keycloak adapter returned no outcome"
                      {:operation operation
                       :result result}))))

(myc/defcell :keycloak/find-users-by-attributes
  {:doc "Finds Keycloak users by exact custom attributes and classifies zero, one, or multiple matches."
   :input (schemas/schema
           [:map
            [:keycloak/user-attributes attributes-schema]])
   :output (let [output
                 {:not-found
                  [:map
                   [:keycloak/user-lookup [:= :not-found]]]
                  :found
                  [:map
                   [:keycloak/user-lookup [:= :found]]
                   [:keycloak/user-id non-blank-string-schema]
                   [:keycloak/user user-schema]]
                  :ambiguous
                  [:map
                   [:keycloak/user-lookup [:= :ambiguous]]
                   [:keycloak/match-count pos-int?]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-attributes]}]
    (let [users ((adapter-operation keycloak :find-users-by-attributes)
                 user-attributes)]
      (case (count users)
        0 #:keycloak{:user-lookup :not-found}
        1 (let [user (first users)]
            {:keycloak/user-lookup :found
             :keycloak/user-id (:id user)
             :keycloak/user user})
        {:keycloak/user-lookup :ambiguous
         :keycloak/match-count (count users)}))))

(myc/defcell :keycloak/get-user
  {:doc "Reads one Keycloak user by ID and distinguishes a missing user from a returned normalized representation."
   :input (schemas/schema
           [:map
            [:keycloak/user-id :app.schemas/non-blank-string]])
   :output (let [output
                 {:not-found
                  [:map
                   [:keycloak/user-lookup [:= :not-found]]]
                  :found
                  [:map
                   [:keycloak/user-lookup [:= :found]]
                   [:keycloak/user user-schema]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id]}]
    (if-let [user ((adapter-operation keycloak :get-user) user-id)]
      {:keycloak/user-lookup :found
       :keycloak/user user}
      #:keycloak{:user-lookup :not-found})))

(myc/defcell :keycloak/find-group-by-name
  {:doc "Finds a Keycloak group by exact name and rejects missing or ambiguous results without choosing one."
   :input (schemas/schema
           [:map
            [:keycloak/group-name :app.schemas/non-blank-string]])
   :output (let [not-found-schema
                 [:map
                  [:keycloak/group-lookup [:= :not-found]]]
                 ambiguous-schema
                 [:map
                  [:keycloak/group-lookup [:= :ambiguous]]
                  [:keycloak/match-count pos-int?]]
                 output
                 {:not-found not-found-schema
                  :found [:map
                          [:keycloak/group-lookup [:= :found]]
                          [:keycloak/group-id non-blank-string-schema]]
                  :ambiguous ambiguous-schema
                  :unavailable [:or not-found-schema ambiguous-schema]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [group-name]}]
    (let [groups ((adapter-operation keycloak :find-groups-by-name) group-name)]
      (case (count groups)
        0 #:keycloak{:group-lookup :not-found}
        1 {:keycloak/group-lookup :found
           :keycloak/group-id (:id (first groups))}
        {:keycloak/group-lookup :ambiguous
         :keycloak/match-count (count groups)}))))

(myc/defcell :keycloak/create-user
  {:doc (str "Creates one Keycloak user from a generic user specification and "
             "classifies only definite server rejection as rejected.")
   :input (schemas/schema
           [:map
            [:keycloak/user-spec
             [:map
              [:username :app.schemas/non-blank-string]
              [:email :app.schemas/email-address]
              [:first-name :app.schemas/non-blank-string]
              [:enabled? :boolean]
              [:email-verified? :boolean]
              [:attributes attributes-schema]]]])
   :output (let [output
                 {:created
                  [:map
                   [:keycloak/create-status [:= :created]]
                   [:keycloak/user-id non-blank-string-schema]]
                  :rejected
                  [:map
                   [:keycloak/create-status [:= :rejected]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-spec]}]
    (let [operation :create-user!
          result ((adapter-operation keycloak operation) user-spec)]
      (case (adapter-outcome operation result)
        :created {:keycloak/create-status :created
                  :keycloak/user-id (:user-id result)}
        :rejected #:keycloak{:create-status :rejected}
        (throw (ex-info "Unexpected Keycloak create outcome"
                        {:result result}))))))

(myc/defcell :keycloak/add-user-to-group
  {:doc "Adds a Keycloak user to a group and classifies only a definite server rejection as rejected."
   :input (schemas/schema
           [:map
            [:keycloak/user-id :app.schemas/non-blank-string]
            [:keycloak/group-id :app.schemas/non-blank-string]])
   :output (let [output
                 {:joined
                  [:map
                   [:keycloak/group-membership-status [:= :joined]]]
                  :rejected
                  [:map
                   [:keycloak/group-membership-status [:= :rejected]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id group-id]}]
    (let [operation :add-user-to-group!
          result ((adapter-operation keycloak operation) user-id group-id)]
      (case (adapter-outcome operation result)
        :joined #:keycloak{:group-membership-status :joined}
        :rejected #:keycloak{:group-membership-status :rejected}
        (throw (ex-info "Unexpected Keycloak group membership outcome"
                        {:result result}))))))

(myc/defcell :keycloak/set-user-enabled
  {:doc "Sets one Keycloak user's enabled flag and classifies only a definite server rejection as rejected."
   :input (schemas/schema
           [:map
            [:keycloak/user-id :app.schemas/non-blank-string]
            [:keycloak/enabled? :boolean]])
   :output (let [output
                 {:updated
                  [:map
                   [:keycloak/update-status [:= :updated]]]
                  :rejected
                  [:map
                   [:keycloak/update-status [:= :rejected]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id enabled?]}]
    (let [operation :set-user-enabled!
          result ((adapter-operation keycloak operation) user-id enabled?)]
      (case (adapter-outcome operation result)
        :updated #:keycloak{:update-status :updated}
        :rejected #:keycloak{:update-status :rejected}
        (throw (ex-info "Unexpected Keycloak update outcome"
                        {:result result}))))))

(myc/defcell :keycloak/delete-user
  {:doc "Deletes one Keycloak user by ID and distinguishes deleted, already absent, and definite rejection."
   :input (schemas/schema
           [:map
            [:keycloak/user-id :app.schemas/non-blank-string]])
   :output (let [deleted-schema
                 [:map
                  [:keycloak/delete-status [:= :deleted]]]
                 not-found-schema
                 [:map
                  [:keycloak/delete-status [:= :not-found]]]
                 output
                 {:deleted deleted-schema
                  :not-found not-found-schema
                  :absent [:or deleted-schema not-found-schema]
                  :rejected [:map
                             [:keycloak/delete-status [:= :rejected]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:keycloak]}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id]}]
    (let [operation :delete-user!
          result ((adapter-operation keycloak operation) user-id)]
      (case (adapter-outcome operation result)
        :deleted #:keycloak{:delete-status :deleted}
        :not-found #:keycloak{:delete-status :not-found}
        :rejected #:keycloak{:delete-status :rejected}
        (throw (ex-info "Unexpected Keycloak delete outcome"
                        {:result result}))))))
