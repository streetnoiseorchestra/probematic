(ns app.datastar-test
  (:require
   [app.datastar]
   [clojure.test :refer [deftest is testing]]
   [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
   [starfederation.datastar.clojure.adapter.test :as d*test]
   [starfederation.datastar.clojure.api :as d*]))

(def request
  {:protocol "HTTP/1.1"})

(defn invoke-respond-sse
  ([events]
   (invoke-respond-sse events (atom 0)))
  ([events opened_]
   (if-let [respond-sse (ns-resolve 'app.datastar 'respond-sse)]
     (with-redefs [hk-gen/->sse-response
                   (fn [req opts]
                     (swap! opened_ inc)
                     (d*test/->sse-response req opts))]
       (let [response (respond-sse request events)]
         (cond-> response
           (volatile? (:body response)) (update :body deref))))
     ::missing-respond-sse)))

(deftest respond-sse-emits-events-in-declared-order-test
  (testing "one finite response emits ordered signal, element, script, and redirect events"
    (is (=
         {:status 200
          :headers {"Cache-Control" "no-cache"
                    "Content-Type" "text/event-stream"}
          :body
          ["event: datastar-patch-signals\nid: remove-1\ndata: signals {\"profile\":{\"secret\":null}}\n\n"
           (str "event: datastar-patch-elements\n"
                "data: selector #main\n"
                "data: useViewTransition true\n"
                "data: elements <div id=\"result\">Saved</div>\n\n")
           "event: datastar-patch-signals\nid: merge-1\ndata: signals {\"loading\":false}\n\n"
           (str "event: datastar-patch-elements\n"
                "data: selector body\n"
                "data: mode append\n"
                "data: elements <script>window.saved = true;</script>\n\n")
           (str "event: datastar-patch-elements\n"
                "data: selector body\n"
                "data: mode append\n"
                "data: elements <script data-effect=\"el.remove()\">"
                "setTimeout(() => window.location.href =\"/next\")</script>\n\n")]}
         (invoke-respond-sse
          [[:app.datastar.sse/remove-signals
            ["profile.secret"]
            {d*/id "remove-1"}]
           [:app.datastar.sse/patch-elements
            "<div id=\"result\">Saved</div>"
            {d*/selector "#main"
             d*/use-view-transition true}]
           [:app.datastar.sse/merge-signals
            {:loading false}
            {d*/id "merge-1"}]
           [:app.datastar.sse/execute-script
            "window.saved = true;"
            {d*/auto-remove false}]
           [:app.datastar.sse/redirect "/next"]])))))

(deftest respond-sse-allows-repeated-event-kinds-test
  (testing "two signal patches remain two ordered SSE events"
    (is (=
         ["event: datastar-patch-signals\ndata: signals {\"step\":1}\n\n"
          "event: datastar-patch-signals\ndata: signals {\"step\":2}\n\n"]
         (:body
          (invoke-respond-sse
           [[:app.datastar.sse/merge-signals {:step 1}]
            [:app.datastar.sse/merge-signals {:step 2}]]))))))

(deftest respond-sse-allows-an-empty-event-vector-test
  (testing "a finite response can close without emitting an event"
    (is (= {:status 200
            :headers {"Cache-Control" "no-cache"
                      "Content-Type" "text/event-stream"}
            :body []}
           (invoke-respond-sse [])))))

(deftest respond-sse-validates-before-opening-the-channel-test
  (testing "an unknown event cannot claim the Http-kit channel"
    (let [opened_ (atom 0)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unknown Datastar SSE event"
           (invoke-respond-sse
            [[:app.datastar.sse/unknown {:value true}]]
            opened_)))
      (is (zero? @opened_)))))

(deftest respond-sse-closes-the-channel-when-event-emission-fails-test
  (testing "an emitter failure cannot strand an opened finite response"
    (let [closed_ (atom 0)]
      (with-redefs [d*/patch-signals! (fn [& _]
                                        (throw (ex-info "emit failed" {})))
                    d*/close-sse!     (fn [_]
                                        (swap! closed_ inc))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"emit failed"
             (invoke-respond-sse
              [[:app.datastar.sse/merge-signals {:loading false}]])))
        (is (= 1 @closed_))))))

(deftest respond-sse-preserves-events-after-redirect-test
  (testing "redirect is an SSE event and does not discard later declared events"
    (is (= [(str "event: datastar-patch-elements\n"
                 "data: selector body\n"
                 "data: mode append\n"
                 "data: elements <script data-effect=\"el.remove()\">"
                 "setTimeout(() => window.location.href =\"/next\")</script>\n\n")
            "event: datastar-patch-signals\ndata: signals {\"after-redirect\":true}\n\n"]
           (:body
            (invoke-respond-sse
             [[:app.datastar.sse/redirect "/next"]
              [:app.datastar.sse/merge-signals {:after-redirect true}]]))))))
