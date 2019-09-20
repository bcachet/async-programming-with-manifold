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

(def executor (fixed-thread-executor 42))


;; # Async Programming

;; ## Why using async programming

;; CPU/Threads resources are limited

;; We do a lot of IO operations (handling HTTP requests, DB access)
;; that can be handled by Asynchronous calls on the JVM (Netty for example)

;; => We will be notified once data received/sent

;; => No need to block a thread to await the result


;; ## Callback


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

;; We have no information about status of operation and when it finishes
;; But we block no threads to wait any result

(print-file-content proj-file)


;; ## Clojure **promise**


(defn read-file-async
  "Promise is an object that will contain a value someday

  Value is delivered once into promise (via *deliver*)

  Value can be retrieved via *deref*. The call will block until value delivered

  Promise doest not start a new thread

  Promise can be used in a `manifold.deferred/chain` workflow

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
(->str @(read-file-async proj-file))



;; ## Future


(defn read-file
  "Future is an object that represent an operation performed on another thread

  Operation result can be retrieved via *deref*. The call will block until value delivered

  Nice way to convert *synchronous*/*blocking* code to *asynchronous*/*not blocking*
  "
  [file]
  (let [path    (-> file as-file .toPath)
        channel (FileChannel/open path read-only)
        buffer  (ByteBuffer/allocate (.size channel))]
    (.read channel buffer)
    (.flip buffer)))

;; This operation will block 2 threads, the one created with `future` (the .read operation is synchronous)
;; and the current thread from which we call the `@/deref`
(->str @(future (read-file proj-file)))


;; ## Why using Manifold

;; There are several libraries to deal with async programming in Clojure
;; `clojure.core.async`, `promesa`, `manifold`

;; `clojure.core.async` only offers *Go* channels => everything is a
;; sequence

;; This make API hard to use for single async operations
(->> [(a/go
        @(read-file-async proj-file))] ; wrap operation in `vect`
     (a/map ->str)
     (a/<!!))

;; We use `Aleph` as our HTTP server and it already comes with `manifold`.
;; No reason not to use it


;; ## Manifold
;; ### Composition
;; `manifold.deferred/chain` allows us to compose operations to define
;; workflow/pipeline

(defn ->stream [d]
  (let [s (s/stream)]
    (s/put! s d)
    (s/close! s)
    s))

(defn put-echo [s]
  (http/put "http://postman-echo.com/put" {:body (->stream s)}))

;; We can define our complete pipeline and await for its completion
;; We can define executor to use
@(-> (read-file-async proj-file)
     (d/chain
      ->str
      put-echo
      :status
      )
     (d/onto executor)
     (d/catch Exception (fn [e]
                          {:error e})))


;; ### Error handling

;; ### Define executors

;; ### Transducer support

;; ### Don't


;; ## Usage example


;; ## Reactor: Observable
