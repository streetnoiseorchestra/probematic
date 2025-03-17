(ns app.dashboard.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.gigs.domain :as domain]
   [app.gigs.service :as gig.service]
   [app.gigs.views :as gig.view]
   [app.icons :as icon]
   [app.insurance.controller :as insurance.controller]
   [app.insurance.views :as insurance.view]
   [app.ledger.views :as ledger]
   [app.poll.controller :as poll.controller]
   [app.queries :as q]
   [app.ui :as ui]
   [app.urls :as url]
   [app.util :as util]
   [clojure.string :as str]
   [ctmx.core :as ctmx]
   [ctmx.rt :as rt]))

(ctmx/defcomponent ^:endpoint gig-attendance-person-motivation [req gig-id member-id motivation]
  (gig.view/motivation-endpoint req
                                {:path path :id id :hash hash :value value}
                                (util/comp-namer #'gig-attendance-person-motivation)
                                (util/ensure-uuid! gig-id)
                                (util/ensure-uuid! member-id)
                                motivation))
(ctmx/defcomponent ^:endpoint gig-attendance-person-plan [req gig-id member-id plan]
  (gig.view/plan-endpoint req
                          {:path path :id id :hash hash :value value}
                          (util/comp-namer #'gig-attendance-person-plan)
                          (util/ensure-uuid! gig-id)
                          (util/ensure-uuid! member-id)
                          plan))
(ctmx/defcomponent ^:endpoint gig-attendance-person-comment [req gig-id member-id comment ^:boolean edit?]
  (gig.view/comment-endpoint req
                             {:path path :id id :hash hash :value value}
                             (util/comp-namer #'gig-attendance-person-comment)
                             (util/ensure-uuid! gig-id)
                             (util/ensure-uuid! member-id)
                             comment edit?))

(ctmx/defcomponent ^:endpoint gig-attendance-snooze [req gig-id member-id]
  (gig.view/snooze-endpoint req
                            {:path path :id id :hash hash :value value}
                            (util/comp-namer #'gig-attendance-snooze)
                            (util/ensure-uuid! gig-id)
                            (util/ensure-uuid! member-id)))

(defn gig-attendance-endpoint
  [req id idx gig]
  (let [attendance (:attendance gig)
        plan (:attendance/plan attendance)
        gig-id (:gig/gig-id gig)
        {:member/keys [member-id]} (:attendance/member attendance)
        {:gig/keys [date end-date status title]} gig]

    [:div {:id id :class (ui/cs "flex flex-col md:grid md:grid-cols-4 gap-x-0 md:gap-y-8 px-4 py-2 sm:px-6 last:rounded-b-md border-b border-gray-200"
                                (when (= 0 idx) "sm:rounded-t-md")
                                (if (= 0 (mod idx 2))
                                  "bg-white"
                                  "bg-white"))}
     [:div {:class "md:order-none md:col-span-1 text-sm whitespace-nowrap font-medium"}
      [:div {:class "flex gap-x-2 md:grid md:grid-flow-col md:auto-cols-min"}
       [:div {:class "hidden md:block"} (ui/gig-status-icon status)]
       [:div {:class "md:order-none md:min-w-[5rem]"}
        (if end-date
          (ui/daterange date end-date)
          (ui/datetime date))]
       [:div {:class "md:order-none hidden md:block"}
        (when-not end-date
          (ui/gig-time gig false))]
       [:div {:class "md:order-none block md:hidden"}
        (when-not end-date
          (ui/gig-time gig))]]]
     [:div {:class "md:order-none md:font-normal"}
      [:a {:href (url/link-gig gig) :class "link-blue"} title]]
     [:div {:class "order-last md:order-none md:col-span-2 flex gap-x-2 flex-wrap"}
      [:div {:class "block md:hidden"} (ui/gig-status-icon status)]
      [:div (gig-attendance-person-plan req gig-id member-id (:attendance/plan attendance))]
      [:div (gig-attendance-person-motivation req gig-id member-id (:attendance/motivation attendance))]

      (when (domain/no-response? plan)
        [:div (gig-attendance-snooze req gig-id member-id)])

      [:div (gig-attendance-person-comment req gig-id member-id (:attendance/comment attendance) false)]]]))

(ctmx/defcomponent ^:endpoint gig-attendance-upcoming [req idx gig]
  gig-attendance-person-comment
  gig-attendance-person-motivation
  gig-attendance-person-plan
  gig-attendance-snooze
  (gig-attendance-endpoint req id idx gig))

(ctmx/defcomponent ^:endpoint gig-attendance-unanswered [req idx gig]
  gig-attendance-person-comment
  gig-attendance-person-motivation
  gig-attendance-person-plan
  gig-attendance-snooze
  (gig-attendance-endpoint req id idx gig))

(ctmx/defcomponent ^:endpoint open-polls [req idx {:poll/keys [title voter-count votes-count] :as poll}]
  (let [style-icon "mr-1.5 h-5 w-5 shrink-0 text-gray-400"]
    [:a {:href (url/link-poll poll)}
     [:div {:id id :class (ui/cs "flex flex-col md:grid md:grid-cols-4 gap-x-0 md:gap-y-8 px-4 py-2 sm:px-6 last:rounded-b-md border-b border-gray-200"
                                 (when (= 0 idx) "sm:rounded-t-md")
                                 (if (= 0 (mod idx 2))
                                   "bg-white"
                                   "bg-white"))}
      [:div {:class "md:order-none md:col-span-1 text-sm whitespace-nowrap font-medium"}
       [:div {:class "flex gap-x-2 md:grid md:grid-flow-col md:auto-cols-min"}
        [:span {:class "link-blue"} title]]]
      [:div {:class "md:order-none md:font-normal flex"}
       [:div {:class "flex"}
        (icon/users-solid {:class style-icon}) voter-count]
       [:div {:class "flex ml-4"}
        (icon/checkmark {:class style-icon}) votes-count]]]]))

(ctmx/defcomponent ^:endpoint todo-policies [{:keys [tr]} idx {:insurance.policy/keys [name] :keys [total-needs-review total-changed total-new total-removed] :as policy}]
  (let [p-class "flex items-center text-sm text-gray-500 mr-6 tooltip"]
    [:a {:href (url/link-policy policy)}
     [:div {:id id :class (ui/cs "flex flex-col md:grid md:grid-cols-4 gap-x-0 md:gap-y-8 px-4 py-2 sm:px-6 last:rounded-b-md border-b border-gray-200"
                                 (when (= 0 idx) "sm:rounded-t-md")
                                 (if (= 0 (mod idx 2))
                                   "bg-white"
                                   "bg-white"))}
      [:div {:class "md:order-none md:col-span-2 text-sm font-medium"}
       [:span {:class "link-blue"} name]]
      [:div {:class "md:order-none md:font-normal flex"}
       (when (> total-needs-review 0)
         [:p {:class p-class :data-tooltip (tr [:insurance/total-needs-review-tooltip])} (insurance.view/coverage-status-icon-span tr :instrument.coverage.status/needs-review) total-needs-review])
       (when (> total-changed 0)
         [:p {:class p-class :data-tooltip (tr [:insurance/total-total-changed-tooltip])} (insurance.view/coverage-change-icon-span tr :instrument.coverage.change/changed) total-changed])
       (when (> total-removed 0)
         [:p {:class p-class :data-tooltip (tr [:insurance/total-total-removed-tooltip])} (insurance.view/coverage-change-icon-span tr :instrument.coverage.change/removed) total-removed])
       (when (> total-new 0)
         [:p {:class p-class :data-tooltip (tr [:insurance/total-total-new-tooltip])} (insurance.view/coverage-change-icon-span tr :instrument.coverage.change/new) total-new])]]]))

(ctmx/defcomponent
  ^:endpoint
  calendar-page
  [{:keys [db tr] :as req}]
  [:div
   (ui/page-header :title "Calendar"
                   :buttons  (list
                              (ui/button :tag :a :label (tr [:action/create-gig])
                                         :priority :primary
                                         :centered? true
                                         :attr {:hx-boost "true" :href (url/link-gig-create)} :icon icon/plus)))
   [:div {:class "flex flex-col w-full h-full"}
    [:iframe {:class "grow" :src "https://data.streetnoise.at/apps/calendar/embed/yRFYYPnQkasfa8nk/listMonth/now"
              :width "100%" :height "1000"}]]])

(defn calendar-subscribe-button [env tr]
  (let [https (config/public-calendar-url env)
        webcal  (str/replace (config/public-calendar-url env) "https" "webcal")
        encoded-webcal (url/url-encode webcal)
        google (format "https://calendar.google.com/calendar/render?cid=%s" encoded-webcal)
        outlook-365 (str "https://outlook.office.com/owa?path=%2Fcalendar%2Faction%2Fcompose&rru=addsubscription"
                         "&url=" encoded-webcal "&name=" "SNO-Kalender")
        outlook-live (str
                      "https://outlook.live.com/owa?path=%2Fcalendar%2Faction%2Fcompose&rru=addsubscription"
                      "&url=" encoded-webcal "&name=" "SNO-Kalender")]
    (ui/action-menu
     :label (tr [:action/add-to-calendar])
     :button-icon icon/calendar-days
     :id "subscribe-calendar"
     :sections [{:label (tr [:choose-calendar-app])
                 :items [{:label [:span {:class "copy-link"} (tr [:action/copy-link])] :tag :button
                          :icon (icon/copy {:class (ui/cs class "mr-1.5 h-5 w-5 shrink-0 text-pink-600")})
                          :attr {:data-href https
                                 :_ (format "
on click writeText(@data-href) on navigator.clipboard
put '%s!' into .copy-link in me
add .animate-pulse to me
wait 2s
remove .animate-pulse from me
put '%s' into .copy-link in me"
                                            (tr [:action/copy-link-confirm])
                                            (tr [:action/copy-link]))}}

                         {:label "Microsoft 365" :tag :a
                          :icon (icon/microsoft-365 {:class (ui/cs class "mr-1.5 h-5 w-5 shrink-0")})
                          :attr {:href outlook-365}}
                         {:label "Outlook Live" :tag :a
                          :icon (icon/outlook {:class (ui/cs class "mr-1.5 h-5 w-5 shrink-0")})
                          :attr {:href outlook-live}}
                         {:label "Google Calendar" :tag :a
                          :icon (icon/google-calendar {:class (ui/cs class "mr-1.5 h-5 w-5 shrink-0")})
                          :attr {:href google}}
                         {:label "Apple Calendar" :tag :a
                          :icon (icon/apple-calendar {:class (ui/cs class "mr-1.5 h-5 w-5 shrink-0 border border-gray-500 rounded-sm")})
                          :attr {:href webcal}}]}])))

(declare dashboard-page)

(ctmx/defcomponent ^:endpoint dashboard-page [{:keys [db tr system] :as req}]
  insurance.view/survey-dismiss-response
  (let [member (auth/get-current-member req)
        _ (assert member)
        gigs-planned (gig.service/gigs-planned-for db member)
        polls-open (poll.controller/open-polls db)
        need-answer-gigs (gig.service/gigs-needing-plan db member)
        ledger (q/retrieve-ledger db (:member/member-id member))
        survey-response (q/open-survey-for-member db member)]
    [:div {:id (util/id :comp/dashboard)}
     (ui/page-header :title (tr [(keyword "dashboard" (name (util/time-window (util/local-time-austria!))))] [(ui/member-nick member)])
                     :buttons  (list
                                (calendar-subscribe-button (:env system) tr)
                                (ui/button :tag :a :label (tr [:action/create-gig])
                                           :priority :primary
                                           :centered? true
                                           :attr {:hx-boost "true" :href (url/link-gig-create)} :icon icon/plus)))

     (when (and ledger (> (:ledger/balance ledger) 0))
       [:div {:class "mt-6 sm:px-6 lg:px-8" :hx-boost "true"}
        (ui/divider-left (tr [:dashboard/you-owe]))
        [:div {:class "bg-white p-4 rounded-md shadow-md"}
         (ledger/outstanding-balance-widget req ledger)]])
     (when (and  survey-response (nil? (:insurance.survey.response/completed-at survey-response)))
       [:div {:class "mt-6 sm:px-6 lg:px-8" :hx-boost "true"}
        (ui/divider-left [:span   "Insurance Time!"])
        [:div {:class "bg-white p-4 rounded-md shadow-md"}
         (insurance.view/dashboard-survey-widget req survey-response)]])
     (when (seq polls-open)
       [:div {:class "mt-6 sm:px-6 lg:px-8" :hx-boost "true"}
        (ui/divider-left (tr [:dashboard/polls-open]))
        (rt/map-indexed open-polls req polls-open)])
     (when (q/insurance-team-member? db member)
       (let [policies (insurance.controller/policies-with-todos db)]
         (when (seq policies)
           [:div {:class "mt-6 sm:px-6 lg:px-8" :hx-boost "true"}
            (ui/divider-left (tr [:dashboard/insurance-todo]))
            (rt/map-indexed todo-policies req policies)])))
     (when (seq need-answer-gigs)
       [:div {:class "mt-6 sm:px-6 lg:px-8" :hx-boost "true"}
        (ui/divider-left (tr [:dashboard/unanswered]))
        (rt/map-indexed gig-attendance-unanswered req need-answer-gigs)])

     (when (seq gigs-planned)
       [:div {:class "mt-6 sm:px-6 lg:px-8" :hx-boost "true"}
        (ui/divider-left (tr [:dashboard/upcoming]))
        (rt/map-indexed gig-attendance-upcoming req gigs-planned)])
     (when-not (or (seq gigs-planned) (seq need-answer-gigs))
       [:div {:class "mt-6 sm:px-6 lg:px-8"}
        "You have no upcoming gigs or probes. Why don't you create one?"])]))
