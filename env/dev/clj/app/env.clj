(ns app.env)

(def defaults
  {:init    (fn []
              (println "\n-=[ starting using the development or test profile]=-"))
   :started (fn []
              (println "\n-=[ started successfully using the development or test profile]=-"))
   :stop    (fn []
              (println "\n-=[ has shut down successfully]=-"))
   :opts    {:profile       :dev
             :persist-data? true}})
