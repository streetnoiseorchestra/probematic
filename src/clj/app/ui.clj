(ns app.ui
  (:refer-clojure :exclude [time])
  (:require
   [app.humanize :as humanize]
   [app.icons :as icon]
   [app.render :as render]
   [app.util :as util]
   [clojure.string :as str]
   [ctmx.render :as ctmx.render]
   [hiccup2.core :refer [html]]
   [jsonista.core :as j]
   [medley.core :as m]
   [ring.middleware.not-modified :as not-modified]
   [ring.util.time :as ring.util]
   [tick.core :as t])
  (:import
   (java.text DecimalFormat NumberFormat)
   (java.util Locale)))

(defn cs [& names]
  (clojure.string/join " " (filter identity names)))

(defn cs-tw [& names]
  (clojure.string/join " "
                       (->> names (filter identity) (map name))))

(comment
  ;; here's an interesting way to use tailwind outside of strings, as pure symbols
  (defn tw-fn [symbols]
    (mapv name symbols))

  (defmacro tw [& names]
    (tw-fn names))
  ;; example
  [:div {:class (cs (tw min-h-full text-black)
                    (when true (tw border))
                    (when false (tw ml-6)))}]

  ;; not using this because it arguably doesn't bring you much.. and, crucially,
  ;; it breaks tailwind classes with colons in them: i.e., sm:flex
  )

