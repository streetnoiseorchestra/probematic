(ns app.insurance.public.views
  (:require
   [app.insurance.public.api :as api]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.icon :as ico]
   [clojure.string :as str]))

(defn- instrument-not-found! [instrument-id]
  (throw (ex-info "Instrument not found"
                  {:app/error-type :app.error.type/not-found
                   :instrument-id instrument-id})))

(defn- download-link [instrument-id]
  (str "/instrument-public/" instrument-id "/download-zip"))

(defn- detail-item [label value]
  (when-not (str/blank? (str value))
    [[:dt label]
     [:dd value]]))

(defn- instrument-details [tr {:instrument/keys [build-year category description make model serial-number]}]
  (into
   [:dl]
   (mapcat identity)
   (keep identity
         [(detail-item (tr [:instrument/category]) (:instrument.category/name category))
          (detail-item (tr [:instrument/make]) make)
          (detail-item (tr [:instrument/model]) model)
          (detail-item (tr [:instrument/description]) description)
          (detail-item (tr [:instrument/serial-number]) serial-number)
          (detail-item (tr [:instrument/build-year]) build-year)])))

(defn- photo-card [instrument-name {:keys [full thumbnail]}]
  [:a {:href   full
       :rel    "noopener noreferrer"
       :style  "display: block; overflow: hidden; aspect-ratio: 1; background: #f3f4f6; border-radius: 0.75rem;"
       :target "_blank"}
   [:img {:alt     instrument-name
          :loading "lazy"
          :src     thumbnail
          :style   "display: block; inline-size: 100%; block-size: 100%; object-fit: cover;"}]])

(defn- photo-grid [instrument-name photo-uris]
  (into [:div {:style "display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr)); gap: 1rem; margin-block-start: 1rem;"}]
        (map #(photo-card instrument-name %) photo-uris)))

(defn- public-page-shell [instrument & body]
  (apply ui2/standalone-page
         {:title       (:instrument/name instrument)
          :description (:instrument/description instrument)}
         body))

(defn instrument-public-page [{:keys [db] :as req} instrument-id]
  (let [instrument (or (q/retrieve-instrument db instrument-id)
                       (instrument-not-found! instrument-id))
        photo-uris (api/build-image-uris req instrument)]
    (public-page-shell
     instrument
     [:header
      [:p "SNOrga"]
      [:h1 (:instrument/name instrument)]]
     (when-let [category (not-empty (get-in instrument [:instrument/category :instrument.category/name]))]
       [:p category])
     (instrument-details (:tr req) instrument)
     [:div {:style "display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-block: 2rem 0 0;"}
      [:h2 {:style "margin: 0; font-size: 1.25rem; line-height: 1.2;"}
       ((:tr req) [:instrument/images])]
      (when (seq photo-uris)
        [:form {:action (download-link instrument-id) :method "get"}
         [:button {:type  "submit"
                   :style "display: inline-flex; align-items: center; gap: 0.5rem;"}
          [ico/Icon {::ico/library :phosphor
                     ::ico/name    :download
                     :style        "inline-size: 1em; block-size: 1em;"}]
          ((:tr req) [:action/download])]])]
     (if (seq photo-uris)
       (photo-grid (:instrument/name instrument) photo-uris)
       [:p ((:tr req) [:insurance/no-photos])]))))

(defn instrument-public-page-download-all [req instrument-id]
  (api/get-all-images-as-input-stream! req instrument-id))
