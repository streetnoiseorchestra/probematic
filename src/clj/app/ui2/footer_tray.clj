(ns app.ui2.footer-tray
  (:require
   [app.ui2.avatar :as avatar]
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as url]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(def doc-footer-tray
  {:examples ["[footer-tray/FooterTray
              {::footer-tray/member member
               ::footer-tray/labels translated-labels
               ::footer-tray/shortcuts shortcuts
               ::footer-tray/notification notification}]"]
   :ns       *ns*
   :as       'footer-tray
   :name     'FooterTray
   :desc     "Renders a fixed application tray with an account menu and caller-provided shortcut triggers."
   :alias    ::footer-tray
   :schema   [:map {}
              [::member {:doc "The signed-in member shown by the account menu."}
               :map]
              [::labels {:doc "Translated accessible labels for the tray and account menu."}
               [:map
                [:footer :string]
                [:account :string]
                [:profile :string]
                [:band-settings :string]
                [:logout :string]]]
              [::shortcuts {:doc "Ordered shortcut definitions containing drawer ids, labels, and icons."}
               [:sequential
                [:map
                 [:id :string]
                 [:label :string]
                 [:icon [:or :keyword :string]]
                 [:icon-library {:optional true} [:or :keyword :string]]]]]
              [::notification {:doc "Notification trigger definition containing its drawer id and label."}
               [:map
                [:id :string]
                [:label :string]]]]})

(def ^{:doc (uic/generate-docstring doc-footer-tray)} FooterTray
  ::footer-tray)

(def ^:private consumed-props
  #{::member ::labels ::shortcuts ::notification})

(def ^:private menu-icon-opts
  {:slot "icon"})

(defn- sheet-trigger-attrs [id]
  {:aria-controls id
   :aria-expanded "false"
   :aria-haspopup "dialog"
   :data-on:click (->expr
                   (set! $footerTraySheet
                         (if (=== $footerTraySheet ~id) "" ~id)))
   :data-attr:aria-expanded (->expr
                             (if (=== $footerTraySheet ~id) "true" "false"))
   :data-class:selected (->expr (=== $footerTraySheet ~id))})

(defn- menu-icon [name]
  [ico/Icon (merge {::ico/library :snoico
                    ::ico/name name}
                   menu-icon-opts)])

(defn- account-menu [member labels]
  [:wa-dropdown {:placement "top-start"
                 :distance 4}
   [button/Button {:slot "trigger"
                   :class "tray-button account"
                   :appearance "plain"
                   :aria-label (:account labels)}
    [avatar/Avatar {::avatar/member member
                    ::avatar/image-size 200
                    ::avatar/icon :user
                    ::avatar/link? false
                    :slot "start"}]
    [:span {:class "wa-visually-hidden"} (:account labels)]]
   [:wa-dropdown-item {:value (url/link-member member)
                       :onclick "window.location = this.value"}
    (menu-icon :user)
    (:profile labels)]
   [:wa-dropdown-item {:value "/band-settings"
                       :onclick "window.location = this.value"}
    (menu-icon :cog)
    (:band-settings labels)]
   [divider/Divider]
   [:wa-dropdown-item {:value (url/link-logout)
                       :variant "danger"
                       :onclick "window.location = this.value"}
    (menu-icon :xmark)
    (:logout labels)]])

(defn- shortcut-button [{:keys [id label icon-library icon]}]
  [button/Button (merge {:class "tray-button"
                         :appearance "plain"
                         :aria-label label}
                        (sheet-trigger-attrs id))
   [ico/Icon {::ico/library (or icon-library :snoico)
              ::ico/name icon}]
   [:span {:class "shortcut-label"} label]])

(defn- ping-button [{:keys [id label]}]
  [button/Button (merge {:class "tray-button ping"
                         :appearance "plain"
                         :aria-label label}
                        (sheet-trigger-attrs id))
   [:span {:slot "start"
           :class "ping-mark"
           :aria-hidden "true"}]
   [:span {:class "wa-visually-hidden"} label]])

(defmethod c/resolve-alias ::footer-tray
  [_ attrs _children]
  (let [attrs (or attrs {})
        _ (uic/validate-opts! doc-footer-tray attrs)
        member (::member attrs)
        labels (::labels attrs)
        shortcuts (::shortcuts attrs)
        notification (::notification attrs)
        footer-attrs (-> (apply dissoc attrs consumed-props)
                         (uic/merge-attrs :class "footer-tray"
                                          :data-signals:footer-tray-sheet "''"))]
    (cc/compile
     [:footer footer-attrs
      [:nav {:aria-label (:footer labels)}
       (account-menu member labels)
       (into [:menu {:class "actions wa-cluster wa-gap-2xs wa-flex-nowrap"}]
             (map (fn [shortcut]
                    [:li (shortcut-button shortcut)]))
             shortcuts)
       (ping-button notification)]])))
