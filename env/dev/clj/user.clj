(ns user
  (:require
   [com.brunobonacci.mulog.publisher :as pp]
   [com.brunobonacci.mulog.buffer :as rb]))

(deftype TapPublisher [buffer transform]
  pp/PPublisher
  (agent-buffer [_]
    buffer)

  (publish-delay [_]
    200)

  (publish [_ buffer]
    (doseq [item (transform (map second (rb/items buffer)))]
      (tap> item))
    (rb/clear buffer)))

(defn tap-publisher
  [{:keys [transform] :as _config}]
  (TapPublisher. (rb/agent-buffer 10000) (or transform identity)))

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev)
  (in-ns 'dev)
  :loaded)

(comment

  ;; Clear all values in the portal inspector window
  (p/clear)

  ;; Close the inspector
  (p/close)) ;; End of rich comment block

(comment
  (dev)

  (do
    (require '[com.brunobonacci.mulog :as mu])
    (require '[clojure.tools.namespace.repl :as repl])
    (require '[portal.api :as p]))

  (p/open {:theme :portal.colors/gruvbox})

  (repl/disable-reload! *ns*)
  (p/open {:theme :portal.colors/gruvbox})
  (add-tap portal.api/submit)
  (dev)

  (mu/log ::my-event ::ns (ns-publics *ns*))
  ;;
  )
