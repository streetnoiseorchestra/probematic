(ns app.gigs.song-plan.views
  (:require
   [app.html :as html]))

(defn pop-helper-script []
  (html/squint-inline
   (let [pop-class     "pop"
         pending-class "pending-pop"
         confirm-class "confirm-pop"
         duration      300]
     (set! js/window.snoSongPlanPop (new Object))
     (set! js/window.snoSongPlanPop.pending
           (fn [target]
             (when target
               (.add target.classList pending-class))))
     (set! js/window.snoSongPlanPop.finish
           (fn [target]
             (when target
               (let [pending?        (.contains target.classList pending-class)
                     animation-class (if pending? confirm-class pop-class)]
                 (.remove target.classList pending-class)
                 (.remove target.classList pop-class)
                 (.remove target.classList confirm-class)
                 (void target.offsetWidth)
                 (.add target.classList animation-class)
                 (setTimeout (fn []
                               (.remove target.classList animation-class))
                             duration))))))))

(defn selected-song-ids [songs]
  (set (map :song/song-id songs)))

(defn repertoire-song? [repertoire-filter song]
  (case repertoire-filter
    "current" (:song/active? song)
    "old" (not (:song/active? song))
    "all" true))

(defn visible-song-choices [songs repertoire-filter]
  (filter #(repertoire-song? repertoire-filter %) songs))

(defn error-callout [error]
  (when-let [message (:error error)]
    [:wa-callout {:variant "danger"}
     message]))

(defn selected-count [songs]
  [:span {:class "gigs-probeplan-editor-count"}
   (str (count songs) " selected")])
