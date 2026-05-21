#!/usr/bin/env bb
(ns build-icon-sprites-test
  (:require
   [babashka.fs :as fs]
   [build-icon-sprites :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]))

(defn- temp-dir []
  (fs/create-temp-dir {:prefix "icon-sprite-test"}))

(deftest builds-symbols-from-svg-files
  (let [sprite (sut/build-sprite
                {"home" "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" aria-hidden=\"true\"><title>Home</title><path d=\"M0 0h24v24\"/></svg>"})]
    (is (= {:has-root-svg?        true
            :has-symbol?          true
            :keeps-viewbox?       true
            :moves-root-fill?     true
            :moves-root-stroke?   true
            :moves-root-width?    true
            :keeps-path?          true
            :strips-title?        true
            :strips-root-width?   true
            :strips-root-height?  true
            :strips-root-aria?    true}
           {:has-root-svg?        (str/starts-with? sprite "<svg xmlns=\"http://www.w3.org/2000/svg\"")
            :has-symbol?          (str/includes? sprite "<symbol id=\"home\"")
            :keeps-viewbox?       (str/includes? sprite "viewBox=\"0 0 24 24\"")
            :moves-root-fill?     (str/includes? sprite "fill=\"none\"")
            :moves-root-stroke?   (str/includes? sprite "stroke=\"currentColor\"")
            :moves-root-width?    (str/includes? sprite "stroke-width=\"1.5\"")
            :keeps-path?          (str/includes? sprite "<path d=\"M0 0h24v24\"")
            :strips-title?        (not (str/includes? sprite "<title"))
            :strips-root-width?   (not (str/includes? sprite "width=\"24\""))
            :strips-root-height?  (not (str/includes? sprite "height=\"24\""))
            :strips-root-aria?    (not (str/includes? sprite "aria-hidden"))}))))

(deftest prefixes-internal-ids-and-references
  (let [sprite (sut/build-sprite
                {"microsoft-365" "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><defs><linearGradient id=\"a\"><stop offset=\"0\"/></linearGradient><clipPath id=\"clip\"><path id=\"shape\" d=\"M0 0h1v1\"/></clipPath></defs><path fill=\"url(#a)\" clip-path=\"url(#clip)\"/><use href=\"#shape\"/></svg>"})]
    (is (= {:prefixes-gradient-id? true
            :prefixes-clip-id?     true
            :prefixes-shape-id?    true
            :rewrites-url-ref?     true
            :rewrites-clip-ref?    true
            :rewrites-href-ref?    true
            :removes-old-ids?      true}
           {:prefixes-gradient-id? (str/includes? sprite "id=\"microsoft-365-a\"")
            :prefixes-clip-id?     (str/includes? sprite "id=\"microsoft-365-clip\"")
            :prefixes-shape-id?    (str/includes? sprite "id=\"microsoft-365-shape\"")
            :rewrites-url-ref?     (str/includes? sprite "fill=\"url(#microsoft-365-a)\"")
            :rewrites-clip-ref?    (str/includes? sprite "clip-path=\"url(#microsoft-365-clip)\"")
            :rewrites-href-ref?    (str/includes? sprite "href=\"#microsoft-365-shape\"")
            :removes-old-ids?      (not (or (str/includes? sprite "id=\"a\"")
                                            (str/includes? sprite "id=\"clip\"")
                                            (str/includes? sprite "id=\"shape\"")))}))))

(deftest bakes-logo-colors-that-cannot-be-mutated-through-external-use
  (let [sprite (sut/build-sprite
                {"logotype" "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><path class=\"logotype-text\" fill=\"currentColor\" d=\"M0 0h1v1\"/><path class=\"logotype-snoman\" fill=\"currentColor\" d=\"M1 1h1v1\"/></svg>"})]
    (is (= {:bakes-text-color?   true
            :bakes-snoman-color? true}
           {:bakes-text-color?   (str/includes? sprite "class=\"logotype-text\" fill=\"#f97316\"")
            :bakes-snoman-color? (str/includes? sprite "class=\"logotype-snoman\" fill=\"#22c55e\"")}))))

(deftest reads-directory-and-returns-sprite-data
  (let [dir (temp-dir)]
    (spit (str (fs/path dir "two.svg")) "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 2 2\"><path d=\"M0 0h2v2\"/></svg>")
    (spit (str (fs/path dir "one.svg")) "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"><path d=\"M0 0h1v1\"/></svg>")
    (let [data (sut/sprite-data dir)]
      (is (= {:viewboxes       {"one" "0 0 1 1"
                                "two" "0 0 2 2"}
              :symbol-order     ["one" "two"]
              :contains-one?    true
              :contains-two?    true}
             {:viewboxes       (:viewboxes data)
              :symbol-order     (mapv second (re-seq #"<symbol id=\"([^\"]+)\"" (:sprite data)))
              :contains-one?    (str/includes? (:sprite data) "<symbol id=\"one\"")
              :contains-two?    (str/includes? (:sprite data) "<symbol id=\"two\"")})))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'build-icon-sprites-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
