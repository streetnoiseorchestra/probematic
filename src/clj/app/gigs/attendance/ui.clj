(ns app.gigs.attendance.ui
  (:require
   [app.datastar :as d*]
   [app.gigs.detail.actions :as actions]
   [app.gigs.domain :as domain]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]))

(def plan-display-order domain/plan-priority-sorting)
(def selectable-plans [:plan/definitely :plan/unknown :plan/definitely-not :plan/not-interested])

(defn plan-icon-data [plan]
  (case plan
    :plan/definitely     {:icon "circle" :class "gigs-attendance-plan-icon--yes"}
    :plan/probably       {:icon "circle-outline" :class "gigs-attendance-plan-icon--yes"}
    :plan/unknown        {:icon "question" :class "gigs-attendance-plan-icon--unknown"}
    :plan/probably-not   {:icon "square-outline" :class "gigs-attendance-plan-icon--no"}
    :plan/definitely-not {:icon "square" :class "gigs-attendance-plan-icon--no"}
    :plan/not-interested {:icon "xmark" :class "gigs-attendance-plan-icon--not-interested"}
    {:icon "minus" :class "gigs-attendance-plan-icon--unknown"}))

(defn plan-label [plan]
  [:i18n/tr (domain/plan-label-key plan)])

(defn plan-icon
  ([plan]
   (plan-icon plan nil))
  ([plan attrs]
   (let [{:keys [icon class]} (plan-icon-data (or plan :plan/no-response))]
     [ico/Icon (merge attrs
                      {::ico/library :snoico
                       ::ico/name    icon
                       :class        (ui2/cs "gigs-attendance-plan-icon"
                                             class
                                             (:class attrs))})])))

(defn js-value [value]
  (pr-str (str value)))

(defn set-attendance-js [m]
  (str/join "; "
            (for [[k v] m]
              (str "$gig-attendance." (name k) " = " (js-value v)))))

(defn action-js [req action m]
  (str (set-attendance-js m)
       "; @post('" (d*/act req action) "')"))

(defn action-attrs [req action m]
  {:data-on:mousedown (action-js req action m)})

(defn comment-editing? [req gig-id member-id]
  (let [comment-edit (get-in req [:page-state :gig-detail :attendance :comment-edit])]
    (and (= (str gig-id) (:gig-id comment-edit))
         (= (str member-id) (:member-id comment-edit)))))

(defn comment-open-js [req gig-id member-id comment]
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

(defn summary-counts [summary]
  [:div {:class "gigs-attendance-summary"}
   (for [plan plan-display-order
         :let [count (get summary plan 0)]
         :when (not (and (zero? count)
                         (contains? domain/plan-priority-optional-display plan)))]
     [:div {:class "gigs-attendance-summary-item"}
      (plan-icon plan)
      [:span count]
      [:span {:class "wa-visually-hidden"} (plan-label plan)]])])

(defn plan-dropdown [req gig-id member-id plan]
  (let [plan (or plan :plan/no-response)]
    [:wa-dropdown {:class              "gigs-attendance-plan-dropdown"
                   :placement          "bottom-start"
                   :data-preserve-attr "open"
                   :data-on:wa-select  (str "if (!evt.detail.item.value) return"
                                            "; " (set-attendance-js {:gig-id    gig-id
                                                                     :member-id member-id})
                                            "; $gig-attendance.plan = evt.detail.item.value"
                                            "; @post('" (d*/act req ::actions/update-attendance-plan) "')")}
     [button/Button {:slot       "trigger"
                     :appearance "outlined"
                     :size       "s"
                     :with-caret true
                     :class      "gigs-attendance-plan-button"
                     :title      (plan-label plan)
                     :aria-label (plan-label plan)}
      (plan-icon plan)]
     (for [option selectable-plans]
       [:wa-dropdown-item {:value (name option)}
        (plan-icon option {:slot "icon"})
        (plan-label option)])]))

(defn motivation-select [req gig-id member-id motivation]
  [:wa-select {:size               "s"
               :data-preserve-attr "open"
               :class              "gigs-attendance-motivation-select"
               :value              (name (or motivation :motivation/none))
               :data-on:change     (str (set-attendance-js {:gig-id    gig-id
                                                            :member-id member-id})
                                        "; $gig-attendance.motivation = evt.target.value"
                                        "; @post('" (d*/act req ::actions/update-attendance-motivation) "')")}
   (for [motivation domain/motivations]
     [:wa-option {:value (name motivation)}
      [:i18n/tr (domain/motivation-label-key motivation)]])])

(defn comment-control [req gig-id member-id comment]
  (if (comment-editing? req gig-id member-id)
    [:wa-input {:class                "gigs-attendance-comment-input"
                :size                 "s"
                :autofocus            true
                :value                comment
                :data-bind            "gig-attendance.comment"
                :data-ref             "_gigAttendanceCommentEl"
                :data-init__delay.1ms "$_gigAttendanceCommentEl.focus()"
                :data-on:keydown      (str "if (evt.key == 'Escape') { evt.preventDefault();"
                                           " @post('" (d*/act req ::actions/close-attendance-comment) "')"
                                           " } else if (evt.key == 'Enter') { evt.preventDefault(); "
                                           (set-attendance-js {:gig-id    gig-id
                                                               :member-id member-id})
                                           "; $gig-attendance.comment = evt.target.value"
                                           "; @post('" (d*/act req ::actions/update-attendance-comment) "')"
                                           " }")
                :data-on:blur         (str "if ($gig-attendance.switching-comment) return; "
                                           (set-attendance-js {:gig-id    gig-id
                                                               :member-id member-id})
                                           "; $gig-attendance.comment = evt.target.value"
                                           "; @post('" (d*/act req ::actions/update-attendance-comment) "')")}]
    (if (seq comment)
      [button/Button {:appearance        "plain"
                      :variant           "brand"
                      :size              "s"
                      :class             "gigs-attendance-comment-link"
                      :data-on:mousedown (comment-open-js req gig-id member-id comment)}
       comment]
      [button/Button {:appearance        "plain"
                      :size              "s"
                      :class             "gigs-attendance-comment-button"
                      :aria-label        [:i18n/tr :action/comment]
                      :data-on:mousedown (comment-open-js req gig-id member-id "")}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :comment-outline}]])))

