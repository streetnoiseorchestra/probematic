(ns app.interceptors.compression
  (:require [app.brotli :as br]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io Closeable File InputStream PipedInputStream PipedOutputStream)
           (java.util.zip GZIPOutputStream)))

(defn accepts-gzip?
  [req]
  (when-let [accepts (get-in req [:headers "accept-encoding"])]
    (re-seq
     #"(gzip\s*,?\s*(gzip|deflate)?|X{4,13}|~{4,13}|\-{4,13})"
     accepts)))

(defn accepts-brotli?
  [req]
  (when-let [accepts (get-in req [:headers "accept-encoding"])]
    (re-seq #"(br\s*)" accepts)))

(accepts-brotli? {:headers {"accept-encoding" "gzip, deflate, br, zstd"}})
(accepts-brotli? {:headers {"accept-encoding" "gzip, deflate"}})
(accepts-brotli? {:headers {"accept-encoding" "br"}})

(accepts-gzip? {:headers {"accept-encoding" "gzip, deflate, br, zstd"}})
(accepts-gzip? {:headers {"accept-encoding" "gzip, deflate"}})
(accepts-gzip? {:headers {"accept-encoding" "gzip, deflate"}})

(defn- set-response-headers
  [headers encoding-type]
  (if-let [vary (or (get headers "vary") (get headers "Vary"))]
    (-> headers
        (assoc "Vary" (str vary ", Accept-Encoding"))
        (assoc "Content-Encoding" encoding-type)
        (dissoc "Content-Length" "content-length")
        (dissoc "vary"))
    (-> headers
        (assoc "Vary" "Accept-Encoding")
        (assoc "Content-Encoding" encoding-type)
        (dissoc "Content-Length" "content-length"))))

(def ^:private supported-status? #{200 201 202 203 205 403 404})

(defn- unencoded-type?
  [headers]
  (if (headers "content-encoding")
    false
    true))

(defn supported-content-type? [content-type]
  (when content-type
    (some
     #(contains? #{"text/css" "text/javascript" "image/svg+xml"
                   "application/json" "application/edn"
                   "image/png" "image/x-icon" "text/xml"} %)
     (str/split content-type #";"))))

(supported-content-type? "text/css; charset=utf-8")
(supported-content-type? "text/css")
(supported-content-type? nil)

(defn- supported-type?
  [resp]
  (let [{:keys [headers body]
         :or   {headers {}}} resp]
    (or (string? body)
        (seq? body)
        (instance? InputStream body)
        (supported-content-type? (get headers "content-type"))
        (and (instance? File body)
             (re-seq #"(?i)\.(htm|html|css|js|json|xml)" (pr-str body))))))

(def ^:private min-length 859)

(defn- supported-size?
  [resp]
  (let [{body :body} resp]
    (cond
      (string? body)        (> (count body) min-length)
      (seq? body)           (> (count body) min-length)
      (instance? File body) (> (.length ^File body) min-length)
      :else                 true)))

(defn- supported-response?
  [resp]
  (let [{:keys [status headers]} resp]
    (and (supported-status? status)
         (unencoded-type? headers)
         (supported-type? resp)
         (supported-size? resp))))

(defn- gzip-compress-body
  [body]
  (let [p-in  (PipedInputStream.)
        p-out (PipedOutputStream. p-in)]
    (future
      (with-open [out (GZIPOutputStream. p-out)]
        (if (seq? body)
          (doseq [string body] (io/copy (str string) out))
          (io/copy body out)))
      (when (instance? Closeable body)
        (.close ^Closeable body)))
    p-in))

(defn- brotli-compress-body
  [body]
  (let [p-in  (PipedInputStream.)
        p-out (PipedOutputStream. p-in)]
    (future
      (with-open [out (br/compress-out-stream p-out)]
        (if (seq? body)
          (doseq [string body] (io/copy (str string) out))
          (io/copy body out)))
      (when (instance? Closeable body)
        (.close ^Closeable body)))
    p-in))

(defn- gzip-response
  [resp]
  (-> resp
      (update-in [:headers] set-response-headers "gzip")
      (update-in [:body] gzip-compress-body)))

(defn- brotli-response
  [resp]
  (-> resp
      (update-in [:headers] set-response-headers "br")
      (update-in [:body] brotli-compress-body)))

(def compress-response-interceptor
  {:name  ::compress-response
   :leave (fn [{:keys [request response] :as ctx}]
            (if (supported-response? response)
              (cond
                (accepts-brotli? request) (assoc ctx :response (brotli-response response))
                (accepts-gzip? request)   (assoc ctx :response (gzip-response response))
                :else
                ctx)
              ctx))})
