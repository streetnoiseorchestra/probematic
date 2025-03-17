(ns app.gigs.views
  (:refer-clojure :exclude [comment hash])
  (:require
   [app.render :as render]
   [app.auth :as auth]
   [app.gigs.domain :as domain]
   [app.gigs.service :as service]
   [app.icons :as icon]
   [app.layout :as layout]
   [app.markdown :as markdown]
   [app.probeplan.domain :as probeplan.domain]
   [app.queries :as q]
   [app.ui :as ui]
   [app.urls :as url]
   [app.util :as util]
   [app.util.http :as http.util]
   [clojure.set :as set]
   [clojure.string :as str]
   [ctmx.core :as ctmx]
   [ctmx.response :as response]
   [ctmx.rt :as rt]
   [hiccup.util :as hiccup.util]
   [medley.core :as m]
   [tick.core :as t]))

(defn- gig-id-from-path [req]
  (http.util/path-param-uuid! req :gig/gig-id))

(defn radio-button  [idx {:keys [id name label value opt-id icon size class icon-class model disabled? required? data]
                          :or {size :normal
                               class ""
                               required? false
                               icon-class ""
                               disabled? false}}]
  (let [checked? (= model value)]
    [:label {:id id
             :class
             (ui/cs
              "cursor-pointer"
              class
              (if checked?
                "toggler--checked"
                "toggler--not-checked"))}

     [:input {:type "radio" :name name :value value :class "sr-only" :aria-labelledby id
              :required required?
              :disabled disabled?
              :checked checked?
              :data-toggler data
              :_ (format  "on change trigger radioChanged(value:[@value]) on <input[name='%s']/> end
                  on radioChanged(value) if I match <:checked/>
                    add .toggler--checked to the closest parent <label/>
                    add .{[@data-toggler]} to the closest parent <li.toggler/>
                    remove .toggler--not-checked from the closest parent <label/>
                  else
                    remove .toggler--checked from the closest parent <label/>
                    remove [@data-toggler] from the closest parent <li.toggler/>
                    remove .{[@data-toggler]} from the closest parent <li.toggler/>
                    add .toggler--not-checked to the closest parent <label/>
                  end" name)}]
     [:span
      (icon {:class (ui/cs "h-8 w-8" icon-class)})]]))

(defn radio-button-group [& {:keys [options id label class value required?]
                             :or {class ""
                                  required? false}}]
  [:fieldset
   [:legend {:class ""} label]
   [:div {:class (ui/cs "mt-1 flex items-center space-x-3" class)}
    (->> options
         (map #(merge {:name id :model value :required? required?} %))
         (map-indexed radio-button))]])

(defn log-play-legend [tr]
  (let [shared-classes "h-8 w-8 border-solid border-b-2 pb-1"]
    [:div {:class "ml-2"}
     [:h3 {:class "font-bold text-gray-900"}
      (tr [:play-log/diagram-legend])]

     [:div {:class "grid grid-cols-3 space-y-2 max-w-sm sm:max-w-none sm:grid-cols-7 sm:space-x-3"}
      [:div {:class "flex items-center justify-start sm:min-w-fit"}
       (icon/circle-xmark-outline {:class (ui/cs shared-classes "icon-not-played border-gray-500 toggler--checked")})
       (tr [:play-log/not-played])]
      [:div {:class "flex items-center justify-start"}
       (icon/smile {:class (ui/cs shared-classes "icon-smile border-green-500 toggler--checked")})
       (tr [:play-log/nice])]
      [:div {:class "flex items-center justify-start"}
       (icon/meh {:class (ui/cs shared-classes "icon-meh border-blue-500 toggler--checked")})
       (tr [:play-log/okay])]
      [:div {:class "flex items-center justify-start"}
       (icon/sad {:class (ui/cs shared-classes "icon-sad border-red-500 toggler--checked")})
       (tr [:play-log/bad])]
      [:div {:class "col-span-2 flex justify-start items-center"}
       (icon/fist-punch {:class (ui/cs shared-classes "icon-fist-punch intensiv--checked")})
       (tr [:play-log/intensive])]]]))

(ctmx/defcomponent gig-log-play [{:keys [db] :as req} idx play]
  (let [was-played? (some? (:played/play-id play))
        check-id (path "intensive")
        radio-id (path "feeling")
        song (:played/song play)
        song-id (:song/song-id song)
        feeling-value (util/kw->str (or (:played/rating play) :play-rating/not-played))
        intensive?  (= :play-emphasis/intensiv (:played/emphasis play))
        toggler-class (get {"play-rating/not-played" "toggler--not-played"
                            "play-rating/good" "toggler--feeling-good"
                            "play-rating/bad" "toggler--feeling-sad"
                            "play-rating/ok" "toggler--feeling-meh"} feeling-value)]
    [:li {:class (ui/cs "toggler" toggler-class)}
     [:input {:type "hidden" :value song-id :name (path "song-id")}]
     (when was-played?
       [:input {:type "hidden" :value (:played/play-id play) :name (path "play-id")}])
     [:div {:class "inline-block"}
      (:song/title song)]

     [:div {:class "flex"}
      [:div
       (radio-button-group  :id  radio-id :label ""
                            :required? true
                            :value feeling-value
                            :class "emotion-radio"
                            :options [{:id (path "feeling/not-played") :value "play-rating/not-played" :icon icon/circle-xmark-outline :size :small :class "icon-not-played" :data "toggler--not-played"}
                                      {:id (path "feeling/good") :label "Nice!" :value "play-rating/good" :icon icon/smile :size :large :class "icon-smile" :data "toggler--feeling-good"}
                                      {:id (path "feeling/ok")  :label "Okay" :value "play-rating/ok" :icon icon/meh :size :large :class "icon-meh"  :data "toggler--feeling-meh"}
                                      {:id  (path "feeling/bad")  :label "Uh-oh" :value "play-rating/bad" :icon icon/sad :size :large :class "icon-sad"  :data "toggler--feeling-sad"}])]
      [:div  {:class "border-l-4  border-gray-200 ml-2 pl-2 mt-1 flex items-center space-x-3"}
       [:label  {:for check-id :class (ui/cs "icon-fist-punch cursor-pointer" (when intensive? "intensiv--checked"))}
        [:input {:type "hidden" :name check-id :value "play-emphasis/durch"}]
        [:input {:type "checkbox" :class "sr-only" :name check-id :id check-id :value "play-emphasis/intensiv"
                 :_ "on change if I match <:checked/>
                                                add .intensiv--checked to the closest parent <label/>
                                                else
                                                remove .intensiv--checked from the closest parent <label/>
                                              end"
                 :checked intensive?}]
        (icon/fist-punch {:class "h-8 w-8"})]]]]))

(ctmx/defcomponent ^:endpoint gig-log-plays [{:keys [tr db] :as req}]
  (let [gig-id (gig-id-from-path req)
        post? (util/post? req)]
    (if post?
      (do
        (service/log-play! req gig-id)
        (response/hx-redirect (url/link-gig gig-id)))
      (let [gig (q/retrieve-gig db gig-id)
            plays (q/plays-by-gig db gig-id)
            planned-songs (q/planned-songs-for-gig db gig-id)
            songs-not-played (service/songs-not-played plays (q/retrieve-all-songs db))
            ;; our log-play component wants to have "plays" for every song
            ;; so we enrich the actual plays with stubs for the songs that were not played
            plays (sort-by #(-> % :played/song :song/title) (concat plays
                                                                    (map (fn [s] {:played/song s}) songs-not-played)))]

        [:form {:id id :hx-post (path ".") :class "space-y-4"}
         (ui/page-header :title (list  (:gig/title gig) " (" (ui/datetime (:gig/date gig)) ")")
                         :subtitle
                         [:div
                          [:p {:class "pb-2"} (tr [:gig/play-log-subtitle])]
                          [:p  (tr [:gig/was-planned-to-play])]
                          [:p {:class " text-gray-600 ml-2"}
                           (str/join ", " (mapv :song/title planned-songs))]])

         (log-play-legend tr)
         [:ul {:class "toggler-container"}
          (rt/map-indexed gig-log-play req plays)]
         [:div
          [:div {:class "flex justify-end space-x-2"}
           (ui/link-button :label (tr [:action/cancel]) :attr {:href (url/link-gig gig)} :priority :white)
           (ui/button :label (tr [:action/save]) :priority :primary)]]]))))

(defn gig-row [{:gig/keys [status title location date end-date] :as gig}]
  (let [style-icon "mr-1.5 h-5 w-5 shrink-0 text-gray-400"]
    [:a {:href (url/link-gig gig) :class "block hover:bg-gray-50"}
     [:div {:class "px-4 py-4 sm:px-6"}
      [:div {:class "flex items-center justify-between"}
       [:div {:class "flex items-center space-x-2"}
        (ui/gig-status-icon status)
        [:p {:class "truncate text-sm font-medium text-sno-orange-600"} title]]]

      [:div {:class "mt-2 sm:flex sm:justify-between"}
       [:div {:class "flex items-center text-sm text-gray-500"}
        (icon/location-dot {:class style-icon})
        location]
       [:div {:class "mt-2 flex items-center text-sm text-gray-500 mt-0 ml-6 min-w-[8rem]"}
        (icon/calendar {:class style-icon})
        (if end-date
          (ui/daterange date end-date)
          (ui/datetime date))]]]]))

(defn plan-icons []
  (let [icon-class "mr-3"
        size-class "w-5 h-5"
        red-class "text-red-500 group-hover:text-red-500"
        green-class "text-green-500 group-hover:text-green-500"]
    {:plan/definitely {:value (name :plan/definitely)  :icon icon/circle :icon-class (ui/cs icon-class size-class green-class)}
     :plan/probably {:value (name :plan/probably) :icon icon/circle-outline  :icon-class (ui/cs icon-class size-class green-class)}
     :plan/unknown {:value (name :plan/unknown) :icon icon/question :icon-class (ui/cs icon-class  size-class "text-gray-500")}
     nil {:value (name :plan/unknown) :icon icon/question :icon-class (ui/cs icon-class  size-class "text-gray-500")}
     :plan/probably-not {:value (name :plan/probably-not) :icon icon/square-outline :icon-class (ui/cs icon-class size-class red-class)}
     :plan/definitely-not {:value (name :plan/definitely-not)    :icon icon/square :icon-class (ui/cs icon-class size-class red-class)}
     :plan/not-interested {:value (name :plan/not-interested)    :icon icon/xmark :icon-class (ui/cs icon-class  size-class "text-black")}
     :plan/no-response {:value (name :plan/no-response) :icon icon/minus :icon-class (ui/cs icon-class  size-class "text-gray-500")}}))

(defn attendance-opts [tr size]
  (let [icon-class "mr-3"
        size-class (if (= size :large) "w-5 h-5" "w-3 h-3")
        red-class "text-red-500 group-hover:text-red-500"
        green-class "text-green-500 group-hover:text-green-500"]
    {:opts [{:label (tr [:plan/definitely]) :value (name :plan/definitely)  :icon icon/circle :icon-class (ui/cs icon-class size-class green-class) :enabled? true}
            {:label (tr [:plan/probably]) :value (name :plan/probably) :icon icon/circle-outline  :icon-class (ui/cs icon-class size-class green-class) :enabled? false}
            {:label (tr [:plan/unknown]) :value (name :plan/unknown) :icon icon/question :icon-class (ui/cs icon-class  size-class "text-gray-500") :enabled? true}
            {:label (tr [:plan/probably-not]) :value (name :plan/probably-not) :icon icon/square-outline :icon-class (ui/cs icon-class size-class red-class) :enabled? false}
            {:label (tr [:plan/definitely-not]) :value (name :plan/definitely-not)    :icon icon/square :icon-class (ui/cs icon-class size-class red-class) :enabled? true}
            {:label (tr [:plan/not-interested]) :value (name :plan/not-interested)    :icon icon/xmark :icon-class (ui/cs icon-class  size-class "text-black") :enabled? true}]
     :default {:label (tr [:plan/no-response]) :value (name :plan/no-response) :icon icon/minus :icon-class (ui/cs icon-class  size-class "text-gray-500") :enabled? true}}))

(defn attendance-dropdown-opt [{:keys [label value icon icon-class enabled?]}]
  [:a {:href "#" :data-value value
       :class (ui/cs "hover:bg-gray-200 text-gray-700 group flex items-center px-4 py-2 text-sm" (when-not enabled? "hidden"))
       :role "menuitem" :tabindex "-1" :id "menu-item-0"}
   (icon {:class  icon-class}) label])

(defn attendance-dropdown [& {:keys [member-id gig-id value tr]
                              :or {value "no-response"}}]
  (let [{:keys [opts default]} (attendance-opts tr :small)
        current-opt (or (m/find-first #(= value (:value %)) (:opts (attendance-opts tr :small))) default)
        button-size-class-normal "px-4 py-2 text-sm "
        button-size-class-small "px-2 py-1 text-xs "]
    [:div {:class "dropdown relative inline-block text-left"}
     [:div
      [:button {:type "button"
                :class (ui/cs button-size-class-small
                              "dropdown-button inline-flex w-full justify-center rounded-md border border-gray-300 bg-white font-medium text-gray-700 shadow-xs hover:bg-gray-50 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2 focus:ring-offset-gray-100 min-h-[25px]")
                :aria-expanded "true" :aria-haspopup "true"}
       [:input {:type "hidden" :name "member-id" :value member-id}]
       [:input {:type "hidden" :name "gig-id" :value gig-id}]
       [:input {:type "hidden" :name "plan" :value (:value current-opt) :class "item-input"}]
       [:span {:class "item-icon"}
        ((:icon current-opt) {:class (ui/cs (:icon-class current-opt) "w-3 h-3")})]
       (icon/chevron-down {:class (ui/cs "-mr-1 ml-1 h-3 w-3")})]]
     [:div {:class "dropdown-menu-container hidden"}
      [:div {:class (ui/cs
                     "dropdown-choices"
                     (when false "absolute right-0 ")
                     "z-10 w-56 origin-top-right divide-y divide-gray-100 rounded-md bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden")
             :role "menu" :aria-orientation "vertical" :aria-labelledby "menu-button" :tabindex "-1"}
       [:div {:class "py-1" :role "none"}
        (map attendance-dropdown-opt  opts)]]]]))

(defn snooze-dropdown [& {:keys [member-id gig-id tr snooze-endpoint hx-target remind-in-days]}]
  (let [hx-attrs {:hx-vals {"member-id" (str member-id) "gig-id" (str gig-id)} :hx-post snooze-endpoint :hx-target hx-target}]
    (ui/action-menu
     :button-icon (if remind-in-days icon/bell-alert icon/bell-snooze)
     :minimal? true :button-icon-class (if remind-in-days "text-sno-green-600" "text-sno-orange-600")
     :id (str "snooze-" gig-id)
     :sections [{:label (str (tr [:reminders/remind-me-in]) ":")
                 :items [{:label (tr [:reminders/one-day]) :tag :button
                          :attr (assoc-in hx-attrs [:hx-vals "remind-in-days"] 1)}
                         {:label (tr [:reminders/three-days]) :tag :button
                          :attr (assoc-in hx-attrs [:hx-vals "remind-in-days"] 3)}
                         {:label (tr [:reminders/one-week]) :tag :button
                          :attr (assoc-in hx-attrs [:hx-vals "remind-in-days"] 7)}]}])))

(defn motivation-endpoint [{:keys [tr] :as req} {:keys [id hash value]} comp-name gig-id member-id motivation]
  (let [post?      (util/post? req)
        member-id   (or member-id (value "member-id"))
        gig-id     (or gig-id (value "gig-id"))
        motivation (if post?
                     (-> (service/update-attendance-motivation! req gig-id) :attendance :attendance/motivation)
                     motivation)]
    [:form {:id id :hx-target (hash ".")}
     [:input {:type :hidden :name  "gig-id" :value gig-id}]
     (ui/select
      :id  "motivation"
      :value (when motivation (name motivation))
      :size :small
      :extra-attrs {:hx-trigger "change" :hx-post (comp-name) :hx-vals {:member-id (str member-id)}}
      :options (map (fn [m] {:label (tr [m]) :value (name m)}) domain/motivations))]))

(defn snooze-endpoint [req {:keys [id hash value]} comp-name gig-id member-id]
  (let [post?      (util/post? req)
        member-id   (or member-id (value "member-id"))
        gig-id     (or gig-id (value "gig-id"))
        remind-in-days (if post? (service/set-reminder! req gig-id member-id
                                                        (-> req (http.util/unwrap-params) :remind-in-days http.util/parse-long))
                           (service/get-reminder req gig-id member-id))]

    [:div {:id id}
     (snooze-dropdown :tr (:tr req)
                      :member-id member-id
                      :gig-id gig-id
                      :hx-target (hash ".")
                      :remind-in-days remind-in-days
                      :snooze-endpoint (comp-name))]))

(defn comment-endpoint [req {:keys [id hash value]} comp-name gig-id member-id comment edit?]
  (let [post? (util/post? req)
        member-id (or member-id (value "member-id"))
        gig-id (or gig-id (value "gig-id"))
        comment (if post?
                  (-> (service/update-attendance-comment! req gig-id) :attendance :attendance/comment)
                  comment)]
    [:div {:id id :class
           ;; this css is relevant only for the gig-detail-page
           ;; even thoough this function is reused by the dashboard page, the css doesn't do anything
           ;; but could cause problems in the future
           (ui/cs (when-not comment "col-start-0 col-span-1")
                  (when comment "col-span-3 col-start-3 lg:col-start-0 lg:col-span-3"))}
     (if edit?
       (ui/text :name "comment" :value comment :required? false :size :small
                :extra-attrs {:hx-target (hash ".") :hx-post (comp-name) :hx-trigger "focusout, keydown[key=='Enter'] changed"  :autofocus true
                              :hx-vals {"member-id" (str member-id) "gig-id" (str gig-id)}
                              :_ "on focus or htmx:afterRequest or load
                                          set :initial_value to my value
                                        end
                                        on keyup[key=='Escape']
                                          set my value to the :initial_value then
                                          blur() me
                                        end"})

       (let [hx-attrs {:hx-target (hash ".") :hx-get (comp-name) :hx-vals {:gig-id (str gig-id) :member-id (str member-id) :comment comment :edit? true}}]
         (if comment
           [:span (merge hx-attrs {:class "text-xs sm:text-sm ml-2 link-blue"})
            comment]
           [:button  hx-attrs
            (icon/comment-outline {:class "ml-2 mb-2 w-5 h-5 cursor-pointer hover:text-blue-500 active:text-blue-300"})])))]))

(defn plan-endpoint [{:keys  [tr] :as  req} {:keys [id  value]} comp-name gig-id member-id plan]
  (let [post? (util/post? req)
        member-id   (or member-id (value "member-id"))
        gig-id     (or gig-id (value "gig-id"))
        plan-kw (or
                 (if post?
                   (-> (service/update-attendance-plan! req gig-id) :attendance :attendance/plan)
                   plan)
                 :plan/no-response)]
    [:form {:hx-post (comp-name) :id id :hx-trigger "planChanged"} ;; this form is triggered by javascript
     (attendance-dropdown :tr tr :gig-id gig-id :member-id member-id :value (name plan-kw))]))

(ctmx/defcomponent ^:endpoint gig-attendance-person-plan [{:keys [db] :as req} member-id plan]
  (plan-endpoint req
                 {:path path :id id :hash hash :value value}
                 (util/comp-namer #'gig-attendance-person-plan)
                 (gig-id-from-path req)
                 (util/ensure-uuid! member-id)
                 plan))

(ctmx/defcomponent ^:endpoint gig-attendance-person-comment [{:keys [db] :as req} member-id comment  ^:boolean edit?]
  (comment-endpoint req
                    {:path path :id id :hash hash :value value}
                    (util/comp-namer #'gig-attendance-person-comment)
                    (gig-id-from-path req)
                    (util/ensure-uuid! member-id)
                    comment edit?))

(ctmx/defcomponent ^:endpoint gig-attendance-person-motivation [{:keys [db] :as req} member-id motivation]
  (motivation-endpoint req
                       {:path path :id id :hash hash :value value}
                       (util/comp-namer #'gig-attendance-person-motivation)
                       (gig-id-from-path req)
                       (util/ensure-uuid! member-id)
                       motivation))

(ctmx/defcomponent ^:endpoint gig-attendance-person-snooze [req member-id]
  (snooze-endpoint req
                   {:path path :id id :hash hash :value value}
                   (util/comp-namer #'gig-attendance-person-snooze)
                   (gig-id-from-path req)
                   (util/ensure-uuid! member-id)))

(ctmx/defcomponent ^:endpoint gig-attendance-person [{:keys [db] :as req} idx attendance]
  (let [{:member/keys [member-id] :as member} (:attendance/member attendance)
        has-comment? (:attendance/comment attendance)
        ;; current-user           (auth/get-current-member req)
        ;; is-current-user? (= (:member/member-id current-user) member-id)
        ]
    ;; (when is-current-user?
    ;;   [:div {:class ""} (gig-attendance-person-snooze req member-id)])
    [:div {:id id}
     [:div {:class "grid grid-cols grid-cols-6 gap-x-2"}
      [:div {:class "col-span-2 lg:col-span-1 align-middle"}
       [:a {:href (url/link-member member-id) :class "text-blue-500 hover:text-blue-600 align-middle"}
        (ui/member-nick member)]]
      [:div {:class "col-span-1"} (gig-attendance-person-plan req member-id (:attendance/plan attendance))]
      [:div {:class "col-span-2 lg:col-span-1"} (gig-attendance-person-motivation req member-id (:attendance/motivation attendance))]
      ;;;
      (gig-attendance-person-comment req member-id (:attendance/comment attendance) false)
      ;; [:div {:class ""}]
      ]]))

(defn gig-attendance-person-archived [{:keys [db] :as req} idx attendance]
  (let [{:member/keys [member-id] :as member} (:attendance/member attendance)]
    [:div {:class "grid grid-cols grid-cols-5"}
     [:div {:class "col-span-2 align-middle"}
      [:a {:href (url/link-member member-id) :class "text-blue-500 hover:text-blue-600 align-middle"}
       (ui/member-nick member)]]
     [:div {:class "col-span-1"}
      (let [{:keys [icon icon-class]} (get (plan-icons) (:attendance/plan attendance))]
        (icon {:class icon-class}))]
     [:div {:class "col-span-1"} (:attendance/motivation attendance)]
     [:div {:class "col-span-1 "} (:attendance/comment attendance)]]))

(defn section-name-wrappable [section]
  (interpose [:span "/" [:wbr]]
             (str/split (:section/name section) #"/")))

(ctmx/defcomponent ^:endpoint gig-attendance [{:keys [db] :as req} idx section]
  [:div {:class "gap-x-0 gap-y-8 mt-2 mb-2 px-4 py-0 sm:px-6 border-b border-gray-200"}
   [:div {:class "font-bold break-words"} (section-name-wrappable section)]
   [:div {:id id :class ""}
    (rt/map-indexed gig-attendance-person req (:members section))]])

(ctmx/defcomponent ^:endpoint gig-attendance-archived [{:keys [db] :as req} idx section]
  [:div {:id id :class (ui/cs "grid grid-cols-3 gap-x-0 gap-y-8 mb-2 px-4 py-0 sm:px-6"
                              (when (= 0 (mod idx 2))
                                "bg-gray-100"))}

   [:div {:class "col-span-1 font-bold break-words"} (section-name-wrappable section)]
   [:div {:class "col-span-2"}
    (rt/map-indexed gig-attendance-person-archived req (:members section))]])

(defn gig-comment-li [comment]
  (let [{:comment/keys [body author created-at]} comment]
    [:li
     [:div {:class "flex space-x-3"}
      [:div {:class "shrink-0"}
       [:a {:href (url/link-member author)}
        (ui/avatar-img author :class "h-10 w-10 rounded-full")]]
      [:div
       [:div {:class "text-sm"}
        [:a {:href (url/link-member author) :class "font-medium text-gray-900"}
         (ui/member-nick author)]]
       [:div {:class "mt-1 text-sm text-gray-700"}
        [:p body]]
       [:div {:class "mt-2 space-x-2 text-sm"}
        [:span {:class "font-medium text-gray-500"}
         (ui/humanize-dt created-at)]]]]]))

(defn comment-list [tr comments]
  [:div {:class "px-4 py-6 sm:px-6"}
   (if (empty? comments)
     (tr [:gig/no-comments])
     [:ul {:role "list", :class "space-y-8"}
      (map gig-comment-li comments)])])

(defn comment-header [tr]
  [:div {:class "px-4 py-5 sm:px-6"}
   [:h2 {:id "notes-title", :class "text-lg font-medium text-gray-900"}
    "Comments"]])

(defn comment-input [member target endpoint tr]
  [:div {:class "bg-gray-50 px-4 py-6 sm:px-6"}
   [:div {:class "flex space-x-3"}
    [:div {:class "shrink-0"}
     (ui/avatar-img member :class "h-10 w-10 rounded-full")]
    [:div {:class "min-w-0 flex-1"}
     [:form {:hx-target target :hx-post endpoint}
      [:div
       [:label {:for "comment-body", :class "sr-only"} "Comment"]
       [:textarea {:id "comment-body", :name "body", :rows "3", :class "block w-full rounded-md border-gray-300 shadow-xs focus:border-blue-500 focus:ring-blue-500 sm:text-sm"
                   :placeholder (tr [:gig/comment-placeholder])}]]
      [:div {:class "mt-3 flex items-center justify-between"}
       [:button {:type "submit", :class "inline-flex items-center justify-center rounded-md border border-transparent bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-xs hover:bg-blue-700 focus:outline-hidden focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"}
        (tr [:action/comment])]]]]]])

(defn comment-section [member archived? id endpoint tr comments]
  [:div {:id id :class "divide-y divide-gray-200"}
   (comment-header tr)
   (comment-list tr comments)
   (when (and member (not archived?))
     (comment-input member (str "#" id) endpoint tr))])

(ctmx/defcomponent ^:endpoint gigs-detail-page-comment [{:keys [tr] :as req} gig]
  (let [comp-name              (util/comp-namer #'gigs-detail-page-comment)
        archived?              (domain/gig-archived? gig)
        current-user           (auth/get-current-member req)
        {:gig/keys [comments]} (cond (util/post? req) (service/post-comment! req)
                                     :else            gig)]
    (comment-section current-user archived? id (comp-name) tr comments)))

;;;; Setlist

(defn setlist-song-checkbox [selected-songs idx song]
  (let [this-song-id (str (:song/song-id song))
        {:keys [song-order]} (m/find-first #(= (:song-id %) this-song-id) selected-songs)]
    [:li
     [:label {:for this-song-id :class "rounded-sm border-4 mx-0 my-1 p-2  basis-1/2 grid grid-flow-col grid-cols-auto justify-between"}
      [:span {:class "truncate"} (:song/title song)]
      [:input {:type :hidden :name (str idx "_songs_song-order")  :value (or song-order "9999")}]
      [:input {:type :checkbox :id this-song-id  :name (str idx "_songs_song-id") :value this-song-id
               :checked (some? song-order)}]]]))

(declare gig-setlist-sort)
(declare gigs-detail-page-setlist)

(ctmx/defcomponent ^:endpoint gig-setlist-choose-songs [{:keys [tr db] :as req}]
  (let [all-songs (q/retrieve-active-songs db)
        comp-name (util/comp-namer #'gig-setlist-choose-songs)]
    (if (util/put? req)
      (gig-setlist-sort req (util/unwrap-params req))
      (ui/trigger-response "setPageDirty"
                           (let [selected-songs (util/unwrap-params req)]
                             (ui/panel {:title (tr [:gig/setlist]) :id "setlist-container"
                                        :buttons (ui/button :class "pulse-delay" :label (tr [:action/continue]) :priority :primary :form id)}
                                       [:form {:hx-target "#setlist-container" :hx-put (comp-name) :id id}
                                        [:div {:class "flex justify-end"}
                                         (ui/link-button :class "cursor-pointer" :label (tr [:action/select-all]) :priority :white :attr {:_ "on click set <input[type=checkbox] />'s checked to true"})]

                                        [:ul {:class "p-0 m-0 grid grid-cols-2 md:grid-cols-2 lg:grid-cols-3 gap-y-0 md:gap-x-2"}
                                         (map-indexed (partial setlist-song-checkbox selected-songs) all-songs)]]))))))

(ctmx/defcomponent ^:endpoint  gig-setlist-sort [{:keys [tr db] :as req} ^:array selected-songs]
  (let [song-ids (->>  (or selected-songs (-> req util/json-params :songs))
                       (filter :song-id)
                       (sort-by :song-order)
                       (map :song-id)
                       (map util/ensure-uuid))
        songs (util/index-sort-by song-ids :song/song-id (q/retrieve-songs db song-ids))]
    (ui/panel {:title (tr [:gig/setlist]) :id "setlist-container"
               :buttons (ui/button :class "pulse-delay" :label (tr [:action/save]) :priority :primary :form id)}
              [:form {:class "w-full" :hx-post (util/endpoint-path gigs-detail-page-setlist) :hx-target "#setlist-container" :id id}
               [:p "Drag the songs into setlist order."]
               [:div {:class "htmx-indicator pulsate"} (tr [:updating])]
               [:ul {:class "sortable p-0 m-0 grid grid-cols-1 gap-y-0 md:gap-x-2 max-w-sm"}
                (map (fn [{:song/keys [song-id title]}]
                       [:li {:class "rounded-sm border-4 mx-0 my-1 p-2  basis-1/2 grid grid-flow-col grid-cols-auto justify-between"}
                        [:div {:class "drag-handle cursor-pointer"} (icon/bars {:class "h-5 w-5"})]
                        [:input {:type :hidden :name "song-ids" :value song-id}]
                        title]) songs)]])))

(defn gigs-detail-page-setlist-list-ro [selected-songs]
  [:ul {:class "list-disc" :hx-boost "true"}
   (map (fn [{:song/keys [title solo-info] :as song}]
          [:li {:class "ml-4"}
           [:a {:href (url/link-song song) :class "link-blue"}
            title]
           (when-not (str/blank? solo-info)
             (list " (" solo-info ")"))]) selected-songs)])

;;;; Setlist editing Flow:
;; setlist --[Edit]-----> choose songs --[Save]--> order --[Done]--> setlist (repeat)
;;        \--[Reorder]---------------------------/
;;

(defn serialize-selected-songs [selected-songs]
  (map-indexed (fn [idx {:keys [song-order song-id]}]
                 (list
                  [:input {:type :hidden :name (str idx "_songs_song-order")  :value (or song-order idx)}]
                  [:input {:type :hidden :name (str idx "_songs_song-id") :value song-id}]))
               selected-songs))

(ctmx/defcomponent ^:endpoint  gigs-detail-page-setlist [{:keys [tr db] :as req}]
  gig-setlist-choose-songs
  gig-setlist-sort
  (let [comp-name (util/comp-namer #'gigs-detail-page-setlist)
        ;; song-ids (map util/ensure-uuid song-ids)
        ;; songs (util/index-sort-by song-ids :song/song-id (if (util/post? req)
        ;;                                                    (service/update-setlist! req song-ids)
        ;;                                                    (q/retrieve-songs db song-ids)))

        songs (if (util/post? req)
                (service/update-setlist! req (util/unwrap-params req))
                (q/setlist-songs-for-gig db (gig-id-from-path req)))
        selected-songs (map-indexed (fn [idx song] {:song-order idx :song-id (-> song :song/song-id str)}) songs)]
    (ui/panel {:title (tr [:gig/setlist]) :id "setlist-container"
               :buttons (when (seq songs)
                          (list
                           [:form {:hx-post (util/endpoint-path gig-setlist-choose-songs)  :hx-target "#setlist-container"}
                            (serialize-selected-songs selected-songs)
                            (ui/button :label (tr [:action/edit]) :priority :white)]
                           (list
                            [:form {:hx-post (util/endpoint-path gig-setlist-sort)  :hx-target "#setlist-container"}
                             (serialize-selected-songs selected-songs)
                             (ui/button :label (tr [:action/reorder]) :priority :white)])))}
              [:div {:id "setlist-container" :class ""}
               (if (empty? songs)
                 [:div {:class "flex flex-col items-center justify-center"}
                  [:div
                   (ui/button :label (tr [:gig/create-setlist]) :priority :primary :icon icon/plus
                              :attr {:hx-get (util/endpoint-path gig-setlist-choose-songs)
                                     :hx-vals {:target (hash ".") :post (comp-name)} :hx-target "#setlist-container"})]]
                 (gigs-detail-page-setlist-list-ro songs))])))
;;;; Probeplan
;;; The flow is the same as the setlist, with the addition that in the drop phase we set the emphasis
(defn probeplan-song-checkbox [selected-songs idx song]
  (let [this-song-id (str (:song/song-id song))
        {:keys [position emphasis] :as d} (m/find-first #(= (:song-id %) this-song-id) selected-songs)]
    [:li
     [:label {:for this-song-id :class "rounded-sm border-4 mx-0 my-1 p-2  basis-1/2 grid grid-flow-col grid-cols-auto justify-between"}
      [:span {:class "truncate"} (:song/title song)]
      [:input {:type :hidden :name (str idx "_songs_position")  :value (or position "9999")}]
      [:input {:type :hidden :name (str idx "_songs_emphasis")  :value emphasis}]
      [:input {:type :checkbox :id this-song-id  :name (str idx "_songs_song-id") :value this-song-id
               :checked (some? position)}]]]))

(declare gig-probeplan-sort)
(declare gigs-detail-page-probeplan)

(ctmx/defcomponent ^:endpoint gig-probeplan-choose-songs [{:keys [tr db] :as req}]
  (let [all-songs (q/retrieve-active-songs db)
        comp-name (util/comp-namer #'gig-probeplan-choose-songs)]
    (if (util/put? req)
      (gig-probeplan-sort req (util/unwrap-params req))
      (let [selected-songs (util/unwrap-params req)]
        (ui/panel {:title (tr [:gig/probeplan]) :id "probeplan-container"
                   :buttons (ui/button :class "pulse-delay" :label (tr [:action/save]) :priority :primary :form id)}
                  [:form {:hx-target "#probeplan-container" :hx-put (comp-name) :id id}
                   [:p {:class "probeplan-instruction mb-2 text-xl"} (tr [:gig/probeplan-choose])]
                   [:ul {:class "p-0 m-0 grid grid-cols-2 md:grid-cols-2 lg:grid-cols-3 gap-y-0 md:gap-x-2"}
                    (map-indexed (partial probeplan-song-checkbox selected-songs) all-songs)]])))))

(ctmx/defcomponent  gig-probeplan-sort-item [req idx {:song/keys [song-id title] :keys [position emphasis] :as arg}]
  (let [checked? (= :probeplan.emphasis/intensive (probeplan.domain/str->play-emphasis emphasis))]
    [:li {:class "rounded-sm border-4 mx-0 my-1 p-2  basis-1/2 grid grid-flow-col grid-cols-auto justify-between items-center"}
     [:div {:class "drag-handle cursor-pointer"} (icon/bars {:class "h-5 w-5"})]
     [:input {:type :hidden :name (path "song-id") :value song-id}]
     [:input {:type :hidden :name (path "position") :value idx :data-sort-order true}]
     title
     [:div  {:class "border-l-4  border-gray-200 ml-2 pl-2 mt-1 flex items-center space-x-3"}
      [:label  {:for id :class (ui/cs "icon-fist-punch cursor-pointer" (when checked? "intensiv--checked"))}
       [:input {:type :checkbox :class "sr-only" :name (path "emphasis") :id id
                :data-maximum probeplan.domain/MAX-INTENSIVE :data-checkbox-limit true
                :_ "
on click
                     if checkboxLimitReached()
                       halt the event
                       then add .shake-horizontal to <p.probeplan-instruction/>
                       then settle then remove .shake-horizontal from <p.probeplan-instruction/>
end
on change if I match <:checked/>
                                                add .intensiv--checked to the closest parent <label/>
                                                else
                                                remove .intensiv--checked from the closest parent <label/>
                                              end"
                :checked checked?}]
       (icon/fist-punch {:class "h-8 w-8"})]]]))

(ctmx/defcomponent ^:endpoint  gig-probeplan-sort [{:keys [tr db] :as req} ^:array selected-songs]
  (let [selected-songs (->>  (or selected-songs (-> req util/json-params :songs))
                             (filter :song-id)
                             (map #(update % :position (fn [v] (Integer/parseInt v))))
                             (map #(update % :song-id util/ensure-uuid)))
        selected-songs (->> (clojure.set/join selected-songs (q/retrieve-songs db (map :song-id selected-songs))  {:song-id :song/song-id})
                            (into [])
                            (sort-by :position))]
    (ui/panel {:title (tr [:gig/probeplan]) :id "probeplan-container"
               :buttons (ui/button :class "pulse-delay" :label (tr [:action/done]) :priority :primary :form id)}
              [:form {:class "w-full" :hx-post (util/endpoint-path gigs-detail-page-probeplan) :hx-target "#probeplan-container" :id id}
               [:p {:class "probeplan-instruction mb-2 text-xl"} (tr [:gig/probeplan-sort])]
               [:div {:class "htmx-indicator pulsate"} (tr [:updating])]
               [:ul {:class "sortable p-0 m-0 grid grid-cols-1 gap-y-0 md:gap-x-2 max-w-sm"}
                (rt/map-indexed gig-probeplan-sort-item req selected-songs)]])))

(defn gigs-detail-page-probeplan-list-ro [tr selected-songs]
  [:div {:class "mt-1 grid grid-cols-1 gap-4 sm:grid-cols-2" :hx-boost "true"}
   (map (fn [{:song/keys [title last-played-on] :keys [emphasis] :as song}]
          (let [intensive? (= emphasis :probeplan.emphasis/intensive)]
            [:div {:class
                   (ui/cs
                    "relative flex items-center space-x-3 rounded-lg px-3 py-2 shadow-xs focus-within:ring-2 focus-within:ring-pink-500 focus-within:ring-offset-2 hover:border-gray-400"
                    (if intensive? "bg-purple-200 border border-purple-500" "bg-white border border-gray-300 "))}
             (when intensive?
               [:div {:class "shrink-0"}
                (icon/fist-punch {:class "h-10 w-10 text-purple-600"})])
             [:div {:class "min-w-0 flex-1"}
              [:a {:href (url/link-song song) :class "focus:outline-hidden"  :hx-boost "true"}
               [:span {:class "absolute inset-0" :aria-hidden "true"}]
               [:p {:class "text-sm font-medium text-gray-900"} title]
               [:p {:class "truncate text-sm text-gray-500"}
                (str (tr [:song/last-played])) ": " (ui/humanize-dt last-played-on)]]]]))
        selected-songs)])

(defn serialize-probeplan-selected-songs [songs]
  (map-indexed (fn [idx {:song/keys [song-id] :keys [position emphasis]}]
                 (list
                  [:input {:type :hidden :name (str idx "_songs_position")  :value (or position idx)}]
                  [:input {:type :hidden :name (str idx "_songs_emphasis")  :value (or emphasis probeplan.domain/probeplan-classic-default-emphasis)}]
                  [:input {:type :hidden :name (str idx "_songs_song-id") :value song-id}]))
               songs))

(ctmx/defcomponent ^:endpoint  gigs-detail-page-probeplan [{:keys [tr db] :as req}]
  gig-probeplan-choose-songs
  gig-probeplan-sort
  (let [;; archived? (domain/gig-archived? gig)
        songs (if (util/post? req)
                (service/update-probeplan! req (util/unwrap-params req))
                (q/probeplan-songs-for-gig db (gig-id-from-path req)))
        target (util/hash :comp/gig-probeplan-container)]
    (ui/panel {:title (tr [:gig/probeplan]) :id (util/id :comp/gig-probeplan-container)
               :buttons (when (seq songs)
                          (list
                           [:form {:hx-post (util/endpoint-path gig-probeplan-choose-songs)  :hx-target  target}
                            (serialize-probeplan-selected-songs songs)
                            (ui/button :label (tr [:action/edit]) :priority :white)]
                           (list
                            [:form {:hx-post (util/endpoint-path gig-probeplan-sort)  :hx-target target}
                             (serialize-probeplan-selected-songs songs)
                             (ui/button :label (tr [:action/reorder]) :priority :white)])))}
              [:div {:id "probeplan-container" :class ""}
               (if (empty? songs)
                 [:div {:class "flex flex-col items-center justify-center"}
                  [:div
                   (ui/button :label (tr [:gig/create-probeplan]) :priority :primary :icon icon/plus
                              :attr {:hx-get (util/endpoint-path gig-probeplan-choose-songs)
                                     :hx-target target})]]
                 (gigs-detail-page-probeplan-list-ro tr songs))])))

(ctmx/defcomponent ^:endpoint gig-delete [{:keys [db] :as req}]
  (when
   (util/delete? req)
    (service/delete-gig! req)
    (response/hx-redirect (url/link-gigs-home))))

(declare gig-detail-info-section-hxget)
(declare gig-detail-page)

(defn gig-create-form [{:keys [hash path tr member-select-vals post-endpoint]}
                       {:gig/keys [title date end-date status gig-type
                                   contact pay-deal call-time set-time end-time
                                   outfit description location setlist leader post-gig-plans
                                   more-details] :as gig}]

  [:form {:hx-post post-endpoint :class "space-y-8 divide-y divide-gray-200"}
   [:div {:class "mb-8"}
    [:div {:class "mx-auto mt-8 grid max-w-3xl grid-cols-1 gap-6 sm:px-6 lg:max-w-7xl lg:grid-flow-col-dense lg:grid-cols-3"}
     [:div {:class "space-y-6 lg:col-span-3 lg:col-start-1"}
      [:section
       [:div {:class "bg-white shadow-sm sm:rounded-lg "}
        [:div {:class "px-4 py-5 sm:px-6"}
         [:h2 {:class "text-lg font-medium leading-6 text-gray-900"}
          (tr [:gig/create-title])]]
        [:div {:class "border-t border-gray-200 px-4 py-5 sm:px-6"}
         [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-3"}
          (list
           (ui/dl-item (tr [:gig/title])
                       (ui/text  :name (path "title") :value title) "sm:col-span-2")

           (ui/dl-item  (tr [:gig/status])
                        (ui/select
                         :id (path "status")
                         :value (when status (name status))
                         :required? true
                         :options (map (fn [m] {:label (tr [m]) :value (name m)}) domain/create-statuses)))
           (ui/dl-item  (tr [:gig/gig-type])
                        (ui/select
                         :id (path "gig-type")

                         :value (when gig-type (name gig-type))
                         :required? true
                         :options (concat [{:label "-" :value ""}]
                                          (map (fn [m] {:label (tr [m]) :value (name m)}) domain/gig-types))))
           (ui/dl-item (tr [:gig/contact]) (ui/member-select :value (:member/member-id contact) :label "" :id (path "contact") :members member-select-vals :with-empty-opt? true))
           (ui/dl-item (tr [:gig/location]) (ui/text :value location :name (path "location")))
           (ui/dl-item (tr [:gig/date])
                       (ui/date :value date :name (path "date") :required? true :min (str (t/date))))
           (ui/dl-item (tr [:gig/end-date])
                       (ui/date :value end-date :name (path "end-date") :required? false :min (str (t/tomorrow))))
           (ui/dl-item "" "" "hidden sm:block")
           (ui/dl-item (tr [:gig/call-time]) (ui/input-time :value call-time :name (path "call-time") :required? true))
           (ui/dl-item (tr [:gig/set-time]) (ui/input-time :value set-time :name (path "set-time") :required? false))
           (ui/dl-item (tr [:gig/end-time]) (ui/input-time :value end-time :name (path "end-time") :required? false))
           (ui/dl-item (tr [:gig/outfit]) (ui/text :value (or  outfit (tr [:orange-and-green])) :name (path "outfit") :required? false))
           (ui/dl-item (tr [:gig/pay-deal]) (ui/text :value pay-deal :name (path "pay-deal") :required? false))
           (ui/dl-item (tr [:gig/leader]) (ui/text :label "" :value leader :name (path "leader") :required? false))
           (ui/dl-item (tr [:gig/post-gig-plans]) (ui/text :value post-gig-plans :name (path "post-gig-plans") :required? false) "sm:col-span-2")
           (ui/dl-item (tr [:gig/more-details]) (ui/textarea :value more-details :name (path "more-details") :required? false :placeholder (tr [:gig/more-details-placeholder])) "sm:col-span-3")
           (when false
             (ui/dl-item (tr [:gig/setlist]) (ui/textarea :value setlist :name (path "setlist") :required? false) "sm:col-span-3"))
           (ui/dl-item (tr [:gig/description]) (ui/textarea :value description :name (path "description") :required? false) "sm:col-span-3")
           (ui/dl-item nil
                       [:div
                        (ui/checkbox :label (tr [:gig/email-about-new?]) :id (path "notify?"))
                        (ui/checkbox :label (tr [:gig/create-a-forum-thread?]) :checked? true :id (path "thread?"))] "sm:col-span-3")
           ;;
           )]]
        [:div {:class "px-4 py-5 sm:px-6"}
         [:div {:class "flex justify-end space-x-4"}
          (ui/link-button :label (tr [:action/cancel])
                          :priority :white :centered? true
                          :attr {:href (url/link-gigs-home) :hx-boost "true"})
          (ui/button :label (tr [:action/save])
                     :priority :primary
                     :centered? true
                     :attr {:hx-target (hash ".")})]]]]]]

    (ui/panel {:title "Advanced"}
              [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-3"}
               (list
                (ui/dl-item "Forum Topic ID"
                            (ui/text  :name (path "topic-id") :required? false) "sm:col-span-2"))])]])

(declare gig-details-edit-post)
(declare gig-details-edit-form)

(defn gig-detail-info-section [{:keys [tr] :as req}  gig]
  (let [{:gig/keys [title date end-date status gig-type
                    contact pay-deal call-time set-time end-time
                    rehearsal-leader1 rehearsal-leader2
                    outfit location setlist leader post-gig-plans
                    more-details]} gig
        archived? (domain/gig-archived? gig)]
    [:div {:id (util/id :comp/gig-detail-page-info)}
     (ui/page-header :title (list  title " " (ui/gig-status-icon status))
                     :subtitle (tr [gig-type])
                     :buttons (list
                               (ui/button :label "Log Plays"
                                          :tag :a
                                          :priority :primary
                                          :centered? true
                                          :class "items-center justify-center"
                                          :attr {:href (url/link-gig gig "/log-play/")})
                               (when (or (auth/current-user-admin? req) (not archived?))
                                 (ui/button :label (tr [:action/edit])
                                            :priority :white
                                            :centered? true
                                            :class "items-center justify-center"
                                            :attr {:hx-get (util/endpoint-path gig-details-edit-form)
                                                   :hx-target (util/hash :comp/gig-detail-page)}))))
     [:div {:class "mx-auto mt-8 grid max-w-3xl grid-cols-1 gap-6 sm:px-6 lg:max-w-7xl lg:grid-flow-col-dense lg:grid-cols-3"}
      [:div {:class "space-y-6 lg:col-span-3 lg:col-start-1"}
       [:section
        [:div {:class "bg-white shadow-sm sm:rounded-lg"}
         [:div {:class "px-4 py-5 sm:px-6"}
          [:h2 {:class "text-lg font-medium leading-6 text-gray-900"}
           (tr [:gig/gig-info])]]
         [:div {:class "border-t border-gray-200 px-4 py-5 sm:px-6"}
          [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 grid-cols-2 sm:grid-cols-3"}
           (list
            (ui/dl-item (tr [:gig/date])
                        (if end-date
                          (ui/daterange date end-date)
                          (ui/datetime date)))
            (ui/dl-item (tr [:gig/location])
                        (markdown/render-one-line location) "break-words")
            (ui/dl-item (tr [:gig/contact]) (ui/member-nick contact))
            (ui/dl-item (tr [:gig/call-time]) (ui/time call-time))
            (ui/dl-item (tr [:gig/set-time]) (ui/time set-time))
            (ui/dl-item (tr [:gig/end-time]) (ui/time end-time))
            (when-not (str/blank? leader)
              (ui/dl-item (tr [:gig/leader]) leader))
            (when (domain/probe? gig)
              (list
               (ui/dl-item (tr [:gig/rehearsal-leader1]) (ui/member-nick rehearsal-leader1))
               (ui/dl-item (tr [:gig/rehearsal-leader2]) (ui/member-nick rehearsal-leader2))))
            (when-not (str/blank? pay-deal)
              (ui/dl-item (tr [:gig/pay-deal]) pay-deal))
            (when-not (str/blank? outfit)
              (ui/dl-item (tr [:gig/outfit]) outfit))
            (when-not (str/blank? more-details)
              (ui/dl-item (tr [:gig/more-details])
                          (markdown/render more-details)
                          "col-span-3 whitespace-pre-wrap"))
            (when-not (str/blank? setlist)
              (ui/dl-item (tr [:gig/setlist]) (interpose [:br] (str/split-lines setlist)) "col-span-3  whitespace-pre-wrap"))
            (when-not (str/blank? post-gig-plans)
              (ui/dl-item (tr [:gig/post-gig-plans]) post-gig-plans "col-span-3 whitespace-pre-wrap")))
           ;;
           ]]]]]]]))
(ctmx/defcomponent ^:endpoint  gig-details-edit-post [req]
  (when (util/post? req)
    (let [{:keys [gig db-after]} (service/update-gig! req)]
      (gig-detail-page (util/make-get-request req {:gig gig :db  db-after}) false))))

(ctmx/defcomponent ^:endpoint  gig-details-edit-form [{:keys [tr] :as req}]
  [:form {:id (util/id :comp/gig-details-edit-form) :hx-post (util/endpoint-path gig-details-edit-post)}
   (let [{:gig/keys [title date end-date status gig-type
                     contact pay-deal call-time set-time end-time
                     outfit description location setlist leader post-gig-plans
                     rehearsal-leader1 rehearsal-leader2
                     more-details] :as gig} (:gig req)
         archived? (domain/gig-archived? gig)
         member-select-vals (q/members-for-select-active (:db req))
         hx-target  (util/hash :comp/gig-details-edit-form)
         gig-detail-page-endpoint (util/endpoint-path gig-detail-page)
         gig-delete-endpoint (util/endpoint-path gig-delete)
         buttons  (list
                   (ui/button :label (tr [:action/save])
                              :priority :primary :centered? true
                              :hx-target hx-target)
                   (ui/button :label (tr [:action/cancel])
                              :priority :white :centered? true :tabindex -1
                              :hx-get gig-detail-page-endpoint
                              :hx-target hx-target :hx-vals {:edit? false})
                   (ui/button :label (tr [:action/delete]) :priority :white-destructive
                              :centered? true :tabindex -1
                              :hx-delete  gig-delete-endpoint :hx-target hx-target
                              :hx-confirm (tr [:action/confirm-delete-gig] [title])))]

     [:div {:class "mb-8"}
      ;; page-header-full with buttons hidden on smallest screens
      [:div {:class "px-4 py-4 sm:flex sm:items-center sm:justify-between sm:px-6 lg:px-8 bg-white"}
       [:div {:class "flex items-center space-x-5 w-full sm:w-1/2"}
        [:div {:class "w-full"}
         [:h1 {:class "text-2xl font-bold text-gray-900 w-full"}
          (ui/text :label (tr [:gig/title]) :name "title" :value title)]
         [:p {:class "text-sm font-medium text-gray-500 w-full"}
          (list
           (ui/select
            :label (tr [:gig/status])
            :id "status"
            :value (when status (name status))
            :size :small
            :required? true
            :options (map (fn [m] {:label (tr [m]) :value (name m)})
                          (if gig
                            domain/statuses
                            domain/create-statuses)))
           (ui/select
            :id "gig-type"
            :label (tr [:gig/gig-type])
            :value (when gig-type (name gig-type))
            :size :small
            :required? true
            :options
            (if (nil? gig)
              (concat [{:label "-" :value ""}]
                      (map (fn [m] {:label (tr [m]) :value (name m)}) domain/gig-types))
              (map (fn [m] {:label (tr [m]) :value (name m)}) domain/gig-types))))]]]
       [:div {:class
              "hidden sm:flex justify-stretch mt-6 flex flex-col-reverse space-y-4 space-y-reverse sm:flex-row-reverse sm:justify-end sm:space-y-0 sm:space-x-3 sm:space-x-reverse md:mt-0 md:flex-row-reverse md:space-x-reverse"}

        (when (service/can-edit-gig? req)
          buttons)]]

      [:div {:class "mx-auto mt-8 grid max-w-3xl grid-cols-1 gap-6 sm:px-6 lg:max-w-7xl lg:grid-flow-col-dense lg:grid-cols-3"}
       [:div {:class "space-y-6 lg:col-span-3 lg:col-start-1"}
        [:section
         [:div {:class "bg-white shadow-sm sm:rounded-lg"}
          [:div {:class "px-4 py-5 sm:px-6"}
           [:h2 {:class "text-lg font-medium leading-6 text-gray-900"}
            (tr [:gig/gig-info])]]
          [:div {:class "border-t border-gray-200 px-4 py-5 sm:px-6"}
           [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-3"}
            (list
             (ui/dl-item (tr [:gig/date])
                         (if (nil? gig)
                           (ui/date :value date :name "date" :required? true :min (str (t/date)))
                           (ui/date :value date :name "date" :required? true)))
             (ui/dl-item (tr [:gig/end-date])
                         (if (nil? gig)
                           (ui/date :value end-date :name "end-date" :required? false :min (str (t/tomorrow)))
                           (ui/date :value end-date :name "end-date" :required? false)))
             (ui/dl-item (tr [:gig/contact]) (ui/member-select :value (:member/member-id contact) :label "" :id  "contact" :members member-select-vals :with-empty-opt? true))
             (ui/dl-item (tr [:gig/call-time]) (ui/input-time :value call-time :name  "call-time" :required? true))
             (ui/dl-item (tr [:gig/set-time]) (ui/input-time :value set-time :name  "set-time" :required? false))
             (ui/dl-item (tr [:gig/end-time]) (ui/input-time :value end-time :name  "end-time" :required? false))
             (ui/dl-item (tr [:gig/location]) (ui/text :value location :name  "location"))
             (ui/dl-item (tr [:gig/outfit]) (ui/text :value (or outfit (tr [:orange-and-green])) :name  "outfit" :required? false))
             (ui/dl-item (tr [:gig/pay-deal]) (ui/text :value pay-deal :name  "pay-deal" :required? false))
             (when-not (str/blank? leader)
               (ui/dl-item (tr [:gig/leader]) (ui/text :label "" :value leader :name  "leader" :required? false)))
             (when (domain/probe? gig)
               (list
                (ui/dl-item (tr [:gig/rehearsal-leader1]) (ui/member-select :value (:member/member-id rehearsal-leader1) :label "" :id  "rehearsal-leader1" :members member-select-vals :with-empty-opt? true))
                (ui/dl-item (tr [:gig/rehearsal-leader2]) (ui/member-select :value (:member/member-id rehearsal-leader2) :label "" :id  "rehearsal-leader2" :members member-select-vals :with-empty-opt? true))))
             (ui/dl-item (tr [:gig/post-gig-plans]) (ui/text :value post-gig-plans :name  "post-gig-plans" :required? false) "sm:col-span-2")
             (ui/dl-item (tr [:gig/more-details]) (ui/textarea :value more-details :name "more-details" :required? false :placeholder (tr [:gig/more-details-placeholder]) :fit-height? true :markdown? true) "sm:col-span-3")
             (when false
               (ui/dl-item (tr [:gig/setlist]) (ui/textarea :value setlist :name  "setlist" :required? false) "sm:col-span-3"))
             (ui/dl-item (tr [:gig/description]) (ui/textarea :value description :name  "description" :required? false) "sm:col-span-3")
             (ui/dl-item nil (ui/checkbox :label (tr [:gig/email-about-change?]) :id  "notify?"))
             ;;
             )]]
          (when (service/can-edit-gig? req)
            [:div {:class "px-4 py-5 sm:px-6"}
             [:div {:class "justify-stretch mt-6 flex flex-col space-y-4 space-y-4 sm:hidden"}
              buttons]])]]]]
      (ui/panel {:title "Advanced"}
                [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-3"}
                 (list
                  (ui/dl-item "Takeover Forum Topic"
                              (ui/checkbox :label "" :id "takeover-topic?" :required? false))
                  (ui/dl-item "Forum Topic ID"
                              (ui/text  :name  "topic-id" :value (:forum.topic/topic-id gig) :required? false) "sm:col-span-2"))])])])

(ctmx/defcomponent ^:endpoint  gig-detail-info-section-hxget [req]
  (gig-detail-info-section req (:gig req)))

(defn discourse-comment-embed [{:keys [tr system] :as req} gig]
  (when-let [topic-id (:forum.topic/topic-id gig)
             ;; (:id  (discourse/topic-for-gig system gig))
             ]
    [:section {:aria-labelledby "notes-title"}
     [:div {:class "bg-white shadow-sm sm:overflow-hidden sm:rounded-lg mb-6"}
      [:div {:class "px-4 py-5 sm:px-6 "}
       [:div {:class "divide-y divide-gray-200"}
        (comment-header tr)
        (list
         [:div {:id "discourse-comments"
                :class "mb-6"}]
         [:script {:type "text/javascript"}
          (hiccup.util/raw-string
           (format
            "
  DiscourseEmbed = { discourseUrl: 'https://forum.streetnoise.at/',
                     topicId: '%s' };

  (function() {
    var d = document.createElement('script'); d.type = 'text/javascript'; d.async = true;
    d.src = DiscourseEmbed.discourseUrl + 'javascripts/embed.js';
    (document.getElementsByTagName('head')[0] || document.getElementsByTagName('body')[0]).appendChild(d);
  })();
" topic-id))])]]]]))

(ctmx/defcomponent ^:endpoint gig-detail-page-remind-all-button [{:keys [tr db] :as req} ^:boolean send-reminder?]
  (let [should-send? (and (util/post? req) send-reminder?)
        hash (util/hash :comp/gig-detail-page-remind-all-button)
        escape #(clojure.string/escape % {\' "\\'"})]
    (when should-send?
      (service/send-reminder-to-all! req (-> req :gig :gig/gig-id)))
    (ui/button :label (tr [:reminders/remind-all])
               :priority (if should-send? :success :secondary)
               :centered? true :size :xsmall
               :icon (when should-send? icon/checkmark)
               :spinner? true
               :attr {:id (util/id :comp/gig-detail-page-remind-all-button)
                      :hx-indicator hash
                      :hx-trigger "click"
                      :hx-post (util/endpoint-path gig-detail-page-remind-all-button)
                      :hx-target hash
                      :hx-vals {:send-reminder? true}
                      :_ (ui/confirm-modal-script
                          (tr [:reminders/confirm-remind-all-title])
                          (tr [:reminders/confirm-remind-all])
                          (tr [:reminders/confirm])
                          (tr [:action/cancel]))})))

(ctmx/defcomponent ^:endpoint gig-detail-page [{:keys [tr db] :as req} ^:boolean show-committed?]
  gig-details-edit-form gig-delete gig-details-edit-post gig-detail-info-section-hxget

  (let [{:gig/keys [gig-id gig-type] :as gig} (:gig req)
        endpoint-self (util/endpoint-path gig-detail-page)
        archived? (domain/gig-archived? gig)
        attendances-for-gig (if archived?
                              (q/attendances-for-gig db gig-id)
                              (q/attendance-for-gig-with-all-active-members db gig-id))
        attendances-by-section (q/attendance-plans-by-section-for-gig db  attendances-for-gig (if archived? nil
                                                                                                  (when show-committed?
                                                                                                    :committed-only?)))
        attendance-summary (->> attendances-for-gig
                                (group-by :attendance/plan)
                                (m/map-vals count))
        send-reminder?
        req]

    [:div {:id (util/id :comp/gig-detail-page)}
     (render/sortable-scripts req)
     (gig-detail-info-section req (:gig req))
     (cond
       archived? nil
       (domain/probe? gig)
       (gigs-detail-page-probeplan req)
       (domain/gig? gig)
       (gigs-detail-page-setlist req)
       :else nil)
     [:div {:class "mx-auto mt-8 grid max-w-3xl grid-cols-1 gap-6 sm:px-6 lg:max-w-7xl lg:grid-flow-col-dense lg:grid-cols-3"}
      [:div {:class "space-y-6 lg:col-span-3 lg:col-start-1"}
;;;; Attendance Section
       [:section
        [:div {:class "bg-white shadow-sm sm:rounded-lg"}
         [:div {:class "px-4 py-5 sm:px-6 flex flex-row flex-items-center justify-between"}
          [:h2 {:class "text-lg font-medium leading-6 text-gray-900"}
           (tr [:gig/attendance])]
          (when-not archived?
            [:div {:class "space-x-2 flex"}
             (gig-detail-page-remind-all-button req false)
             (ui/button :label (if show-committed?
                                 (tr [:gig/show-all])
                                 (tr [:gig/show-committed]))
                        :size :xsmall
                        :priority :primary
                        :centered? true
                        :attr {:hx-get endpoint-self :hx-target (util/hash :comp/gig-detail-page) :hx-vals {:show-committed? (not show-committed?)}})])]

         [:div {:class "border-t border-gray-200 mb-4"}
          [:div {:class "flex gap-4 md:gap-10 justify-center pt-4 lg:pt-6"}
           (map (fn [plan]
                  (let [{:keys [icon icon-class]} (get (plan-icons) plan)
                        count (get attendance-summary plan 0)]
                    ;;  if the plan has count of 0 (0 people attending), and its in the optional list
                    ;;  don't show the summary for that item
                    (when-not (and (= 0 count) (contains? domain/plan-priority-optional-display plan))
                      [:div {:class "flex gap-1"}
                       (icon {:class (str  icon-class " mr-0")})
                       [:span {:class ""} count]]))) domain/plan-priority-sorting)]

          (if archived?
            (rt/map-indexed gig-attendance-archived req attendances-by-section)
            (rt/map-indexed gig-attendance req attendances-by-section))]]]
;;;; Comments Section
       (discourse-comment-embed req gig)]]]))

;;;; Create Gig
(ctmx/defcomponent ^:endpoint gig-create-page [{:keys [tr db] :as req}]
  (if (util/post? req)
    (let [new-gig (:gig (service/create-gig! req))]
      (response/hx-redirect (url/link-gig new-gig)))
    (gig-create-form {:path path :hash hash :tr tr
                      :post-endpoint (util/endpoint-path gig-create-page)
                      :member-select-vals (q/members-for-select-active db)} nil)))

;;;; List Gigs
(ctmx/defcomponent ^:endpoint gigs-list-page [{:keys [tr db] :as req}]
  (let [future-gigs (q/gigs-future db)
        offset 0
        limit 20
        past-gigs (service/gigs-past-page db offset limit)]
    [:div
     (ui/page-header :title (tr [:gigs/title])
                     :buttons  (list
                                (ui/button :tag :a :label (tr [:action/create])
                                           :priority :primary
                                           :centered? true
                                           :attr {:hx-boost "true" :href (url/link-gig-create)} :icon icon/plus)))

     [:div {:class "mt-6 px-4 sm:px-6 md:px-8 md:flex md:flex-row md:space-x-4"}
      [:div {:class "max-w-lg"}
       (ui/divider-left (tr [:gigs/upcoming]))
       [:div {:class "overflow-hidden bg-white shadow-sm sm:rounded-md mb-8"}
        (if (empty? future-gigs)
          (tr [:gigs/no-future])
          [:ul {:role "list" :class "divide-y divide-gray-200"}
           (map (fn [gig]
                  [:li
                   (gig-row gig)]) future-gigs)])]]
      [:div {:class "max-w-lg mb-8"}
       (ui/divider-left (tr [:gigs/past]))
       [:div {:class "overflow-hidden bg-white shadow-sm sm:rounded-md"}
        (if (empty? past-gigs)
          (tr [:gigs/no-past])
          [:div
           [:div {:class "mt-6 flow-root"}
            [:ul {:role "list", :class "divide-y divide-gray-200"}
             (map (fn [gig]
                    [:li
                     (gig-row gig)]) past-gigs)]

            [:div {:class "px-6 mb-8 mt-8 justify-stretch flex flex-col space-y-4 space-y-4"}
             (ui/link-button :centered? true :priority :white :label (tr [:gigs/view-archive])
                             :hx-boost true
                             :attr {:href (url/link-gig-archive)})]]])]]]]))

;;;; List Gig Archive
(ctmx/defcomponent ^:endpoint gigs-archive-page [{:keys [tr db] :as req}]
  (let [year-groups (->> (service/gigs-past-page db 0 ##Inf)
                         (group-by #(-> % :gig/date (t/year)))
                         (map (fn [[year gigs]]
                                {:year year
                                 :gigs gigs}))
                         (sort-by :year t/>))]

    [:div
     (ui/page-header :title (tr [:gigs/title])
                     :buttons  (list
                                (ui/button :tag :a :label (tr [:action/create])
                                           :priority :primary
                                           :centered? true
                                           :attr {:hx-boost "true" :href (url/link-gig-create)} :icon icon/plus)))

     [:div {:class "mt-6 px-4 sm:px-6 md:px-8 md:space-x-4 max-w-md"}
      [:ul {:class "grid grid-cols-4 sm:grid-cols-5 gap-4"}
       (map (fn [{:keys [year]}]
              [:li
               (ui/link-button :attr {:href (str "#" year)} :label year :priority :white)]) year-groups)]]
     [:div {:class "mt-6 px-4 sm:px-6 md:px-8 xl:flex xl:flex-row md:space-x-4"}
      (map-indexed
       (fn [idx {:keys  [year gigs]}]
         [:div {:class "max-w-lg mb-8" :id (str year)}
          (ui/divider-left year)
          [:div {:class "overflow-hidden bg-white shadow-sm sm:rounded-md"}
           [:div
            [:div {:class "mt-6 flow-root"}
             [:ul {:role "list", :class "divide-y divide-gray-200"}
              (map (fn [gig]
                     [:li
                      (gig-row gig)]) gigs)]]]]])
       year-groups)]]))

;;;; Handle Answer Links from Emails
(defn gig-answer-link [{:keys [tr] :as req}]
  (let [logged-in? (-> req :session :session/member)
        {:keys [gig]} (service/update-attendance-from-link! req)]
    (if logged-in?
      (response/redirect (url/link-gig gig))
      (layout/centered-content req
                               [:div
                                [:span (tr [:gig/answer-link-submitted])]
                                [:a {:href (url/absolute-gig-answer-link-undo (-> req :system :env))} " "]]))))
