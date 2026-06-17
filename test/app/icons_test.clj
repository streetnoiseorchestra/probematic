(ns app.icons-test
  (:require
   [app.brotli :as br]
   [app.icons :as icons]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.util.zip GZIPInputStream)))

(def snoico-home-library
  [{:id          :snoico
    :source-root "public/img/snoico"
    :icons       [:home]}])

(defn- response-bytes [{:keys [body]}]
  (cond
    (string? body)
    (.getBytes ^String body "UTF-8")

    :else
    (with-open [out (ByteArrayOutputStream.)]
      (io/copy body out)
      (.toByteArray out))))

(defn- gunzip-string [^bytes bytes]
  (with-open [in (GZIPInputStream. (ByteArrayInputStream. bytes))]
    (slurp in)))

(deftest default-icon-registries-reference-existing-svg-resources
  (is (nil? (icons/validate-libraries! icons/icon-libraries))))

(deftest registry-validation-rejects-duplicate-icon-names
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Duplicate icon names"
       (icons/validate-libraries!
        [{:id          :snoico
          :source-root "public/img/snoico"
          :icons       [:home :home]}]))))

(deftest registry-validation-rejects-missing-svg-resources
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Cannot load registered icon resource"
       (icons/validate-libraries!
        [{:id          :snoico
          :source-root "public/img/snoico"
          :icons       [:not-a-real-icon]}]))))

(deftest sprite-manifest-builds-fingerprinted-library-sprites
  (let [manifest (icons/build-sprite-manifest snoico-home-library)
        library  (get-in manifest [:by-library :snoico])]
    (is (= {:filename? true
            :url       (:url library)
            :etag?     true
            :icon-href "/img/icons/"
            :body?     true
            :icon?     {:symbol-id "snoico-home" :viewBox "0 0 24 24"}}
           {:filename? (boolean (re-matches #"snoico\.[0-9a-f]{16}\.svg" (:filename library)))
            :url       (:url library)
            :etag?     (boolean (seq (:etag library)))
            :icon-href (subs (icons/sprite-href manifest :snoico :home) 0 11)
            :body?     (str/includes? (:body library) "<symbol id=\"snoico-home\" viewBox=\"0 0 24 24\"")
            :icon?     (get-in library [:icons :home])}))))

(deftest sprite-builder-removes-source-title-and-root-accessibility-from-symbols
  (let [manifest (icons/build-sprite-manifest
                  [{:id          :iconoir-test
                    :source-root "public/img/iconoir"
                    :icons       [:nav-arrow-right]}])
        body     (get-in manifest [:by-library :iconoir-test :body])]
    (is (= {:has-symbol? true
            :has-title?  false
            :has-role?   false
            :has-label?  false}
           {:has-symbol? (str/includes? body "<symbol id=\"iconoir-test-nav-arrow-right\"")
            :has-title?  (str/includes? body "<title>")
            :has-role?   (str/includes? body "role=\"img\"")
            :has-label?  (str/includes? body "aria-label=")}))))

(deftest sprite-builder-preserves-root-presentation-attrs-for-stroke-icons
  (let [manifest (icons/build-sprite-manifest
                  [{:id          :snoico
                    :source-root "public/img/snoico"
                    :icons       [:bars]}])
        body     (get-in manifest [:by-library :snoico :body])]
    (is (= {:has-fill-none?    true
            :has-stroke?       true
            :has-stroke-width? true
            :has-aria-hidden?  false}
           {:has-fill-none?    (str/includes? body "fill=\"none\"")
            :has-stroke?       (str/includes? body "stroke=\"currentColor\"")
            :has-stroke-width? (str/includes? body "stroke-width=\"1.5\"")
            :has-aria-hidden?  (str/includes? body "aria-hidden=")}))))

(deftest sprite-response-serves-memory-sprite-with-immutable-cache-headers
  (let [manifest (icons/build-sprite-manifest snoico-home-library)
        filename (get-in manifest [:by-library :snoico :filename])
        response (icons/sprite-response manifest filename)
        body     (response-bytes response)]
    (is (= {:status         200
            :content-type   "image/svg+xml; charset=utf-8"
            :cache-control  "public, max-age=31536000, immutable"
            :content-length (str (alength ^bytes body))
            :vary           "Accept-Encoding"
            :etag?          true
            :has-symbol?    true}
           {:status         (:status response)
            :content-type   (get-in response [:headers "Content-Type"])
            :cache-control  (get-in response [:headers "Cache-Control"])
            :content-length (get-in response [:headers "Content-Length"])
            :vary           (get-in response [:headers "Vary"])
            :etag?          (boolean (seq (get-in response [:headers "ETag"])))
            :has-symbol?    (str/includes? (String. ^bytes body "UTF-8") "snoico-home")}))))

(deftest sprite-response-serves-precompressed-brotli-when-accepted
  (let [manifest (icons/build-sprite-manifest snoico-home-library)
        filename (get-in manifest [:by-library :snoico :filename])
        response (icons/sprite-response {:headers {"accept-encoding" "gzip, br"}} manifest filename)
        body     (response-bytes response)]
    (is (= {:status           200
            :content-encoding "br"
            :content-length   (str (alength ^bytes body))
            :vary             "Accept-Encoding"
            :body             (get-in manifest [:by-library :snoico :body])}
           {:status           (:status response)
            :content-encoding (get-in response [:headers "content-encoding"])
            :content-length   (get-in response [:headers "Content-Length"])
            :vary             (get-in response [:headers "Vary"])
            :body             (br/decompress body)}))))

(deftest sprite-response-serves-precompressed-gzip-when-accepted
  (let [manifest (icons/build-sprite-manifest snoico-home-library)
        filename (get-in manifest [:by-library :snoico :filename])
        response (icons/sprite-response {:headers {"accept-encoding" "gzip"}} manifest filename)
        body     (response-bytes response)]
    (is (= {:status           200
            :content-encoding "gzip"
            :content-length   (str (alength ^bytes body))
            :vary             "Accept-Encoding"
            :body             (get-in manifest [:by-library :snoico :body])}
           {:status           (:status response)
            :content-encoding (get-in response [:headers "content-encoding"])
            :content-length   (get-in response [:headers "Content-Length"])
            :vary             (get-in response [:headers "Vary"])
            :body             (gunzip-string body)}))))

(deftest sprite-response-returns-404-for-unknown-fingerprinted-filename
  (is (= {:status 404
          :body   "Icon sprite not found"}
         (select-keys (icons/sprite-response (icons/build-sprite-manifest snoico-home-library)
                                             "missing.svg")
                      [:status :body]))))

(deftest sprite-href-rejects-unregistered-icons
  (let [manifest (icons/build-sprite-manifest snoico-home-library)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Icon is not registered"
         (icons/sprite-href manifest :snoico :missing)))))
