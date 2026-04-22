(ns app.ui2.form
  (:require
   [app.datastar :as d*]
   [app.ui2.core :as uic]
   [app.ui2.input :as input]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]
   [malli.experimental.lite :as l]))

(def FormSchema
  {:ns      :keyword
   :command :string
   :fields  :map})

(def NameAttribute
  [:name {:doc           "The field name html attribute."
          :error/message "is required. All form fields require a name attribute as a string or keyword."}
   [:or :string :keyword]])

(defn data-class [m]
  (str "{"
       (->> m
            (map (fn [[expr classes]]
                   (format "\"%s\": %s" classes expr)))
            (str/join ","))
       "}"))

(defn field-signal-name [{:keys [ns]} field-name]
  (let [top-ns         (name ns)
        field-name-str (if (keyword? field-name)
                         (name field-name)
                         field-name)]
    [(str top-ns "." field-name-str)
     (str top-ns ".error." field-name-str)
     (str top-ns ".touched." field-name-str)]))

(def doc-form
  {:examples ["[form/Form {::form/form {:ns :team :command \"saveTeam()\" :fields {:team-name \"\"}}} [...]]"]
   :ns       *ns*
   :name     'Form
   :desc     "An HTML form that manages Datastar signals."
   :alias    ::form
   :schema
   [:map {}
    [::form {:doc "Form configuration"}
     (l/schema FormSchema)]]})

(def ^{:doc (uic/generate-docstring doc-form)} Form ::form)

(defmethod c/resolve-alias ::form
  [_ {::keys [form] :as attrs} children]
  (uic/validate-opts! doc-form attrs)
  (cc/compile
   [:form (uic/merge-attrs attrs
                           :data-signals__ifmissing (d*/->signals
                                                     {(:ns form)
                                                      (merge (:fields form)
                                                             {:touched (zipmap (keys (:fields form)) (repeat 0))
                                                              :error   (-> (keys (:fields form))
                                                                           (zipmap (repeat nil))
                                                                           (assoc :_top nil))})})
                           :data-on:submit (:command form))
    children]))

(def doc-actions
  {:examples ["[form/Actions {::form/left [:div \"Cancel\"] ::form/right [:div \"Save\"]}]"]
   :ns       *ns*
   :name     'Actions
   :desc     "Form actions container for left and right button groups."
   :alias    ::actions
   :schema
   [:map {}
    [::left {:optional true
             :doc      "Content for the left side of the actions row"}
     :any]
    [::right {:optional true
              :doc      "Content for the right side of the actions row"}
     :any]]})

(def ^{:doc (uic/generate-docstring doc-actions)} Actions ::actions)

(defmethod c/resolve-alias ::actions
  [_ {::keys [left right] :as attrs} _children]
  (uic/validate-opts! doc-actions attrs)
  (cc/compile
   [:div {:class "py-5 flex justify-between items-center"}
    [:div {:class "flex items-center space-x-3 space-x-4"}
     left]
    [:div {:class "flex justify-end space-x-4"}
     right]]))

(def doc-root-errors
  {:examples ["[form/RootErrors {::form/form form-config ::form/title \"Errors\"}]"]
   :ns       *ns*
   :name     'RootErrors
   :desc     "Displays top-level form errors."
   :alias    ::errors
   :schema
   [:map {}
    [::title {:optional true
              :doc      "Title for the error section"}
     :string]
    [::form {:doc "Form configuration"}
     (l/schema FormSchema)]]})

(def ^{:doc (uic/generate-docstring doc-root-errors)} RootErrors ::errors)

(defmethod c/resolve-alias ::errors
  [_ {::keys [form title] :as attrs} children]
  (uic/validate-opts! doc-root-errors attrs)
  (let [$top-error-signal (str "$" (name (:ns form)) ".error._top")]
    (cc/compile
     [:div (uic/merge-attrs attrs
                            :class "hidden mt-1 text-red-700"
                            :data-class:hidden (str "!" $top-error-signal))
      (when title
        [:h3 {:class "text-sm font-semibold text-red-700"} title])
      [:p {:class     "mt-1 text-sm/6 text-sm text-red-600"
           :data-text $top-error-signal}]
      [:div children]])))

