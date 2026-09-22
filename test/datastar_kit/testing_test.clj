(ns datastar-kit.testing-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
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

;; ---- EDIT-030..EDIT-032: command replay ----

(defn- note-store
  "A tiny stand-in for a server that commits ONE note per command id and
   appends one durable line per commit."
  []
  {:state (atom {}) :log (atom []) :ids (atom 0)})

;; @spec EDIT-030
(deftest command-replay-passes-a-handler-that-commits-a-command-once
  (let [{:keys [state log]} (note-store)
        post! (fn []
                (when-not (contains? @state "cmd-1")
                  (swap! state assoc "cmd-1" {:text "Portland"})
                  (swap! log conj "committed cmd-1")))
        result (kit/command-replay {:post! post!
                                    :state #(deref state)
                                    :effects #(count @log)})]
    (is (true? (:replay-safe? result)))
    (is (= {} (:state-diff result)))
    (is (nil? (:effects-diff result)))))

;; @spec EDIT-030, EDIT-031
(deftest command-replay-catches-a-duplicate-durable-write-state-alone-would-miss
  (let [{:keys [state log]} (note-store)
        post! (fn []
                (swap! state assoc "cmd-1" {:text "Portland"})   ; same value twice
                (swap! log conj "committed cmd-1"))              ; but a SECOND line
        result (kit/command-replay {:post! post!
                                    :state #(deref state)
                                    :effects #(count @log)})]
    (testing "the projection is identical — only the effects count tells the truth"
      (is (= {} (:state-diff result))))
    (is (false? (:replay-safe? result)))
    (is (= {:after-first 1 :after-second 2} (:effects-diff result)))
    (is (str/includes? (kit/replay-failure-message result) ":effects"))))

;; @spec EDIT-030
(deftest command-replay-names-the-key-a-new-id-changes
  (let [{:keys [state ids]} (note-store)
        post! (fn [] (swap! state assoc :last-id (swap! ids inc)))
        result (kit/command-replay {:post! post! :state #(deref state)})]
    (is (false? (:replay-safe? result)))
    (is (= [:last-id] (keys (:state-diff result))))
    (is (= {:after-first 1 :after-second 2} (:last-id (:state-diff result))))
    (testing "the message names the key, not the whole state"
      (is (str/includes? (kit/replay-failure-message result) ":last-id"))
      (is (str/includes? (kit/replay-failure-message result) ":state")))))

;; @spec EDIT-030
(deftest command-replay-without-an-effects-fn-checks-state-only
  (let [{:keys [state]} (note-store)
        result (kit/command-replay {:post! #(swap! state assoc :k 1) :state #(deref state)})]
    (is (true? (:replay-safe? result)))
    (is (nil? (:effects-diff result)))))

;; @spec EDIT-032
(deftest assert-command-replay-passes-a-replay-safe-command
  (let [state (atom {})]
    (kit/assert-command-replay {:post! #(swap! state assoc :k 1) :state #(deref state)})))

;; @spec EDIT-032
(deftest assert-command-replay-fails-naming-what-the-replay-changed
  (let [reports (atom [])
        log (atom [])
        state (atom {})]
    (binding [clojure.test/report #(swap! reports conj %)]
      (kit/assert-command-replay {:post! (fn [] (swap! state assoc :k 1) (swap! log conj :line))
                                  :state #(deref state)
                                  :effects #(count @log)}))
    (is (= 1 (count @reports)))
    (is (= :fail (:type (first @reports))))
    (is (str/includes? (:message (first @reports)) ":effects"))))
