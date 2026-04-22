(ns app.ui2.layout
  (:require
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-page-header
  {:examples ["[layout/PageHeader {::layout/title \"Band Settings\"}]"
              "[layout/PageHeader {::layout/title \"Band Settings\" ::layout/subtitle \"Manage teams\" ::layout/buttons [:button \"Create\"]}]"
   ]
   :ns       *ns*
   :as       'layout
   :name     'PageHeader
   :desc     "A full-width page header with optional subtitle and action buttons."
   :alias    ::page-header
   :schema
   [:map {}
    [::title {:doc "Header title"} :any]
    [::subtitle {:optional true
                 :doc      "Header subtitle"} :any]
    [::buttons {:optional true
                :doc      "Header action buttons"} :any]]})

(def ^{:doc (uic/generate-docstring doc-page-header)} PageHeader
  ::page-header)

(defmethod c/resolve-alias ::page-header
  [_ {::keys [title subtitle buttons] :as attrs} _children]
  (uic/validate-opts! doc-page-header attrs)
  (cc/compile
   [:div (uic/merge-attrs attrs :class "border-b border-gray-200 px-4 py-4 sm:flex sm:items-center sm:justify-between sm:px-6 lg:px-8 bg-white")
    [:div {:class "min-w-0 flex-1"}
     [:h1 {:class "text-lg font-medium leading-6 text-gray-900"}
      title]
     (when subtitle
       [:p {:class "text-sm font-medium text-gray-500"}
        subtitle])]
    [:div {:class "justify-stretch mt-6 flex flex-col-reverse space-y-4 space-y-reverse sm:flex-row-reverse sm:justify-end sm:space-y-0 sm:space-x-3 sm:space-x-reverse md:mt-0 md:flex-row md:space-x-3"}
     buttons]]))

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
