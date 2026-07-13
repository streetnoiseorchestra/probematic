(ns app.file-browser.views-test
  (:require
   [app.file-browser.views :as views]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]))

(deftest selected-file-path-test
  (is (= {"/foo/bar"        "foo/bar"
          "/foo//bar"       "foo//bar"
          "/../foo"         "foo"
          "/foo/../../bar"  "bar"}
         (into {}
               (map (fn [path] [path (@#'views/selected-file-path {:path path})]))
               ["/foo/bar" "/foo//bar" "/../foo" "/foo/../../bar"]))))

(deftest component-paths-test
  (is (= {"/foo/bar"   ["/foo" "/foo/bar"]
          "/foo//bar"  ["/foo" "/foo/" "/foo//bar"]}
         (into {}
               (map (fn [path] [path (vec (@#'views/component-paths path))]))
               ["/foo/bar" "/foo//bar"]))))

(deftest choose-file-page-surface
  (testing "The closed full-page picker uses a standard workspace with its initial action in the toolbar."
    (let [view          (views/page {::r/router   (r/router ["/act" {:name :app.routes.datastar/act}])
                                     :page-state {}
                                     :system     {:env {:nextcloud {:sheet-music-path "/scores"
                                                                    :current-songs-path "/scores/current"}}}})
          surface       (l/select-one page-surface/PageSurface view)
          surface-attrs (l/attrs surface)
          toolbar-attrs (-> surface-attrs ::page-surface/toolbar l/attrs)
          breadcrumb    (::page-toolbar/breadcrumb toolbar-attrs)
          mobile-back   (::page-toolbar/mobile-back toolbar-attrs)
          action        (l/select-one button/Button (::page-toolbar/actions toolbar-attrs))
          header        (l/select-one page-header/PageHeader surface)]
      (is (= {:width       :standard
              :breadcrumbs [:home :files/choose-file]
              :mobile      {:href "/" :label :home}
              :action      {:label :files/choose-file
                            :opens-picker? true}
              :heading     :files/choose-file
              :panel-open? false}
             {:width       (::page-surface/width surface-attrs)
              :breadcrumbs (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                                 (l/select breadcrumb/BreadcrumbItem breadcrumb))
              :mobile      {:href  (-> (l/select-one button/Button mobile-back) l/attrs :href)
                            :label (some-> (l/select-one :i18n/tr mobile-back) l/first-child)}
              :action      {:label (some-> (l/select-one :i18n/tr action) l/first-child)
                            :opens-picker? (str/includes? (:data-on:click (l/attrs action))
                                                          "ns=app.file-browser.actions&kw=open-picker")}
              :heading     (some-> header l/attrs ::page-header/title l/first-child)
              :panel-open? (boolean (l/select-one ".file-browser-panel" surface))})))))
