(ns async-programming.core
  (:require
   [manifold.deferred :as d]
   [manifold.stream :as s]
   [manifold.executor :refer [fixed-thread-executor]]
   [aleph.http :as http]
   [clojure.java.io :refer [as-file reader]]
   [clojure.core.async :as a])
  (:import
   java.io.DataInputStream
   java.nio.channels.AsynchronousFileChannel
   java.nio.channels.FileChannel
   java.nio.channels.CompletionHandler
   java.nio.ByteBuffer
   java.nio.file.StandardOpenOption
   java.nio.file.OpenOption))

(defn ->str [^ByteBuffer bb]
  (-> bb .array String.))

(def proj-file "/home/bertrand/Desktop/async_programming/project.clj")

(def read-only (into-array OpenOption [StandardOpenOption/READ]))

;; # Async Programming

;; ## Why using async programming

;; CPU/Threads resources are limited

;; We do a lot of IO operations (handling HTTP requests, DB access)
;; that can be handled by asynchronous calls on the JVM (Netty for example)

;; => We will be notified once data received/sent

;; => No need to block a thread to await operation's result

;; => We are not talking about parallel programming.

;; Well defined async programming, do not use too many threads or at least do not block too many of them.



;; ## Async programming approaches

;; ### Callback

(defn print-file-content
  "We define what to do on operation completion via **callbacks**

  Nothing returned to represent the operation =>
  transformation on the result occurs in the callback

  CPU used only when Callback is called

  When we need to perform another async task, we will nest
  callbacks together => **callbacks hell**"
  [file]
  (let [path    (-> file as-file .toPath)
        channel (AsynchronousFileChannel/open path read-only)
        buffer  (ByteBuffer/allocate (.size channel))]
    (.read channel buffer 0 nil
           (proxy [CompletionHandler] []
             (completed [result attachment]
               (.close channel)
               (.flip buffer)
               (println (->str buffer)))
             (failed [e attachment]
               (.close channel)
               (println "Error reading file"))))))

;; We have no information about status of operation and when it finishes.
;; But we block no threads to wait any result

(print-file-content proj-file)


;; ### Promise


(defn read-file-async
  "Promise is an object that will contain a value someday

  Value is delivered once into promise (via *deliver*)

  Value can be retrieved via *deref*. The call will block until value delivered

  Promise doest not start a new thread

  Promise can be used in a `manifold.deferred/chain` workflow

  `d/deferred` can be used as a `promise`. We can `deliver` on a `d/deferred`

  In Java Promise refers to Future"
  [file]
  (let [path    (-> file as-file .toPath)
        channel (AsynchronousFileChannel/open path read-only)
        buffer  (ByteBuffer/allocate (.size channel))
        p       (promise)]
    (.read channel buffer 0 nil
           (reify CompletionHandler
             (completed [this result attachment]
               (.close channel)
               (.flip buffer)
               (deliver p buffer))
             (failed [this e attachment]
               (.close channel)
               (deliver p nil))))
    p))

;; We only use one thread to await async task to finish
(->str @(read-file-async proj-file)) ; => "(defproject ....


;; ### Future


(defn read-file
  "Future is an object that represent an operation performed on another thread

  Operation result can be retrieved via *deref*. The call will block until value delivered

  Nice way to convert *synchronous*/*blocking* code to *asynchronous*/*not blocking*

  `d/future` can be used instead of `future`
  "
  [file]
  (let [path    (-> file as-file .toPath)
        channel (FileChannel/open path read-only)
        buffer  (ByteBuffer/allocate (.size channel))]
    (.read channel buffer)
    (.flip buffer)))

;; This operation will block 2 threads, the one created with `future` (the .read operation is synchronous)
;; and the current thread from which we call the `@/deref`
(->str @(future (read-file proj-file))) ; => "(defproject ...


;; ## Why using Manifold

;; There are several libraries to deal with async programming in Clojure
;; `clojure.core.async`, `promesa`, `manifold`

;; `clojure.core.async` only offers *Go* channels => everything is a
;; sequence

;; This make API hard to use for single async operations


(->> [(a/go
        @(read-file-async proj-file))] ; wrap operation in `vect`
     (a/map ->str)
     (a/<!!)) ; => "(defproject ...

;; We use `Aleph` as our HTTP server and it already comes with `manifold`.
;; No reason not to use it


;; ## Manifold

;; ### Manifold.deferred, Promise and Future

;; `d/deferred` can be used as Clojure `promise` (we can `deliver` on a `d/deferred`)
;; and `d/future` is a direct replacement of `future`

