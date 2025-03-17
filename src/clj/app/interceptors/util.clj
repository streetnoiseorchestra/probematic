(ns app.interceptors.util
  (:require
   [sieppari.context :as sieppari.context]))

(defn terminate
  "Halts interceptor chain execution and begins the leave phase. When called:
   - Clears remaining interceptors from the queue
   - Immediately starts executing :leave functions in reverse order
   - Optionally accepts a response to set before termination

   Usage:
   (terminate context)             ;; just terminate
   (terminate context response)    ;; set response and terminate"
  ([ctx]
   (sieppari.context/terminate ctx))
  ([ctx response]
   (sieppari.context/terminate ctx response)))
