(ns datastar-kit.testing
  "The consumer contract as plain functions and one clojure.test macro, so an
   app can adopt the kit's copy checks in one require + one line.

   Reach without adoption comes from datastar-kit.assets/script-tags, which
   runs the same copy audit once per process and warns on standard error;
   this namespace is for apps that want the checks to fail a test run
   instead of only printing a warning."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test]
   [datastar-kit.assets :as assets]))

(defn- relative-path
  "root and f are both java.io.File under root; return f's path relative to
   root, using forward slashes regardless of platform."
  [root f]
  (-> (.toPath ^java.io.File root)
      (.relativize (.toPath ^java.io.File f))
      str
      (str/replace java.io.File/separatorChar \/)))

(defn- files-under
  "Every regular file under dir (a java.io.File directory), as a vector of
   {:file :rel}. [] when dir does not exist or is not a directory."
  [^java.io.File dir]
  (if-not (.isDirectory dir)
    []
    (vec
     (for [^java.io.File f (file-seq dir)
           :when (.isFile f)]
       {:file f :rel (relative-path dir f)}))))

;; @spec CONTRACT-020
(defn shadowed-public-files
  "For every regular file under public-root (a directory path string),
   resolve its providers at \"public/<relative path>\" through the thread's
   context class loader. Returns a vector, sorted by path, of
   {:path <relative path> :providers [<provider URL string> ...]} for every
   file with more than one provider. A missing public-root returns []."
  [public-root]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (->> (files-under (io/file public-root))
         (keep (fn [{:keys [rel]}]
                 (let [providers (mapv str (enumeration-seq (.getResources cl (str "public/" rel))))]
                   (when (> (count providers) 1)
                     {:path rel :providers providers}))))
         (sort-by :path)
         vec)))

(defn- read-manifest [manifest]
  (if (map? manifest)
    manifest
    (edn/read-string (slurp (io/file manifest)))))