(defn error-common [{:keys [tr] :as req} code title message human-id]
  [:body {:class "h-full"}
   [:div {:class "min-h-full bg-white px-4 py-16 sm:px-6 sm:py-24 md:grid md:place-items-center lg:px-8"}
    [:div {:class "mx-auto max-w-max"}
     [:main {:class "sm:flex"}
      (when code
        [:p {:class "text-4xl font-bold tracking-tight text-sno-orange-600 sm:text-5xl"}
         code])
      [:div {:class "sm:ml-6"}
       [:div {:class "sm:border-l sm:border-gray-200 sm:pl-6"}
        [:h1 {:class "text-4xl font-bold tracking-tight text-gray-900 sm:text-5xl"}
         title]
        [:p {:class "mt-1 text-base text-gray-500"}
         message]
        [:p {:class "mt-1 text-base text-gray-500"}
         "Error Code: "
         [:span {:class "mt-1 text-base text-red-500 font-mono bg-red-100"}
          human-id]]]]]]
    [:div {:class "flex items-center justify-center mt-10"}
     [:img {:src "/img/tuba-robot-boat-1000.jpg" :class "rounded-md w-full sm:w-1/2"}]]
    [:div {:class "mx-auto max-w-max"}
     [:main {:class "sm:flex"}
      [:div {:class ""}
       [:div {:class "mt-10 flex space-x-3 sm:border-l sm:border-transparent sm:pl-6"}
        [:a {:href "/", :class "inline-flex items-center rounded-md border border-transparent bg-sno-orange-600 px-4 py-2 text-sm font-medium text-white shadow-xs hover:bg-sno-orange-700 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
         (tr [:error/go-home])]
        [:a {:href "#"
             :hx-post "/notify-admin"
             :hx-vals {:human-id human-id}
             :hx-target "#notification-confirmation"
             :class "inline-flex items-center rounded-md border border-transparent bg-sno-orange-100 px-4 py-2 text-sm font-medium text-sno-orange-700 hover:bg-sno-orange-200 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
         (tr [:error/notify])]]
       [:div {:class "mt-10 flex space-x-3 sm:border-l sm:border-transparent sm:pl-6 text-sno-orange-600"
              :id "notification-confirmation"}]]]]]

   (render/body-end req nil)])

(defn unauthorized-error-body [{:keys [tr human-id] :as req}]
  (error-common req "401" (tr [:error/unauthorized-title]) (tr [:error/unauthorized-message]) human-id))

(defn not-found-error-body [{:keys [tr human-id] :as req}]
  (error-common req "404" (tr [:error/not-found-title]) (tr [:error/not-found-message]) human-id))

(defn unknown-error-body [{:keys [tr human-id] :as req}]
  (error-common req nil (tr [:error/unknown-title]) (tr [:error/unknown-message]) human-id))

(defn error-page-response-fragment [cause req status]
  (render/html-status-response (or status 404)
                               {"HX-Retarget" "body"}
                               (str (html (unknown-error-body req)))))

(defn error-page-response [cause req status]
  (render/html-status-response (or status 404)
                               (render/html5-safe
                                [:head
                                 [:meta {:charset "utf-8"}]
                                 [:meta {:name "viewport"
                                         :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
                                 [:link {:rel "shortcut icon" :href "/img/megaphone-icon.png"}]
                                 [:link {:rel "stylesheet" :href "/css/compiled/main.css"}]]
                                (ctmx.render/walk-attrs
                                 (condp = status
                                   404
                                   (not-found-error-body req)
                                   401
                                   (unauthorized-error-body req)
                                   (unknown-error-body req))))))

(def hx-trigger-types
  {:hx-trigger "HX-Trigger"
   :hx-trigger-after-settle "HX-Trigger-After-Settle"
   :hx-trigger-after-swap "HX-Trigger-After-Swap"})

(defn trigger-response
  ([trigger-name body]
   (trigger-response trigger-name body {}))
  ([trigger-name body {:keys [trigger-type data]
                       :or {trigger-type :hx-trigger}}]
   {:status 200
    :headers {"Content-Type" "text/html" (get hx-trigger-types trigger-type)
              (if data
                (j/write-value-as-string {trigger-name data})
                trigger-name)}
    :body (ctmx.render/html body)}))

(defn file-response [req file-name stream & {:keys [size mime-type last-modified inline? etag] :or {inline? true}}]
  (not-modified/not-modified-response
   {:status 200
    :headers (-> {"Content-Disposition" (util/content-disposition-filename file-name inline?)}
                 (m/assoc-some "Last-Modified" (ring.util/format-date last-modified))
                 (m/assoc-some "Content-Type" mime-type)
                 (m/assoc-some "ETag" etag))
    :body stream}
   req))

(defn multi-response
  "Return HTML has a response with multiple to-level HTML elements.
  In normal Hiccup this isn't allowed, there can be only one top-level element.
  This is useful when using hx-swap-oob
  "
  [bodies]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (->> bodies
              (map ctmx.render/html)
              (str/join "\n"))})

(defn retarget-response
  [new-target body]
  {:status 200
   :headers {"Content-Type" "text/html"
             "HX-Retarget" new-target}
   :body (ctmx.render/html body)})

(defn field-error [error id]
  (when (get error (keyword id))
    (map (fn [msg]
           [:p {:class "mt-2 text-sm text-red-600"} msg]) (get error (keyword id)))))

(def select-size {:normal "text-base  sm:text-sm py-2 pl-3 pr-10 mt-1"
                  :small "text-xs py-1 pl-2 pr-5 "
                  :xsmall "text-xs py-1 pl-0 pr-0 "})

(def select-label-size {:normal "text-base  sm:text-sm "
                        :small "text-xs"
                        :xsmall "text-xs"})

(defn select [& {:keys [id label options value extra-attrs size required? attr]
                 :or   {extra-attrs {}
                        size        :normal}}]
  (let [selected-value value]
    [:div
     [:label {:for id :class (cs (get select-label-size size) "block font-medium text-gray-700")} label]
     [:select (merge {:name     id :class
                      (cs  (get select-size size)
                           "block w-full rounded-md border-gray-300 focus:border-sno-orange-500 focus:outline-hidden focus:ring-sno-orange-500")
                      :required required?}

                     extra-attrs
                     attr)
      (for [{:keys [value label selected?]} options]
        [:option {:selected (if (not (nil? selected?)) selected? (= value selected-value)) :value value} label])]]))

(defn required-label [required?]
  (when required?
    [:p {:class "text-red-400"} "required"]))

(defn required-label-inline
  ([]
   (required-label-inline true))
  ([required?]
   (when required?
     [:span  {:class "text-red-400"} " required"])))

(defn select-left [& {:keys [id label options required? error value hint]
                      :or {required? true}}]
  (let [has-error? (get error (keyword id))
        selected-value value]
    [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
     [:label {:for id :class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2"}
      label (required-label required?)
      (when hint
        [:p {:class "mt-2 text-sm text-gray-500 font-normal"}
         hint])]
     [:div {:class "mt-1 sm:col-span-2 sm:mt-0"}
      [:div {:class "relative max-w-lg sm:max-w-xs "}
       (when has-error?
         [:div {:class "pointer-events-none absolute inset-y-0 right-4 flex items-center pr-3"}
          (icon/circle-exclamation {:class  "h-5 w-5 text-red-500"})])
       [:select {:id id :name id
                 :required required?
                 :class (cs
                         "block w-full rounded-md  shadow-xs sm:text-sm"
                         (if has-error?
                           "border-red-300 focus:border-red-500 focus:ring-red-500 "
                           "border-gray-300 focus:border-sno-orange-500 focus:ring-sno-orange-500 "))}

        (for [{:keys [value label selected?]} options]
          [:option {:selected (if (not (nil? selected?)) selected? (= value selected-value)) :value value} label])]]
      (field-error error id)]]))

(def icon-sizes {:small "h-3 w-3"
                 :normal "h-5 w-5"
                 :large "h-10 w-10"})

(def radio-option-state {:active "ring-2 ring-offset-2 ring-sno-orange-500"
                         :checked "bg-sno-orange-600 border-transparent text-white hover:bg-sno-orange-700"
                         :not-checked "bg-white border-gray-200 text-gray-900 hover:bg-gray-50"})

(defn radio-button  [idx {:keys [id name label value opt-id icon size class icon-class model disabled? required?]
                          :or {size :normal
                               class ""
                               required? false
                               icon-class ""
                               disabled? false}}]
  (let [checked? (= model value)]
    [:label {:id id
             :class
             (cs  "radio-button"
                  "self-stretch"
                  class
                  (if checked?
                    "radio-button-checked"
                    "radio-button-not-checked"))}

     [:input {:type "radio" :name name :value value :class "sr-only" :aria-labelledby id
              :required required?
              :checked checked?
              :_ "on change trigger radioChanged(value:[@value]) on .sr-only end
                  on radioChanged(value) if I match <:checked/>
                    add .radio-button-checked to the closest parent <label/>
                    remove .radio-button-not-checked from the closest parent <label/>
                  else
                    remove .radio-button-checked from the closest parent <label/>
                    add .radio-button-not-checked to the closest parent <label/>
                  end"}]
     [:span
      (when icon
        (icon {:checked checked?
               :disabled disabled?
               :class
               (cs
                (size icon-sizes)
                icon-class)}))
      (when label
        label)]]))

(defn radio-button-group [& {:keys [options id label class value required?]
                             :or {class ""
                                  required? false}}]
  [:fieldset
   [:legend {:class "block text-sm font-medium text-gray-700"} label]
   [:div {:class (cs "mt-1 flex items-center space-x-3" class)}
    (->> options
         (map #(merge {:name id :model value :required? required?} %))
         (map-indexed radio-button))]])

(defn textarea-left [& {:keys [label value hint hint-under id name rows placeholder fit-height? error required?]
                        :or {rows 3}}]
  (let [has-error? (get error (keyword name))]
    [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
     [:label {:for name :class "block text-sm font-medium leading-6 text-gray-700 sm:pt-1.5"} label
      (required-label required?)
      (when hint
        [:p {:class "mt-2 text-sm text-gray-500 font-normal"} hint])]
     [:div {:class "mt-2 sm:col-span-2 sm:mt-0"}
      [:textarea {:id id :name name :rows rows
                  :placeholder placeholder
                  :required required?
                  :data-auto-size (when  fit-height? "true")
                  :class (cs
                          "block w-full max-w-lg rounded-md border-0 text-gray-900 shadow-xs ring-1 ring-inset placeholder:text-gray-400 focus:ring-2 focus:ring-inset sm:py-1.5 sm:text-sm sm:leading-6"
                          (if has-error?
                            "ring-red-300 focus:ring-red-500 placeholder:text-red-300"
                            "ring-gray-300 focus:ring-sno-orange-600 "))}
       value]
      (when has-error?
        (field-error error id))
      (when hint-under
        [:p {:class "mt-2 text-sm text-gray-500"} hint-under])]]))

(defn textarea [& {:keys [label value hint id name rows placeholder fit-height? markdown? markdown-upload-endpoint]
                   :or {rows 3}}]
  [:div {:class "sm:col-span-6"}
   [:label {:for name :class "block text-sm font-medium text-gray-700"} label]
   [:div {:class "mt-1"}
    [:textarea {:id id :name name :rows rows
                :placeholder placeholder
                :data-auto-size (when  fit-height? "true")
                :data-image-upload-endpoint (when markdown? markdown-upload-endpoint)
                :class
                (cs (if markdown? "markdown-editor hidden"
                        "block w-full rounded-md border-gray-300 shadow-xs focus:border-sno-orange-500 focus:ring-sno-orange-500 sm:text-sm"))}
     (when value value)]]
   (when hint
     [:p {:class "mt-2 text-sm text-gray-500"}
      hint])])

(def input-container-size {:normal "px-3 py-2 "
                           :small "px-1 py-1  text-xs"})
(def input-label-size {:normal "px-1 text-xs "
                       :small "px-0 text-xs"})
(def input-size {:normal "sm:text-sm"
                 :small "text-xs"})

(defn input [& {:keys [attr type label name placeholder value extra-attrs class pattern title size required? id minlength suffix]
                :or   {size :normal required? true} :as opts}]
  [:div {:class (cs class (get input-label-size size)
                    (get input-container-size size)
                    "grow relative rounded-md border border-gray-300 shadow-xs focus-within:border-sno-orange-600 focus-within:ring-1 focus-within:ring-sno-orange-600")}
   (when label
     [:label {:for name :class "absolute -top-2 left-2 -mt-px inline-block bg-white font-medium text-gray-900"}
      label])
   [:input (util/remove-nils (merge extra-attrs
                                    {:class       (cs (get input-size size) "block w-full border-0 p-0 text-gray-900 placeholder-gray-500 focus:ring-0"
                                                      (when suffix "text-right pl-7 pr-12"))
                                     :type        type
                                     :id          id
                                     :pattern     pattern
                                     :title       title
                                     :minlength   minlength
                                     :name        name
                                     :value       (if (= "null" value) nil value)
                                     :required    required?
                                     :placeholder placeholder}
                                    attr))]
   (when suffix
     [:div {:class "pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3"}
      [:span {:class "text-gray-500 sm:text-sm"} suffix]])])

(defn text [& opts]
  (apply input (conj opts "text" :type)))

(defn input-datetime [& {:keys [value name required?]}]
  [:input {:type "datetime-local" :name name
           :value (when value
                    (t/truncate (t/date-time value) :minutes))
           :required required?
           :class "block w-full max-w-lg rounded-md border-gray-300 shadow-xs focus:border-sno-orange-500 focus:ring-sno-orange-500 sm:text-sm"}])

(defn input-datetime2 [& {:keys [value] :as opts}]
  (input (-> opts
             (assoc :type "datetime-local"
                    :value (when value (t/truncate (t/date-time value) :minutes))))))

(defn date [& {:keys [value name required? min max]}]
  [:input {:type "date" :name name
           :value (when value  (t/date value))
           :required required?
           :min min
           :max max
           :class "block w-full max-w-lg rounded-md border-gray-300 shadow-xs focus:border-sno-orange-500 focus:ring-sno-orange-500 sm:text-sm"}])

(defn date2 [& {:keys [value name required? min max label size]
                :or {size :normal
                     required? true}}]
  [:div {:class (cs class (get input-label-size size)
                    (get input-container-size size)
                    "grow relative rounded-md border border-gray-300 shadow-xs focus-within:border-sno-orange-600 focus-within:ring-1 focus-within:ring-sno-orange-600")}
   (when label
     [:label {:for name :class "absolute -top-2 left-2 -mt-px inline-block bg-white font-medium text-gray-900"}
      label])
   [:input {:type "date" :name name
            :value (when value  (t/date value))
            :required required?
            :min min
            :max max
            :class (cs (get input-size size) "block w-full border-0 p-0 text-gray-900 placeholder-gray-500 focus:ring-0")}]])

(defn input-time [& {:keys [value name required?]}]
  [:input {:type "time" :name name
           :value (when value (t/time value))
           :required required?
           :class "block w-full max-w-lg rounded-md border-gray-300 shadow-xs focus:border-sno-orange-500 focus:ring-sno-orange-500 sm:text-sm"}])

(def button-priority-classes {:link             "font-semibold text-sno-orange-600 hover:text-sno-orange-500"
                              :link-destructive "font-semibold text-red-600 hover:text-red-500"
                              :success
                              "border-transparent bg-sno-green-600 text-white shadow-xs hover:bg-sno-green-700 focus:outline-hidden focus:ring-2 focus:ring-sno-green-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :secondary
                              "border-transparent bg-sno-orange-100 px-4 py-2  text-sno-orange-700 hover:bg-sno-orange-200 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"
                              :white
                              "border-gray-300 bg-white px-4 py-2 text-sm  text-gray-700 shadow-xs hover:bg-gray-50 focus:outline-hidden focus:ring-2 focus:ring-sno-green-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :white-destructive
                              "border-red-300 bg-white px-4 py-2 text-sm  text-red-600 shadow-xs hover:bg-red-50 focus:outline-hidden focus:ring-2 focus:ring-red-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :primary
                              "border-transparent bg-sno-orange-600 text-white shadow-xs hover:bg-sno-orange-700 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :primary-orange
                              "border-transparent bg-orange-600 text-white shadow-xs hover:bg-orange-700 focus:outline-hidden focus:ring-2 focus:ring-orange-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :white-rounded    "rounded-full border border-gray-300 bg-white text-gray-700 shadow-xs hover:bg-gray-50"})

(def spinner-priority-classes {:secondary  "text-sno-orange-900"
                               :success "text-sno-green-700"
                               :white  "text-sno-green-700"
                               :white-destructive  "text-sno-orange-900"
                               :primary "text-white"
                               :primary-orange "text-white"
                               :white-rounded "text-sno-orange-900"})

(def button-sizes-classes {:2xsmall "px-1.5 py-0.5 text-xs"
                           :xsmall "px-2.5 py-1.5 text-xs"
                           :small "px-3 py-2 text-sm leading-4"
                           :normal "px-4 py-2 text-sm"
                           :large "px-4 py-2 text-base"
                           :xlarge "px-6 py-3 text-base"})

(def button-icon-sizes-classes {:xsmall "h-3 w-3"
                                :small "h-4 w-4"
                                :normal "h-5 w-5"
                                :large "h-5 w-5"
                                :xlarge "h-5 w-5"})

(defn button [& {:keys [tag label disabled? class attr icon priority centered? size hx-target hx-get hx-put hx-post hx-delete hx-vals hx-confirm hx-boost hx-push-url hx-swap form tabindex href spinner? id title name value]
                 :as   buttono
                 :or   {class     ""
                        priority  :white
                        size      :normal
                        disabled? false
                        tag       :button}}]
  [tag (merge
        (util/remove-nils {:hx-target hx-target :hx-swap hx-swap :hx-get hx-get :hx-post hx-post :hx-put hx-put :hx-delete hx-delete :hx-vals hx-vals :hx-confirm hx-confirm :hx-push-url (when hx-push-url "true") :form form :tabindex tabindex
                           :hx-boost  (when hx-boost "true")
                           :id        id
                           :name      name
                           :value     value
                           :title     title
                           :href      (when-not disabled? href)})
        {:class
         (cs
          (when-not (#{:link :link-destructive} priority)
            "inline-flex items-center rounded-md border font-medium")
          ;; "inline-flex items-center border font-medium"
          ;; "inline-flex items-center rounded-md border"
          (size button-sizes-classes)
          (priority button-priority-classes)
          (when spinner? "button-spinner")
          (when centered? "items-center justify-center")
          (when (and disabled? (= :tag :a)) "opacity-50 cursor-not-allowed")
          "disabled:opacity-50 disabled:cursor-not-allowed"
          class)
         :disabled disabled?}
        attr)
   (when icon (icon  {:class (cs "button-icon" (size button-icon-sizes-classes)  (when label "-ml-1 mr-2"))}))
   (when spinner? (icon/spinner {:class (cs "spinner"
                                            (size button-icon-sizes-classes)
                                            (priority spinner-priority-classes))}))
   [:span {:class "button-label"} label]])

(defn link-button [& {:as opts}]
  (button (assoc opts :tag :a)))

(defn page-header [& {:keys [title subtitle buttons] :as args}]
  [:div {:class "border-b border-gray-200 px-4 py-4 sm:flex sm:items-center sm:justify-between sm:px-6 lg:px-8 bg-white"}
   [:div {:class "min-w-0 flex-1"}
    [:h1 {:class
          ;; NOTE: i removed sm:truncate from this class list, because it cuts off the input label. later we might need a better solution when a title is very long
          "text-lg font-medium leading-6 text-gray-900"} title]
    (when subtitle
      [:p {:class "text-sm font-medium text-gray-500"}
       subtitle])]
   [:div {;; :class "mt-4 flex sm:mt-0 sm:ml-4"
          :class "justify-stretch mt-6 flex flex-col-reverse space-y-4 space-y-reverse sm:flex-row-reverse sm:justify-end sm:space-y-0 sm:space-x-3 sm:space-x-reverse md:mt-0 md:flex-row md:space-x-3"}
    buttons
    ;; [:button {:type "button" :class "sm:order-0 order-1 ml-3 inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-xs hover:bg-gray-50 focus:outline-hidden focus:ring-2 focus:ring-purple-500 focus:ring-offset-2 sm:ml-0"} "Share"]
    ;; [:button {:type "button" :class "order-0 inline-flex items-center rounded-md border border-transparent bg-purple-600 px-4 py-2 text-sm font-medium text-white shadow-xs hover:bg-purple-700 focus:outline-hidden focus:ring-2 focus:ring-purple-500 focus:ring-offset-2 sm:order-1 sm:ml-3"} "Create"]
    ]])

(defn page-header2 [& {:keys [title subtitle buttons] :as args}]
  [:div {:class "px-4 py-4 sm:flex sm:items-center sm:justify-between sm:px-6 lg:px-8"}
   [:div {:class "flex items-center space-x-5"}
    [:div
     [:h1 {:class "text-2xl font-bold text-gray-900 w-full"} title]
     (when subtitle
       [:p {:class "text-sm font-medium text-gray-500"}
        subtitle])]]
   [:div {:class "justify-stretch mt-6 flex flex-col-reverse space-y-4 space-y-reverse sm:flex-row-reverse sm:justify-end sm:space-y-0 sm:space-x-3 sm:space-x-reverse md:mt-0 md:flex-row md:space-x-3"}
    buttons]])

(defn page-header-full [& {:keys [title subtitle buttons buttons-class] :as args}]
  [:div {:class "px-4 py-4 sm:flex sm:items-center sm:justify-between sm:px-6 lg:px-8 bg-white"}
   [:div {:class "flex items-center space-x-5 w-full sm:w-1/2"}
    [:div {:class "w-full"}
     [:h1 {:class "text-2xl font-bold text-gray-900 w-full"} title]
     (when subtitle
       [:p {:class "text-sm font-medium text-gray-500 w-full"}
        subtitle])]]
   [:div {:class
          (cs
           "justify-stretch mt-6 flex flex-col-reverse space-y-4 space-y-reverse sm:flex-row-reverse sm:justify-end sm:space-y-0 sm:space-x-3 sm:space-x-reverse md:mt-0 md:flex-row md:space-x-3"
           buttons-class)}
    buttons]])

(defn member-nick
  "Renders the nickname of the member, if available, otherwise renders the name."
  [{:member/keys [name nick]}]
  (if (str/blank? nick)
    name
    nick))

(defn member-section
  "Renders the member's section"
  [{:member/keys [section]}]
  (if section
    (:section/name section)
    "No Section"))

(defn member-select-options [members & {:keys [full-names?  with-empty-opt?]}]
  (concat
   (if with-empty-opt?
     [{:value "" :label " - "}] [])
   (->> members
        (map (fn [m] {:value (:member/member-id m)
                      :label (if full-names?
                               (:member/name m)
                               (member-nick m))}))
        (util/isort-by :label))))

(defn member-select [& {:keys [id value label members variant with-empty-opt? error full-names? size attr]
                        :or   {label           "Member"
                               full-names?     false
                               variant         :inline
                               with-empty-opt? false}}]
  (let [options (member-select-options members :full-names? full-names? :with-empty-opt? with-empty-opt?)]
    (condp = variant
      :inline (select :id id
                      :label label
                      :value value
                      :error error
                      :size size
                      :attr attr
                      :options options)

      :inline-no-label (select :id id
                               :label ""
                               :value value
                               :error error
                               :size size
                               :attr attr
                               :options options)
      :left            (select-left :id id
                                    :label label
                                    :value value
                                    :error error
                                    :size size
                                    :attr attr
                                    :options options))))

(defn section-select-options [sections]
  (map (fn [{:section/keys [name]}]
         {:value name :label name}) sections))
(defn section-select [& {:keys [id value label sections extra-attrs]}]
  (let [options (section-select-options sections)]
    (select :id id :label label :value value :options options :extra-attrs extra-attrs)))

(defn motivation-select [& {:keys [id value label motivations extra-attrs]}]
  (let [options (map (fn [[k v]]
                       {:value k :label v}) motivations)]
    (select :id id :label label :value value :options options :extra-attrs extra-attrs :size :small)))

(defn song-select [& {:keys [id value label songs extra-attrs size] :or {size :small}}]
  (let [options (map (fn [{:song/keys [title song-id]}]
                       {:value song-id :label title}) songs)]
    (select :id id :label label :value value :options options :extra-attrs extra-attrs :size size)))

(defn travel-discount-type-select [& {:keys [id value label discount-types size] :or {size :small}}]
  (let [options (map (fn [{:travel.discount.type/keys [discount-type-id discount-type-name]}]
                       {:value discount-type-id :label discount-type-name}) discount-types)]
    (select :id id :label label :value value :options options  :size size)))

(defn instrument-select [& {:keys [id value label instruments variant]
                            :or {label "Instrument"
                                 variant :inline}}]
  (let [options
        (map (fn [m]
               {:value (:instrument/instrument-id m)
                :label (str (-> m :instrument/owner :member/name) " " (:instrument/name m))})
             instruments)]
    (condp = variant
      :inline (select :id id
                      :label label
                      :value value
                      :options options)

      :inline-no-label (select :id id
                               :label ""
                               :value value
                               :options options)
      :left (select-left :id id
                         :label label
                         :value value
                         :options options))))

(defn instrument-category-select [& {:keys [id value label categories variant with-empty-opt? hint]
                                     :or {label "Instrument Category"
                                          with-empty-opt? true
                                          variant :inline}}]
  (let [options
        (concat
         (if with-empty-opt?
           [{:value "" :label " - "}] [])
         (map (fn [c]
                {:value (:instrument.category/category-id c)
                 :label (:instrument.category/name c)})
              categories))]
    (condp = variant
      :inline
      (select :id id
              :label label
              :value value
              :options options)

      :inline-no-label
      (select :id id
              :label ""
              :value value
              :options options)
      :left
      (select-left :id id
                   :hint hint
                   :label label
                   :value value
                   :options options))))

(defn form-left-section [& {:keys [label hint]}]
  [:div
   [:h3 {:class "text-base font-semibold leading-6 text-gray-900"} label]
   [:p {:class "mt-1 max-w-2xl text-sm text-gray-500"} hint]])

(defn form-buttons [& {:keys [buttons-left buttons-right]}]
  [:div {:class "pt-5"}
   [:div {:class "flex justify-between gap-x-3"}
    [:div {:class "flex justify-start gap-x-3"} buttons-left]
    [:div {:class "flex justify-end gap-x-3"} buttons-right]]])

(defn datetime-left [& {:keys [id value label required? hint error]
                        :or {required? true}}]
  (let [has-error? (get error (keyword id))]
    [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
     [:label {:for id :class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2"}
      label (required-label required?)
      (when hint
        [:p {:class "mt-2 text-sm text-gray-500 font-normal"}
         hint])]
     [:div {:class "mt-1 sm:col-span-2 sm:mt-0"}
      [:div {:class "relative max-w-lg sm:max-w-xs"}
       (input-datetime :value value :name id :id id :required? required?)
       #_[:input (merge  {:type type
                          :name id
                          :id id
                          :value value
                          :placeholder placeholder
                          :required required?
                          :class (cs
                                  "block w-full rounded-md shadow-xs sm:text-sm border-0 ring-1 ring-inset focus:ring-2 focus:ring-inset"
                                  (if has-error?
                                    "ring-red-300 focus:ring-red-500 placeholder:text-red-300"
                                    "ring-gray-300 focus:border-sno-orange-500  focus:ring-sno-orange-500"))}
                         attr)]
       (when has-error?
         [:div {:class "pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3"}
          (icon/circle-exclamation {:class  "h-5 w-5 text-red-500"})])]
      (field-error error id)]]))

(defn text-left [& {:keys [id value label placeholder required? hint attr type error]
                    :or {required? true
                         attr {}
                         type "text"}}]
  (let [has-error? (get error (keyword id))]
    [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
     [:label {:for id :class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2"}
      label (required-label required?)
      (when hint
        [:p {:class "mt-2 text-sm text-gray-500 font-normal"}
         hint])]
     [:div {:class "mt-1 sm:col-span-2 sm:mt-0"}
      [:div {:class "relative max-w-lg sm:max-w-xs"}
       [:input (merge  {:type type
                        :name id
                        :id id
                        :value value
                        :placeholder placeholder
                        :required required?
                        :class (cs
                                "block w-full rounded-md shadow-xs sm:text-sm border-0 ring-1 ring-inset focus:ring-2 focus:ring-inset"
                                (if has-error?
                                  "ring-red-300 focus:ring-red-500 placeholder:text-red-300"
                                  "ring-gray-300 focus:border-sno-orange-500  focus:ring-sno-orange-500"))}
                       attr)]
       (when has-error?
         [:div {:class "pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3"}
          (icon/circle-exclamation {:class  "h-5 w-5 text-red-500"})])]
      (field-error error id)]]))

(defn factor-input [& {:keys [name value required? label]
                       :or {required? true}}]
  (input :value value :type "number"
         :name name
         :label label
         :required? required?
         :extra-attrs {:step "0.00000001" :min "0" :max "2.0"}))

(defn table-header [{:keys [title description body buttons]} & more]
  [:div {:class "px-4 sm:px-6 lg:px-8"}
   [:div {:class "sm:flex sm:items-center"}
    [:div {:class "sm:flex-auto"}
     [:h1 {:class "text-2xl font-semibold text-gray-900"} title]
     [:p {:class "mt-2 text-sm text-gray-700"} description]]
    [:div {:class "mt-4 sm:mt-0 sm:ml-16 sm:flex-none"}
     buttons]]
   [:div {:class "-mx-4 mt-8 overflow-hidden shadow-sm ring-1 ring-black/5 sm:-mx-6 md:mx-0 md:rounded-lg"}
    (into [] (concat
              [:table {:class "min-w-full divide-y divide-gray-300"}]
              more))]])

(def table-row-priorities
  {:important ""
   :normal "sm:pl-6"
   :medium "hidden sm:table-cell"
   :low "hidden xl:table-cell"
   :sm-only "table-cell sm:hidden"})

(def table-row-variant
  {:action "relative py-3.5 pl-3 pr-4 sm:pr-6"
   :number "text-right"})

(->>
 [{:label "Name" :priority :normal :key :name}
  {:label "Owner" :priority :medium :key :owner}
  {:label "Coverage" :priority :normal :key :coverage}
  {:label "Edit" :variant :action}]
 (group-by :key)
 (m/map-vals first))
[{}]

(into []
      (concat [1 2 3] [4 5 6]))

(defn table-row-head [headers]
  [:thead {:class "bg-gray-50"}
   [:tr
    (map-indexed (fn [idx {:keys [label variant priority]}]
                   [:th {:scope "col"
                         :class (cs
                                 "text-left text-sm font-semibold text-gray-900"
                                 (if  (= idx 0) "pl-4 pr-3" "px-3 py-3.5 ")
                                 (get table-row-priorities priority "")
                                 (get table-row-variant variant ""))}
                    [:span {:class (cs (when (= variant :action) "sr-only"))} label]])
                 headers)]])

(defn table-body [& more]
  (into [] (concat
            [:tbody {:class "divide-y divide-gray-200 bg-white"}]
            more)))

(defn table-body-old [headers data]
  (let [column-keys (->> headers (map :key) (filter some?))
        grouped-cols (->> headers (group-by :key) (m/map-vals first))]
    [:div {:class "-mx-4 mt-8 overflow-hidden shadow-sm ring-1 ring-black/5 sm:-mx-6 md:mx-0 md:rounded-lg"}
     [:table {:class "min-w-full divide-y divide-gray-300"}
      (table-row-head headers)

      [:tbody {:class "divide-y divide-gray-200 bg-white"}
       (map-indexed (fn [_idx data-row]
                      [:tr
                       (map-indexed (fn [col-idx col-key]
                                      [:td {:class
                                            (cs
                                             (if (= 0 col-idx) "w-full max-w-0 py-4 pl-4 pr-3 sm:w-auto   sm:max-w-none sm:pl-6" "px-3 py-4")
                                             (get table-row-priorities (get-in grouped-cols [col-key :priority]))
                                             "text-sm font-medium text-gray-900")}
                                       (if-let [render-fn (get-in grouped-cols [col-key :render-fn])]
                                         (render-fn col-key data-row)
                                         (get data-row col-key))])
                                    column-keys)])
                    data)]]]))

(def currency-symbols {:EUR "€"})
(def currency-default-locale {:EUR Locale/GERMANY
                              :USD Locale/US})

(defn money-formatter
  ([] (money-formatter (Locale/getDefault)))
  ([^Locale locale]
   (NumberFormat/getCurrencyInstance locale)))

(defn money-format [v currency]
  (.format ^DecimalFormat (money-formatter (get currency-default-locale currency Locale/GERMANY)) v))

(defn money-cents-format [v currency]
  (money-format (/ v 100) currency))

(defn money [value currency]
  (when value
    [:span
     [:span (money-format value currency)]]))

(defn money-cents
  "Given a monetary value in cents, returns a formatted string with the currency symbol."
  [value currency]
  (money (/ value 100) currency))

(defn money-input-left [& {:keys [id value label required? hint error integer?]
                           :or {required? true}}]
  (let [has-error? (get error (keyword id))
        min (if integer? 1 0.01)
        step (if integer? 1 0.01)]

    [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
     [:label {:for id :class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2"}
      label (required-label required?)
      (when hint
        [:p {:class "mt-2 text-sm text-gray-500 font-normal"}
         hint])]
     [:div {:class "mt-1 rounded-md shadow-xs"}
      [:div {:class "relative max-w-lg sm:max-w-xs"}
       [:div {:class "pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3"}
        [:span {:class "text-gray-500 sm:text-sm"} (:EUR currency-symbols)]]
       [:input {:type "number"
                :min min
                :step step
                :placeholder "0" :value value :required required? :name id :id id
                :class (cs
                        "text-right pl-7 pr-12"
                        "block w-full rounded-md shadow-xs sm:text-sm border-0 ring-1 ring-inset focus:ring-2 focus:ring-inset"
                        (if has-error?
                          "ring-red-300 focus:ring-red-500 placeholder:text-red-300"
                          "ring-gray-300 focus:border-sno-orange-500  focus:ring-sno-orange-500"))}]
       [:div {:class "pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3"}
        [:span {:class "text-gray-500 sm:text-sm"} "EUR"]
        (when has-error?
          [:div {:class "pointer-events-none "}
           (icon/circle-exclamation {:class "h-5 w-5 text-red-500"})])]]
      (field-error error id)]]))

(defn number-input-left [& {:keys [id value label required? hint error min max step]
                            :or {required? true
                                 step 1}}]
  (let [has-error? (get error (keyword id))]

    [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
     [:label {:for id :class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2"}
      label (required-label required?)
      (when hint
        [:p {:class "mt-2 text-sm text-gray-500 font-normal"}
         hint])]
     [:div {:class "mt-1 rounded-md shadow-xs"}
      [:div {:class "relative max-w-lg sm:max-w-xs"}

       [:input {:type "number" :min min :max max :step step :value value :required required? :name id :id id
                :class (cs
                        "text-right pl-7"
                        "block w-full rounded-md shadow-xs sm:text-sm border-0 ring-1 ring-inset focus:ring-2 focus:ring-inset"
                        (if has-error?
                          "ring-red-300 focus:ring-red-500 placeholder:text-red-300"
                          "ring-gray-300 focus:border-sno-orange-500  focus:ring-sno-orange-500"))}]
       [:div {:class "pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3"}
        (when has-error?
          [:div {:class "pointer-events-none "}
           (icon/circle-exclamation {:class "h-5 w-5 text-red-500"})])]]
      (field-error error id)]]))

(defn money-input [tr & {:as opts}]
  (input (-> opts
             (assoc :type "text" :suffix "EUR" :pattern "^\\d*(,\\d{2}$)?" :placeholder "0,00" :title (tr [:money-input-format-error])))))

(defn checkbox
  "TODO: misuses name and id"
  [& {:keys [label id checked?]}]

  [:div {:class "mt-2 relative flex items-start"}
   [:div {:class "flex h-5 items-center"}
    [:input {:type "checkbox" :id id :name id :class "h-4 w-4 rounded-sm border-gray-300 text-sno-orange-600 focus:ring-sno-orange-500"
             :checked checked?}]]
   [:div {:class "ml-3 text-sm"}
    [:label {:for id :class "font-medium text-gray-700"} label]]])

(defn checkbox-left [& {:keys [label id checked? hint name value disabled?]
                        :or {disabled? false}}]
  [:div {:class "relative flex items-start"}
   [:div {:class "flex h-6 items-center"}
    [:input {:id id :name name :type "checkbox"
             :checked checked?
             :value value
             :disabled disabled?
             :class "h-4 w-4 rounded-sm border-gray-300 text-sno-orange-600 focus:ring-sno-orange-600 disabled:text-sno-orange-400 "}]]
   [:div {:class "ml-3"}
    [:label {:for id :class "text-sm font-medium leading-6 text-gray-700"} label]
    (when hint
      [:p {:class "text-sm text-gray-500"} hint])]])

(defn radio-left [& {:keys [label id name checked? hint value required? attr disabled?]}]
  [:div {:class "relative flex items-start"}
   [:div {:class "flex h-6 items-center"}
    [:input (merge {:id id :name name :type "radio"
                    :checked checked? :value value
                    :required required?
                    :disabled disabled?
                    :class "h-4 w-4 border-gray-300 text-sno-orange-600 focus:ring-sno-orange-600 disabled:text-sno-orange-400 disabled:opacity-50"}
                   attr)]]
   [:div {:class "ml-3"}
    [:label {:for id :class "text-sm font-medium leading-6 text-gray-700"} label]
    (when hint
      [:p {:class "text-sm text-gray-500"} hint])]])

(defn checkbox-group-left [& {:keys [head-title head-hint label label-hint id checkboxes required?]}]
  [:div {:class (cs "space-y-6 divide-y divide-gray-200 sm:space-y-5 " (when head-title "pt-8 sm:pt-10"))}
   [:div
    (when head-title
      (list
       [:h3 {:class "text-base font-semibold leading-6 text-gray-700"} head-title]
       [:p {:class "mt-1 max-w-2xl text-sm text-gray-500"} head-hint]))]
   [:div {:class "space-y-6 divide-y divide-gray-200 sm:space-y-5"}
    [:div {:class "pt-6 sm:pt-5"}
     [:div {:role "group", :aria-labelledby (str "label-" id)}
      [:div {:class "sm:grid sm:grid-cols-3 sm:gap-4"}
       [:div
        [:div {:class "text-sm font-medium leading-6 text-gray-700" :id (str "label-" id)}
         label
         (required-label required?)
         (when label-hint
           [:p {:class "text-sm text-gray-500 font-normal"} label-hint])]]
       [:div {:class "mt-4 sm:col-span-2 sm:mt-0"}
        [:div {:class "max-w-lg space-y-4"}
         checkboxes]]]]]]])

(defn toggle [& {:keys [label hx-target hx-post hx-get hx-vals active? id]}]
  [:div {:class "flex items-center"}
   [:button {:type "button" :hx-target hx-target :hx-post hx-post :hx-get hx-get :hx-vals hx-vals
             :class (cs (if active? "bg-sno-orange-600" "bg-gray-200") "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2")
             :_ "on click toggle between .bg-gray-200 and .bg-sno-orange-600 end
                 on click toggle between .translate-x-5 and .translate-x-0 on <span/> in me end "
             :role "switch"}
    [:span {:aria-hidden "true"
            :class (cs (if active? "translate-x-5" "translate-x-0") "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-sm ring-0 transition duration-200 ease-in-out")}]]
   [:span {:class "ml-3"}
    [:span {:class "text-sm font-medium text-gray-900"} label]]])

(defn toggle-checkbox [& {:keys [label checked? name id]}]
  [:label {:for (or id name) :class "inline-flex relative items-center cursor-pointer"}
   [:input {:type "checkbox" :checked checked? :class "sr-only peer" :name name :id (or id name)}]
   [:div {:class (cs
                  ;; dark:peer-focus:ring-sno-orange-800 dark:bg-gray-700 dark:border-gray-600
                  "w-11 h-6 bg-gray-200 peer-focus:outline-hidden peer-focus:ring-4 peer-focus:ring-sno-orange-300  rounded-full peer"
                  " peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px]"
                  "after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all"
                  " peer-checked:bg-sno-orange-600")}]
   [:span {:class "ml-3 text-sm font-medium text-gray-900 "
           ;; dark:text-gray-300
           } label]])

(defn toggle-checkbox-left [& {:keys [label checked? name id hint]}]
  [:div {:class "sm:grid sm:grid-cols-3 sm:items-start sm:gap-4 sm:border-t sm:border-gray-200 sm:pt-5"}
   [:label {:for id :class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2"}
    label
    (when hint
      [:p {:class "mt-2 text-sm text-gray-500 font-normal"} hint])]
   [:div {:class "mt-1 sm:col-span-2 sm:mt-0"}
    [:label {:for name :class "inline-flex relative items-center cursor-pointer"}
     [:input {:type "checkbox" :checked checked? :class "sr-only peer" :name name :id id}]
     [:div {:class  (cs
                     ;; dark:peer-focus:ring-sno-orange-800 dark:bg-gray-700 dark:border-gray-600
                     "w-11 h-6 bg-gray-200 peer-focus:outline-hidden peer-focus:ring-4 peer-focus:ring-sno-orange-300  rounded-full peer"
                     " peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px]"
                     "after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all"
                     " peer-checked:bg-sno-orange-600")}]

             ;; :class "block w-full max-w-lg rounded-md border-gray-300 shadow-xs focus:border-sno-orange-500 focus:ring-sno-orange-500 sm:max-w-xs sm:text-sm"
     [:span {:class "ml-3 text-sm font-medium text-gray-900 "
             ;; dark:text-gray-300
             }]]]])

(defn avatar-img [member & {:keys [class]}]
  [:img {:class class
         :src (if-let [avatar-template (:member/avatar-template member)]
                (str "https://forum.streetnoise.at"
                     (str/replace avatar-template "{size}" "200"))
                "/img/default-avatar.png")}])

(defn member
  ([{:member/keys [name nick] :as m}]
   (member m nil))
  ([{:member/keys [name nick] :as m} link]
   [:div {:class "flex items-center"}
    [:div {:class "h-11 w-11 shrink-0"}
     (if link
       [:a {:href link} (avatar-img m :class "h-10 w-10 rounded-full")]
       (avatar-img m :class "h-10 w-10 rounded-full"))]
    [:div {:class "ml-2"}
     [:div {:class "font-medium text-gray-900"}
      (if link
        [:a {:class "link-blue" :href link} name]
        name)]
     [:div {:class "mt-1 text-gray-500"} nick]]]))

(defn divider-center [title]
  [:div {:class "relative"}
   [:div {:class "absolute inset-0 flex items-center", :aria-hidden "true"}
    [:div {:class "w-full border-t border-gray-300"}]]
   [:div {:class "relative flex justify-center"}
    [:span {:class "bg-white px-3 text-lg font-medium text-gray-900"}
     title]]])

(defn divider-left
  ([title] (divider-left title nil))
  ([title button]
   [:div {:class "relative ml-4 sm:ml-0 "}
    [:div {:class "absolute inset-0 flex items-center", :aria-hidden "true"}
     [:div {:class "w-full border-t border-gray-300"}]]
    [:div {:class "relative flex items-center justify-between mb-2"}
     [:span {:class "bg-gray-100 pr-3 text-lg font-medium text-gray-900"}
      title]
     (when button
       button)]]))

(defn bool-bubble
  ([is-active]
   (bool-bubble is-active {true "Aktiv" false "Inaktiv"}))
  ([is-active labels]
   [:span {:class
           (cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
               (when is-active "text-green-800 bg-green-100")
               (when (not is-active) "text-red-800 bg-red-100"))}
    (get labels is-active)]))

(def gig-status-colors
  {:gig.status/confirmed {:text-color "text-green-800"
                          :bg-color "bg-green-100"
                          :label "Confirmed"}
   :gig.status/unconfirmed {:text-color "text-orange-800"
                            :bg-color "bg-orange-100"
                            :label "Unconfirmed"}

   :gig.status/cancelled {:text-color "text-red-800"
                          :bg-color "bg-red-100"
                          :label "Cancelled"}})

(def gig-status-icons
  {:gig.status/confirmed {:icon icon/circle-check
                          :color "text-sno-green-500"}
   :gig.status/unconfirmed {:icon icon/circle-question
                            :color "text-sno-orange-500"}
   :gig.status/cancelled {:icon icon/circle-xmark
                          :color "text-red-500"}})

(defn gig-status-bubble [status]
  (let [{:keys [text-color bg-color label]} (gig-status-colors status)]
    [:span {:class
            (cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
                text-color bg-color)}
     label]))

(defn song-active-bubble [song]
  (let [[text-color bg-color label] (if (:song/active? song)
                                      ["text-green-500" "bg-green-100" "Active"]
                                      ["text-red-500" "bg-red-100" "Inactive"])]
    [:span {:class
            (cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
                text-color bg-color)}
     label]))

(defn gig-status-icon [status]
  (when status
    (let [{:keys [icon color]} (gig-status-icons status)]
      ;; mr-1.5
      (icon {:class (str " h-5 w-5 inline " color)}))))

(defn format-dt
  ([dt]
   (format-dt dt (t/formatter "E dd MMM yyyy" Locale/GERMAN)))
  ([dt fmt]
   (let [norm-dt (if (inst? dt) (t/date-time dt) dt)]
     (t/format fmt  norm-dt))))

(defn format-time [dt]
  (let [norm-dt (if (inst? dt) (t/date-time dt) dt)]
    (t/format (t/formatter "HH:mm" Locale/GERMAN) norm-dt)))

(defn gig-date-all [{:gig/keys [date call-time]}]
  (cond
    (and date call-time)
    (let [dt (t/at date call-time)]
      [:time {:datetime (str dt)}
       (t/format (t/formatter "dd.MM.yy E HH:mm" Locale/GERMAN) dt)])
    (some? date)
    [:time {:datetime (str date)}
     (t/format (t/formatter "dd.MM.yy E" Locale/GERMAN) date)]
    :else nil))

(defn gig-date [{:gig/keys [date call-time]}]
  (when date
    (t/format (t/formatter "dd.MM.yy E" Locale/GERMAN) (t/date date))))

(defn gig-time
  ([gig]
   (gig-time gig true))
  ([{:gig/keys [date call-time set-time end-time]} show-end-time?]
   (when-let [time (or call-time set-time)]
     (let [fmt-start (t/format (t/formatter "HH:mm" Locale/GERMAN) (t/at date time))]
       (if (and show-end-time? end-time)
         (str fmt-start " - " (t/format (t/formatter "HH:mm" Locale/GERMAN) (t/at date end-time)))
         fmt-start)))))

(defn datetime [dt]
  (if dt
    [:time {:dateetime (str dt)}
     (format-dt dt)]
    "-"))

(defn timestamp [dt]
  (if dt
    [:time {:dateetime (str dt)}
     (format-dt dt "dd.MM.YY HH:mm:ss")]
    "-"))

(defn daterange
  "As per: https://en.wikipedia.org/wiki/Wikipedia:Manual_of_Style/Dates_and_numbers#Ranges"
  [start end]
  (let [same?  (= start end)
        same-month? (= (t/month start) (t/month end))
        same-year? (= (t/year start) (t/year end))]
    (cond
      same?
      (datetime start)
      (and same-month? same-year?)
      [:span
       (format-dt start (t/formatter "E dd" Locale/GERMAN))
       "–"
       (format-dt end (t/formatter "E dd" Locale/GERMAN))
       " "
       (format-dt start (t/formatter "MMM yyyy" Locale/GERMAN))]
      same-year?
      [:span
       (format-dt start (t/formatter "dd MMM" Locale/GERMAN))
       " – "
       (format-dt end (t/formatter "dd MMM yyyy" Locale/GERMAN))]
      :else
      [:span
       (format-dt start (t/formatter "dd MMM yyyy" Locale/GERMAN))
       " – "
       (format-dt end (t/formatter "dd MMM yyyy" Locale/GERMAN))])))

(defn daterange-plain
  "As per: https://en.wikipedia.org/wiki/Wikipedia:Manual_of_Style/Dates_and_numbers#Ranges"
  [start end]
  (let [same?  (= start end)
        same-month? (= (t/month start) (t/month end))
        same-year? (= (t/year start) (t/year end))]
    (cond
      same?
      (format-dt start)
      (and same-month? same-year?)
      (str
       (format-dt start (t/formatter "dd" Locale/GERMAN))
       "–"
       (format-dt end (t/formatter "dd" Locale/GERMAN))
       " "
       (format-dt start (t/formatter "MMM yyyy" Locale/GERMAN)))
      same-year?
      (str
       (format-dt start (t/formatter "dd MMM" Locale/GERMAN))
       " – "
       (format-dt end (t/formatter "dd MMM yyyy" Locale/GERMAN)))
      :else
      (str
       (format-dt start (t/formatter "dd MMM yyyy" Locale/GERMAN))
       " – "
       (format-dt end (t/formatter "dd MMM yyyy" Locale/GERMAN))))))

(defn gig-date-plain [{:gig/keys [end-date date]}]
  (if end-date
    (daterange-plain date end-date)
    (format-dt date)))

(defn time [t]
  (when t
    (t/format (t/formatter "HH:mm" Locale/GERMAN) t)))

(defn humanize-dt [dt]
  (when dt
    (let [local-dt (cond (inst? dt) (t/date-time dt)
                         :else      dt)]
      [:time {:datetime (str local-dt) :title (format-dt dt (t/formatter "yyyy-MM-dd HH:mm:ss" Locale/GERMAN))}
       (humanize/from local-dt)])))

(defn dl-item
  ([label value]
   (dl-item label value "sm:col-span-1"))
  ([label value class]
   [:div {:class (cs class)}
    [:dt {:class "text-sm font-medium text-gray-500"} label]
    [:dd {:class "mt-1 text-sm text-gray-900"}
     value]]))

(defn dl [& items]
  [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-3"}
   items])

(defn panel [{:keys [id title subtitle buttons]} & body]
  [:div {:class "mx-auto mt-8 grid max-w-3xl grid-cols-1 gap-6 sm:px-6 lg:max-w-7xl lg:grid-flow-col-dense lg:grid-cols-3" :id id}
   [:div {:class "space-y-6 lg:col-span-3 lg:col-start-1"}
    [:section
     [:div {:class "bg-white shadow-sm sm:rounded-lg"}
      (when (or title buttons)
        [:div {:class "px-4 py-5 px-6  flex items-center justify-between "}
         (when (or title subtitle)
           [:div {:class "w-full"}
            [:h2 {:class "text-lg font-medium leading-6 text-gray-900"} title]
            (when subtitle
              [:p {:class "text-sm font-medium text-gray-500 w-full"}
               subtitle])])
         [:div {:class "space-x-2 flex"}
          buttons]])
      [:div {:class "border-t border-gray-200 px-4 py-5 sm:px-6"}
       body]]]]])

(defn rich-li-action [body]
  [:div {:class "ml-4 shrink-0"} body])

(defn rich-li-action-a [& {:keys [href label attrs]}]
  [:div {:class "ml-4 shrink-0"} [:a
                                  (merge
                                   {:href href :class "font-medium text-blue-600 hover:text-blue-500"}
                                   attrs) label]])
(defn rich-li-text [_ body]
  [:span {:class "ml-2 w-0 flex-1 truncate"} body])

(defn rich-li [{:keys [icon]} & items]
  [:li {:class "flex items-center justify-between py-3 pl-3 pr-4 text-sm"}
   [:div {:class "flex w-0 flex-1 items-center"}
    (when icon
      (icon {:class "h-5 w-5 shrink-0 text-gray-400"}))
    items]])

(defn rich-ul [_ & items]
  [:ul {:role "list", :class "divide-y divide-gray-200 rounded-md border border-gray-200"}
   items])

(defn slideover-panel
  [{:keys [id title subtitle buttons]} body]
  [:div {:class "hidden relative z-10", :aria-labelledby "slide-over-title", :role "dialog", :aria-modal "true" :id id :data-flyout-type "flyout-panel"}
   [:div {:class "fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"  :data-flyout-backdrop true}]
   [:div {:class "fixed inset-0 overflow-hidden"}
    [:div {:class "absolute inset-0 overflow-hidden"}
     [:div {:class "pointer-events-none fixed inset-y-0 right-0 flex max-w-full pl-10"}
      [:div {:class "pointer-events-auto w-screen max-w-md" :data-flyout-menu true}
       [:div {:class "flex h-full flex-col overflow-y-scroll bg-white py-6 shadow-xl"}
        [:div {:class "px-4 sm:px-6"}
         [:div {:class "flex items-start justify-between"}
          [:h2 {:class "text-lg font-medium text-gray-900", :id "slide-over-title"} title]
          [:div {:class "ml-3 flex h-7 items-center" :data-flyout-close-button true}
           [:button {:type "button", :class "rounded-md bg-white text-gray-400 hover:text-gray-500 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
            [:span {:class "sr-only"} "Close panel"]
            (icon/xmark-thin {:class "h-6 w-6"})]]]]
        [:div {:class "relative mt-6 flex-1 px-4 sm:px-6"}
         body]]]]]]])
[:div {:class "absolute inset-0 px-4 sm:px-6"}
 [:div {:class "h-full border-2 border-dashed border-gray-200", :aria-hidden "true"}]]

(defn slideover-extra-close-script [id]
  (format "on click trigger click on <#%s [data-flyout-close-button] button/>" id))

(defn slideover-panel-form
  [{:keys [id title subtitle buttons form-attrs]} body]
  [:div {:class "hidden relative z-10", :aria-labelledby (str id "slide-over-title"), :role "dialog", :aria-modal "true"  :data-flyout-type "flyout-panel" :id id}
   [:div {:class "fixed inset-0"  :data-flyout-backdrop true}]
   [:div {:class "fixed inset-0 overflow-hidden"}
    [:div {:class "absolute inset-0 overflow-hidden"}
     [:div {:class "pointer-events-none fixed inset-y-0 right-0 flex max-w-full pl-10 sm:pl-16"}
      [:div {:class "pointer-events-auto w-screen max-w-md"  :data-flyout-menu true}
       [:form (merge {:class "flex h-full flex-col divide-y divide-gray-200 bg-white shadow-xl"} form-attrs)
        [:div {:class "h-0 flex-1 overflow-y-auto"}
         [:div {:class "bg-orange-600 py-6 px-4 sm:px-6"}
          [:div {:class "flex items-center justify-between"}
           [:h2 {:class "text-lg font-medium text-white", :id (str id "slide-over-title")} title]
           [:div {:class "ml-3 flex h-7 items-center"  :data-flyout-close-button true}
            [:button {:type "button", :class "rounded-md bg-orange-700 text-orange-200 hover:text-white focus:outline-hidden focus:ring-2 focus:ring-white"}
             [:span {:class "sr-only"} "Close panel"]
             (icon/xmark-thin {:class "h-6 w-6"})]]]
          [:div {:class "mt-1"}
           (when subtitle
             [:p {:class "text-sm text-orange-300"} subtitle])]]
         body]

        (when buttons
          [:div {:class "flex shrink-0 justify-end px-4 py-4 space-x-3"}
           buttons])]]]]]])

(defn action-menu-item [id idx {:keys [label href attr active? icon tag spinner?]
                                :or {attr {}
                                     tag :a}}]
  [tag (merge {:href href :class
               (cs
                "text-gray-700 hover:bg-gray-100 hover:text-gray-900 block px-4 py-2 text-sm w-full text-left"
                (when spinner? "button-spinner")
                (when icon "flex gap-1")
                (when active? "font-medium bg-gray-100 text-gray-900 ")) :role "menuitem" :tabindex "-1" :id (str id "-" idx)}
              attr)
   (when icon icon)
   [:span {:class "button-label"} label]
   (when spinner? (icon/spinner {:class (cs "spinner"
                                            "h-5 w-5"
                                            "text-sno-green-500")}))])

(defn action-menu-section [idx id section]
  [:div {:class
         ;; maybe add w-48 to make it wider and more clickable?
         ;; mt-2
         (cs (if (zero? idx) "rounded-t-md" "rounded-b-md")
             "z-10 py-1 divide-y divide-gray-200  bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden")
         :role "none"}
   (when (:label section)
     [:div {:class "px-4 py-3", :role "none"}
      [:p {:class "truncate text-sm font-medium text-gray-900", :role "none"}  (:label section)]])
   (map-indexed (partial action-menu-item id) (:items section))])

(defn action-menu
  "An action menu drop down.

      :minimal? - when true only shows the button-icon
      :section - a list of maps containing the :items key. The value of :items should be another list of maps
                 the section map can also have the :label key for a section header"

  [& {:keys [id button-icon sections hx-boost label minimal? button-icon-class]
      :or {minimal? false
           button-icon-class "text-gray-900"}}]
  [:div {:class "flex items-center" :hx-boost hx-boost}
   [:div {:class "relative"}
    [:div
     [:button {:class
               (cs
                ;; "dark:bg-gray-800 dark:text-white dark:border-gray-600 dark:hover:bg-gray-700 dark:hover:border-gray-600 dark:focus:ring-gray-700"
                (when-not minimal?
                  "inline-flex items-center text-gray-900 bg-white border border-gray-300 focus:outline-hidden hover:bg-gray-100 focus:ring-4 focus:ring-gray-200 font-medium rounded-md text-sm px-3 py-1.5"))
               :type "button"
               :data-action-menu2-trigger (str "#" id)}
      (when button-icon
        (button-icon {:class (cs  (when minimal? "w-5 h-5")
                                  (when-not minimal? "w-4 h-4 mr-2")
                                  button-icon-class)}))
      (when label label)
      (when-not minimal?
        (icon/chevron-down {:class "w-3 h-3 ml-2"}))]]
    [:div {:id id :data-action-menu2 true
           :class "hidden" :role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabindex "-1"}
     (map-indexed #(action-menu-section %1 id %2) sections)]]])

(defn step-circles [total-steps current-step]
  (let [$circle-complete "steps-circle-complete" #_"relative flex h-8 w-8 items-center justify-center rounded-full bg-sno-orange-600 hover:bg-sno-orange-900"
        $circle-current "steps-circle-current" #_"relative flex h-8 w-8 items-center justify-center rounded-full border-2 border-sno-orange-600 bg-white"
        $circle-empty "group steps-circle-empty"
        finished? (> current-step total-steps)]
    [:nav {:aria-label "Progress"}
     [:ol {:role "list" :class "flex items-center"}
      (map (fn [n]
             (let [last? (= n total-steps)
                   previous? (= n (dec current-step))
                   next? (= n (inc current-step))]
               (cond
                 (< n current-step)
                 [:li {:class (cs "relative pr-8 sm:pr-20" (when previous? "steps-step-previous"))}
                  ;; "<!-- Completed Step -->"
                  [:div {:class "absolute inset-0 flex items-center" :aria-hidden "true"}
                   [:div {:class (cs "h-0.5 w-full" (if previous? "bg-gray-200" "bg-sno-orange-600"))}]]
                  [:div {:class (cs "bar absolute inset-0 flex items-center") :aria-hidden "true"}
                   [:div {:class "h-0.5 w-full bg-sno-orange-600"}]]
                  [:a {:href "#" :class $circle-complete}
                   [:svg {:class "h-5 w-5 text-white" :viewbox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
                    [:path {:fill-rule "evenodd" :d "M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" :clip-rule "evenodd"}]]
                   [:span {:class "sr-only"} "Step 1"]]]
                 (= n current-step)
                 [:li {:class (cs  "relative steps-step-current"
                                   (when-not last? "pr-8 sm:pr-20"))}
                  ;; "<!-- Current Step -->"
                  [:div {:class "absolute inset-0 flex items-center" :aria-hidden "true"}
                   [:div {:class "h-0.5 w-full bg-gray-200"}]]
                  #_[:div {:class "absolute inset-0 flex items-center steps-step-current" :aria-hidden "true"}
                     [:div {:class "h-0.5 w-full bg-sno-orange-600 "}]]
                  [:a {:href "#" :class $circle-current :aria-current "step"}
                   [:span {:class "h-2.5 w-2.5 rounded-full bg-sno-orange-600 step-dot-inner" :aria-hidden "true"}]
                   [:span {:class "sr-only"} "Step 3"]]]
                 :else
                 [:li {:class (cs  "relative"
                                   (when-not last? "pr-8 sm:pr-20")
                                   (when next? "steps-step-next"))}
                  ;; "<!-- Final Step -->"
                  [:div {:class "absolute inset-0 flex items-center", :aria-hidden "true"}
                   [:div {:class "h-0.5 w-full bg-gray-200"}]]
                  [:a {:href "#", :class $circle-empty}
                   [:span {:class "h-2.5 w-2.5 rounded-full bg-transparent group-hover:bg-gray-300", :aria-hidden "true"}]
                   [:span {:class "sr-only"} "Step 5"]]])))

           (range 1
                  (if finished?
                    (dec total-steps)
                    (inc total-steps))))
      (when finished?
        [:li {:class (cs  "relative"
                          "pr-8 sm:pr-20")}
         ;; "<!-- Final Step -->"
         [:div {:class "absolute inset-0 flex items-center", :aria-hidden "true"}
          #_[:div {:class "h-0.5 w-full bg-gray-200"}]]
         [:a {:href "final", :class "group steps-circle-complete"}
          [:svg {:class "h-5 w-5 text-white" :viewbox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
           [:path {:fill-rule "evenodd" :d "M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" :clip-rule "evenodd"}]]]])]]))

(defn photo-grid [photos]
  [:section {:class "overflow-hidden text-neutral-700"}
   [:div {:class "container mx-auto px-5 py-2"}
    [:div {:class "-m-1 flex flex-wrap md:-m-2"}
     (map (fn [{:keys [thumbnail full]}]
            [:div {:class "flex w-1/3 flex-wrap"}
             [:div {:class "w-full p-1 md:p-2"}
              [:a {:href full :target :_blank}
               [:img {:alt "gallery" :class "block h-full w-full rounded-lg object-cover object-center" :src thumbnail}]]]]) photos)]]])

(defn confirm-modal-script
  "Returns the hyperscript for a modal confirmation dialog"
  ([title text confirm-text cancel-text]
   (confirm-modal-script "htmx:confirm" title text confirm-text cancel-text))
  ([event title text confirm-text cancel-text]
   (let [escape #(clojure.string/escape % {\' "\\'"})]
     (format "on %s(issueRequest)
                          halt the event
                          call Swal.fire({
                            title: '%s',
                            text: '%s',
                            confirmButtonText: '%s',
                            cancelButtonText: '%s',
                            icon: 'warning',
                            iconColor: '#e00087',
                            showCancelButton: true,
                            confirmButtonColor: '#dc2626'
                          })
                          if result.isConfirmed issueRequest()"
             event
             (escape title)
             (escape text)
             (escape confirm-text)
             (escape cancel-text)))))

(defn breadcrumb-contained [attrs & items]
  [:div (merge {:class "mx-auto mt-8 max-w-3xl gap-6 sm:px-6 lg:max-w-7xl "} attrs)
   [:nav
    {:class "flex", :aria-label "Breadcrumb"}
    [:ol
     {:role "list",
      :class "flex space-x-0 sm:space-x-4 rounded-md bg-white px-2 sm:px-6 shadow-sm"}
     [:li
      {:class "flex"}
      [:div
       {:class "flex items-center"}
       [:a
        {:href "/", :class "text-gray-400 hover:text-gray-500"}
        (icon/home-solid {:class "h-5 w-5 shrink-0 text-gray-400"})
        [:span {:class "sr-only"} "Home"]]]]
     (map (fn [{:keys [href label icon]}]
            [:li
             {:class "flex"}
             [:div
              {:class "flex items-center"}
              [:svg
               {:class "h-full w-4 sm:w-6 shrink-0 text-gray-200",
                :viewBox "0 0 24 44",
                :preserveaspectratio "none",
                :fill "currentColor",
                :aria-hidden "true"}
               [:path {:d "M.293 0l22 22-22 22h1.414l22-22-22-22H.293z"}]]
              [(if href :a :span)
               {:href href,
                :class (cs "flex ml-2 sm:ml-4 text-sm font-medium text-gray-500" (when href  "hover:text-gray-700"))}
               (when icon
                 (icon {:class "h-5 w-5 shrink-0 mr-2 text-gray-400"}))
               label]]]) items)]]])

(defn iban [iban]
  (when iban
    (->> (str/replace iban #"\s" "")
         (partition 4)
         (map (fn [group]
                [:span {:class "visually-spaced"} (apply str group)])))))

(defn thumbs-up-animation
  "Must be wrapped in a :hx-ext class-tools" []
  [:div {:class "flex items-center relative  transition-all duration-500 ml-[50%] -translate-x-1/2" :classes "remove ml-[50%]:1s & remove -translate-x-1/2:1s"}
   [:div {:class "thumb opacity-0" :classes "remove opacity-0:30ms"}
    (icon/thumbs-up {:class "h-32 w-32 text-sno-green-500 transition-all duration-500" :classes "remove h-32:1.0s & remove w-32:1.0s & add h-8:1.0s & add w-8:1.0s"})]
   [:div {:class "circle-wrap"}
    [:div {:class "circle-lg"}]]
   [:div {:class "dots-wrap"}
    [:div {:class "dot dot--t"}]
    [:div {:class "dot dot--tr"}]
    [:div {:class "dot dot--br"}]
    [:div {:class "dot dot--b"}]
    [:div {:class "dot dot--bl"}]
    [:div {:class "dot dot--tl"}]]])
