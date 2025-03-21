;; MIT License
;; part of clojure-plus https://github.com/tonsky/clojure-plus/blob/4db2bb081045d19034a93b33fd513c51cc4cddcc/src/clojure%2B/error.clj
;; Copyright (c) 2025 Nikita Prokopov
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
(ns app.util.error
  (:require
   [clojure.string :as str])
  (:import
   [clojure.lang Compiler ExceptionInfo MultiFn]
   [java.io Writer]))

(defn- noise? [^StackTraceElement el]
  (let [class (.getClassName el)]
    (#{"clojure.lang.RestFn" "clojure.lang.AFn"} class)))

(defn- duplicate? [^StackTraceElement prev-el ^StackTraceElement el]
  (and
   (= (.getClassName prev-el) (.getClassName el))
   (= (.getFileName prev-el) (.getFileName el))
   (#{"invokeStatic"} (.getMethodName prev-el))
   (#{"invoke" "doInvoke" "invokePrim"} (.getMethodName el))))

(defn- clear-duplicates [els]
  (for [[prev-el el] (map vector (cons nil els) els)
        :when        (or (nil? prev-el) (not (duplicate? prev-el el)))]
    el))

(defn- trace-element [^StackTraceElement el]
  (let [file     (.getFileName el)
        line     (.getLineNumber el)
        cls      (.getClassName el)
        method   (.getMethodName el)
        clojure? (if file
                   (or (.endsWith file ".clj") (.endsWith file ".cljc") (= file "NO_SOURCE_FILE"))
                   (#{"invoke" "doInvoke" "invokePrim" "invokeStatic"} method))

        [ns separator method]
        (cond
          (not true #_(:clean? config))
          [cls "." method]

          (not clojure?)
          [(-> cls (str/split #"\.") last) "." method]

          (#{"invoke" "doInvoke" "invokeStatic"} method)
          (let [[ns method] (str/split (Compiler/demunge cls) #"/" 2)
                method      (-> method
                                (str/replace #"eval\d{3,}" "eval")
                                (str/replace #"--\d{3,}" ""))]
            [ns "/" method])

          :else
          [(Compiler/demunge cls) "/" (Compiler/demunge method)])]
    {:element   el
     :file      (if (= "NO_SOURCE_FILE" file) nil file)
     :line      line
     :ns        ns
     :separator separator
     :method    method}))

(defn clean-trace [trace]
  (->> trace
       (remove noise?)
       (clear-duplicates)
       (mapv trace-element)))

(defmacro write [w & args]
  (list* 'do
         (for [arg args]
           (if (or (string? arg) (= String (:tag (meta arg))))
             `(Writer/.write ~w ~arg)
             `(Writer/.write ~w (str ~arg))))))

(defn- pad [ch ^long len]
  (when (pos? len)
    (let [sb (StringBuilder. len)]
      (.repeat sb (int ch) len)
      (str sb))))

(defn- longest-method [trace]
  (reduce max 0
          (for [el trace]
            (+  (count (:ns el)) (count (:separator el)) (count (:method el))))))

(defn print-trace [trace w]
  (let [depth   0
        max-len (longest-method trace)
        indent  (pad \space depth)]
    (loop [trace  trace
           first? false]
      (when-not (empty? trace)
        (let [el                                      (first trace)
              {:keys [ns separator method file line]} el
              right-pad                               (pad \space (- max-len depth (count ns) (count separator) (count method)))]
          (when-not first?
            (write w "\n" indent "  "))
          (write w "[" ns separator method)
          (cond
            (= -2 line)
            (write w right-pad "  :native-method")

            file
            (do
              (write w right-pad "  ")
              (print-method file w)
              (write w " " line)))
          (write w "]")
          (recur (next trace) false))))))
