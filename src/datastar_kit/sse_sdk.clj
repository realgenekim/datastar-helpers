(ns datastar-kit.sse-sdk
  "SDK-flavored bulletproof SSE broadcast for http-kit + the Datastar Clojure SDK.

   Sibling to datastar-kit.sse. That namespace is the RAW-channel/string flavor
   (org.httpkit as-channel + ds/sse-event strings). THIS namespace is for apps
   that use the Datastar SDK adapter — i.e. `hk/->sse-response` giving you a
   `sse-gen`, and `d*/patch-elements!` / `d*/patch-signals!` to write to it.
   (joe-payne-app/esr-dashboard, social-media-writer, reddit-scraper-fulcro.)

   The three reliability rules that make a long-lived stream BORING instead of
   dangerous (learned the hard way in marvin-voice-remote, 2026-05-30):

     1. HEARTBEAT — a tiny signals patch every ~15s so an idle proxy / LB / Cloud
        Run timeout can't silently reap the stream (frozen display, no error).
        Started ONCE (idempotent — safe to call from on-open OR startup), NOT
        recreated per connection. A failed heartbeat write reaps the dead sse-gen.

     2. OFF-THREAD PUSH — broadcasts run on a single agent thread, NEVER on the
        request/handler thread. A slow or half-dead client therefore can't block a
        POST handler and starve http-kit's (small, default 4) worker pool → 503s
        on UNRELATED requests. This is the piece the SDK reference apps historically
        lacked — they fan out synchronously in the handler (see esr-dashboard#16).

     3. SUBSCRIBER SET + REAPING — one shared set of sse-gens (not one watch per
        connection, which leaks); any whose write fails is reaped, so dead
        connections can't accumulate.

   Safe because every push is idempotent (a full element/fragment, not a delta):
   a dropped+reconnected client just re-paints the truth. The stream is disposable;
   your state atom is the truth. Heartbeat + off-thread push + full-state push =
   a long-lived stream that's boring.

   The CONSUMER provides the SDK adapter (dev.data-star.clojure/http-kit) + timbre;
   they're intentionally not required-with-version here so versions don't fight."
  (:require [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [taoensso.timbre :as log]))

(defonce subscribers (atom #{}))
(defonce ^:private push-agent (agent nil))
(defonce ^:private heartbeat-timer (atom nil))

(def ^:dynamic *heartbeat-ms* 15000)

(defn subscriber-count [] (count @subscribers))

(defn- reap!
  "Drop sse-gens whose write failed."
  [dead]
  (when (seq dead)
    (swap! subscribers #(reduce disj % dead))
    (log/info ::reaped :n (count dead) :remaining (count @subscribers))))

(defn- broadcast!*
  "Apply `write-fn` to every subscriber; reap any whose write throws. Runs on the
   agent thread. `write-fn` takes a single sse-gen."
  [write-fn]
  (let [dead (reduce (fn [d sub]
                       (if (try (write-fn sub) true (catch Exception _ false))
                         d (conj d sub)))
                     #{} @subscribers)]
    (reap! dead)))

;; ---------------------------------------------------------------------------
;; Element patches (the common case)
;; ---------------------------------------------------------------------------
(defn- apply-patches!
  "Apply a seq of element patches to ONE sse-gen.
   Each patch: {:html <string> :selector \"#id\" (opt) :mode d*/pm-* (opt)}."
  [sse-gen patches]
  (doseq [{:keys [html selector mode]} patches]
    (d*/patch-elements! sse-gen html
                        (cond-> {}
                          selector (assoc d*/selector selector)
                          mode     (assoc d*/patch-mode mode)))))

(defn push!
  "Broadcast one or more ELEMENT patches to every subscriber, OFF-THREAD.
   `patches` is a seq of maps {:html :selector :mode} (:selector/:mode optional).
   Do the Hiccup->string render in the caller (cheap); the slow socket writes
   happen on the agent thread. Returns immediately — safe to call from a POST
   handler or an atom watch."
  [patches]
  (let [patches (vec patches)]
    (send-off push-agent (fn [_] (broadcast!* #(apply-patches! % patches)) nil))))

(defn push-1!
  "Convenience: broadcast a single element patch (defaults to pm-outer morph)."
  ([html]               (push! [{:html html}]))
  ([selector html]      (push! [{:html html :selector selector :mode d*/pm-outer}]))
  ([selector html mode] (push! [{:html html :selector selector :mode mode}])))

(defn push-signals!
  "Broadcast a signals patch (JSON string) to every subscriber, off-thread."
  [signals-json]
  (send-off push-agent
            (fn [_] (broadcast!* #(d*/patch-signals! % signals-json)) nil)))

;; ---------------------------------------------------------------------------
;; Heartbeat
;; ---------------------------------------------------------------------------
(defn- send-heartbeat! []
  (let [ts (System/currentTimeMillis)]
    (broadcast!* #(d*/patch-signals! % (str "{_sseHeartbeat:" ts "}")))))

(defn start-heartbeat!
  "Start the heartbeat timer ONCE (idempotent — safe to call from on-open or at
   server startup; the per-connection restart bug can't happen). Sends a
   `_sseHeartbeat` signals patch every *heartbeat-ms* so the connection is never
   idle; a failed write reaps the dead sse-gen. No page contract required (it's a
   signal, not a DOM element)."
  []
  (when (compare-and-set! heartbeat-timer nil ::starting)
    (let [timer (java.util.Timer. "datastar-sse-heartbeat" true)
          task  (proxy [java.util.TimerTask] []
                  (run [] (try (send-heartbeat!)
                               (catch Exception e (log/warn e ::heartbeat-error)))))]
      (.scheduleAtFixedRate timer task (long *heartbeat-ms*) (long *heartbeat-ms*))
      (reset! heartbeat-timer timer)
      (log/info ::heartbeat-started :interval-ms *heartbeat-ms*))))

;; ---------------------------------------------------------------------------
;; Connection handler
;; ---------------------------------------------------------------------------
(defn sse-response
  "Build an http-kit + Datastar-SDK SSE ring response: registers the sse-gen as a
   subscriber, ensures the heartbeat is running, optionally streams initial state
   to THIS client via `on-connect`, and deregisters on close. The connection is
   LONG-LIVED (never closed server-side) — state changes arrive via push!/
   push-signals! broadcasts.

   `on-connect` (optional) is a 1-arg fn called with the sse-gen right after
   registration; use it to send the current full state to the connecting client,
   e.g. (doseq [t tiles] (d*/patch-elements! sse-gen ...))."
  ([request] (sse-response request nil))
  ([request on-connect]
   (hk/->sse-response
    request
    {hk/on-open  (fn [sse-gen]
                   (swap! subscribers conj sse-gen)
                   (start-heartbeat!)
                   (when on-connect
                     (try (on-connect sse-gen)
                          (catch Exception e (log/error e ::on-connect-error))))
                   (log/info ::connected :subscribers (count @subscribers)))
     hk/on-close (fn [sse-gen _status]
                   (swap! subscribers disj sse-gen)
                   (log/info ::disconnected :subscribers (count @subscribers)))})))
