(ns app.keycloak.cells
  "Reusable Mycelium cells for Keycloak administration operations."
  (:require
   [app.schemas :as s]
   [mycelium.core :as myc]))

(def attributes-schema
  (s/schema
   [:map-of
    ::s/non-blank-string
    [:vector ::s/non-blank-string]]))

(def user-schema
  (s/schema
   [:map
    [:id ::s/non-blank-string]
    [:username {:optional true} ::s/non-blank-string]
    [:email {:optional true} ::s/email-address]
    [:enabled? :boolean]
    [:attributes attributes-schema]]))

(defn- adapter-outcome [operation result]
  (or (:outcome result)
      (throw (ex-info "Keycloak adapter returned no outcome"
                      {:operation operation
                       :result result}))))

(myc/defcell :keycloak/find-users-by-attributes
  {:doc "Finds Keycloak users by exact custom attributes and classifies zero, one, or multiple matches."
   :input (s/schema
           [:map
            [:keycloak/user-attributes attributes-schema]])
   :output (let [output
                 {:not-found
                  [:map
                   [:keycloak/user-lookup [:= :not-found]]]
                  :found
                  [:map
                   [:keycloak/user-lookup [:= :found]]
                   [:keycloak/user-id ::s/non-blank-string]
                   [:keycloak/user user-schema]]
                  :ambiguous
                  [:map
                   [:keycloak/user-lookup [:= :ambiguous]]
                   [:keycloak/match-count pos-int?]]}]
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-attributes]}]
    (let [users ((:find-users-by-attributes keycloak)
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
   :input (s/schema
           [:map
            [:keycloak/user-id ::s/non-blank-string]])
   :output (let [output
                 {:not-found
                  [:map
                   [:keycloak/user-lookup [:= :not-found]]]
                  :found
                  [:map
                   [:keycloak/user-lookup [:= :found]]
                   [:keycloak/user user-schema]]}]
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id]}]
    (if-let [user ((:get-user keycloak) user-id)]
      {:keycloak/user-lookup :found
       :keycloak/user user}
      #:keycloak{:user-lookup :not-found})))

(myc/defcell :keycloak/find-group-by-name
  {:doc "Finds a Keycloak group by exact name and rejects missing or ambiguous results without choosing one."
   :input (s/schema
           [:map
            [:keycloak/group-name ::s/non-blank-string]])
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
                          [:keycloak/group-id ::s/non-blank-string]]
                  :ambiguous ambiguous-schema
                  :unavailable [:or not-found-schema ambiguous-schema]}]
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [group-name]}]
    (let [groups ((:find-groups-by-name keycloak) group-name)]
      (case (count groups)
        0 #:keycloak{:group-lookup :not-found}
        1 {:keycloak/group-lookup :found
           :keycloak/group-id (:id (first groups))}
        {:keycloak/group-lookup :ambiguous
         :keycloak/match-count (count groups)}))))

(myc/defcell :keycloak/create-user
  {:doc (str "Creates one Keycloak user from a generic user specification and "
             "classifies only definite server rejection as rejected.")
   :input (s/schema
           [:map
            [:keycloak/user-spec
             [:map
              [:username ::s/non-blank-string]
              [:email ::s/email-address]
              [:first-name ::s/non-blank-string]
              [:enabled? :boolean]
              [:email-verified? :boolean]
              [:attributes attributes-schema]]]])
   :output (let [output
                 {:created
                  [:map
                   [:keycloak/create-status [:= :created]]
                   [:keycloak/user-id ::s/non-blank-string]]
                  :rejected
                  [:map
                   [:keycloak/create-status [:= :rejected]]]}]
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-spec]}]
    (let [operation :create-user!
          result ((operation keycloak) user-spec)]
      (case (adapter-outcome operation result)
        :created {:keycloak/create-status :created
                  :keycloak/user-id (:user-id result)}
        :rejected #:keycloak{:create-status :rejected}
        (throw (ex-info "Unexpected Keycloak create outcome"
                        {:result result}))))))

(myc/defcell :keycloak/add-user-to-group
  {:doc "Adds a Keycloak user to a group and classifies only a definite server rejection as rejected."
   :input (s/schema
           [:map
            [:keycloak/user-id ::s/non-blank-string]
            [:keycloak/group-id ::s/non-blank-string]])
   :output (let [output
                 {:joined
                  [:map
                   [:keycloak/group-membership-status [:= :joined]]]
                  :rejected
                  [:map
                   [:keycloak/group-membership-status [:= :rejected]]]}]
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id group-id]}]
    (let [operation :add-user-to-group!
          result ((operation keycloak) user-id group-id)]
      (case (adapter-outcome operation result)
        :joined #:keycloak{:group-membership-status :joined}
        :rejected #:keycloak{:group-membership-status :rejected}
        (throw (ex-info "Unexpected Keycloak group membership outcome"
                        {:result result}))))))

(myc/defcell :keycloak/set-user-enabled
  {:doc "Sets one Keycloak user's enabled flag and classifies only a definite server rejection as rejected."
   :input (s/schema
           [:map
            [:keycloak/user-id ::s/non-blank-string]
            [:keycloak/enabled? :boolean]])
   :output (let [output
                 {:updated
                  [:map
                   [:keycloak/update-status [:= :updated]]]
                  :rejected
                  [:map
                   [:keycloak/update-status [:= :rejected]]]}]
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id enabled?]}]
    (let [operation :set-user-enabled!
          result ((operation keycloak) user-id enabled?)]
      (case (adapter-outcome operation result)
        :updated #:keycloak{:update-status :updated}
        :rejected #:keycloak{:update-status :rejected}
        (throw (ex-info "Unexpected Keycloak update outcome"
                        {:result result}))))))

(myc/defcell :keycloak/delete-user
  {:doc "Deletes one Keycloak user by ID and distinguishes deleted, already absent, and definite rejection."
   :input (s/schema
           [:map
            [:keycloak/user-id ::s/non-blank-string]])
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
             (run! s/schema (vals output))
             [:per-transition output])}
  (fn [{:keys [keycloak]} {:keycloak/keys [user-id]}]
    (let [operation :delete-user!
          result ((operation keycloak) user-id)]
      (case (adapter-outcome operation result)
        :deleted #:keycloak{:delete-status :deleted}
        :not-found #:keycloak{:delete-status :not-found}
        :rejected #:keycloak{:delete-status :rejected}
        (throw (ex-info "Unexpected Keycloak delete outcome"
                        {:result result}))))))
