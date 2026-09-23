(ns datastar-kit.picker
  "The server half of `datastar-kit.ds/picker`: pure functions over the
   picker's ordering fence and its selection. No state, no IO.

   Every picker gesture posts to its own endpoint and forgets, so two posts can
   reach the server in either order. The browser stamps each one with `seq`, a
   counter it keeps on the picker's morph-ignored filter wrapper
   (`data-kit-seq`). The server keeps the last seq it APPLIED and asks:

     filter / pick / move  -> `picker-accept?`    (later than the last one?)
     submit                -> `picker-caught-up?` (exactly the next one?)

   A late filter response therefore cannot undo a later pick, and a submit
   cannot overtake a pick still in flight and commit the selection the user
   already moved past. Intent: docs/intent/picker/.")

;; @spec PICK-022
(defn parse-seq
  "The integer a JSON request body carried under \"seq\" -- a number, or a
   string of digits -- and nil for anything else, including a fraction."
  [body]
  (let [v (get body "seq")]
    (cond
      (integer? v) (long v)
      (and (number? v) (== v (Math/floor (double v)))) (long v)
      (and (string? v) (re-matches #"\d{1,15}" v)) (Long/parseLong v)
      :else nil)))

;; @spec PICK-020
(defn picker-accept?
  "Apply a filter, pick or move gesture? Only when it is LATER than the last
   gesture this picker applied. A gap is fine -- an earlier gesture may still
   arrive, and it will be refused then. `seq` must already be an integer
   (`parse-seq`)."
  [last-seq seq]
  (boolean (and (integer? seq)
                (or (nil? last-seq)
                    (and (integer? last-seq) (< last-seq seq))))))

;; @spec PICK-021
(defn picker-caught-up?
  "Apply a submit? Only when it is EXACTLY the next gesture after the last one
   applied. A gap means an earlier gesture (a pick, a move) is still in flight,
   and committing now would commit the selection the user has already moved
   past -- so the caller refuses and says so beside the buttons."
  [last-seq seq]
  (boolean (and (integer? seq)
                (or (nil? last-seq) (integer? last-seq))
                (= seq (inc (or last-seq 0))))))

;; @spec PICK-023
(defn step
  "The value one place before (:up) or after (:down) `selected` in `values`,
   stopping at the ends. Not in `values` (or nil) -> the first value; empty
   `values` -> nil."
  [values selected dir]
  (let [vs (vec values)
        i (.indexOf ^java.util.List vs selected)]
    (cond
      (empty? vs) nil
      (neg? i) (first vs)
      (= :up dir) (nth vs (max 0 (dec i)))
      :else (nth vs (min (dec (count vs)) (inc i))))))
