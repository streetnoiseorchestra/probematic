(ns app.insurance.exporters.harmonia-v1
  (:require
   [dk.ative.docjure.spreadsheet :as excel]
   [tarayo.core :as tarayo]
   [tick.core :as t])
  (:import
   [java.io ByteArrayOutputStream]
   [java.util Locale]
   [org.apache.poi.ss.usermodel Cell CellStyle]))

(def id
  :insurance/exporter-harmonia-v1)

(def overnight-vehicle-role
  :insurance.exporter.harmonia-v1/overnight-vehicle)

(def unattended-building-role
  :insurance.exporter.harmonia-v1/unattended-building)

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
        item-count        (or item-count 1)
        {:instrument/keys [category description serial-number build-year name
                           model make owner images-share-url]}
        (:instrument.coverage/instrument coverage)]
    [item-count
     name
     make
     model
     serial-number
     build-year
     (str (:instrument.category/name category) "; " description)
     value
     (* item-count value)
     (if (role-selected? overnight-vehicle-role) "x" "")
     (if (role-selected? unattended-building-role) "x" "")
     ""                                 ; klavier transport
     ""                                 ; wert zuwachs
     (:member/name owner)
     insurer-id
     (or images-share-url "")]))

(defn get-cell-style-at
  ^CellStyle [sheet row col]
  (let [r (nth (excel/row-seq sheet) row)
        ^Cell c (nth (excel/cell-seq r) col)]
    (.getCellStyle c)))

(defn clear-rows!
  [sheet]
  (let [rows (drop START-ROW (excel/row-seq sheet))]
    ;; Remove all the rows under the template header
    (doseq [row rows]
      (excel/remove-row! sheet row))))

(defn set-item-styles!
  [row normal-style stuckpreis-style total-style]
  (doseq [^Cell cell (excel/cell-seq row)]
    (when cell
      (when (>= (.getRowIndex cell) START-ROW)
        (.setCellStyle cell normal-style))
      (when (and (>= (.getRowIndex cell) START-ROW)
                 (= STUCKPREIS-COL (.getColumnIndex cell)))
        (.setCellStyle cell stuckpreis-style))
      (when (and (>= (.getRowIndex cell) START-ROW)
                 (= TOTAL-COL (.getColumnIndex cell)))
        (.setCellStyle cell total-style)))))

(defn add-blank-rows!
  [sheet count style]
  (dotimes [_i count]
    (let [row (excel/add-row! sheet (repeat 15 ""))]
      (excel/set-row-style! row style))))

(defn add-label-row!
  [sheet label style]
  (let [row (excel/add-row! sheet ["" "" label "" "" "" "" "" ""])]
    (excel/set-row-style! row style)))

(defn -add-instruments!
  [sheet normal-style stuckpreis-style total-style label-style title items]
  (when (seq items)
    (when title
      (add-label-row! sheet title label-style))
    (doseq [item items]
      (let [row (excel/add-row! sheet item)]
        (set-item-styles! row normal-style stuckpreis-style total-style)))))

(defn- generate-excel
  [fname sheet-name output-fname
   {changed-items :instrument.coverage.change/changed
    removed-items :instrument.coverage.change/removed
    new-items     :instrument.coverage.change/new
    :as _changeset}]
  (let [wb               (excel/load-workbook-from-resource fname)
        sheet            (excel/select-sheet sheet-name wb)
        ^CellStyle total-style (get-cell-style-at sheet 4 TOTAL-COL)
        ^CellStyle stuckpreis-style (doto (get-cell-style-at sheet START-ROW STUCKPREIS-COL)
                                      (.setLocked false))
        ^CellStyle label-style (doto ^CellStyle (excel/create-cell-style!
                                                 wb
                                                 {:font {:size 14 :bold true}
                                                  :wrap false})
                                 (.setLocked false))
        ^CellStyle normal-style (doto ^CellStyle (excel/create-cell-style! wb {})
                                  (.setLocked false)
                                  (.setFillBackgroundColor (short (excel/color-index :white))))
        date-today       (t/format (t/formatter "dd MMM yyyy" Locale/GERMAN)
                                   (t/today))
        add-instruments! (partial -add-instruments!
                                  sheet
                                  normal-style
                                  stuckpreis-style
                                  total-style
                                  label-style)]
    (clear-rows! sheet)
    (when new-items
      ;; They do not want new items to have a title.
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
                           (into []
                                 (filter #(= scope
                                             (:instrument.coverage/change %))
                                         covered-instruments)))
        changesets       (into {}
                               (map (fn [scope]
                                      [scope
                                       (map (partial row-generator
                                                     role->coverage-type-id)
                                            (gather-changeset scope))]))
                               changeset-scope)]
    (generate-excel template-resource sheet-name output changesets))
  output)

(def descriptor
  {:exporter-id       id
   :label-key         id
   :template-resource "insurance/exporters/harmonia-v1.xls"
   :sheet-name        "Inventar"
   :generator         generate-excel-changeset!
   :row-generator     coverage->row
   :roles
   [{:role      overnight-vehicle-role
     :label-key :insurance/exporter-role-overnight-vehicle
     :required? true}
    {:role      unattended-building-role
     :label-key :insurance/exporter-role-unattended-building
     :required? true}]})

(defn send-email!
  [generate-changeset! policy smtp-params from to subject body
   attachment-filename-new attachment-filename-changes]
  (with-open [conn (tarayo/connect smtp-params)]
    (let [new-items-output-stream     (ByteArrayOutputStream.)
          changed-items-output-stream (ByteArrayOutputStream.)]
      (generate-changeset! #{:instrument.coverage.change/new}
                           policy
                           new-items-output-stream)
      (generate-changeset! #{:instrument.coverage.change/changed
                             :instrument.coverage.change/removed}
                           policy
                           changed-items-output-stream)
      (tarayo/send!
       conn
       {:from    from
        :to      to
        :subject subject
        :body    [{:content body}
                  {:content      (.toByteArray new-items-output-stream)
                   :content-type "application/vnd.ms-excel"
                   :filename     attachment-filename-new}
                  {:content      (.toByteArray changed-items-output-stream)
                   :content-type "application/vnd.ms-excel"
                   :filename     attachment-filename-changes}]}))))
