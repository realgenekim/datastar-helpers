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
