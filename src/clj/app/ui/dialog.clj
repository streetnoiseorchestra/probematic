(ns app.ui.dialog
  (:require [app.ui.core :as uic]
            [malli.experimental.lite :as l]))

(defn alert-icon [& args]
  (let [[_opts attrs _children]                  (uic/extract #'alert-icon args)]
    [:div (uic/merge-attrs attrs :class "mx-auto flex size-12 shrink-0 items-center justify-center rounded-full bg-red-100 sm:mx-0 sm:size-10")
     [:svg {:class "size-6 text-red-600"
            :fill "none",
            :viewBox "0 0 24 24",
            :stroke-width "1.5",
            :stroke "currentColor",
            :aria-hidden "true",
            :data-slot "icon"}
      [:path
       {:stroke-linecap "round",
        :stroke-linejoin "round",
        :d
        "M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"}]]]))

(defn form-dialog
  {:opts {:title :string
          :on-hide :string
          :on-show (l/optional :string)
          :open (l/optional :string)}}
  [& args]
  (let [[opts attrs children]                  (uic/extract #'form-dialog args)
        {:keys [id]} attrs
        {:keys [title on-hide on-show open]} opts
        modal-title-id (str "modal-title-" id)
        modal-body-id (str "modal-body-" id)]
    [:my-dialog (uic/attr-map :id                       id
                              :body-id modal-body-id
                              :aria-labelledby modal-title-id
                              :data-attr-open open
                              :data-on-my-show__case.kebab (when on-show on-show)
                              :data-on-my-hide__case.kebab__debounce.300ms (when on-hide on-hide))

     [:div {:class "relative z-10"}
      [:div {:data-dialog-backdrop true :class "fixed inset-0 bg-gray-500/75 transition-opacity" :aria-hidden "true"}]
      [:div {:class "fixed inset-0 z-10 w-screen overflow-y-auto"}
       [:div {:class "flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0"}
        [:div {:id modal-body-id :data-dialog-body true :class "relative transform overflow-hidden rounded-lg bg-white px-4 pt-5 pb-4 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6"}
         [:div {:class "sm:flex sm:items-start"}
          [:div
           {:class "mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left"}
           [:h3 {:class "text-base font-semibold text-gray-900" :id modal-title-id}
            title]
           [:div {:class "mt-2"}
            children]]]]]]]]))

(defn confirm-dialog
  {:opts {:icon          (l/optional fn?)
          :open (l/optional :boolean)
          :title :string
          :prompt :string
          :confirm-text :string
          :cancel-text :string
          :on-confirm (l/optional :string)
          :on-show (l/optional :string)
          :on-hide (l/optional :string)}}
  [& args]
  (let [[opts attrs _children]                  (uic/extract #'confirm-dialog args)
        {:keys [icon title prompt open confirm-text cancel-text on-confirm on-show on-hide]} opts
        {:keys [id]} attrs
        modal-title-id (str "modal-title-" id)]
    (assert id "Dialogs must have an id")
    [:my-dialog (uic/attr-map :id                       id
                              :class "cloak"
                              :aria-labelledby modal-title-id
                              :data-signals  (format  "{'%s': false}" id)
                              :data-class (format "{'cloak': !$%s}" id)
                              :data-attr-open (format "$%s" id)
                              :data-on-my-show__case.kebab (when on-show on-show)
                              :data-on-my-hide__case.kebab__debounce.300ms (when on-hide on-hide))
     [:div {:class "relative z-10"}
      [:div {:class "fixed inset-0 bg-gray-500/75 transition-opacity" :aria-hidden "true"}]
      [:div {:class "fixed inset-0 z-10 w-screen overflow-y-auto"}
       [:div {:class "flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0"}
        [:div {:class "relative transform overflow-hidden rounded-lg bg-white px-4 pt-5 pb-4 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6"}
         [:div {:class "sm:flex sm:items-start"}
          (when icon icon)
          [:div
           {:class "mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left"}
           [:h3 {:class "text-base font-semibold text-gray-900" :id modal-title-id}
            title]
           [:div {:class "mt-2"}
            [:p {:class "text-sm text-gray-500"}
             prompt]]]]
         [:div
          {:class "mt-5 sm:mt-4 sm:flex sm:flex-row-reverse"}
          [:button {:type "button"
                    :data-on-click (when on-confirm on-confirm)
                    :data-dialog   "close"
                    :class "inline-flex w-full justify-center rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white shadow-xs hover:bg-red-500 sm:ml-3 sm:w-auto"}
           confirm-text]
          [:button {:type "button"
                    :data-dialog "close"
                    :class "mt-3 inline-flex w-full justify-center rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 shadow-xs ring-1 ring-gray-300 ring-inset hover:bg-gray-50 sm:mt-0 sm:w-auto"}
           cancel-text]]]]]]]))
