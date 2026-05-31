(ns datastar-kit.sse
  "Reliable SSE broadcast for http-kit + Datastar.

   The three things that make a long-lived SSE stream BORING instead of dangerous
   (each learned the hard way in production):

     1. HEARTBEAT  — a comment line every ~15s so idle proxy/Cloud-Run timeouts
                     can't silently reap the stream (frozen display with no error).
                     A failed heartbeat write also reaps the dead channel.
     2. OFF-THREAD PUSH — broadcasts run on an agent thread, NEVER on the request/
                     swap thread. A slow or half-dead client therefore can't block
                     a POST handler and starve http-kit's worker pool (→ 503s).
     3. SUBSCRIBER SET + REAPING — one shared set of channels (not one watch per
                     connection, which leaks); any channel whose write fails is
                     reaped, so dead connections can't accumulate.

   Safe because every push sends the FULL current fragment (idempotent, not deltas):
   a dropped+reconnected stream just re-paints the truth. The stream is disposable;
   your state atom is the truth.

   The CONSUMER provides org.httpkit + timbre (every SSE app already has them)."
  (:require [org.httpkit.server :as http]
            [taoensso.timbre :as log]))

(defonce subscribers (atom #{}))
(defonce ^:private push-agent (agent nil))

(def ^:dynamic *heartbeat-ms* 15000)

(defn subscriber-count [] (count @subscribers))

(defn- broadcast!*
  "Send (render-fn) — the full SSE event string — to every subscriber; reap any
   whose write fails."
  [render-fn]
  (let [frames (render-fn)
        dead (reduce (fn [d ch]
                       (if (try (http/send! ch frames false) (catch Exception _ false))
                         d (conj d ch)))
                     #{} @subscribers)]
    (when (seq dead)
      (swap! subscribers #(reduce disj % dead))
      (log/info ::reaped :n (count dead) :remaining (count @subscribers)))))

(defn trigger-push!
  "Schedule a broadcast to all subscribers on the agent thread. `render-fn` is a
   0-arg fn returning the full SSE event string (build it with
   datastar-kit.ds/sse-event etc.). Returns immediately — the actual writes happen
   off the caller's thread, so this is safe to call from inside a POST handler or
   an atom watch."
  [render-fn]
  (send-off push-agent (fn [_] (broadcast!* render-fn) nil)))

(defn- start-heartbeat! [ch]
  (future
    (loop []
      (Thread/sleep *heartbeat-ms*)
      (when (contains? @subscribers ch)
        (if (try (http/send! ch ": heartbeat\n\n" false) (catch Exception _ false))
          (recur)
          (do (swap! subscribers disj ch)
              (log/info ::heartbeat-reaped :remaining (count @subscribers))))))))

(defn sse-handler
  "Build an http-kit ring handler for an SSE endpoint. `initial-fn` is a 0-arg fn
   returning the SSE string to send to a newly-connected client (the current full
   state). Registers the channel in `subscribers`, starts its heartbeat, and
   deregisters on close.

   Wire your state changes to broadcast like this:
     (add-watch app-state ::push
       (fn [_ _ o n] (when (not= o n) (trigger-push! render-frames))))"
  [initial-fn]
  (fn [req]
    (http/as-channel
     req
     {:on-open  (fn [ch]
                  (http/send! ch {:status 200
                                  :headers {"Content-Type"  "text/event-stream"
                                            "Cache-Control" "no-cache"
                                            "Connection"    "keep-alive"
                                            "X-Accel-Buffering" "no"}}
                              false)
                  (http/send! ch (initial-fn) false)   ; initial state to THIS client
                  (swap! subscribers conj ch)
                  (start-heartbeat! ch)
                  (log/info ::connected :subscribers (count @subscribers)))
      :on-close (fn [ch status]
                  (swap! subscribers disj ch)
                  (log/info ::disconnected :status status :subscribers (count @subscribers)))})))
