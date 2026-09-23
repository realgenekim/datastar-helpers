(ns datastar-kit.picker-test
  "ds/picker — ONE owner for the chosen value (the server), a filter that is
   never a value, and the ordering fence (datastar-kit.picker)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datastar-kit.ds :as ds]
   [datastar-kit.picker :as picker]
   [hiccup2.core :as h]))

(defn- nodes
  "Every hiccup element vector in `tree` whose tag is `tag` (a keyword that may
   carry .classes, matched as a prefix of the tag's name)."
  [tree tag]
  (filter #(and (vector? %) (keyword? (first %))
                (let [t (name (first %)) want (name tag)]
                  (or (= t want) (str/starts-with? t (str want "."))
                      (str/starts-with? t (str want "#")))))
          (tree-seq sequential? seq tree)))

(defn- attrs [node] (let [a (second node)] (if (map? a) a {})))

(defn- classes
  "Classes from the tag's `.x.y` suffix plus its :class attribute."
  [node]
  (set (concat (rest (str/split (name (first node)) #"\."))
               (when-let [c (:class (attrs node))] (str/split c #"\s+")))))

(defn- with-class [tree tag cls]
  (filter #(contains? (classes %) cls) (nodes tree tag)))

(def ^:private items
  [{:value "Hosting & Internet" :label "Hosting & Internet"}
   {:value "Office Expenses" :label "Office Expenses"}
   {:value "Owner's Draw"}])

(defn- a-picker [& {:as overrides}]
  (ds/picker (merge {:filter-url "/r/1/reassign/scrub"
                     :pick-url "/r/1/reassign/pick"
                     :move-url "/r/1/reassign/move"
                     :submit {:label "reassign" :url "/r/1/reassign/submit"
                              :payload {:tx-id "t1"}}
                     :cancel {:label "cancel" :url "/r/1/reassign/cancel"}
                     :command-id "evt-7"
                     :placeholder "type to filter…"
                     :items items
                     :selected "Office Expenses"
                     :message nil}
                    overrides)))

(defn- filter-wrapper [p] (first (with-class p :div "ds-picker-filter")))
(defn- the-input [p] (first (nodes (filter-wrapper p) :input)))
(defn- submit-btn [p] (first (with-class p :button "ds-picker-submit")))
(defn- cancel-btn [p] (first (with-class p :button "ds-picker-cancel")))
(defn- item-btns [p] (with-class p :button "ds-picker-item"))

(defn- inside? [outer node]
  (some #(identical? % node) (tree-seq sequential? seq outer)))

;; @spec PICK-001
(deftest the-root-contains-every-click
  (let [p (a-picker)]
    (is (contains? (classes p) "ds-picker"))
    (is (= "event.stopPropagation()" (:onclick (attrs p))))))

;; @spec PICK-002
(deftest the-filter-wrapper-is-the-one-morph-ignored-island
  (let [p (a-picker)
        w (filter-wrapper p)]
    (is (= 1 (count (with-class p :div "ds-picker-filter"))))
    (is (= "" (:data-star-ignore-morph (attrs w))))
    (is (= "0" (:data-kit-seq (attrs w))) "the ordering fence starts at zero")
    (is (= "ds-picker-evt-7" (:id (attrs w)))
        "keyed by the session, so a new session never inherits an old frozen input")
    (is (= 1 (count (filter #(contains? (attrs %) :data-star-ignore-morph)
                            (filter vector? (tree-seq sequential? seq p)))))
        "exactly one element in the picker ignores morphs")
    (is (nil? (find (attrs (filter-wrapper (a-picker :command-id nil))) :id)))
    (testing "a reload continues the server's order: the counter starts at :seq"
      (is (= "5" (:data-kit-seq (attrs (filter-wrapper (a-picker :seq 5)))))))))

;; @spec PICK-003
(deftest the-filter-opens-empty-it-is-a-filter-never-a-value
  (let [p (a-picker)
        inputs (nodes p :input)
        a (attrs (the-input p))]
    (is (= 1 (count inputs)))
    (is (= "text" (:type a)))
    (is (= "ds-picker-input" (:class a)))
    (is (= "type to filter…" (:placeholder a)))
    (is (nil? (find a :value)) "no value attribute: a prefill looks like an answer and is not one")
    (is (true? (:autofocus a)))
    (is (nil? (find (attrs (the-input (a-picker :autofocus? false))) :autofocus)))
    (is (nil? (find (attrs (the-input (a-picker :placeholder nil))) :placeholder)))))

;; @spec PICK-004
(deftest the-list-renders-outside-the-island-with-the-server-selection-marked
  (let [p (a-picker)
        w (filter-wrapper p)
        ul (first (with-class p :ul "ds-picker-items"))
        btns (item-btns p)]
    (is (some? ul))
    (is (not (inside? w ul)) "a push must be able to re-render the list")
    (is (= ["Hosting & Internet" "Office Expenses" "Owner's Draw"] (mapv #(nth % 2) btns))
        "label, or the value when there is no label")
    (is (= 3 (count (nodes ul :li))))
    (let [sel (filter #(contains? (classes %) "ds-picker-selected") btns)]
      (is (= 1 (count sel)))
      (is (= "Office Expenses" (nth (first sel) 2)))
      (is (= "true" (:aria-selected (attrs (first sel))))))
    (testing "nil selection marks nothing"
      (is (empty? (filter #(contains? (classes %) "ds-picker-selected")
                          (item-btns (a-picker :selected nil))))))))

;; @spec PICK-005
(deftest the-actions-and-the-message-render-beside-each-other-outside-the-island
  (let [p (a-picker :message "no change")
        w (filter-wrapper p)
        actions (first (with-class p :div "ds-picker-actions"))]
    (is (not (inside? w actions)))
    (is (inside? actions (submit-btn p)))
    (is (inside? actions (cancel-btn p)))
    (is (= "reassign" (last (submit-btn p))))
    (is (= "cancel" (last (cancel-btn p))))
    (let [msg (first (with-class actions :span "ds-picker-message"))]
      (is (= "no change" (last msg)) "feedback sits beside the buttons that caused it"))
    (testing "no message, no span; no cancel, no cancel button"
      (is (empty? (with-class (a-picker :message nil) :span "ds-picker-message")))
      (is (empty? (with-class (a-picker :message "  ") :span "ds-picker-message")))
      (is (nil? (cancel-btn (a-picker :cancel nil)))))))

;; @spec PICK-006
(deftest submit-is-disabled-exactly-when-nothing-is-selected
  (is (nil? (find (attrs (submit-btn (a-picker))) :disabled)))
  (is (true? (:disabled (attrs (submit-btn (a-picker :selected nil)))))))

;; @spec PICK-010
(deftest every-handler-is-plain-and-contained
  (let [p (a-picker)
        els (filter vector? (tree-seq sequential? seq p))]
    (doseq [el els
            [k v] (attrs el)
            :when (#{:onclick :oninput :onkeydown} k)]
      (is (str/includes? v "event.stopPropagation()") (str k " on " (first el))))
    (doseq [b (nodes p :button)]
      (is (= "button" (:type (attrs b))) (str b)))
    (is (not (str/includes? (str (h/html p)) "data-star-on")))
    (is (not (str/includes? (str (h/html p)) "data-star-bind")))))

;; @spec PICK-011, PICK-016
(deftest the-filter-posts-its-own-text-with-the-next-seq
  (let [js (:oninput (attrs (the-input (a-picker))))]
    (is (str/includes? js "postJSON('/r/1/reassign/scrub',{"))
    (is (str/includes? js "'q':this.value"))
    (is (str/includes? js "'seq':"))
    (is (str/includes? js "'command-id':'evt-7'"))
    (testing "the seq lives on the island, read, incremented and written back"
      (is (str/includes? js "this.closest('.ds-picker').querySelector('.ds-picker-filter')"))
      (is (str/includes? js "dataset.kitSeq")))))

;; @spec PICK-012
(deftest a-click-on-an-item-picks-its-literal-value
  (let [[hosting _ owners] (item-btns (a-picker))
        js (:onclick (attrs hosting))]
    (is (str/includes? js "postJSON('/r/1/reassign/pick',{"))
    (is (str/includes? js "'value':'Hosting & Internet'"))
    (is (str/includes? js "'seq':"))
    (is (str/includes? js "'command-id':'evt-7'"))
    (is (str/includes? (:onclick (attrs owners)) "'value':'Owner\\'s Draw'")
        "a quote in a value cannot break out of the JS string literal")))

;; @spec PICK-013
(deftest the-keyboard-moves-submits-and-cancels-through-the-current-buttons
  (let [js (:onkeydown (attrs (the-input (a-picker))))]
    (is (str/starts-with? js "event.stopPropagation();"))
    (is (str/includes? js "event.key==='ArrowDown'"))
    (is (str/includes? js "event.key==='ArrowUp'"))
    (is (str/includes? js "postJSON('/r/1/reassign/move',{'dir':'down'"))
    (is (str/includes? js "postJSON('/r/1/reassign/move',{'dir':'up'"))
    (is (str/includes? js "event.key==='Enter'"))
    (is (str/includes? js ".ds-picker-submit"))
    (is (str/includes? js "!b.disabled") "Enter submits only when something is selected")
    (is (str/includes? js "event.key==='Escape'"))
    (is (str/includes? js ".ds-picker-cancel"))
    (is (= 4 (count (re-seq #"event\.preventDefault\(\)" js))))))

;; @spec PICK-014
(deftest submit-sends-identity-and-order-never-a-value
  (let [js (:onclick (attrs (submit-btn (a-picker))))]
    (is (str/includes? js "postJSON('/r/1/reassign/submit',{"))
    (is (str/includes? js "'tx-id':'t1'"))
    (is (str/includes? js "'command-id':'evt-7'"))
    (is (str/includes? js "'seq':"))
    (doseq [k ["'q'" "'value'" "'text'" "'category'" ".value"]]
      (is (not (str/includes? js k)) (str "submit must not carry " k)))
    (let [body-keys (set (map second (re-seq #"'([a-z-]+)':" js)))]
      (is (= #{"tx-id" "command-id" "seq"} body-keys)))))

;; @spec PICK-015
(deftest cancel-posts-nothing-but-the-close
  (is (str/includes? (:onclick (attrs (cancel-btn (a-picker))))
                     "postJSON('/r/1/reassign/cancel',{'command-id':'evt-7'})"))
  (is (str/includes? (:onclick (attrs (cancel-btn (a-picker :command-id nil))))
                     "postJSON('/r/1/reassign/cancel',{})")))

;; @spec PICK-017
(deftest the-picker-refuses-what-it-could-not-render
  (is (thrown? AssertionError (a-picker :filter-url nil)))
  (is (thrown? AssertionError (a-picker :pick-url "")))
  (is (thrown? AssertionError (a-picker :move-url nil)))
  (is (thrown? AssertionError (a-picker :submit {:label "go"})))
  (is (thrown? AssertionError (a-picker :cancel {:label "cancel"}))))

;; @spec PICK-020
(deftest accept-only-a-later-gesture
  (is (true? (picker/picker-accept? nil 1)))
  (is (true? (picker/picker-accept? 0 1)))
  (is (true? (picker/picker-accept? 3 7)) "a gap is fine: an earlier gesture may still arrive and be refused")
  (is (false? (picker/picker-accept? 3 3)) "a replay")
  (is (false? (picker/picker-accept? 4 3)) "a late response cannot undo a later pick")
  (is (false? (picker/picker-accept? 3 nil)))
  (is (false? (picker/picker-accept? 3 "4")) "parse first")
  (is (false? (picker/picker-accept? nil nil))))

;; @spec PICK-021
(deftest submit-only-when-caught-up
  (is (true? (picker/picker-caught-up? nil 1)))
  (is (true? (picker/picker-caught-up? 4 5)))
  (is (false? (picker/picker-caught-up? 3 5)) "gesture 4 is still in flight")
  (is (false? (picker/picker-caught-up? 5 5)))
  (is (false? (picker/picker-caught-up? 5 4)))
  (is (false? (picker/picker-caught-up? 4 nil))))

;; @spec PICK-022
(deftest parse-seq-takes-numbers-and-digit-strings-only
  (is (= 7 (picker/parse-seq {"seq" 7})))
  (is (= 7 (picker/parse-seq {"seq" 7.0})))
  (is (= 12 (picker/parse-seq {"seq" "12"})))
  (is (nil? (picker/parse-seq {"seq" "x"})))
  (is (nil? (picker/parse-seq {"seq" 1.5})))
  (is (nil? (picker/parse-seq {})))
  (is (nil? (picker/parse-seq nil))))

;; @spec PICK-023
(deftest step-moves-through-values-and-stops-at-the-ends
  (let [vs ["a" "b" "c"]]
    (is (= "b" (picker/step vs "a" :down)))
    (is (= "c" (picker/step vs "c" :down)))
    (is (= "a" (picker/step vs "b" :up)))
    (is (= "a" (picker/step vs "a" :up)))
    (is (= "a" (picker/step vs "zzz" :down)) "not in the list starts at the top")
    (is (= "a" (picker/step vs nil :up)))
    (is (nil? (picker/step [] "a" :down)))))
