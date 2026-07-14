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

(defn icon*
  ([svg]
   (icon* svg nil))
  ([svg {:keys [class] :or {class ""} :as opts}]
   (-> svg
       (update-in [1] #(merge % (dissoc opts :class)))
       (update-in [1 :class]
                  (fn [existing new]
                    (str new " " existing)) (str  "icon " class)))))

(defn deficon [svg] (partial icon* svg))

(def logotype (deficon
                [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 160.276 25.465"}
                 [:g [:path {:class "logotype-text" :fill "currentColor" :d "M-428.298 198.81q.152.76.304 1.699-.71.253-2.18.659l-2.206.634q-.203-.99-1.014-.99-1.14 0-2.079.863-.583.71-.38 1.343.228.38 1.344.38.557 0 1.47-.126 1.293-.177 2.307-.177 2.56 0 3.321 1.267.228.431.33.811.355 1.42-.786 3.043-1.395 2.13-4.488 3.6-1.571.735-3.32.735-1.573 0-3.246-.609.457-1.318 1.496-3.93 1.116.533 2.231.533.456 0 .938-.127.938-.177 1.673-.507 1.09-.456.989-.963-.127-.583-1.369-.583-.913 0-2.434.228-1.546.304-2.434.304-1.115 0-1.8-.33-1.343-.557-1.394-1.774-.203-2.408 2.916-5.577 3.245-3.321 6.21-3.499 2.815-.076 3.6 3.093zm14.476 1.166q-3.22.178-4.589.28-.279 2.661.685 11.585l-5.375.989-.025-12.22q-.457.025-1.902.228l-2.155.203.304-4.234 12.22-1.014zm4.715 1.166-.05 1.572q1.014-.405 1.85-.988 1.47-.99 1.293-1.75-.101-.558-1.014-.608-.684-.026-1.952.253-.127.786-.127 1.521zm3.068-5.476q4.158 0 4.791 2.89.355 3.474-3.524 5.68.584.81 4.462 5.02-1.597 1.267-4.081 3.168l-4.766-6.186q-.026 2.966.177 5.4l-4.538 1.014q.431-11.814-.076-15.92 4.487-1.066 7.555-1.066zm13.512 4.31q-1.394.178-2.61.28-.128.76-.153 1.115l-.05 1.09 2.129-.305 2.028-.304.178 1.496.202 1.547q-1.977.278-2.61.38-1.066.177-1.928.304l-.05 2.054 2.788-.355 2.79-.38.278 2.078.279 2.003q-2.13.127-4.842.482-2.713.43-5.958 1.04-.025-1.32-.05-3.905l-.077-3.955q-.05-5.197-.05-7.86l4.918-.506 4.969-.558.304 2.028.33 2.054q-1.47.076-2.815.177zm11.637 0q-1.394.178-2.611.28-.127.76-.152 1.115l-.051 1.09 2.13-.305 2.028-.304.177 1.496.203 1.547q-1.977.278-2.611.38-1.065.177-1.927.304l-.05 2.054 2.788-.355 2.789-.38.279 2.078.278 2.003q-2.13.127-4.842.482-2.712.43-5.957 1.04-.026-1.32-.051-3.905l-.076-3.955q-.051-5.197-.051-7.86l4.918-.506 4.97-.558.304 2.028.33 2.054q-1.471.076-2.815.177zm16.682 0q-3.22.178-4.589.28-.279 2.661.684 11.585l-5.374.989-.026-12.22q-.456.025-1.9.228l-2.156.203.304-4.234 12.22-1.014zm.431-3.65 5.095-.761q.153.684.431 1.977.736 2.941 1.572 5.198.457 1.293.938 2.053v-8.518l5.121-.71q-1.039 8.822-.43 15.211l-4.64 1.014q-.025-.05-.558-.811l-2.357-3.245q-.66-.76-.761-1.04 0 2.662.203 5.096l-4.589.964zm15.464 2.484q2.155-2.94 5.68-2.94 2.94 0 4.968 2.433 1.09 1.344 1.521 2.763.406 1.445.33 3.144-.101 2.89-1.901 5.324-2.18 2.966-5.705 2.966-2.991 0-4.994-2.459-.837-1.065-1.369-2.637-.482-1.546-.406-3.194.152-3.017 1.876-5.4zm7.429 2.814q-.71-1.445-1.851-1.445-1.37 0-2.282 1.85-.608 1.37-.659 2.84-.05.735.127 1.37.279.912.836 1.343.71.558 1.674.558 1.445 0 2.18-1.116.532-.938.558-2.358.025-1.571-.583-3.042zm5.805-4.893 2.586-.33 2.56-.38q-.532 4.412-.658 8.24-.077 3.777.202 6.972l-2.281.557-2.383.406zm18.507 2.079q.152.76.304 1.699-.71.253-2.18.659l-2.206.634q-.202-.99-1.014-.99-1.14 0-2.078.863-.584.71-.38 1.343.227.38 1.343.38.558 0 1.47-.126 1.293-.177 2.307-.177 2.56 0 3.322 1.267.228.431.33.811.354 1.42-.787 3.043-1.394 2.13-4.487 3.6-1.572.735-3.321.735-1.572 0-3.245-.609.456-1.318 1.495-3.93 1.116.533 2.231.533.457 0 .938-.127.938-.177 1.674-.507 1.09-.456.988-.963-.126-.583-1.369-.583-.912 0-2.433.228-1.547.304-2.434.304-1.116 0-1.8-.33-1.344-.557-1.395-1.774-.202-2.408 2.916-5.577 3.245-3.321 6.211-3.499 2.814-.076 3.6 3.093zm9.432 1.166q-1.395.178-2.612.28-.127.76-.152 1.115l-.05 1.09 2.129-.305 2.028-.304.177 1.496.203 1.547q-1.977.278-2.611.38-1.065.177-1.927.304l-.05 2.054 2.788-.355 2.789-.38.279 2.078.279 2.003q-2.13.127-4.843.482-2.712.43-5.957 1.04-.026-1.32-.051-3.905l-.076-3.955q-.05-5.197-.05-7.86l4.917-.506 4.97-.558.304 2.028.33 2.054q-1.471.076-2.815.177z", :transform "translate(464.147 -190.53)"}]]
                 [:path {:class "logotype-snoman" :fill "currentColor" :d "M-457.989 215.698a4.223 4.223 0 0 1-.54-.748l-.506-.931a7.85 7.85 0 0 1-.467-1.101c-.243-.754-.48-1.095-1.726-2.492-.558-.625-1.192-1.406-1.41-1.735-.216-.33-.513-.711-.66-.848-.2-.188-.265-.322-.265-.549 0-.259.074-.37.537-.81.295-.28.635-.642.755-.805.475-.643.883-2.321 1.16-4.782.095-.837.173-1.537.173-1.557 0-.046-1.743.06-2.036.125a.483.483 0 0 1-.413-.113c-.175-.142-.228-.36-.482-1.975-.326-2.079-.35-2.493-.149-2.57.076-.03.428-.111.783-.183 2.449-.491 4.36-1.723 5.394-3.473.268-.454.395-.591.564-.611.273-.033.252-.105.568 1.93.332 2.13.286 1.986.714 2.28.442.304.645.743.645 1.393 0 .347-.049.518-.21.74-.525.718-.489.909.141.75.572-.144.949.039 1.177.57.406.944 1.261 3.71 1.632 5.275.504 2.132 1.067 4.901 1.075 5.295.006.317.313 1.253.325.993.005-.104-.071-.81-.169-1.567-.235-1.828-.196-2.627.166-3.385.146-.306.265-.61.265-.676 0-.18-.448-.842-.75-1.106-.284-.25-.326-.391-.164-.554.28-.28.93.01 1.592.713.256.272.758.723 1.115 1.004 1.638 1.287 2.017 1.683 2.017 2.103 0 .197-.034.228-.258.228-.141 0-.546-.143-.897-.317l-.64-.316-.194.242c-.19.238-.245.425-.38 1.28-.074.471.086 2.31.293 3.372.28 1.44.102 2.233-.602 2.68-1.075.682-2.339.214-2.858-1.059a11.67 11.67 0 0 0-.34-.759 3.385 3.385 0 0 1-.225-.705c-.079-.378-.117-.44-.235-.382-.252.125-1.56.056-2.457-.13-.482-.1-.93-.181-.995-.181-.18 0-.144.365.14 1.397l.39 1.418.135.487h.355c.284 0 .403.05.6.257.214.222.251.335.28.838.031.554.022.59-.194.73-.196.13-.282.136-.65.05-.569-.13-.56-.133-.623.156-.07.316-.069.315-.68.383-.503.055-.507.054-.816-.27zm3.782-7.924c0-.334.02-.365.282-.442.255-.075.277-.107.236-.34-.026-.14-.092-.549-.147-.908-.055-.36-.132-.634-.171-.61-.202.125-1.062.277-1.851.327-.832.054-1.953.194-2.01.251-.16.159.487.576 1.456.94.735.278 1.277.558 1.697.879.186.142.376.26.423.26.047.002.085-.159.085-.357zm-1.159-3.877c.295-.047.66-.091.813-.097l.277-.01-.154-.855a41.255 41.255 0 0 1-.25-1.574c-.115-.862-.36-2.03-.525-2.498-.094-.267-.167-.344-.347-.366-.2-.023-.222-.003-.182.17.087.378.384 2.354.384 2.56 0 .115-.037.231-.083.26-.109.067-.511-.147-.722-.384-.265-.3-1.355-.984-1.877-1.18-.26-.097-.483-.166-.496-.153-.012.014.012.234.054.49.042.256.108.833.147 1.282.064.729.107.877.398 1.375.289.493.37.572.712.68.212.067.537.18.724.251.41.157.451.16 1.127.05z" :transform "translate(464.147 -190.53)"}]]))

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
                  :arrow-fat-down
                  :arrow-square-out
                  :arrow-left
                  :bell
                  :bell-ringing
                  :bell-slash
                  :calendar
                  :car-profile
                  :caret-left
                  :caret-right
                  :check
                  :clipboard-text
                  :download
                  :download-simple
                  :eye
                  :funnel
                  :gear
                  :hash
                  :info
                  :magnifying-glass
                  :monitor
                  :moon
                  :palette
                  :paper-plane-right
                  :pause-circle
                  :pencil-simple
                  :plus-circle
                  :shield
                  :sliders-horizontal
                  :star
                  :sun
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
                  :table
                  :warehouse
                  :sign-out]}])

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
