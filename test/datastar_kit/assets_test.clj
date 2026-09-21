(ns datastar-kit.assets-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datastar-kit.assets :as assets])
  (:import
   (java.security MessageDigest)))

;; middleware-installed? is process-global (defonce atom), so each test must
;; start from a known state -- otherwise a test that installs the middleware
;; leaks that state into every test that runs after it.
(use-fixtures :each
  (fn [f]
    (reset! @#'assets/middleware-installed? false)
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
      (is (re-find #"BASIC-AUTH-HISTORY-001" (second (first tags))))
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
    (is (re-find #"standalone Shift keydown" source))))

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
