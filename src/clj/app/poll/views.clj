(ns app.poll.views
  (:require
   [app.render :as render]
   [app.auth :as auth]
   [app.icons :as icon]
   [app.layout :as layout]
   [app.markdown :as markdown]
   [app.poll.controller :as controller]
   [app.poll.domain :as domain]
   [app.response :as response]
   [app.ui :as ui]
   [app.urls :as url]
   [app.util :as util]
   [app.util.http :as util.http]
   [ctmx.core :as ctmx]
   [hiccup.util :as hiccup.util]
   [jsonista.core :as j]
   [tick.core :as t]))

(ctmx/defcomponent ^:endpoint poll-publish [req]
  (when (util/post? req)
    (response/redirect req (url/link-poll (:poll
                                           (controller/publish-poll! req (util.http/path-param-uuid! req :poll-id)))))))

(ctmx/defcomponent ^:endpoint poll-close [req]
  (when (util/post? req)
    (response/redirect req (url/link-poll (:poll
                                           (controller/close-poll! req (util.http/path-param-uuid! req :poll-id)))))))

(ctmx/defcomponent ^:endpoint poll-delete [req]
  (when (util/post? req)
    (controller/delete-poll! req (util.http/path-param-uuid! req :poll-id))
    (response/redirect req (url/link-polls-home))))

(defn poll-option
  ([idx]
   (poll-option idx  nil))
  ([idx value]
   (let [input-id (str "options_" idx "_value")]
     [:li {:class "grid grid-cols-4 gap-2"}
      [:div {:class "col-span-3 "}
       (ui/text :id input-id :name input-id :required? true :value value :minlength 1)]
      [:div {:class "col-span-1 flex h-8 min-h-full space-x-2"}
       (when (not= 0 idx)
         (ui/button :priority :white-destructive :icon icon/minus :size :small :hx-delete ""
                    :attr {:type :button :_ "on click remove the closest <li/>"}))]])))

(defn poll-new-option [tr]
  [:div {:class "grid grid-cols-4 gap-2" :id "poll-new-opt-row"}
   ;; [:div {:class "col-span-3 "}]
   [:div {:class "col-span-2 flex h-8 min-h-full space-x-2"}
    (ui/button :priority :secondary :icon icon/plus  :size :small
               :label (tr [:poll/add-option])
               :attr {:_ "
on click
set count to the length of <#poll-options input/>
set optName to 'options_' + `${count}_value`
get #poll-opt-template's innerHTML
put it at the end of #poll-options
get the last <input/> in #poll-options
set its @id to optName
set its @name to optName
"
                      :type :button})]])

(defn empty-opts []
  (for [i (range 1)]
    (poll-option i)))

