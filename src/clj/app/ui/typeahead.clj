(ns app.ui.typeahead
  (:require
   [selmer.parser :as selmer]
   [app.icons :as icon]
   [app.ui.input :as input]
   [app.ui.core :as uic]
   [malli.experimental.lite :as l]
   [jsonista.core :as j]))

(defn- ns-signals [ns m]
  (if ns
    {ns m}
    m))

(defn- ns-signal [ns kw]
  (clojure.core/name
   (if ns
     (keyword (str (clojure.core/name ns) "." (clojure.core/name kw)))
     kw)))

(defn- $ [s]
  (str "$" s))

(defn typeahead
  {:opts {:phrase :string
          :ns     (l/optional :keyword)
          :id     :string
          :label  :string
          :action :string}}
  [& args]
  (let [[opts attrs _children]              (uic/extract #'typeahead args)
        {:keys [phrase ns id label action]} opts
        container-id                        (str id "_container")
        input-id                            id
        $phrase                             ($ (ns-signal ns :phrase))
        $last-phrase                        ($ (ns-signal ns :last-phrase))
        $update-history                     ($ (ns-signal ns :update-history))
        $search-counter                     ($ (ns-signal ns :search-counter))
        $browser-history-counter            ($ (ns-signal ns :browser-history-counter))]
    [:form (uic/merge-attrs attrs
                            :class                   "flex items-center"
                            :id                      container-id
                            :data-signals__ifmissing (j/write-value-as-string (ns-signals ns {:last-phrase             ""
                                                                                              :update-history          nil
                                                                                              :search-counter          0
                                                                                              :browser-history-counter 0}))
                            :data-signals            (j/write-value-as-string (ns-signals ns {:phrase phrase})))

     [:label {:for input-id :class "sr-only"} label]
     [:div {:class "relative w-full"}
      (input/text (uic/attr-map :id                   input-id
                                :class                "w-full"
                                :placeholder          label
                                :data-on-load         (selmer/render  "TypeaheadSearch('{{input-id}}', '{{container-id}}')" {:input-id input-id :container-id container-id})
                                :data-bind            (ns-signal ns :phrase)
                                :data-on-keydown__debounce.300ms
                                (selmer/render
                                 "{{phrase}}.trim() !== {{last-phrase}}.trim() && ( {{update-history}} = true ) && {{
action|safe }} && {{search-counter}}++; {{last-phrase}} = {{phrase}}.trim();"

                                 {:phrase                  $phrase
                                  :last-phrase             $last-phrase
                                  :action                  action
                                  :update-history          $update-history
                                  :search-counter          $search-counter
                                  :browser-history-counter $browser-history-counter})

                                :data-on-historychange
                                (selmer/render "{{phrase}}=evt.detail.phrase; {{browser-history-counter}}++; {{update-history}} = false; {{action|safe}}"
                                               {:action                  action
                                                :phrase                  $phrase
                                                :update-history          $update-history
                                                :search-counter          $search-counter
                                                :browser-history-counter $browser-history-counter})

                                :-label   label
                                :-leading icon/search))]]))

(defn response-data [req ns]
  (let [{:keys [phrase update-history]} (get-in req (if ns [:parameters :body ns] [:parameters :body]))]
    {:phrase phrase
     :script
     (str
      "const url = new URL(window.location.href);"
      (if (and phrase (>  (count phrase) 0))
        (format "url.searchParams.set('phrase', %s);" (j/write-value-as-string phrase))
        "url.searchParams.delete('phrase');")
      (when update-history
        "window.history.pushState({}, '', url);"))}))
