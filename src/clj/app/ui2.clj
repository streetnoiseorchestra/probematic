(ns app.ui2
  (:require
   [app.html :as html]
   [app.humanize :as humanize]
   [app.i18n :as i18n]
   [app.icons :as icons]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [starfederation.datastar.clojure.expressions :refer [->expr]]
   [tick.core :as t])
  (:import
   [java.text NumberFormat]
   [java.time LocalDate]
   [java.time.chrono IsoChronology]
   [java.time.format DateTimeFormatter DateTimeFormatterBuilder FormatStyle]
   [java.util Locale]))

(defn safe-dom-id
  "Returns `value` as a safe DOM id fragment.

  Required: `value`."
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")))

(defn remove-dialog-id
  "Builds the standard remove-dialog DOM id.

  Required: `prefix` and `ent-id`."
  [prefix ent-id]
  (str prefix "-remove-" (safe-dom-id ent-id)))

(defn active-badge
  "Renders a translated active/inactive badge.

  Required: `tr` translation function and `active?`."
  [tr active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active?       (assoc :variant "success")
               (not active?) (assoc :variant "neutral"))
   (if active?
     (tr [:Active])
     (tr [:Inactive]))])

(defn cs
  "Joins truthy class names with spaces.

  Optional: zero or more `names`."
  [& names]
  (str/join " " (filter identity names)))

(defn muted
  "Renders `value`, or an em dash when `value` is blank."
  [value]
  (if (str/blank? (str value))
    [:span {:class "wa-color-text-quiet"} html/emdash]
    value))

