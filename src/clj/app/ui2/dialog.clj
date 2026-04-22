(ns app.ui2.dialog
  (:require
   [app.ui2.button :as btn]
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-alert-icon
  {:examples ["[dialog/AlertIcon]"
              "[dialog/AlertIcon {:class \"size-10\"}]"]
   :ns       *ns*
   :as       'dialog
   :name     'AlertIcon
   :desc     "A red alert icon wrapper for confirm dialogs."
   :alias    ::alert-icon
   :schema   [:map {}]})

(def ^{:doc (uic/generate-docstring doc-alert-icon)} AlertIcon
  ::alert-icon)

(defmethod c/resolve-alias ::alert-icon
  [_ attrs _children]
  (uic/validate-opts! doc-alert-icon attrs)
  (cc/compile
   [:div (uic/merge-attrs attrs :class "mx-auto flex size-12 shrink-0 items-center justify-center rounded-full bg-red-100 sm:mx-0 sm:size-10")
    [:svg {:class "size-6 text-red-600"
           :fill "none"
           :viewBox "0 0 24 24"
           :stroke-width "1.5"
           :stroke "currentColor"
           :aria-hidden "true"
           :data-slot "icon"}
     [:path {:stroke-linecap  "round"
             :stroke-linejoin "round"
             :d               "M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"}]]]))

(def doc-form-dialog
  {:examples ["[dialog/FormDialog {:id \"edit-team-1\" ::dialog/title \"Edit Team\" ::dialog/on-hide \"closeTeam()\"} [:div \"Body\"]]"]
   :ns       *ns*
   :as       'dialog
   :name     'FormDialog
   :desc     "A dialog wrapper for form content."
   :alias    ::form-dialog
   :schema
   [:map {}
    [:id {:doc "Dialog id"} :string]
    [::title {:doc "Dialog title"} :string]
    [::on-hide {:doc "Expression to run when the dialog hides"} :string]
    [::on-show {:optional true
                :doc      "Expression to run when the dialog shows"} :string]
    [::open {:optional true
             :doc      "Datastar expression controlling dialog open state"} :string]]})

(def ^{:doc (uic/generate-docstring doc-form-dialog)} FormDialog
  ::form-dialog)

(defmethod c/resolve-alias ::form-dialog
  [_ {::keys [title on-hide on-show open] :keys [id] :as attrs} children]
  (uic/validate-opts! doc-form-dialog attrs)
  (let [modal-title-id (str "modal-title-" id)
        modal-body-id  (str "modal-body-" id)]
    (cc/compile
     [:my-dialog (uic/attr-map :id                                    id
                               :body-id                               modal-body-id
                               :aria-labelledby                      modal-title-id
                               :data-attr:open                       open
                               :data-on:my-show__case.kebab          on-show
                               :data-on:my-hide__case.kebab__debounce.300ms on-hide)
      [:div {:class "relative z-10"}
       [:div {:data-dialog-backdrop true
              :class                "fixed inset-0 bg-gray-500/75 transition-opacity"
              :aria-hidden          "true"}]
       [:div {:class "fixed inset-0 z-10 w-screen overflow-y-auto"}
        [:div {:class "flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0"}
         [:div {:id               modal-body-id
                :data-dialog-body true
                :class            "relative transform overflow-hidden rounded-lg bg-white px-4 pt-5 pb-4 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6"}
          [:div {:class "sm:flex sm:items-start"}
           [:div {:class "mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left"}
            [:h3 {:class "text-base font-semibold text-gray-900"
                  :id    modal-title-id}
             title]
            [:div {:class "mt-2"}
             children]]]]]]]])))

(defn- render-icon [icon]
  (cond
    (nil? icon) nil
    (keyword? icon) [icon]
    (vector? icon) icon
    (fn? icon) (icon {})
    :else icon))

(def doc-confirm-dialog
  {:examples ["[dialog/ConfirmDialog {:id \"delete-team-1\" ::dialog/title \"Confirm\" ::dialog/prompt \"Delete this team?\" ::dialog/confirm-text \"Delete\" ::dialog/cancel-text \"Cancel\"}]"]
   :ns       *ns*
   :as       'dialog
   :name     'ConfirmDialog
   :desc     "A confirmation dialog with confirm and cancel actions."
   :alias    ::confirm-dialog
   :schema
   [:map {}
    [:id {:doc "Dialog id"} :string]
    [::icon {:optional true
             :doc      "Optional icon component or hiccup"} :any]
    [::title {:doc "Dialog title"} :string]
    [::prompt {:doc "Dialog prompt text"} :string]
    [::confirm-text {:doc "Confirm button text"} :string]
    [::cancel-text {:doc "Cancel button text"} :string]
    [::on-confirm {:optional true
                   :doc      "Expression to run on confirm"} :string]
    [::on-show {:optional true
                :doc      "Expression to run when the dialog shows"} :string]
    [::on-hide {:optional true
                :doc      "Expression to run when the dialog hides"} :string]]})

(def ^{:doc (uic/generate-docstring doc-confirm-dialog)} ConfirmDialog
  ::confirm-dialog)

(defmethod c/resolve-alias ::confirm-dialog
  [_ {::keys [icon title prompt confirm-text cancel-text on-confirm on-show on-hide]
      :keys   [id]
      :as     attrs}
   _children]
  (uic/validate-opts! doc-confirm-dialog attrs)
  (let [modal-title-id (str "modal-title-" id)]
    (cc/compile
     [:my-dialog (uic/attr-map :id                                    id
                               :class                                 "cloak"
                               :aria-labelledby                      modal-title-id
                               :data-signals                         (format "{'%s': false}" id)
                               :data-class                           (format "{'cloak': !$%s}" id)
                               :data-attr:open                       (format "$%s" id)
                               :data-on:my-show__case.kebab          on-show
                               :data-on:my-hide__case.kebab__debounce.300ms on-hide)
      [:div {:class "relative z-10"}
       [:div {:class "fixed inset-0 bg-gray-500/75 transition-opacity"
              :aria-hidden "true"}]
       [:div {:class "fixed inset-0 z-10 w-screen overflow-y-auto"}
        [:div {:class "flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0"}
         [:div {:class "relative transform overflow-hidden rounded-lg bg-white px-4 pt-5 pb-4 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6"}
          [:div {:class "sm:flex sm:items-start"}
           (render-icon icon)
           [:div {:class "mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left"}
            [:h3 {:class "text-base font-semibold text-gray-900"
                  :id    modal-title-id}
             title]
            [:div {:class "mt-2"}
             [:p {:class "text-sm text-gray-500"}
              prompt]]]]
          [:div {:class "mt-5 sm:mt-4 sm:flex sm:flex-row-reverse"}
           [btn/Button {::btn/intent :destructive
                        :type         "button"
                        :data-on:click on-confirm
                        :data-dialog   "close"}
            confirm-text]
           [btn/Button {::btn/intent :secondary
                        :type         "button"
                        :data-dialog   "close"
                        :class         "mt-3 sm:mt-0"}
            cancel-text]]]]]]])))
