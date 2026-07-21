(ns datastar-kit.assets
  "Ordered browser assets for Datastar applications.

   The Basic-Auth bootstrap MUST run before the Datastar module. Consumers pass
   their own asset-url function so cache-busting remains owned by the app.")

(defn script-tags
  "Return Datastar script tags in dependency order.

   Options:
   - :asset-url       path -> public URL, commonly an app cache-buster (default identity)
   - :basic-auth?     include datastar-auth-fix.js before Datastar (default false)
   - :keyboard-chords? include keyboard-chords.js before consumer scripts (default false)
   - :kit-runtime?    include datastar-kit.js after Datastar (default false)
   - :datastar-path   override the vendored Datastar path

   Example:
   (script-tags {:asset-url views/static :basic-auth? true :kit-runtime? true})"
  [{:keys [asset-url basic-auth? keyboard-chords? kit-runtime? datastar-path]
    :or {asset-url identity
         basic-auth? false
         kit-runtime? false
         datastar-path "/vendor/datastar-aliased.js"}}]
  (seq
    (cond-> []
      basic-auth?
      (conj [:script {:src (asset-url "/js/datastar-auth-fix.js")}])

      keyboard-chords?
      (conj [:script {:src (asset-url "/js/keyboard-chords.js")}])

      true
      (conj [:script {:type "module" :src (asset-url datastar-path)}])

      kit-runtime?
      (conj [:script {:src (asset-url "/js/datastar-kit.js")}]))))
