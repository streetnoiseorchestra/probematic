(ns app.ui2
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn safe-dom-id [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")))

(defn remove-dialog-id [prefix ent-id]
  (str prefix "-remove-" (safe-dom-id ent-id)))

(defn active-badge [active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active?       (assoc :variant "success")
               (not active?) (assoc :variant "neutral"))
   (if active? "Active" "Inactive")])

(defn section-card [{:keys [id title subtitle actions] :as attrs} & children]
  (into
   [:section (merge {:id    id
                     :class "wa-stack"}
                    (dissoc attrs :id :title :subtitle :actions))
    [:div {:class "wa-flank:end wa-align-items-start"}
     [:div {:class "wa-stack"}
      (when title
        [:h2 title])
      (when subtitle
        [:span {:class "wa-caption-s"} subtitle])]
     (when actions
       (into [:div {:class "wa-cluster wa-gap-xs"}]
             actions))]]
   children))

(defn remove-dialog [{:keys [id label cancel-label confirm-label dialog-attrs confirm-attrs]} body]
  [:wa-dialog (merge {:id                 id
                      :label              label
                      :data-preserve-attr "open"}
                     dialog-attrs)
   body
   [:wa-button {:slot        "footer"
                :appearance  "outlined"
                :data-dialog "close"}
    cancel-label]
   [:wa-button (merge {:slot        "footer"
                       :appearance  "filled"
                       :variant     "danger"
                       :data-dialog "close"}
                      confirm-attrs)
    confirm-label]])

(defn row-action-menu [{:keys [button-id disabled? items]}]
  (list
   [:wa-dropdown {:placement "bottom-end"}
    [:wa-button {:id         button-id
                 :slot       "trigger"
                 :appearance "plain"
                 :disabled   disabled?
                 :aria-label "More actions"}
     "More"]
    (for [{:keys [label] :as item} items]
      [:wa-dropdown-item (dissoc item :icon)
       label])]
   [:wa-tooltip {:for button-id :without-arrow true}
    "More actions"]))

(defn table-shell [& children]
  (into
   [:div {:class "table-shell"}]
   children))

(defn empty-state [title body]
  [:wa-callout {:appearance "outlined" :variant "neutral"}
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong title]
    [:span body]]])

(defn datastar-main-attrs []
  {:id             "main"
   :data-on:submit (->expr (when evt.target.dataset.action
                             (evt.target.setAttribute "loading" "")
                             (set! $loading evt.target.dataset.id)
                             (set! $targetid evt.target.dataset.id)
                             (@post ("`${evt.target.dataset.action}`"))))
   :data-on:mousedown (->expr (when evt.target.dataset.action
                                (evt.target.setAttribute "loading" "")
                                (set! $loading evt.target.dataset.id)
                                (set! $targetid evt.target.dataset.id)
                                (@post ("`${evt.target.dataset.action}`"))))
   :data-on:keydown (->expr (when (and evt.target.dataset.action (= evt.key "Enter"))
                              (evt.target.setAttribute "loading" "")
                              (set! $loading evt.target.dataset.id)
                              (set! $targetid evt.target.dataset.id)
                              (@post ("`${evt.target.dataset.action}`"))))})

(defn datastar-page [& children]
  (html/->str
   (into [:main (datastar-main-attrs)] children)))

(defn plain-page [& children]
  (html/->str
   (into [:main {:id "main"}] children)))
