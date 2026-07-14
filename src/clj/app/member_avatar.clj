(ns app.member-avatar
  "Canonical member avatar source selection.

  Managed member images take precedence over dormant Discourse templates.
  Callers can disable the legacy fallback on surfaces such as the profile editor."
  (:require
   [clojure.string :as str]))

(def forum-avatar-origin "https://forum.streetnoise.at")

(defn- requested-size [size]
  (cond
    (integer? size) size
    (string? size) (try
                     (Long/parseLong size)
                     (catch NumberFormatException _exception
                       80))
    :else 80))

(defn- absolute-template-src [template size]
  (let [template (some-> template str str/trim)]
    (when-not (str/blank? template)
      (let [src (str/replace template "{size}" (str size))]
        (if (re-find #"(?i)^https?://" src)
          src
          (str forum-avatar-origin
               (when-not (str/starts-with? src "/") "/")
               src))))))

(defn- managed-sizes [size]
  (if (<= size 40) [40 80] [160 320]))

(defn avatar-image
  "Resolves the canonical image URLs for `member`.

  Returns `nil` when no managed image exists and the legacy fallback is absent
  or disabled.

  Options:

  | key              | description
  |------------------|-------------
  | `:size`          | Requested CSS-pixel size
  | `:allow-legacy?` | Permit the Discourse template fallback (default `true`)"
  [member {:keys [size allow-legacy?]
           :or {size 80
                allow-legacy? true}}]
  (let [size (requested-size size)
        member-id (:member/member-id member)]
    (cond
      (and member-id (get-in member [:member/avatar :image/image-id]))
      (let [image-id (get-in member [:member/avatar :image/image-id])
            [one-x two-x] (managed-sizes size)
            path #(str "/member-avatar/" member-id "/" % "?v=" image-id)]
        {:src (path one-x)
         :srcset (str (path one-x) " 1x, " (path two-x) " 2x")
         :managed? true})

      (and allow-legacy? (:member/avatar-template member))
      (let [one-x (absolute-template-src (:member/avatar-template member) size)
            two-x (absolute-template-src (:member/avatar-template member)
                                         (* 2 size))]
        {:src one-x
         :srcset (str one-x " 1x, " two-x " 2x")
         :managed? false})

      :else nil)))
