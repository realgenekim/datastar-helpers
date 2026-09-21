(ns datastar-kit.testing-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datastar-kit.assets :as assets]
   [datastar-kit.test-helpers :as th]
   [datastar-kit.testing :as kit]))

;; ---- CONTRACT-020: shadowed-public-files ----

;; @spec CONTRACT-020
(deftest shadowed-public-files-flags-only-duplicate-providers
  (let [public-root (th/create-temp-dir "kit-contract-approot-")
        dir1 (th/create-temp-dir "kit-contract-cp1-")
        dir2 (th/create-temp-dir "kit-contract-cp2-")]
    ;; files on disk under public-root
    (th/spit-file public-root "js/a.js" "a")
    (th/spit-file public-root "js/b.js" "b")
    ;; classpath: js/a.js is provided by BOTH dir1 and dir2; js/b.js only by dir1
    (th/spit-file dir1 "public/js/a.js" "a1")
    (th/spit-file dir1 "public/js/b.js" "b1")
    (th/spit-file dir2 "public/js/a.js" "a2")
    (th/with-classpath-dir dir2 (ClassLoader/getPlatformClassLoader)
      (th/with-classpath-dir dir1 (.getContextClassLoader (Thread/currentThread))
        (let [result (kit/shadowed-public-files (.getPath public-root))]
          (is (= 1 (count result)))
          (is (= "js/a.js" (:path (first result))))
          (is (= 2 (count (:providers (first result)))))
          (is (every? string? (:providers (first result)))))))))

;; @spec CONTRACT-020
(deftest shadowed-public-files-missing-directory-returns-empty
  (let [absent (io/file (th/create-temp-dir "kit-contract-missing-") "does-not-exist")]
    (is (= [] (kit/shadowed-public-files (.getPath absent))))))

;; ---- CONTRACT-021: unauthorized-js ----

(defn- build-public-root []
  (let [root (th/create-temp-dir "kit-contract-jsroot-")]
    (th/spit-file root "js/a.js" "a-content")
    (th/spit-file root "js/b.mjs" "b-content")
    (th/spit-file root "vendor/x.js" "x-content")
    (th/spit-file root "css/site.css" "css-content")
    root))

