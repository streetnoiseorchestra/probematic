(ns app.ui.dialog
  (:require
   [app.ui.core :as uic]))

(defn form-dialog [{:keys [id title on-hide on-show open]} & body]
  [:wa-dialog (uic/attr-map :id                       id
                            :data-attr-open open
                            :data-on-wa-show__case.kebab (when on-show on-show)
                            :data-on-wa-hide__case.kebab__debounce.300ms (when on-hide on-hide)
                            :with-header  (when title title)
                            :label title)
   [:div {:class "px-2"}
    body]])

(defn confirm-dialog [& {:keys [id title text confirm-text cancel-text icon on-confirm on-show on-hide]}]
  [:wa-dialog (uic/attr-map :id id
                            :class "cloak"
                            :data-signals  (format  "{'%s': false}" id)
                            :data-class (format "{'cloak': !$%s}" id)
                            :data-attr-open (format "$%s" id)
                            :data-on-wa-show__case.kebab (when on-show on-show)
                            :data-on-wa-hide__case.kebab__debounce.300ms (when on-hide on-hide))
   [:div {:class "sm:flex sm:items-start"}
    (when icon
      [:div {:class "mx-auto flex size-12 shrink-0 items-center justify-center rounded-full bg-red-100 sm:mx-0 sm:size-10"}
       (icon {:class "size-6 text-red-600"})])
    [:div
     {:class "mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left"}
     [:h3 {:class "text-base font-semibold text-gray-900"} title]
     [:div
      {:class "mt-2"}
      [:p
       {:class "text-sm text-gray-500"}
       text]]]]
   [:div
    {:class "mt-5 sm:mt-4 sm:flex sm:flex-row-reverse"}
    [:button (merge
              {:type          "button"
               :data-on-click (when on-confirm on-confirm)
               :data-dialog   "close"
               :class         "inline-flex w-full justify-center rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white shadow-xs hover:bg-red-500 sm:ml-3 sm:w-auto"})
     confirm-text]
    [:button (merge  {:type        "button"
                      :data-dialog "close"
                      :class       "mt-3 inline-flex w-full justify-center rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 ring-1 shadow-xs ring-gray-300 ring-inset hover:bg-gray-50 sm:mt-0 sm:w-auto"})
     cancel-text]]])