(def doc-section
  {:examples ["[form/Section {::form/title \"Team\" ::form/subtitle \"Edit team details\"}]..."]
   :ns       *ns*
   :name     'Section
   :desc     "A section within a form with optional title and subtitle."
   :alias    ::section
   :schema
   [:map {}
    [::title {:optional true :doc "Section title"} :any]
    [::subtitle {:optional true :doc "Section subtitle"} :any]
    [::narrow? {:optional true :doc "When true, uses a narrower grid layout"} :boolean]
    [::compact? {:optional true :doc "When true, uses more compact spacing"} :boolean]]})

(def ^{:doc (uic/generate-docstring doc-section)} Section ::section)

(defmethod c/resolve-alias ::section
  [_ {::keys [title subtitle compact? narrow?] :as attrs} children]
  (uic/validate-opts! doc-section attrs)
  (cc/compile
   [:div (uic/merge-attrs attrs :class (uic/cs
                                        "border-b border-gray-900/10"
                                        (if compact? "pb-6" "pb-12")))
    (when title
      [:h2 {:class "text-base/7 font-semibold text-gray-900"} title])
    (when subtitle
      [:p {:class "mt-1 text-sm/6 text-gray-600"} subtitle])
    [:div {:class (uic/cs "grid grid-cols-1 gap-x-6 gap-y-8"
                          (if narrow? "sm:grid-cols-3" "sm:grid-cols-6")
                          (if compact? "mt-4" "mt-10"))}
     children]]))

(defn control
  [attrs input-fn]
  (let [class                                (:class attrs)
        {:keys [id name] :as attrs}          (dissoc attrs :class)
        {::keys [form label required? description variant]
         :or    {required? true}}            attrs
        id                                   (or id (str "form-control" (:ns form) name))
        description-id                       (str "_" id "-description")
        required-id                          (str "_" id "-required")
        error-id                             (str "_" id "-error")
        aria-describedby                     (cond
                                               description description-id
                                               required?   required-id
                                               :else       nil)
        [signal error-signal _touched-signal] (field-signal-name form name)
        $error-signal                        (str "$" error-signal)
        default-value                        (get-in form [:fields name])
        hidden?                              (= variant :hidden)]
    (assert name "form controls require a :name")
    (cc/compile
     [:div {:class class}
      (when-not hidden?
        [:div {:class "flex justify-between"}
         [:label {:for id :class "block text-sm/6 font-medium text-gray-900"} label]
         (when required?
           [:span {:class "text-sm/6 text-red-400" :id required-id}
            "required"])])
      [:div {:data-class (data-class {$error-signal "grid grid-cols-1"})
             :class      (uic/cs (when-not hidden? "mt-2"))}
       (input-fn (uic/merge-attrs attrs
                                  :id               id
                                  :value            default-value
                                  :data-bind        signal
                                  :aria-describedby aria-describedby
                                  :aria-label       (when hidden? label))
                 {:required?     required?
                  :$error-signal $error-signal})]
      (when (and (not hidden?) description)
        [:p {:class     "mt-2 text-sm text-gray-500"
             :id        description-id
             :data-show (str "!" $error-signal)}
         description])
      [:p {:class     "mt-2 text-sm text-red-600"
           :id        error-id
           :data-show $error-signal
           :data-text $error-signal}]])))

(def doc-hidden-input
  {:examples ["[form/HiddenInput {::form/form form-config :name :team-id}]"]
   :ns       *ns*
   :name     'HiddenInput
   :desc     "A hidden form input."
   :alias    ::hidden
   :schema
   [:map {}
    [::form {:doc "Form configuration"}
     (l/schema FormSchema)]
    NameAttribute]})

(def ^{:doc (uic/generate-docstring doc-hidden-input)} HiddenInput ::hidden)

(defmethod c/resolve-alias ::hidden
  [_ {::keys [form] :keys [id name] :as attrs} _children]
  (uic/validate-opts! doc-hidden-input attrs)
  (let [id            (or id (str "form-control" (:ns form) name))
        [signal _ _]  (field-signal-name form name)
        default-value (get-in form [:fields name])]
    (cc/compile
     [:input (uic/merge-attrs attrs
                              :type      "hidden"
                              :id        id
                              :value     default-value
                              :data-bind signal)])))

