(ns async-programming.mani
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [aleph.http :as http]
            ))


(comment
  (do
    (def r1 (d/deferred))
    (d/success! r1 :foo)
    @r1
    )
  )


(comment
  (do
    (def r2 (d/deferred))
    (d/error! r2 :foo)
    @r2)
  )

(comment
  (do
    (def r3 (d/deferred))
    (d/on-realized r3 (partial prn :success!) (partial prn :failure!))
    (d/error! r3 :foo))
  )


(comment
  (do
    (def r4 (d/future (Thread/sleep 2000) :foo))
    (def r5 (d/future (Thread/sleep 1000) :bar))

    (time
     @(d/zip r4 r5)))
  )

(defn slow-value
  [value]
  (d/future
    (Thread/sleep 2000)
    value))

(comment

  (time
   @(d/let-flow [x (slow-value 3)
                 y (slow-value 2)]
                (+ x y)))
  )

(comment

  @(d/chain (d/future :bar)
            name
            (partial str "foo"))

  )

(comment

  @(-> (d/future 0)
       (d/chain #(d/future (inc %))
                #(d/future (inc %))
                #(d/future (/ % 0)))
       (d/catch (constantly :error/fault)))

  )

(comment

  (def s (s/stream))

  (s/put! s :foo)
  (s/take! s) ;; yields a deferred!


  )

(comment

  (def s (s/stream))
  (s/put! s 0)

  @(d/chain (s/take! s)
            inc)

  )

(comment

  (def s (s/stream 10 (map inc)))

  (s/put! s 0)

  @(s/take! s)
  
  )

(comment

  (defn handler
    [_]
    {:status 200
     :content-type "text/plain"
     :body (s/periodically 1000 (constantly "hello\n"))})


  (def s (http/start-server handler {:port 8080}))

  (.close s)

  )
