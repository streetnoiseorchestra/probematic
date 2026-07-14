(ns app.qrcode
  (:import
   [javax.imageio ImageIO]
   [java.io ByteArrayOutputStream]
   [java.util Base64]
   [java.awt.image BufferedImage]
   [java.awt Font Color]
   [com.google.zxing.qrcode QRCodeWriter]
   [com.google.zxing.common BitMatrix]
   [com.google.zxing BarcodeFormat]
   [com.google.zxing.client.j2se MatrixToImageWriter])
  (:require
   [clojure.string :as cstr]))

(defn qr
  "Takes the string and returns a BufferedImage of the QR Code"
  [s width height]
  (let [writer (QRCodeWriter.)
        matrix (.encode writer s BarcodeFormat/QR_CODE width height)]
    (MatrixToImageWriter/toBufferedImage matrix)))

(defn image-to-data-uri [image]
  (let [baos (ByteArrayOutputStream.)]
    (ImageIO/write image "png" baos)
    (.flush baos)
    (str "data:image/png;base64,"
         (.encodeToString (Base64/getEncoder) (.toByteArray baos)))))

(def SERVICE-TAG "BCD")
(def VERSION "002")
(def CHARACTER-SET 1)
(def IDENTIFICATION-CODE "SCT")

(def non-alphanum "[^a-zA-Z0-9]")

(defn serialize-iban [iban]
  (.toUpperCase (.replaceAll iban non-alphanum "")))

(serialize-iban "AT1234")

(defn sepa-payment-code [{:keys [bic name iban amount purpose-code structured-reference unstructured-reference information] :as _args}]
  (assert bic "BIC is required")
  (assert name "Name is required")
  (assert iban "IBAN is required")
  (let [data [SERVICE-TAG
              VERSION
              CHARACTER-SET
              IDENTIFICATION-CODE
              bic
              name
              (serialize-iban iban)
              (if (nil? amount) "" (str "EUR" (format "%.2f" (double amount))))
              (or purpose-code "")
              (or structured-reference "")
              (or unstructured-reference "")
              (or information "")]]
    (clojure.string/join "\n" data)))

(comment
  (image-to-data-uri (qr "hello world" 300 300))
  (image-to-data-uri (qr (sepa-payment-code {:bic "133" :iban "AT123" :name "Test" :amount 100.0}) 300 300))

  ;;
  )
