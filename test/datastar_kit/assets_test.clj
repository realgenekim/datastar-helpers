(ns datastar-kit.assets-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [datastar-kit.assets :as assets]))

(deftest script-tags-preserve-required-order
  (testing "the Basic-Auth bootstrap precedes the Datastar module"
    (is (= [[:script {:src "/js/datastar-auth-fix.js?v=test"}]
            [:script {:src "/js/keyboard-chords.js?v=test"}]
            [:script {:type "module" :src "/vendor/datastar-aliased.js?v=test"}]
            [:script {:src "/js/datastar-kit.js?v=test"}]]
           (assets/script-tags
             {:asset-url #(str % "?v=test")
              :basic-auth? true
              :keyboard-chords? true
              :kit-runtime? true})))))

(deftest script-tags-have-small-safe-default
  (is (= [[:script {:type "module" :src "/vendor/datastar-aliased.js"}]]
         (assets/script-tags {}))))
