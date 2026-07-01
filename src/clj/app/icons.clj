(ns app.icons
  (:require
   [app.brotli :as br]
   [app.interceptors.compression :as compression]
   [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [integrant.core :as ig])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.util.zip GZIPOutputStream)))

;;; <START LEGACY CODE>
;;; do not use any functions/macros/vars in this block
;;; do not edit/update any code in this block
;;; if you're here to use an icon skip to <END LEGACY CODE>

(defn icon*
  ([svg]
   (icon* svg nil))
  ([svg {:keys [class] :or {class ""} :as opts}]
   (-> svg
       (update-in [1] #(merge % (dissoc opts :class)))
       (update-in [1 :class]
                  (fn [existing new]
                    (str new " " existing)) (str  "icon " class)))))

#_(defmacro deficon [name svg]
    `(def ~name (partial icon* ~svg)))
(defn deficon [svg] (partial icon* svg))

(def calendar (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :viewbox "0 0 448 512"} "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc. -->"
                 [:path {:fill "currentColor" :d "M152 64H296V24C296 10.75 306.7 0 320 0C333.3 0 344 10.75 344 24V64H384C419.3 64 448 92.65 448 128V448C448 483.3 419.3 512 384 512H64C28.65 512 0 483.3 0 448V128C0 92.65 28.65 64 64 64H104V24C104 10.75 114.7 0 128 0C141.3 0 152 10.75 152 24V64zM48 448C48 456.8 55.16 464 64 464H384C392.8 464 400 456.8 400 448V192H48V448z"}]]))
(def plus (deficon
            [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 448 512"}
             [:path {:fill "currentColor" :d "M256 80c0-17.7-14.3-32-32-32s-32 14.3-32 32V224H48c-17.7 0-32 14.3-32 32s14.3 32 32 32H192V432c0 17.7 14.3 32 32 32s32-14.3 32-32V288H400c17.7 0 32-14.3 32-32s-14.3-32-32-32H256V80z"}]]))

(def plus-thin
  (deficon
    [:svg {:fill "none", :viewBox "0 0 24 24", :stroke-width "1.5", :stroke "currentColor", :aria-hidden "true"}
     [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M12 6v12m6-6H6"}]]))

(def circle-plus-solid (deficon
                         [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512" :fill "currentColor"}
                          ;; "<!--!Font Awesome Free 6.5.2 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.-->"
                          [:path {:d "M256 512a256 256 0 1 0 0-512 256 256 0 1 0 0 512zm-24-168v-64h-64c-13.3 0-24-10.7-24-24s10.7-24 24-24h64v-64c0-13.3 10.7-24 24-24s24 10.7 24 24v64h64c13.3 0 24 10.7 24 24s-10.7 24-24 24h-64v64c0 13.3-10.7 24-24 24s-24-10.7-24-24z"}]]))
(def circle-question (deficon
                       [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512"} "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->"
                        [:path {:fill "currentColor" :d "M256 512c141.4 0 256-114.6 256-256S397.4 0 256 0 0 114.6 0 256s114.6 256 256 256zm-86.2-346.7c7.9-22.3 29.1-37.3 52.8-37.3h58.3c34.9 0 63.1 28.3 63.1 63.1 0 22.6-12.1 43.5-31.7 54.8L280 264.4c-.2 13-10.9 23.6-24 23.6-13.3 0-24-10.7-24-24v-13.5c0-8.6 4.6-16.5 12.1-20.8l44.3-25.4c4.7-2.7 7.6-7.7 7.6-13.1 0-8.4-6.8-15.1-15.1-15.1h-58.3c-3.4 0-6.4 2.1-7.5 5.3l-.4 1.2c-4.4 12.5-18.2 19-30.6 14.6s-19-18.2-14.6-30.6l.4-1.2zM288 352c0 17.7-14.3 32-32 32s-32-14.3-32-32 14.3-32 32-32 32 14.3 32 32z"}]]))

(def circle-check (deficon
                    [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512"} "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->" [:path {:fill "currentColor", :d "M256 512c141.4 0 256-114.6 256-256S397.4 0 256 0 0 114.6 0 256s114.6 256 256 256zm113-303L241 337c-9.4 9.4-24.6 9.4-33.9 0l-64-64c-9.4-9.4-9.4-24.6 0-33.9s24.6-9.4 33.9 0l47 47L335 175c9.4-9.4 24.6-9.4 33.9 0s9.4 24.6 0 33.9z"}]]))

(def circle-check-outline (deficon
                            [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512" :fill "currentColor"}
                             ;; "<!--! Font Awesome Pro 6.3.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2023 Fonticons, Inc.-->"
                             [:path {:d "M243.8 339.8c-10.9 10.9-28.7 10.9-39.6 0l-64-64c-10.9-10.9-10.9-28.7 0-39.6 10.9-10.9 28.7-10.9 39.6 0l44.2 44.2 108.2-108.2c10.9-10.9 28.7-10.9 39.6 0 10.9 10.9 10.9 28.7 0 39.6l-128 128zM512 256c0 141.4-114.6 256-256 256S0 397.4 0 256 114.6 0 256 0s256 114.6 256 256zM256 48C141.1 48 48 141.1 48 256s93.1 208 208 208 208-93.1 208-208S370.9 48 256 48z"}]]))

(def circle-question-outline (deficon
                               [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512" :fill "currentColor"}
                                ;; "<!--! Font Awesome Pro 6.3.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2023 Fonticons, Inc.-->"
                                [:path {:d "M464 256a208 208 0 1 0-416 0 208 208 0 1 0 416 0zM0 256a256 256 0 1 1 512 0 256 256 0 1 1-512 0zm169.8-90.7c7.9-22.3 29.1-37.3 52.8-37.3h58.3c34.9 0 63.1 28.3 63.1 63.1 0 22.6-12.1 43.5-31.7 54.8L280 264.4c-.2 13-10.9 23.6-24 23.6-13.3 0-24-10.7-24-24v-13.5c0-8.6 4.6-16.5 12.1-20.8l44.3-25.4c4.7-2.7 7.6-7.7 7.6-13.1 0-8.4-6.8-15.1-15.1-15.1h-58.3c-3.4 0-6.4 2.1-7.5 5.3l-.4 1.2c-4.4 12.5-18.2 19-30.6 14.6s-19-18.2-14.6-30.6l.4-1.2zM224 352a32 32 0 1 1 64 0 32 32 0 1 1-64 0z"}]]))

(def circle-dot-outline (deficon
                          [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512" :fill "currentColor"}
                   ;; "<!--! Font Awesome Pro 6.3.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2023 Fonticons, Inc.-->"
                           [:path {:d "M160 256c0-53.9 42.1-96 96-96 53 0 96 42.1 96 96 0 53-43 96-96 96-53.9 0-96-43-96-96zm352 0c0 141.4-114.6 256-256 256S0 397.4 0 256 114.6 0 256 0s256 114.6 256 256zM256 48C141.1 48 48 141.1 48 256s93.1 208 208 208 208-93.1 208-208S370.9 48 256 48z"}]]))

(def circle-xmark (deficon
                    [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512"} "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->" [:path {:fill "currentColor", :d "M256 512c141.4 0 256-114.6 256-256S397.4 0 256 0 0 114.6 0 256s114.6 256 256 256zm-81-337c9.4-9.4 24.6-9.4 33.9 0l47 47 47-47c9.4-9.4 24.6-9.4 33.9 0s9.4 24.6 0 33.9l-47 47 47 47c9.4 9.4 9.4 24.6 0 33.9s-24.6 9.4-33.9 0l-47-47-47 47c-9.4 9.4-24.6 9.4-33.9 0s-9.4-24.6 0-33.9l47-47-47-47c-9.4-9.4-9.4-24.6 0-33.9z"}]]))
(def circle-exclamation (deficon
                          [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "currentColor" :viewBox "0 0 512 512"}
                           ;; "<!--!Font Awesome Free 6.5.2 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.-->"
                           [:path {:d "M256 512a256 256 0 1 0 0-512 256 256 0 1 0 0 512zm0-384c13.3 0 24 10.7 24 24v112c0 13.3-10.7 24-24 24s-24-10.7-24-24V152c0-13.3 10.7-24 24-24zm-32 224a32 32 0 1 1 64 0 32 32 0 1 1-64 0z"}]]))

(def user (deficon
            [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 448 512"}
             "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->"
             [:path {:fill "currentColor" :d "M224 256c70.7 0 128-57.3 128-128S294.7 0 224 0 96 57.3 96 128s57.3 128 128 128zm-45.7 48C79.8 304 0 383.8 0 482.3 0 498.7 13.3 512 29.7 512h388.6c16.4 0 29.7-13.3 29.7-29.7 0-98.5-79.8-178.3-178.3-178.3h-91.4z"}]]))

(def download (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3"}]]))

(def trumpet (deficon
               [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 700 700"}
                [:path {:fill "currentColor" :d "M590.765 194.7 432.881 36.816c-11.062-11.062-30.672-4.023-31.174 12.067-3.52 61.843-48.772 142.289-95.03 211.175l-19.608-19.609c-2.514-2.514-7.039-2.514-10.056 0l-16.593 16.593c-2.514 2.514-2.514 7.04 0 10.056l24.638 24.638c-5.028 7.04-10.056 14.078-14.582 20.615l-27.653-27.653c-2.514-2.514-7.04-2.514-10.056 0l-16.593 16.593c-2.514 2.514-2.514 7.039 0 10.056l31.676 31.676c-5.53 7.542-10.056 14.078-14.582 20.615l-34.19-34.694c-2.514-2.514-7.04-2.514-10.056 0l-16.593 16.593c-2.514 2.514-2.514 7.04 0 10.056l38.213 38.213c-30.168 42.235-30.672 99.554-1.509 142.794l-37.709 37.71-63.855 50.783c-4.525 3.52-5.028 10.559-1.005 14.582l26.145 26.145c4.022 4.023 11.062 3.52 14.582-1.005l50.783-63.855 37.207-37.207c48.772 38.212 119.66 35.196 164.918-9.553l135.255-135.256c28.66-28.66 29.665-74.918 1.509-104.079-28.66-29.664-75.922-29.664-104.582-1.005l-35.698 35.698-28.157-28.157c68.884-45.754 149.334-91.007 211.175-95.03 15.084-1.005 22.123-20.111 11.062-31.675zM284.558 379.224l40.224 40.224-17.597 17.597-43.241-43.24c6.536-4.023 13.575-9.05 20.615-14.582zm-47.263 41.23 43.743 43.742-44.75 44.75c-16.09-27.653-15.586-61.844 1.006-88.493zm220.726-80.448c14.078-14.079 37.207-14.079 51.285 0 14.078 14.078 14.078 37.207 0 51.285l-135.75 135.75c-30.168 30.168-77.933 33.687-111.62 9.553zm-88.996 35.698L351.427 393.3l-36.201-36.2c6.536-4.525 13.575-9.553 20.615-14.582z", :style "stroke-width:1.14925"}]]))
(def music-note-outline (deficon
                          [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "m9 9 10.5-3m0 6.553v3.75a2.25 2.25 0 0 1-1.632 2.163l-1.32.377a1.803 1.803 0 1 1-.99-3.467l2.31-.66a2.25 2.25 0 0 0 1.632-2.163zm0 0V2.25L9 5.25v10.303m0 0v3.75a2.25 2.25 0 0 1-1.632 2.163l-1.32.377a1.803 1.803 0 0 1-.99-3.467l2.31-.66A2.25 2.25 0 0 0 9 15.553z"}]]))
(def users-outline (deficon
                     [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0z"}]]))

(def users-solid (deficon
                   [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "currentColor", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:d "M4.5 6.375a4.125 4.125 0 1 1 8.25 0 4.125 4.125 0 0 1-8.25 0zm9.75 2.25a3.375 3.375 0 1 1 6.75 0 3.375 3.375 0 0 1-6.75 0zM1.5 19.125a7.125 7.125 0 0 1 14.25 0v.003l-.001.119a.75.75 0 0 1-.363.63 13.067 13.067 0 0 1-6.761 1.873c-2.472 0-4.786-.684-6.76-1.873a.75.75 0 0 1-.364-.63l-.001-.122zm15.75.003-.001.144a2.25 2.25 0 0 1-.233.96 10.088 10.088 0 0 0 5.06-1.01.75.75 0 0 0 .42-.643 4.875 4.875 0 0 0-6.957-4.611 8.586 8.586 0 0 1 1.71 5.157v.003z"}]]))

(def shield-check-outline (deficon
                            [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M9 12.75 11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z"}]]))

(def shield-check-solid (deficon
                          [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "currentColor", :class "", :viewBox "0 0 24 24"} [:path {:fill-rule "evenodd", :d "M12.516 2.17a.75.75 0 0 0-1.032 0 11.209 11.209 0 0 1-7.877 3.08.75.75 0 0 0-.722.515A12.74 12.74 0 0 0 2.25 9.75c0 5.942 4.064 10.933 9.563 12.348a.749.749 0 0 0 .374 0c5.499-1.415 9.563-6.406 9.563-12.348 0-1.39-.223-2.73-.635-3.985a.75.75 0 0 0-.722-.516l-.143.001c-2.996 0-5.717-1.17-7.734-3.08zm3.094 8.016a.75.75 0 1 0-1.22-.872l-3.236 4.53L9.53 12.22a.75.75 0 0 0-1.06 1.06l2.25 2.25a.75.75 0 0 0 1.14-.094l3.75-5.25z", :clip-rule "evenodd"}]]))

(def envelope (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"}]]))

(def xmark (deficon
             [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 320 512"}
              [:path {:fill "currentColor" :d "M310.6 150.6c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L160 210.7 54.6 105.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3L114.7 256 9.4 361.4c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0L160 301.3l105.4 105.3c12.5 12.5 32.8 12.5 45.3 0s12.5-32.8 0-45.3L205.3 256l105.3-105.4z"}]]))

(def xmark-thin (deficon
            ;; "<!-- Heroicon name: outline/x-mark -->"
                  [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :viewbox "0 0 24 24", :stroke-width "1.5", :stroke "currentColor", :aria-hidden "true"}
                   [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M6 18L18 6M6 6l12 12"}]]))

(def circle (deficon
              [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512"} "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->"
               [:path {:fill "currentColor" :d "M256 512c141.4 0 256-114.6 256-256S397.4 0 256 0 0 114.6 0 256s114.6 256 256 256z"}]]))
(def question (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 320 512"} "<!--! Font Awesome Pro 6.2.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->"
                 [:path {:fill "currentColor" :d "M96 96c-17.7 0-32 14.3-32 32s-14.3 32-32 32-32-14.3-32-32c0-53 43-96 96-96h97c70.1 0 127 56.9 127 127 0 52.4-32.2 99.4-81 118.4l-63 24.5V320c0 17.7-14.3 32-32 32s-32-14.3-32-32v-18.1c0-26.4 16.2-50.1 40.8-59.6l63-24.5C240 208.3 256 185 256 159c0-34.8-28.2-63-63-63H96zm48 384c-22.1 0-40-17.9-40-40s17.9-40 40-40 40 17.9 40 40-17.9 40-40 40z"}]]))
(def chevron-down (deficon
       ;; "<!-- Heroicon name: mini/chevron-down -->"
                    [:svg {:class "", :xmlns "http://www.w3.org/2000/svg", :viewbox "0 0 20 20", :fill "currentColor", :aria-hidden "true"}
                     [:path {:fill-rule "evenodd", :d "M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z", :clip-rule "evenodd"}]]))

(def chevron-up-down (deficon

        ;; "<!-- Heroicon name: mini/chevron-up-down -->"
                       [:svg {:class "" :xmlns "http://www.w3.org/2000/svg" :viewbox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
                        [:path {:fill-rule "evenodd" :d "M10 3a.75.75 0 01.55.24l3.25 3.5a.75.75 0 11-1.1 1.02L10 4.852 7.3 7.76a.75.75 0 01-1.1-1.02l3.25-3.5A.75.75 0 0110 3zm-3.76 9.2a.75.75 0 011.06.04l2.7 2.908 2.7-2.908a.75.75 0 111.1 1.02l-3.25 3.5a.75.75 0 01-1.1 0l-3.25-3.5a.75.75 0 01.04-1.06z" :clip-rule "evenodd"}]]))

(def comments (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 640 512"}
                 ;; "<!--! Font Awesome Pro 6.4.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2023 Fonticons, Inc.-->"
                 [:path {:fill "currentColor" :d "M88.2 309.1c9.8-18.3 6.8-40.8-7.5-55.8C59.4 230.9 48 204 48 176c0-63.5 63.8-128 160-128s160 64.5 160 128-63.8 128-160 128c-13.1 0-25.8-1.3-37.8-3.6-10.4-2-21.2-.6-30.7 4.2-4.1 2.1-8.3 4.1-12.6 6-16 7.2-32.9 13.5-49.9 18 2.8-4.6 5.4-9.1 7.9-13.6 1.1-1.9 2.2-3.9 3.2-5.9zM0 176c0 41.8 17.2 80.1 45.9 110.3-.9 1.7-1.9 3.5-2.8 5.1-10.3 18.4-22.3 36.5-36.6 52.1-6.6 7-8.3 17.2-4.6 25.9C5.8 378.3 14.4 384 24 384c43 0 86.5-13.3 122.7-29.7 4.8-2.2 9.6-4.5 14.2-6.8 15.1 3 30.9 4.5 47.1 4.5 114.9 0 208-78.8 208-176S322.9 0 208 0 0 78.8 0 176zm432 304c16.2 0 31.9-1.6 47.1-4.5 4.6 2.3 9.4 4.6 14.2 6.8C529.5 498.7 573 512 616 512c9.6 0 18.2-5.7 22-14.5 3.8-8.8 2-19-4.6-25.9-14.2-15.6-26.2-33.7-36.6-52.1-.9-1.7-1.9-3.4-2.8-5.1 28.8-30.3 46-68.6 46-110.4 0-94.4-87.9-171.5-198.2-175.8 4.1 15.2 6.2 31.2 6.2 47.8v.6c87.2 6.7 144 67.5 144 127.4 0 28-11.4 54.9-32.7 77.2-14.3 15-17.3 37.6-7.5 55.8 1.1 2 2.2 4 3.2 5.9 2.5 4.5 5.2 9 7.9 13.6-17-4.5-33.9-10.7-49.9-18-4.3-1.9-8.5-3.9-12.6-6-9.5-4.8-20.3-6.2-30.7-4.2-12.1 2.4-24.7 3.6-37.8 3.6-61.7 0-110-26.5-136.8-62.3-16 5.4-32.8 9.4-50 11.8C279 439.8 350 480 432 480z"}]]))

(def home (deficon
            [:svg {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewbox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 12l8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25"}]]))

(def home-solid (deficon
                  [:svg {:viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
                   [:path {:fill-rule "evenodd" :d "M9.293 2.293a1 1 0 011.414 0l7 7A1 1 0 0117 11h-1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-3a1 1 0 00-1-1H9a1 1 0 00-1 1v3a1 1 0 01-1 1H5a1 1 0 01-1-1v-6H3a1 1 0 01-.707-1.707l7-7z", :clip-rule "evenodd"}]]))

(def minus (deficon
             [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 448 512"}
              ;; "<!--! Font Awesome Pro 6.2.1 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc.-->"
              [:path {:fill "currentColor" :d "M432 256c0 17.7-14.3 32-32 32H48c-17.7 0-32-14.3-32-32s14.3-32 32-32h352c17.7 0 32 14.3 32 32z"}]]))

(def minus-thin (deficon
                  [:svg {:fill "none", :viewBox "0 0 24 24", :stroke-width "1.5", :stroke "currentColor", :aria-hidden "true"}
                   [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M18 12H6"}]]))

(def file-excel-outline (deficon
                          [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 384 512"}
                           ;; "<!--! Font Awesome Free 6.4.2 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2023 Fonticons, Inc.-->"
                           [:path {:fill "currentColor" :d "M48 448V64c0-8.8 7.2-16 16-16h160v80c0 17.7 14.3 32 32 32h80v288c0 8.8-7.2 16-16 16H64c-8.8 0-16-7.2-16-16zM64 0C28.7 0 0 28.7 0 64v384c0 35.3 28.7 64 64 64h256c35.3 0 64-28.7 64-64V154.5c0-17-6.7-33.3-18.7-45.3l-90.6-90.5C262.7 6.7 246.5 0 229.5 0H64zm90.9 233.3c-8.1-10.5-23.2-12.3-33.7-4.2s-12.3 23.2-4.2 33.7l44.6 57.2-44.5 57.3c-8.1 10.5-6.3 25.5 4.2 33.7s25.5 6.3 33.7-4.2l37-47.7 37.1 47.6c8.1 10.5 23.2 12.3 33.7 4.2s12.3-23.2 4.2-33.7L222.4 320l44.5-57.3c8.1-10.5 6.3-25.5-4.2-33.7s-25.5-6.3-33.7 4.2l-37 47.7-37.1-47.6z"}]]))

;;;;
(def folder-open (deficon
                   [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 576 512"}
                    ;; "<!--! Font Awesome Pro 6.4.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2023 Fonticons, Inc.-->"
                    [:path {:fill "currentColor" :d "M384 480h48c11.4 0 21.9-6 27.6-15.9l112-192c5.8-9.9 5.8-22.1.1-32.1s-16.2-16-27.7-16H144c-11.4 0-21.9 6-27.6 15.9L48 357.1V96c0-8.8 7.2-16 16-16h117.5c4.2 0 8.3 1.7 11.3 4.7l26.5 26.5c21 21 49.5 32.8 79.2 32.8H416c8.8 0 16 7.2 16 16v32h48v-32c0-35.3-28.7-64-64-64H298.5c-17 0-33.3-6.7-45.3-18.7l-26.5-26.6c-12-12-28.3-18.7-45.3-18.7H64C28.7 32 0 60.7 0 96v320c0 35.3 28.7 64 64 64h320z"}]]))

(def search (deficon
              [:svg {:aria-hidden "true", :fill "currentColor", :viewbox "0 0 20 20", :xmlns "http://www.w3.org/2000/svg"}
               [:path {:fill-rule "evenodd", :d "M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z", :clip-rule "evenodd"}]]))

(def checkmark (deficon
                 [:svg {:aria-hidden "true" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewbox "0 0 512 512"}
 ;; "<!--! Font Awesome Pro 6.2.1 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license (Commercial License) Copyright 2022 Fonticons, Inc. -->"
                  [:path {:d "M470.6 105.4c12.5 12.5 12.5 32.8 0 45.3l-256 256c-12.5 12.5-32.8 12.5-45.3 0l-128-128c-12.5-12.5-12.5-32.8 0-45.3s32.8-12.5 45.3 0L192 338.7 425.4 105.4c12.5-12.5 32.8-12.5 45.3 0z"}]]))

(def spinner (deficon
               [:svg {:class "spinner animate-spin", :xmlns "http://www.w3.org/2000/svg", :fill "none", :viewbox "0 0 24 24"}
                [:circle {:class "opacity-25", :cx "12", :cy "12", :r "10", :stroke "currentColor", :stroke-width "4"}]
                [:path {:class "opacity-75", :fill "currentColor", :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]))

(def cog (deficon
           [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 0 1 0 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 0 1 0-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.281z"}] [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"}]]))

(def snoman (deficon
              [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 17.009 25.465"}
               [:path {:class "logotype-snoman" :fill "currentColor" :d "M89.19 122.74a4.223 4.223 0 0 1-.54-.747l-.506-.932a7.85 7.85 0 0 1-.467-1.1c-.244-.754-.48-1.095-1.726-2.492-.558-.625-1.192-1.406-1.41-1.736-.217-.329-.514-.71-.66-.847-.2-.188-.265-.322-.265-.549 0-.259.074-.37.536-.81.295-.28.635-.642.756-.805.475-.643.882-2.321 1.16-4.782.095-.837.172-1.537.172-1.557 0-.046-1.742.06-2.035.125a.483.483 0 0 1-.413-.113c-.175-.142-.229-.36-.482-1.975-.327-2.079-.35-2.493-.15-2.57.077-.03.43-.111.784-.183 2.448-.491 4.36-1.723 5.394-3.473.268-.454.395-.591.564-.611.273-.033.252-.105.568 1.93.331 2.13.286 1.986.714 2.28.441.304.644.743.644 1.393 0 .347-.048.518-.21.74-.524.718-.488.909.142.75.572-.144.949.039 1.177.57.406.944 1.26 3.71 1.631 5.274.505 2.133 1.067 4.902 1.076 5.296.006.317.312 1.253.324.992.005-.103-.07-.808-.168-1.566-.235-1.828-.196-2.627.166-3.385.146-.306.265-.61.265-.676 0-.18-.448-.842-.75-1.106-.284-.25-.327-.391-.164-.554.28-.28.93.01 1.592.713.256.272.757.723 1.114 1.004 1.639 1.287 2.018 1.683 2.018 2.103 0 .197-.034.227-.258.227-.142 0-.546-.142-.898-.316l-.64-.316-.193.242c-.19.238-.245.425-.38 1.28-.074.471.085 2.31.292 3.372.281 1.44.103 2.233-.601 2.68-1.076.682-2.34.214-2.859-1.059a11.67 11.67 0 0 0-.338-.759 3.385 3.385 0 0 1-.226-.705c-.079-.378-.117-.44-.235-.382-.252.125-1.56.056-2.457-.13-.483-.1-.93-.181-.995-.181-.18 0-.144.365.14 1.397l.39 1.418.135.487h.355c.284 0 .403.05.6.257.213.222.251.335.28.838.03.554.021.59-.194.73-.196.13-.282.136-.65.05-.57-.13-.56-.133-.623.156-.07.316-.07.315-.68.383-.504.055-.507.054-.816-.27zm3.781-7.923c0-.334.02-.365.282-.442.256-.076.278-.107.236-.34-.025-.14-.09-.549-.146-.908-.056-.36-.133-.634-.172-.61-.2.125-1.061.277-1.85.327-.832.054-1.953.194-2.011.251-.159.159.488.576 1.457.94.735.278 1.277.558 1.696.879.187.142.377.26.424.26.046.002.084-.159.084-.357zm-1.158-3.877c.294-.047.66-.091.813-.097l.277-.01-.155-.855a41.255 41.255 0 0 1-.25-1.574c-.114-.863-.36-2.03-.524-2.498-.094-.267-.167-.344-.347-.366-.2-.023-.223-.003-.183.17.088.378.384 2.354.384 2.56 0 .115-.037.231-.082.26-.11.067-.512-.147-.722-.384-.265-.3-1.355-.985-1.877-1.18-.26-.097-.484-.166-.496-.153-.013.014.011.234.054.49.042.256.108.833.147 1.282.063.729.106.877.398 1.375.288.493.37.572.711.68.212.067.538.18.724.251.41.157.452.16 1.128.05z" :transform "translate(-83.032 -97.573)"}]]))

(def snomegaphone (deficon
                    [:svg {:xmlns "http://www.w3.org/2000/svg",
                           :class "logotype-megaphone" :fill "currentColor"
                           :viewBox "0 0 105.833 105.833"} [:path {:d "M262.465 291.258c-7.403-9.303-17.949-16.675-29.635-20.717-6.915-2.39-18.852-4.5-25.49-4.504-7.97-.004-7.789.52-7.789-22.617 0-23.327-.93-21.649 12.423-22.41 21.827-1.242 38.15-9.603 51.63-26.447 5.17-6.462 5.515-5.416 5.515 16.75V230.1l2.884 3.148c6.058 6.614 5.953 14.246-.296 21.362l-2.588 2.948v18.25c0 17.662-.258 20.87-1.674 20.87-.368 0-2.609-2.438-4.98-5.419z", :style "fill-opacity:1;stroke-width:.828193", :transform "translate(-185.088 -190.976)"}]]))

(def logotype (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 160.276 25.465"}
                 [:g [:path {:class "logotype-text" :fill "currentColor" :d "M-428.298 198.81q.152.76.304 1.699-.71.253-2.18.659l-2.206.634q-.203-.99-1.014-.99-1.14 0-2.079.863-.583.71-.38 1.343.228.38 1.344.38.557 0 1.47-.126 1.293-.177 2.307-.177 2.56 0 3.321 1.267.228.431.33.811.355 1.42-.786 3.043-1.395 2.13-4.488 3.6-1.571.735-3.32.735-1.573 0-3.246-.609.457-1.318 1.496-3.93 1.116.533 2.231.533.456 0 .938-.127.938-.177 1.673-.507 1.09-.456.989-.963-.127-.583-1.369-.583-.913 0-2.434.228-1.546.304-2.434.304-1.115 0-1.8-.33-1.343-.557-1.394-1.774-.203-2.408 2.916-5.577 3.245-3.321 6.21-3.499 2.815-.076 3.6 3.093zm14.476 1.166q-3.22.178-4.589.28-.279 2.661.685 11.585l-5.375.989-.025-12.22q-.457.025-1.902.228l-2.155.203.304-4.234 12.22-1.014zm4.715 1.166-.05 1.572q1.014-.405 1.85-.988 1.47-.99 1.293-1.75-.101-.558-1.014-.608-.684-.026-1.952.253-.127.786-.127 1.521zm3.068-5.476q4.158 0 4.791 2.89.355 3.474-3.524 5.68.584.81 4.462 5.02-1.597 1.267-4.081 3.168l-4.766-6.186q-.026 2.966.177 5.4l-4.538 1.014q.431-11.814-.076-15.92 4.487-1.066 7.555-1.066zm13.512 4.31q-1.394.178-2.61.28-.128.76-.153 1.115l-.05 1.09 2.129-.305 2.028-.304.178 1.496.202 1.547q-1.977.278-2.61.38-1.066.177-1.928.304l-.05 2.054 2.788-.355 2.79-.38.278 2.078.279 2.003q-2.13.127-4.842.482-2.713.43-5.958 1.04-.025-1.32-.05-3.905l-.077-3.955q-.05-5.197-.05-7.86l4.918-.506 4.969-.558.304 2.028.33 2.054q-1.47.076-2.815.177zm11.637 0q-1.394.178-2.611.28-.127.76-.152 1.115l-.051 1.09 2.13-.305 2.028-.304.177 1.496.203 1.547q-1.977.278-2.611.38-1.065.177-1.927.304l-.05 2.054 2.788-.355 2.789-.38.279 2.078.278 2.003q-2.13.127-4.842.482-2.712.43-5.957 1.04-.026-1.32-.051-3.905l-.076-3.955q-.051-5.197-.051-7.86l4.918-.506 4.97-.558.304 2.028.33 2.054q-1.471.076-2.815.177zm16.682 0q-3.22.178-4.589.28-.279 2.661.684 11.585l-5.374.989-.026-12.22q-.456.025-1.9.228l-2.156.203.304-4.234 12.22-1.014zm.431-3.65 5.095-.761q.153.684.431 1.977.736 2.941 1.572 5.198.457 1.293.938 2.053v-8.518l5.121-.71q-1.039 8.822-.43 15.211l-4.64 1.014q-.025-.05-.558-.811l-2.357-3.245q-.66-.76-.761-1.04 0 2.662.203 5.096l-4.589.964zm15.464 2.484q2.155-2.94 5.68-2.94 2.94 0 4.968 2.433 1.09 1.344 1.521 2.763.406 1.445.33 3.144-.101 2.89-1.901 5.324-2.18 2.966-5.705 2.966-2.991 0-4.994-2.459-.837-1.065-1.369-2.637-.482-1.546-.406-3.194.152-3.017 1.876-5.4zm7.429 2.814q-.71-1.445-1.851-1.445-1.37 0-2.282 1.85-.608 1.37-.659 2.84-.05.735.127 1.37.279.912.836 1.343.71.558 1.674.558 1.445 0 2.18-1.116.532-.938.558-2.358.025-1.571-.583-3.042zm5.805-4.893 2.586-.33 2.56-.38q-.532 4.412-.658 8.24-.077 3.777.202 6.972l-2.281.557-2.383.406zm18.507 2.079q.152.76.304 1.699-.71.253-2.18.659l-2.206.634q-.202-.99-1.014-.99-1.14 0-2.078.863-.584.71-.38 1.343.227.38 1.343.38.558 0 1.47-.126 1.293-.177 2.307-.177 2.56 0 3.322 1.267.228.431.33.811.354 1.42-.787 3.043-1.394 2.13-4.487 3.6-1.572.735-3.321.735-1.572 0-3.245-.609.456-1.318 1.495-3.93 1.116.533 2.231.533.457 0 .938-.127.938-.177 1.674-.507 1.09-.456.988-.963-.126-.583-1.369-.583-.912 0-2.433.228-1.547.304-2.434.304-1.116 0-1.8-.33-1.344-.557-1.395-1.774-.202-2.408 2.916-5.577 3.245-3.321 6.211-3.499 2.814-.076 3.6 3.093zm9.432 1.166q-1.395.178-2.612.28-.127.76-.152 1.115l-.05 1.09 2.129-.305 2.028-.304.177 1.496.203 1.547q-1.977.278-2.611.38-1.065.177-1.927.304l-.05 2.054 2.788-.355 2.789-.38.279 2.078.279 2.003q-2.13.127-4.843.482-2.712.43-5.957 1.04-.026-1.32-.051-3.905l-.076-3.955q-.05-5.197-.05-7.86l4.917-.506 4.97-.558.304 2.028.33 2.054q-1.471.076-2.815.177z",  :transform "translate(464.147 -190.53)"}]]
                 [:path {:class "logotype-snoman" :fill "currentColor" :d "M-457.989 215.698a4.223 4.223 0 0 1-.54-.748l-.506-.931a7.85 7.85 0 0 1-.467-1.101c-.243-.754-.48-1.095-1.726-2.492-.558-.625-1.192-1.406-1.41-1.735-.216-.33-.513-.711-.66-.848-.2-.188-.265-.322-.265-.549 0-.259.074-.37.537-.81.295-.28.635-.642.755-.805.475-.643.883-2.321 1.16-4.782.095-.837.173-1.537.173-1.557 0-.046-1.743.06-2.036.125a.483.483 0 0 1-.413-.113c-.175-.142-.228-.36-.482-1.975-.326-2.079-.35-2.493-.149-2.57.076-.03.428-.111.783-.183 2.449-.491 4.36-1.723 5.394-3.473.268-.454.395-.591.564-.611.273-.033.252-.105.568 1.93.332 2.13.286 1.986.714 2.28.442.304.645.743.645 1.393 0 .347-.049.518-.21.74-.525.718-.489.909.141.75.572-.144.949.039 1.177.57.406.944 1.261 3.71 1.632 5.275.504 2.132 1.067 4.901 1.075 5.295.006.317.313 1.253.325.993.005-.104-.071-.81-.169-1.567-.235-1.828-.196-2.627.166-3.385.146-.306.265-.61.265-.676 0-.18-.448-.842-.75-1.106-.284-.25-.326-.391-.164-.554.28-.28.93.01 1.592.713.256.272.758.723 1.115 1.004 1.638 1.287 2.017 1.683 2.017 2.103 0 .197-.034.228-.258.228-.141 0-.546-.143-.897-.317l-.64-.316-.194.242c-.19.238-.245.425-.38 1.28-.074.471.086 2.31.293 3.372.28 1.44.102 2.233-.602 2.68-1.075.682-2.339.214-2.858-1.059a11.67 11.67 0 0 0-.34-.759 3.385 3.385 0 0 1-.225-.705c-.079-.378-.117-.44-.235-.382-.252.125-1.56.056-2.457-.13-.482-.1-.93-.181-.995-.181-.18 0-.144.365.14 1.397l.39 1.418.135.487h.355c.284 0 .403.05.6.257.214.222.251.335.28.838.031.554.022.59-.194.73-.196.13-.282.136-.65.05-.569-.13-.56-.133-.623.156-.07.316-.069.315-.68.383-.503.055-.507.054-.816-.27zm3.782-7.924c0-.334.02-.365.282-.442.255-.075.277-.107.236-.34-.026-.14-.092-.549-.147-.908-.055-.36-.132-.634-.171-.61-.202.125-1.062.277-1.851.327-.832.054-1.953.194-2.01.251-.16.159.487.576 1.456.94.735.278 1.277.558 1.697.879.186.142.376.26.423.26.047.002.085-.159.085-.357zm-1.159-3.877c.295-.047.66-.091.813-.097l.277-.01-.154-.855a41.255 41.255 0 0 1-.25-1.574c-.115-.862-.36-2.03-.525-2.498-.094-.267-.167-.344-.347-.366-.2-.023-.222-.003-.182.17.087.378.384 2.354.384 2.56 0 .115-.037.231-.083.26-.109.067-.511-.147-.722-.384-.265-.3-1.355-.984-1.877-1.18-.26-.097-.483-.166-.496-.153-.012.014.012.234.054.49.042.256.108.833.147 1.282.064.729.107.877.398 1.375.289.493.37.572.712.68.212.067.537.18.724.251.41.157.451.16 1.127.05z" :transform "translate(464.147 -190.53)"}]]))

(def chart-bar-square
  (deficon
    ;; hero icons
    [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M7.5 14.25v2.25m3-4.5v4.5m3-6.75v6.75m3-9v9M6 20.25h12A2.25 2.25 0 0 0 20.25 18V6A2.25 2.25 0 0 0 18 3.75H6A2.25 2.25 0 0 0 3.75 6v12A2.25 2.25 0 0 0 6 20.25z"}]]))

(def images (deficon
              [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 576 512" :fill "currentColor"}
               ;; "<!--!Font Awesome Free 6.5.2 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.-->"
               [:path {:d "M160 80h352c8.8 0 16 7.2 16 16v224c0 8.8-7.2 16-16 16h-21.2L388.1 178.9c-4.4-6.8-12-10.9-20.1-10.9s-15.7 4.1-20.1 10.9l-52.2 79.8-12.4-16.9c-4.5-6.2-11.7-9.8-19.4-9.8s-14.8 3.6-19.4 9.8L175.6 336H160c-8.8 0-16-7.2-16-16V96c0-8.8 7.2-16 16-16zM96 96v224c0 35.3 28.7 64 64 64h352c35.3 0 64-28.7 64-64V96c0-35.3-28.7-64-64-64H160c-35.3 0-64 28.7-64 64zm-48 24c0-13.3-10.7-24-24-24S0 106.7 0 120v224c0 75.1 60.9 136 136 136h320c13.3 0 24-10.7 24-24s-10.7-24-24-24H136c-48.6 0-88-39.4-88-88V120zm208 24a32 32 0 1 0-64 0 32 32 0 1 0 64 0z"}]]))

(def images-solid (deficon
                    [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 576 512" :fill "currentColor"}
 ;; "<!--!Font Awesome Free 6.5.2 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.-->"
                     [:path {:d "M160 32c-35.3 0-64 28.7-64 64v224c0 35.3 28.7 64 64 64h352c35.3 0 64-28.7 64-64V96c0-35.3-28.7-64-64-64H160zm236 106.7 96 144c4.9 7.4 5.4 16.8 1.2 24.6S480.9 320 472 320H200c-9.2 0-17.6-5.3-21.6-13.6s-2.9-18.2 2.9-25.4l64-80c4.6-5.7 11.4-9 18.7-9s14.2 3.3 18.7 9l17.3 21.6 56-84c4.5-6.6 12-10.6 20-10.6s15.5 4 20 10.7zM192 128a32 32 0 1 1 64 0 32 32 0 1 1-64 0zm-144-8c0-13.3-10.7-24-24-24S0 106.7 0 120v224c0 75.1 60.9 136 136 136h320c13.3 0 24-10.7 24-24s-10.7-24-24-24H136c-48.6 0-88-39.4-88-88V120z"}]]))

(def arrow-small-left
  ;; hero icons
  (deficon
    [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M19.5 12h-15m0 0 6.75 6.75M4.5 12l6.75-6.75"}]]))

(def arrow-right
  ;; heroicon
  (deficon
    [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class "w-6 h-6", :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3"}]]))

(def thumbs-up
  (deficon
    [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 512 512" :fill "currentColor"}
     ;; "<!--!Font Awesome Free 6.5.2 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.-->"
     [:path {:d "M313.4 32.9c26 5.2 42.9 30.5 37.7 56.5l-2.3 11.4c-5.3 26.7-15.1 52.1-28.8 75.2h144c26.5 0 48 21.5 48 48 0 18.5-10.5 34.6-25.9 42.6C497 275.4 504 288.9 504 304c0 23.4-16.8 42.9-38.9 47.1 4.4 7.3 6.9 15.8 6.9 24.9 0 21.3-13.9 39.4-33.1 45.6.7 3.3 1.1 6.8 1.1 10.4 0 26.5-21.5 48-48 48h-97.5c-19 0-37.5-5.6-53.3-16.1l-38.5-25.7C176 420.4 160 390.4 160 358.3V247.1c0-29.2 13.3-56.7 36-75l7.4-5.9c26.5-21.2 44.6-51 51.2-84.2l2.3-11.4c5.2-26 30.5-42.9 56.5-37.7zM32 192h64c17.7 0 32 14.3 32 32v224c0 17.7-14.3 32-32 32H32c-17.7 0-32-14.3-32-32V224c0-17.7 14.3-32 32-32z"}]]))
;;; <END LEGACY CODE>

;; Runtime SVG sprite support for app.ui2.icon.

(def icon-libraries
  [{:id          :snoico
    :source-root "public/img/snoico"
    :icons       [:apple-calendar
                  :bars
                  :calendar
                  :chart-bar-square
                  :chevron-down
                  :circle
                  :circle-check
                  :circle-check-outline
                  :circle-dot-outline
                  :circle-exclamation
                  :circle-outline
                  :circle-plus-solid
                  :circle-question
                  :circle-question-outline
                  :circle-xmark
                  :circle-xmark-outline
                  :cog
                  :comment-outline
                  :comments
                  :copy
                  :dots-six
                  :dots-six-vertical
                  :ellipsis
                  :envelope
                  :file-audio-solid
                  :file-csv-solid
                  :file-excel-outline
                  :file-excel-solid
                  :file-image-solid
                  :file-pdf-outline
                  :file-pdf-solid
                  :file-powerpoint-solid
                  :file-solid
                  :file-video-solid
                  :file-word-solid
                  :file-zipper-solid
                  :fist-punch
                  :folder-open
                  :google-calendar
                  :home
                  :location-dot
                  :logotype
                  :meh
                  :microsoft-365
                  :minus
                  :music-note-outline
                  :outlook
                  :question
                  :sad
                  :shield-check-outline
                  :smile
                  :sno-trumpet
                  :snoman
                  :snomegaphone
                  :square
                  :square-info
                  :square-outline
                  :trumpet
                  :user
                  :users-outline
                  :xmark]}
   {:id          :phosphor
    :source-root "public/img/phosphor/phosphor-regular"
    :icons       [:arrow-bend-down-right
                  :calendar
                  :caret-left
                  :caret-right
                  :check
                  :download
                  :funnel
                  :hash
                  :info
                  :magnifying-glass
                  :sliders-horizontal
                  :star
                  :trend-up
                  :warning
                  :money
                  :money-wavy
                  :currency-eur
                  :bank
                  :coin
                  :coins
                  :hand-coins
                  :hand-pointing
                  :table]}])

(defonce sprite-manifest_ (atom nil))

(defn- ->id [value]
  (keyword (name value)))

(defn- ->icon-name [value]
  (name value))

(defn- icon-resource-path [{:keys [source-root]} icon-name]
  (str source-root "/" (->icon-name icon-name) ".svg"))

(defn- duplicate-values [values]
  (->> values
       frequencies
       (keep (fn [[value n]]
               (when (< 1 n)
                 value)))
       seq))

(defn validate-libraries! [libraries]
  (doseq [{:keys [id icons] :as library} libraries]
    (when-let [dups (duplicate-values (map ->id icons))]
      (throw (ex-info "Duplicate icon names in icon library"
                      {:library id
                       :duplicates (vec dups)})))
    (doseq [icon icons]
      (let [path (icon-resource-path library icon)]
        (when-not (io/resource path)
          (throw (ex-info "Cannot load registered icon resource"
                          {:library id
                           :icon    icon
                           :path    path})))))))

(defn- parse-svg-resource [path]
  (if-let [resource (io/resource path)]
    (xml/parse-str (slurp resource))
    (throw (ex-info "Cannot load registered icon resource"
                    {:path path}))))

(defn- tag-name [node]
  (some-> node :tag name))

(defn- attr-value [attrs attr-name]
  (some (fn [[k v]]
          (when (= attr-name (name k))
            v))
        attrs))

(defn- root-viewbox [svg path]
  (or (attr-value (:attrs svg) "viewBox")
      (attr-value (:attrs svg) "viewbox")
      (throw (ex-info "Registered icon SVG is missing viewBox"
                      {:path path}))))

(defn- element-node? [node]
  (and (map? node)
       (contains? node :tag)))

(defn- skipped-symbol-child? [node]
  (and (element-node? node)
       (#{"title" "desc"} (tag-name node))))

(def ^:private root-presentation-attr-names
  #{"clip-rule"
    "color"
    "fill"
    "fill-opacity"
    "fill-rule"
    "opacity"
    "stroke"
    "stroke-dasharray"
    "stroke-dashoffset"
    "stroke-linecap"
    "stroke-linejoin"
    "stroke-miterlimit"
    "stroke-opacity"
    "stroke-width"
    "vector-effect"})

(defn- preserved-root-presentation-attr? [[attr value]]
  (let [attr-name (name attr)]
    (and (root-presentation-attr-names attr-name)
         (or (not= "fill" attr-name)
             (#{"currentColor" "currentcolor" "none"} value)))))

(defn- root-presentation-attrs [svg]
  (into {} (filter preserved-root-presentation-attr?) (:attrs svg)))

(defn- emit-xml [node]
  (str/replace (xml/emit-str node) #"^<\?xml[^>]*>\s*" ""))

(defn- symbol-body [svg]
  (let [content (remove skipped-symbol-child? (:content svg))
        attrs   (root-presentation-attrs svg)]
    (if (seq attrs)
      (emit-xml {:tag :g :attrs attrs :content content})
      (->> content
           (map emit-xml)
           (apply str)))))

(defn- symbol-id [library-id icon-name]
  (str (name (->id library-id)) "-" (->icon-name icon-name)))

(defn- icon-symbol [{:keys [id] :as library} icon-name]
  (let [path (icon-resource-path library icon-name)
        svg  (parse-svg-resource path)]
    (when-not (= "svg" (tag-name svg))
      (throw (ex-info "Registered icon resource is not an SVG"
                      {:library id
                       :icon    icon-name
                       :path    path
                       :tag     (:tag svg)})))
    {:icon      (->id icon-name)
     :symbol-id (symbol-id id icon-name)
     :viewBox   (root-viewbox svg path)
     :body      (symbol-body svg)}))

(defn- symbol-str [{:keys [symbol-id viewBox body]}]
  (str "<symbol id=\"" symbol-id "\" viewBox=\"" viewBox "\">" body "</symbol>"))

(defn- sha-256-hex [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- gzip-bytes [^bytes body]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gzip (GZIPOutputStream. out)]
      (.write gzip body))
    (.toByteArray out)))

(defn- brotli-bytes [^bytes body]
  (br/compress body))

(defn- accepted-sprite-encoding [req]
  (cond
    (compression/accepts-brotli? req) :br
    (compression/accepts-gzip? req)   :gzip
    :else                             :identity))

(defn- sprite-response-template [etag encoding ^bytes body]
  {:status  200
   :headers (cond-> {"Content-Type"   "image/svg+xml; charset=utf-8"
                     "Cache-Control"  "public, max-age=31536000, immutable"
                     "Content-Length" (str (alength body))
                     "ETag"           (str "\"" etag "\"")
                     "Vary"           "Accept-Encoding"}
              (= :br encoding)   (assoc "content-encoding" "br")
              (= :gzip encoding) (assoc "content-encoding" "gzip"))
   :body    body})

(defn- precompressed-sprite-responses [^bytes body etag]
  {:identity (sprite-response-template etag :identity body)
   :gzip     (sprite-response-template etag :gzip (gzip-bytes body))
   :br       (sprite-response-template etag :br (brotli-bytes body))})

(defn- memory-response [response]
  (update response :body #(ByteArrayInputStream. ^bytes %)))

(defn- library-sprite [{:keys [id icons] :as library}]
  (let [library-id (->id id)
        symbols    (mapv #(icon-symbol library %) icons)
        body       (str "<svg xmlns=\"http://www.w3.org/2000/svg\">"
                        (apply str (map symbol-str symbols))
                        "</svg>")
        bytes      (.getBytes ^String body StandardCharsets/UTF_8)
        digest     (sha-256-hex bytes)
        short      (subs digest 0 16)
        filename   (str (name library-id) "." short ".svg")
        url        (str "/img/icons/" filename)
        icons      (into {} (map (fn [{:keys [icon symbol-id viewBox]}]
                                   [icon {:symbol-id symbol-id
                                          :viewBox   viewBox}])
                                 symbols))]
    {:id        library-id
     :filename  filename
     :url       url
     :body      body
     :bytes     bytes
     :responses (precompressed-sprite-responses bytes digest)
     :etag      digest
     :icons     icons}))

(defn build-sprite-manifest
  ([]
   (build-sprite-manifest icon-libraries))
  ([libraries]
   (validate-libraries! libraries)
   (let [library-sprites (map library-sprite libraries)
         by-library      (into {} (map (juxt :id identity) library-sprites))
         by-filename     (into {} (map (juxt :filename identity) library-sprites))]
     {:by-library  by-library
      :by-filename by-filename})))

(defn install-sprite-manifest! [manifest]
  (reset! sprite-manifest_ manifest)
  manifest)

(defn current-sprite-manifest []
  (or @sprite-manifest_
      (install-sprite-manifest! (build-sprite-manifest))))

(defn sprite-href
  ([library icon]
   (sprite-href (current-sprite-manifest) library icon))
  ([manifest library icon]
   (let [library-id (->id library)
         icon-id    (->id icon)
         library    (get-in manifest [:by-library library-id])
         icon       (get-in library [:icons icon-id])]
     (when-not icon
       (throw (ex-info "Icon is not registered"
                       {:library library-id
                        :icon    icon-id})))
     (str (:url library) "#" (:symbol-id icon)))))

(defn sprite-response
  ([manifest filename]
   (sprite-response {} manifest filename))
  ([req manifest filename]
   (if-let [{:keys [responses]} (get-in manifest [:by-filename filename])]
     (memory-response (get responses (accepted-sprite-encoding req)))
     {:status  404
      :headers {"Content-Type" "text/plain; charset=utf-8"}
      :body    "Icon sprite not found"})))

(defn- request-filename [req]
  (or (get-in req [:parameters :path :filename])
      (get-in req [:path-params :filename])
      (get-in req [:params :filename])
      (get-in req [:params "filename"])))

(defn sprite-handler [manifest]
  (fn [req]
    (sprite-response req
                     (or manifest (current-sprite-manifest))
                     (request-filename req))))

(defn routes [manifest]
  ["/img/icons/{filename}"
   {:get {:handler (sprite-handler manifest)}}])

(defmethod ig/init-key ::sprites
  [_ _]
  (install-sprite-manifest! (build-sprite-manifest)))
