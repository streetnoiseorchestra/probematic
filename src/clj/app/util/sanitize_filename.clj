(ns app.util.sanitize-filename
  (:require
   [clojure.string :as s]))

;; based on https://github.com/madrobby/zaru
;;
;; Takes a given filename (a string) and normalizes, filters and truncates it.
;; * leaves unicode characters in place
;; * any sequence of whitespace that is 1 or more characters in length is collapsed to a single space
;; * Filenames are truncated so that they are at maximum 255 characters long (thanks windows)
;; * Windows reserved names are removed
;; * "Bad" characters are removed

;; Resources on valid file names
;; * https://msdn.microsoft.com/en-us/library/aa365247(v=vs.85).aspx#naming_conventions

(def CHARACTER-FILTER #"[\x00-\x1F\x80-\x9f\/\\:\*\?\"<>\|]")
(def UNICODE-WHITESPACE #"(?im)\s+")
(def WINDOWS-RESERVED-NAMES #"^(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$")
(def FALLBACK-FILENAME "file")

(defn- normalize [filename]
  (-> filename
      s/trim
      (s/replace UNICODE-WHITESPACE " ")))

(defn- filter-windows-reserved-names [filename]
  (if (re-matches WINDOWS-RESERVED-NAMES filename)
    FALLBACK-FILENAME
    filename))

(defn- filter-blank [filename]
  (if (s/blank? filename)
    FALLBACK-FILENAME
    filename))

(defn- filter-dot [^String filename]
  (if (.startsWith filename ".")
    (str FALLBACK-FILENAME filename)
    filename))

(defn- filter-characters [filename]
  (-> filename
      (s/replace CHARACTER-FILTER "")
      ;;  alternatively, replace the removed char with another one to show that it was removed
      ;; (s/replace CHARACTER-FILTER (s/re-quote-replacement "$"))
      ))

(defn- -filter [filename]
  (-> filename
      filter-characters
      filter-windows-reserved-names
      filter-blank
      filter-dot))

(defn- truncate [^String filename padding]
  (let [threshold (- 254 (or padding 0))]
    (if (> (.length filename) threshold)
      (.substring filename 0 threshold)
      filename)))

(defn sanitize
  ([filename]
   (sanitize filename 0))
  ([filename padding]
   (-> filename
       normalize
       -filter
       normalize
       (truncate padding))))

(comment
  (every? true?
          [(= "abcdef" (sanitize "abcdef"))
           (= 254 (count (sanitize (s/join "" (repeat 300 "A")))))
           (= 250 (count (sanitize (s/join "" (repeat 300 "A")) 4)))

           (every? true?
                   (map
                    #(= "a" (sanitize %))
                    ["a" " a" "a " " a " "a    \n"]))

           (->>  ["x x" "x  x" "x   x" " x    x " " x  |  x " "x\tx" "x\r\nx"]
                 (map sanitize)
                 (map #(= "x x" %))
                 (every? true?))
           (let [bad-chars ["<" ">" "|" "/" "\\"  "*" "?"  ":"]]
             (->>
              (for [c bad-chars]
                [(= (sanitize c) "file")
                 (= (sanitize (str "a" c)) "a")
                 (= (sanitize (str c "a")) "a")
                 (= (sanitize (str "a" c "a")) "aa")])
              (flatten)
              (every? true?)))

           (= "笊, ざる.pdf" (sanitize "笊, ざる.pdf"))
           (= "whatēverwëirduserînput" (sanitize "  what\\ēver//wëird:user:înput:"))
           (= "file" (sanitize "CON"))
           (= "file" (sanitize "COM1"))
           (= "COM10" (sanitize "COM10"))
           (= "file" (sanitize "LPT1"))
           (= "file" (sanitize " LPT1"))
           (= "file" (sanitize " LpT1"))
           (= "LpT10" (sanitize " LpT10"))

           (= "file.pdf" (sanitize  ".pdf"))
           (= "file.pdf" (sanitize  "<.pdf"))
           (= "file..pdf" (sanitize  "..pdf"))]) ;; rcf
  )
