(ns app.secret-box
  (:require
   [app.util :as util]
   [buddy.core.codecs :as codecs]
   [buddy.core.hash :as digest]
   [clojure.java.io :as io]
   [taoensso.nippy :as nippy]))

(defn encrypt-bytes
  "Warning: this uses the :cached password form from nippy. Be sure you know what that means.
  source: http://ptaoussanis.github.io/nippy/taoensso.nippy.encryption.html#var-aes128-gcm-encryptor"
  [plaintext password]
  (nippy/freeze plaintext {:password [:cached password]}))

(defn encrypt
  "Warning: this uses the :cached password form from nippy. Be sure you know what that means.
  source: http://ptaoussanis.github.io/nippy/taoensso.nippy.encryption.html#var-aes128-gcm-encryptor"
  [plaintext password]
  (codecs/bytes->b64-str
   (encrypt-bytes plaintext password) true))

(defn decrypt-bytes [ciphertext password]
  (nippy/thaw ciphertext {:password [:cached password]}))

(defn decrypt [ciphertext password]
  (when (and ciphertext password)
    (decrypt-bytes (codecs/b64->bytes ciphertext true) password)))

(defn random-str [len]
  (codecs/bytes->b64-str (util/random-bytes len) true))

(defn sha1-str [in]
  (codecs/bytes->hex (digest/sha1 in)))

(defn sha384-resource [path]
  (if-let [resource (io/resource path)]
    (str "sha384-"
         (-> resource
             io/input-stream
             digest/sha384
             (codecs/bytes->b64-str true)))
    (throw (ex-info "Cannot load resource %s from classpath" {:path path}))))

(comment
  ;; Usage

  (encrypt "hello world" "hunter2")
  ;; => "TlBZDokS_z1NfL6Z4T45dipuFQ28AC4nJ_JtnWqPtA3FrBJy2gby2bBCSiT9"

  (decrypt "TlBZDokS_z1NfL6Z4T45dipuFQ28AC4nJ_JtnWqPtA3FrBJy2gby2bBCSiT9" "hunter2")
  (decrypt "TlBZDokS_z1NfL6Z4T45dipuFQ28AC4nJ_JtnWqPtA3FrBJy2gby2bBCSiT9" "hunter2")
  ;; => "hello world"

  (encrypt {:member-id "7a0affc7-9436-4667-b57a-f622e8fb82e4"
            :uuid (sq/generate-squuid)} "hunter2")
  (-> {:member-id "7a0affc7-9436-4667-b57a-f622e8fb82e4"
       :uuid (sq/generate-squuid)}
      (encrypt "hunter2")
      (decrypt "hunter2")) ;; rcf

  (sha384-resource "public/css/main2.css") ;; rcf
  ;;
  )
