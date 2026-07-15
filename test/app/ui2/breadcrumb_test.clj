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
       :icons       [:arrow-left :caret-right]}])))

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

(defn direct-positions [trail]
  (vec (drop 2 trail)))

(defn collapse-position? [position]
  (contains? (:class (l/attrs position)) "collapse"))

(defn visible-labels [trail]
  (->> (direct-positions trail)
       (remove collapse-position?)
       (mapv l/text)))

(defn popover-items [node]
  (some->> (l/select-one :wa-popover node)
           (l/select breadcrumb/BreadcrumbItem)))

(defn popover-labels [node]
  (mapv l/text (popover-items node)))

(defn state-only? [state position]
  (contains? (:class (l/attrs position))
             (case state
               :expanded "compact-only"
               :compact  "expanded-only")))

(defn state-labels [positions state]
  (->> positions
       (remove collapse-position?)
       (remove #(state-only? state %))
       (mapv l/text)))

(defn popover-positions [node]
  (some->> (l/select-one ".popover-list" node)
           direct-positions))

(defn state-popover-labels [node state]
  (->> (popover-positions node)
       (remove #(state-only? state %))
       (mapv l/text)))

(defn resolved-item [item]
  (let [[_ attrs & children] (uic/norm item)]
    (c/resolve-alias breadcrumb/BreadcrumbItem attrs children)))

(deftest absent-options-use-component-owned-responsive-defaults
  (let [view       (breadcrumb-view {} six-items)
        parent     (l/select-one button/BackButton view)
        desktop    (l/select-one "ol.sno-breadcrumb-list.desktop" view)
        desktop-li (direct-positions desktop)
        expanded-label (l/select-one ".expanded-label" desktop)
        compact-label  (l/select-one ".compact-label" desktop)]
    (is (= {:mobile-parent  {:href  "/root/section/collection/item/parent"
                             :label "Parent"}
            :expanded-visible ["Item" "Parent" "Current"]
            :expanded-hidden  ["Root" "Section" "Collection"]
            :compact-visible  ["Parent" "Current"]
            :compact-hidden   ["Root" "Section" "Collection" "Item"]
            :position-count 4
            :collapse-count 1
            :current-count  1
            :popover-count  1
            :expanded-label
            [:i18n/tr :action/show-hidden-breadcrumb-items {:count 3}]
            :compact-label
            [:i18n/tr :action/show-hidden-breadcrumb-items {:count 4}]}
           {:mobile-parent  (select-keys (l/attrs parent) [:href :label])
            :expanded-visible (state-labels desktop-li :expanded)
            :expanded-hidden  (state-popover-labels desktop :expanded)
            :compact-visible  (state-labels desktop-li :compact)
            :compact-hidden   (state-popover-labels desktop :compact)
            :position-count (count desktop-li)
            :collapse-count (count (filter collapse-position? desktop-li))
            :current-count  (count (l/select "[aria-current=page]" desktop))
            :popover-count  (count (l/select :wa-popover desktop))
            :expanded-label (l/select-one :i18n/tr expanded-label)
            :compact-label  (l/select-one :i18n/tr compact-label)}))
    (is (= ["Item"]
           (mapv l/text (l/select "li.expanded-only" desktop))))
    (is (= ["Item"]
           (mapv l/text
                 (l/select "li.compact-only"
                           (l/select-one ".popover-list"
                                         desktop)))))
    (is (empty? (l/select "ol.sno-breadcrumb-list.mobile" view)))))

(deftest responsive-desktop-trail-keeps-one-trigger-across-states
  (let [view     (breadcrumb-view {::breadcrumb/max-items [2 3]} six-items)
        desktop  (l/select-one "ol.sno-breadcrumb-list.desktop" view)
        trigger  (l/select-one button/Button desktop)
        popover  (l/select-one :wa-popover desktop)]
    (is (= {:desktop-trails 1
            :triggers       1
            :popovers       1
            :connected?     true}
           {:desktop-trails (count (l/select "ol.sno-breadcrumb-list.desktop" view))
            :triggers       (count (l/select button/Button desktop))
            :popovers       (count (l/select :wa-popover desktop))
            :connected?     (= (-> trigger l/attrs :id)
                               (-> popover l/attrs :for))}))))

(deftest responsive-limits-count-actual-items-not-the-overflow-trigger
  (let [items   [(first six-items) (nth six-items 4) (last six-items)]
        view    (breadcrumb-view {::breadcrumb/max-items  [2 2]
                                  ::breadcrumb/mobile-mode :trail}
                                 items)
        mobile  (l/select-one "ol.sno-breadcrumb-list.mobile" view)
        desktop (l/select-one "ol.sno-breadcrumb-list.desktop" view)]
    (is (= {:mobile  {:visible  ["Parent" "Current"]
                      :hidden   ["Root"]
                      :positions 3}
            :desktop {:visible  ["Parent" "Current"]
                      :hidden   ["Root"]
                      :positions 3}}
           {:mobile  {:visible   (visible-labels mobile)
                      :hidden    (popover-labels mobile)
                      :positions (count (direct-positions mobile))}
            :desktop {:visible   (visible-labels desktop)
                      :hidden    (popover-labels desktop)
                      :positions (count (direct-positions desktop))}}))))

(deftest explicit-nil-limit-keeps-the-standalone-trail-unrestricted
  (let [view  (breadcrumb-view {::breadcrumb/max-items  nil
                                ::breadcrumb/mobile-mode :trail}
                               six-items)
        trail (l/select-one "ol.sno-breadcrumb-list" view)]
    (is (= {:visible-labels (mapv l/text six-items)
            :position-count 6
            :popover-count  0
            :current-count  1
            :variant-count  0}
           {:visible-labels (visible-labels trail)
            :position-count (count (direct-positions trail))
            :popover-count  (count (l/select :wa-popover view))
            :current-count  (count (l/select "[aria-current=page]" view))
            :variant-count  (+ (count (l/select "ol.sno-breadcrumb-list.mobile" view))
                               (count (l/select "ol.sno-breadcrumb-list.desktop" view)))}))))

(deftest nonresponsive-limits-still-honor-mobile-mode
  (testing "a scalar limit keeps the default mobile parent projection"
    (let [view    (breadcrumb-view {::breadcrumb/max-items 2} six-items)
          parent  (l/select-one button/BackButton view)
          desktop (l/select-one "ol.sno-breadcrumb-list.desktop" view)]
      (is (= {:parent   {:href  "/root/section/collection/item/parent"
                         :label "Parent"}
              :visible  ["Parent" "Current"]
              :hidden   ["Root" "Section" "Collection" "Item"]
              :shared-trails 0}
             {:parent   (select-keys (l/attrs parent) [:href :label])
              :visible  (visible-labels desktop)
              :hidden   (popover-labels desktop)
              :shared-trails
              (count (remove #(contains? (:class (l/attrs %))
                                         "desktop")
                             (l/select "ol.sno-breadcrumb-list" view)))}))))
  (testing "an unlimited limit can hide mobile context without limiting desktop"
    (let [view    (breadcrumb-view {::breadcrumb/max-items  nil
                                    ::breadcrumb/mobile-mode :hidden}
                                   six-items)
          desktop (l/select-one "ol.sno-breadcrumb-list.desktop" view)]
      (is (= {:visible       (mapv l/text six-items)
              :parent-count  0
              :mobile-trails 0
              :popover-count 0}
             {:visible        (visible-labels desktop)
              :parent-count   (count (l/select button/BackButton view))
              :mobile-trails  (count (l/select "ol.sno-breadcrumb-list.mobile" view))
              :popover-count  (count (l/select :wa-popover view))})))))

(deftest one-actual-item-limit-keeps-current-and-collapses-every-ancestor
  (let [view  (breadcrumb-view {::breadcrumb/max-items  1
                                ::breadcrumb/mobile-mode :trail}
                               six-items)
        trail (l/select-one "ol.sno-breadcrumb-list" view)]
    (is (= {:visible-labels ["Current"]
            :hidden-labels  ["Root" "Section" "Collection" "Item" "Parent"]
            :position-count 2
            :separator-count 1
            :current-count  1}
           {:visible-labels (visible-labels trail)
            :hidden-labels  (popover-labels trail)
            :position-count (count (direct-positions trail))
            :separator-count (count (l/select ".separator" trail))
            :current-count  (count (l/select "[aria-current=page]" trail))}))))

(deftest leading-items-use-the-actual-item-budget
  (testing "one preserved leading item still leaves parent and current"
    (let [view  (breadcrumb-view {::breadcrumb/max-items             3
                                  ::breadcrumb/items-before-collapse 1
                                  ::breadcrumb/mobile-mode           :trail}
                                 six-items)
          trail (l/select-one "ol.sno-breadcrumb-list" view)]
      (is (= {:visible ["Root" "Parent" "Current"]
              :hidden  ["Section" "Collection" "Item"]
              :positions 4}
             {:visible   (visible-labels trail)
              :hidden    (popover-labels trail)
              :positions (count (direct-positions trail))}))))
  (testing "the leading count may consume every actual slot except current"
    (let [view  (breadcrumb-view {::breadcrumb/max-items             3
                                  ::breadcrumb/items-before-collapse 2
                                  ::breadcrumb/mobile-mode           :trail}
                                 six-items)
          trail (l/select-one "ol.sno-breadcrumb-list" view)]
      (is (= {:visible ["Root" "Section" "Current"]
              :hidden  ["Collection" "Item" "Parent"]
              :positions 4}
             {:visible   (visible-labels trail)
              :hidden    (popover-labels trail)
              :positions (count (direct-positions trail))})))))

(deftest mobile-modes-select-one-responsive-presentation
  (testing "parent is the default mobile projection"
    (let [parent (l/select-one button/BackButton
                               (breadcrumb-view {} six-items))]
      (is (= {:href  "/root/section/collection/item/parent"
              :label "Parent"}
             (select-keys (l/attrs parent) [:href :label])))))
  (testing "trail renders the mobile item limit"
    (let [view   (breadcrumb-view {::breadcrumb/mobile-mode :trail}
                                  six-items)
          mobile (l/select-one "ol.sno-breadcrumb-list.mobile" view)]
      (is (= {:visible ["Parent" "Current"]
              :hidden  ["Root" "Section" "Collection" "Item"]}
             {:visible (visible-labels mobile)
              :hidden  (popover-labels mobile)}))
      (is (empty? (l/select button/BackButton view)))))
  (testing "hidden omits mobile context while retaining desktop"
    (let [view (breadcrumb-view {::breadcrumb/mobile-mode :hidden}
                                six-items)]
      (is (= 1 (count (l/select "ol.sno-breadcrumb-list.desktop" view))))
      (is (empty? (l/select "ol.sno-breadcrumb-list.mobile" view)))
      (is (empty? (l/select button/BackButton view))))))

(deftest parent-mode-preserves-parent-link-behavior
  (let [items  [[breadcrumb/BreadcrumbItem {::breadcrumb/href   "https://example.test/parent"
                                            ::breadcrumb/target "_blank"
                                            ::breadcrumb/rel    "external"
                                            :data-track         "parent"}
                 "Parent"]
                [breadcrumb/BreadcrumbItem "Current"]]
        parent (l/select-one button/BackButton
                             (breadcrumb-view {} items))]
    (is (= {:href       "https://example.test/parent"
            :target     "_blank"
            :rel        "external"
            :data-track "parent"
            :label      "Parent"}
           (select-keys (l/attrs parent)
                        [:href :target :rel :data-track :label])))))

(deftest zero-and-one-source-item-have-no-mobile-parent
  (doseq [[description items expected-current-count]
          [["zero items" [] 0]
           ["one current item" [(last six-items)] 1]]]
    (testing description
      (let [view (breadcrumb-view {} items)]
        (is (= {:parent-count  0
                :popover-count 0
                :current-count expected-current-count}
               {:parent-count  (count (l/select button/BackButton view))
                :popover-count (count (l/select :wa-popover view))
                :current-count (count (l/select "[aria-current=page]" view))}))))))

(deftest popover-preserves-ordinary-breadcrumb-items
  (let [items [[breadcrumb/BreadcrumbItem {::breadcrumb/href   "https://example.test/root"
                                           ::breadcrumb/target "_blank"
                                           ::breadcrumb/rel    "external"
                                           :data-track         "root"}
                "External"]
               [breadcrumb/BreadcrumbItem {::breadcrumb/href "relative/path"
                                           :data-on:click   "evt.preventDefault()"}
                "Relative"]
               [breadcrumb/BreadcrumbItem "Grouping"]
               [breadcrumb/BreadcrumbItem {::breadcrumb/href "/policy"
                                           ::breadcrumb/rel  "nofollow"}
                "Rel only"]
               [breadcrumb/BreadcrumbItem "Current"]]
        view (breadcrumb-view {::breadcrumb/max-items  1
                               ::breadcrumb/mobile-mode :trail}
                              items)
        popover (l/select-one :wa-popover view)
        hidden  (popover-items popover)
        resolved (mapv resolved-item hidden)]
    (is (= (mapv l/attrs (take 4 items))
           (mapv l/attrs hidden)))
    (is (= (mapv resolved-item (take 4 items))
           resolved))
    (is (= "nofollow" (-> resolved last l/attrs :rel)))
    (is (empty? (l/select :wa-dropdown view)))
    (is (empty? (l/select :wa-dropdown-item view)))
    (is (empty? (l/select "[data-on:wa-select]" view)))
    (is (empty? (l/select ico/Icon popover)))))

(deftest responsive-popovers-have-distinct-connected-triggers
  (let [view      (breadcrumb-view {::breadcrumb/max-items  [1 2]
                                    ::breadcrumb/mobile-mode :trail}
                                   six-items)
        triggers  (l/select button/Button view)
        popovers  (l/select :wa-popover view)
        trigger-ids (mapv #(-> % l/attrs :id) triggers)
        popover-fors (mapv #(-> % l/attrs :for) popovers)]
    (is (= {:trigger-count 2
            :popover-count 2
            :connected?    true
            :distinct?     true
            :string-ids?   true}
           {:trigger-count (count triggers)
            :popover-count (count popovers)
            :connected?    (= (set trigger-ids) (set popover-fors))
            :distinct?     (= 2 (count (set trigger-ids)))
            :string-ids?   (every? string? trigger-ids)}))
    (is (every? #(= {:appearance "plain" :size "s"}
                    (select-keys (l/attrs %) [:appearance :size]))
                triggers))
    (is (every? #(= {:placement     "bottom-start"
                     :without-arrow true}
                    (select-keys (l/attrs %)
                                 [:placement :without-arrow]))
                popovers))
    (is (= #{[:i18n/tr :action/show-hidden-breadcrumb-items {:count 5}]
             [:i18n/tr :action/show-hidden-breadcrumb-items {:count 4}]}
           (set (l/select :i18n/tr triggers))))
    (doseq [trigger triggers]
      (let [trigger-id (-> trigger l/attrs :id)
            popover    (some #(when (= trigger-id (-> % l/attrs :for)) %)
                             popovers)]
        (is (= {:aria-controls (-> popover l/attrs :id)
                :aria-expanded "false"
                :aria-haspopup "dialog"}
               (select-keys (l/attrs trigger)
                            [:aria-controls :aria-expanded :aria-haspopup])))
        (is (= (str "document.getElementById('" trigger-id
                    "').setAttribute('aria-expanded', 'true')")
               (-> popover l/attrs :data-on:wa-show)))
        (is (= (str "document.getElementById('" trigger-id
                    "').setAttribute('aria-expanded', 'false')")
               (-> popover l/attrs :data-on:wa-hide)))))))

