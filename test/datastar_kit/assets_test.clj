(ns datastar-kit.assets-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datastar-kit.assets :as assets]
   [datastar-kit.test-helpers :as th])
  (:import
   (java.security MessageDigest)))

;; middleware-installed? and audit-done? are process-global (defonce atoms), so
;; each test must start from a known state -- otherwise a test that installs
;; the middleware or runs the copy audit leaks that state into every test that
;; runs after it.
(use-fixtures :each
  (fn [f]
    (reset! @#'assets/middleware-installed? false)
    (reset! @#'assets/audit-done? false)
    (f)))

(defn- sha256-hex12 [^bytes bs]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (subs (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest)) 0 12)))

(defn- resource-bytes [path]
  (.getBytes (slurp (io/resource path)) "UTF-8"))

(defn- big-surrogate-straddling-string
  "200,000 chars: an ASCII run long enough that a naive 16000-char split
   would land inside a surrogate pair, the pair itself, then a mix of
   multi-byte chars (e/->) to pad out to the target length."
  []
  (let [prefix (apply str (repeat 15999 \a))
        emoji (str (char 0xD83D) (char 0xDE00)) ;; U+1F600 GRINNING FACE, a surrogate pair
        remaining (- 200000 (count prefix) (count emoji))
        filler (apply str (take remaining (cycle "é→b")))]
    (str prefix emoji filler)))

