(ns app.ui2.page-toolbar-test
  (:require
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.test :refer [deftest is testing]]
   [dev.onionpancakes.chassis.core :as c]
   [lookup.core :as l]))

(def breadcrumb-items
  [[breadcrumb/BreadcrumbItem {::breadcrumb/href "/root"} "Root"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section"} "Section"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section/parent"} "Parent"]
   [breadcrumb/BreadcrumbItem "Current"]])

(defn toolbar-view [attrs]
  (c/resolve-alias page-toolbar/PageToolbar attrs []))

(deftest page-toolbar-composes-context-actions-and-accessible-overflow
  (let [breadcrumb-node [:nav {:aria-label "Breadcrumb"}
                         [:a {:href "/gigs"} "Gigs"]]
        mobile-back     [:a {:href "/gigs"} "Back to gigs"]
        primary-action  [:a {:href "/gig/1/log-plays"} "Log Plays"]
        view            (toolbar-view
                         {:id "gig-toolbar"
                          :aria-label "Gig controls"
                          ::page-toolbar/breadcrumb breadcrumb-node
                          ::page-toolbar/mobile-back mobile-back
                          ::page-toolbar/actions [primary-action]
                          ::page-toolbar/overflow-label "More gig actions"
                          ::page-toolbar/overflow-items
                          [[:wa-dropdown-item {:value "/gig/1/edit"} "Edit"]]})
        dropdown        (l/select-one :wa-dropdown view)
        trigger         (l/select-one button/Button dropdown)
        trigger-icon    (l/select-one ico/Icon trigger)]
    (is (= {:breadcrumb breadcrumb-node
            :mobile-back mobile-back
            :primary-action primary-action
            :dropdown {:placement "bottom-end"}
            :trigger {:slot "trigger"
                      :appearance "plain"
                      :aria-label "More gig actions"}
            :trigger-icon {::ico/library :snoico
                           ::ico/name    :ellipsis}
            :overflow-item {:value "/gig/1/edit"
                            :label "Edit"}}
           {:breadcrumb (l/select-one :nav view)
            :mobile-back (some #(when (= "Back to gigs" (l/text %)) %)
                               (l/select "a[href=/gigs]" view))
            :primary-action (l/select-one "a[href=/gig/1/log-plays]" view)
            :dropdown (select-keys (l/attrs dropdown) [:placement])
            :trigger (select-keys (l/attrs trigger)
                                  [:slot :appearance :aria-label])
            :trigger-icon (select-keys (l/attrs trigger-icon)
                                       [::ico/library ::ico/name])
            :overflow-item
            (let [item (l/select-one :wa-dropdown-item dropdown)]
              {:value (-> item l/attrs :value)
               :label (l/text item)})}))))

(deftest page-toolbar-omits-empty-action-and-overflow-regions
  (let [context [:span "Gigs"]
        view    (toolbar-view
                 {:aria-label "Page context"
                  ::page-toolbar/breadcrumb context
                  ::page-toolbar/mobile-back [:a {:href "/gigs"} "Gigs"]})]
    (is (= context (l/select-one :span view)))
    (is (empty? (l/select :menu view)))
    (is (empty? (l/select :wa-dropdown view)))))

(deftest page-toolbar-adds-only-missing-breadcrumb-collapse-defaults
  (doseq [[description supplied expected]
          [["both defaults"
            {}
            {::breadcrumb/max-items 3
             ::breadcrumb/items-before-collapse 0}]
           ["only max-items"
            {::breadcrumb/max-items 5}
            {::breadcrumb/max-items 5
             ::breadcrumb/items-before-collapse 0}]
           ["only items-before-collapse"
            {::breadcrumb/items-before-collapse 1}
            {::breadcrumb/max-items 3
             ::breadcrumb/items-before-collapse 1}]
           ["no defaults over explicit options"
            {::breadcrumb/max-items 5
             ::breadcrumb/items-before-collapse 2}
            {::breadcrumb/max-items 5
             ::breadcrumb/items-before-collapse 2}]]]
    (testing description
      (let [source (into [breadcrumb/Breadcrumb
                          (assoc supplied ::breadcrumb/label "Hierarchy")]
                         breadcrumb-items)
            view   (toolbar-view
                    {:aria-label "Page context"
                     ::page-toolbar/breadcrumb source})
            result (l/select-one breadcrumb/Breadcrumb view)]
        (is (= (assoc expected ::breadcrumb/label "Hierarchy")
               (l/attrs result)))
        (is (= breadcrumb-items
               (vec (drop 2 result)))))))
  (testing "the Breadcrumb may omit its attribute map"
    (let [source (into [breadcrumb/Breadcrumb] breadcrumb-items)
          view   (toolbar-view
                  {:aria-label "Page context"
                   ::page-toolbar/breadcrumb source})
          result (l/select-one breadcrumb/Breadcrumb view)]
      (is (= {::breadcrumb/max-items 3
              ::breadcrumb/items-before-collapse 0}
             (l/attrs result)))
      (is (= breadcrumb-items
             (vec (drop 2 result)))))))

(deftest page-toolbar-promotes-responsive-breadcrumbs-to-both-breakpoints
  (let [source      (into [breadcrumb/Breadcrumb
                           {::breadcrumb/max-items [2 3]}]
                          breadcrumb-items)
        mobile-back [:a {:href "/root/section/parent"} "Parent"]
        view        (toolbar-view
                     {:aria-label "Page context"
                      ::page-toolbar/breadcrumb source
                      ::page-toolbar/mobile-back mobile-back})
        responsive  (l/select-one ".responsive-breadcrumb" view)
        result      (l/select-one breadcrumb/Breadcrumb responsive)]
    (is (= {::breadcrumb/max-items [2 3]
            ::breadcrumb/items-before-collapse 0}
           (l/attrs result)))
    (is (empty? (l/select ".desktop" view)))
    (is (empty? (l/select ".mobile" view)))
    (is (empty? (l/select "a[href=/root/section/parent]" view)))))

(deftest page-toolbar-leaves-other-context-nodes-unchanged
  (let [context [:nav {:class "specialist-path"
                       :data-max-items 9}
                 [:a {:href "/files"} "Files"]]
        view    (toolbar-view
                 {:aria-label "File controls"
                  ::page-toolbar/breadcrumb context})
        result  (l/select-one "nav.specialist-path" view)]
    (is (= {:attrs {:class #{"specialist-path"}
                    :data-max-items 9}
            :href "/files"
            :label "Files"}
           {:attrs (l/attrs result)
            :href (-> (l/select-one :a result) l/attrs :href)
            :label (-> (l/select-one :a result) l/text)}))
    (is (empty? (l/select breadcrumb/Breadcrumb view)))))
