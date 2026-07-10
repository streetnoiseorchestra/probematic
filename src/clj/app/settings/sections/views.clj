(ns app.settings.sections.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.queries :as q]
   [app.settings.sections.actions :as actions]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn sections-reordering [{:keys [tr page-state] :as req} sections]
  (when (get-in page-state [:section-reorder :open])
    [:wa-dialog {:id                    "section-reorder-dialog"
                 :label                 (tr [:action/reorder])
                 :data-init__delay.10ms "el.open = true"
                 :data-signals          (d*/->signals {:section {:order []}})
                 :data-preserve-attr    "open"
                 :data-on:wa-hide       (->expr
                                         (evt.preventDefault)
                                         (@post ~(d*/act req ::actions/close-section-reorder)))}
     [:div {:class "wa-stack wa-gap-m"}
      [:wa-callout {:appearance "outlined" :variant "neutral"}
       (tr [:band-settings/section-reorder-instructions])]
      [:div {:id                "sortContainer"
             :class             "wa-stack"
             :data-on:reordered (->expr
                                 (set! $section.order evt.detail.orderInfo)
                                 (@post ~(d*/act req ::actions/update-section-order)))}
       (for [[idx section] (map-indexed vector sections)]
         (let [section-name (:section/name section)
               active?      (:section/active? section)]
           [:div {:data-drag-item-id section-name
                  :class (str  "wa-flank " (if active? "active" "inactive"))}
            [:div {:class "drag-handle wa-font-weight-bold wa-color-text-quiet"}
             "≡"]
            [:div {:class "min-inline-size-0"}
             [:input {:type "hidden" :value idx :data-sort-order section-name}]
             [:strong section-name]]]))]]
     (html/squint-inline
      (require '["sortable" :as s])
      (new s/Sortable
           sortContainer
           {:animation  100
            :ghostClass "opacity-50"
            :onEnd
            (fn []
              (let [data (new js/Array)]
                (.forEach
                 (.querySelectorAll sortContainer "input[data-sort-order]")
                 (fn [el]
                   (let [id (.getAttribute el "data-sort-order")]
                     (if id
                       (.push data id)
                       (.warn js/console "no data-sort-order value found on dragged item")))))
                (.dispatchEvent sortContainer
                                (new js/CustomEvent  "reordered" {:detail {:orderInfo data}}))))}))
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/done])]]))

(defn section-create-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error]} (:section-create page-state)
        section-name-error (-> error :section-name :error)]
    (when (get-in page-state [:section-create :open])
      [:wa-dialog {:id                    "section-create-dialog"
                   :label                 (tr [:band-settings/section-add])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-section-create)))}
       [:form {:id             "section-create-form"
               :data-id        "section-create"
               :data-action    (d*/act req ::actions/create-section)
               :data-on:submit "evt.preventDefault();"}
        [:wa-input {:placeholder  "Bass"
                    :type         :text
                    :required     true
                    :label        (tr [:band-settings/section-name])
                    :autofocus    true
                    :hint         section-name-error
                    :data-invalid (if section-name-error "true" nil)
                    :data-bind    "section-create.section-name"
                    :name         :section-name}]]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        (tr [:action/cancel])]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "section-create-form"
                       :data-attr:disabled "!!$loading && $loading !== 'section-create'"
                       :data-attr:loading  "$loading === 'section-create'"}
        (tr [:action/create])]])))

(defn section-edit-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error section-id]} (:section page-state)
        section-name-error         (-> error :section-name :error)]
    (when section-id
      [:wa-dialog {:id                    "section-edit-dialog"
                   :label                 (tr [:band-settings/section-name])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-section-edit)))}
       [:form {:id             "section-edit-form"
               :data-id        "section"
               :data-action    (d*/act req ::actions/update-section)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type :hidden :name "section.section-id" :value nil}]
        [:wa-input {:placeholder  "Bass"
                    :type         :text
                    :required     true
                    :label        (tr [:band-settings/section-name])
                    :autofocus    true
                    :hint         section-name-error
                    :data-invalid (if section-name-error "true" nil)
                    :data-bind    "section.section-name"
                    :name         :section-name}]
        [:wa-switch {:data-attr:checked "$section.section-enabled"
                     :data-on:change    "$section.section-enabled = !$section.section-enabled"}
         (tr [:status-active])]]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        (tr [:action/cancel])]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "section-edit-form"
                       :data-attr:disabled "!!$loading && $loading !== 'section'"
                       :data-attr:loading  "$loading === 'section'"}
        (tr [:action/save])]])))