(defn markdown-editor-scripts
  "Returns the shared EasyMDE assets and Datastar-safe markdown editor initializer."
  []
  [[:link {:rel "stylesheet" :href "/css/easymde.min@2.18.0.css"}]
   [:script
    (html/raw
     "
     window.EasyMDEReady ||= import('/js/easymde.min@2.18.0.js');
     window.MarkdownEditor = async function MarkdownEditor(target) {
        await window.EasyMDEReady;
        if (!target || target.dataset.easymdeInitialized === 'true') return;
        target.dataset.easymdeInitialized = 'true';
        const imageUploadEndpoint = target.getAttribute('data-image-upload-endpoint');
        const hasUpload = !!imageUploadEndpoint;
        const maxSizeMB = 10;
        const maxSizeB = 1024 * 1024 * maxSizeMB;
        const easyMDE = new EasyMDE({
          element: target,
          spellChecker: false,
          forceSync: true,
          promptURLs: true,
          uploadImage: hasUpload,
          autoDownloadFontAwesome: false,
          previewImagesInEditor: true,
          toolbar: [
            'bold',
            'italic',
            'strikethrough',
            'heading',
            '|',
            'quote',
            'code',
            'unordered-list',
            'ordered-list',
            'clean-block',
            '|',
            'link',
            'upload-image',
            'table',
            'horizontal-rule',
            '|',
            'preview',
            'side-by-side',
            'fullscreen',
          ],
          imageUploadFunction: (file, onSuccess, onError) => {
            if (file.size > maxSizeB) {
              onError(`File is too big! Maximum size is ${maxSizeMB}MB.`);
              return;
            }

            const validMimeTypes = [
              'image/jpeg',
              'image/png',
              'image/gif',
              'image/jpg',
            ];
            if (!validMimeTypes.includes(file.type)) {
              onError('Invalid file type. Only JPG, PNG, and GIF files are allowed.');
              return;
            }

            let formData = new FormData();
            formData.append('file', file);

            let xhr = new XMLHttpRequest();

            xhr.onreadystatechange = function () {
              if (xhr.readyState !== 4) return;
              if (xhr.status === 201) {
                const response = JSON.parse(xhr.responseText);
                onSuccess(response['file-url']);
              } else {
                const response = JSON.parse(xhr.responseText);
                onError(response.error);
              }
            };

            xhr.onerror = function () {
              onError('XMLHttpRequest error.');
            };

            xhr.open('POST', imageUploadEndpoint, true);
            xhr.send(formData);
          },
        });
        easyMDE.codemirror.on('change', function () {
          target.value = easyMDE.value();
          target.dispatchEvent(new Event('input', {bubbles: true}));
        });
        const container = target.parentElement && target.parentElement.querySelector('.EasyMDEContainer');
        if (container) {
          container.setAttribute('data-ignore-morph', '');
        }
        return easyMDE;
      };

      window.InitializeMarkdownEditors = function InitializeMarkdownEditors(target) {
        const root = target || document;
        root.querySelectorAll('textarea.markdown-editor').forEach(window.MarkdownEditor);
      };")]])

(def currency-default-locale
  {:EUR Locale/GERMANY
   :USD Locale/US})

(defn money-format
  "Formats numeric `value` for `currency` using the default project locale."
  [value currency]
  (when value
    (.format (NumberFormat/getCurrencyInstance (get currency-default-locale currency Locale/GERMANY)) value)))

(defn money
  "Renders formatted numeric `value` for `currency`, or an em dash when blank."
  [value currency]
  (muted (money-format value currency)))

(def ^:private date-format-styles
  {:short                FormatStyle/SHORT
   :medium               FormatStyle/MEDIUM
   :long                 FormatStyle/LONG
   :full                 FormatStyle/FULL
   :with-weekday         FormatStyle/FULL
   :compact-with-weekday :app.ui2/compact-with-weekday})

(def ^:private date-time-format-styles
  {:short        FormatStyle/SHORT
   :medium       FormatStyle/MEDIUM
   :long         FormatStyle/LONG
   :full         FormatStyle/FULL
   :with-weekday FormatStyle/FULL})

(def ^:private time-format-styles
  {:short  FormatStyle/SHORT
   :medium FormatStyle/MEDIUM})

(defn- localized-format-style [styles style]
  (or (get styles style)
      (throw (ex-info "Unknown date/time format style"
                      {:style style :available-styles (sort (keys styles))}))))

(defn- temporal-value [value]
  (when value
    (if (inst? value)
      (t/date-time value)
      value)))

(defn- date-temporal [value]
  (some-> value temporal-value t/date))

(defn- localized-formatter [locale formatter]
  (.withLocale ^DateTimeFormatter formatter locale))

(def ^:private month-day-pattern
  (memoize
   (fn [^Locale locale]
     (let [short-pattern (DateTimeFormatterBuilder/getLocalizedDateTimePattern
                          FormatStyle/SHORT
                          nil
                          IsoChronology/INSTANCE
                          locale)
           month-idx     (.indexOf short-pattern "M")
           day-idx       (.indexOf short-pattern "d")]
       (if (and (not= -1 month-idx)
                (not= -1 day-idx)
                (< month-idx day-idx))
         "MM.dd"
         "dd.MM")))))

(def ^:private compact-weekday-date-pattern "E dd MMM yyyy")

(def ^:private date-formatter
  (memoize
   (fn [locale style]
     (localized-formatter
      locale
      (case style
        :month-day
        (DateTimeFormatter/ofPattern (month-day-pattern locale))

        :compact-with-weekday
        (DateTimeFormatter/ofPattern compact-weekday-date-pattern)

        (DateTimeFormatter/ofLocalizedDate (localized-format-style date-format-styles style)))))))

(def ^:private date-time-formatter
  (memoize
   (fn [locale style]
     (localized-formatter
      locale
      (DateTimeFormatter/ofLocalizedDateTime
       (localized-format-style date-time-format-styles style)
       FormatStyle/SHORT)))))

(def ^:private time-formatter
  (memoize
   (fn [locale style]
     (localized-formatter
      locale
      (DateTimeFormatter/ofLocalizedTime (localized-format-style time-format-styles style))))))

(def ^:private compact-date-range-formatters
  (memoize
   (fn [locale]
     {:same-month-start (localized-formatter locale (DateTimeFormatter/ofPattern "E dd"))
      :same-month-end   (localized-formatter locale (DateTimeFormatter/ofPattern compact-weekday-date-pattern))
      :same-year-start  (localized-formatter locale (DateTimeFormatter/ofPattern "E dd MMM"))
      :same-year-end    (localized-formatter locale (DateTimeFormatter/ofPattern compact-weekday-date-pattern))
      :full             (localized-formatter locale (DateTimeFormatter/ofPattern compact-weekday-date-pattern))})))

(defn- same-year? [^LocalDate start-date ^LocalDate end-date]
  (= (.getYear start-date) (.getYear end-date)))

(defn- same-month? [^LocalDate start-date ^LocalDate end-date]
  (and (same-year? start-date end-date)
       (= (.getMonthValue start-date) (.getMonthValue end-date))))

(defn- format-with [formatter date]
  (t/format formatter date))

(defn- compact-date-range-labels [req start-date end-date]
  (let [{:keys [full same-month-end same-month-start same-year-end same-year-start]}
        (compact-date-range-formatters (i18n/req-locale req))]
    (cond
      (same-month? start-date end-date)
      [(format-with same-month-start start-date)
       (format-with same-month-end end-date)]

      (same-year? start-date end-date)
      [(format-with same-year-start start-date)
       (format-with same-year-end end-date)]

      :else
      [(format-with full start-date)
       (format-with full end-date)])))

(defn format-date
  "Formats `value` as a localized date for `style`.
  Date styles are `:month-day`, `:short`, `:medium`, `:long`, `:full`, `:with-weekday`, and `:compact-with-weekday`."
  [req style value]
  (when-let [date (date-temporal value)]
    (t/format (date-formatter (i18n/req-locale req) style) date)))

(defn format-date-time
  "Formats `value` as a localized date and time for `style`.
  Date-time styles are `:short`, `:medium`, `:long`, `:full`, and `:with-weekday`."
  [req style value]
  (when-let [date-time (temporal-value value)]
    (t/format (date-time-formatter (i18n/req-locale req) style) date-time)))

(defn format-time
  "Formats `value` as a localized time for `style`.
  Time styles are `:short` and `:medium`."
  [req style value]
  (when-let [time (temporal-value value)]
    (t/format (time-formatter (i18n/req-locale req) style) time)))

(defn format-date-range
  "Formats `start` and optional `end` as a localized date range for `style`."
  [req style start end]
  (when-let [start-date (date-temporal start)]
    (if-let [end-date (date-temporal end)]
      (if (= :compact-with-weekday style)
        (let [[start-label end-label] (compact-date-range-labels req start-date end-date)]
          (str start-label "–" end-label))
        (str (format-date req style start-date)
             " – "
             (format-date req style end-date)))
      (format-date req style start-date))))

(defn date-display
  "Renders `value` as a localized `<time>` element for `style`."
  [req style value]
  (if-let [date (date-temporal value)]
    [:time {:datetime (str date)} (format-date req style date)]
    html/emdash))

(defn- compact-date-range-display [req start-date end-date]
  (let [[start-label end-label] (compact-date-range-labels req start-date end-date)]
    [:span
     [:time {:datetime (str start-date)} start-label]
     [:span {:aria-hidden true} "–"]
     [:time {:datetime (str end-date)} end-label]]))

(defn date-range-display
  "Renders `start` and optional `end` as localized `<time>` elements for `style`."
  [req style start end]
  (let [start-date (date-temporal start)
        end-date   (date-temporal end)]
    (cond
      (and (= :compact-with-weekday style) start-date end-date)
      (compact-date-range-display req start-date end-date)

      (and start-date end-date)
      [:span
       (date-display req style start-date)
       [:span {:aria-hidden true} " – "]
       (date-display req style end-date)]

      start-date
      (date-display req style start-date)

      end-date
      (date-display req style end-date)

      :else
      html/emdash)))

(defn date-input-value
  "Formats `value` as an HTML date input value."
  [value]
  (when-let [date (date-temporal value)]
    (t/format (DateTimeFormatter/ofPattern "yyyy-MM-dd") date)))

(defn time-input-value
  "Formats `value` as an HTML time input value."
  [value]
  (when-let [time (temporal-value value)]
    (t/format (DateTimeFormatter/ofPattern "HH:mm") time)))

(defn relative-time-value
  "Formats `value` as a relative time such as `2 days ago`."
  [value]
  (when value
    (humanize/from (if (inst? value) (t/date-time value) value))))

(defn detail-item
  "Renders a `dl` item using the shared particulars styling."
  [label value]
  [:div
   [:dt label]
   [:dd (muted value)]])

(defn title-block
  "Renders a reusable title/subtitle block.

  Required: one of `:heading`, `:title`, or `:subtitle`.
  Optional: `:class`, `:heading`, `:level`, `:subtitle`, `:title`.
  Use `:heading` for custom Hiccup, or `:title` for an `hN` heading."
  [{:keys [class heading level subtitle title]}]
  (let [heading-tag (keyword (str "h" (or level 1)))]
    (when (or heading title subtitle)
      [:div {:class (cs "sno-title-block" "wa-stack" "wa-gap-2xs" class)}
       (cond
         heading heading
         title   [heading-tag title])
       (when subtitle
         [:span {:class "wa-caption-s"} subtitle])])))

(defn action-bar
  "Renders a responsive action button area.

  Required: `actions`, a seq of Hiccup nodes.
  Optional: `attrs`, including `:class`; nil actions are ignored."
  [{:keys [class] :as attrs} actions]
  (let [actions (filter some? actions)]
    (when (seq actions)
      (into [:div (merge {:class (cs "sno-action-bar" class)}
                         (dissoc attrs :class))]
            actions))))

(defn section-divider
  "Renders a section title with a trailing divider.

  Required: `title`."
  [title]
  (cc/compile
   [:div {:class "sno-section-divider"}
    [:h2 title]
    [divider/Divider]]))

(defn page-header
  "Renders a standard page header.

  Required: none.
  Optional: `:actions`, `:breadcrumb`, `:class`, `:heading`, `:subtitle`, `:title`.
  Extra keys become attributes on the `header`."
  [{:keys [actions breadcrumb class heading subtitle title] :as attrs}]
  [:header (merge {:class (cs "sno-page-header" "wa-stack" "wa-gap-m" class)}
                  (dissoc attrs :actions :breadcrumb :class :heading :subtitle :title))
   breadcrumb
   [:section {:class "sno-page-header-main"}
    (title-block {:heading  heading
                  :subtitle subtitle
                  :title    title})
    (action-bar {:class "sno-page-actions"} actions)]])

(defn section-card
  "Renders a Web Awesome card section with optional header actions.

  Required: none.
  Optional: `children`, `:id`, `:title`, `:subtitle`, `:actions`, `:divider?`.
  Extra keys become attributes on the `section`."
  [{:keys [id title subtitle actions divider?] :as attrs} & children]
  (into
   [:section (merge {:id    id
                     :class "wa-stack"}
                    (dissoc attrs :id :title :subtitle :actions :divider?))
    [:div {:class "sno-section-header"}
     (title-block {:level    2
                   :subtitle subtitle
                   :title    title})
     (action-bar {:class "sno-section-actions"} actions)]
    (when divider? [divider/Divider])]
   children))

(defn remove-dialog
  "Renders a reusable delete confirmation dialog.

  Required: `:id`, `:label`, `:cancel-label`, `:confirm-label`, and `body`.
  Optional: `:dialog-attrs`, `:confirm-attrs`."
  [{:keys [id label cancel-label confirm-label dialog-attrs confirm-attrs]} body]
  [:wa-dialog (merge {:id                 id
                      :label              label
                      :data-preserve-attr "open"}
                     dialog-attrs)
   body
   [button/Button {:slot        "footer"
                   :appearance  "outlined"
                   :data-dialog "close"}
    cancel-label]
   [button/Button (merge {:slot        "footer"
                          :appearance  "filled"
                          :variant     "danger"
                          :data-dialog "close"}
                         confirm-attrs)
    confirm-label]])

(defn row-action-menu
  "Renders a compact row action dropdown.

  Required: `:button-id` and `:items`.
  Optional: `:disabled?`; each item needs `:label` plus dropdown attrs."
  [{:keys [button-id disabled? items]}]
  (list
   [:wa-dropdown {:placement "bottom-end"}
    [button/Button {:id         button-id
                    :slot       "trigger"
                    :appearance "plain"
                    :disabled   disabled?
                    :aria-label "More actions"}
     [ico/Icon {::ico/library :snoico ::ico/name :ellipsis}]]
    (for [{:keys [label] :as item} items]
      [:wa-dropdown-item (dissoc item :icon)
       label])]
   [:wa-tooltip {:for button-id :without-arrow true}
    "More actions"]))

(defn table-shell
  "Wraps table-like content in the standard scrollable shell.

  Optional: zero or more `children`."
  [& children]
  (into
   [:div {:class "table-shell"}]
   children))

(defn empty-state
  "Renders a neutral empty-state callout.

  Required: `title` and `body`."
  [title body]
  [:wa-callout {:appearance "outlined" :variant "neutral"}
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong title]
    [:span body]]])

(def standalone-page-style
  ":root { --sno-brand-green: #22c55e; --sno-brand-orange: #f97316; }
   body { font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 0; background: #f7f7f7; color: #1f2933; line-height: 1.5; }
   main { max-width: 42rem; margin: 4rem auto; padding: 0 1.5rem; }
   main > header { display: flex; justify-content: center; margin: 0 0 1.25rem; }
   main > header svg { display: block; width: min(16rem, 70vw); height: auto; }
   main > header svg .logotype-snoman { fill: var(--sno-brand-green); }
   main > header svg .logotype-text { fill: var(--sno-brand-orange); }
   main > section { background: white; border: 1px solid #e5e7eb; border-radius: 0.75rem; padding: 2rem; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04); }
   section > header > p { margin: 0 0 0.5rem; color: #ea580c; font-size: 0.875rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; }
   section > header.danger > p { color: #b91c1c; }
   h1 { margin: 0; font-size: 1.875rem; line-height: 1.2; color: #111827; }
   section > p { margin: 1rem 0 0; color: #4b5563; }
   dl { margin: 1.5rem 0; padding: 1rem; background: #f9fafb; border-radius: 0.5rem; }
   dt { font-weight: 700; color: #111827; }
   dt + dd + dt { margin-top: 1rem; }
   dd { margin: 0.25rem 0 0; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; color: #374151; }
   footer { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; margin-top: 1.5rem; }
   button { background: #ea580c; color: white; border: 0; border-radius: 0.375rem; padding: 0.625rem 1rem; font: inherit; font-weight: 700; cursor: pointer; }
   button:hover { background: #c2410c; }
   footer a { color: #374151; font-weight: 700; text-decoration: none; }
   footer a:hover { color: #7c2d12; }
   code { overflow-wrap: anywhere; }")

(defn standalone-page [{:keys [description lang status title]} & body]
  {:status  (or status 200)
   :headers {"Content-Type" "text/html"}
   :body    (html/->str
             (html/html-document
              {:title       title
               :description description
               :lang        lang
               :head        [:style (html/raw standalone-page-style)]}
              [:main {:id "main"}
               [:header {:aria-label "SNOrga"}
                (icons/logotype {:aria-hidden "true"})]
               (into [:section]
                     body)]))})

(defn datastar-main-attrs
  "Returns the standard `main` attrs for Nexus-backed Datastar pages.

  Required: none."
  []
  {:id             "main"
   :data-on:submit (->expr (when evt.target.dataset.action
                             (evt.target.setAttribute "loading" "")
                             (set! $loading evt.target.dataset.id)
                             (set! $targetid evt.target.dataset.id)
                             (@post ("`${evt.target.dataset.action}`"))))
   :data-on:mousedown (->expr (when (and evt.target.dataset.action
                                         (= evt.button 0)
                                         (not (evt.target.matches "form")))
                                (evt.target.setAttribute "loading" "")
                                (set! $loading evt.target.dataset.id)
                                (set! $targetid evt.target.dataset.id)
                                (@post ("`${evt.target.dataset.action}`"))))
   :data-on:keydown (->expr (when (and evt.target.dataset.action (= evt.key "Enter"))
                              (evt.target.setAttribute "loading" "")
                              (set! $loading evt.target.dataset.id)
                              (set! $targetid evt.target.dataset.id)
                              (@post ("`${evt.target.dataset.action}`"))))})

(defn datastar-page
  "Renders `children` inside the standard Datastar `main` element.

  Optional: zero or more `children`."
  [& children]
  (html/->str
   (into [:main (datastar-main-attrs)] children)))

(defn plain-page
  "Renders `children` inside a plain `main` element.

  Optional: zero or more `children`."
  [& children]
  (html/->str
   (into [:main {:id "main"}] children)))

(defn member-nick
  "Renders the nickname of the member, if available, otherwise renders the name."
  [{:member/keys [name nick]}]
  (if (str/blank? nick)
    name
    nick))