(def doc-input
  {:examples ["[form/Input {::form/form form-config ::form/label \"Team Name\" :name :team-name}]"]
   :ns       *ns*
   :name     'Input
   :desc     "A standard text input for forms."
   :alias    ::input
   :schema
   [:map {}
    [::label {:doc "Label text for the input"} :string]
    [::form {:doc "Form configuration"} (l/schema FormSchema)]
    [::description {:optional true :doc "Description or help text"} :string]
    [::variant {:optional true :doc "Input layout variant"} [:enum :hidden]]
    [::required? {:optional true :default true :doc "Whether the field is required"} :boolean]
    NameAttribute]})

(def ^{:doc (uic/generate-docstring doc-input)} Input ::input)

(defmethod c/resolve-alias ::input
  [_ attrs _children]
  (uic/validate-opts! doc-input attrs)
  (control attrs
           (fn [attrs {:keys [required? $error-signal]}]
             [:input (uic/merge-attrs attrs
                                      :required required?
                                      :class "block w-full rounded-md bg-white py-1.5 text-base outline-1 -outline-offset-1 focus:outline-2 focus:-outline-offset-2 sm:text-sm/6"
                                      :data-class (data-class {$error-signal           "col-start-1 row-start-1 pr-10 pl-3 text-red-900 outline-red-300 placeholder:text-red-300 focus:outline-red-600 sm:pr-9"
                                                               (str "!" $error-signal) "px-3 text-gray-900 outline-gray-300 placeholder:text-gray-400 focus:outline-sno-orange-600"})
                                      :data-attr:aria-invalid $error-signal)])))

(def doc-toggle
  {:examples ["[form/Toggle {::form/form form-config ::form/label \"Enabled\" :name :team-enabled :value \"enabled\"}]"]
   :ns       *ns*
   :name     'Toggle
   :desc     "A toggle checkbox bound to a boolean signal."
   :alias    ::toggle
   :schema
   [:map {}
    [::label {:optional true :doc "Label text for the toggle"} :string]
    [::form {:doc "Form configuration"} (l/schema FormSchema)]
    NameAttribute]})

(def ^{:doc (uic/generate-docstring doc-toggle)} Toggle ::toggle)

(defmethod c/resolve-alias ::toggle
  [_ attrs _children]
  (uic/validate-opts! doc-toggle attrs)
  (control attrs
           (fn [attrs {:keys [$error-signal]}]
             (let [checked      (:value attrs)
                   value        (:value attrs)
                   real-signal  (:data-bind attrs)
                   $real-signal (str "$" real-signal)
                   signal       (str real-signal "ref")
                   $signal      (str "$" signal)]
               (input/toggle-checkbox
                (uic/merge-attrs (dissoc attrs :value :data-bind)
                                 :value value
                                 :checked checked
                                 :data-ref signal
                                 :data-attr:aria-invalid $error-signal
                                 :data-on:change (str $real-signal " = " $signal ".checked")))))))

(def doc-select
  {:examples ["[form/Select {::form/form form-config ::form/label \"Team Type\" :name :team-type ::form/options [{:value \"finance\" :label \"Finance\"}]}]"]
   :ns       *ns*
   :name     'Select
   :desc     "A select control for forms."
   :alias    ::select
   :schema
   [:map {}
    [::label {:doc "Label text for the select"} :string]
    [::form {:doc "Form configuration"} (l/schema FormSchema)]
    [::options {:doc "Select options"} :any]
    [::variant {:optional true :doc "Select layout variant"} [:enum :hidden]]
    [::required? {:optional true :default true :doc "Whether the field is required"} :boolean]
    NameAttribute]})

(def ^{:doc (uic/generate-docstring doc-select)} Select ::select)

(defmethod c/resolve-alias ::select
  [_ {::keys [options] :as attrs} _children]
  (uic/validate-opts! doc-select attrs)
  (control attrs
           (fn [attrs {:keys [required? $error-signal]}]
             (input/select (uic/merge-attrs (assoc attrs :options options :required? required?)
                                            :data-class (data-class {$error-signal           "text-red-900 outline-red-300 placeholder:text-red-300 focus:outline-red-600"
                                                                     (str "!" $error-signal) "text-gray-900 outline-gray-300 focus:outline-sno-orange-600"})
                                            :data-attr:aria-invalid $error-signal)))))