(defn section-remove-dialog [{:keys [tr] :as req} {:section/keys [name]}]
  (let [loading-id (pr-str (str name))]
    [:wa-dialog {:id    (ui2/remove-dialog-id "section" name)
                 :label (tr [:action/confirm-generic])}
     [:p (tr [:band-settings/section-delete-confirm] {:section-name name})]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/cancel])]
     [button/Button {:slot               "footer"
                     :appearance         "filled"
                     :variant            "danger"
                     :data-dialog        "close"
                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                     :data-attr:loading  (str "$loading === " loading-id)
                     :data-id            name
                     :data-action        (d*/act req ::actions/delete-section)}
      (tr [:action/confirm-delete])]]))

(defn section-table-row [{:keys [tr] :as req} {:section/keys [name active?]}]
  (let [button-id  (str "section-actions-" name)
        loading-id (pr-str (str name))]
    [:tr {:id (str "section-container-" name)}
     [:td {:class "align-middle"} name]
     [:td {:class "align-middle"} (ui2/active-badge tr active?)]
     [:td {:class "align-top text-right"}
      (ui2/row-action-menu
       {:button-id button-id
        :items     [{:label              (tr [:action/update])
                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                     :data-attr:loading  (str "$loading === " loading-id)
                     :data-id            name
                     :data-action        (d*/act req ::actions/open-section-edit)}
                    {:label       (tr [:action/remove])
                     :variant     "danger"
                     :data-dialog (format "open %s" (ui2/remove-dialog-id "section" name))}]})]]))

(defn sections-panel [{:keys [page-state db tr] :as req}]
  (let [sections (q/retrieve-sections db)]
    [:div {:id           "sections-panel"
           :data-signals (d*/->signals {:section-create  (:section-create page-state)
                                        :section         (:section page-state)
                                        :section-reorder (:section-reorder page-state)})}
     (section-create-form req)
     (section-edit-form req)
     (sections-reordering req sections)
     (for [section sections]
       (section-remove-dialog req section))
     (ui2/section-card
      {:title    (tr [:band-settings/section-manage-title])
       :subtitle (tr [:band-settings/section-manage-subtitle])
       :actions  [[button/Button {:appearance  "outlined"
                                  :variant     "brand"
                                  :size        "m"
                                  :data-id     "section-create"
                                  :data-action (d*/act req ::actions/open-section-create)}
                   (tr [:band-settings/section-add])]
                  [button/Button {:appearance  "outlined"
                                  :size        "m"
                                  :data-id     "section-reorder"
                                  :data-action (d*/act req ::actions/open-section-reorder)}
                   (tr [:action/reorder])]]}
      (ui2/table-shell
       (if (seq sections)
         [:table
          [:thead
           [:tr
            [:th (tr [:band-settings/section-name])]
            [:th (tr [:status-label])]
            [:th]]]
          [:tbody
           (for [section sections]
             (section-table-row req section))]]
         (ui2/empty-state
          (tr [:band-settings/section-empty-title])
          (tr [:band-settings/section-empty-subtitle])))))]))

(defn page [{:keys [tr] :as req}]
  (let [title (tr [:band-settings/section-title])]
    (ui2/datastar-page
     [:div {:class "wa-stack wa-gap-2xl"}
      [page-header/PageHeader
       {:breadcrumb [breadcrumb/Breadcrumb
                     {}
                     [breadcrumb/BreadcrumbItem {::breadcrumb/href "/band-settings"}
                      (tr [:band-settings/title])]
                     [breadcrumb/BreadcrumbItem title]]
        :title      title
        :subtitle   (tr [:band-settings/section-page-subtitle])}]
      (sections-panel req)])))

(d*/refresh-all!)
