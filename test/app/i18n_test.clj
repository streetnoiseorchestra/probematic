(ns app.i18n-test
  (:require
   [app.html :as html]
   [app.i18n :as i18n]
   [app.interceptors :as interceptors]
   [clojure.test :refer [deftest is]]
   [dev.onionpancakes.chassis.core :as c]))

(defn translator [locale]
  (i18n/tr-with (i18n/read-langs) [locale]))

(deftest fluent-translations-test
  (let [en (translator :en)
        de (translator :de)]
    (is (= {:english          "Teams"
            :german           "Teams"
            :other-file       "Save"
            :default-file     "Active"
            :map-data         "Are you sure you want to delete the team “Brass”?"
            :inline-fallback  "Inline fallback"}
           {:english         (en [:band-settings/team-title])
            :german          (de [:band-settings/team-title])
            :other-file      (en [:action/save])
            :default-file    (en [:status-active])
            :map-data        (en [:band-settings/team-delete-confirm] {:team-name "Brass"})
            :inline-fallback (en [:band-settings/not-defined "Inline fallback"])}))))

(deftest fluent-candidate-precedence-test
  (let [tr (translator :en)]
    (is (= {:first-message "Teams"
            :next-message  "Delete"
            :fallback      "Fallback"}
           {:first-message (tr [:band-settings/team-title "Fallback"])
            :next-message  (tr [:unknown/not-defined :action/delete "Fallback"])
            :fallback      (tr [:unknown/not-defined "Fallback"])}))))

(deftest fluent-translations-use-the-default-locale-test
  (is (= "Teams"
         ((translator :fr) [:band-settings/team-title]))))

(deftest fluent-translation-data-must-be-a-map-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Fluent translation data must be a map"
                        ((translator :en) [:band-settings/team-delete-confirm] ["Brass"]))))

(deftest i18n-interceptor-detects-the-browser-locale-test
  (let [interceptor (interceptors/i18n-interceptor
                     {:env        {:ig/system {:app.ig/profile :prod}}
                      :i18n-langs (i18n/read-langs)})
        request     (:request
                     ((:enter interceptor)
                      {:request {:headers {"accept-language" "de-DE,de;q=0.8,en;q=0.5"}}}))]
    (is (= {:current-locale :de
            :translation    "Teams"}
           {:current-locale (:current-locale request)
            :translation    ((:tr request) [:band-settings/team-title])}))))

(deftest i18n-interceptor-switches-locale-and-persists-the-cookie-test
  (let [interceptor (interceptors/i18n-interceptor
                     {:env        {:ig/system {:app.ig/profile :prod}}
                      :i18n-langs (i18n/read-langs)})
        entered     ((:enter interceptor)
                     {:request {:query-string "lang=en"
                                :cookies      {"lang" {:value "de"}}
                                :headers      {"accept-language" "de"}}})
        left        ((:leave interceptor) (assoc entered :response {}))]
    (is (= {:current-locale :en
            :translation    "Teams"
            :cookie         {:value "en"
                             :path  "/"
                             :max-age 108000}}
           {:current-locale (get-in entered [:request :current-locale])
            :translation    ((get-in entered [:request :tr]) [:band-settings/team-title])
            :cookie         (get-in left [:response :cookies "lang"])}))))

(defn node-translator
  ([resource-ids]
   (or (get {[:test/title] "Band & Settings"} resource-ids)
       (some #(when (string? %) %) resource-ids)))
  ([resource-ids data]
   (case resource-ids
     [:test/delete] (str "Delete " (:name data))
     (node-translator resource-ids))))

(defn resolve-translation-nodes [translator value]
  (if-let [resolve-fn (ns-resolve 'app.i18n 'resolve-translations)]
    (resolve-fn translator value)
    ::missing-resolver))

(defn render-translation-nodes [translator value]
  (try
    (html/->str translator value)
    (catch Throwable _exception
      ::missing-renderer)))

(defmethod c/resolve-alias ::late-translation
  [_ _attrs _children]
  [:span [:i18n/tr :test/delete {:name "<Brass>"}]])

(deftest translation-data-nodes-resolve-throughout-hiccup-test
  (is (= [:div {:title "Band & Settings"}
          [:h1 "Band & Settings"]
          [:p "Delete <Brass>"]
          [:p "Inline fallback"]]
         (resolve-translation-nodes
          node-translator
          [:div {:title [:i18n/tr :test/title]}
           [:h1 [:i18n/tr :test/title]]
           [:p [:i18n/tr :test/delete {:name "<Brass>"}]]
           [:p [:i18n/tr [:test/missing "Inline fallback"]]]]))))

(deftest translation-data-nodes-render-as-escaped-html-test
  (is (= "<p title=\"Band &amp; Settings\">Delete &lt;Brass&gt;</p>"
         (render-translation-nodes
          node-translator
          [:p {:title [:i18n/tr :test/title]}
           [:i18n/tr :test/delete {:name "<Brass>"}]]))))

(deftest alias-generated-translation-data-nodes-render-test
  (is (= "<span>Delete &lt;Brass&gt;</span>"
         (render-translation-nodes
          node-translator
          [::late-translation]))))

(deftest translation-data-nodes-require-a-translator-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"translator"
                        (resolve-translation-nodes nil [:i18n/tr :test/title]))))

(deftest malformed-translation-data-nodes-are-rejected-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"translation"
                        (resolve-translation-nodes node-translator [:i18n/tr]))))
