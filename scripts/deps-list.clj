(require '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(defn dependency-lines [tree]
  (loop [[line & more] (remove str/blank? (str/split-lines tree))
         omitted-depth nil
         lines         []]
    (if line
      (let [depth (quot (count (re-find #"^\s*" line)) 2)]
        (cond
          (and omitted-depth (> depth omitted-depth))
          (recur more omitted-depth lines)

          (re-find #"^\s*X " line)
          (recur more depth lines)

          :else
          (recur more nil
                 (conj lines {:depth      depth
                              :dependency (str/replace line #"^\s*(?:\. )?" "")}))))
      lines)))

(defn dependency-paths [lines target]
  (loop [[line & more] lines
         stack         []
         matches       []]
    (if line
      (let [{:keys [depth dependency]} line
            path                       (conj (subvec stack 0 depth) dependency)]
        (recur more
               path
               (cond-> matches
                 (= target (first (str/split dependency #"\s+")))
                 (conj path))))
      matches)))

(defn print-path [path]
  (run! (fn [[depth dependency]]
          (println
           (str (when (pos? depth)
                  (str (apply str (repeat (dec depth) "    ")) "└── "))
                dependency)))
        (map-indexed vector path)))

(defn print-tree [lines]
  (let [lines (mapv (fn [i line]
                      (let [depth     (:depth line)
                            next-line (some #(when (<= (:depth %) depth) %)
                                            (subvec lines (inc i)))]
                        (assoc line :last-sibling? (not= depth (:depth next-line)))))
                    (range (count lines))
                    lines)]
    (loop [[line & more] lines
           ancestors     []]
      (when line
        (let [{:keys [depth dependency last-sibling?]} line]
          (println
           (if (zero? depth)
             dependency
             (str (apply str (map #(if % "    " "│   ")
                                  (subvec ancestors 1 depth)))
                  (if last-sibling? "└── " "├── ")
                  dependency)))
          (recur more
                 (conj (subvec ancestors 0 depth) last-sibling?)))))))

(let [lines                    (dependency-lines
                                (:out (shell {:out :string}
                                             "clj" "-X:deps" "tree" ":aliases" "[:dev]")))
      [command target & extra] *command-line-args*]
  (cond
    (nil? command)
    (print-tree lines)

    (and (= "flat" command) (nil? target) (empty? extra))
    (->> lines
         (map :dependency)
         distinct
         sort
         (run! println))

    (and (= "why" command) target (empty? extra))
    (run! (fn [[i path]]
            (when (pos? i)
              (println))
            (print-path path))
          (map-indexed vector (dependency-paths lines target)))

    :else
    (do
      (binding [*out* *err*]
        (println "Usage: bb bb/deps-list.clj [flat | why dependency]"))
      (System/exit 1))))
