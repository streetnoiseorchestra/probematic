(ns app.ui2.page-toolbar-test
  (:require
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev.onionpancakes.chassis.core :as c]
   [lookup.core :as l]))

(def breadcrumb-items
  [[breadcrumb/BreadcrumbItem {::breadcrumb/href "/root"} "Root"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section"} "Section"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section/parent"} "Parent"]
   [breadcrumb/BreadcrumbItem "Current"]])

(def breadcrumb-node
  (into [breadcrumb/Breadcrumb {::breadcrumb/label       "Page hierarchy"
                                ::breadcrumb/max-items   nil
                                ::breadcrumb/mobile-mode :trail}]
        breadcrumb-items))

(defn toolbar-view [attrs]
  (c/resolve-alias page-toolbar/PageToolbar attrs []))

(deftest page-toolbar-composes-one-breadcrumb-and-action-regions
  (let [primary-action [:a {:href "/gig/1/log-plays"} "Log Plays"]
        view           (toolbar-view
                        {:id "gig-toolbar"
                         :aria-label "Gig controls"
                         ::page-toolbar/breadcrumb breadcrumb-node
                         ::page-toolbar/actions [primary-action]
                         ::page-toolbar/overflow-label "More gig actions"
                         ::page-toolbar/overflow-items
                         [[:wa-dropdown-item {:value "/gig/1/edit"} "Edit"]]})
        dropdown       (l/select-one :wa-dropdown view)
        trigger        (l/select-one button/Button dropdown)
        trigger-icon   (l/select-one ico/Icon trigger)]
    (is (= {:breadcrumb breadcrumb-node
            :primary-action primary-action
            :dropdown {:placement "bottom-end"}
            :trigger {:slot "trigger"
                      :appearance "plain"
                      :aria-label "More gig actions"}
            :trigger-icon {::ico/library :snoico
                           ::ico/name    :ellipsis}
            :overflow-item {:value "/gig/1/edit"
                            :label "Edit"}}
           {:breadcrumb (l/select-one breadcrumb/Breadcrumb view)
            :primary-action (l/select-one "a[href=/gig/1/log-plays]" view)
            :dropdown (select-keys (l/attrs dropdown) [:placement])
            :trigger (select-keys (l/attrs trigger)
                                  [:slot :appearance :aria-label])
            :trigger-icon (select-keys (l/attrs trigger-icon)
                                       [::ico/library ::ico/name])
            :overflow-item
            (let [item (l/select-one :wa-dropdown-item dropdown)]
              {:value (-> item l/attrs :value)
               :label (l/text item)})}))
    (is (empty? (l/select ".desktop" view)))
    (is (empty? (l/select ".mobile" view)))
    (is (empty? (l/select ".responsive-breadcrumb" view)))))

(deftest page-toolbar-omits-empty-action-and-overflow-regions
  (let [view (toolbar-view
              {:aria-label "Page context"
               ::page-toolbar/breadcrumb breadcrumb-node})]
    (is (= breadcrumb-node
           (l/select-one breadcrumb/Breadcrumb view)))
    (is (empty? (l/select :menu view)))
    (is (empty? (l/select :wa-dropdown view)))))

(deftest page-toolbar-never-reads-or-rewrites-breadcrumb-props
  (doseq [[description source]
          [["explicit nullable and responsive props"
            breadcrumb-node]
           ["an omitted Breadcrumb attribute map"
            (into [breadcrumb/Breadcrumb] breadcrumb-items)]]]
    (testing description
      (let [view   (toolbar-view
                    {:aria-label "Page context"
                     ::page-toolbar/breadcrumb source})
            result (l/select-one breadcrumb/Breadcrumb view)]
        (is (= source result))))))

(deftest page-toolbar-requires-an-actual-breadcrumb
  (doseq [[description attrs]
          [["missing context"
            {:aria-label "Page controls"}]
           ["arbitrary nav"
            {:aria-label "Page controls"
             ::page-toolbar/breadcrumb
             [:nav {:aria-label "Special path"}
              [:a {:href "/files"} "Files"]]}]
           ["arbitrary text node"
            {:aria-label "Page controls"
             ::page-toolbar/breadcrumb [:span "Gigs"]}]]]
    (testing description
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"PageToolbar requires ::breadcrumb/Breadcrumb"
           (toolbar-view attrs))))))

(deftest page-toolbar-publishes-sticky-state-from-a-sibling-sentinel
  (let [view     (toolbar-view
                  {:aria-label "Page context"
                   ::page-toolbar/breadcrumb breadcrumb-node})
        sentinel (l/select-one ".sno-page-toolbar-sentinel" view)
        toolbar  (l/select-one ".sno-page-toolbar" view)
        scroll-listeners
        (->> (keys (l/attrs toolbar))
             (filter #(str/starts-with? (name %) "data-on:scroll")))]
    (is (= {:sentinel
            {:aria-hidden true
             :signal      "false"
             :enter       "$pageToolbarStuck = false"
             :exit        "$pageToolbarStuck = el.getBoundingClientRect().top < 0"}
            :toolbar-stuck-class "$pageToolbarStuck"
            :scroll-listeners    []}
           {:sentinel
            {:aria-hidden (-> sentinel l/attrs :aria-hidden)
             :signal      (-> sentinel l/attrs
                              :data-signals:page-toolbar-stuck)
             :enter       (-> sentinel l/attrs :data-on-intersect)
             :exit        (-> sentinel l/attrs :data-on-intersect__exit)}
            :toolbar-stuck-class (-> toolbar l/attrs :data-class:stuck)
            :scroll-listeners    scroll-listeners}))))

(deftest page-toolbar-css-expands-at-rest-and-reserves-the-safe-lane-when-stuck
  (let [css (slurp "resources/public/css/ui2/page-toolbar.css")]
    (is (re-find #"(?s)\.sno-page-toolbar-sentinel\s*\{.*block-size:\s*var\(--wa-border-width-s\);.*margin-block-end:\s*calc\(-1 \* var\(--wa-border-width-s\)\);"
                 css))
    (is (re-find #"(?s)\.sno-page-toolbar\s*\{.*grid-template-areas:\s*\"context actions\";.*grid-template-columns:\s*minmax\(0, 1fr\) auto;"
                 css))
    (is (re-find #"(?s)@media \(--sno-viewport-s\).*\.sno-page-toolbar\.stuck\s*\{.*grid-template-areas:\s*\"context safe-lane actions\";.*var\(--sno-jump-menu-safe-inline-size\)"
                 css))
    (is (re-find #"(?s)\.sno-page-toolbar\.stuck\s*\{.*--sno-breadcrumb-expanded-item-track:\s*0fr;.*--sno-breadcrumb-expanded-item-opacity:\s*0;.*--sno-breadcrumb-compact-item-track:\s*1fr;.*--sno-breadcrumb-compact-item-opacity:\s*1;.*--sno-breadcrumb-expanded-label-visibility:\s*hidden;.*--sno-breadcrumb-compact-label-visibility:\s*visible;"
                 css))))
