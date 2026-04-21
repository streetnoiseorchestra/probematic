(ns app.ui.dropdown
  (:require [app.icons :as icon]
            [app.ui.core :as uic]
            [malli.experimental.lite :as l]))

(defn action-menu-item [id idx {:keys [label href attr active? icon tag spinner?]
                                :or   {attr {}
                                       tag  :a}}]
  [tag (merge {:href                                                     href  :class
               (uic/cs
                "text-gray-700 hover:bg-gray-100 hover:text-gray-900 block px-4 py-2 text-sm w-full text-left"
                (when spinner? "button-spinner")
                (when icon "flex gap-1")
                (when active? "font-medium bg-gray-100 text-gray-900 ")) :role "menuitem" :tabindex "-1" :id (str id "-" idx)}
              attr)
   (when icon icon)
   [:span {:class "button-label"} label]
   (when spinner? (icon/spinner {:class (uic/cs "spinner"
                                                "h-5 w-5"
                                                "text-sno-green-500")}))])

(defn action-menu-section [idx id section]
  [:div {:class
         ;; maybe add w-48 to make it wider and more clickable?
         ;; mt-2
         (uic/cs (if (zero? idx) "rounded-t-md" "rounded-b-md")
                 "z-10 py-1 divide-y divide-gray-200  bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden")
         :role "none"}
   (when (:label section)
     [:div {:class "px-4 py-3", :role "none"}
      [:p {:class "truncate text-sm font-medium text-gray-900", :role "none"}  (:label section)]])
   (map-indexed (partial action-menu-item id) (:items section))])

(defn action-menu
  "An action menu drop down.

      :minimal? - when true only shows the button-icon
      :section - a list of maps containing the :items key. The value of :items should be another list of maps
                 the section map can also have the :label key for a section header"
  {:opts {:id                (l/optional string?)
          :button-icon       (l/optional fn?)
          :button-icon-class (l/optional string?)
          :label             (l/optional string?)
          :minimal?          (l/optional boolean?)
          :sections          [:vector
                              [:map
                               {:label   (l/optional string?)
                                :href    (l/optional string?)
                                :attr    (l/optional [:map])
                                :active? (l/optional boolean?)
                                :icon    (l/optional fn?)
                                :tag     (l/optional keyword?)}]]}}

  [& args]

  (let [[opts attrs _children]                      (uic/extract #'action-menu args)
        {:keys [id button-icon sections label minimal? button-icon-class]
         :or   {minimal?          false
                button-icon-class "text-gray-900"}} opts
        trigger                                     (str id "_trigger")]
    [:div {:class "flex items-center"}
     [:div (uic/attr-map :class "relative" :data-ref id :data-init (format "ActionMenuPopover($%s)" id))
      [:div
       [:button {:id            trigger
                 :popovertarget id
                 :class
                 (uic/cs
                  ;; "dark:bg-gray-800 dark:text-white dark:border-gray-600 dark:hover:bg-gray-700 dark:hover:border-gray-600 dark:focus:ring-gray-700"
                  (when-not minimal?
                    "inline-flex items-center text-gray-900 bg-white border border-gray-300 focus:outline-hidden hover:bg-gray-100 focus:ring-4 focus:ring-gray-200 font-medium rounded-md text-sm px-3 py-1.5"))
                 :type          "button"}
        (when button-icon
          (button-icon {:class (uic/cs  (when minimal? "w-5 h-5")
                                        (when-not minimal? "w-4 h-4 mr-2")
                                        button-icon-class)}))
        (when label label)
        (when-not minimal?
          (icon/chevron-down {:class "w-3 h-3 ml-2"}))]]
      [:div (uic/merge-attrs attrs
                             :popover "auto"
                             :anchor trigger
                             :class "animate-entry absolute top-0 left-0 z-10 mt-2 w-max origin-top-right divide-y divide-gray-100 rounded-md bg-white ring-1 shadow-lg ring-black/5 focus:outline-hidden"
                             :id id  :role "menu" :aria-orientation "vertical" :aria-labelledby trigger :tabindex "-1")
       (map-indexed #(action-menu-section %1 id %2) sections)]]]))