;; @spec CONTRACT-021
(deftest unauthorized-js-clean-manifest-is-empty-everywhere
  (let [root (build-public-root)
        x-sha (assets/sha256-hex (.getBytes "x-content" "UTF-8"))
        manifest {:app #{"js/a.js" "js/b.mjs"}
                  :third-party {"vendor/x.js" {:sha256 x-sha :source "https://example.com/x.js" :version "1.0.0"}}}
        result (kit/unauthorized-js (.getPath root) manifest)]
    (is (= [] (:unlisted result)))
    (is (= [] (:missing result)))
    (is (= [] (:bad-pins result)))
    (testing "css/site.css never appears -- it is not a script"
      (is (not (some #{"css/site.css"} (:unlisted result)))))))

;; @spec CONTRACT-021
(deftest unauthorized-js-flags-an-unlisted-script
  (let [root (build-public-root)
        x-sha (assets/sha256-hex (.getBytes "x-content" "UTF-8"))
        manifest {:app #{"js/a.js"} ;; js/b.mjs left off the manifest
                  :third-party {"vendor/x.js" {:sha256 x-sha :source "https://example.com/x.js" :version "1.0.0"}}}
        result (kit/unauthorized-js (.getPath root) manifest)]
    (is (= ["js/b.mjs"] (:unlisted result)))
    (is (= [] (:missing result)))
    (is (= [] (:bad-pins result)))))

;; @spec CONTRACT-021
(deftest unauthorized-js-flags-a-manifest-entry-with-no-file
  (let [root (build-public-root)
        x-sha (assets/sha256-hex (.getBytes "x-content" "UTF-8"))
        manifest {:app #{"js/a.js" "js/b.mjs" "js/ghost.js"} ;; no file on disk
                  :third-party {"vendor/x.js" {:sha256 x-sha :source "https://example.com/x.js" :version "1.0.0"}}}
        result (kit/unauthorized-js (.getPath root) manifest)]
    (is (= [] (:unlisted result)))
    (is (= ["js/ghost.js"] (:missing result)))
    (is (= [] (:bad-pins result)))))

;; @spec CONTRACT-021
(deftest unauthorized-js-flags-third-party-missing-provenance
  (let [root (build-public-root)
        manifest {:app #{"js/a.js" "js/b.mjs"}
                  :third-party {"vendor/x.js" {:sha256 "deadbeef" :source "https://example.com/x.js"}}} ;; no :version
        result (kit/unauthorized-js (.getPath root) manifest)]
    (is (= [] (:unlisted result)))
    (is (= [] (:missing result)))
    (is (= [{:path "vendor/x.js" :reason :missing-provenance}] (:bad-pins result)))))

;; @spec CONTRACT-021
(deftest unauthorized-js-flags-third-party-sha-mismatch
  (let [root (build-public-root)
        manifest {:app #{"js/a.js" "js/b.mjs"}
                  :third-party {"vendor/x.js" {:sha256 "0000000000000000000000000000000000000000000000000000000000000000"
                                               :source "https://example.com/x.js" :version "1.0.0"}}}
        result (kit/unauthorized-js (.getPath root) manifest)]
    (is (= [] (:unlisted result)))
    (is (= [] (:missing result)))
    (is (= [{:path "vendor/x.js" :reason :sha-mismatch}] (:bad-pins result)))))

;; @spec CONTRACT-021
(deftest unauthorized-js-accepts-manifest-as-edn-file-path
  (let [root (build-public-root)
        x-sha (assets/sha256-hex (.getBytes "x-content" "UTF-8"))
        manifest {:app #{"js/a.js" "js/b.mjs"}
                  :third-party {"vendor/x.js" {:sha256 x-sha :source "https://example.com/x.js" :version "1.0.0"}}}
        manifest-file (io/file (th/create-temp-dir "kit-contract-manifest-") "authorized-js.edn")
        _ (spit manifest-file (pr-str manifest))
        result (kit/unauthorized-js (.getPath root) (.getPath manifest-file))]
    (is (= [] (:unlisted result)))
    (is (= [] (:missing result)))
    (is (= [] (:bad-pins result)))))

;; ---- CONTRACT-022, CONTRACT-023: defcontract-tests ----

(defn- probe-vars [ns-sym form]
  (let [probe-ns (create-ns ns-sym)]
    (try
      (binding [*ns* probe-ns]
        (clojure.core/refer-clojure)
        (eval form))
      (into {}
            (map (fn [[sym v]] [sym (meta v)]))
            (ns-interns probe-ns))
      (finally
        (remove-ns ns-sym)))))

;; @spec CONTRACT-022
(deftest defcontract-tests-defines-two-tests-without-a-manifest
  (let [root (th/create-temp-dir "kit-contract-defcontract-")
        vars (probe-vars 'kit-contract-probe-1
                         `(datastar-kit.testing/defcontract-tests {:public-root ~(.getPath root)}))]
    (is (contains? vars 'kit-contract-no-kit-copies))
    (is (:test (get vars 'kit-contract-no-kit-copies)))
    (is (contains? vars 'kit-contract-no-shadowed-public-files))
    (is (:test (get vars 'kit-contract-no-shadowed-public-files)))
    (is (not (contains? vars 'kit-contract-only-authorized-js)))))

;; @spec CONTRACT-023
(deftest defcontract-tests-defines-a-third-test-with-a-manifest
  (let [root (th/create-temp-dir "kit-contract-defcontract-")
        x-sha (assets/sha256-hex (.getBytes "x-content" "UTF-8"))
        manifest {:app #{} :third-party {}}
        manifest-file (io/file (th/create-temp-dir "kit-contract-defcontract-manifest-") "authorized-js.edn")
        _ (spit manifest-file (pr-str manifest))
        _ x-sha
        vars (probe-vars 'kit-contract-probe-2
                         `(datastar-kit.testing/defcontract-tests
                            {:public-root ~(.getPath root) :js-manifest ~(.getPath manifest-file)}))]
    (is (contains? vars 'kit-contract-no-kit-copies))
    (is (:test (get vars 'kit-contract-no-kit-copies)))
    (is (contains? vars 'kit-contract-no-shadowed-public-files))
    (is (:test (get vars 'kit-contract-no-shadowed-public-files)))
    (is (contains? vars 'kit-contract-only-authorized-js))
    (is (:test (get vars 'kit-contract-only-authorized-js)))))
