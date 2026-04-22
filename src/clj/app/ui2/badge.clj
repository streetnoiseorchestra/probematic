(ns app.ui2.badge
  (:require
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-bool-bubble
  {:examples ["[badge/BoolBubble {::badge/value true}]"
              "[badge/BoolBubble {::badge/value false ::badge/labels {true \"Band\" false \"Privat\"}}]"]
   :ns       *ns*
   :as       'badge
   :name     'BoolBubble
   :desc     "A status badge for boolean values."
   :alias    ::bool-bubble
   :schema
   [:map {}
    [::value {:doc "The boolean value to render"} :boolean]
    [::labels {:optional true
               :doc      "Labels to use for true and false values"}
     [:map {}
      [true :string]
      [false :string]]]]})

(def ^{:doc (uic/generate-docstring doc-bool-bubble)} BoolBubble
  ::bool-bubble)

(defmethod c/resolve-alias ::bool-bubble
  [_ {::keys [value labels] :or {labels {true "Aktiv" false "Inaktiv"}} :as attrs} _children]
  (uic/validate-opts! doc-bool-bubble attrs)
  (cc/compile
   [:span (uic/merge-attrs attrs :class (uic/cs "px-2 inline-flex text-xs leading-5 font-semibold rounded-full"
                                                (when value "text-green-800 bg-green-100")
                                                (when (not value) "text-red-800 bg-red-100")))
    (get labels value)]))
