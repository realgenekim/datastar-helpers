(ns datastar-kit.assets
  "Ordered browser assets for Datastar applications.

   The Basic-Auth bootstrap MUST run before the Datastar module. Consumers pass
   their own asset-url function so cache-busting remains owned by the app.

   The kit also serves its own URL-loaded assets: the vendored Datastar client
   and the kit runtime are embedded at compile time and served by
   `wrap-kit-assets` at content-hashed URLs, so a consuming app has no file to
   copy and no path to write."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

;; @spec KIT-ASSETS-001, KIT-ASSETS-002
(defn chunk-text
  "Split s into a vector of substrings, each at most 16000 chars, whose
   concatenation equals s. 16000 chars * 3 bytes worst-case modified-UTF-8
   stays under the JVM's 65,535-byte string-constant limit, so each chunk is
   safe to emit as its own string literal.

   Never splits a UTF-16 surrogate pair: if a chunk would end on a high
   surrogate, it is extended by one char to include its low surrogate.

   An empty string returns [\"\"]."
  [^String s]
  (let [len (count s)]
    (if (zero? len)
      [""]
      (loop [start 0
             chunks []]
        (if (>= start len)
          chunks
          (let [end (min (+ start 16000) len)
                end (if (and (< end len)
                             (Character/isHighSurrogate (.charAt s (dec end))))
                      (inc end)
                      end)]
            (recur end (conj chunks (subs s start end)))))))))

;; @spec KIT-ASSETS-001, KIT-ASSETS-002
(defmacro ^:private inline-resource
  "Embed a classpath resource while compiling this namespace. This deliberately
   survives thin-JAR builds that AOT-compile git deps but omit their resource dirs.

   The resource text is emitted as several string-literal chunks (see
   chunk-text) and joined at load, because a single asset can exceed the
   JVM's 65,535-byte string-constant limit."
  [path]
  (let [resource (io/resource path)]
    (when-not resource
      (throw (ex-info "Missing datastar-kit classpath resource" {:path path})))
    `(str ~@(chunk-text (slurp resource)))))

;; @spec KIT-ASSETS-005
(defn strip-comment-lines
  "Return s with every line whose trimmed content starts with a JavaScript
   line comment (//) removed, lines rejoined with \\n. A line that merely
   CONTAINS // after real code is left untouched -- only a line that is
   NOTHING but a comment (after trimming leading whitespace) is dropped.

   Safe only for source that contains no multi-line string or template
   literal (see inline-source-files-contain-no-backtick)."
  [s]
  (->> (str/split s #"\n" -1)
       (remove #(str/starts-with? (str/trim %) "//"))
       (str/join "\n")))

(def ^:private keyboard-chords-source
  (inline-resource "public/js/keyboard-chords.js"))

(def ^:private keyboard-chords-source-stripped
  (strip-comment-lines keyboard-chords-source))

(defn keyboard-chords-script
  "Return a self-contained script tag for the shared keyboard chord engine.

   The source is embedded when datastar-kit.assets is compiled, so consumers do
   not need to copy dependency resources into thin-JAR or container static dirs.
   Comment-only lines are stripped before emission (KIT-ASSETS-005); the copy
   audit always hashes the unstripped embedded text."
  []
  [:script keyboard-chords-source-stripped])

(def ^:private basic-auth-source
  (inline-resource "public/js/datastar-auth-fix.js"))

(def ^:private basic-auth-source-stripped
  (strip-comment-lines basic-auth-source))

;; @spec BASIC-AUTH-LOAD-002, KIT-ASSETS-005
(defn basic-auth-script
  "Return a self-contained script tag for the Basic-Auth bootstrap.

   Patches Request/fetch (credentialed URLs) and History (pushState/
   replaceState SecurityError) so Datastar and htmx work behind HTTP Basic
   Auth. MUST be the first script on the page, before htmx and Datastar. Safe
   to include on pages without credentials, and safe to include twice -- the
   script is idempotent via `__datastarAuthFixVersion`.

   The source is embedded when datastar-kit.assets is compiled, so consumers do
   not need to keep their own copy of datastar-auth-fix.js. Comment-only lines
   are stripped before emission (KIT-ASSETS-005); the copy audit always hashes
   the unstripped embedded text."
  []
  [:script basic-auth-source-stripped])

(def ^:private datastar-aliased-source
  (inline-resource "public/vendor/datastar-aliased.js"))

(def ^:private datastar-kit-runtime-source
  (inline-resource "public/js/datastar-kit.js"))

(defn sha256-hex
  "The full 64 lowercase hex characters of the SHA-256 digest of bs."
  [^bytes bs]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- sha256-hex12
  "The first 12 lowercase hex characters of the SHA-256 of bs."
  [^bytes bs]
  (subs (sha256-hex bs) 0 12))

(defn- embedded-asset [source]
  (let [bs (.getBytes ^String source "UTF-8")]
    {:bytes bs
     :sha (sha256-hex12 bs)}))

(def ^:private kit-assets
  "Kit assets served by URL: name -> {:bytes <byte[] UTF-8> :sha <12-hex sha256>}."
  {"datastar-aliased.js" (embedded-asset datastar-aliased-source)
   "datastar-kit.js" (embedded-asset datastar-kit-runtime-source)})

(def ^:private kit-asset-source-paths
  "The four kit asset classpath resource paths, in the copy audit's report
   order, paired with the raw text the kit embedded for each at compile time."
  [["public/vendor/datastar-aliased.js" datastar-aliased-source]
   ["public/js/datastar-kit.js" datastar-kit-runtime-source]
   ["public/js/datastar-auth-fix.js" basic-auth-source]
   ["public/js/keyboard-chords.js" keyboard-chords-source]])

(defn- resource-providers
  "Every classpath provider of path, as resolved by the thread's context class
   loader: a vector of {:url <str> :sha <64-hex sha256 of the provider's bytes>}."
  [path]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (vec
     (for [^java.net.URL url (enumeration-seq (.getResources cl path))]
       {:url (str url)
        :sha (sha256-hex (with-open [in (.openStream url)]
                           (.readAllBytes in)))}))))

;; @spec CONTRACT-001, CONTRACT-002, CONTRACT-003
(defn copy-audit
  "Inspect, through the thread's context class loader, every classpath
   provider of each kit asset resource path, and compare it with the bytes
   the kit embedded at compile time. Returns a vector of findings, in
   kit-asset-source-paths order:

   - more than one provider -> {:path :problem :shadowed :kit-sha :providers}
   - exactly one provider whose bytes differ from the embedded bytes ->
     {:path :problem :stale-copy :kit-sha :providers}
   - no provider, or one provider with identical bytes -> no finding for
     that path"
  []
  (vec
   (keep
    (fn [[path raw]]
      (let [kit-sha (sha256-hex (.getBytes ^String raw "UTF-8"))
            providers (resource-providers path)]
        (cond
          (> (count providers) 1)
          {:path path :problem :shadowed :kit-sha kit-sha :providers providers}

          (and (= (count providers) 1) (not= kit-sha (:sha (first providers))))
          {:path path :problem :stale-copy :kit-sha kit-sha :providers providers}

          :else nil)))
    kit-asset-source-paths)))

(defonce ^:private audit-done? (atom false))

(defn- format-finding
  "One human-readable warning line for a copy-audit finding."
  [{:keys [path problem kit-sha providers]}]
  (str "[datastar-kit] WARNING " path " is " problem
       " -- kit sha256 " kit-sha "; "
       (str/join "; " (map (fn [{:keys [url sha]}] (str "provider " url " sha256 " sha)) providers))
       ". Delete the app's copy; the kit serves this asset itself."))

;; @spec CONTRACT-010, CONTRACT-011, CONTRACT-012
(defn- warn-on-copies-once!
  "Run the copy audit the first time this is called in a process and print one
   warning line per finding to standard error. A no-op on every later call,
   and never lets an audit failure escape -- script-tags must return the same
   tags whether the audit finds nothing, finds something, or throws."
  []
  (when (compare-and-set! audit-done? false true)
    (try
      (doseq [finding (copy-audit)]
        (binding [*out* *err*]
          (println (format-finding finding))))
      (catch Throwable _ nil))))

;; @spec KIT-ASSETS-003, KIT-ASSETS-004
(defn asset-path
  "Return the content-hashed URL for a kit asset: /_kit/<sha>/<name>. Throws
   if name is not a known kit asset."
  [asset-name]
  (if-let [{:keys [sha]} (get kit-assets asset-name)]
    (str "/_kit/" sha "/" asset-name)
    (throw (ex-info "Unknown datastar-kit asset"
                    {:asset asset-name :known (sort (keys kit-assets))}))))

(defonce ^:private middleware-installed? (atom false))

(def ^:private kit-asset-uri-re #"^/_kit/([^/]+)/([^/]+)$")

(defn- kit-asset-response
  "The response for a kit-asset request, or nil if this request is not one --
   in which case the caller must delegate to the wrapped handler unchanged."
  [request]
  (when (contains? #{:get :head} (:request-method request))
    (when-let [[_ hash asset-name] (re-matches kit-asset-uri-re (:uri request))]
      (when-let [{asset-bytes :bytes :keys [sha]} (get kit-assets asset-name)]
        {:status 200
         :headers {"Content-Type" "text/javascript; charset=utf-8"
                   "Cache-Control" (if (= hash sha)
                                     "public, max-age=31536000, immutable"
                                     "no-cache")
                   "Content-Length" (str (alength ^bytes asset-bytes))}
         :body (when (= :get (:request-method request))
                 (java.io.ByteArrayInputStream. asset-bytes))}))))

;; @spec KIT-ASSETS-010, KIT-ASSETS-011, KIT-ASSETS-012, KIT-ASSETS-013
(defn wrap-kit-assets
  "Ring middleware that serves the kit's own assets at their content-hashed
   URLs (see asset-path). Supports both the 1-arity sync and 3-arity async
   Ring handler shapes. Plain maps only -- no ring library dependency.

   Marks the kit's middleware as installed in this process the moment it is
   wrapped (not per request); script-tags reads that switch to decide whether
   to emit kit URLs."
  [handler]
  (reset! middleware-installed? true)
  (fn
    ([request]
     (or (kit-asset-response request) (handler request)))
    ([request respond raise]
     (if-let [response (kit-asset-response request)]
       (respond response)
       (handler request respond raise)))))

;; @spec BASIC-AUTH-LOAD-003, KIT-ASSETS-020, KIT-ASSETS-021, KIT-ASSETS-022, CONTRACT-010, CONTRACT-011, CONTRACT-012
(defn script-tags
  "Return Datastar script tags in dependency order.

   Options:
   - :asset-url       path -> public URL, commonly an app cache-buster (default identity)
   - :basic-auth?     include the inlined Basic-Auth bootstrap before Datastar (default false)
   - :keyboard-chords? include keyboard-chords.js before consumer scripts (default false)
   - :kit-runtime?    include datastar-kit.js after Datastar (default false)
   - :datastar-path   override the vendored Datastar path

   The Datastar module and kit runtime `src`s switch to the kit's own
   content-hashed URLs (via asset-path, bypassing :asset-url) once
   wrap-kit-assets has been installed in this process -- installing the
   middleware is the single switch, so an app cannot emit kit URLs that
   nothing serves. An explicit :datastar-path always overrides the module
   path, whether or not the middleware is installed, and is always passed
   through :asset-url.

   The first call in a process also runs the copy audit and warns on standard
   error for each finding (see copy-audit); every later call is silent, and a
   failed audit never changes what this returns.

   Example:
   (script-tags {:asset-url views/static :basic-auth? true :kit-runtime? true})"
  [{:keys [asset-url basic-auth? keyboard-chords? kit-runtime? datastar-path]
    :or {asset-url identity
         basic-auth? false
         kit-runtime? false}}]
  (warn-on-copies-once!)
  (let [module-src (cond
                     datastar-path (asset-url datastar-path)
                     @middleware-installed? (asset-path "datastar-aliased.js")
                     :else (asset-url "/vendor/datastar-aliased.js"))
        runtime-src (when kit-runtime?
                      (if @middleware-installed?
                        (asset-path "datastar-kit.js")
                        (asset-url "/js/datastar-kit.js")))]
    (seq
     (cond-> []
       basic-auth?
       (conj (basic-auth-script))

       keyboard-chords?
       (conj (keyboard-chords-script))

       true
       (conj [:script {:type "module" :src module-src}])

       kit-runtime?
       (conj [:script {:src runtime-src}])))))
