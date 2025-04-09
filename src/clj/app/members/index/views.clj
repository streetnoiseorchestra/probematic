(ns app.members.index.views
  (:require [app.datastar :as d*]
            [app.members.queries :as queries]
            [app.ui.layout :as l]
            [app.html :as html]
            [app.icons :as icon]
            [app.members.routes2 :as commands]
            [app.members.domain :as domain]
            [app.settings.domain :as settings.domain]
            [app.ui :as ui]
            [app.ui.button2 :as button2]
            [app.ui.dropdown :as dropdown]
            [app.ui.typeahead :as typeahead]
            [app.urls :as url]
            [app.util :as util]
            [clojure.set :as set]
            [clojure.string :as str]
            [medley.core :as medley]
            [app.ui.form :as form]))

(def query-param-field-mapping
  {"name"            :member/name
   "active"          :member/active?
   "phone"           :member/phone
   "email"           :member/email
   "section"         :section
   "travel-discount" :travel-discount})

(defn parse-sort-param [v]
  (let [[param order] (str/split v #":")
        order         (if (= "desc" order) :desc :asc)
        field         (get query-param-field-mapping param nil)]
    (when field
      {:field field
       :order order})))

(defn sort-param [{:keys [query-params]}]
  (let [sort-spec (->> (util/ensure-coll (get query-params "sort" []))
                       (remove str/blank?)
                       (mapv parse-sort-param))]
    (when (seq sort-spec)
      sort-spec)))

(defn get-search-phrase [{:keys [page-state] :as req}]
  (or
   (get-in page-state [:phrase])
   (get-in req [:query-params "phrase"])
   ""))

(comment
  {:fields []
   :preset ""
   :search ""})

(defn filter-param [{:keys [query-params] :as req}]
  {:preset (get query-params "filter-preset" "active")
   :search (get-search-phrase req)
   :fields []})

(defn order-invert [o]
  (get {:asc  :desc
        :desc :asc} o))

(defn serialize-sort-param [{:keys [field order]}]
  (when field
    (str "?sort=" (get (set/map-invert query-param-field-mapping) field)
         (order-invert (or order :desc)))))

(defn sort-param-by-field [sort-spec field]
  (or
   (medley/find-first #(= field (:field %)) sort-spec)
   {:field field :order :asc}))

(defn member-table-headers-ro [req tr]
  (let [sort-spec        (sort-param req)
        sort-param-maker (fn [k]
                           {:hx-boost "true"
                            :href     (serialize-sort-param (sort-param-by-field sort-spec k))
                            :class    "link-blue"})]

    [{:label
      [:a (sort-param-maker :member/name)
       (tr [:member/name])] :priority :important :key :name
      :render-fn            (fn [_ instrument]
                              (list
                               (:name instrument)
                               [:dl {:class "font-normal sm:hidden"}
                                [:dt {:class "sr-only"} (:name instrument)]
                                [:dd {:class "mt-1 truncate text-gray-700"} (:owner instrument)]]))}

     {:label [:a (sort-param-maker :travel-discount) (tr [:oebb-discount])] :priority :medium :key :value}
     {:label [:a (sort-param-maker :member/email) (tr [:Email])] :priority :low :key :owner}
     {:label [:a (sort-param-maker :member/phone) (tr [:Phone])] :priority :low :key :value}
     {:label [:a (sort-param-maker :section) (tr [:section])] :priority :medium :key :value}
     {:label [:a (sort-param-maker :member/active?) (tr [:Active])] :priority :low :key :value}
     ;;
     ]))

(defn member-table-rw [_req])

(defn member-travel-discounts [travel-discounts]
  (->> travel-discounts
       ;; (remove #(settings.domain/expired? %))
       (map (fn [{:travel.discount/keys [discount-type] :as d}]
              [:span {:class
                      (ui/cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
                             (if (settings.domain/expired? d) "text-green-800 bg-green-100" "text-red-800 bg-red-100"))}
               (:travel.discount.type/discount-type-name discount-type)]))))

(defn member-row-ro [{:keys [tr] :as _req} _idx  {:member/keys [member-id name email active? phone section travel-discounts] :as member}]
  ;; (q/retrieve-member db member-id)
  (let [discounts    (member-travel-discounts travel-discounts)
        section-name (:section/name section)
        td-class     "px-3 py-4"]
    [:tr {:id (str "member-ro-" member-id)}
     (list
      [:td {:class (ui/cs "w-full max-w-0 py-4 pl-4 pr-3 sm:w-auto   sm:max-w-none sm:pl-6"
                          (ui/table-row-priorities :important))}
       [:a {:href (url/link-member member) :class "font-medium text-blue-600 hover:text-blue-500"} name
        [:span {:class "xl:hidden"} " " (ui/bool-bubble active?)]]
       [:dl {:class "font-normal xl:hidden"}
        [:dt {:class "sr-only sm:hidden"} (tr [:member/email])]
        [:dd {:class "mt-1 truncate text-gray-500"} email]
        [:dt {:class "sr-only sm:hidden"} (tr [:section])]
        [:dd {:class "mt-1 truncate text-gray-500 sm:hidden"} section-name]
        [:dd {:class "mt-1 truncate text-gray-500 sm:hidden"} discounts]
        [:dt {:class "sr-only sm:hidden"} (tr [:member/active?])]
        [:dd {:class "mt-1 truncate text-gray-500"}]]]

      [:td {:class (ui/cs td-class (ui/table-row-priorities :medium))}
       discounts]
      [:td {:class (ui/cs td-class (ui/table-row-priorities :low))} email]
      [:td {:class (ui/cs td-class (ui/table-row-priorities :low))} phone]
      [:td {:class (ui/cs td-class (ui/table-row-priorities :medium))} section-name]
      [:td {:class (ui/cs td-class (ui/table-row-priorities :low))} (ui/bool-bubble active?)])]))

(defn member-action-button [_req]
  [:div
   {:id "actionsDropdown",
    :class
    "hidden z-10 w-44 bg-white rounded divide-y divide-gray-100 shadow dark:bg-gray-700 dark:divide-gray-600"}
   [:ul
    {:class           "py-1 text-sm text-gray-700 dark:text-gray-200",
     :aria-labelledby "actionsDropdownButton"}
    [:li
     [:a
      {:href "#",
       :class
       "block py-2 px-4 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"}
      "Mass Edit"]]]
   [:div
    {:class "py-1"}
    [:a
     {:href "#",
      :class
      "block py-2 px-4 text-sm text-gray-700 hover:bg-gray-100 dark:hover:bg-gray-600 dark:text-gray-200 dark:hover:text-white"}
     "Delete all"]]])

(defn member-filter-button [{:keys [tr] :as req}]
  (let [filter-spec         (filter-param req)
        active-preset       (:preset filter-spec)
        active-preset-label (get {"all"      :member/filter-all
                                  "active"   :member/filter-active
                                  "inactive" :member/filter-inactive} active-preset :member/filter-active)]
    (dropdown/action-menu {:-button-icon icon/users-solid
                           :-label       (tr [active-preset-label])
                           :-sections    [{:items [{:label (tr [:member/filter-all]) :href "?filter-preset=all" :active? (= active-preset "all")}
                                                   {:label (tr [:member/filter-active]) :href "?filter-preset=active" :active? (= active-preset "active")}
                                                   {:label (tr [:member/filter-inactive]) :href "?filter-preset=inactive" :active? (= active-preset "inactive")}]}]
                           :-id          "member-table-actions"}))
  #_[:button
     {:id                   "filterDropdownButton",
      :data-dropdown-toggle "filterDropdown",
      :class
      "w-full md:w-auto flex items-center justify-center py-2 px-4 text-sm font-medium text-gray-900 focus:outline-none bg-white rounded-lg border border-gray-200 hover:bg-gray-100 hover:text-primary-700 focus:z-10 focus:ring-4 focus:ring-gray-200 dark:focus:ring-gray-700 dark:bg-gray-800 dark:text-gray-400 dark:border-gray-600 dark:hover:text-white dark:hover:bg-gray-700",
      :type                 "button"}
     [:svg
      {:xmlns       "http://www.w3.org/2000/svg",
       :aria-hidden "true",
       :class       "h-4 w-4 mr-2 text-gray-400",
       :viewBox     "0 0 20 20",
       :fill        "currentColor"}
      [:path
       {:fill-rule "evenodd",
        :d
        "M3 3a1 1 0 011-1h12a1 1 0 011 1v3a1 1 0 01-.293.707L12 11.414V15a1 1 0 01-.293.707l-2 2A1 1 0 018 17v-5.586L3.293 6.707A1 1 0 013 6V3z",
        :clip-rule "evenodd"}]]
     "Filter"
     [:svg
      {:class       "-mr-1 ml-1.5 w-5 h-5",
       :fill        "currentColor",
       :viewBox     "0 0 20 20",
       :xmlns       "http://www.w3.org/2000/svg",
       :aria-hidden "true"}
      [:path
       {:clip-rule "evenodd",
        :fill-rule "evenodd",
        :d
        "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]])

(defn member-table-ro [{:keys [tr db] :as req}]
  (let [filter-p      (filter-param req)
        members       (queries/members db (sort-param req) filter-p)
        table-headers (member-table-headers-ro req tr)]
    [:div
     [:div
      {:class
       "flex flex-col md:flex-row items-center justify-between space-y-3 md:space-y-0 md:space-x-4 p-4"}
      [:div {:class "w-full md:w-1/2"}
       (typeahead/typeahead {:-phrase (get-search-phrase req)
                             :-id     "member-search"
                             :-ns     :member-table
                             :-label  (tr [:action/search])
                             :-action (d*/dispatch req ::commands/search-member)})]
      [:div
       {:class
        "w-full md:w-auto flex flex-col md:flex-row space-y-2 md:space-y-0 items-stretch md:items-center justify-end md:space-x-3 flex-shrink-0"}
       (button2/button {:-priority :primary :-icon icon/plus
                        :href      (url/url-for req ::commands/new-member)} (tr [:team/add-member]))
       [:div {:class "flex items-center space-x-3 w-full md:w-auto"}
        #_[:button
           {:id                   "actionsDropdownButton",
            :data-dropdown-toggle "actionsDropdown",
            :class
            "w-full md:w-auto flex items-center justify-center py-2 px-4 text-sm font-medium text-gray-900 focus:outline-none bg-white rounded-lg border border-gray-200 hover:bg-gray-100 hover:text-primary-700 focus:z-10 focus:ring-4 focus:ring-gray-200 dark:focus:ring-gray-700 dark:bg-gray-800 dark:text-gray-400 dark:border-gray-600 dark:hover:text-white dark:hover:bg-gray-700",
            :type                 "button"}
           [:svg
            {:class       "-ml-1 mr-1.5 w-5 h-5",
             :fill        "currentColor",
             :viewBox     "0 0 20 20",
             :xmlns       "http://www.w3.org/2000/svg",
             :aria-hidden "true"}
            [:path
             {:clip-rule "evenodd",
              :fill-rule "evenodd",
              :d
              "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]
           "Actions"]
        (member-action-button req)
        (member-filter-button req)

        [:div
         {:id "filterDropdown",
          :class
          "z-10 hidden w-48 p-3 bg-white rounded-lg shadow dark:bg-gray-700"}
         [:h6
          {:class "mb-3 text-sm font-medium text-gray-900 dark:text-white"}
          "Choose brand"]]]]]
     (d*/debug-signals)
     [:div {:class "flex flex-col space-y-4 sm:flex-row sm:items-center justify-end pb-4 mt-4"}
      (tr [:total]) ": " (count members)]

     [:table {:class "min-w-full divide-y divide-gray-300"}
      (ui/table-row-head table-headers)
      (ui/table-body
       (map-indexed (partial member-row-ro req) members))]]))

(defn members-table [{:keys [page-state] :as req}]
  (let [quick-edit? (get-in page-state [:quick-edit :open])]
    [:div {:class "mt-4"}
     (if quick-edit?
       nil
       #_(member-table-rw req)
       (member-table-ro req))]))

(defn open-invitations [_req])

(defn new-member [{:keys [tr db] :as req}]
  (html/->str
   [:main {:class "flex-1" :id "main"}

    (l/panel {:-title    (tr [:member/new-member])
              :-subtitle "The more the merrier"}
             (let [section-options (ui/section-select-options  (queries/sections db))
                   form            {:ns               :member
                                    :command          (d*/dispatch req ::commands/create-member)
                                    :live-validation? true
                                    :fields           {:name          ""
                                                       :nick          ""
                                                       :email         ""
                                                       :username      ""
                                                       :phone         ""
                                                       :section-name  ""
                                                       :active        true
                                                       :create-sno-id true}}]
               (form/form {:-form form :class "sm:max-w-lg"}
                          (form/section {:-narrow? true}
                                        (form/input {:-label     (tr [:member/name])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :text
                                                     :name       :name})
                                        (form/input {:-label     (tr [:member/nick])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :text
                                                     :name       :nick})
                                        (form/input {:-label     (tr [:member/email])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :email
                                                     :name       :email})
                                        (form/input {:-label     (tr [:member/username])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :text
                                                     :name       :username
                                                     :pattern    (str domain/username-regex)
                                                     :title      (tr [:member/username-validation])})
                                        (form/input {:-label     (tr [:member/phone])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :tel
                                                     :name       :phone
                                                     :pattern    "\\+[\\d\\- ]+"
                                                     :title      (tr [:member/phone-validation])})
                                        (form/select {:-label     (tr [:section])
                                                      :-required? true
                                                      :-form      form
                                                      :-options   section-options
                                                      :class      "sm:col-span-2"
                                                      :name       :section-name})
                                        [:div {:class "sm:col-span-3"}
                                         (form/checkbox {:-label       (tr [:member/create-sno-id])
                                                         :-description (tr [:member/create-sno-id-description])
                                                         :-form        form
                                                         :name         :create-sno-id})
                                         (form/checkbox {:-label       (tr [:Active])
                                                         :-description "Should the new member be marked as an active member?"
                                                         :-form        form
                                                         :class        "mt-4"
                                                         :name         :active})])
                          (form/errors {:-form form})
                          (form/actions {:-right (list
                                                  (button2/button {:-priority :secondary
                                                                   :href      (url/url-for req ::commands/members)}
                                                                  (tr [:action/cancel]))
                                                  (button2/button {:-priority :primary
                                                                   :type      :submit}
                                                                  (tr [:action/create])))})
                          #_(d*/debug-signals))))]))

(defn members [{:keys [tr] :as req}]
  (html/->str
   [:main {:class "flex-1" :id "main"}
    (ui/page-header :title (tr [:nav/members]))
    (open-invitations req)
    (members-table req)]))

(d*/refresh-all!)
