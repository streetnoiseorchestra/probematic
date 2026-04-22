(ns app.ui2.layout
  (:require
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-panel
  {:examples ["[layout/Panel {::layout/title \"Teams\" ::layout/subtitle \"Because someone has to do the work\"}] ...]"]
   :ns       *ns*
   :as       'layout
   :name     'Panel
   :desc     "A white card panel with optional title, subtitle, and header actions."
   :alias    ::panel
   :schema
   [:map {}
    [::title {:doc "Panel title"} :any]
    [::subtitle {:optional true
                 :doc      "Panel subtitle"} :any]
    [::buttons {:optional true
                :doc      "Header action buttons"} :any]]})

(def ^{:doc (uic/generate-docstring doc-panel)} Panel
  ::panel)

(defmethod c/resolve-alias ::panel
  [_ {::keys [title subtitle buttons] :as attrs} children]
  (uic/validate-opts! doc-panel attrs)
  (cc/compile
   [:div (uic/merge-attrs attrs :class "mx-auto mt-8 grid max-w-3xl grid-cols-1 gap-6 sm:px-6 lg:max-w-7xl lg:grid-flow-col-dense lg:grid-cols-3")
    [:div {:class "space-y-6 lg:col-span-3 lg:col-start-1"}
     [:section
      [:div {:class "bg-white shadow-sm sm:rounded-lg"}
       (when (or title buttons)
         [:div {:class "px-4 py-5 px-6 flex items-center justify-between"}
          (when (or title subtitle)
            [:div {:class "w-full"}
             [:h2 {:class "text-lg font-medium leading-6 text-gray-900"} title]
             (when subtitle
               [:p {:class "text-sm font-medium text-gray-500 w-full"}
                subtitle])])
          [:div {:class "space-x-2 flex"}
           buttons]])
       [:div {:class "border-t border-gray-200 px-4 py-5 sm:px-6"}
        children]]]]]))