(defn poll-form [{:keys [tr] :as req} comp-name  {:poll/keys [title description autoremind? closes-at min-choice max-choice poll-type poll-status options author] :as existing-poll}]
  (let [min-choice (or min-choice 1)
        max-choice (or max-choice 2)
        single? (if existing-poll (= :poll.type/single poll-type)
                    true)
        semi-read-only? (and existing-poll (= poll-status :poll.status/open))
        autoremind? (if (nil? autoremind?) true
                        autoremind?)
        closes-at (or closes-at nil)
        author-id (or (:member/member-id author) (:member/member-id (auth/get-current-member req)))
        poll-status (or poll-status :poll.status/draft)
        option-elements (or (when options
                              (map (fn [{:poll.option/keys [value position]}]
                                     (poll-option position value)) options))
                            (empty-opts))]
    (tap> {:single? single?
           :pt poll-type})
    (ui/panel {:title (tr [:poll/poll])
               :buttons (list
                         (when existing-poll
                           (ui/button :priority :white-destructive :label "Delete" :hx-post (util/endpoint-path poll-delete)
                                      :attr {:_ (ui/confirm-modal-script
                                                 (tr [:action/confirm-generic])
                                                 (tr [:action/confirm-delete-poll] [title])
                                                 (tr [:action/confirm-delete])
                                                 (tr [:action/cancel]))}))
                         (when (and existing-poll (= poll-status :poll.status/open))
                           (ui/button :label (tr [:poll/close-early]) :hx-post (util/endpoint-path poll-close)
                                      :attr {:_ (ui/confirm-modal-script
                                                 (tr [:action/confirm-generic])
                                                 (tr [:action/confirm-close-poll] [title])
                                                 (tr [:action/confirm-close])
                                                 (tr [:action/cancel]))})))}
              [:div

               [:div {:class "hidden" :id "poll-opt-template"} (poll-option 999  nil)]

               [:form {:hx-post (comp-name) :class "space-y-8"}
                [:input {:type :hidden :name "author-id" :value (str author-id)}]
                [:input {:type :hidden :name "poll-status" :value (name poll-status)}]
                [:div {:class "space-y-8  sm:space-y-5"}
                 [:div {:class "space-y-6 sm:space-y-5"}
                  [:div {:class "space-y-6 sm:space-y-5"}
                   (list
                    (ui/text-left :label (tr [:poll/title]) :id  "poll-title" :value title)
                    (ui/textarea-left :required? true :label (tr [:poll/description]) :hint (tr [:poll/description-hint]) :id "poll-description" :name "poll-description" :value description)
                    (when semi-read-only?
                      [:input {:type :hidden :name "poll-type" :value (name poll-type)}])
                    (ui/checkbox-group-left :label (tr [:poll/poll-type]) :id "label-poll-type"
                                             ;; :label-hint (tr [:poll/poll-type-hint])
                                            :required? true
                                            :checkboxes (list
                                                         (ui/radio-left :name "poll-type" :id "single" :value "single" :label (tr [:poll.type/single]) :checked? (= poll-type :poll.type/single)
                                                                        :required? true
                                                                        :disabled? semi-read-only?
                                                                        :hint (tr [:poll.type/single-hint])
                                                                        :attr {:_ "on click add .hidden to .min-choice then add .hidden to .max-choice"})
                                                         (ui/radio-left :name "poll-type" :id "multiple" :value "multiple" :label (tr [:poll.type/multiple]) :checked? (= poll-type :poll.type/multiple)
                                                                        :required? true
                                                                        :disabled? semi-read-only?
                                                                        :hint (tr [:poll.type/multiple-hint])
                                                                        :attr {:_ "on click remove .hidden from .min-choice then remove .hidden from .max-choice"})))
                    [:div {:class (ui/cs "min-choice" (when  single? "hidden"))}
                     (ui/number-input-left :id "min-choice" :label (tr [:poll/min-choice]) :hint (tr [:poll/min-choice-hint]) :min 1 :value min-choice :required? false)]
                    [:div {:class (ui/cs "max-choice" (when single? "hidden"))}
                     (ui/number-input-left :id "max-choice" :label (tr [:poll/max-choice]) :hint (tr [:poll/max-choice-hint]) :min 1 :value max-choice :required? false)]
                    (ui/datetime-left :label (tr [:poll/closes-at]) :hint (tr [:poll/closes-at-hint]) :id "closes-at" :value closes-at :required? true)
                    [:input {:type :hidden :name "autoremind?" :value nil}]
                    #_(ui/toggle-checkbox-left :id "autoremind?" :name "autoremind?" :label (tr [:poll/autoremind?]) :checked? autoremind?
                                               :hint (tr [:poll/autoremind-hint])))

                   (ui/form-left-section :label (tr [:poll/choices]))
                   (if semi-read-only?
                     (list
                      [:ul {:class "space-y-2 list-disc mb-4" :id "poll-options"}
                       (map (fn [{:poll.option/keys [value]}]
                              [:li {:class "ml-6"} value]) options)]
                      [:div (tr [:poll/options-read-only-hint])])
                     (list
                      [:ul {:class "space-y-2" :id "poll-options"}
                       option-elements]
                      [:div {:class "space-y-2"}
                       (poll-new-option tr)]))]]]

                [:div {:class "pt-5"}
                 [:div {:class "flex justify-end"}
                  [:a {:href (if existing-poll (url/link-poll existing-poll) (url/link-polls-home))
                       :class "rounded-md border border-gray-300 bg-white py-2 px-4 text-sm font-medium text-gray-700 shadow-xs hover:bg-gray-50 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
                   (tr [:action/cancel])]
                  [:button {:type "submit" :class "ml-3 inline-flex justify-center rounded-md border border-transparent bg-sno-orange-600 py-2 px-4 text-sm font-medium text-white shadow-xs hover:bg-sno-orange-700 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
                   (if existing-poll
                     (tr [:action/save])
                     (tr [:action/create]))]]]]])))

(ctmx/defcomponent ^:endpoint poll-edit-page [{:keys [db] :as req}]
  poll-delete
  poll-close
  (let [existing-poll (controller/retrieve-poll db (util.http/path-param-uuid! req :poll-id))
        closed? (= :poll.status/closed (:poll/poll-status existing-poll))
        post? (util/post? req)
        result (when post? (controller/update-poll! req))
        error (:error result)
        comp-name (util/comp-namer #'poll-edit-page)]
    (cond
      closed? (response/redirect req (url/link-polls-home))
      (and post? (not error)) (response/redirect req (url/link-poll (:poll result)))

      :else (layout/maybe-app-shell req
                                    [:div
                                     (poll-form req comp-name existing-poll)]))))

(ctmx/defcomponent ^:endpoint polls-create-page [{:keys [tr] :as req}]
  (let [post? (util/post? req)
        result (when post? (controller/create-poll! req))
        error (:error result)
        comp-name (util/comp-namer #'polls-create-page)]
    (if (and post? (not error))
      (response/redirect req (url/link-poll (:poll result)))
      [:div
       (ui/page-header :title (tr [:polls/create-title]))
       (poll-form req comp-name  nil)])))

(defn stat-block [title value]
  [:div {:class "flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2 bg-white px-4 py-4 sm:px-6 lg:py-10 xl:px-8"}
   [:dt {:class "text-sm font-medium leading-6 text-gray-500"} title]
   [:dd {:class "text-xs font-medium text-gray-700"} ""]
   [:dd {:class "w-full flex-none text-lg lg:text-3xl font-medium leading-4 lg:leading-10 tracking-tight text-gray-900"} value]])

(defn poll-results [{:keys [tr] :as req} {:poll/keys [closes-at poll-status votes] :as poll}]
  (let [total-voters (count (distinct (map #(get-in % [:poll.vote/author :member/member-id]) votes)))
        graph-data (->> votes
                        (map :poll.vote/poll-option)
                        (map #(select-keys % [:poll.option/poll-option-id :poll.option/value]))
                        (frequencies)
                        (map (fn [[{:poll.option/keys [value]} freq]]
                               #_[value (/ (* 100 freq) total-voters)]
                               [value freq]))
                        (sort-by second))
        y-labels (map first graph-data)
        graph-values (map second graph-data)
        height-per-option 35
        height (+ height-per-option (* height-per-option (count y-labels)))
        end (domain/closes-at-instant closes-at)
        days-left (t/days
                   (t/duration
                    {:tick/beginning (t/now)
                     :tick/end end}))
        total-votes (count votes)]
    (tap> {:poll poll :graph-data graph-data})
    [:div {:class "mt-4"}
     (render/chart-poll-scripts req)
     [:h2 {:class "text-lg"} (tr [:poll/results])]
     [:script {:type "application/json" :id "poll-labels"}
      (hiccup.util/raw-string (j/write-value-as-string y-labels))]
     [:script {:type "application/json" :id "poll-values"}
      (hiccup.util/raw-string (j/write-value-as-string {:totalVoters total-voters
                                                        :values graph-values}))]
     [:div  {:class ""}
      [:dl {:class "mx-auto grid grid-cols-3 gap-px bg-gray-900/5 lg:grid-cols-5"}
       (stat-block (tr [:poll/total-voters]) total-voters)
       (stat-block (tr [:poll/total-votes]) total-votes)
       (if (= poll-status :poll.status/closed)
         (stat-block (tr [:poll/closed-on]) (ui/format-dt closes-at))
         (stat-block (tr [:poll/days-left]) days-left))
       [:div {:class "flex-wrap items-baseline justify-between gap-x-4 gap-y-2 bg-white px-4 py-10 sm:px-6 xl:px-8 hidden lg:flex lg:col-span-2"}]]]
     [:div  {:class "poll-chart-container w-full max-w-5xl" :style (str "height: " height "px;")}
      [:canvas {:class "poll-chart" :data-labels "#poll-labels" :data-datasets "#poll-values"}]]
     (when (= poll-status :poll.status/open)
       [:div {:class "mt-2"}
        (ui/link-button :label (tr [:action/change-vote]) :icon icon/arrow-small-left :href (url/link-poll poll "/?change-vote=true"))])]))

(ctmx/defcomponent ^:endpoint poll-vote [{:keys [tr db] :as req} poll]
  (let [poll (or poll (controller/retrieve-poll db (util.http/path-param-uuid! req :poll-id)))
        {:poll/keys [poll-status poll-type poll-id options min-choice max-choice]} poll
        votes (controller/retrieve-vote-for db poll (auth/get-current-member req))
        selected-opts (controller/votes->option-id-set votes)
        multiple-choice? (= poll-type :poll.type/multiple)
        draft? (= poll-status :poll.status/draft)
        vote-result (when (util/post? req) (controller/cast-vote! req))]
    (if (and (util/post? req) (not (:errors vote-result)))
      (response/redirect req (url/link-poll (:poll vote-result)))
      [:form {:hx-post (util/endpoint-path poll-vote) :class "space-y-8"}
       [:input {:type :hidden :name "poll-id" :value (str poll-id)}]
       [:div {:class "mt-4"}
        (let [input-type (if multiple-choice? :checkbox :radio)]
          [:ul {:class "space-y-4"}
           (map (fn [{:poll.option/keys [value poll-option-id]}]
                  [:li
                   [:label {:class "inline-flex items-center"}
                    (comment "
on click if I match <:checked/> set #cast-vote @disabled to false
then remove .cursor-not-allowed from #cast-vote
then add .cursor-pointer to #cast-vote
then remove .opacity-50 from #cast-vote
")
                    [:input {:type input-type :name "vote" :value (str poll-option-id)
                             :class (ui/cs
                                     "h-4 w-4 border-gray-300 text-sno-orange-600 focus:ring-sno-orange-500"
                                     (when (= input-type :checkbox) "poll-checkbox"))
                             :required (= input-type :radio)
                             :_    (when (and  (= input-type :checkbox) (= poll-status :poll.status/open)) (format "
on change from .poll-checkbox
  set minChoices to %s
  set maxChoices to %s
  set checkedFound to <.poll-checkbox:checked />'s length
  set uncheckedFound to <.poll-checkbox:checked />'s length < <.poll-checkbox />'s length
  if checkedFound == 0 or checkedFound < minChoices or checkedFound > maxChoices
    set #cast-vote @disabled to true
    add .cursor-not-allowed to #cast-vote
    remove .cursor-pointer from #cast-vote
    add .opacity-50 to #cast-vote
  else
    set #cast-vote @disabled to null
    remove .cursor-not-allowed from #cast-vote
    add .cursor-pointer to #cast-vote
    remove .opacity-50 from #cast-vote
  end
    " min-choice max-choice))

                             :checked (contains? selected-opts poll-option-id)}]
                    [:span {:class "ml-2"} value]]])
                (sort-by :poll.option/position options))])

        (if draft?
          [:div {:class "mt-2 flex space-x-4 text-red-400"} (tr [:poll/open-hint])]
          (when (and (not (:errors vote-result))  multiple-choice?)
            [:div {:class "mt-2 flex space-x-4"} (tr [:poll/select-num-choices] [min-choice max-choice])]))
        (when (:errors vote-result)
          [:ul {:class "mt-2 text-red-400 list-disc"}
           (map (fn [{:keys [message]}]
                  [:li message]) (:errors vote-result))])
        [:div {:class "flex space-x-4"}
         (when-not (empty? votes)
           (ui/link-button :label (tr [:action/cancel]) :class "mt-4" :href (url/link-poll poll)))

         (ui/button :priority :primary :icon icon/checkmark
                    :id "cast-vote"
                    :disabled? (if (or draft?  multiple-choice?) true false)
                    :label (if (empty? votes)
                             (tr [:action/vote])
                             (tr [:action/change-vote])) :class "mt-4")]]])))

(ctmx/defcomponent ^:endpoint poll-detail-page [{:keys [db tr query-params] :as req}]
  poll-vote
  poll-publish
  poll-close
  (let [poll-id (util.http/path-param-uuid! req :poll-id)
        {:poll/keys [title poll-type description poll-status closes-at min-choice max-choice] :as poll} (controller/retrieve-poll db poll-id)
        member (auth/get-current-member req)
        has-voted? (controller/member-has-voted? db poll-id member)
        change-vote? (get query-params "change-vote")
        show-results? (and (not change-vote?) has-voted?)]
    (ui/panel {:title title
               :id id
               :buttons (list
                         (when (= poll-status :poll.status/draft)
                           (ui/button :label (tr [:action/open-poll]) :priority :primary :title (tr [:poll/open-hint])
                                      :hx-post (util/endpoint-path poll-publish)
                                      :attr {:_ (ui/confirm-modal-script
                                                 (tr [:action/confirm-generic])
                                                 (tr [:poll/options-read-only-hint])
                                                 (tr [:action/confirm-open-poll])
                                                 (tr [:action/cancel]))}))
                         (when (poll-status #{:poll.status/open :poll.status/draft})
                           (ui/button :label (tr [:action/edit]) :hx-get (util/comp-name #'poll-edit-page) :hx-target (hash ".") :hx-push-url true))
                         (when (= poll-status :poll.status/open)
                           (ui/button :label (tr [:reminders/remind-all]) :hx-post (util/endpoint-path poll-close)
                                      :priority :secondary
                                      :attr {:_
                                             (ui/confirm-modal-script
                                              (tr [:reminders/confirm-remind-all-title])
                                              (tr [:reminders/confirm-remind-all])
                                              (tr [:reminders/confirm])
                                              (tr [:action/cancel]))})))}

              [:div
               (ui/dl
                (ui/dl-item (tr [:poll/closes-at])
                            [:span
                             (ui/format-time closes-at) " "
                             (ui/format-dt closes-at)])
                (ui/dl-item (tr [:poll/poll-type])
                            [:span
                             (tr [poll-type])
                             (when (= poll-type :poll.type/multiple)
                               [:span
                                (format " (%s-%s)" min-choice max-choice)])])

                (ui/dl-item (tr [:poll/description])
                            (markdown/render description) "sm:col-span-3"))
               (if (or show-results? (= poll-status :poll.status/closed))
                 (poll-results req poll)
                 (poll-vote req poll))])))

(defn poll-row [{:poll/keys [title closes-at votes-count voter-count] :as poll}]
  (let [style-icon "mr-1.5 h-5 w-5 shrink-0 text-gray-400"]
    [:a {:href (url/link-poll poll) :class "block hover:bg-gray-50"}
     [:div {:class "px-4 py-4 sm:px-6"}
      [:div {:class "flex items-center justify-between"}
       [:div {:class "flex items-center space-x-2"}
        [:p {:class "truncate text-sm font-medium text-sno-orange-600"} title]]]

      [:div {:class "mt-2 sm:flex sm:justify-between"}
       [:div {:class "flex items-center text-sm text-gray-500" :title (str voter-count " people have voted")}
        (icon/users-solid {:class style-icon}) voter-count]
       [:div {:class "flex items-center text-sm text-gray-500 ml-4" :title (str votes-count " votes have been cast")}
        (icon/checkmark {:class style-icon}) votes-count]
       [:div {:class "mt-2 flex items-center text-sm text-gray-500 mt-0 ml-6 min-w-[8rem]"}
        (icon/calendar {:class style-icon})
        (ui/datetime closes-at)]]]]))

(ctmx/defcomponent ^:endpoint polls-index-page [{:keys [db tr] :as req}]
  (let [running-polls  (concat  (controller/open-polls db) (controller/draft-polls db))
        past-polls (controller/past-polls db)]
    [:div
     (ui/page-header :title (tr [:polls/index-title])
                     :buttons  (list
                                (ui/button :tag :a :label (tr [:action/create])
                                           :priority :primary
                                           :centered? true
                                           :attr {:hx-boost "true" :href (url/link-polls-create)} :icon icon/plus)))

     [:div {:class "mt-6 px-4 sm:px-6 md:px-8 md:flex md:flex-row md:space-x-4"}
      [:div {:class "max-w-lg"}
       (ui/divider-left (tr [:polls/running]))
       [:div {:class "overflow-hidden bg-white shadow-sm sm:rounded-md mb-8"}
        (if (empty? running-polls)
          [:div {:class "px-4 py-5 sm:p-6"}
           [:p {:class "text-sm text-gray-500"}
            (tr [:polls/no-running])]]

          [:ul {:role "list" :class "divide-y divide-gray-200"}
           (map (fn [poll]
                  [:li
                   (poll-row poll)]) running-polls)])]]
      [:div {:class "max-w-lg mb-8"}
       (ui/divider-left (tr [:polls/past]))
       [:div {:class "overflow-hidden bg-white shadow-sm sm:rounded-md"}
        (if (empty? past-polls)

          [:div {:class "px-4 py-5 sm:p-6"}
           [:p {:class "text-sm text-gray-500"}
            (tr [:polls/no-past])]]
          [:div
           [:div {:class "mt-6 flow-root"}
            [:ul {:role "list", :class "divide-y divide-gray-200"}
             (map (fn [gig]
                    [:li
                     (poll-row gig)]) past-polls)]]])]]]]))