(defn comment-class [req gig-id member-id comment]
  (ui2/cs "gigs-attendance-comment"
          (when (seq comment) "gigs-attendance-comment--filled")
          (when (comment-editing? req gig-id member-id) "gigs-attendance-comment--editing")))

(defn attendance-controls
  ([req attendance gig-id]
   (attendance-controls req attendance gig-id nil))
  ([req {:attendance/keys [plan motivation comment member]} gig-id class]
   (let [member-id (:member/member-id member)]
     [:div {:class (ui2/cs class "wa-cluster wa-gap-2xs wa-align-items-center")}
      (plan-dropdown req gig-id member-id plan)
      (motivation-select req gig-id member-id motivation)
      [:div {:class (comment-class req gig-id member-id comment)}
       (comment-control req gig-id member-id comment)]])))

(defn member-link [member]
  (let [{:member/keys [member-id]} member]
    [:a {:href  (urls/link-member member-id)
         :class "gigs-attendance-member-link"}
     (ui2/member-nick member)]))

(defn attendance-row-id [gig-id member-id]
  (str "gig-attendance-row-"
       (ui2/safe-dom-id gig-id)
       "-"
       (ui2/safe-dom-id member-id)))

(defn editable-attendance-row [{:keys [gig-id] :as req} attendance]
  (let [{:member/keys [member-id] :as member} (:attendance/member attendance)]
    [:div {:id    (attendance-row-id gig-id member-id)
           :class "gigs-attendance-row gigs-attendance-row--editable"}
     [:div {:class "gigs-attendance-member"}
      (member-link member)]
     [:div {:class "gigs-attendance-plan"}
      (plan-dropdown req gig-id member-id (:attendance/plan attendance))]
     [:div {:class "gigs-attendance-motivation"}
      (motivation-select req gig-id member-id (:attendance/motivation attendance))]
     [:div {:class (comment-class req gig-id member-id (:attendance/comment attendance))}
      (comment-control req gig-id member-id (:attendance/comment attendance))]]))

(defn archived-attendance-row [{:keys [gig-id]} attendance]
  (let [{:member/keys [member-id] :as member} (:attendance/member attendance)]
    [:div {:id    (attendance-row-id gig-id member-id)
           :class "gigs-attendance-row gigs-attendance-row--archived"}
     [:div {:class "gigs-attendance-member"}
      (member-link member)]
     [:div {:class "gigs-attendance-plan"}
      (plan-icon (:attendance/plan attendance))]
     [:div {:class "gigs-attendance-motivation-readonly"}
      (some-> (:attendance/motivation attendance) name)]
     [:div {:class "gigs-attendance-comment-readonly"}
      (:attendance/comment attendance)]]))

(defn attendance-signals [{:keys [page-state]}]
  (let [{:keys [comment gig-id member-id]} (get-in page-state [:gig-detail :attendance :comment-edit])]
    {:gig-attendance {:comment           (or comment "")
                      :comment-gig-id    (or gig-id "")
                      :comment-member-id (or member-id "")
                      :switching-comment false}}))
