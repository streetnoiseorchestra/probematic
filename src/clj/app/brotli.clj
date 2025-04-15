;; Portions of this file are based on hyperlith code from @Anders
;; MIT License
;; Copyright (c) 2025 Anders Murphy
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:

;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.

;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.
(ns app.brotli
  (:require
   [clojure.java.io :as io]
   [clojure.math :as m])
  (:import (com.aayushatharva.brotli4j Brotli4jLoader)
           (com.aayushatharva.brotli4j.encoder Encoder Encoder$Parameters
                                               Encoder$Mode BrotliOutputStream)
           (com.aayushatharva.brotli4j.decoder Decoder BrotliInputStream)
           (java.io ByteArrayOutputStream IOException OutputStream)))

(defonce ensure-br
  (Brotli4jLoader/ensureAvailability))

(defn window-size->kb [window-size]
  (/ (- (m/pow 2 window-size) 16) 1000))

(defn encoder-params [{:keys [quality window-size]}]
  (doto (Encoder$Parameters/new)
    (.setMode Encoder$Mode/TEXT)
    ;; LZ77 window size (0, 10-24) (default: 24)
    ;; window size is (pow(2, NUM) - 16)
    (.setWindow (or window-size 24))
    (.setQuality (or quality 5))))

(defn compress [data & {:as opts}]
  (-> (if (string? data) (String/.getBytes data) ^byte/1 data)
      (Encoder/compress (encoder-params opts))))

(defn byte-array-out-stream ^ByteArrayOutputStream []
  (ByteArrayOutputStream/new))

(defn compress-out-stream ^BrotliOutputStream
  [^ByteArrayOutputStream out-stream & {:as opts}]
  (BrotliOutputStream/new out-stream (encoder-params opts)
                          ;; TODO: Default buffer size for brotli library, needs to be tuned.
                          16384))

(defn compress-stream [^ByteArrayOutputStream out ^BrotliOutputStream br chunk]
  (doto br
    (.write  (String/.getBytes chunk))
    (.flush))
  (let [result (.toByteArray out)]
    (.reset out)
    result))

(defn decompress [data]
  (let [decompressed (Decoder/decompress data)]
    (String/new (.getDecompressedData decompressed))))

(defn decompress-stream [data]
  (with-open [in  (-> (if (string? data) (String/.getBytes data) data)
                      io/input-stream
                      (BrotliInputStream/new))
              out (ByteArrayOutputStream/new)]
    (.enableEagerOutput in)
    (try ;; Allows decompressing of incomplete streams
      (loop [read (.read in)]
        (when (> read -1)
          (.write out read)
          (recur (.read in))))
      (catch IOException _))
    (str out)))

(comment
  (decompress (compress "hellohellohello")))

(defn ->brotli-os
  "Make a BrotliOutputStream from an OutputStream `os` and an optional
  `buffer-size`.

  Default buffer-size is the BrotliOutputStream's own.
  "
  ([^OutputStream os]
   (BrotliOutputStream/new os (encoder-params nil)
                           ;; TODO: Default buffer size for brotli library, needs to be tuned.
                           16384))
  ([^OutputStream os buffer-size]
   (BrotliOutputStream/new os (encoder-params nil)
                           buffer-size)))
