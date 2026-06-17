(ns app.ui2.icon-test
  (:require
   [app.html :as html]
   [app.icons :as icons]
   [app.ui2.icon :as ico]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(def test-manifest
  (delay
    (icons/build-sprite-manifest
     [{:id          :snoico
       :source-root "public/img/snoico"
       :icons       [:home]}
      {:id          :phosphor
       :source-root "public/img/phosphor/phosphor-regular"
       :icons       [:info]}])))

(defn install-test-manifest [f]
  (let [manifest_ (deref #'icons/sprite-manifest_)
        original  @manifest_]
    (icons/install-sprite-manifest! @test-manifest)
    (try
      (f)
      (finally
        (reset! manifest_ original)))))

(use-fixtures :each install-test-manifest)

(defn render-icon [attrs]
  (html/->str [ico/Icon attrs]))

(deftest icon-alias-renders-native-svg-use-markup
  (let [html (render-icon {::ico/name :home})]
    (is (= {:svg?       true
            :wa-icon?   false
            :use?       true
            :symbol?    true
            :decorative true}
           {:svg?       (str/includes? html "<svg")
            :wa-icon?   (str/includes? html "<wa-icon")
            :use?       (str/includes? html "<use")
            :symbol?    (str/includes? html "#snoico-home")
            :decorative (and (str/includes? html "aria-hidden=\"true\"")
                             (str/includes? html "focusable=\"false\""))}))))

(deftest icon-alias-selects-non-default-library-with-qualified-prop
  (let [html (render-icon {::ico/name    :info
                           ::ico/library :phosphor})]
    (is (= {:phosphor-url? true
            :symbol?       true}
           {:phosphor-url? (str/includes? html "/img/icons/phosphor.")
            :symbol?       (str/includes? html "#phosphor-info")}))))

(deftest icon-alias-passes-unqualified-attrs-to-svg
  (let [html (render-icon {::ico/name          :home
                           :id                 "home-icon"
                           :slot               "start"
                           :class              "extra-icon"
                           :data-preserve-attr "class"})]
    (is (= {:id?       true
            :slot?     true
            :class?    true
            :data-attr? true}
           {:id?        (str/includes? html "id=\"home-icon\"")
            :slot?      (str/includes? html "slot=\"start\"")
            :class?     (and (str/includes? html "sno-icon")
                             (str/includes? html "extra-icon"))
            :data-attr? (str/includes? html "data-preserve-attr=\"class\"")}))))

(deftest icon-alias-renders-accessible-label-when-provided
  (let [html (render-icon {::ico/name  :home
                           ::ico/label "Home"})]
    (is (= {:role?        true
            :label?       true
            :aria-hidden? false}
           {:role?        (str/includes? html "role=\"img\"")
            :label?       (str/includes? html "aria-label=\"Home\"")
            :aria-hidden? (str/includes? html "aria-hidden")}))))

(deftest icon-alias-renders-transform-and-auto-width-attrs
  (let [html (render-icon {::ico/name       :home
                           ::ico/auto-width true
                           ::ico/flip       :x
                           ::ico/rotate     90})]
    (is (= {:auto-width? true
            :flip?       true
            :rotate?     true
            :style?      true}
           {:auto-width? (str/includes? html "auto-width")
            :flip?       (str/includes? html "flip=\"x\"")
            :rotate?     (str/includes? html "rotate=\"90\"")
            :style?      (str/includes? html "--rotate-angle: 90deg")}))))