(deftest invalid-collapse-and-mobile-options-fail-at-the-component-boundary
  (binding [uic/*validate-opts* false]
    (testing "zero and malformed responsive limits are invalid"
      (doseq [max-items [0 [3] [3 0] [3 4 5]]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"positive integer or \[compact expanded\] pair"
             (breadcrumb-view {::breadcrumb/max-items max-items}
                              six-items)))))
    (testing "the compact limit cannot exceed the expanded limit"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"compact max-items must not exceed expanded max-items"
           (breadcrumb-view {::breadcrumb/max-items [3 2]}
                            six-items))))
    (testing "the leading count must preserve one current item"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"items-before-collapse must not exceed max-items - 1"
           (breadcrumb-view {::breadcrumb/max-items             3
                             ::breadcrumb/items-before-collapse 3
                             ::breadcrumb/mobile-mode           :trail}
                            six-items))))
    (testing "mobile mode is an explicit enum"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"mobile-mode"
           (breadcrumb-view {::breadcrumb/mobile-mode :history}
                            six-items))))))

(deftest parent-mode-rejects-a-non-navigable-source-parent
  (let [items [[breadcrumb/BreadcrumbItem "Parent without href"]
               [breadcrumb/BreadcrumbItem "Current"]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"mobile parent requires an href"
         (breadcrumb-view {} items)))))

(deftest breadcrumb-css-switches-adaptive-items-with-component-properties
  (let [css (slurp "resources/public/css/ui2/breadcrumb.css")]
    (is (not (re-find #"--sno-breadcrumb-(?:expanded|compact)-item-display" css)))
    (is (re-find #"(?s)li\.expanded-only\s*\{.*--sno-breadcrumb-adaptive-track:\s*var\(\s*--sno-breadcrumb-expanded-item-track,\s*1fr\s*\)"
                 css))
    (is (re-find #"(?s)li\.compact-only\s*\{.*--sno-breadcrumb-adaptive-track:\s*var\(\s*--sno-breadcrumb-compact-item-track,\s*0fr\s*\)"
                 css))
    (is (re-find #"(?s)li:is\(\.expanded-only,\s*\.compact-only\).*display:\s*grid;.*grid-template-columns:\s*minmax\(\s*0,\s*var\(--sno-breadcrumb-adaptive-track\)\s*\);.*transition:.*grid-template-columns\s+var\(--wa-transition-normal\)"
                 css))
    (is (re-find #"(?s)\.expanded-label\s*\{.*visibility:\s*var\(--sno-breadcrumb-expanded-label-visibility,\s*visible\)"
                 css))
    (is (re-find #"(?s)\.compact-label\s*\{.*visibility:\s*var\(--sno-breadcrumb-compact-label-visibility,\s*hidden\)"
                 css))))

(deftest breadcrumb-css-keeps-a-compact-only-open-popover-coherent
  (let [css (slurp "resources/public/css/ui2/breadcrumb.css")]
    (is (re-find #"(?s)li\.collapse\.compact-only:has\(wa-popover\[open\]\)\s*\{.*--sno-breadcrumb-adaptive-track:\s*1fr;"
                 css))
    (is (re-find #"(?s)ol\.sno-breadcrumb-list:has\(\s*>\s*li\.collapse\.compact-only\s+wa-popover\[open\]\s*\)\s*>\s*li\.expanded-only\s*\{.*--sno-breadcrumb-adaptive-track:\s*0fr;"
                 css))
    (is (re-find #"(?s)li\.collapse\.compact-only:has\(wa-popover\[open\]\).*\.popover-list\s*>\s*li\.compact-only\s*\{.*--sno-breadcrumb-adaptive-track:\s*1fr;"
                 css))))

(deftest breadcrumb-css-retains-a-focused-compact-only-trigger
  (let [css (slurp "resources/public/css/ui2/breadcrumb.css")]
    (is (re-find #"(?s)li\.collapse\.compact-only:focus-within\s*\{.*--sno-breadcrumb-adaptive-track:\s*1fr;"
                 css))
    (is (re-find #"(?s)ol\.sno-breadcrumb-list:has\(\s*>\s*li\.collapse\.compact-only:focus-within\s*\)\s*>\s*li\.expanded-only\s*\{.*--sno-breadcrumb-adaptive-track:\s*0fr;"
                 css))))

(deftest breadcrumb-css-disables-adaptive-motion-when-requested
  (let [css (slurp "resources/public/css/ui2/breadcrumb.css")]
    (is (re-find #"(?s)@media\s*\(prefers-reduced-motion:\s*reduce\).*li:is\(\.expanded-only,\s*\.compact-only\).*transition:\s*none;"
                 css))
    (is (< (.indexOf css "grid-template-columns var(--wa-transition-normal)")
           (.indexOf css "@media (prefers-reduced-motion: reduce)")))))

(deftest breadcrumb-css-uses-scoped-token-based-popover-sizing
  (let [css (slurp "resources/public/css/ui2/breadcrumb.css")]
    (is (not (re-find #"24rem|100vw" css)))
    (is (re-find #"(?s)--sno-breadcrumb-popover-max-inline-size:\s*min\(\s*calc\(100dvi\s*-\s*var\(--wa-space-xl\)\),\s*calc\(var\(--wa-space-5xl\)\s*\*\s*5\)\s*\)"
                 css))))
