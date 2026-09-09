(ns app.markdown
  (:require
   [medley.core :as m]
   [nextjournal.markdown :as md]
   [nextjournal.markdown.transform :as md.transform]
   [clojure.string :as str]))

(defn nop [_ _] "")

(def custom-renderers {:plain (partial md.transform/into-markup [:span])})

(def default-renderers (merge md.transform/default-hiccup-renderers
                              custom-renderers))

(def one-line-renderers
  (-> (m/map-vals (fn [_] nop) md.transform/default-hiccup-renderers)
      (assoc :paragraph (partial md.transform/into-markup [:span]))
      (assoc :text (fn [_ {:keys [text]}] text))
      (assoc :link (:link md.transform/default-hiccup-renderers))
      (assoc :doc (partial md.transform/into-markup [:span]))))

(defn render [text]
  [:div {:class "prose prose-blue"}
   (when-not (str/blank? text)
     (md.transform/->hiccup default-renderers (md/parse text)))])

(defn render-one-line [text]
  [:span {:class "prose prose-blue"}
   (when-not (str/blank? text)
     (md.transform/->hiccup one-line-renderers
                            (md/parse text)))])
