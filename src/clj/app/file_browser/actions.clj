(ns app.file-browser.actions
  (:require
   [app.file-utils :as fu]
   [clojure.string :as str]))

(def default-picker-id :default)

(def clear-file-browser-signals
  [:app.datastar/merge-signals {:file-browser {:selected-path nil
                                               :target-dir nil}}])

(defn picker-key [picker-id]
  (cond
    (keyword? picker-id) picker-id
    (seq (str picker-id)) (keyword (str picker-id))
    :else default-picker-id))

(defn remote-path [path]
  (let [path (str/trim (str (or path "/")))
        path (if (str/starts-with? path "/")
               path
               (str "/" path))
        path (fu/normalize-path path)
        path (if (= path "/")
               path
               (fu/strip-trailing-slash path))]
    (if (str/blank? path)
      "/"
      path)))

(defn within-root? [root-dir path]
  (let [root-dir (remote-path root-dir)
        path     (remote-path path)]
    (or (= root-dir path)
        (str/starts-with? path (str root-dir "/")))))

(defn bounded-current-dir [root-dir current-dir]
  (let [root-dir    (remote-path root-dir)
        current-dir (remote-path (or current-dir root-dir))]
    (if (within-root? root-dir current-dir)
      current-dir
      root-dir)))

(defn open-picker-state [{:keys [root-dir current-dir target]}]
  (let [root-dir (remote-path root-dir)]
    {:open?      true
     :root-dir   root-dir
     :current-dir (bounded-current-dir root-dir current-dir)
     :target     target}))

(defn open-picker-action
  [_state {:keys [file-browser]}]
  (let [picker-id (picker-key (:picker-id file-browser))]
    [[:app.datastar/assoc-state
      [:file-browser picker-id]
      (open-picker-state file-browser)]]))

(defn set-current-dir-action
  [{:keys [page-state]} {:keys [file-browser]}]
  (let [picker-id (picker-key (:picker-id file-browser))
        root-dir  (get-in page-state [:file-browser picker-id :root-dir] "/")]
    [[:app.datastar/assoc-state
      [:file-browser picker-id :current-dir]
      (bounded-current-dir root-dir (:target-dir file-browser))]]))

(defn close-picker-action
  [_state {:keys [file-browser]}]
  [[:app.datastar/assoc-state [:file-browser (picker-key (:picker-id file-browser))] nil]
   clear-file-browser-signals])

(defn select-file-action
  [_state {:keys [file-browser]}]
  [[:app.datastar/assoc-state
    [:file-browser (picker-key (:picker-id file-browser)) :selected-path]
    (some-> (:selected-path file-browser) remote-path)]
   clear-file-browser-signals])

(def actions
  {::open-picker     #'open-picker-action
   ::set-current-dir #'set-current-dir-action
   ::close-picker    #'close-picker-action
   ::select-file     #'select-file-action})
