(ns app.account.ui
  (:require
   [app.auth :as auth]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]))

(def account-root "/account-settings")

(defn current-member-id [req]
  (:member/member-id (auth/get-current-member req)))

(defn instance-name [req]
  (get-in req [:system :env :name]))

(defn instance-tr [req message-id]
  [:i18n/tr message-id {:instance-name (instance-name req)}])

(defn account-main [& children]
  (-> (apply ui2/datastar-page* children)
      (update-in [1 :class] ui2/cs "account-settings")))

(defn save-action [form-id label]
  [button/Button {:appearance "filled"
                  :variant    "brand"
                  :type       "submit"
                  :form       form-id}
   label])

(defn account-toolbar [title actions mobile-mode]
  [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                             [breadcrumb/Breadcrumb (cond-> {}
                                                      mobile-mode (assoc ::breadcrumb/mobile-mode mobile-mode))
                              [breadcrumb/BreadcrumbItem {::breadcrumb/href account-root}
                               [:i18n/tr :account-settings/title]]
                              [breadcrumb/BreadcrumbItem title]]
                             ::page-toolbar/actions actions
                             :aria-label [:i18n/tr :account-settings/toolbar-label]}])

(defn standard-page
  [{:keys [title subtitle actions after show-header? breadcrumb-mobile-mode]
    :or   {show-header? true}} & content]
  (account-main
   (into
    [page-surface/PageSurface
     {::page-surface/width :standard
      :class "account-detail-surface"
      ::page-surface/toolbar (account-toolbar title actions breadcrumb-mobile-mode)}
     (into (cond-> [:div {:class "wa-stack wa-gap-xl"}]
             show-header?
             (conj [page-header/PageHeader
                    {::page-header/title title
                     ::page-header/subtitle subtitle}]))
           content)]
    after)))

(defn field-error [state field]
  (get-in state [:_error field :error]))

(defn field
  [{:keys [state root field id label description attrs]}]
  (let [error-id (str id "-error")
        description-id (str id "-description")
        error (field-error state field)]
    [:div {:class "account-field wa-stack wa-gap-2xs"}
     [:label {:for id :class "wa-caption-s"} label]
     [:input (merge {:id               id
                     :name             (name field)
                     :data-bind        (str root "." (name field))
                     :aria-invalid     (boolean error)
                     :aria-describedby (ui2/cs
                                        (when description description-id)
                                        (when error error-id))}
                    attrs)]
     (when description
       [:span {:id description-id :class "wa-caption-s wa-color-text-quiet"}
        description])
     (when error
       [:span {:id error-id :class "wa-caption-s wa-color-text-danger" :role "alert"}
        error])]))

(defn- choice-copy [label description]
  (if description
    [:span {:class "account-choice-copy wa-stack wa-gap-3xs"}
     [:span label]
     [:span {:class "wa-caption-s wa-color-text-quiet"} description]]
    [:span label]))

(defn radio-option
  [{:keys [id name value signal checked? label description form attrs]}]
  [:label {:for id
           :class (str "account-choice wa-flank wa-gap-xs "
                       (if description "wa-align-items-start" "wa-align-items-center"))}
   [:input (merge (cond-> {:id        id
                           :type      "radio"
                           :name      name
                           :value     value
                           :checked   checked?
                           :data-bind signal}
                    form (assoc :form form))
                  attrs)]
   (choice-copy label description)])

(defn checkbox-option
  [{:keys [id name signal checked? label description form attrs]}]
  [:label {:for id
           :class (str "account-choice wa-flank wa-gap-xs "
                       (if description "wa-align-items-start" "wa-align-items-center"))}
   [:input (merge (cond-> {:id        id
                           :type      "checkbox"
                           :name      name
                           :checked   checked?
                           :data-bind signal}
                    form (assoc :form form))
                  attrs)]
   (choice-copy label description)])

(defn feedback [state]
  (when-let [message (:_feedback state)]
    [:p {:class "account-feedback wa-caption-s" :role "status"} message]))
