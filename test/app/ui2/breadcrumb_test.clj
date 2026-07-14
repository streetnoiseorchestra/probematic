(ns app.ui2.breadcrumb-test
  (:require
   [app.icons :as icons]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [dev.onionpancakes.chassis.core :as c]
   [lookup.core :as l]))

(def test-manifest
  (delay
    (icons/build-sprite-manifest
     [{:id          :snoico
       :source-root "public/img/snoico"
       :icons       [:ellipsis]}
      {:id          :phosphor
       :source-root "public/img/phosphor/phosphor-regular"
       :icons       [:caret-right]}])))

(defn install-test-manifest [f]
  (let [manifest_ (deref #'icons/sprite-manifest_)
        original  @manifest_]
    (icons/install-sprite-manifest! @test-manifest)
    (try
      (f)
      (finally
        (reset! manifest_ original)))))

(use-fixtures :each install-test-manifest)

(def six-items
  [[breadcrumb/BreadcrumbItem {::breadcrumb/href "/root"} "Root"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section"} "Section"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section/collection"} "Collection"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section/collection/item"} "Item"]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/root/section/collection/item/parent"} "Parent"]
   [breadcrumb/BreadcrumbItem "Current"]])

(defn breadcrumb-view [attrs items]
  (c/resolve-alias breadcrumb/Breadcrumb attrs items))

(defn visible-labels [view]
  (mapv l/text (l/select breadcrumb/BreadcrumbItem view)))

(defn dropdown-labels [view]
  (mapv l/text (l/select :wa-dropdown-item view)))

(deftest unrestricted-and-within-limit-breadcrumbs-remain-expanded
  (doseq [[description attrs items]
          [["without a limit" {} six-items]
           ["when the count equals the limit"
            {::breadcrumb/max-items 3}
            (take-last 3 six-items)]
           ["with one item"
            {::breadcrumb/max-items 2}
            [(last six-items)]]
           ["with no items"
            {::breadcrumb/max-items 2}
            []]]]
    (testing description
      (let [view    (breadcrumb-view attrs items)
            current (l/select "[aria-current=page]" view)]
        (is (= (mapv l/text items)
               (visible-labels view)))
        (is (empty? (l/select :wa-dropdown view)))
        (is (= (if (seq items) 1 0)
               (count current)))
        (when (seq items)
          (is (= (l/text (last items))
                 (l/text (first current)))))))))

