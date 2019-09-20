(ns async-programming.core
  (:require
   [manifold.deferred :as d]
   [manifold.executor :refer [fixed-thread-executor]]
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

;; ## Callback


(defn print-file-content
  "We define what to do on operation completion via **callbacks**

  Nothing returned to represent the operation =>
  transformation on the result occurs in the callback

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
(print-file-content proj-file)

;; manifold.deferred offers callback support, but do you really want to use it ?

(def d (let [d (d/deferred)]
         (d/on-realized d
                        (fn completed [v]
                          (println v))
                        (fn failed [e]
                          (println "Error: " e)))
         d))

(d/success! d :some-value)

;; ## Clojure **promise**

(defn read-file
  "Promise is an object that will contain a value someday

  Value is delivered once into promise (via *deliver*)

  Value can be retrieved via *deref*. The call will block until value delivered

  Promise doest not start a new thread

  In Java Promise refers to Future
  "
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

;; Clojure **promise** can be used in a `manifold.deferred/chain` workflow

(-> (read-file proj-file)
    deref
    ->str)


;; ## Future

(defn read-file
  "Future is an object that represent an operation performed on another thread

  Operation result can be retrieved via *deref*. The call will block until value delivered

  Nice way to convert *synchronous*/*blocking* code to *asynchronous*/*not blocking*
  "
  [file]
  (let [path    (-> file as-file .toPath)
        channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))
        buffer  (ByteBuffer/allocate (.size channel))]
    (.read channel buffer)
    (.flip buffer)))

;; Waiting for a better Future
@(future (read-file proj-file))

;; ## Why using Manifold

;; There are several libraries to deal with async programming in Clojure
;; `clojure.core.async`, `promesa`, `manifold`

;; `clojure.core.async`, there is all you need but API is hard to use
;; Do you really want to compose this way ?

(->> [(a/go (read-file proj-file))]
     (a/map ->str)
     (a/<!!))


;; We use `Aleph` as our HTTP server and it already comes with `manifold`
;; No reason not to use it


;; ## Manifold
;; ### Composition
;; `manifold.deferred/chain` allows us to compose operations to define
;; workflow/pipeline

()

@(-> (d/future (read-file "not-existing-file"))
     (d/onto executor)
     (d/chain ->str)
     (d/catch Exception (fn [e]
                          (println "Error" e))))


;; ### Error handling

;; ### Define executors

;; ### Transducer support




;; ## Usage example
