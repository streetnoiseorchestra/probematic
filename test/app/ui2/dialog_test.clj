(ns app.ui2.dialog-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def missing-component ::missing-component)

(defn resolve-var [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _
      nil)))

(defn alias-value [sym]
  (if-let [v (resolve-var sym)]
    @v
    missing-component))

(defn render [alias-sym attrs & children]
  (let [alias (alias-value alias-sym)]
    (when-not (= missing-component alias)
      (html/->str (into [alias attrs] children)))))

(deftest alert-icon-renders-warning-markup
  (let [html (render 'app.ui2.dialog/AlertIcon {})]
    (is (some? html) "AlertIcon alias should exist")
    (when html
      (is (str/includes? html "bg-red-100"))
      (is (str/includes? html "text-red-600"))
      (is (str/includes? html "M12 9v3.75")))))

(deftest form-dialog-renders-open-state-title-and-child-content
  (let [html (render 'app.ui2.dialog/FormDialog
                     {:id                    "edit-team-1"
                      :app.ui2.dialog/title  "Edit Team"
                      :app.ui2.dialog/open   "$team.open"
                      :app.ui2.dialog/on-hide "closeTeam()"
                      :app.ui2.dialog/on-show "openTeam()"}
                     [:div "Body"])]
    (is (some? html) "FormDialog alias should exist")
    (when html
      (is (str/includes? html "<my-dialog"))
      (is (str/includes? html "data-attr:open=\"$team.open\""))
      (is (str/includes? html "closeTeam()"))
      (is (str/includes? html "openTeam()"))
      (is (str/includes? html "Edit Team"))
      (is (str/includes? html "Body")))))

(deftest confirm-dialog-renders-prompt-and-action-buttons
  (let [AlertIcon (alias-value 'app.ui2.dialog/AlertIcon)
        html      (render 'app.ui2.dialog/ConfirmDialog
                          {:id                           "delete-team-1"
                           :app.ui2.dialog/title         "Confirm"
                           :app.ui2.dialog/prompt        "Delete this team?"
                           :app.ui2.dialog/confirm-text  "Delete"
                           :app.ui2.dialog/cancel-text   "Cancel"
                           :app.ui2.dialog/on-confirm    "deleteTeam()"
                           :app.ui2.dialog/on-hide       "hideConfirm()"
                           :app.ui2.dialog/icon          AlertIcon}
                          [:div "ignored"])]
    (is (some? html) "ConfirmDialog alias should exist")
    (when html
      (is (str/includes? html "<my-dialog"))
      (is (str/includes? html "Delete this team?"))
      (is (str/includes? html "Delete"))
      (is (str/includes? html "Cancel"))
      (is (str/includes? html "deleteTeam()"))
      (is (str/includes? html "hideConfirm()"))
      (is (str/includes? html "data-dialog=\"close\"")))))
