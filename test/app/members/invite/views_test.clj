(ns app.members.invite.views-test
  (:require
   [app.members.invite.views :as views]
   [app.members.invite.workflows :as workflows]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(defn- tr
  ([path]
   (name (last path)))
  ([path _args]
   (name (last path))))

(def request
  {:tr tr
   :params {:invite-code "retry-code"}})

(defn- breadcrumb-parent-context [breadcrumb]
  (let [items  (vec (l/select breadcrumb/BreadcrumbItem breadcrumb))
        parent (nth items (- (count items) 2))
        attrs  (l/attrs parent)]
    {:href  (or (::breadcrumb/href attrs) (:href attrs))
     :label (or (some-> (l/select-one :i18n/tr parent) l/first-child)
                (l/text parent))}))

(deftest retryable-acceptance-renders-the-current-invitation-again-test
  (with-redefs [workflows/setup-account!
                (fn [_req]
                  (throw (ex-info "Retry acceptance"
                                  {:reason :acceptance-retry})))
                views/load-invite
                (fn [_req]
                  {:member {:member/email "alice@example.com"}
                   :invite-code "retry-code"})]
    (let [{:keys [status body]} (views/invite-accept-post request)]
      (is (= 200 status))
      (is (str/includes?
           body
           "<form action=\"/invite-accept\" method=\"POST\">"))
      (is (str/includes?
           body
           (str "<input type=\"hidden\" name=\"invite-code\" "
                "value=\"retry-code\">"))))))

(deftest accepted-receipt-renders-success-without-another-create-form-test
  (with-redefs [views/load-invite
                (fn [_req]
                  {:member {:member/email "alice@example.com"}
                   :invite-accepted? true})
                views/login-link
                (fn [_req _member]
                  "https://example.test/login")]
    (let [{:keys [status body]} (views/invite-accept request)]
      (is (= 200 status))
      (is (str/includes? body "account-created-title"))
      (is (str/includes? body "https://example.test/login"))
      (is (not (str/includes? body "<form"))))))

(deftest operator-required-error-escapes-the-view-test
  (let [error (ex-info "Operator required" {:reason :operator-required})]
    (with-redefs [workflows/setup-account! (fn [_req] (throw error))]
      (is (identical? error
                      (try
                        (views/invite-accept-post request)
                        nil
                        (catch Throwable exception
                          exception)))))))

(deftest invitation-editor-owns-its-lifecycle-actions-test
  (let [{:keys [conn]} (tc/new-system "member-invite-view")
        request        {::r/router       (r/router ["/act" {:name :app.routes.datastar/act}])
                        :current-locale :en
                        :db             (d/db conn)
                        :page-state     {}
                        :session        {:session/roles #{}}
                        :system         {:env {}}
                        :tr             tr}
        view           (views/page request)
        surface        (l/select-one page-surface/PageSurface view)
        surface-attrs  (some-> surface l/attrs)
        toolbar-attrs  (some-> surface-attrs ::page-surface/toolbar l/attrs)
        breadcrumb     (::page-toolbar/breadcrumb toolbar-attrs)
        actions        (::page-toolbar/actions toolbar-attrs)
        buttons        (l/select button/Button actions)
        form           (l/select-one "form#member-invite-form" surface)
        signals-attrs  (some-> (l/select-one "[data-signals]" surface) l/attrs)
        validation-action
        (fn [field]
          (str "$member-invite.validate-field = '"
               field
               "'; @post('/act?ns=app.members.invite.actions&kw=validate-member-invite-field')"))
        input-validation
        (into
         {}
         (map
          (fn [input]
            (let [attrs (l/attrs input)]
              [(:data-bind attrs)
               (select-keys attrs
                            [:data-on:blur
                             :data-on:input__debounce.500ms])])))
         (l/select "wa-input" form))
        section-attrs (some-> (l/select-one "wa-select" form) l/attrs)]
    (is (= :standard (::page-surface/width surface-attrs)))
    (is (= [:members/title :members/invite-member]
           (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                 (l/select breadcrumb/BreadcrumbItem breadcrumb))))
    (is (= {:href  "/members"
            :label :members/title}
           (breadcrumb-parent-context breadcrumb)))
    (is (not (contains? toolbar-attrs ::page-toolbar/mobile-back)))
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
    (is (= "data-signals" (:data-preserve-attr signals-attrs)))
    (is (not (str/includes? (:data-signals signals-attrs) "member-id")))
    (is (every? #(= "value" (:data-preserve-attr (l/attrs %)))
                (l/select "wa-input" form)))
    (is (= :members/invite-member
           (some-> (l/select-one page-header/PageHeader surface)
                   l/attrs
                   ::page-header/title
                   l/first-child)))
    (is (= {"member-invite.name"
            {:data-on:blur                    (validation-action "name")
             :data-on:input__debounce.500ms (validation-action "name")}
            "member-invite.nick"
            {:data-on:blur                    (validation-action "nick")
             :data-on:input__debounce.500ms (validation-action "nick")}
            "member-invite.email"
            {:data-on:blur                    (validation-action "email")
             :data-on:input__debounce.500ms (validation-action "email")}
            "member-invite.username"
            {:data-on:blur                    (validation-action "username")
             :data-on:input__debounce.500ms (validation-action "username")}
            "member-invite.phone"
            {:data-on:blur                    (validation-action "phone")
             :data-on:input__debounce.500ms (validation-action "phone")}}
           input-validation))
    (is (= {:data-on:blur (validation-action "section-name")}
           (select-keys section-attrs
                        [:data-on:blur :data-on:change])))
    (is (= "value" (:data-preserve-attr section-attrs)))
    (let [error-view
          (views/page
           (assoc request
                  :page-state
                  {:member-invite
                   {:error
                    {:email        {:error "Email is already in use."}
                     :section-name {:error "Section is required."}}}}))
          error-form    (l/select-one "form#member-invite-form" error-view)
          email-attrs   (some (fn [input]
                                (let [attrs (l/attrs input)]
                                  (when (= "member-invite.email"
                                           (:data-bind attrs))
                                    attrs)))
                              (l/select "wa-input" error-form))
          section-attrs (some-> (l/select-one "wa-select" error-form)
                                l/attrs)]
      (is (= {:email   {:hint         "Email is already in use."
                        :data-invalid "true"}
              :section {:hint         "Section is required."
                        :data-invalid "true"}}
             {:email   (select-keys email-attrs [:hint :data-invalid])
              :section (select-keys section-attrs
                                    [:hint :data-invalid])})))))
