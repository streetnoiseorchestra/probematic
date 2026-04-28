(ns app.members.views
  (:require
   [app.auth :as auth]
   [app.i18n :as i18n]
   [app.icons :as icon]
   [app.keycloak :as keycloak]
   [app.layout :as layout]
   [app.ledger.views :as ledger]
   [app.members.controller :as controller]
   [app.members.domain :as members.domain]
   [app.queries :as q]
   [app.settings.domain :as settings.domain]
   [app.ui :as ui]
   [app.urls :as url]
   [app.util :as util]
   [clojure.set :as set]
   [clojure.string :as str]
   [ctmx.core :as ctmx]
   [ctmx.response :as response]
   [ctmx.rt :as rt]
   [medley.core :as m]
   [tick.core :as t]))

(def query-param-field-mapping
  {"name" :member/name
   "active" :member/active?
   "phone" :member/phone
   "email" :member/email
   "section" :section
   "travel-discount" :travel-discount})

(defn parse-sort-param [v]
  (let [[param order] (str/split v #":")
        order (if (= "desc" order) :desc :asc)
        field (get query-param-field-mapping param nil)]
    (when field
      {:field  field
       :order order})))

(defn sort-param [{:keys [query-params] :as req}]
  (let [sort-spec (->> (util/ensure-coll (get query-params "sort" []))
                       (remove str/blank?)
                       (mapv parse-sort-param))]
    (when (seq sort-spec)
      sort-spec)))

(comment
  {:fields []
   :preset ""
   :search ""})

(defn filter-param [{:keys [query-params] :as req}]
  {:preset (get query-params "filter-preset" "active")
   :search (get query-params "q" nil)
   :fields []})

(defn order-invert [o]
  (get {:asc :desc
        :desc :asc} o))

(defn serialize-sort-param [{:keys [field order]}]
  (when field
    (str "?sort=" (get (set/map-invert query-param-field-mapping) field)
         (order-invert (or order :desc)))))

(defn sort-param-by-field [sort-spec field]
  (or
   (m/find-first #(= field (:field %)) sort-spec)
   {:field field :order :asc}))

(defn member-table-headers-ro [req tr]
  (let [sort-spec (sort-param req)
        sort-param-maker (fn [k]
                           {:hx-boost "true"
                            :href (serialize-sort-param (sort-param-by-field sort-spec k))
                            :class "link-blue"})]

    [{:label
      [:a (sort-param-maker :member/name)
       (tr [:member/name])] :priority :important :key :name
      :render-fn (fn [_ instrument]
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
(defn member-table-headers-rw [tr]
  [{:label (tr [:member/name]) :priority :important :key :name
    :render-fn (fn [_ instrument]
                 (list
                  (:name instrument)
                  [:dl {:class "font-normal sm:hidden"}
                   [:dt {:class "sr-only"} (:name instrument)]
                   [:dd {:class "mt-1 truncate text-gray-700"} (:owner instrument)]]))}

   {:label (tr [:Email]) :priority :low :key :owner}
   {:label (tr [:Phone]) :priority :low :key :value}
   {:label (tr [:section]) :priority :important :key :value}
   {:label (tr [:Active]) :priority :important :key :value}
   ;;
   ])

(ctmx/defcomponent ^:endpoint travel-discount-single [{:keys [db tr] :as req} idx discount-id]
  (if (util/delete? req)
    (do
      (controller/delete-travel-discount! req)
      [:div])
    (let  [{:travel.discount/keys [discount-type
                                   discount-id
                                   expiry-date]
            :as discount}  (if (util/post? req)
                             (controller/update-travel-discount! req)
                             (q/retrieve-travel-discount db (util/ensure-uuid! discount-id)))
           {:travel.discount.type/keys [discount-type-name]} discount-type
           expired? (settings.domain/expired? discount)
           form-class (str (path ".") "-form")
           label-class (str (path ".") "-label")]
      [:form {:class "sm:flex" :id (path ".")
              :hx-target (hash ".")}
       [:input {:type :hidden :value discount-id :name "travel-discount-id"}]

       ;; rw
       [:dt {:class (ui/cs  "hidden mb-2 text-gray-900 sm:w-64 sm:flex-none sm:pr-6" form-class)}
        [:div {:class "mt-2"}]
        discount-type-name]
       [:dd {:class (ui/cs "hidden mt-1 flex  sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" form-class)}
        [:div {:class ""}
         (ui/input :name "expiry-date" :type :date :value (t/date expiry-date) :label (tr [:travel-discounts/expiry-date]))]

        (ui/button :priority :white-destructive :label (tr [:action/delete]) :size :xsmall
                   :hx-delete (util/endpoint-path travel-discount-single))
        (ui/button :priority :primary :label (tr [:action/save]) :size :xsmall
                   :hx-post (util/endpoint-path travel-discount-single))]

       ;; ro
       [:dt {:class (ui/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6" label-class)}
        [:div discount-type-name]]
       [:dd {:class (ui/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" label-class)}
        [:div {:class "text-gray-900"}
         [:span {:class
                 (ui/cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
                        (if expired? "text-green-800 bg-green-100" "text-red-800 bg-red-100"))}
          (t/date expiry-date)]]
        (ui/button :priority :link :label (tr [:action/update])
                   :attr {:type :button
                          :_ (format  "on click remove .hidden from .%s then add .hidden to .%s" form-class label-class)})]])))

(ctmx/defcomponent ^:endpoint travel-discount-panel [{:keys [db tr] :as req}]
  (let [db (if (util/post? req)
             (controller/add-discount! req)
             db)
        member (q/retrieve-member db (-> req :member :member/member-id))
        discount-types (q/retrieve-all-discount-types db)
        {:member/keys [travel-discounts]} member
        req (util/make-get-request req {:db db})]
    (ui/panel {:title (tr [:travel-discounts/title])
               :subtitle (tr [:travel-discounts/subtitle])
               :id (path ".")}
              [:div
               (if (seq travel-discounts)
                 [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
                  (rt/map-indexed travel-discount-single req (map :travel.discount/discount-id travel-discounts))]
                 (tr [:travel-discounts/none]))
               [:div {:class "flex border-t border-gray-100 pt-6"}
                [:div {:class "discount-add-form hidden"}
                 [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
                  [:div {:class "sm:grid sm:grid-cols-4 sm:items-start sm:gap-4"}
                   [:form {:class "sm:col-span-2 sm:flex sm:flex-col sm:space-y-4"
                           :hx-target (hash ".")
                           :hx-post (util/endpoint-path travel-discount-panel)}
                    (ui/travel-discount-type-select :discount-types discount-types
                                                    :size :normal
                                                    :label (tr [:travel-discounts/discount-type-name])
                                                    :id "new-discount-type")
                    (ui/date :name "expiry-date" :required? true)
                    (ui/button :class "mt-4"
                               :priority :primary
                               :label (tr [:action/add]))]]]]
                (ui/button :attr {:_ "on click remove .hidden from .discount-add-form then add .hidden to me"}
                           :icon icon/plus
                           :label (tr [:travel-discounts/add-discount]))]])))

(ctmx/defcomponent ^:endpoint members-detail-page [{:keys [db] :as req}  ^:boolean edit?]
  travel-discount-panel
  travel-discount-single
  (let [comp-name (util/comp-namer #'members-detail-page)
        post? (util/post? req)
        tr (i18n/tr-from-req req)
        member (cond post?
                     (:member (controller/update-member! req))
                     :else
                     (:member req))
        {:member/keys [member-id name nick email username phone active? section keycloak-id]} member
        {:keys [coverages policy]} (controller/member-current-insurance-info req member)
        private-instruments (filter :instrument.coverage/private? coverages)
        band-instruments (filter #(not (:instrument.coverage/private? %)) coverages)
        sections (controller/sections db)
        section-name (:section/name section)
        current-user-admin? (auth/current-user-admin? req)
        account-enabled? (keycloak/user-account-enabled? (keycloak/kc-from-req req) keycloak-id)]
    [:div {:class "mt-2"}
     [(if edit? :form :div)
      (if edit?
        {:id id :hx-post (comp-name)}
        {:id id})
      [:div {:class "mx-auto max-w-3xl px-4 sm:px-6 md:flex md:items-center md:justify-between md:space-x-5 lg:max-w-7xl lg:px-8"}
       [:div {:class "flex items-center space-x-5"}
        [:div {:class "shrink-0"}
         [:div {:class "relative"}
          (ui/avatar-img member :class "h-16 w-16 rounded-full")
          [:span {:class "absolute inset-0 rounded-full shadow-inner" :aria-hidden "true"}]]]
        [:div
         [:h1 {:class "text-2xl font-bold text-gray-900"}
          (if edit?
            [:div {:class "flex flex-col sm:flex-row"}
             (ui/text :label (tr [:member/name])  :name (path "name") :value name :class "mb-2 sm:mb-0 sm:mr-2")
             (ui/text :label (tr [:member/nick])  :name (path "nick") :value nick)]
            [:h1 {:class "text-2xl font-bold text-gray-900"}
             name
             (when nick
               [:sup {:class "text-gray-500 text-lg"}
                (str " (" nick ")")])])]
         [:p {:class "text-sm font-medium text-gray-500"}
          (if edit?
            (ui/section-select :label (tr [:section])  :id (path "section-name") :value section-name :class "mt-4" :sections sections)
            (if section-name section-name
                (tr [:section-none])))]]]
       [:div {:class "justify-stretch mt-6 flex flex-col-reverse space-y-4 space-y-reverse sm:flex-row-reverse sm:justify-end sm:space-y-0 sm:space-x-3 sm:space-x-reverse md:mt-0 md:flex-row md:space-x-3"}
        (if edit?
          (list
           (ui/button :label (tr [:action/cancel]) :priority :white :centered? true
                      :attr {:hx-get (comp-name) :hx-target (hash ".") :hx-vals {:edit? false}})
           (ui/button :label (tr [:action/save])  :priority :primary :centered? true))
          (ui/button :label (tr [:action/edit]) :priority :white :centered? true
                     :attr {:hx-get (comp-name) :hx-target (hash ".") :hx-vals {:edit? true}}))]]
      (ui/panel {:title
                 (tr [:Contact-Information])
                 :buttons (ui/link-button :label (tr [:Contact-Download]) :icon icon/download
                                          :href (str "/member-vcard/" (str member-id)))}
                (ui/dl
                 (ui/dl-item (tr [:member/active?]) (if edit?
                                                      (ui/toggle-checkbox  :checked? active? :name (path "active?"))
                                                      (ui/bool-bubble active?)))
                 (ui/dl-item (tr [:member/email]) (if edit?
                                                    (ui/input :name (path "email") :label "" :value email :type :email)
                                                    email))
                 (ui/dl-item (tr [:member/phone]) (if edit?
                                                    (ui/input :type :tel :name (path "phone") :label "" :value phone :pattern "\\+?[\\d\\s-]+" :title
                                                              (tr [:member/phone-validation]))
                                                    phone))
                 (ui/dl-item (tr [:member/username])
                             (if (and edit? current-user-admin?)
                               (ui/text :label "" :name (path "username") :value username :required? false)
                               username))
                 (ui/dl-item (tr [:member/keycloak-id])
                             (if (and edit? current-user-admin?)
                               (ui/text :label "" :name (path "keycloak-id") :value keycloak-id :required? false)
                               [:a {:href (keycloak/link-user-edit (-> req :system :env)  keycloak-id)
                                    :class "link-blue"}
                                keycloak-id]))
                 (ui/dl-item (tr [:member/sno-id-enabled-disabled])
                             (if (and edit? current-user-admin?)
                               (ui/toggle-checkbox  :checked? account-enabled? :name (path "sno-id-enabled?"))
                               (ui/bool-bubble account-enabled?
                                               {true (tr [:member/sno-id-enabled])
                                                false (tr [:member/sno-id-disabled])})))))]
     [:div {:class "mt-8"}

      (travel-discount-panel (util/make-get-request req))

      (ledger/member-ledger-panel req member)

      (ui/panel {:title (tr [:member/insurance-title])
                 :subtitle (tr [:member/insurance-subtitle])
                 :buttons (list
                           (ui/link-button :href (url/link-coverage-create (:insurance.policy/policy-id policy))
                                           :label (tr [:instrument.coverage/create-button])
                                           :priority :primary
                                           :icon icon/plus))}

                (ui/dl

                 (ui/dl-item (tr [:band-instruments])  (count band-instruments))
                 (ui/dl-item (tr [:private-instruments]) (count private-instruments))
                 (ui/dl-item (tr [:instruments]) (if (empty? coverages)
                                                   (tr [:none])
                                                   (ui/rich-ul {}
                                                               (map (fn [{:as coverage :instrument.coverage/keys [private? value] {:instrument/keys [name category]} :instrument.coverage/instrument}]
                                                                      (ui/rich-li {:icon icon/trumpet}
                                                                                  (ui/rich-li-text {} name)
                                                                                  (ui/rich-li-text {} (:instrument.category/name category))
                                                                                  (ui/rich-li-text {} (ui/money value :EUR))
                                                                                  (ui/rich-li-text {} (ui/bool-bubble (not private?) {false "Private" true "Band"}))
                                                                                  (ui/rich-li-action-a :href (url/link-coverage coverage) :label
                                                                                                       (tr [:action/view]))))
                                                                    coverages))) "sm:col-span-3")))

      (ui/panel {:title (tr ["Gigs & Probes"])
                 :subtitle (tr ["Fun stats!"])}
                (ui/dl
                 (ui/dl-item (tr [:member/gigs-attended]) "coming soon")
                 (ui/dl-item (tr [:member/probes-attended]) "coming soon")))]]))

(ctmx/defcomponent ^:endpoint member-row-rw [{:keys [db] :as req} ^:long idx member-id]
  (let [td-class "px-3 py-4"
        comp-name  (util/comp-namer #'member-row-rw)
        member (cond
                 (util/post? req) (:member (controller/update-active-and-section! req))
                 :else (q/retrieve-member db member-id))
        {:member/keys [name email active? phone section]} member
        sections (controller/sections db)
        section-name (:section/name section)]
    [:tr {:id id}
     (list
      [:td {:class (ui/cs "w-full max-w-0 py-4 pl-4 pr-3 sm:w-auto   sm:max-w-none sm:pl-6")}
       name]
      [:td {:class (ui/cs td-class (ui/table-row-priorities :low))} email]
      [:td {:class (ui/cs td-class (ui/table-row-priorities :low))} phone]
      [:td {:class td-class :hx-include (str "#" id " input, #"  id " select")}
       (ui/section-select :label "" :id "section-name" :value section-name :class "mt-4" :sections sections
                          :extra-attrs {:hx-post (comp-name) :hx-target (hash ".")
                                        :hx-vals {:member-id (str member-id)}})]
      [:td {:class td-class :id (str "member" idx)}
       (ui/toggle :active? active? :hx-post (util/endpoint-path member-row-rw)
                  :hx-vals {:active? (not active?)
                            :member-id (str member-id)}
                  :hx-target (hash "."))]

;;
      )]))

(ctmx/defcomponent ^:endpoint member-row-ro [{:keys [db tr] :as req} idx member-id]
  (let [{:member/keys [name email active? phone section travel-discounts] :as member} (q/retrieve-member db member-id)
        discounts (->> travel-discounts
                       ;; (remove #(settings.domain/expired? %))
                       (map (fn [{:travel.discount/keys [discount-type expiry-date] :as d}]
                              [:span {:class
                                      (ui/cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
                                             (if (settings.domain/expired? d) "text-green-800 bg-green-100" "text-red-800 bg-red-100"))}
                               (:travel.discount.type/discount-type-name discount-type)])))

        section-name (:section/name section)
        td-class "px-3 py-4"]
    [:tr {:id id :hx-boost "true"}
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
      [:td {:class (ui/cs td-class (ui/table-row-priorities :low))} (ui/bool-bubble active?)]
      ;;
      )]))

(ctmx/defcomponent ^:endpoint member-table-rw [{:keys [db] :as req}]
  (let [members (controller/members db nil nil)
        tr (i18n/tr-from-req req)
        table-headers (member-table-headers-rw tr)]
    [:div
     [:div {:class "flex flex-col space-y-4 sm:flex-row sm:items-center justify-end pb-4 mt-4"}
      (tr [:total]) ": " (count members)]
     [:table {:class "min-w-full divide-y divide-gray-300"}
      (list
       (ui/table-row-head table-headers)
       (ui/table-body
        (rt/map-indexed member-row-rw req (map :member/member-id members))))]]))

(ctmx/defcomponent ^:endpoint member-table-ro [{:keys [tr db] :as req}]
  (let [members (controller/members db (sort-param req) (filter-param req))
        table-headers (member-table-headers-ro req tr)]
    [:div
     [:div {:class "flex flex-col space-y-4 sm:flex-row sm:items-center justify-end pb-4 mt-4"}
      (tr [:total]) ": " (count members)]
     [:table {:class "min-w-full divide-y divide-gray-300"}
      (ui/table-row-head table-headers)
      (ui/table-body
       (rt/map-indexed member-row-ro req (map :member/member-id members)))]]))

(defn member-add-form [{:keys [tr path sections]}]
  (let [required-label [:span  {:class "text-red-300 float-right"} " required"]]
    [:div {:class "flex flex-1 flex-col justify-between"}
     [:div {:class "divide-y divide-gray-200 px-4 sm:px-6"}
      [:div {:class "space-y-6 pt-6 pb-5"}
       [:div
        [:label {:for (path "name") :class "block text-sm font-medium text-gray-900"} (tr [:member/name]) required-label]
        [:div {:class "mt-2 space-y-5"}
         (ui/text :id (path "name") :name "name" :value "" :class "mb-2 sm:mb-0 sm:mr-2" :required? true)]]
       [:div
        [:label {:for (path "nick") :class "block text-sm font-medium text-gray-900"} (tr [:member/nick])]
        [:div {:class "mt-2 space-y-5"}
         (ui/text :id (path "nick") :name "nick" :value "" :required? false)]]
       [:div
        [:label {:for (path "email") :class "block text-sm font-medium text-gray-900"} (tr [:Email]) required-label]
        [:div {:class "mt-2 space-y-5"}
         (ui/input :id (path "email") :name "email" :label ""  :type :email  :required? true)]]
       [:div
        [:label {:for "username" :class "block text-sm font-medium text-gray-900"} (tr [:member/username]) required-label]
        [:div {:class "mt-2 space-y-5"}
         (ui/input :name "username" :label ""  :pattern (str members.domain/username-regex) :title (tr [:member/username-validation]) :required? true)]]
       [:div
        [:label {:for (path "phone") :class "block text-sm font-medium text-gray-900"} (tr [:Phone]) required-label]
        [:div {:class "mt-2 space-y-5"}
         (ui/input :id (path "phone") :type :tel :name "phone" :label ""  :pattern "\\+[\\d-]+" :title "Phone number starting with +country code. Only spaces, dashes, and numbers"  :required? true)]]
       [:div
        [:label {:for "section-name" :class "block text-sm font-medium text-gray-900"} (tr [:section]) required-label]
        [:div {:class "mt-2 space-y-5"}
         (ui/section-select  :id "section-name" :class "mt-4" :sections sections  :required? true)]]
       [:div
        [:div {:class "relative flex items-start"}
         [:div {:class "flex h-5 items-center"}
          (ui/checkbox :id "create-sno-id" :checked? true)]
         [:div {:class "ml-3 text-sm"}
          [:label {:for "create-sno-id" :class "font-medium text-gray-700"}
           (tr [:member/create-sno-id])]
          [:p {:class "text-gray-500"} (tr [:member/create-sno-id-description])]]]]
       [:div
        [:div {:class "relative flex items-start"}
         [:div {:class "flex h-5 items-center"}
          (ui/checkbox :id "active?" :checked? true)]
         [:div {:class "ml-3 text-sm"}
          [:label {:for "active?" :class "font-medium text-gray-700"}
           (tr [:Active])]]]]
       [:div {:id "new-member-form-errors"}]]]]))

(defn -member-create [req]
  (try
    (let [new-member (throw (ex-info "PORTED ELSEWHERE" {}))]
      (response/hx-redirect (url/link-member new-member)))
    (catch Exception e
      (if-let [error-msg (when (:validation/error (ex-data e))
                           (:validation/error (ex-data e)))]
        (ui/retarget-response
         "#new-member-form-errors"
         [:div {:id "new-member-form-errors" :class "text-red-500"}
          [:p [:span "⚠ "] error-msg]])
        (throw e)))))

(ctmx/defcomponent ^:endpoint member-create-endpoint [req]
  (when (util/post? req)
    (-member-create req)))

(defn member-table-action-button [{:keys [tr] :as req}]
  (let [filter-spec (filter-param req)
        active-preset (:preset filter-spec)
        active-preset-label (get {"all" :member/filter-all
                                  "active" :member/filter-active
                                  "inactive" :member/filter-inactive} active-preset :member/filter-active)]
    (ui/action-menu :button-icon icon/users-solid
                    :label (tr [active-preset-label])
                    :hx-boost "true"
                    :sections [{:items [{:label (tr [:member/filter-all]) :href "?filter-preset=all" :active? (= active-preset "all")}
                                        {:label (tr [:member/filter-active]) :href "?filter-preset=active"  :active? (= active-preset "active")}
                                        {:label (tr [:member/filter-inactive]) :href "?filter-preset=inactive" :active? (= active-preset "inactive")}]}]
                    :id "member-table-actions")))

(ctmx/defcomponent ^:endpoint members-index-page [{:keys [db] :as req} ^:boolean edit?]
  (when (util/delete? req) (controller/delete-invitation! req))
  (when (util/post? req) (controller/resend-invitation! req))
  (let [tr (i18n/tr-from-req req)
        comp-name (util/comp-namer #'members-index-page)
        sections (controller/sections db)
        open-invitations (controller/members-with-open-invites req)]
    [:div {:id id}
     (ui/page-header :title "Member Admin")
     (ui/slideover-panel-form {:title (tr [:member/new-member]) :id (path "slideover")
                               :form-attrs {:hx-post (util/comp-name #'member-create-endpoint) :hx-target (hash ".")}
                               :buttons (list
                                         (ui/button {:label (tr [:action/cancel]) :priority :white :attr {:_ (ui/slideover-extra-close-script (path "slideover")) :type "button"}})
                                         (ui/button {:label (tr [:action/save]) :priority :primary-orange}))}
                              (member-add-form {:tr tr :path path :sections sections}))

     [:div {:class "mt-2"}
      (when (seq open-invitations)
        [:div {:class "px-4 sm:px-6 lg:px-8 py-10"}
         (ui/divider-left (tr [:member/open-invitations]) nil)
         [:div {:class "mt-4"}
          [:table {:class "min-w-full divide-y divide-gray-300"}

           (ui/table-row-head [{:label (tr [:member/name]) :priority :medium :key :owner}
                               {:label (tr [:member/email]) :priority :medium :key :owner}
                               {:label "" :variant :action :key :action}])

           (ui/table-body
            (rt/map-indexed (fn [req idx {:member/keys [name email invite-code]}]
                              [:tr
                               [:td {:class "px-3 py-4"} name]
                               [:td {:class "px-3 py-4"} email]
                               [:td {:class "py-4 pl-3 pr-4 text-right text-sm font-medium sm:pr-6"}
                                [:span {:class "flex flex-row space-x-2"}
                                 [:button {:type "submit" :class "text-green-600 hover:text-sno-orange-900 cursor-pointer"
                                           :hx-target (hash ".")
                                           :hx-post (comp-name) :hx-vals {:code invite-code}} "Resend Invite"]
                                 [:button {:type "submit" :class "text-red-600 hover:text-sno-orange-900 cursor-pointer"
                                           :hx-target (hash ".")
                                           :hx-delete (comp-name) :hx-vals {:code invite-code}} (tr [:action/delete])]]]]) req open-invitations))]]])

      [:div {:class "px-4 sm:px-6 lg:px-8 mt-4"}
       (when (seq open-invitations) (ui/divider-left (tr [:nav/members]) nil))

       ;; table actions row 1
       [:div {:class "flex items-center justify-end"}
        [:div {:class "mt-4 sm:mt-0 sm:ml-16 flex sm:flex-row space-x-4"}
         (ui/toggle :label (tr [:action/quick-edit]) :active? edit? :id "member-table-edit-toggle" :hx-target (hash ".") :hx-get (comp-name) :hx-vals {"edit?" (not edit?)})
         (when-not edit?
           (ui/button :label (tr [:action/add]) :priority :primary :class "" :icon icon/plus :centered? true
                      :attr {:data-flyout-trigger (hash "slideover")}))]]

       ;; table actions row 2
       [:div {:class "flex flex-col space-y-4 sm:flex-row sm:items-center justify-between pb-4 mt-4"}
        [:div ;; search container
         [:label {:for "table-search", :class "sr-only"} (tr [:action/search])]
         [:div {:class "relative"}
          [:div {:class "absolute inset-y-0 left-0 flex items-center pl-3 pointer-events-none"}
           (icon/search {:class  "w-5 h-5 text-gray-500 "
                         ;; dark:text-gray-400
                         })]

          [:input {:type "text" :id "table-search"
                   ;; dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-blue-500 dark:focus:border-blue-500
                   :class "block p-2 pl-10 text-sm text-gray-900 border border-gray-300 rounded-md w-80 bg-gray-50 focus:ring-blue-500 focus:border-blue-500"
                   :name "q"
                   :hx-get (comp-name)
                   :value (get-in req [:query-params "q"])
                   :hx-trigger "keyup changed delay:500ms"
                   :hx-vals (util/remove-nils {:filter-preset (get-in req [:query-params "filter-preset"])
                                               :sort  (get-in req [:query-params "sort"])})
                   :hx-target (hash ".")
                   :placeholder (tr [:action/search])}]]]
        (member-table-action-button req)]

       [:div {:class "mt-4"}
        (if edit?
          (member-table-rw req)
          (member-table-ro req))]]]]))

(defn invite-accept-form [req {:keys [member invite-code]} error-msg]
  (let [tr (i18n/tr-from-req req)]
    (layout/centered-content req
                             [:form {:class "space-y-6" :action "/invite-accept" :method "POST"}
                              [:input {:type :hidden :name "invite-code" :value invite-code}]
                              [:h2 {:class "font-medium text-2xl"} (tr [:account/create-sno-id-title])]
                              [:p {:class "text-lg text-gray-700"} (tr [:account/create-sno-id-subtitle])]
                              [:div
                               [:label {:for "email" :class "block text-sm font-medium text-gray-700"} (tr [:member/email])]
                               [:div {:class "mt-1"}
                                [:input {:disabled true :value (:member/email member) :id "email" :name "email" :type "email" :autocomplete "email" :required true :class "block w-full appearance-none rounded-md border border-gray-300 px-3 py-2 placeholder-gray-400 shadow-xs focus:border-sno-orange-500 focus:outline-hidden focus:ring-sno-orange-500 sm:text-sm  disabled:cursor-not-allowed disabled:border-gray-200 disabled:bg-gray-50 disabled:text-gray-500"}]]]
                              [:div
                               [:label {:for "password" :class "block text-sm font-medium text-gray-700"} (tr [:account/password])]
                               [:div {:class "mt-1"}
                                [:input {:id "password" :name "password" :type "password" :autocomplete "new-password" :required true :class "block w-full appearance-none rounded-md border border-gray-300 px-3 py-2 placeholder-gray-400 shadow-xs focus:border-sno-orange-500 focus:outline-hidden focus:ring-sno-orange-500 sm:text-sm"}]]
                               [:p {:class "text-xs text-gray-700"} (tr [:account/password-instructions])]]
                              [:div
                               [:label {:for "password-confirm" :class "block text-sm font-medium text-gray-700"} (tr [:account/password-confirm])]
                               [:div {:class "mt-1"}
                                [:input {:id "password-confirm" :name "password-confirm" :autocomplete "new-password" :type "password" :required true :class "block w-full appearance-none rounded-md border border-gray-300 px-3 py-2 placeholder-gray-400 shadow-xs focus:border-sno-orange-500 focus:outline-hidden focus:ring-sno-orange-500 sm:text-sm"}]]
                               [:p {:class "text-xs text-gray-700"} (tr [:account/password-confirm-instructions])]]
                              (when error-msg
                                [:div
                                 [:p {:class "text-medium text-red-500"} error-msg]])
                              [:div
                               (ui/button :centered? true :priority :primary :label (tr [:account/create-account]) :class "w-full")]])))

(defn invite-invalid [req]
  (let [tr (i18n/tr-from-req req)]
    (layout/centered-content req
                             [:div
                              [:div (tr [:email/invite-expired])]])))

(defn invite-accept [req]
  (let [tr (i18n/tr-from-req req)
        invite-data (controller/load-invite req)]
    (if invite-data
      (invite-accept-form req invite-data nil)
      (invite-invalid req))))

(defn post-invite-login-link [env member]
  (str
   (url/absolute-link-login env)
   "?login_hint="
   (util/url-encode (:member/email member))))

(defn invite-accept-post [{:keys [tr system] :as req}]
  (try

    (let [member (controller/setup-account req)]
      (layout/centered-content req
                               [:div {:class "space-y-6"}
                                [:h2 {:class "font-medium text-2xl"} (tr [:account/account-created-title])]
                                [:p {:class "text-lg text-gray-700"} (tr [:account/account-created-subtitle])]
                                [:div
                                 (ui/link-button :priority :primary :centered? true
                                                 :class "w-full"
                                                 :label (tr [:login])
                                                 :attr {:href (post-invite-login-link (:env system) member)})]]))

    (catch Throwable e
      (let [reason (-> e ex-data :reason)]
        (condp = reason
          nil (throw e)
          :code-expired (invite-invalid req)
          (invite-accept-form req (->  e ex-data :invite-data) ((i18n/tr-from-req req) [reason])))))))

(comment

  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))
    (def system {:datomic {:conn conn}
                 :redis (-> state/system :app.ig/redis)
                 :i18n-langs (-> state/system :app.ig/i18n-langs)
                 :env (-> state/system :app.ig/env)})) ;; rcf

  (post-invite-login-link (:env system) (q/member-by-email db "me@caseylink.com"))

  ;;
  )
