(ns datastar-kit.assets
  "Ordered browser assets for Datastar applications.

   The Basic-Auth bootstrap MUST run before the Datastar module. Consumers pass
   their own asset-url function so cache-busting remains owned by the app."
  (:require
   [clojure.java.io :as io]))

(defmacro ^:private inline-resource
  "Embed a classpath resource while compiling this namespace. This deliberately
   survives thin-JAR builds that AOT-compile git deps but omit their resource dirs."
  [path]
  (let [resource (io/resource path)]
    (when-not resource
      (throw (ex-info "Missing datastar-kit classpath resource" {:path path})))
    (slurp resource)))

(def ^:private keyboard-chords-source
  (inline-resource "public/js/keyboard-chords.js"))

(defn keyboard-chords-script
  "Return a self-contained script tag for the shared keyboard chord engine.

   The source is embedded when datastar-kit.assets is compiled, so consumers do
   not need to copy dependency resources into thin-JAR or container static dirs."
  []
  [:script keyboard-chords-source])

(def ^:private basic-auth-source
  (inline-resource "public/js/datastar-auth-fix.js"))

;; @spec BASIC-AUTH-LOAD-002
(defn basic-auth-script
  "Return a self-contained script tag for the Basic-Auth bootstrap.

   Patches Request/fetch (credentialed URLs) and History (pushState/
   replaceState SecurityError) so Datastar and htmx work behind HTTP Basic
   Auth. MUST be the first script on the page, before htmx and Datastar. Safe
   to include on pages without credentials, and safe to include twice -- the
   script is idempotent via `__datastarAuthFixVersion`.

   The source is embedded when datastar-kit.assets is compiled, so consumers do
   not need to keep their own copy of datastar-auth-fix.js."
  []
  [:script basic-auth-source])

;; @spec BASIC-AUTH-LOAD-003
(defn script-tags
  "Return Datastar script tags in dependency order.

   Options:
   - :asset-url       path -> public URL, commonly an app cache-buster (default identity)
   - :basic-auth?     include the inlined Basic-Auth bootstrap before Datastar (default false)
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
     (conj (basic-auth-script))

     keyboard-chords?
     (conj (keyboard-chords-script))

     true
     (conj [:script {:type "module" :src (asset-url datastar-path)}])

     kit-runtime?
     (conj [:script {:src (asset-url "/js/datastar-kit.js")}]))))
