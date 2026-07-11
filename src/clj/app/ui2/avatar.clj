(ns app.ui2.avatar
  (:require
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def forum-avatar-origin
  "https://forum.streetnoise.at")

(defn avatar-template-src
  ([template]
   (avatar-template-src template 80))
  ([template size]
   (let [template (some-> template str str/trim)]
     (when-not (str/blank? template)
       (let [src (str/replace template "{size}" (str size))]
         (if (re-find #"(?i)^https?://" src)
           src
           (str forum-avatar-origin
                (when-not (str/starts-with? src "/") "/")
                src)))))))

(defn initials
  [value]
  (let [parts (->> (str/split (str/trim (str value)) #"\s+")
                   (remove str/blank?))]
    (not-empty
     (str/upper-case
      (if (< 1 (count parts))
        (apply str (map first (take 2 parts)))
        (apply str (take 2 (first parts))))))))

(defn- member-id
  [member]
  (:member/member-id member))

(defn- member-avatar-template
  [member]
  (:member/avatar-template member))

(defn- member-name
  [member]
  (:member/name member))

(defn- member-nick
  [member]
  (or (:member/nick member)
      (:member/name member)))

(def doc-avatar
  {:examples ["[avatar/Avatar {::avatar/member member :shape \"rounded\"}]"
              "[avatar/Avatar {::avatar/member member ::avatar/text :nick}]"
              "[avatar/Avatar {::avatar/name \"Ada Lovelace\" ::avatar/initials \"AL\"}]"
              "[avatar/Avatar {::avatar/icon :user ::avatar/icon-library :snoico :shape \"rounded\"}]"]
   :ns       *ns*
   :as       'avatar
   :name     'Avatar
   :desc     "Renders a native HTML avatar with project defaults for member images, initials, optional member text, member links, and SVG icon fallbacks."
   :alias    ::avatar
   :schema   [:map {}
              [:image {:optional true
                       :doc      "Web Awesome-compatible image source. `::image` takes precedence when both are present."}
               :string]
              [:label {:optional true
                       :doc      "Web Awesome-compatible accessible label."}
               :string]
              [:initials {:optional true
                          :doc      "Web Awesome-compatible initials shown when no image is present."}
               :string]
              [:loading {:optional true
                         :default  "eager"
                         :doc      "Web Awesome-compatible image loading behavior."}
               [:enum "eager" "lazy"]]
              [:shape {:optional true
                       :default  "circle"
                       :doc      "Web Awesome-compatible avatar shape."}
               [:enum "circle" "square" "rounded"]]
              [::member {:optional true
                         :doc      "Member map used to derive label, image, initials, text, and profile link."}
               :map]
              [::name {:optional true
                       :doc      "Display name used for the accessible label, initials, and optional text."}
               :string]
              [::avatar-template {:optional true
                                  :doc      "Discourse avatar template with an optional `{size}` placeholder."}
               [:maybe :string]]
              [::image {:optional true
                        :doc      "Avatar image URL. Overrides `:image` and member avatar templates."}
               :string]
              [::image-size {:optional true
                             :default  80
                             :doc      "Size used when expanding an avatar template."}
               [:or :int :string]]
              [::initials {:optional true
                           :doc      "Initials to show as an image fallback. Overrides `:initials`."}
               :string]
              [::text {:optional true
                       :default  :none
                       :doc      "Visible text to render next to the avatar."}
               [:enum :none :name :nick]]
              [::link? {:optional true
                        :default  true
                        :doc      "When true, link to `::href` or the member detail page when a member id is available."}
               :boolean]
              [::href {:optional true
                       :doc      "Explicit link for the avatar or avatar/text wrapper."}
               :string]
              [::wrapper-attrs {:optional true
                                :doc      "Attributes merged into the link or avatar/text wrapper."}
               [:map {:closed false}]]
              [::text-attrs {:optional true
                             :doc      "Attributes merged into the visible text span."}
               [:map {:closed false}]]
              [::icon {:optional true
                       :doc      "Project SVG icon name to show when no image or initials are available."}
               [:or :keyword :string]]
              [::icon-library {:optional true
                               :default  :snoico
                               :doc      "Project SVG icon library for `::icon`."}
               [:or :keyword :string]]
              [::icon-attrs {:optional true
                             :doc      "Extra attributes for the generated icon."}
               [:map {:closed false}]]]})

(def ^{:doc (uic/generate-docstring doc-avatar)} Avatar
  ::avatar)

(def ^:private consumed-props
  #{::member ::name ::avatar-template ::image ::image-size ::initials ::text ::link? ::href
    ::wrapper-attrs ::text-attrs ::icon ::icon-library ::icon-attrs})

(def ^:private content-props
  #{:image :label :initials :loading})

(defn- avatar-name
  [attrs]
  (or (::name attrs)
      (some-> attrs ::member member-name)))

(defn- avatar-image
  [attrs]
  (or (::image attrs)
      (:image attrs)
      (avatar-template-src
       (or (::avatar-template attrs)
           (some-> attrs ::member member-avatar-template))
       (or (::image-size attrs) 80))))

(defn- avatar-initials
  [attrs name]
  (or (::initials attrs)
      (:initials attrs)
      (initials name)))

(defn- avatar-values
  [attrs]
  (let [name     (avatar-name attrs)
        image    (not-empty (avatar-image attrs))
        initials (not-empty (avatar-initials attrs name))]
    {:image    image
     :initials initials
     :label    (or (:label attrs) name initials)
     :loading  (if (contains? attrs :loading) (:loading attrs) "eager")}))

(defn- avatar-attrs
  [attrs]
  (-> (apply dissoc attrs (into consumed-props content-props))
      (uic/merge-attrs :class "sno-avatar")))

(defn- icon-child
  [attrs]
  [ico/Icon (merge {::ico/library (or (::icon-library attrs) :snoico)
                    ::ico/name    (or (::icon attrs) :user)}
                   (::icon-attrs attrs))])

(defn- avatar-content
  [attrs children {:keys [image initials label loading]}]
  (cond
    image
    [:img {:src        image
           :loading    loading
           :role       "img"
           :aria-label label
           :class      "image"}]

    initials
    [:span {:role "img" :aria-label label :class "initials"} initials]

    :else
    (into [:span {:role "img" :aria-label label :class "icon"}]
          (if (seq children) children [(icon-child attrs)]))))

(defn- member-text
  [attrs]
  (case (or (::text attrs) :none)
    :name (avatar-name attrs)
    :nick (or (some-> attrs ::member member-nick)
              (avatar-name attrs))
    nil))

(defn- link-href
  [attrs]
  (when (not= false (::link? attrs))
    (or (::href attrs)
        (when-let [id (some-> attrs ::member member-id)]
          (urls/link-member id)))))

(defn- wrapper-attrs
  [attrs href text]
  (let [wrapper-attrs (or (::wrapper-attrs attrs) {})
        name          (avatar-name attrs)
        wrapper-attrs (cond-> wrapper-attrs
                        (and href (not text) name (not (:aria-label wrapper-attrs)))
                        (assoc :aria-label name))]
    (cond-> wrapper-attrs
      href (assoc :href href)
      text (update :class uic/cs "wa-flank wa-gap-xs wa-align-items-center"))))

(defn- text-node
  [attrs text]
  (when text
    [:span (::text-attrs attrs) text]))

(defmethod c/resolve-alias ::avatar
  [_ attrs children]
  (let [attrs           (or attrs {})
        children        (filter some? children)
        text            (member-text attrs)
        href            (link-href attrs)
        wrapper?        (or text href (seq (::wrapper-attrs attrs)))
        avatar-element  [:span (avatar-attrs attrs)
                         (avatar-content attrs children (avatar-values attrs))]
        wrapper-element (if href :a :span)]
    (uic/validate-opts! doc-avatar attrs)
    (cc/compile
     (if wrapper?
       (into [wrapper-element (wrapper-attrs attrs href text)]
             (remove nil? [avatar-element (text-node attrs text)]))
       avatar-element))))
