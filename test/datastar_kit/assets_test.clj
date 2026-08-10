(ns datastar-kit.assets-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datastar-kit.assets :as assets]))

(deftest script-tags-preserve-required-order
  (testing "the Basic-Auth bootstrap precedes the Datastar module"
    (let [tags (assets/script-tags
                 {:asset-url #(str % "?v=test")
                  :basic-auth? true
                  :keyboard-chords? true
                  :kit-runtime? true})]
      (is (= [:script {:src "/js/datastar-auth-fix.js?v=test"}] (first tags)))
      (is (= :script (first (second tags))))
      (is (string? (second (second tags))))
      (is (= [:script {:type "module" :src "/vendor/datastar-aliased.js?v=test"}] (nth tags 2)))
      (is (= [:script {:src "/js/datastar-kit.js?v=test"}] (nth tags 3))))))

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
