(ns app.members.index.actions
  (:require
   [app.util :as util]))

(def clear-invite-signals
  [:app.datastar/respond-sse
   [[:app.datastar.sse/merge-signals
     {:invite {:action     nil
               :code       nil
               :member-id  nil
               :generation nil
               :inflight   false}}]]])

(defn resend-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/resend-invitation (:code invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(defn reissue-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/reissue-invitation (:code invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(defn reissue-revoked-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/reissue-revoked-invitation
    (util/ensure-uuid! (:member-id invite))
    (:generation invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(defn delete-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/delete-invitation (:code invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(def actions
  {::resend-invitation          #'resend-invitation-action
   ::reissue-invitation         #'reissue-invitation-action
   ::reissue-revoked-invitation #'reissue-revoked-invitation-action
   ::delete-invitation          #'delete-invitation-action})