(defn- served-scripts
  "The relative paths (forward-slash) of every .js/.mjs file under public-root."
  [^java.io.File root]
  (->> (files-under root)
       (map :rel)
       (filter #(re-find #"\.(js|mjs)$" %))
       set))

(defn- bad-pin
  "nil, or {:path :reason} for one third-party manifest entry, given root
   and whether path exists on disk (served? true only for a served script,
   which is enough since a missing file is already reported under :missing)."
  [root path {:keys [sha256 source version]} served?]
  (cond
    (or (not (string? source)) (not (string? version)))
    {:path path :reason :missing-provenance}

    (and served?
         (not= sha256 (assets/sha256-hex (.readAllBytes (io/input-stream (io/file root path))))))
    {:path path :reason :sha-mismatch}

    :else nil))

;; @spec CONTRACT-021
(defn unauthorized-js
  "public-root is a directory path string; manifest is either a map or a
   path string to an EDN file shaped
   {:app #{\"js/a.js\" ...} :third-party {\"path\" {:sha256 ... :source ... :version ...}}}.

   Returns
   {:unlisted [<served .js/.mjs paths the manifest does not list>, sorted]
    :missing  [<manifest paths with no file>, sorted]
    :bad-pins [{:path :reason :missing-provenance|:sha-mismatch} ...]}."
  [public-root manifest]
  (let [{:keys [app third-party] :or {app #{} third-party {}}} (read-manifest manifest)
        root (io/file public-root)
        served (served-scripts root)
        manifest-paths (set/union (set app) (set (keys third-party)))]
    {:unlisted (vec (sort (set/difference served manifest-paths)))
     :missing (vec (sort (set/difference manifest-paths served)))
     :bad-pins (->> third-party
                    (keep (fn [[path entry]]
                            (bad-pin root path entry (contains? served path))))
                    (sort-by :path)
                    vec)}))

;; @spec CONTRACT-022, CONTRACT-023
(defmacro defcontract-tests
  "Define, in the calling namespace, the consumer contract as clojure.test
   tests:

   - kit-contract-no-kit-copies             -- fails on any copy-audit finding
   - kit-contract-no-shadowed-public-files  -- fails on any shadowed-public-files result
   - kit-contract-only-authorized-js        -- only when :js-manifest is given;
                                                fails on any unauthorized-js result

   Options:
   - :public-root  directory path string (default \"resources/public\")
   - :js-manifest  optional map or EDN file path, passed to unauthorized-js

   Expands with fully qualified symbols, so the caller needs no requires
   beyond datastar-kit.testing.

   Example:
   (datastar-kit.testing/defcontract-tests
     {:public-root \"resources/public\" :js-manifest \"test/authorized-js.edn\"})"
  [{:keys [public-root js-manifest] :or {public-root "resources/public"}}]
  `(do
     (clojure.test/deftest ~'kit-contract-no-kit-copies
       (let [findings# (datastar-kit.assets/copy-audit)]
         (clojure.test/is (empty? findings#)
                          (str "kit assets shadowed or stale on the classpath: " (pr-str findings#)))))
     (clojure.test/deftest ~'kit-contract-no-shadowed-public-files
       (let [findings# (datastar-kit.testing/shadowed-public-files ~public-root)]
         (clojure.test/is (empty? findings#)
                          (str "files under " ~public-root " shadowed by another classpath root: "
                               (pr-str findings#)))))
     ~(when js-manifest
        `(clojure.test/deftest ~'kit-contract-only-authorized-js
           (let [result# (datastar-kit.testing/unauthorized-js ~public-root ~js-manifest)]
             (clojure.test/is (empty? (:unlisted result#))
                              (str "scripts not listed in " ~js-manifest ": " (pr-str (:unlisted result#))))
             (clojure.test/is (empty? (:missing result#))
                              (str "manifest entries in " ~js-manifest " with no file: " (pr-str (:missing result#))))
             (clojure.test/is (empty? (:bad-pins result#))
                              (str "bad third-party pins in " ~js-manifest ": " (pr-str (:bad-pins result#)))))))))

;; ---------------------------------------------------------------------------
;; Command replay — the gesture contract for a fire-and-forget client
;;
;; A fire-and-forget client can always turn one click into two POSTs: a handler
;; that bubbles into an ancestor's own handler, a double tap, a retry after a
;; dropped response. "Every endpoint must be idempotent" is the wrong rule —
;; move-down and undo are legitimately repeatable. The rule that holds is:
;;
;;   a REPLAY of one command (the same command id) has ONE effect;
;;   a NEW command carries a NEW id.
;;
;; State equality alone cannot prove that. A handler that appends a second
;; durable line while writing the same projection value looks identical through
;; an atom deref, so `command-replay` also reads a caller-chosen effects count.
;; ---------------------------------------------------------------------------

(defn- diff-by-key
  "The keys whose value differs between two states, as
   {k {:after-first x :after-second y}} in a stable order. Only differing keys
   appear — a state is a projection and may be large. Non-map states compare
   whole, under the key :value."
  [a b]
  (if (and (map? a) (map? b))
    (into (array-map)
          (for [k (sort-by pr-str (distinct (concat (keys a) (keys b))))
                :let [x (get a k ::absent)
                      y (get b k ::absent)]
                :when (not= x y)]
            [k {:after-first x :after-second y}]))
    (if (= a b) {} {:value {:after-first a :after-second b}})))

;; @spec EDIT-030, EDIT-031
(defn command-replay
  "Send the SAME command twice and report what the second one changed.

   Options
     :post!    0-arity fn performing ONE POST with a FIXED body — the same
               command id both times. Called twice.
     :state    0-arity fn returning the server's own state for this command: an
               atom deref, or better a projection narrowed to what the command
               touches. Read after each post.
     :effects  0-arity fn returning something COUNTABLE the caller chooses —
               appended event count, log lines, rows written. Optional, but a
               duplicate durable write is invisible to :state alone: writing the
               same value twice leaves an identical projection.

   Returns
     {:replay-safe?  true when neither state nor effects moved on the replay
      :state-diff    differing keys only, {} when none
      :effects-diff  {:after-first x :after-second y}, or nil when unchanged
                     or when no :effects fn was given}

   A false :replay-safe? means the endpoint treats a repeat as a second
   command. Give the gesture a server-minted command id and commit that id
   once."
  [{:keys [post! state effects]}]
  (post!)
  (let [state-1 (when state (state))
        effects-1 (when effects (effects))
        _ (post!)
        state-2 (when state (state))
        effects-2 (when effects (effects))
        state-diff (diff-by-key state-1 state-2)
        effects-diff (when (and effects (not= effects-1 effects-2))
                       {:after-first effects-1 :after-second effects-2})]
    {:replay-safe? (and (empty? state-diff) (nil? effects-diff))
     :state-diff state-diff
     :effects-diff effects-diff}))

;; @spec EDIT-031
(defn replay-failure-message
  "The failure text for a `command-replay` result: what the replay moved, named
   under :state and :effects, and nothing that did not move. Never the whole
   state — a projection can be large, and the differing keys are the finding."
  [{:keys [state-diff effects-diff]}]
  (str "replaying the same command changed "
       (pr-str (cond-> {}
                 (seq state-diff) (assoc :state state-diff)
                 (some? effects-diff) (assoc :effects effects-diff)))
       " — a replay of one command must have ONE effect; a NEW command needs a NEW command id."))

;; @spec EDIT-032
(defmacro assert-command-replay
  "`command-replay` as one clojure.test assertion. Takes the same options map
   and returns the result, so a test can assert further on the diff.

   Expands with fully qualified symbols, so the caller needs no requires beyond
   datastar-kit.testing.

   (datastar-kit.testing/assert-command-replay
     {:post!   #(handler (submit-request \"cmd-7\"))
      :state   #(select-keys @db [:notes])
      :effects #(count @event-log)})"
  [opts]
  `(let [result# (datastar-kit.testing/command-replay ~opts)]
     (clojure.test/is (:replay-safe? result#)
                      (datastar-kit.testing/replay-failure-message result#))
     result#))
