(ns app.settings.discounts.views
  (:require
   [app.datastar :as d*]
   [app.queries :as q]
   [app.settings.discounts.actions :as actions]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn travel-discount-type-create-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error]} (:discount-type-create page-state)
        dt-name-error   (-> error :discount-type-name :error)]
    (when (get-in page-state [:discount-type-create :open])
      [:wa-dialog {:id                    "discount-type-create-dialog"
                   :label                 (tr [:travel-discounts/add-discount-type])
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
                    :label        (tr [:travel-discounts/discount-type-name])
                    :autofocus    true
                    :hint         dt-name-error
                    :data-invalid (if dt-name-error "true" nil)
                    :data-bind    "discount-type-create.discount-type-name"
                    :name         :discount-type-name}]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :data-attr:disabled "!!$loading && $loading !== 'discount-type-create'"
                    :data-attr:loading  "$loading === 'discount-type-create'"
                    :form               "dt-create"}
        (tr [:action/create])]])))

(defn travel-discount-type-edit-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error discount-type-id]} (:discount-type page-state)
        dt-name-error                    (-> error :discount-type-name :error)]
    (when discount-type-id
      [:wa-dialog {:id                    "discount-type-edit-dialog"
                   :label                 (tr [:travel-discounts/discount-type-name])
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
                    :label        (tr [:travel-discounts/discount-type-name])
                    :autofocus    true
                    :hint         dt-name-error
                    :data-invalid (if dt-name-error "true" nil)
                    :data-bind    "discount-type.discount-type-name"
                    :name         :discount-type-name}]
        [:wa-switch {:data-attr:checked "$discount-type.discount-type-enabled"
                     :data-on:change    "$discount-type.discount-type-enabled = !$discount-type.discount-type-enabled"}
         "Active"]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :form               "dt-edit-form"
                    :data-attr:disabled "!!$loading && $loading !== 'discount-type'"
                    :data-attr:loading  "$loading === 'discount-type'"}
        (tr [:action/save])]])))

(defn travel-discount-type-remove-dialog [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name]}]
  [:wa-dialog {:id    (ui2/remove-dialog-id "discount-type" discount-type-id)
               :label (tr [:action/confirm-generic])}
   [:p (tr [:action/confirm-delete-discount-type] [(str "\"" discount-type-name "\"")])]
   [:wa-button {:slot        "footer"
                :appearance  "outlined"
                :data-dialog "close"}
    (tr [:action/cancel])]
   [:wa-button {:slot               "footer"
                :appearance         "filled"
                :variant            "danger"
                :data-dialog        "close"
                :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                :data-id            discount-type-id
                :data-action        (d*/act req ::actions/delete-discount-type)}
    (tr [:action/confirm-delete])]])

(defn travel-discount-type-table-row [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (let [button-id (str "discount-type-actions-" discount-type-id)]
    [:tr {:id (str "dt-container-" discount-type-id)}
     [:td {:class "align-middle"} discount-type-name]
     [:td {:class "align-middle"} (ui2/active-badge tr enabled?)]
     [:td {:class "align-top text-right"}
      (ui2/row-action-menu
       {:button-id button-id
        :items     [{:label              (tr [:action/update])
                     :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                     :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                     :data-id            discount-type-id
                     :data-action        (d*/act req ::actions/open-discount-type-edit)}
                    {:label       (tr [:action/remove])
                     :variant     "danger"
                     :data-dialog (format "open %s" (ui2/remove-dialog-id "discount-type" discount-type-id))}]})]]))

(defn travel-discount-types [{:keys [db tr page-state] :as req}]
  (let [discount-types (q/retrieve-all-discount-types db)]
    [:div {:id           "travel-discount-types"
           :data-signals (d*/->signals {:discount-type-create (:discount-type-create page-state)
                                        :discount-type        (:discount-type page-state)})}
     (travel-discount-type-create-form req)
     (travel-discount-type-edit-form req)
     (for [discount-type discount-types]
       (travel-discount-type-remove-dialog req discount-type))
     (ui2/section-card
      {:title    "Manage travel discounts"
       :subtitle "Reusable labels for member travel discounts."
       :actions  [[:wa-button {:appearance  "outlined"
                               :variant     "brand"
                               :size        "m"
                               :data-id     "discount-type-create"
                               :data-action (d*/act req ::actions/open-discount-type-create)}
                   (tr [:travel-discounts/add-discount-type])]]}
      (ui2/table-shell
       (if (seq discount-types)
         [:table
          [:thead
           [:tr
            [:th (tr [:travel-discounts/discount-type-name])]
            [:th "Status"]
            [:th]]]
          [:tbody
           (for [discount-type discount-types]
             (travel-discount-type-table-row req discount-type))]]
         (ui2/empty-state
          "No travel discount types yet."
          "Add discount types so members can select them consistently."))))]))

(defn page [{:keys [tr] :as req}]
  (let [title (tr [:travel-discounts/title])]
    (ui2/datastar-page
     [:div {:class "wa-stack wa-gap-2xl"}
      (ui2/page-header
       {:breadcrumb [breadcrumb/Breadcrumb
                     {}
                     [breadcrumb/BreadcrumbItem {::breadcrumb/href "/band-settings"}
                      (tr [:nav/band-settings])]
                     [breadcrumb/BreadcrumbItem title]]
        :title      title
        :subtitle   "Manage the reusable travel discount types members can choose."})
      (travel-discount-types req)])))

(d*/refresh-all!)
