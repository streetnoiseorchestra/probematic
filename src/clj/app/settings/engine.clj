(ns app.settings.engine
  (:require
   [app.settings.controller :as controller]
   [app.settings.routes :as routes]))

(def discount-type-form
  {:form-name   :discount-type
   :form-id-key :discount-type-id})

(defn create-discount-type-handler [{:app/keys [request]} _]
  (let [{:keys [error]} (controller/create-discount-type! request)]
    {:outcome/effects [(if error
                         {:effect/kind :app.datastar/merge-signals
                          :effect/data {:discount-type-create {:error error}}}
                         {:effect/kind :app.datastar/remove-signals
                          :effect/data ["discount-type-create"]})]}))

(defn update-discount-type-handler [{:app/keys [request]} _]
  (let [{:keys [error]} (controller/update-discount-type request)]
    {:outcome/effects [(if error
                         {:effect/kind :app.datastar/merge-signals
                          :effect/data {:discount-type {:error error}}}
                         {:effect/kind :app.datastar/close-form
                          :effect/data discount-type-form})]}))

(defn delete-discount-type-handler [{:app/keys [request]} _]
  (let [{:keys [error]} (controller/delete-discount-type! request)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      {:outcome/effects [{:effect/kind :app.datastar/close-form
                          :effect/data discount-type-form}]})))

(defn open-discount-type-edit-handler [_ _]
  {:outcome/effects [{:effect/kind :app.datastar/open-form
                      :effect/data discount-type-form}]})

(defn close-discount-type-edit-handler [_ _]
  {:outcome/effects [{:effect/kind :app.datastar/close-form
                      :effect/data discount-type-form}]})

(def create-discount-type-command
  {:command/kind      ::routes/create-discount-type
   :command/coeffects [:app/request]
   :command/handler   #'create-discount-type-handler})

(def update-discount-type-command
  {:command/kind      ::routes/update-discount-type
   :command/coeffects [:app/request]
   :command/handler   #'update-discount-type-handler})

(def delete-discount-type-command
  {:command/kind      ::routes/delete-discount-type
   :command/coeffects [:app/request]
   :command/handler   #'delete-discount-type-handler})

(def open-discount-type-edit-command
  {:command/kind      ::routes/open-discount-type-edit
   :command/coeffects []
   :command/handler   #'open-discount-type-edit-handler})

(def close-discount-type-edit-command
  {:command/kind      ::routes/close-discount-type-edit
   :command/coeffects []
   :command/handler   #'close-discount-type-edit-handler})

(defn commands []
  [create-discount-type-command
   update-discount-type-command
   delete-discount-type-command
   open-discount-type-edit-command
   close-discount-type-edit-command])
