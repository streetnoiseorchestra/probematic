(ns app.insurance.exporters
  (:require
   [app.insurance.excel :as excel]))

(def inventory-xls-v1
  :insurance.exporter/inventory-xls-v1)

(def ^:private exporter-order
  [inventory-xls-v1])

(def ^:private registry
  {inventory-xls-v1
   {:exporter-id      inventory-xls-v1
    :label-key        :insurance/exporter-inventory-xls-v1
    :template-resource "insurance-changes-template.xls"
    :sheet-name       "Inventar"
    :generator        excel/generate-excel-changeset!
    :row-generator    excel/coverage->row
    :roles
    [{:role      :overnight-vehicle
      :label-key
      :insurance/exporter-role-overnight-vehicle
      :required? true}
     {:role      :unattended-building
      :label-key
      :insurance/exporter-role-unattended-building
      :required? true}]}})

(defn descriptors
  []
  (mapv registry exporter-order))

(defn descriptor
  [exporter-id]
  (get registry exporter-id))

(defn- role->coverage-type
  [exporter-descriptor policy]
  (let [supported-roles (set (map :role (:roles exporter-descriptor)))]
    (->> (:insurance.policy/export-mappings policy)
         (keep (fn [{:insurance.export.mapping/keys [coverage-type role]}]
                 (when (and coverage-type (contains? supported-roles role))
                   [role coverage-type])))
         (into {}))))

(defn policy-configuration
  [policy]
  (let [exporter-id        (:insurance.policy/exporter-id policy)
        exporter-descriptor (descriptor exporter-id)]
    (cond
      (nil? exporter-id)
      {:exporter-id              nil
       :status                   :not-configured
       :missing-roles            []
       :role->coverage-type-id   {}
       :role->coverage-type      {}}

      (nil? exporter-descriptor)
      {:exporter-id              exporter-id
       :status                   :unknown
       :missing-roles            []
       :role->coverage-type-id   {}
       :role->coverage-type      {}}

      :else
      (let [role->type    (role->coverage-type exporter-descriptor policy)
            missing-roles (->> (:roles exporter-descriptor)
                               (filter :required?)
                               (map :role)
                               (remove #(contains? role->type %))
                               vec)]
        {:exporter-id            exporter-id
         :descriptor             exporter-descriptor
         :status                 (if (seq missing-roles)
                                   :incomplete
                                   :complete)
         :missing-roles          missing-roles
         :role->coverage-type-id (update-vals
                                  role->type
                                  :insurance.coverage.type/type-id)
         :role->coverage-type    role->type}))))

(defn configuration-guidance-key
  [policy]
  (case (:status (policy-configuration policy))
    :not-configured :insurance/exporter-not-configured-guidance
    :unknown        :insurance/exporter-unknown-guidance
    :incomplete     :insurance/exporter-incomplete-guidance
    nil))

(defn configured?
  [policy]
  (= :complete (:status (policy-configuration policy))))

(defn- configured-exporter
  [policy]
  (let [{:keys [exporter-id missing-roles status]
         :as configuration}
        (policy-configuration policy)]
    (if (= :complete status)
      configuration
      (throw
       (ex-info "Insurance policy exporter is not completely configured"
                {:type          :insurance.exporter/configuration-error
                 :exporter-id   exporter-id
                 :status        status
                 :missing-roles missing-roles})))))

(defn coverage->row
  [policy coverage]
  (let [{:keys [descriptor role->coverage-type-id]}
        (configured-exporter policy)]
    ((:row-generator descriptor) role->coverage-type-id coverage)))

(defn generate-changeset!
  [changeset-scope policy output]
  (let [{:keys [descriptor role->coverage-type-id]}
        (configured-exporter policy)]
    ((:generator descriptor)
     descriptor
     role->coverage-type-id
     changeset-scope
     policy
     output)))

(defn send-email!
  [policy smtp-params from to subject body attachment-filename-new
   attachment-filename-changes]
  (excel/send-email!
   generate-changeset!
   policy
   smtp-params
   from
   to
   subject
   body
   attachment-filename-new
   attachment-filename-changes))