;; @spec BASIC-AUTH-LOAD-003
(deftest script-tags-preserve-required-order
  (testing "the Basic-Auth bootstrap precedes the Datastar module"
    (let [tags (assets/script-tags
                {:asset-url #(str % "?v=test")
                 :basic-auth? true
                 :keyboard-chords? true
                 :kit-runtime? true})]
      (is (= :script (ffirst tags)))
      (is (string? (second (first tags))))
      (is (re-find #"__datastarAuthFixVersion" (second (first tags))))
      (is (re-find #"toRelativeHistoryUrl" (second (first tags))))
      (is (= :script (first (second tags))))
      (is (string? (second (second tags))))
      (is (= [:script {:type "module" :src "/vendor/datastar-aliased.js?v=test"}] (nth tags 2)))
      (is (= [:script {:src "/js/datastar-kit.js?v=test"}] (nth tags 3))))))

;; @spec BASIC-AUTH-LOAD-002
(deftest basic-auth-script-is-self-contained
  (let [[tag source] (assets/basic-auth-script)]
    (is (= :script tag))
    (is (re-find #"History\.prototype" source))
    (is (re-find #"toRelativeHistoryUrl" source))
    (is (not (re-find #"history-patch\.js" source)))))

(deftest keyboard-chords-script-is-self-contained
  (let [[tag source] (assets/keyboard-chords-script)]
    (is (= :script tag))
    (is (re-find #"DatastarKeyboardChords" source))
    (is (re-find #"modifierKeys" source))))

(deftest script-tags-have-small-safe-default
  (is (= [[:script {:type "module" :src "/vendor/datastar-aliased.js"}]]
         (assets/script-tags {}))))

(deftest vendored-datastar-does-not-open-sse-in-an-initially-hidden-tab
  (let [bundle (slurp (io/resource "public/vendor/datastar-aliased.js"))]
    (is (re-find #"openWhenHidden" bundle))
    (is (re-find #"h\|\|!document\.hidden" bundle))))

;; @spec KIT-ASSETS-002
(deftest chunk-text-handles-large-strings-and-surrogates
  (testing "no chunk splits a surrogate pair, every chunk is bounded, concatenation round-trips"
    (let [big (big-surrogate-straddling-string)]
      (is (= 200000 (count big)))
      (let [chunks (assets/chunk-text big)]
        (is (every? #(<= (count %) 16001) chunks))
        (is (every? #(not (Character/isHighSurrogate (.charAt ^String % (dec (count %)))))
                    chunks))
        (is (= big (apply str chunks))))))
  (testing "the compile path: chunk-text splices into a form the compiler can build even
            when a single string literal could not"
    (let [big (apply str (repeat 70123 \x))]
      (is (= big (eval `(str ~@(assets/chunk-text big))))))))

;; @spec KIT-ASSETS-002
(deftest chunk-text-empty-string
  (is (= [""] (assets/chunk-text ""))))

;; @spec KIT-ASSETS-001
(deftest embedded-asset-bytes-match-resource-exactly
  (doseq [[asset-name resource-path] [["datastar-aliased.js" "public/vendor/datastar-aliased.js"]
                                      ["datastar-kit.js" "public/js/datastar-kit.js"]]]
    (testing asset-name
      (let [handler (assets/wrap-kit-assets (fn [_] {:status 404}))
            expected (resource-bytes resource-path)
            response (handler {:request-method :get :uri (assets/asset-path asset-name)})]
        (is (= (vec expected) (vec (.readAllBytes (:body response)))))))))

;; @spec KIT-ASSETS-003
(deftest asset-path-shape-and-hash
  (doseq [[asset-name resource-path pattern]
          [["datastar-aliased.js" "public/vendor/datastar-aliased.js"
            #"^/_kit/[0-9a-f]{12}/datastar-aliased\.js$"]
           ["datastar-kit.js" "public/js/datastar-kit.js"
            #"^/_kit/[0-9a-f]{12}/datastar-kit\.js$"]]]
    (testing asset-name
      (let [path (assets/asset-path asset-name)
            expected-hash (sha256-hex12 (resource-bytes resource-path))]
        (is (re-matches pattern path))
        (is (str/includes? path expected-hash))))))

;; @spec KIT-ASSETS-004
(deftest asset-path-unknown-asset-throws
  (try
    (assets/asset-path "nope.js")
    (is false "expected asset-path to throw")
    (catch clojure.lang.ExceptionInfo e
      (is (= "nope.js" (:asset (ex-data e)))))))

;; @spec KIT-ASSETS-010
(deftest wrap-kit-assets-serves-exact-hash
  (let [handler (assets/wrap-kit-assets (fn [_] {:status 404}))
        path (assets/asset-path "datastar-kit.js")
        expected (resource-bytes "public/js/datastar-kit.js")
        response (handler {:request-method :get :uri path})]
    (is (= 200 (:status response)))
    (is (= "text/javascript; charset=utf-8" (get-in response [:headers "Content-Type"])))
    (is (= "public, max-age=31536000, immutable" (get-in response [:headers "Cache-Control"])))
    (is (= (str (count expected)) (get-in response [:headers "Content-Length"])))
    (is (= (vec expected) (vec (.readAllBytes (:body response)))))))

;; @spec KIT-ASSETS-011
(deftest wrap-kit-assets-serves-mismatched-hash-as-no-cache
  (let [handler (assets/wrap-kit-assets (fn [_] {:status 404}))
        expected (resource-bytes "public/vendor/datastar-aliased.js")
        response (handler {:request-method :get :uri "/_kit/000000000000/datastar-aliased.js"})]
    (is (= 200 (:status response)))
    (is (= "no-cache" (get-in response [:headers "Cache-Control"])))
    (is (= (vec expected) (vec (.readAllBytes (:body response)))))))

;; @spec KIT-ASSETS-012
(deftest wrap-kit-assets-head-mirrors-get-headers-with-no-body
  (let [handler (assets/wrap-kit-assets (fn [_] {:status 404}))
        exact-path (assets/asset-path "datastar-aliased.js")
        mismatched-path "/_kit/000000000000/datastar-aliased.js"]
    (doseq [path [exact-path mismatched-path]]
      (let [get-resp (handler {:request-method :get :uri path})
            head-resp (handler {:request-method :head :uri path})]
        (is (= (:status get-resp) (:status head-resp)))
        (is (= (:headers get-resp) (:headers head-resp)))
        (is (nil? (:body head-resp)))))))

;; @spec KIT-ASSETS-013
(deftest wrap-kit-assets-passes-through-non-kit-requests
  (let [sentinel {:status 999 :headers {} :body "sentinel"}
        handler (assets/wrap-kit-assets (fn
                                          ([_] sentinel)
                                          ([_ respond _raise] (respond sentinel))))
        known-path (assets/asset-path "datastar-aliased.js")]
    (testing "non-kit path"
      (is (= sentinel (handler {:request-method :get :uri "/not-kit"}))))
    (testing "unknown kit asset name"
      (is (= sentinel (handler {:request-method :get :uri "/_kit/abcdef012345/unknown.js"}))))
    (testing "missing name segment"
      (is (= sentinel (handler {:request-method :get :uri "/_kit/abcdef012345"}))))
    (testing "wrong method"
      (is (= sentinel (handler {:request-method :post :uri known-path}))))
    (testing "3-arity async form delegates for a non-kit request"
      (let [responded (atom nil)]
        (handler {:request-method :get :uri "/not-kit"}
                 (fn [resp] (reset! responded resp))
                 (fn [_] (throw (ex-info "should not raise" {}))))
        (is (= sentinel @responded))))
    (testing "3-arity async form calls respond with the 200 response for a kit URL"
      (let [responded (atom nil)]
        (handler {:request-method :get :uri known-path}
                 (fn [resp] (reset! responded resp))
                 (fn [_] (throw (ex-info "should not raise" {}))))
        (is (= 200 (:status @responded)))))))

;; @spec KIT-ASSETS-020, KIT-ASSETS-021, KIT-ASSETS-022
(deftest script-tags-switch-to-kit-urls-once-middleware-installed
  (testing "before install: app-served defaults, asset-url applied"
    (let [tags (assets/script-tags {:asset-url #(str % "?v=t") :kit-runtime? true})]
      (is (= [:script {:type "module" :src "/vendor/datastar-aliased.js?v=t"}] (first tags)))
      (is (= [:script {:src "/js/datastar-kit.js?v=t"}] (second tags)))))
  (testing "before install: :datastar-path overrides, still through asset-url"
    (let [tags (assets/script-tags {:asset-url #(str % "?v=t") :datastar-path "/x.js"})]
      (is (= [:script {:type "module" :src "/x.js?v=t"}] (first tags)))))
  (assets/wrap-kit-assets identity)
  (testing "after install: kit URLs from asset-path, no ?v=t"
    (let [tags (assets/script-tags {:asset-url #(str % "?v=t") :kit-runtime? true})]
      (is (= [:script {:type "module" :src (assets/asset-path "datastar-aliased.js")}] (first tags)))
      (is (= [:script {:src (assets/asset-path "datastar-kit.js")}] (second tags)))))
  (testing "after install: :datastar-path still overrides, still through asset-url"
    (let [tags (assets/script-tags {:asset-url #(str % "?v=t") :datastar-path "/x.js"})]
      (is (= [:script {:type "module" :src "/x.js?v=t"}] (first tags))))))

;; ---- KIT-ASSETS-005: inline comment stripping ----

;; @spec KIT-ASSETS-006
(deftest the-kit-embeds-its-own-file-not-a-shadowing-copy
  (let [u #(java.net.URL. %)]
    (testing "directory checkout: an app copy earlier on the classpath is skipped"
      (let [app (u "file:/work/my-app/resources/public/vendor/datastar-aliased.js")
            kit (u "file:/home/me/.gitlibs/libs/genek/datastar-helpers/abc123/resources/public/vendor/datastar-aliased.js")]
        (is (= kit (assets/own-resource-url
                    (u "file:/home/me/.gitlibs/libs/genek/datastar-helpers/abc123/src/datastar_kit/assets.clj")
                    [app kit])))))
    (testing "a sibling checkout with a similar name is not mistaken for the kit"
      (let [other (u "file:/src/datastar-helpers-fork/resources/public/js/datastar-kit.js")
            kit (u "file:/src/datastar-helpers/resources/public/js/datastar-kit.js")]
        (is (= kit (assets/own-resource-url
                    (u "file:/src/datastar-helpers/src/datastar_kit/assets.clj")
                    [other kit])))))
    (testing "jar: the provider inside the same jar is chosen"
      (let [app (u "file:/work/my-app/resources/public/js/datastar-kit.js")
            kit (u "jar:file:/m2/datastar-helpers-1.0.jar!/public/js/datastar-kit.js")]
        (is (= kit (assets/own-resource-url
                    (u "jar:file:/m2/datastar-helpers-1.0.jar!/datastar_kit/assets.clj")
                    [app kit])))))
    (testing "no provider under the kit's root -> nil (the macro turns that into a compile error)"
      (is (nil? (assets/own-resource-url
                 (u "file:/src/datastar-helpers/src/datastar_kit/assets.clj")
                 [(u "file:/work/my-app/resources/public/js/datastar-kit.js")]))))))

;; @spec KIT-ASSETS-006
(deftest embedded-hashes-match-the-kits-own-files-on-disk
  ;; The end-to-end form of the rule above: whatever else is on the classpath, the URL hash is
  ;; the hash of the file that ships in this repository.
  (doseq [[asset-name file] {"datastar-aliased.js" "resources/public/vendor/datastar-aliased.js"
                             "datastar-kit.js" "resources/public/js/datastar-kit.js"}]
    (let [on-disk (subs (assets/sha256-hex (java.nio.file.Files/readAllBytes (.toPath (io/file file)))) 0 12)]
      (is (= (str "/_kit/" on-disk "/" asset-name) (assets/asset-path asset-name))))))

;; @spec KIT-ASSETS-005
(deftest strip-comment-lines-drops-full-line-comments-only
  (testing "drops a full-line comment, including an indented one"
    (is (= "a\nc" (assets/strip-comment-lines "a\n// b\nc"))))
  (testing "drops an indented full-line comment"
    (is (= "a\nc" (assets/strip-comment-lines "a\n    // b\nc"))))
  (testing "keeps a code line that contains // after code"
    (let [line "var u = \"http://x\"; // note"]
      (is (= line (assets/strip-comment-lines line)))))
  (testing "mixed: comment lines vanish, code lines (even with trailing //) survive"
    (is (= "var u = \"http://x\"; // note\ncode();"
           (assets/strip-comment-lines
            "// header comment\nvar u = \"http://x\"; // note\n  // another full-line comment\ncode();")))))

;; @spec KIT-ASSETS-005
(deftest inline-scripts-have-no-comment-only-lines
  (testing "basic-auth-script"
    (let [[_ source] (assets/basic-auth-script)]
      (is (not-any? #(str/starts-with? (str/trim %) "//") (str/split-lines source)))
      (is (re-find #"__datastarAuthFixVersion" source))
      (is (re-find #"toRelativeHistoryUrl" source))))
  (testing "keyboard-chords-script"
    (let [[_ source] (assets/keyboard-chords-script)]
      (is (not-any? #(str/starts-with? (str/trim %) "//") (str/split-lines source)))
      (is (re-find #"DatastarKeyboardChords" source)))))

;; @spec KIT-ASSETS-005
(deftest inline-source-files-contain-no-backtick
  (testing "the assumption that makes whole-line comment stripping safe"
    (doseq [path ["public/js/datastar-auth-fix.js" "public/js/keyboard-chords.js"]]
      (testing path
        (is (not (str/includes? (slurp (io/resource path)) "`")))))))

;; ---- CONTRACT-001..003: copy audit ----

(defn- finding-for [findings path]
  (first (filter #(= path (:path %)) findings)))

;; @spec CONTRACT-001
(deftest copy-audit-shadowed-when-more-than-one-provider
  (testing "development shape: kit resource + app copy with different bytes -> shadowed"
    (let [tmp (th/create-temp-dir "kit-contract-shadow-diff-")]
      (th/spit-file tmp "public/vendor/datastar-aliased.js" "totally different bytes")
      (th/with-classpath-dir tmp (.getContextClassLoader (Thread/currentThread))
        (let [findings (assets/copy-audit)
              finding (finding-for findings "public/vendor/datastar-aliased.js")]
          (is (= 1 (count findings)))
          (is (= :shadowed (:problem finding)))
          (is (= 2 (count (:providers finding))))))))
  (testing "development shape: identical bytes -> still shadowed (one of them silently wins)"
    (let [tmp (th/create-temp-dir "kit-contract-shadow-same-")
          real-bytes (slurp (io/resource "public/vendor/datastar-aliased.js"))]
      (th/spit-file tmp "public/vendor/datastar-aliased.js" real-bytes)
      (th/with-classpath-dir tmp (.getContextClassLoader (Thread/currentThread))
        (let [findings (assets/copy-audit)
              finding (finding-for findings "public/vendor/datastar-aliased.js")]
          (is (= :shadowed (:problem finding)))
          (is (= 2 (count (:providers finding)))))))))

;; @spec CONTRACT-002
(deftest copy-audit-stale-copy-thin-jar-shape
  (let [tmp (th/create-temp-dir "kit-contract-stale-")]
    (th/spit-file tmp "public/vendor/datastar-aliased.js" "stale bytes")
    (th/with-classpath-dir tmp (ClassLoader/getPlatformClassLoader)
      (let [findings (assets/copy-audit)
            finding (finding-for findings "public/vendor/datastar-aliased.js")]
        (is (= 1 (count findings)))
        (is (= :stale-copy (:problem finding)))
        (is (not= (:kit-sha finding) (:sha (first (:providers finding)))))
        (is (str/includes? (:url (first (:providers finding))) (str tmp)))))))

;; @spec CONTRACT-003
(deftest copy-audit-no-finding-when-identical-or-absent
  (testing "thin-jar shape, identical copy -> no finding"
    (let [tmp (th/create-temp-dir "kit-contract-identical-")]
      (io/make-parents (io/file tmp "public/vendor/datastar-aliased.js"))
      (with-open [in (io/input-stream (io/resource "public/vendor/datastar-aliased.js"))]
        (io/copy in (io/file tmp "public/vendor/datastar-aliased.js")))
      (th/with-classpath-dir tmp (ClassLoader/getPlatformClassLoader)
        (is (empty? (assets/copy-audit))))))
  (testing "thin-jar shape, empty temp dir (no app copies) -> no findings"
    (let [tmp (th/create-temp-dir "kit-contract-empty-")]
      (th/with-classpath-dir tmp (ClassLoader/getPlatformClassLoader)
        (is (empty? (assets/copy-audit))))))
  (testing "the repo's own normal classpath -> empty"
    (is (empty? (assets/copy-audit)))))

;; ---- CONTRACT-010..012: runtime warning ----

;; @spec CONTRACT-010, CONTRACT-011
(deftest script-tags-warns-once-on-stderr-for-a-stale-copy
  (let [tmp (th/create-temp-dir "kit-contract-warn-")]
    (th/spit-file tmp "public/vendor/datastar-aliased.js" "stale bytes")
    (th/with-classpath-dir tmp (ClassLoader/getPlatformClassLoader)
      (let [kit-sha (assets/sha256-hex
                     (.getBytes (slurp (io/resource "public/vendor/datastar-aliased.js")) "UTF-8"))
            err1 (java.io.StringWriter.)
            _ (binding [*err* err1] (assets/script-tags {}))
            output1 (str err1)
            err2 (java.io.StringWriter.)
            _ (binding [*err* err2] (assets/script-tags {}))
            output2 (str err2)]
        (testing "first call: prints the finding"
          (is (str/includes? output1 "public/vendor/datastar-aliased.js"))
          (is (str/includes? output1 ":stale-copy"))
          (is (str/includes? output1 kit-sha)))
        (testing "second call: prints nothing"
          (is (= "" output2)))))))

;; @spec CONTRACT-012
(deftest script-tags-returns-same-tags-when-audit-throws
  (let [opts {:asset-url #(str % "?v=t") :basic-auth? true :keyboard-chords? true :kit-runtime? true}
        expected (assets/script-tags opts)]
    (reset! @#'assets/audit-done? false)
    (with-redefs [assets/copy-audit (fn [] (throw (ex-info "boom" {})))]
      (is (= expected (assets/script-tags opts))))))
