(ns app.gigs.detail.views
  (:require
   [app.datastar :as d*]
   [app.gigs.detail.actions :as actions]
   [app.gigs.detail.queries :as detail.queries]
   [app.gigs.domain :as domain]
   [app.gigs.ui :as gigs.ui]
   [app.markdown :as markdown]
   [app.queries :as q]
   [app.ui :as ui]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- muted [value]
  (if (str/blank? (str value))
    [:span {:class "wa-color-text-quiet"} "—"]
    value))

(defn- detail-item
  ([label value]
   (detail-item label value nil))
  ([label value attrs]
   (into [:div (or attrs {})]
         [[:dt label]
          [:dd value]])))

(defn- member-name [member]
  (muted (some-> member ui/member-nick)))

(defn- blankish? [value]
  (str/blank? (str value)))

(defn- optional-item
  ([label value]
   (optional-item label value nil))
  ([label value attrs]
   (when-not (blankish? value)
     (detail-item label value attrs))))

(defn- optional-markdown-item [label markdown-text]
  (when-not (str/blank? markdown-text)
    (detail-item label
                 (markdown/render markdown-text)
                 {:class "gigs-detail-wide"})))

(defn- optional-lines-item [label text]
  (when-not (str/blank? text)
    (detail-item label
                 (interpose [:br] (str/split-lines text))
                 {:class "gigs-detail-wide"})))

(defn- gig-date [{:gig/keys [date end-date]}]
  (cond
    (and date end-date) (ui/daterange date end-date)
    date                (ui/datetime date)
    :else               (muted nil)))

(defn- header-actions [{:keys [tr]} gig]
  [:div {:class "wa-cluster wa-gap-xs"}
   [:wa-button {:appearance "outlined"
                :href       (urls/link-gig-edit gig)}
    (tr [:action/edit])]
   [:wa-button {:appearance "outlined"
                :variant    "brand"
                :disabled   true}
    "Log Plays"]])

(defn- gig-summary [{:keys [tr] :as req} {:gig/keys [title gig-type status] :as gig}]
  [:header {:class "gigs-detail-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item (gigs.ui/gig-breadcrumb-label gig)]]
   [:section {:class "wa-stack wa-gap-l"}
    [:div {:class "wa-flank:end wa-align-items-start"}
     [:div {:class "wa-stack wa-gap-2xs"}
      [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
       [:h1 title]
       (when status
         (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))]
      [:span {:class "wa-caption-s"}
       (tr [gig-type])]]
     (header-actions req gig)]]])

(defn- gig-info-section [{:keys [tr]} {:gig/keys [call-time contact end-time leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time setlist] :as gig}]
  (ui2/section-card
   {:title (tr [:gig/gig-info])}
   [:dl {:class "particulars"}
    (detail-item (tr [:gig/date]) (gig-date gig))
    (detail-item (tr [:gig/location]) (if (str/blank? location)
                                        (muted nil)
                                        (markdown/render-one-line location)))
    (detail-item (tr [:gig/contact]) (member-name contact))
    (detail-item (tr [:gig/call-time]) (muted (ui/time call-time)))
    (optional-item (tr [:gig/set-time]) (ui/time set-time))
    (optional-item (tr [:gig/end-time]) (ui/time end-time))
    (optional-item (tr [:gig/leader]) leader)
    (when (domain/probe? gig)
      (list
       (optional-item (tr [:gig/rehearsal-leader1]) (some-> rehearsal-leader1 ui/member-nick))
       (optional-item (tr [:gig/rehearsal-leader2]) (some-> rehearsal-leader2 ui/member-nick))))
    (optional-item (tr [:gig/pay-deal]) pay-deal)
    (optional-item (tr [:gig/outfit]) outfit)
    (optional-markdown-item (tr [:gig/more-details]) more-details)
    (optional-lines-item (tr [:gig/setlist]) setlist)
    (optional-item (tr [:gig/post-gig-plans]) post-gig-plans {:class "gigs-detail-wide"})]))

(defn- disabled-create-button [label]
  [:wa-button {:appearance "outlined"
               :variant    "brand"
               :disabled   true}
   label])

(defn- setlist-section [{:keys [db tr]} gig-id]
  (let [songs (q/setlist-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    (tr [:gig/setlist])
      :divider? true
      :actions  (when-not (seq songs)
                  [(disabled-create-button (tr [:gig/create-setlist]))])}
     (gigs.ui/setlist-list songs))))

(defn- probeplan-section [{:keys [db tr]} gig-id]
  (let [songs (q/probeplan-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    (tr [:gig/probeplan])
      :divider? true
      :actions  (when-not (seq songs)
                  [(disabled-create-button (tr [:gig/create-probeplan]))])}
     (gigs.ui/probeplan-list tr songs))))

(defn- planned-songs-section [req {:gig/keys [gig-id] :as gig}]
  (cond
    (domain/probe? gig) (probeplan-section req gig-id)
    (domain/gig? gig)   (setlist-section req gig-id)
    :else               nil))

(def plan-display-order domain/plan-priority-sorting)
(def selectable-plans [:plan/definitely :plan/unknown :plan/definitely-not :plan/not-interested])

(defn- plan-icon-data [plan]
  (case plan
    :plan/definitely     {:icon "circle" :class "gigs-attendance-plan-icon--yes"}
    :plan/probably       {:icon "circle-outline" :class "gigs-attendance-plan-icon--yes"}
    :plan/unknown        {:icon "question" :class "gigs-attendance-plan-icon--unknown"}
    :plan/probably-not   {:icon "square-outline" :class "gigs-attendance-plan-icon--no"}
    :plan/definitely-not {:icon "square" :class "gigs-attendance-plan-icon--no"}
    :plan/not-interested {:icon "xmark" :class "gigs-attendance-plan-icon--not-interested"}
    {:icon "minus" :class "gigs-attendance-plan-icon--unknown"}))

(defn- plan-label [tr plan]
  (tr [(or plan :plan/no-response)]))

(defn- plan-icon
  ([plan]
   (plan-icon plan nil))
  ([plan attrs]
   (let [{:keys [icon class]} (plan-icon-data (or plan :plan/no-response))]
     [:wa-icon (merge attrs
                      {:library "snoico"
                       :name    icon
                       :class   (str "gigs-attendance-plan-icon " class
                                     (when-let [extra (:class attrs)]
                                       (str " " extra)))})])))

(defn- section-name-wrappable [{:section/keys [name]}]
  (interpose [:span "/" [:wbr]]
             (str/split name #"/")))

(defn- js-value [value]
  (pr-str (str value)))

(defn- set-attendance-js [m]
  (str/join "; "
            (for [[k v] m]
              (str "$gig-attendance." (name k) " = " (js-value v)))))

(defn- action-js [req action m]
  (str (set-attendance-js m)
       "; @post('" (d*/act req action) "')"))

(defn- action-attrs [req action m]
  {:data-on:mousedown (action-js req action m)})

(defn- comment-open-js [req gig-id member-id comment]
  (str "if ($gig-attendance.comment-member-id) {"
       "$gig-attendance.switching-comment = true; "
       (set-attendance-js {:next-gig-id    gig-id
                           :next-member-id member-id
                           :next-comment   (or comment "")})
       "; @post('" (d*/act req ::actions/switch-attendance-comment) "')"
       " } else { "
       (action-js req
                  ::actions/open-attendance-comment
                  {:gig-id    gig-id
                   :member-id member-id
                   :comment   (or comment "")})
       " }"))

(defn- summary-counts [tr summary]
  [:div {:class "gigs-attendance-summary"}
   (for [plan plan-display-order
         :let [count (get summary plan 0)]
         :when (not (and (zero? count)
                         (contains? domain/plan-priority-optional-display plan)))]
     [:div {:class "gigs-attendance-summary-item"}
      (plan-icon plan)
      [:span count]
      [:span {:class "wa-visually-hidden"} (plan-label tr plan)]])])

(defn- plan-dropdown [{:keys [tr] :as req} gig-id member-id plan]
  (let [plan (or plan :plan/no-response)]
    [:wa-dropdown {:class             "gigs-attendance-plan-dropdown"
                   :placement         "bottom-start"
                   :data-preserve-attr    "open"
                   :data-on:wa-select (str "if (!evt.detail.item.value) return"
                                           "; " (set-attendance-js {:gig-id    gig-id
                                                                    :member-id member-id})
                                           "; $gig-attendance.plan = evt.detail.item.value"
                                           "; @post('" (d*/act req ::actions/update-attendance-plan) "')")}
     [:wa-button {:slot       "trigger"
                  :appearance "outlined"
                  :size       "small"
                  :class      "gigs-attendance-plan-button"
                  :title      (plan-label tr plan)
                  :aria-label (plan-label tr plan)}
      (plan-icon plan)
      [:wa-icon {:library "snoico"
                 :name    "chevron-down"
                 :class   "gigs-attendance-plan-caret"}]]
     (for [option selectable-plans]
       [:wa-dropdown-item {:value (name option)}
        (plan-icon option {:slot "icon"})
        (plan-label tr option)])]))

(defn- motivation-select [{:keys [tr] :as req} gig-id member-id motivation]
  [:wa-select {:size           "small"
               :data-preserve-attr    "open"
               :class          "gigs-attendance-motivation-select"
               :value          (name (or motivation :motivation/none))
               :data-on:change (str (set-attendance-js {:gig-id    gig-id
                                                        :member-id member-id})
                                    "; $gig-attendance.motivation = evt.target.value"
                                    "; @post('" (d*/act req ::actions/update-attendance-motivation) "')")}
   (for [motivation domain/motivations]
     [:wa-option {:value (name motivation)}
      (tr [motivation])])])

(defn- comment-editing? [req gig-id member-id]
  (let [comment-edit (get-in req [:page-state :gig-detail :attendance :comment-edit])]
    (and (= (str gig-id) (:gig-id comment-edit))
         (= (str member-id) (:member-id comment-edit)))))

(defn- comment-control [req gig-id member-id comment]
  (if (comment-editing? req gig-id member-id)
    [:wa-input {:class           "gigs-attendance-comment-input"
                :size            "small"
                :autofocus       true
                :value           comment
                :data-bind       "gig-attendance.comment"
                :data-ref        "_gigAttendanceCommentEl"
                :data-init__delay.1ms       "$_gigAttendanceCommentEl.focus()"
                :data-on:keydown (str "if (evt.key == 'Escape') { evt.preventDefault();"
                                      " @post('" (d*/act req ::actions/close-attendance-comment) "')"
                                      " } else if (evt.key == 'Enter') { evt.preventDefault(); "
                                      (set-attendance-js {:gig-id    gig-id
                                                          :member-id member-id})
                                      "; $gig-attendance.comment = evt.target.value"
                                      "; @post('" (d*/act req ::actions/update-attendance-comment) "')"
                                      " }")
                :data-on:blur    (str "if ($gig-attendance.switching-comment) return; "
                                      (set-attendance-js {:gig-id    gig-id
                                                          :member-id member-id})
                                      "; $gig-attendance.comment = evt.target.value"
                                      "; @post('" (d*/act req ::actions/update-attendance-comment) "')")}]
    (if (seq comment)
      [:wa-button {:appearance        "plain"
                   :size              "small"
                   :class             "gigs-attendance-comment-link"
                   :data-on:mousedown (comment-open-js req gig-id member-id comment)}
       comment]
      [:wa-button {:appearance        "plain"
                   :size              "small"
                   :class             "gigs-attendance-comment-button"
                   :aria-label        "Add comment"
                   :data-on:mousedown (comment-open-js req gig-id member-id "")}
       [:wa-icon {:library "snoico"
                  :name    "comment-outline"}]])))

(defn- member-link [member]
  (let [{:member/keys [member-id]} member]
    [:a {:href  (urls/link-member member-id)
         :class "gigs-attendance-member-link"}
     (ui/member-nick member)]))

(defn- attendance-row-id [gig-id member-id]
  (str "gig-attendance-row-"
       (ui2/safe-dom-id gig-id)
       "-"
       (ui2/safe-dom-id member-id)))

(defn- editable-attendance-row [{:keys [gig-id] :as req} attendance]
  (let [{:member/keys [member-id] :as member} (:attendance/member attendance)]
    [:div {:id    (attendance-row-id gig-id member-id)
           :class "gigs-attendance-row gigs-attendance-row--editable"}
     [:div {:class "gigs-attendance-member"}
      (member-link member)]
     [:div {:class "gigs-attendance-plan"}
      (plan-dropdown req gig-id member-id (:attendance/plan attendance))]
     [:div {:class "gigs-attendance-motivation"}
      (motivation-select req gig-id member-id (:attendance/motivation attendance))]
     [:div {:class (str "gigs-attendance-comment"
                        (when (seq (:attendance/comment attendance))
                          " gigs-attendance-comment--filled")
                        (when (comment-editing? req gig-id member-id)
                          " gigs-attendance-comment--editing"))}
      (comment-control req gig-id member-id (:attendance/comment attendance))]]))

(defn- archived-attendance-row [{:keys [gig-id]} attendance]
  (let [{:member/keys [member-id] :as member} (:attendance/member attendance)]
    [:div {:id    (attendance-row-id gig-id member-id)
           :class "gigs-attendance-row gigs-attendance-row--archived"}
     [:div {:class "gigs-attendance-member"}
      [:a {:href  (urls/link-member member-id)
           :class "gigs-attendance-member-link"}
       (ui/member-nick member)]]
     [:div {:class "gigs-attendance-plan"}
      (plan-icon (:attendance/plan attendance))]
     [:div {:class "gigs-attendance-motivation-readonly"}
      (some-> (:attendance/motivation attendance) name)]
     [:div {:class "gigs-attendance-comment-readonly"}
      (:attendance/comment attendance)]]))

(defn- attendance-section-view [req archived? idx section]
  [:div {:class (str "gigs-attendance-section"
                     (when archived? " gigs-attendance-section--archived")
                     (when (and archived? (even? idx)) " gigs-attendance-section--alt"))}
   [:div {:class "gigs-attendance-section-name"}
    (section-name-wrappable section)]
   [:div {:class "gigs-attendance-section-members"}
    (for [attendance (:members section)]
      (if archived?
        (archived-attendance-row req attendance)
        (editable-attendance-row req attendance)))]])

(defn- attendance-actions [req archived? show-committed?]
  (when-not archived?
    [[:wa-button {:appearance "outlined"
                  :size       "small"
                  :disabled   true}
      ((:tr req) [:reminders/remind-all])]
     [:wa-button (merge {:appearance "filled"
                         :variant    "brand"
                         :size       "small"}
                        (action-attrs req
                                      ::actions/toggle-attendance-committed
                                      {:show-committed (not show-committed?)}))
      (if show-committed?
        ((:tr req) [:gig/show-all])
        ((:tr req) [:gig/show-committed]))]]))

(defn- attendance-section [{:keys [db page-state tr] :as req} {:gig/keys [gig-id] :as gig}]
  (let [show-committed? (boolean (get-in page-state [:gig-detail :attendance :show-committed?]))
        {:keys [archived? sections summary]} (detail.queries/attendance-data db gig show-committed?)]
    (ui2/section-card
     {:id       "gig-attendance"
      :title    (tr [:gig/attendance])
      :class    "gigs-attendance-card"
      :divider? true
      :actions  (attendance-actions req archived? show-committed?)}
     (summary-counts tr summary)
     [:div {:class "gigs-attendance-sections"}
      (map-indexed (fn [idx section]
                     (attendance-section-view (assoc req :gig-id gig-id) archived? idx section))
                   sections)])))

(defn- attendance-signals [{:keys [page-state]}]
  (let [{:keys [comment gig-id member-id]} (get-in page-state [:gig-detail :attendance :comment-edit])]
    {:gig-attendance {:comment           (or comment "")
                      :comment-gig-id    (or gig-id "")
                      :comment-member-id (or member-id "")
                      :switching-comment false}}))

(defn page [{:keys [db] :as req}]
  (let [gig-id (http.util/path-param-uuid! req :gig/gig-id)
        gig    (q/retrieve-gig db gig-id)]
    (if gig
      (ui2/datastar-page
       [:div {:class        "wa-stack wa-gap-2xl gigs-detail-page"
              :data-signals (d*/->signals (attendance-signals req))}
        (gig-summary req gig)
        (gig-info-section req gig)
        (planned-songs-section req gig)
        (attendance-section req gig)])
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(d*/refresh-all!)
