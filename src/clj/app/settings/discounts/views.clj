(ns app.settings.discounts.views
  (:require
   [app.datastar :as d*]
   [app.queries :as q]
   [app.settings.discounts.actions :as actions]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn travel-discount-type-create-form [{:keys [page-state] :as req}]
  (let [{:keys [error]} (:discount-type-create page-state)
        dt-name-error   (-> error :discount-type-name :error)]
    (when (get-in page-state [:discount-type-create :open])
      [:wa-dialog {:id                    "discount-type-create-dialog"
                   :label                 [:i18n/tr :band-settings/travel-discount-type-add]
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-discount-type-create)))}
       [:form {:id             "dt-create"
               :data-id        "discount-type-create"
               :data-action    (d*/act req ::actions/create-discount-type)
               :data-on:submit "evt.preventDefault();"}
        [:wa-input {:placeholder  "Klimaticket Mond"
                    :type         :text
                    :required     true
                    :label        [:i18n/tr :band-settings/travel-discount-type-name]
                    :autofocus    true
                    :hint         dt-name-error
                    :data-invalid (if dt-name-error "true" nil)
                    :data-bind    "discount-type-create.discount-type-name"
                    :name         :discount-type-name}]]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        [:i18n/tr :action/cancel]]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :data-attr:disabled "!!$loading && $loading !== 'discount-type-create'"
                       :data-attr:loading  "$loading === 'discount-type-create'"
                       :form               "dt-create"}
        [:i18n/tr :action/create]]])))

(defn travel-discount-type-edit-form [{:keys [page-state] :as req}]
  (let [{:keys [error discount-type-id]} (:discount-type page-state)
        dt-name-error                    (-> error :discount-type-name :error)]
    (when discount-type-id
      [:wa-dialog {:id                    "discount-type-edit-dialog"
                   :label                 [:i18n/tr :band-settings/travel-discount-type-name]
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-discount-type-edit)))}
       [:form {:id             "dt-edit-form"
               :data-id        "discount-type"
               :data-action    (d*/act req ::actions/update-discount-type)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type :hidden :name "discount-type.discount-type-id" :value nil}]
        [:wa-input {:placeholder  "Klimaticket Mond"
                    :type         :text
                    :required     true
                    :label        [:i18n/tr :band-settings/travel-discount-type-name]
                    :autofocus    true
                    :hint         dt-name-error
                    :data-invalid (if dt-name-error "true" nil)
                    :data-bind    "discount-type.discount-type-name"
                    :name         :discount-type-name}]
        [:wa-switch {:data-attr:checked "$discount-type.discount-type-enabled"
                     :data-on:change    "$discount-type.discount-type-enabled = !$discount-type.discount-type-enabled"}
         [:i18n/tr :status-active]]]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        [:i18n/tr :action/cancel]]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "dt-edit-form"
                       :data-attr:disabled "!!$loading && $loading !== 'discount-type'"
                       :data-attr:loading  "$loading === 'discount-type'"}
        [:i18n/tr :action/save]]])))

(defn travel-discount-type-remove-dialog [req {:travel.discount.type/keys [discount-type-id discount-type-name]}]
  [:wa-dialog {:id    (ui2/remove-dialog-id "discount-type" discount-type-id)
               :label [:i18n/tr :action/confirm-generic]}
   [:p [:i18n/tr :band-settings/travel-discount-delete-confirm {:discount-type-name discount-type-name}]]
   [button/Button {:slot        "footer"
                   :appearance  "outlined"
                   :data-dialog "close"}
    [:i18n/tr :action/cancel]]
   [button/Button {:slot               "footer"
                   :appearance         "filled"
                   :variant            "danger"
                   :data-dialog        "close"
                   :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                   :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                   :data-id            discount-type-id
                   :data-action        (d*/act req ::actions/delete-discount-type)}
    [:i18n/tr :action/confirm-delete]]])

(defn travel-discount-type-table-row [req {:travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (let [button-id (str "discount-type-actions-" discount-type-id)]
    [:tr {:id (str "dt-container-" discount-type-id)}
     [:td {:class "align-middle"} discount-type-name]
     [:td {:class "align-middle"} (ui2/active-badge enabled?)]
     [:td {:class "align-top text-right"}
      (ui2/row-action-menu
       {:button-id button-id
        :items     [{:label              [:i18n/tr :action/update]
                     :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                     :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                     :data-id            discount-type-id
                     :data-action        (d*/act req ::actions/open-discount-type-edit)}
                    {:label       [:i18n/tr :action/remove]
                     :variant     "danger"
                     :data-dialog (format "open %s" (ui2/remove-dialog-id "discount-type" discount-type-id))}]})]]))

(defn travel-discount-types [{:keys [db page-state] :as req}]
  (let [discount-types (q/retrieve-all-discount-types db)]
    [:div {:id           "travel-discount-types"
           :data-signals (d*/->signals {:discount-type-create (:discount-type-create page-state)
                                        :discount-type        (:discount-type page-state)})}
     (ui2/section-card
      {:title    [:i18n/tr :band-settings/travel-discount-manage-title]
       :subtitle [:i18n/tr :band-settings/travel-discount-manage-subtitle]
       :actions  [[button/Button {:appearance  "outlined"
                                  :variant     "brand"
                                  :size        "m"
                                  :data-id     "discount-type-create"
                                  :data-action (d*/act req ::actions/open-discount-type-create)}
                   [:i18n/tr :band-settings/travel-discount-type-add]]]}
      (ui2/table-shell
       (if (seq discount-types)
         [:table
          [:thead
           [:tr
            [:th [:i18n/tr :band-settings/travel-discount-type-name]]
            [:th [:i18n/tr :status-label]]
            [:th]]]
          [:tbody
           (for [discount-type discount-types]
             (travel-discount-type-table-row req discount-type))]]
         (ui2/empty-state
          [:i18n/tr :band-settings/travel-discount-empty-title]
          [:i18n/tr :band-settings/travel-discount-empty-subtitle]))))
     (travel-discount-type-create-form req)
     (travel-discount-type-edit-form req)
     (for [discount-type discount-types]
       (travel-discount-type-remove-dialog req discount-type))]))

(defn page [req]
  (let [title [:i18n/tr :band-settings/travel-discount-title]]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :standard
       ::page-surface/toolbar
       [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                  [breadcrumb/Breadcrumb {}
                                   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/band-settings"}
                                    [:i18n/tr :band-settings/title]]
                                   [breadcrumb/BreadcrumbItem title]]
                                  :aria-label [:i18n/tr :band-settings/toolbar-label]}]}
      [:div {:class "wa-stack wa-gap-l"}
       [page-header/PageHeader
        {:title    title
         :subtitle [:i18n/tr :band-settings/travel-discount-page-subtitle]}]
       (travel-discount-types req)]])))

(d*/refresh-all!)
