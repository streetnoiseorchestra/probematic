(ns app.gigs.attendance.ui
  (:require
   [app.datastar :as d*]
   [app.gigs.detail.actions :as actions]
   [app.gigs.domain :as domain]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->js-str]]))

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

(defn interaction-attrs [req action params]
  (when (::d*/enabled? req)
    (let [policy (get actions/interaction-policies action)
          key    (d*/interaction-key policy params)]
      {:data-interaction    key
       :data-attr:aria-busy (->js-str (if ~(d*/pending-expr key) "true" "false"))
       :data-attr:disabled  (d*/blocked-expr policy key)})))

(defn action-js
  ([req action params]
   (action-js req action params {}))
  ([req action params values]
   (if (::d*/enabled? req)
     (d*/submit-js (d*/act req action) (get actions/interaction-policies action) params values)
     (str "(() => { " (set-attendance-js params)
          (apply str (for [[k expression] values] (str "; $gig-attendance." (name k) " = " expression)))
          "; return @post('" (d*/act req action) "'); })()"))))

(defn action-attrs [req action params]
  (assoc (interaction-attrs req action params) :data-on:mousedown (action-js req action params)))

(defn comment-editing? [req gig-id member-id]
  (let [comment-edit (get-in req [:page-state :gig-detail :attendance :comment-edit])]
    (and (= (str gig-id) (:gig-id comment-edit))
         (= (str member-id) (:member-id comment-edit)))))

(defn- legacy-comment-open-js [req gig-id member-id comment]
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

(defn comment-open-js [req gig-id member-id comment]
  (if-not (::d*/enabled? req)
    (legacy-comment-open-js req gig-id member-id comment)
    (if-let [editing (get-in req [:page-state :gig-detail :attendance :comment-edit])]
      (action-js req ::actions/switch-attendance-comment
                 {:gig-id            gig-id
                  :comment-gig-id    (:gig-id editing)
                  :comment-member-id (:member-id editing)
                  :next-gig-id       gig-id
                  :next-member-id    member-id
                  :next-comment      (or comment "")}
                 {:comment "$gig-attendance.comment"})
      (action-js req ::actions/open-attendance-comment
                 {:gig-id gig-id :member-id member-id :comment (or comment "")}))))

(defn summary-counts [summary]
  [:div {:class "gigs-attendance-summary"}
   (for [plan  plan-display-order
         :let  [count (get summary plan 0)]
         :when (not (and (zero? count)
                         (contains? domain/plan-priority-optional-display plan)))]
     [:div {:class "gigs-attendance-summary-item"}
      (plan-icon plan)
      [:span count]
      [:span {:class "wa-visually-hidden"} (plan-label plan)]])])

(defn plan-dropdown [req gig-id member-id plan]
  (let [plan    (or plan :plan/no-response)
        params  {:gig-id gig-id :member-id member-id}
        attrs   (interaction-attrs req ::actions/update-attendance-plan params)
        pending (some-> (:data-interaction attrs) d*/pending-expr)]
    [:wa-dropdown (merge attrs
                         {:class              "gigs-attendance-plan-dropdown"
                          :placement          "bottom-start"
                          :data-preserve-attr "open"
                          :data-on:wa-select  (->js-str
                                               (when evt.detail.item.value
                                                 (expr/raw ~(action-js req ::actions/update-attendance-plan
                                                                       params {:plan "evt.detail.item.value"}))))})
     [button/Button (merge {:slot       "trigger"
                            :appearance "outlined"
                            :size       "s"
                            :with-caret true
                            :class      "gigs-attendance-plan-button"
                            :title      (plan-label plan)
                            :aria-label (plan-label plan)}
                           (select-keys attrs [:data-attr:disabled :data-attr:aria-busy]))
      (if pending
        (list [:span {:data-show (str "!" pending)} (plan-icon plan)]
              [:wa-spinner {:data-show pending :style {:font-size "1em"} :aria-hidden "true"}])
        (plan-icon plan))]
     (for [option selectable-plans]
       [:wa-dropdown-item {:value (name option)}
        (plan-icon option {:slot "icon"})
        (plan-label option)])]))

(defn motivation-select [req gig-id member-id motivation]
  (let [params  {:gig-id gig-id :member-id member-id}
        attrs   (interaction-attrs req ::actions/update-attendance-motivation params)
        pending (some-> (:data-interaction attrs) d*/pending-expr)
        value   (name (or motivation :motivation/none))]
    [:wa-select (cond-> (merge attrs
                               {:size               "s"
                                :data-preserve-attr "open"
                                :class              "gigs-attendance-motivation-select"
                                :value              value
                                :data-on:change     (action-js req ::actions/update-attendance-motivation params
                                                               {:motivation "evt.target.value"})})
                  pending
                  (assoc :data-preserve-attr "open value"
                         :data-effect (->js-str
                                       (set! el.value
                                             (if ~pending
                                               (.-motivation (.-args (aget $_pending ~(:data-interaction attrs))))
                                               ~value)))))
     (for [motivation domain/motivations]
       [:wa-option {:value (name motivation)}
        [:i18n/tr (domain/motivation-label-key motivation)]])]))

(defn comment-open-attrs [req gig-id member-id]
  (let [editing (get-in req [:page-state :gig-detail :attendance :comment-edit])]
    (interaction-attrs req
                       (if editing ::actions/switch-attendance-comment ::actions/open-attendance-comment)
                       {:gig-id            gig-id
                        :member-id         member-id
                        :comment-member-id (:member-id editing)
                        :next-member-id    member-id})))

(defn comment-control [req gig-id member-id comment]
  (let [params  {:gig-id gig-id :member-id member-id}
        attrs   (interaction-attrs req ::actions/update-attendance-comment params)
        save-js (action-js req ::actions/update-attendance-comment params {:comment "evt.target.value"})]
    (if (comment-editing? req gig-id member-id)
      [:wa-input (merge attrs
                        {:class                "gigs-attendance-comment-input"
                         :size                 "s"
                         :autofocus            true
                         :value                comment
                         :data-bind            "gig-attendance.comment"
                         :data-preserve-attr   "value"
                         :data-ref             "_gigAttendanceCommentEl"
                         :data-init__delay.1ms "$_gigAttendanceCommentEl.focus()"
                         :data-on:keydown      (->js-str
                                                (cond
                                                  (= evt.key "Escape")
                                                  (do (evt.preventDefault)
                                                      (expr/raw ~(action-js req ::actions/close-attendance-comment params)))
                                                  (= evt.key "Enter")
                                                  (do (evt.preventDefault)
                                                      (expr/raw ~save-js))))
                         :data-on:blur         (str (when-not (::d*/enabled? req)
                                                      "if ($gig-attendance.switching-comment) return; ")
                                                    save-js)})]
      [button/Button (merge (comment-open-attrs req gig-id member-id)
                            {:appearance        "plain"
                             :size              "s"
                             :class             (if (seq comment) "gigs-attendance-comment-link" "gigs-attendance-comment-button")
                             :data-on:mousedown (comment-open-js req gig-id member-id comment)}
                            (if (seq comment) {:variant "brand"} {:aria-label [:i18n/tr :action/comment]}))
       (if (seq comment)
         comment
         [ico/Icon {::ico/library :snoico ::ico/name :comment-outline}])])))

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
