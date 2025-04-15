(ns app.members.create.view
  (:require [app.datastar :as d*]
            [app.html :as html]
            [app.members.domain :as domain]
            [app.members.queries :as queries]
            [app.members.routes2 :as commands]
            [app.ui :as ui]
            [app.ui.button2 :as button2]
            [app.ui.form :as form]
            [app.ui.layout :as l]
            [app.urls :as url]))

(defn page [{:keys [tr db] :as req}]
  (html/->str
   [:main {:class "flex-1" :id "main"}
    (l/panel {:-title    (tr [:member/new-member])
              :-subtitle "The more the merrier"}
             (let [section-options (ui/section-select-options  (queries/sections db))
                   form            {:ns               :member
                                    :command          (d*/dispatch req ::commands/create-member)
                                    :live-validation? true
                                    :fields           {:name          ""
                                                       :nick          ""
                                                       :email         ""
                                                       :username      ""
                                                       :phone         ""
                                                       :section-name  ""
                                                       :active        true
                                                       :create-sno-id true}}]
               (form/form {:-form form :class "sm:max-w-lg"}
                          (form/section {:-narrow? true}
                                        (form/input {:-label     (tr [:member/name])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :text
                                                     :name       :name})
                                        (form/input {:-label     (tr [:member/nick])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :text
                                                     :name       :nick})
                                        (form/input {:-label     (tr [:member/email])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :email
                                                     :name       :email})
                                        (form/input {:-label     (tr [:member/username])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :text
                                                     :name       :username
                                                     :pattern    (str domain/username-regex)
                                                     :title      (tr [:member/username-validation])})
                                        (form/input {:-label     (tr [:member/phone])
                                                     :-form      form
                                                     :-required? true
                                                     :class      "sm:col-span-2"
                                                     :type       :tel
                                                     :name       :phone
                                                     :pattern    "\\+[\\d\\- ]+"
                                                     :title      (tr [:member/phone-validation])})
                                        (form/select {:-label     (tr [:section])
                                                      :-required? true
                                                      :-form      form
                                                      :-options   section-options
                                                      :class      "sm:col-span-2"
                                                      :name       :section-name})
                                        [:div {:class "sm:col-span-3"}
                                         (form/checkbox {:-label       (tr [:member/create-sno-id])
                                                         :-description (tr [:member/create-sno-id-description])
                                                         :-form        form
                                                         :name         :create-sno-id})
                                         (form/checkbox {:-label       (tr [:Active])
                                                         :-description "Should the new member be marked as an active member?"
                                                         :-form        form
                                                         :class        "mt-4"
                                                         :name         :active})])
                          (form/errors {:-form form})
                          (form/actions {:-right (list
                                                  (button2/button {:-priority :secondary
                                                                   :href      (url/url-for req ::commands/members)}
                                                                  (tr [:action/cancel]))
                                                  (button2/button {:-priority :primary
                                                                   :type      :submit}
                                                                  (tr [:action/create])))})
                          ;; (d*/debug-signals)
                          )))]))
