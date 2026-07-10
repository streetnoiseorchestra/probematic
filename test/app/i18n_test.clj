(ns app.i18n-test
  (:require
   [app.i18n :as i18n]
   [app.interceptors :as interceptors]
   [clojure.test :refer [deftest is]]))

(defn translator [locale]
  (i18n/tr-with (i18n/read-langs) [locale]))

(deftest fluent-and-tempura-translations-coexist-test
  (let [en (translator :en)
        de (translator :de)]
    (is (= {:tempura-english      "Delete"
            :tempura-german       "Löschen"
            :tempura-vector-data  "Welcome Ada!"
            :fluent-english       "Teams"
            :fluent-german        "Teams"
            :fluent-other-file    "Save"
            :fluent-default-file  "Active"
            :fluent-map-data      "Are you sure you want to delete the team “Brass”?"
            :inline-fallback      "Inline fallback"}
           {:tempura-english      (en [:action/delete])
            :tempura-german       (de [:action/delete])
            :tempura-vector-data  (en [:dashboard/welcome] ["Ada"])
            :fluent-english       (en [:band-settings/team-title])
            :fluent-german        (de [:band-settings/team-title])
            :fluent-other-file    (en [:action/save])
            :fluent-default-file  (en [:status-active])
            :fluent-map-data      (en [:band-settings/team-delete-confirm] {:team-name "Brass"})
            :inline-fallback      (en [:band-settings/not-defined "Inline fallback"])}))))

(deftest translation-source-precedence-test
  (let [lang-data (-> (i18n/read-langs)
                      (assoc-in [:en :tempura :band-settings :team-title] "Tempura Teams")
                      (assoc-in [:en :tempura :band-settings :legacy-message] "Legacy %1"))
        tr        (i18n/tr-with lang-data [:en])]
    (is (= {:fluent-first             "Teams"
            :tempura-after-fluent-miss "Legacy value"
            :tempura-next             "Delete"
            :fallback-last            "Fallback"}
           {:fluent-first             (tr [:band-settings/team-title "Fallback"])
            :tempura-after-fluent-miss (tr [:band-settings/legacy-message "Fallback"] ["value"])
            :tempura-next             (tr [:action/delete "Fallback"])
            :fallback-last            (tr [:unknown/not-defined "Fallback"])}))))

(deftest fluent-translations-use-the-default-locale-test
  (is (= "Teams"
         ((translator :fr) [:band-settings/team-title]))))

(deftest raw-tempura-dictionaries-remain-supported-test
  (let [tr (partial i18n/tr
                    {:dict           {:en {:legacy "Legacy translation"
                                           :fluent {:legacy "Tempura Fluent namespace"}}}
                     :default-locale :en}
                    [:en])]
    (is (= {:unqualified      "Legacy translation"
            :fluent-namespace "Tempura Fluent namespace"}
           {:unqualified      (tr [:legacy])
            :fluent-namespace (tr [:fluent/legacy])}))))

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