(deftest collapse-without-preserved-leading-items-keeps-newest-trail
  (let [view           (breadcrumb-view {::breadcrumb/max-items 3}
                                        six-items)
        positions      (l/select :li view)
        dropdown       (l/select-one :wa-dropdown view)
        dropdown-items (l/select :wa-dropdown-item dropdown)]
    (is (= {:visible-labels  ["Parent" "Current"]
            :dropdown-labels ["Root" "Section" "Collection" "Item"]
            :dropdown-values ["/root"
                              "/root/section"
                              "/root/section/collection"
                              "/root/section/collection/item"]
            :position-count  3
            :collapse-count  1
            :separator-count 2
            :current-count   1}
           {:visible-labels  (visible-labels view)
            :dropdown-labels (mapv l/text dropdown-items)
            :dropdown-values (mapv #(-> % l/attrs :value) dropdown-items)
            :position-count  (count positions)
            :collapse-count  (count (l/select "li.collapse" view))
            :separator-count (count (l/select ".separator" view))
            :current-count   (count (l/select "[aria-current=page]" view))}))
    (is (empty? (l/select ".separator" (first positions))))
    (is (every? #(= 1 (count (l/select ".separator" %)))
                (rest positions)))))

(deftest collapse-one-item-beyond-limit-uses-one-collapse-position
  (let [view (breadcrumb-view {::breadcrumb/max-items 3}
                              (take-last 4 six-items))]
    (is (= {:visible-labels  ["Parent" "Current"]
            :dropdown-labels ["Collection" "Item"]
            :position-count  3
            :collapse-count  1}
           {:visible-labels  (visible-labels view)
            :dropdown-labels (dropdown-labels view)
            :position-count  (count (l/select :li view))
            :collapse-count  (count (l/select "li.collapse" view))}))))

(deftest collapse-with-one-preserved-leading-item-keeps-root-and-current
  (let [view (breadcrumb-view {::breadcrumb/max-items            3
                               ::breadcrumb/items-before-collapse 1}
                              six-items)]
    (is (= {:visible-labels  ["Root" "Current"]
            :dropdown-labels ["Section" "Collection" "Item" "Parent"]
            :position-count  3
            :separator-count 2}
           {:visible-labels  (visible-labels view)
            :dropdown-labels (dropdown-labels view)
            :position-count  (count (l/select :li view))
            :separator-count (count (l/select ".separator" view))}))))

(deftest responsive-max-items-render-mobile-and-desktop-trails
  (let [view         (breadcrumb-view {::breadcrumb/max-items [3 4]}
                                      six-items)
        mobile-list  (l/select-one ".sno-breadcrumb-list-mobile" view)
        desktop-list (l/select-one ".sno-breadcrumb-list-desktop" view)]
    (is (= {:mobile  {:visible-labels  ["Parent" "Current"]
                      :dropdown-labels ["Root" "Section" "Collection" "Item"]
                      :position-count  3
                      :current-count   1}
            :desktop {:visible-labels  ["Item" "Parent" "Current"]
                      :dropdown-labels ["Root" "Section" "Collection"]
                      :position-count  4
                      :current-count   1}}
           {:mobile  {:visible-labels  (visible-labels mobile-list)
                      :dropdown-labels (dropdown-labels mobile-list)
                      :position-count  (count (l/select :li mobile-list))
                      :current-count   (count (l/select "[aria-current=page]"
                                                        mobile-list))}
            :desktop {:visible-labels  (visible-labels desktop-list)
                      :dropdown-labels (dropdown-labels desktop-list)
                      :position-count  (count (l/select :li desktop-list))
                      :current-count   (count (l/select "[aria-current=page]"
                                                        desktop-list))}}))
    (is (= 1 (count (l/select :nav view))))))

(deftest collapse-dropdown-has-one-delegated-navigation-contract
  (let [view           (breadcrumb-view {::breadcrumb/max-items 3}
                                        six-items)
        dropdown       (l/select-one :wa-dropdown view)
        trigger        (l/select-one button/Button dropdown)
        trigger-icon   (l/select-one ico/Icon trigger)
        accessible-name (l/select-one :i18n/tr trigger)
        dropdown-items (l/select :wa-dropdown-item dropdown)]
    (is (= {:dropdown {:placement "bottom-start"
                       :data-on:wa-select
                       "window.location.href = evt.detail.item.value"}
            :trigger  {:slot "trigger"
                       :appearance "plain"
                       :size "s"}
            :trigger-icon {::ico/library :snoico
                           ::ico/name    :ellipsis}
            :accessible-name
            [:i18n/tr :action/show-hidden-breadcrumb-items {:count 4}]}
           {:dropdown (select-keys (l/attrs dropdown)
                                   [:placement :data-on:wa-select])
            :trigger (select-keys (l/attrs trigger)
                                  [:slot :appearance :size])
            :trigger-icon (select-keys (l/attrs trigger-icon)
                                       [::ico/library ::ico/name])
            :accessible-name accessible-name}))
    (is (= 1 (count (l/select "[data-on:wa-select]" dropdown))))
    (is (every? #(empty? (l/select "[data-on:click]" %))
                dropdown-items))
    (is (every? #(empty? (l/select ico/Icon %))
                dropdown-items))))

(deftest collapsing-rejects-impossible-option-combinations
  (testing "a responsive limit must contain mobile and desktop values"
    (binding [uic/*validate-opts* false]
      (doseq [max-items [[3] [3 0] [3 4 5]]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"positive integer or \[mobile desktop\] pair"
             (breadcrumb-view {::breadcrumb/max-items max-items}
                              six-items))))))
  (testing "the trigger and current page each require a visible position"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"max-items must be at least 2"
         (breadcrumb-view {::breadcrumb/max-items 1}
                          (take 2 six-items)))))
  (testing "the leading count cannot consume the trigger or current position"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"items-before-collapse must not exceed max-items - 2"
         (breadcrumb-view {::breadcrumb/max-items            3
                           ::breadcrumb/items-before-collapse 2}
                          six-items)))))

(deftest collapsing-rejects-a-non-navigable-hidden-ancestor
  (let [items [[breadcrumb/BreadcrumbItem {::breadcrumb/href "/root"} "Root"]
               [breadcrumb/BreadcrumbItem "Missing link"]
               [breadcrumb/BreadcrumbItem {::breadcrumb/href "/parent"} "Parent"]
               [breadcrumb/BreadcrumbItem "Current"]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Collapsed breadcrumb items require an internal href"
         (breadcrumb-view {::breadcrumb/max-items 3} items)))))

(deftest collapsed-ancestor-hrefs-must-remain-on-the-current-origin
  (testing "root-relative paths may include queries and fragments"
    (doseq [href ["/ordinary/path"
                  "/ordinary/path?filter=all#results"
                  "/#section"]]
      (let [items [[breadcrumb/BreadcrumbItem {::breadcrumb/href href} "Root"]
                   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/parent"} "Parent"]
                   [breadcrumb/BreadcrumbItem "Current"]]]
        (is (= [href "/parent"]
               (->> (breadcrumb-view {::breadcrumb/max-items 2} items)
                    (l/select :wa-dropdown-item)
                    (mapv #(-> % l/attrs :value))))))))
  (testing "authority, relative, backslash, and control-character forms are rejected"
    (doseq [href ["relative/path"
                  "https://evil.example/path"
                  "//evil.example/path"
                  "///evil.example/path"
                  "/\\evil.example/path"
                  "/safe\npath"]]
      (let [items [[breadcrumb/BreadcrumbItem {::breadcrumb/href href} "Root"]
                   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/parent"} "Parent"]
                   [breadcrumb/BreadcrumbItem "Current"]]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Collapsed breadcrumb items require an internal href"
             (breadcrumb-view {::breadcrumb/max-items 2} items)))))))
