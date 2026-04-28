(ns app.ui2
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn safe-dom-id
  "Returns `value` as a safe DOM id fragment.

  Required: `value`."
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")))

(defn remove-dialog-id
  "Builds the standard remove-dialog DOM id.

  Required: `prefix` and `ent-id`."
  [prefix ent-id]
  (str prefix "-remove-" (safe-dom-id ent-id)))

(defn active-badge
  "Renders a translated active/inactive badge.

  Required: `tr` translation function and `active?`."
  [tr active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active?       (assoc :variant "success")
               (not active?) (assoc :variant "neutral"))
   (if active?
     (tr [:Active])
     (tr [:Inactive]))])

(defn cs
  "Joins truthy class names with spaces.

  Optional: zero or more `names`."
  [& names]
  (str/join " " (filter identity names)))

(defn title-block
  "Renders a reusable title/subtitle block.

  Required: one of `:heading`, `:title`, or `:subtitle`.
  Optional: `:class`, `:heading`, `:level`, `:subtitle`, `:title`.
  Use `:heading` for custom Hiccup, or `:title` for an `hN` heading."
  [{:keys [class heading level subtitle title]}]
  (let [heading-tag (keyword (str "h" (or level 1)))]
    (when (or heading title subtitle)
      [:div {:class (cs "sno-title-block" "wa-stack" "wa-gap-2xs" class)}
       (cond
         heading heading
         title   [heading-tag title])
       (when subtitle
         [:span {:class "wa-caption-s"} subtitle])])))

(defn action-bar
  "Renders a responsive action button area.

  Required: `actions`, a seq of Hiccup nodes.
  Optional: `attrs`, including `:class`; nil actions are ignored."
  [{:keys [class] :as attrs} actions]
  (let [actions (filter some? actions)]
    (when (seq actions)
      (into [:div (merge {:class (cs "sno-action-bar" class)}
                         (dissoc attrs :class))]
            actions))))

(defn page-header
  "Renders a standard page header.

  Required: none.
  Optional: `:actions`, `:breadcrumb`, `:class`, `:heading`, `:subtitle`, `:title`.
  Extra keys become attributes on the `header`."
  [{:keys [actions breadcrumb class heading subtitle title] :as attrs}]
  [:header (merge {:class (cs "sno-page-header" "wa-stack" "wa-gap-m" class)}
                  (dissoc attrs :actions :breadcrumb :class :heading :subtitle :title))
   breadcrumb
   [:section {:class "wa-stack wa-gap-l"}
    [:div {:class "wa-stack wa-gap-m"}
     (title-block {:heading  heading
                   :subtitle subtitle
                   :title    title})
     (action-bar {} actions)]]])

(defn section-card
  "Renders a Web Awesome card section with optional header actions.

  Required: none.
  Optional: `children`, `:id`, `:title`, `:subtitle`, `:actions`, `:divider?`.
  Extra keys become attributes on the `section`."
  [{:keys [id title subtitle actions divider?] :as attrs} & children]
  (into
   [:section (merge {:id    id
                     :class "wa-stack"}
                    (dissoc attrs :id :title :subtitle :actions :divider?))
    [:div {:class "sno-section-header"}
     (title-block {:level    2
                   :subtitle subtitle
                   :title    title})
     (action-bar {:class "sno-section-actions"} actions)]
    (when divider? [:wa-divider])]
   children))

(defn remove-dialog
  "Renders a reusable delete confirmation dialog.

  Required: `:id`, `:label`, `:cancel-label`, `:confirm-label`, and `body`.
  Optional: `:dialog-attrs`, `:confirm-attrs`."
  [{:keys [id label cancel-label confirm-label dialog-attrs confirm-attrs]} body]
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

(defn row-action-menu
  "Renders a compact row action dropdown.

  Required: `:button-id` and `:items`.
  Optional: `:disabled?`; each item needs `:label` plus dropdown attrs."
  [{:keys [button-id disabled? items]}]
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

(defn table-shell
  "Wraps table-like content in the standard scrollable shell.

  Optional: zero or more `children`."
  [& children]
  (into
   [:div {:class "table-shell"}]
   children))

(defn empty-state
  "Renders a neutral empty-state callout.

  Required: `title` and `body`."
  [title body]
  [:wa-callout {:appearance "outlined" :variant "neutral"}
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong title]
    [:span body]]])

(defn datastar-main-attrs
  "Returns the standard `main` attrs for Nexus-backed Datastar pages.

  Required: none."
  []
  {:id             "main"
   :data-on:submit (->expr (when evt.target.dataset.action
                             (evt.target.setAttribute "loading" "")
                             (set! $loading evt.target.dataset.id)
                             (set! $targetid evt.target.dataset.id)
                             (@post ("`${evt.target.dataset.action}`"))))
   :data-on:mousedown (->expr (when (and evt.target.dataset.action
                                         (= evt.button 0)
                                         (not (evt.target.matches "form")))
                                (evt.target.setAttribute "loading" "")
                                (set! $loading evt.target.dataset.id)
                                (set! $targetid evt.target.dataset.id)
                                (@post ("`${evt.target.dataset.action}`"))))
   :data-on:keydown (->expr (when (and evt.target.dataset.action (= evt.key "Enter"))
                              (evt.target.setAttribute "loading" "")
                              (set! $loading evt.target.dataset.id)
                              (set! $targetid evt.target.dataset.id)
                              (@post ("`${evt.target.dataset.action}`"))))})

(defn datastar-page
  "Renders `children` inside the standard Datastar `main` element.

  Optional: zero or more `children`."
  [& children]
  (html/->str
   (into [:main (datastar-main-attrs)] children)))

(defn plain-page
  "Renders `children` inside a plain `main` element.

  Optional: zero or more `children`."
  [& children]
  (html/->str
   (into [:main {:id "main"}] children)))
