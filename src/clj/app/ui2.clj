(ns app.ui2
  (:require
   [app.html :as html]
   [app.humanize :as humanize]
   [app.icons :as icons]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]
   [tick.core :as t])
  (:import
   [java.text NumberFormat]
   [java.util Locale]))

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

(defn muted
  "Renders `value`, or an em dash when `value` is blank."
  [value]
  (if (str/blank? (str value))
    [:span {:class "wa-color-text-quiet"} html/emdash]
    value))

(def currency-default-locale
  {:EUR Locale/GERMANY
   :USD Locale/US})

(defn money-format
  "Formats numeric `value` for `currency` using the default project locale."
  [value currency]
  (when value
    (.format (NumberFormat/getCurrencyInstance (get currency-default-locale currency Locale/GERMANY)) value)))

(defn money
  "Renders formatted numeric `value` for `currency`, or an em dash when blank."
  [value currency]
  (muted (money-format value currency)))

(defn date-time-value
  "Formats `value` with `pattern`, accepting `java.util.Date` instants or tick temporal values."
  [pattern value]
  (when value
    (t/format (t/formatter pattern) (if (inst? value) (t/date-time value) value))))

(defn date-value
  "Formats `value` as `yyyy-MM-dd`."
  [value]
  (date-time-value "yyyy-MM-dd" value))

(defn time-value
  "Formats `value` as `HH:mm`."
  [value]
  (date-time-value "HH:mm" value))

(defn relative-time-value
  "Formats `value` as a relative time such as `2 days ago`."
  [value]
  (when value
    (humanize/from (if (inst? value) (t/date-time value) value))))

(defn detail-item
  "Renders a `dl` item using the shared particulars styling."
  [label value]
  [:div
   [:dt label]
   [:dd (muted value)]])

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

(defn section-divider
  "Renders a section title with a trailing divider.

  Required: `title`."
  [title]
  [:div {:class "sno-section-divider"}
   [:h2 title]
   [:wa-divider]])

(defn page-header
  "Renders a standard page header.

  Required: none.
  Optional: `:actions`, `:breadcrumb`, `:class`, `:heading`, `:subtitle`, `:title`.
  Extra keys become attributes on the `header`."
  [{:keys [actions breadcrumb class heading subtitle title] :as attrs}]
  [:header (merge {:class (cs "sno-page-header" "wa-stack" "wa-gap-m" class)}
                  (dissoc attrs :actions :breadcrumb :class :heading :subtitle :title))
   breadcrumb
   [:section {:class "sno-page-header-main"}
    (title-block {:heading  heading
                  :subtitle subtitle
                  :title    title})
    (action-bar {:class "sno-page-actions"} actions)]])

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
     [:wa-icon {:name "ellipsis" :library "snoico"}]]
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

(def standalone-page-style
  ":root { --sno-brand-green: #22c55e; --sno-brand-orange: #f97316; }
   body { font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 0; background: #f7f7f7; color: #1f2933; line-height: 1.5; }
   main { max-width: 42rem; margin: 4rem auto; padding: 0 1.5rem; }
   main > header { display: flex; justify-content: center; margin: 0 0 1.25rem; }
   main > header svg { display: block; width: min(16rem, 70vw); height: auto; }
   main > header svg .logotype-snoman { fill: var(--sno-brand-green); }
   main > header svg .logotype-text { fill: var(--sno-brand-orange); }
   main > section { background: white; border: 1px solid #e5e7eb; border-radius: 0.75rem; padding: 2rem; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04); }
   section > header > p { margin: 0 0 0.5rem; color: #ea580c; font-size: 0.875rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; }
   section > header.danger > p { color: #b91c1c; }
   h1 { margin: 0; font-size: 1.875rem; line-height: 1.2; color: #111827; }
   section > p { margin: 1rem 0 0; color: #4b5563; }
   dl { margin: 1.5rem 0; padding: 1rem; background: #f9fafb; border-radius: 0.5rem; }
   dt { font-weight: 700; color: #111827; }
   dt + dd + dt { margin-top: 1rem; }
   dd { margin: 0.25rem 0 0; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; color: #374151; }
   footer { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; margin-top: 1.5rem; }
   button { background: #ea580c; color: white; border: 0; border-radius: 0.375rem; padding: 0.625rem 1rem; font: inherit; font-weight: 700; cursor: pointer; }
   button:hover { background: #c2410c; }
   footer a { color: #374151; font-weight: 700; text-decoration: none; }
   footer a:hover { color: #7c2d12; }
   code { overflow-wrap: anywhere; }")

(defn standalone-page [{:keys [description lang status title]} & body]
  {:status  (or status 200)
   :headers {"Content-Type" "text/html"}
   :body    (html/->str
             (html/html-document
              {:title       title
               :description description
               :lang        lang
               :head        [:style (html/raw standalone-page-style)]}
              [:main {:id "main"}
               [:header {:aria-label "SNOrga"}
                (icons/logotype {:aria-hidden "true"})]
               (into [:section]
                     body)]))})

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
