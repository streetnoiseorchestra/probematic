(ns app.members.views-test
  (:require
   [app.members.detail.views :as detail.views]
   [app.members.index.views :as index.views]
   [app.members.invite.views :as invite.views]
   [app.system :as app-system]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(deftest member-page-shells
  (let [{:keys [conn member-id]} (tc/new-system "member-page-shells")
        _                          @(d/transact
                                     conn
                                     [{:member/member-id member-id
                                       :member/name      "Casey Jones"
                                       :member/nick      "Casey"
                                       :member/email     "casey@example.test"
                                       :member/phone     "+43 1 234"
                                       :member/username  "casey"
                                       :member/active?   true}])
        tr                         (fn
                                     ([path]
                                      (name (last path)))
                                     ([path _args]
                                      (name (last path))))
        request                    {::r/router       (r/router ["/act" {:name :app.routes.datastar/act}])
                                    :current-locale :en
                                    :db             (d/db conn)
                                    :page-state     {}
                                    :session        {:session/roles #{}}
                                    :system         {:env   {}
                                                     :redis {:spec (assoc (get-in (app-system/config {:profile :test})
                                                                                  [:redis :conn-spec])
                                                                          :db
                                                                          15)}}
                                    :tr             tr}
        member-url                 (str "/member/" member-id)]
    (testing "the directory uses a wide Members surface"
      (let [view          (index.views/page request)
            surface       (l/select-one page-surface/PageSurface view)
            surface-attrs (some-> surface l/attrs)
            toolbar       (::page-surface/toolbar surface-attrs)
            toolbar-attrs (some-> toolbar l/attrs)
            breadcrumb    (::page-toolbar/breadcrumb toolbar-attrs)
            mobile-back   (::page-toolbar/mobile-back toolbar-attrs)
            actions       (::page-toolbar/actions toolbar-attrs)
            header        (l/select-one page-header/PageHeader surface)]
        (is (= :wide (::page-surface/width surface-attrs)))
        (is (= [:home :members/title]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem breadcrumb))))
        (is (= {:href  "/"
                :label :home}
               {:href  (some-> (l/select-one button/BackButton mobile-back) l/attrs :href)
                :label (some-> (l/select-one button/BackButton mobile-back)
                               l/attrs :label l/first-child)}))
        (is (= [{:href  "/members/invite"
                 :label :members/invite-member}]
               (mapv (fn [action]
                       {:href  (:href (l/attrs action))
                        :label (some-> (l/select-one :i18n/tr action) l/first-child)})
                     (l/select button/Button actions))))
        (is (= :members/title
               (some-> header l/attrs ::page-header/title l/first-child)))))

    (testing "the invitation editor owns its lifecycle actions in a standard surface"
      (let [view          (invite.views/page request)
            surface       (l/select-one page-surface/PageSurface view)
            surface-attrs (some-> surface l/attrs)
            toolbar-attrs (some-> surface-attrs ::page-surface/toolbar l/attrs)
            actions       (::page-toolbar/actions toolbar-attrs)
            buttons       (l/select button/Button actions)
            form          (l/select-one "form#member-invite-form" surface)]
        (is (= :standard (::page-surface/width surface-attrs)))
        (is (= [:members/title :members/invite-member]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem
                               (::page-toolbar/breadcrumb toolbar-attrs)))))
        (is (= {:href  "/members"
                :label :members/title}
               {:href  (some-> (l/select-one button/BackButton
                                             (::page-toolbar/mobile-back toolbar-attrs))
                               l/attrs :href)
                :label (some-> (l/select-one button/BackButton
                                             (::page-toolbar/mobile-back toolbar-attrs))
                               l/attrs :label l/first-child)}))
        (is (= [{:form nil
                 :href "/members"
                 :label :action/cancel
                 :type nil}
                {:form "member-invite-form"
                 :href nil
                 :label :members/invite-member
                 :type "submit"}]
               (mapv (fn [action]
                       {:form  (:form (l/attrs action))
                        :href  (:href (l/attrs action))
                        :label (some-> (l/select-one :i18n/tr action) l/first-child)
                        :type  (:type (l/attrs action))})
                     buttons)))
        (is (= "member-invite-form" (some-> form l/attrs :id)))
        (is (= :members/invite-member
               (some-> (l/select-one page-header/PageHeader surface)
                       l/attrs
                       ::page-header/title
                       l/first-child)))))

    (testing "a member detail route uses member context and keeps Download contact secondary"
      (let [view          (detail.views/page (assoc request :path-params {:member-id (str member-id)}))
            surface       (l/select-one page-surface/PageSurface view)
            surface-attrs (some-> surface l/attrs)
            toolbar-attrs (some-> surface-attrs ::page-surface/toolbar l/attrs)
            actions       (::page-toolbar/actions toolbar-attrs)
            overflow      (::page-toolbar/overflow-items toolbar-attrs)
            download      (l/select-one 'wa-dropdown-item overflow)]
        (is (= :wide (::page-surface/width surface-attrs)))
        (is (= [:members/title nil]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem
                               (::page-toolbar/breadcrumb toolbar-attrs)))))
        (is (= "Casey Jones"
               (-> (l/select breadcrumb/BreadcrumbItem
                             (::page-toolbar/breadcrumb toolbar-attrs))
                   second
                   l/text)))
        (is (= {:href  "/members"
                :label :members/title}
               {:href  (some-> (l/select-one button/BackButton
                                             (::page-toolbar/mobile-back toolbar-attrs))
                               l/attrs :href)
                :label (some-> (l/select-one button/BackButton
                                             (::page-toolbar/mobile-back toolbar-attrs))
                               l/attrs :label l/first-child)}))
        (is (= [:action/edit]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select button/Button actions))))
        (is (= {:label :members/download-contact
                :value (str "/member-vcard/" member-id)}
               {:label (some-> (l/select-one :i18n/tr download) l/first-child)
                :value (:value (l/attrs download))}))
        (is (= "Casey Jones"
               (some-> (l/select-one page-header/PageHeader surface)
                       l/attrs
                       ::page-header/title)))))

    (testing "a member detail tab keeps the same page shell"
      (let [view          (detail.views/page
                           (assoc request
                                  :path-params {:member-id         (str member-id)
                                                :member-detail-tab "travel"}))
            surface       (l/select-one page-surface/PageSurface view)
            toolbar-attrs (some-> surface l/attrs ::page-surface/toolbar l/attrs)]
        (is (= :wide (some-> surface l/attrs ::page-surface/width)))
        (is (= [:members/title nil]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem
                               (::page-toolbar/breadcrumb toolbar-attrs)))))
        (is (= "Casey Jones"
               (-> (l/select breadcrumb/BreadcrumbItem
                             (::page-toolbar/breadcrumb toolbar-attrs))
                   second
                   l/text)))))

    (testing "contact editing names the member and binds Save to the existing form"
      (let [view          (detail.views/page
                           (assoc request
                                  :path-params {:member-id (str member-id)}
                                  :page-state {:member-detail
                                               {:contact {:active       true
                                                          :email        "casey@example.test"
                                                          :error        {}
                                                          :member-id    (str member-id)
                                                          :name         "Casey Jones"
                                                          :nick         "Casey"
                                                          :phone        "+43 1 234"
                                                          :section-name ""}}}))
            surface       (l/select-one page-surface/PageSurface view)
            surface-attrs (some-> surface l/attrs)
            toolbar-attrs (some-> surface-attrs ::page-surface/toolbar l/attrs)
            actions       (l/select button/Button (::page-toolbar/actions toolbar-attrs))]
        (is (= :wide (::page-surface/width surface-attrs)))
        (is (= [:members/title nil :action/edit]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem
                               (::page-toolbar/breadcrumb toolbar-attrs)))))
        (is (= [2 3]
               (some-> toolbar-attrs
                       ::page-toolbar/breadcrumb
                       l/attrs
                       ::breadcrumb/max-items)))
        (is (= "Casey Jones"
               (-> (l/select breadcrumb/BreadcrumbItem
                             (::page-toolbar/breadcrumb toolbar-attrs))
                   second
                   l/text)))
        (is (= {:href  member-url
                :label "Casey Jones"}
               {:href  (some-> (l/select-one button/BackButton
                                             (::page-toolbar/mobile-back toolbar-attrs))
                               l/attrs :href)
                :label (some-> (l/select-one button/BackButton
                                             (::page-toolbar/mobile-back toolbar-attrs))
                               l/attrs :label)}))
        (is (= [{:form nil
                 :label :action/cancel
                 :type nil}
                {:form "member-contact-form"
                 :label :action/save
                 :type "submit"}]
               (mapv (fn [action]
                       {:form  (:form (l/attrs action))
                        :label (some-> (l/select-one :i18n/tr action) l/first-child)
                        :type  (:type (l/attrs action))})
                     actions)))
        (is (= "member-contact-form"
               (some-> (l/select-one "form#member-contact-form" surface) l/attrs :id)))
        (is (= "Casey Jones"
               (some-> (l/select-one page-header/PageHeader surface)
                       l/attrs
                       ::page-header/title)))))))
