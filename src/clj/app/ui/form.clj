(ns app.ui.form
  (:require [app.icons :as icon]
            [app.ui.core :as uic]
            [app.ui.input :as input]
            [clojure.string :as str]
            [jsonista.core :as j]
            [malli.experimental.lite :as l]))

(def FormSchema
  {:ns               :keyword
   :command          :string
   :live-validation? (l/optional :boolean)
   :fields           :map})

(defn data-class [m]
  (str "{"
       (->> m
            (map (fn [[expr classes]]
                   (format "\"%s\": %s" classes expr)))
            (str/join ","))
       "}"))

(defn field-signal-name [{:keys [ns] :as _form} field-name]
  (let [top-ns         (str (clojure.core/name ns))
        field-name-str (if (keyword? field-name)
                         (name field-name)
                         field-name)]
    [(str top-ns "." field-name-str)
     (str top-ns ".error." field-name-str)
     (str top-ns ".touched." field-name-str)]))

(defn validate-signal-name [{:keys [ns]}]
  (str
   (clojure.core/name ns)
   ".validate-only"))

(defn form
  {:opts {:form FormSchema}}
  [& args]
  (let [[opts attrs children] (uic/extract #'form args)
        {:keys [form]}        opts
        $validate-signal      (str "$" (validate-signal-name form))]

    [:form (uic/merge-attrs attrs
                            :data-signals__ifmissing (j/write-value-as-string
                                                      {(:ns form)
                                                       (merge (-> form :fields)
                                                              {:touched       (zipmap (-> form :fields keys) (repeat 0))
                                                               :validate-only (true? (:live-validation? form))
                                                               :error         (-> (-> form :fields keys)
                                                                                  (zipmap (repeat nil))
                                                                                  (assoc :_top nil))})})
                            :data-on:submit (str $validate-signal " = false; " (:command form)))
     children]))

(defn actions
  {:opts {:left  (l/optional :any)
          :right (l/optional :any)}}
  [& args]
  (let [[opts _attrs _children] (uic/extract #'actions args)
        {:keys [left right]}    opts]
    [:div {:class "py-5 flex justify-between items-center"
           #_"mt-6 flex items-center justify-end gap-x-6"}
     [:div {:class "flex items-center space-x-3 space-x-4"}
      left]
     [:div {:class "flex justify-end space-x-4"}
      right]]))

(defn errors
  "Renders top-level form errors, should be avoided when possible"
  {:opts {:title (l/optional :string)
          :form  FormSchema}}
  [& args]
  (let [[opts attrs children] (uic/extract #'errors args)
        {:keys [form title]}  opts
        $top-error-signal     (str "$" (clojure.core/name (:ns form)) ".error._top")]
    [:div (uic/merge-attrs attrs :class (uic/cs
                                         "hidden mt-1 text-red-700")
                           :data-class:hidden (str "!" $top-error-signal))
     (when title
       [:h3 {:class "text-sm font-semibold text-red-700"} title])
     [:p {:class     "mt-1 text-sm/6 text-sm text-red-600"
          :data-text $top-error-signal}]
     [:div {:class (uic/cs "")}
      children]]))

(defn section
  {:opts {:title    (l/optional :any)
          :subtitle (l/optional :any)
          :narrow?  (l/optional :boolean)
          :compact? (l/optional :boolean)}}
  [& args]
  (let [[opts attrs children]                     (uic/extract #'section args)
        {:keys [title subtitle compact? narrow?]} opts]
    [:div (uic/merge-attrs attrs :class (uic/cs
                                         "border-b border-gray-900/10"
                                         (if compact? "pb-6" "pb-12")))
     (when title
       [:h2 {:class "text-base/7 font-semibold text-gray-900"} title])
     (when subtitle
       [:p {:class "mt-1 text-sm/6 text-gray-600"} subtitle])
     [:div {:class (uic/cs "grid grid-cols-1 gap-x-6 gap-y-8 "
                           (if narrow? "sm:grid-cols-3" "sm:grid-cols-6")
                           (if compact? "mt-4" "mt-10"))}
      children]]))

(defn control
  [opts attrs input-fn]
  (let [class                                (:class attrs)
        {:keys [id name] :as attrs}          (dissoc attrs :class)
        {:keys [form label  required? description error variant error-icon?]
         :or   {required?   true
                error-icon? false}}          opts
        id                                   (or id (str "form-control" (:ns form) name))
        description-id                       (str "_" id "-description")
        required-id                          (str "_" id "-required")
        error-id                             (str "_" id "-error")
        aria-describedby                     (cond
                                               error       error-id
                                               description description-id
                                               required-id required-id
                                               :else       nil)
        [signal error-signal touched-signal] (field-signal-name form name)
        $error-signal                        (str "$" error-signal)
        $touched-signal                      (str "$" touched-signal)
        $validate-signal                     (str "$" (validate-signal-name form))
        default-value                        (get-in form [:fields name])
        hidden?                              (= variant :hidden)]
    (assert name "form-controls require an :name")
    [:div {:class (uic/cs class)}
     (when-not hidden?
       [:div  {:class "flex justify-between"}
        [:label {:for id :class "block text-sm/6 font-medium text-gray-900"} label]

        (when required?
          [:span {:class "text-sm/6 text-red-400" :id required-id}
           "required"])])
     [:div {:data-class (data-class {$error-signal "grid grid-cols-1"})
            :class      (uic/cs (when-not (= :hidden variant) "mt-2"))}
      (input-fn (uic/merge-attrs attrs
                                 :aria-describedby aria-describedby
                                 :aria-label (when (= variant :hidden) label)
                                 :data-bind        signal
                                 :data-on:blur (when (:live-validation? form) (str $touched-signal "++;" $validate-signal "= true;" (:command form)))
                                 :value default-value
                                 :id               id)
                {:required?      required?
                 :$error-signal  $error-signal
                 :description-id description-id
                 :error          error})
      (when error-icon?
        (icon/circle-exclamation {:class       "pointer-events-none col-start-1 row-start-1 mr-3 size-5 self-center justify-self-end text-red-500 sm:size-4"
                                  :aria-hidden "true"
                                  :data-show   $error-signal}))]
     (when (and (not error) (not hidden?) description)
       [:p {:class     "mt-2 text-sm text-gray-500"
            :id        description-id
            :data-show (str "!" $error-signal)}
        description])
     [:p {:class     "mt-2 text-sm text-red-600" :id error-id
          :data-show $error-signal
          :data-text $error-signal}]]))

(defn hidden
  {:opts {:form FormSchema}}
  [& args]
  (let [[opts attrs _children] (uic/extract #'hidden args)
        name                   (:name attrs)
        form                   (:form opts)
        id                     (or (:id attrs) (str "form-control" (:ns form) name))
        [signal _]             (field-signal-name form name)
        default-value          (get-in form [:fields name])]
    (assert name "hidden control requires a :name")
    [:input (uic/merge-attrs attrs {:type      "hidden"
                                    :id        id
                                    :value     default-value
                                    :data-bind signal})]))

(defn input
  {:opts {:label       :string
          :form        FormSchema
          :error       (l/optional :string)
          :description (l/optional :string)
          :suffix      (l/optional :any)
          :required?   (l/optional :boolean)}}
  [& args]
  (let [[opts attrs _children] (uic/extract #'input args)]
    (control opts attrs
             (fn [attrs {:keys [required? $error-signal]}]
               [:input (uic/merge-attrs attrs
                                        :required required?
                                        :class (uic/cs "block w-full rounded-md bg-white py-1.5 text-base outline-1 -outline-offset-1 focus:outline-2 focus:-outline-offset-2 sm:text-sm/6")
                                        :data-class
                                        (data-class {$error-signal           "col-start-1 row-start-1 pr-10 pl-3 text-red-900 outline-red-300 placeholder:text-red-300 focus:outline-red-600 sm:pr-9"
                                                     (str "!" $error-signal) "px-3 text-gray-900 outline-gray-300 placeholder:text-gray-400 focus:outline-sno-orange-600"})
                                        :data-attr:aria-invalid $error-signal)]))))

(defn toggle
  {:opts {:label (l/optional :string)

          :form     FormSchema
          :checked? (l/optional :boolean)}}
  [& args]
  (let [[opts attrs _children] (uic/extract #'toggle args)
        value                  (:value attrs)]
    (control opts attrs
             (fn [attrs {:keys [$error-signal]}]
               (let [checked      (:value attrs)
                     real-signal  (:data-bind attrs)
                     $real-signal (str "$" real-signal)
                     signal       (str real-signal "ref")
                     $signal      (str "$" signal)]
                 (input/toggle-checkbox
                  (uic/merge-attrs (into (uic/attr-map) (dissoc attrs
                                                                :value
                                                                :data-bind))
                                   :value value
                                   :checked checked
                                   :data-ref signal
                                   :data-attr:aria-invalid $error-signal
                                   :data-on:change (str $real-signal " = " $signal ".checked"))))))))
(defn checkbox
  {:opts {:label       (l/optional :string)
          :description (l/optional :string)
          :form        :map
          :checked?    (l/optional :boolean)}}
  [& args]
  (let [[opts attrs _children]      (uic/extract #'toggle args)
        value                       (:value attrs)
        {:keys [label description]} opts]
    (control (assoc opts :variant :hidden) attrs
             (fn [attrs {:keys [$error-signal description-id]}]
               (let [checked      (:value attrs)
                     id           (:id attrs)
                     real-signal  (:data-bind attrs)
                     $real-signal (str "$" real-signal)
                     signal       (str real-signal "ref")
                     $signal      (str "$" signal)]
                 [:div
                  {:class "space-y-5"}
                  [:div {:class "flex gap-3"}
                   (input/checkbox
                    (uic/merge-attrs (into (uic/attr-map) (dissoc attrs :value :data-bind))
                                     :value value
                                     :checked checked
                                     :data-ref signal
                                     :data-attr:aria-invalid $error-signal
                                     :data-on:change (str $real-signal " = " $signal ".checked")))
                   [:div
                    {:class "text-sm/6"}
                    [:label {:for id :class "font-medium text-gray-900"} label]
                    (when description
                      [:p {:id description-id, :class "text-gray-500"} description])]]])))))

(defn select
  {:opts {:label       :string
          :form        FormSchema
          :error       (l/optional :string)
          :description (l/optional :string)
          :suffix      (l/optional :any)
          :required?   (l/optional :boolean)
          :options     :any}}
  [& args]

  (let [[opts attrs _children] (uic/extract #'select args)
        {:keys [options]}      opts]
    (control (assoc  opts :error-icon? false) attrs
             (fn [attrs {:keys [required? $error-signal]}]
               (input/select (uic/merge-attrs attrs
                                              :-options         options
                                              :-required?        required?
                                              :data-class
                                              (data-class {$error-signal           "text-red-900 outline-red-300 placeholder:text-red-300 focus:outline-red-600"
                                                           (str "!" $error-signal) "text-gray-900 outline-gray-300 focus:outline-sno-orange-600"})
                                              :data-attr:aria-invalid $error-signal))))))
