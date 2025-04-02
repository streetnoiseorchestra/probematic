(ns browser
  #_(:require [etaoin.api :as eta]
              [clojure.core.async :as async]))

(comment
  (defn debounce [in timeout-atom]
    (let [out (async/chan)]
      (async/go-loop [last-val nil]
        (let [val          (if (nil? last-val) (async/<! in) last-val)
              ms           @timeout-atom
              timer        (async/timeout ms)
              [new-val ch] (async/alts! [in timer])]
          (condp = ch
            timer (do (async/>! out val) (recur nil))
            in    (recur new-val))))
      out))

  (defonce browser (atom nil))
  (defonce refresh-ch (async/chan (async/dropping-buffer 2)))
  (defonce debounce-timeout (atom 500))
  (defonce debounce-ch (debounce refresh-ch debounce-timeout))

  (defn do-refresh []
    (when-let [browser @browser]
      (eta/refresh browser)))

  (defn start-refresh-receiver []
    (async/go-loop []
      (let [_ (async/<! debounce-ch)]
        (do-refresh)
        (recur))))

  (defn open-browser [url]
    (when-let [browser @browser]
      (eta/quit browser))
    (reset! browser (eta/chrome {:path-driver  "/usr/bin/chromedriver"
                                 :path-browser "/usr/bin/chromium-freeworld"}))
    (eta/go @browser url)
    (start-refresh-receiver)
    nil)

  (defn set-debounce-timeout! [ms]
    (reset! debounce-timeout ms))

  (defn refresh []
    (async/go (async/>! refresh-ch :r))
    nil)

  (+ 1 1)

  (comment
    ;; Open the browser window
    (open-browser "http://localhost:4180")

    ;; Refresh the page
    (refresh)
    ;;
    ))
