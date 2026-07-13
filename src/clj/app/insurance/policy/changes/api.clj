(ns app.insurance.policy.changes.api
  (:require
   [app.insurance.excel :as excel]
   [app.queries :as q]
   [app.util :as util])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn download-excel
  [{:keys [db parameters policy]}]
  (let [policy-id           (get-in parameters [:path :policy-id])
        policy              (or policy (q/retrieve-policy db policy-id))
        attachment-filename (get-in parameters [:query :attachment-filename])
        preview-type        (get-in parameters [:query :preview-type])
        changeset-scope     (case preview-type
                              "new"
                              #{:instrument.coverage.change/new}

                              "changes"
                              #{:instrument.coverage.change/changed
                                :instrument.coverage.change/removed})
        output-stream       (ByteArrayOutputStream.)
        _                   (excel/generate-excel-changeset!
                             changeset-scope
                             policy
                             output-stream)
        file-bytes          (.toByteArray output-stream)]
    {:status  200
     :headers {"Content-Disposition" (util/content-disposition-filename
                                      attachment-filename
                                      false)
               "Content-Type"        "application/vnd.ms-excel"
               "Content-Length"      (str (count file-bytes))}
     :body    (ByteArrayInputStream. file-bytes)}))