(let [p (d/deferred)]
  (deliver p :some-value)
  (deliver p :another-value)
  (repeatedly 2 #(deref p))
  ) ; => (:some-value :some-value)


;; We should always use `manifold` version because `promise`/`future` use blocking
;; dereference => Manifold allocated thread to treat them as asynchronous deferred.

(defn read-file-async
  [file]
  (let [path    (-> file as-file .toPath)
        channel (AsynchronousFileChannel/open path read-only)
        buffer  (ByteBuffer/allocate (.size channel))
        p       (d/deferred)]
    (.read channel buffer 0 nil
           (reify CompletionHandler
             (completed [this result attachment]
               (.close channel)
               (.flip buffer)
               (d/success! p buffer))
             (failed [this e attachment]
               (.close channel)
               (d/error! p nil))))
    p))

;; ### Composition
;; `d/chain` allows us to compose operations to define
;; workflow/pipeline

(defn put-echo
  "PUT data to postman-echo

  Response returned in a `manifold.deferred`"
  [s]
  (http/put "http://postman-echo.com/put" {:body s}))

;; We can define our complete pipeline.
;; If one of the step return a `manifold.deferred`,
;; `manifold.deferred/chain` will wait a value is delivered
;; before moving to next step
@(d/chain (read-file-async proj-file)
          ->str
          clojure.string/upper-case
          put-echo
          :status) ; => 200

;; ### Error handling

;; A `manifold.deferred` can represent both success (`d/success-deferred`) and error (`d/error-deferred`)
;; When derefing a `d/error-deferred`, an exception will be thrown with associated value

(comment
  @(-> (d/success-deferred :starting-nicely)
       (d/chain (constantly (d/error-deferred :booooom)))) ; => ExceptionInfo {:error :boooom}

  )

;; In a `d/chain`, if a step throw an exception, it is catched and wrap into a `d/error-deferred`
(comment
  @(-> (d/success-deferred :starting-nicely)
       (d/chain (fn [_] (throw (ex-info "boooom" {}))))) ; => ExceptionInfo {:error :boooom}
  )

;; Once a step is in *error*, following steps in a `d/chain` will be skipped

(comment
  @(-> (d/success-deferred :starting-nicely)
       (d/chain
        (constantly (d/error-deferred :booooom))
        (constantly :never-reached))) ; => ExceptionInfo {:error :booooom}
  )

;; We can catch error to perform any recovery

@(-> (d/success-deferred :starting-nicely)
     (d/chain (constantly (d/error-deferred :booooom))
              (constantly :never-reached))
     (d/catch (fn error-handling [e] {:state :recovered :error e}))) ; => {:state :recovered :error :booooom}



;; ## Manifold Streams

;; A `s/stream` can be seen as a `a/channel`.

;; We can `s/put!`, `s/take!` messages until stream is closed via `s/close!`

(let [s  (s/stream)
      s' (s/map (comp inc inc) s)]
  (doall (map #(s/put! s %) (range 5)))
  (s/close! s)
  (repeatedly 6 #(deref (s/take! s')))) ; => (2 3 4 5 6 nil)


;; ## Transducer support

;; *transducer* are really nice to express operation that do not depend on the messaging system.
;; We can use same *transducer* on top of `a/channel` or `s/stream`

;; `s/transform` apply a *transducer* to a stream

;; ## Executors

;; We can define our own executor
(def executor (fixed-thread-executor 42))

;; We can specify on which executor a pipeline shouls be run

@(-> (read-file-async proj-file)
     (d/onto executor)
     (d/chain ->str
              (fn [s]
                (println "Deferred: " (.getName (Thread/currentThread)))
                s)
             (fn [s]
               (d/future (println "Future: " (.getName (Thread/currentThread)))
                         s)))
     ) ; => "(defproject ...

(.getName (Thread/currentThread))

;; Manifold use same thread from which `d/deferred` is created.

;; By default Manifold use same thread-pool from which thread creating `d/future` is created.

;; `aleph` ensure request is handled outside of IO thread pool (does not block `netty`).
;; It is ran onto dedicated thread pool (configured via `aleph.http.server/start-server`)

;; => `d/deferred`/`d/future` will be handled/created onto this `aleph` thread pool.


;; Today in Obwald, we have dedicated thread pools for our HTTP clients (CloudStack, Privnets, Bundes).
;; As CloudStack operation can be long, it is maybe fine to have dedicated thread pool here. But not really sure of the need for other services.



;; ## When to deref ?

;; `deref` means waiting for task to finish => we block a thread.

;; As we use `aleph` we should never deref our pipeline

;; `Aleph` response can be a deferred that will be written to client socket once realized.
;; => no need to deref our pipeline to handle a request

;; Note: `aleph.http/post`/`aleph.http/put` request body/data can be a `s/stream`



;; ## Don't



;; ## Usage example


