(ns app.insurance.excel
  (:require
   [dk.ative.docjure.spreadsheet :as excel]
   [tarayo.core :as tarayo]
   [tick.core :as t])

  (:import
   [java.io ByteArrayOutputStream]
   [java.util Locale]))

(def START-ROW 10) ;; 0 indexed
(def STUCKPREIS-COL 7)
(def TOTAL-COL 8)

(defn coverage->row
  [role->coverage-type-id
   {:instrument.coverage/keys [value insurer-id item-count types] :as coverage}]
  (let [coverage-type-ids (set (map :insurance.coverage.type/type-id types))
        role-selected?    (fn [role]
                            (contains? coverage-type-ids
                                       (get role->coverage-type-id role)))
        item-count (or item-count 1)
        {:instrument/keys [category description serial-number build-year name model make owner images-share-url]} (:instrument.coverage/instrument coverage)]
    [item-count
     name
     make
     model
     serial-number
     build-year
     (str (:instrument.category/name category) "; " description)
     value
     (* item-count value)
     (if (role-selected? :overnight-vehicle) "x" "")
     (if (role-selected? :unattended-building) "x" "")
     ""                                 ; klavier transport
     ""                                 ; wert zuwachs
     (:member/name owner)
     insurer-id
     (or images-share-url "")]))

(defn get-cell-style-at [sheet row col]
  (let [r (nth  (excel/row-seq sheet) row)
        c (nth (excel/cell-seq r) col)]
    (.getCellStyle c)))

(defn clear-rows! [sheet]
  (let [rows  (drop START-ROW (excel/row-seq sheet))]
    ;; Remove all the rows under the template header
    (doseq [row rows]
      (excel/remove-row! sheet row))))

(defn set-item-styles! [row normal-style stuckpreis-style total-style]
  (doseq [cell (excel/cell-seq row)]
    (when cell
        ;; (.setLocked (.getCellStyle cell) false)
      (when (>=  (.getRowIndex cell) START-ROW)
        (.setCellStyle cell normal-style))
      (when (and (>=  (.getRowIndex cell) START-ROW)
                 (= STUCKPREIS-COL (.getColumnIndex cell)))
        (.setCellStyle cell stuckpreis-style))
      (when (and (>=  (.getRowIndex cell) START-ROW)
                 (= TOTAL-COL (.getColumnIndex cell)))
        (.setCellStyle cell total-style)))))

(defn add-blank-rows! [sheet count style]
  (dotimes [_i count]
    (let [row (excel/add-row! sheet (repeat 15 ""))]
      (excel/set-row-style! row style))))

(defn add-label-row! [sheet label style]
  (let [row (excel/add-row! sheet ["" "" label "" "" "" "" "" ""])]
    (excel/set-row-style! row style)))

(defn -add-instruments! [sheet normal-style stuckpreis-style total-style label-style title items]
  (when (seq items)
    (when title
      (add-label-row! sheet title label-style))
    (doseq [item items]
      (let [row (excel/add-row! sheet item)]
        (set-item-styles! row normal-style stuckpreis-style total-style)))))

(defn- generate-excel [fname sheet-name output-fname {changed-items  :instrument.coverage.change/changed  removed-items  :instrument.coverage.change/removed new-items :instrument.coverage.change/new :as _cs}]
  (let [wb               (excel/load-workbook-from-resource fname)
        sheet            (excel/select-sheet sheet-name wb)
        total-style      (get-cell-style-at sheet 4 TOTAL-COL)
        stuckpreis-style (doto (get-cell-style-at sheet START-ROW STUCKPREIS-COL)
                           (.setLocked false))
        label-style      (doto  (excel/create-cell-style! wb {:font {:size 14 :bold true} :wrap false})
                           (.setLocked false))
        normal-style     (doto
                          (excel/create-cell-style! wb {})
                           (.setLocked false)
                           (.setFillBackgroundColor (excel/color-index :white)))
        date-today       (t/format (t/formatter "dd MMM yyyy" Locale/GERMAN) (t/today))
        add-instruments! (partial -add-instruments! sheet normal-style stuckpreis-style total-style label-style)]
    (clear-rows! sheet)
    (when new-items
      ;; they don't like the new items to have a title
      (add-instruments! nil new-items)
      (add-blank-rows! sheet 3 normal-style))
    (when changed-items
      (add-instruments! (format "Änderungen: (Ab %s)" date-today) changed-items)
      (add-blank-rows! sheet 3 normal-style))
    (when removed-items
      (add-instruments! (format "Entfernung: (Ab %s)" date-today) removed-items))
    (excel/save-workbook! output-fname wb)))

(defn generate-excel-changeset!
  [{:keys [row-generator sheet-name template-resource]}
   role->coverage-type-id
   changeset-scope
   {:insurance.policy/keys [covered-instruments]}
   output]
  (let [gather-changeset (fn [scope]
                           (into [] (filter #(= scope (:instrument.coverage/change %)) covered-instruments)))
        changesets (into {}
                         (map (fn [scope]
                                [scope
                                 (map (partial row-generator role->coverage-type-id)
                                      (gather-changeset scope))]))
                         changeset-scope)]

    (generate-excel template-resource sheet-name output changesets))
  output)

(defn send-email!
  [generate-changeset! policy smtp-params from to subject body
   attachment-filename-new attachment-filename-changes]
  (with-open [conn (tarayo/connect smtp-params)]
    (let [new-items-output-stream (ByteArrayOutputStream.)
          changed-items-output-stream (ByteArrayOutputStream.)]
      (generate-changeset! #{:instrument.coverage.change/new}
                           policy
                           new-items-output-stream)
      (generate-changeset! #{:instrument.coverage.change/changed
                             :instrument.coverage.change/removed}
                           policy
                           changed-items-output-stream)
      (tarayo/send! conn {:from    from
                          :to      to
                          :subject subject
                          :body    [{:content body}
                                    {:content      (.toByteArray new-items-output-stream)
                                     :content-type "application/vnd.ms-excel" :filename attachment-filename-new}
                                    {:content      (.toByteArray changed-items-output-stream)
                                     :content-type "application/vnd.ms-excel" :filename attachment-filename-changes}]}))))

(comment

  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))
    (let [{:insurance.policy/keys [covered-instruments]} (q/retrieve-policy db #uuid "018bf625-1a68-8327-b386-fbb9e80dc987")
          changed (into [] (filter #(= :instrument.coverage.change/changed (:instrument.coverage/change %)) covered-instruments))
          removed (into [] (filter #(= :instrument.coverage.change/removed (:instrument.coverage/change %)) covered-instruments))
          new (into [] (filter #(= :instrument.coverage.change/new (:instrument.coverage/change %)) covered-instruments))]

      (generate-excel  "insurance-changes-template.xls" "insurance-changes-template-changed.xls"
                       (map coverage->row new)
                       (map coverage->row changed)
                       (map coverage->row removed)))) ;; rcf
  )
