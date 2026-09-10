(ns app.members.views-test
  (:require
   [app.members.detail.views :as detail.views]
   [app.members.index.views :as index.views]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [jsonista.core :as j]
   [lookup.core :as l]
   [reitit.core :as r]
   [ring.util.codec :as codec]))

(defn- breadcrumb-parent-context [breadcrumb]
  (let [items  (vec (l/select breadcrumb/BreadcrumbItem breadcrumb))
        parent (nth items (- (count items) 2))
        attrs  (l/attrs parent)]
    {:href  (or (::breadcrumb/href attrs) (:href attrs))
     :label (or (some-> (l/select-one :i18n/tr parent) l/first-child)
                (l/text parent))}))

(deftest member-page-shells
  (let [{:keys [conn member-id]} (tc/new-system "member-page-shells")
        invited-member-id        (random-uuid)
        expired-member-id        (random-uuid)
        revoked-member-id        (random-uuid)
        _                        @(d/transact
                                   conn
                                   [{:member/member-id member-id
                                     :member/name      "Casey Jones"
                                     :member/nick      "Casey"
                                     :member/email     "casey@example.test"
                                     :member/phone     "+43 1 234"
                                     :member/username  "casey"
                                     :member/active?   true}
                                    {:member/member-id         invited-member-id
                                     :member/name              "Pending Invite"
                                     :member/email             "pending@example.test"
                                     :member/username          "pending-invite"
                                     :member/invite-code       "pending-code"
                                     :member/invite-expires-at #inst "2099-01-01T00:00:00.000-00:00"
                                     :member/invite-status     :member.invite.status/pending
                                     :member/invite-generation 1
                                     :member/invite-status-at  #inst "2026-07-16T07:00:00.000-00:00"}
                                    {:member/member-id         expired-member-id
                                     :member/name              "Expired Invite"
                                     :member/email             "expired@example.test"
                                     :member/username          "expired-invite"
                                     :member/invite-code       "expired-code"
                                     :member/invite-expires-at #inst "2000-01-01T00:00:00.000-00:00"
                                     :member/invite-status     :member.invite.status/pending
                                     :member/invite-generation 1
                                     :member/invite-status-at  #inst "2000-01-01T00:00:00.000-00:00"}
                                    {:member/member-id         revoked-member-id
                                     :member/name              "Revoked Invite"
                                     :member/email             "revoked@example.test"
                                     :member/username          "revoked-invite"
                                     :member/invite-status     :member.invite.status/revoked
                                     :member/invite-generation 5
                                     :member/invite-status-at  #inst "2026-07-16T08:00:00.000-00:00"}])
        tr                       (fn
                                   ([path]
                                    (name (last path)))
                                   ([path _args]
                                    (name (last path))))
        request                  {::r/router      (r/router ["/act" {:name :app.routes.datastar/act}])
                                  :current-locale :en
                                  :db             (d/db conn)
                                  :page-state     {}
                                  :app/session    {:session/roles #{}}
                                  :system         {:env {:app-base-url "https://members.example.test"}}
                                  :tr             tr}
        member-url               (str "/member/" member-id)]
    (testing "only usable invitations can be copied, with plain copy and resend controls"
      (let [view   (index.views/page request)
            copies (l/select :wa-copy-button view)
            copy   (first copies)
            resend (->> (l/select button/Button view)
                        (filter #(= :action/resend-invitation
                                    (some-> (l/select-one :i18n/tr %) l/first-child)))
                        first)]
        (is (= {:copy-count        1
                :url               "https://members.example.test/invite-accept?code=pending-code"
                :copy-appearance   "plain"
                :resend-appearance "plain"}
               {:copy-count        (count copies)
                :url               (:value (l/attrs copy))
                :copy-appearance   (:appearance (l/attrs (l/select-one button/Button copy)))
                :resend-appearance (:appearance (l/attrs resend))}))))
    (testing "the member header, filters and table precede the invitations section"
      (let [view    (index.views/page request)
            content (l/select-one "[data-signals]" view)]
        (is (= [page-header/PageHeader :div :div :div :section]
               (mapv first (drop 2 content))))))
    (testing "pagination offers the supported sizes and disables navigation for a single page"
      (let [view    (index.views/page request)
            nav     (->> (l/select :nav view)
                         (filter #(= [:i18n/tr :pagination] (:aria-label (l/attrs %))))
                         first)
            buttons (l/select button/Button nav)]
        (is (= {:sizes    ["10" "30" "50" "100"]
                :disabled [true true]
                :summary  {:range-start 1 :range-end 1 :total-results 1}}
               {:sizes    (mapv #(-> % l/attrs :value) (l/select :wa-dropdown-item nav))
                :disabled (mapv #(-> % l/attrs :disabled) [(first buttons) (last buttons)])
                :summary  (->> (l/select :i18n/tr nav)
                               (filter #(= :pagination-summary (second %)))
                               first
                               last)}))))
    (testing "the URL overrides stale page state and table navigation preserves its filters"
      (let [view          (index.views/page
                           (assoc request
                                  :query-params {"search"     "Casey" "filter-preset" "active"
                                                 "sort-field" "name"  "sort-order"    "asc"
                                                 "page"       "2"     "page-size"     "10"}
                                  :page-state {:members-index {:search "no match" :filter-preset "inactive"}}))
            forms         (l/select :form view)
            search-form   (first forms)
            filter-form   (second forms)
            hidden-fields (fn [form]
                            (into {} (map (fn [input]
                                            (let [{:keys [name value]} (l/attrs input)]
                                              [name (str value)]))
                                          (l/select "input[type=hidden]" form))))
            sort-url      (:href (l/attrs (l/select-one "a.wa-link-plain" view)))]
        (is (= {:search        "Casey"                                                         :filter "active"
                :search-hidden {"filter-preset" "active" "sort-field" "name"
                                "sort-order"    "asc"    "page"       "1"    "page-size" "10"}
                :filter-hidden {"search"     "Casey" "sort-field" "name"
                                "sort-order" "asc"   "page"       "1"    "page-size" "10"}
                :sort-query    {"search"     "Casey" "filter-preset" "active"
                                "sort-field" "name"  "sort-order"    "desc"
                                "page"       "1"     "page-size"     "10"}
                :form-methods  ["get" "get"]}
               {:search        (:value (l/attrs (l/select-one :wa-input search-form)))
                :filter        (:value (l/attrs (l/select-one :wa-select filter-form)))
                :search-hidden (hidden-fields search-form)
                :filter-hidden (hidden-fields filter-form)
                :sort-query    (codec/form-decode (second (str/split sort-url #"\?" 2)))
                :form-methods  (mapv #(-> % l/attrs :method) forms)}))))
    (testing "the directory uses a standard Members surface"
      (let [view           (index.views/page request)
            surface        (l/select-one page-surface/PageSurface view)
            surface-attrs  (some-> surface l/attrs)
            toolbar        (::page-surface/toolbar surface-attrs)
            toolbar-attrs  (some-> toolbar l/attrs)
            breadcrumb     (::page-toolbar/breadcrumb toolbar-attrs)
            actions        (::page-toolbar/actions toolbar-attrs)
            header         (l/select-one page-header/PageHeader surface)
            invite-signals (-> (l/select-one "[data-signals]" surface)
                               l/attrs
                               :data-signals
                               j/read-value
                               (get "invite"))
            reissue-click  (->> (l/select button/Button surface)
                                (some #(when (= :action/reissue-invitation
                                                (some-> (l/select-one :i18n/tr %)
                                                        l/first-child))
                                         (-> % l/attrs :data-on:click))))]
        (is (= :standard (or (::page-surface/width surface-attrs) :standard)))
        (is (= [:home :members/title]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem breadcrumb))))
        (is (= {:href  "/"
                :label :home}
               (breadcrumb-parent-context breadcrumb)))
        (is (not (contains? toolbar-attrs ::page-toolbar/mobile-back)))
        (is (= [{:href  "/members/invite"
                 :label :members/invite-member}]
               (mapv (fn [action]
                       {:href  (:href (l/attrs action))
                        :label (some-> (l/select-one :i18n/tr action) l/first-child)})
                     (l/select button/Button actions))))
        (is (= :members/title
               (some-> header l/attrs ::page-header/title l/first-child)))
        (is (= {:signals {"action"     nil
                          "code"       nil
                          "member-id"  nil
                          "generation" nil
                          "inflight"   false}
                :reissue-click
                "$invite.code = \"expired-code\"; $invite.action = \"reissue\"; $invite.inflight = true; @post(\"/act?ns=app.members.index.actions&kw=reissue-invitation\")"}
               {:signals       invite-signals
                :reissue-click reissue-click}))
        (is (some #(= "Pending Invite" (l/text %))
                  (l/select "td" surface)))
        (is (some #(= "Expired Invite" (l/text %))
                  (l/select "td" surface)))
        (is (some #(= "Revoked Invite" (l/text %))
                  (l/select "td" surface)))
        (is (= {:action/delete             2
                :action/reissue-invitation 2
                :action/resend-invitation  1}
               (->> (l/select button/Button surface)
                    (keep #(some-> (l/select-one :i18n/tr %)
                                   l/first-child))
                    (filter #{:action/delete
                              :action/reissue-invitation
                              :action/resend-invitation})
                    frequencies)))))

    (testing "a revoked invitation exposes only a generation-guarded reissue action"
      (let [surface (l/select-one page-surface/PageSurface
                                  (index.views/page request))
            actions (->> (l/select button/Button surface)
                         (filter #(re-find
                                   #"reissue-revoked-invitation"
                                   (or (-> % l/attrs :data-on:click) ""))))]
        (is (= [:action/reissue-invitation]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     actions)))
        (is (= (str "$invite.member-id = \"" revoked-member-id
                    "\"; $invite.generation = 5; "
                    "$invite.action = \"reissue-revoked\"; "
                    "$invite.inflight = true; "
                    "@post(\"/act?ns=app.members.index.actions&kw="
                    "reissue-revoked-invitation\")")
               (-> actions first l/attrs :data-on:click)))))

    (testing "a member detail route uses member context and keeps Download contact secondary"
      (let [view          (detail.views/page (assoc request :path-params {:member-id (str member-id)}))
            surface       (l/select-one page-surface/PageSurface view)
            surface-attrs (some-> surface l/attrs)
            toolbar-attrs (some-> surface-attrs ::page-surface/toolbar l/attrs)
            breadcrumb    (::page-toolbar/breadcrumb toolbar-attrs)
            actions       (::page-toolbar/actions toolbar-attrs)
            overflow      (::page-toolbar/overflow-items toolbar-attrs)
            download      (l/select-one 'wa-dropdown-item overflow)]
        (is (= :standard (or (::page-surface/width surface-attrs) :standard)))
        (is (= [:members/title nil]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem
                               breadcrumb))))
        (is (= "Casey Jones"
               (-> (l/select breadcrumb/BreadcrumbItem
                             breadcrumb)
                   second
                   l/text)))
        (is (= {:href  "/members"
                :label :members/title}
               (breadcrumb-parent-context breadcrumb)))
        (is (not (contains? toolbar-attrs ::page-toolbar/mobile-back)))
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
        (is (= :standard
               (or (some-> surface l/attrs ::page-surface/width) :standard)))
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
            breadcrumb    (::page-toolbar/breadcrumb toolbar-attrs)
            actions       (l/select button/Button (::page-toolbar/actions toolbar-attrs))]
        (is (= :standard (or (::page-surface/width surface-attrs) :standard)))
        (is (= [:members/title nil :action/edit]
               (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                     (l/select breadcrumb/BreadcrumbItem
                               breadcrumb))))
        (is (= [2 3]
               (some-> toolbar-attrs
                       ::page-toolbar/breadcrumb
                       l/attrs
                       ::breadcrumb/max-items)))
        (is (= "Casey Jones"
               (-> (l/select breadcrumb/BreadcrumbItem
                             breadcrumb)
                   second
                   l/text)))
        (is (= {:href  member-url
                :label "Casey Jones"}
               (breadcrumb-parent-context breadcrumb)))
        (is (not (contains? toolbar-attrs ::page-toolbar/mobile-back)))
        (is (= [{:form  nil
                 :label :action/cancel
                 :type  nil}
                {:form  "member-contact-form"
                 :label :action/save
                 :type  "submit"}]
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
