(ns app.ui2.button-test
  (:require
   [app.html :as html]
   [app.icons :as icons]
   [app.ui2.icon :as ico]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(def missing-component ::missing-component)

(def test-manifest
  (delay
    (icons/build-sprite-manifest
     [{:id          :snoico
       :source-root "public/img/snoico"
       :icons       [:home :calendar]}])))

(defn install-test-manifest [f]
  (let [manifest_ (deref #'icons/sprite-manifest_)
        original  @manifest_]
    (icons/install-sprite-manifest! @test-manifest)
    (try
      (f)
      (finally
        (reset! manifest_ original)))))

(use-fixtures :each install-test-manifest)

(defn resolve-button []
  (try
    (requiring-resolve 'app.ui2.button/Button)
    (catch Throwable _
      nil)))

(defn button-alias []
  (or (resolve-button) missing-component))

(defn button-html [attrs & children]
  (let [button (button-alias)]
    (when-not (= missing-component button)
      (html/->str (into [@button attrs] children)))))

(deftest basic-button-renders-a-native-button-by-default
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:appearance    "filled"
                               :variant       "brand"
                               :size          "s"
                               :class         "extra-action"
                               :data-on:click "save()"}
                              "Save")]
        (is (str/includes? html "<button"))
        (is (not (str/includes? html "<wa-button")))
        (is (str/includes? html "type=\"button\""))
        (is (str/includes? html "wa-filled"))
        (is (str/includes? html "wa-brand"))
        (is (str/includes? html "wa-size-s"))
        (is (str/includes? html "extra-action"))
        (is (str/includes? html "data-on:click=\"save()\""))
        (is (not (str/includes? html "appearance=\"filled\"")))))))

(deftest basic-submit-button-keeps-explicit-submit-type
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:appearance "outlined"
                               :type       "submit"
                               :form       "edit-form"}
                              "Save")]
        (is (str/includes? html "<button"))
        (is (str/includes? html "type=\"submit\""))
        (is (str/includes? html "form=\"edit-form\""))))))

(deftest basic-link-button-renders-a-native-anchor
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:href       "/members"
                               :appearance "plain"
                               :variant    "brand"
                               :type       "submit"}
                              "Members")]
        (is (str/includes? html "<a"))
        (is (not (str/includes? html "<wa-button")))
        (is (str/includes? html "href=\"/members\""))
        (is (str/includes? html "wa-button"))
        (is (str/includes? html "wa-plain"))
        (is (str/includes? html "wa-brand"))
        (is (not (str/includes? html "type=\"submit\"")))
        (is (not (str/includes? html "type=\"button\"")))))))

(deftest icon-only-buttons-use-the-basic-branch
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:appearance "plain"
                               :size       "s"
                               :aria-label "Download"}
                              [ico/Icon {::ico/library :snoico ::ico/name :home}])]
        (is (str/includes? html "<button"))
        (is (not (str/includes? html "<wa-button")))
        (is (str/includes? html "type=\"button\""))
        (is (str/includes? html "aria-label=\"Download\""))
        (is (str/includes? html "<svg"))))))

(deftest icon-and-label-buttons-wrap-the-label-for-native-icon-spacing
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:appearance "outlined"}
                              [ico/Icon {::ico/library :snoico ::ico/name :calendar :slot "start"}]
                              "Edit")]
        (is (str/includes? html "<button"))
        (is (not (str/includes? html "<wa-button")))
        (is (str/includes? html "<svg"))
        (is (str/includes? html "slot=\"start\""))
        (is (str/includes? html "<span>Edit</span>"))))))

(deftest loading-buttons-use-the-advanced-web-awesome-branch
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:appearance        "filled"
                               :variant           "brand"
                               :data-attr:loading "$loading === 'save'"}
                              "Save")]
        (is (str/includes? html "<wa-button"))
        (is (str/includes? html "appearance=\"filled\""))
        (is (str/includes? html "variant=\"brand\""))
        (is (str/includes? html "data-attr:loading=\"$loading === &apos;save&apos;\""))))))

(deftest caret-buttons-use-the-advanced-web-awesome-branch
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:appearance "outlined"
                               :with-caret true}
                              "More")]
        (is (str/includes? html "<wa-button"))
        (is (str/includes? html "with-caret"))))))
