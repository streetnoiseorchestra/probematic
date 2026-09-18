(ns app.datastar-test
  (:require
   [app.datastar :as datastar]
   [clojure.test :refer [deftest is]]))

(deftest response-plans-preserve-event-order-and-repeated-kinds
  (let [events [[:app.datastar.sse/merge-signals {:step 1}]
                [:app.datastar.sse/redirect "/next"]
                [:app.datastar.sse/merge-signals {:step 2}]]
        plan   (datastar/sse-response-plan events)]
    (is (datastar/sse-response-plan? plan))
    (is (= events (datastar/sse-response-events plan)))
    (is (= [] (datastar/sse-response-events (datastar/sse-response-plan []))))))

(deftest response-plans-reject-unknown-events
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown Datastar SSE event"
                        (datastar/sse-response-plan [[:app.datastar.sse/unknown {}]]))))

(deftest absent-runtime-cannot-render-outside-the-frame
  (is (= 503 (:status (datastar/render-in-frame! nil (fn [_] (throw (ex-info "must not render" {}))))))))
