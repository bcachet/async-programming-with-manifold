(ns async-programming.irc
  (:require [aleph.tcp :as tcp]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [manifold.bus :as bus]
            [aleph.netty :as netty]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [com.stuartsierra.component :as c]
            [reloaded.repl :refer [go stop]]
            [clojure.edn :as edn]))

(def protocol
  (gloss/compile-frame
   (gloss/string :utf-8 :delimiters ["\r\n"])))

(defn ->cmd [s]
  (-> (zipmap [:cmd :args] (clojure.string/split s #" " 2))
      (update :cmd (comp keyword clojure.string/lower-case))
      (update :args #(clojure.string/split % #" "))))

(defmulti handle-cmd :cmd)

(defrecord IRCServer [port    ; config
                     ]
  c/Lifecycle
  (start [this]
    (let [b (bus/event-bus)]
      (defmethod handle-cmd :join [{:keys [args stream]}]
        (let [chan-name (clojure.string/lower-case (first args))]
          (s/connect (bus/subscribe b chan-name) stream {:timeout 100})))

      (defmethod handle-cmd :quit [{:keys [args]}]
        (str "QUIT " args))

      (defmethod handle-cmd :send [{:keys [args]}]
        (let [chan-name (clojure.string/lower-case (first args))
              message   (clojure.string/join " " (rest args))]
          (println "Message: " message)
          (-> (bus/publish! b chan-name message)
              (d/chain
               (fn [v]
                 (println "Success? " v)))
              (d/catch (fn [e]
                         (println "Error: " e))))
          nil))

      (assoc this :server (tcp/start-server
                           (fn [s info]
                             (s/consume (fn [line]
                                          (when-let [bus (-> line
                                                             ->cmd
                                                             (assoc :stream s)
                                                             handle-cmd)])) (io/decode-stream s protocol)))
                           {:port port}))))
  (stop [{:keys [server] :as this}]
    (when server
      (.close server)
      (netty/wait-for-close server))
    (assoc this :server nil)))

(def make-irc-server #'map->IRCServer)

(reloaded.repl/set-init! (fn [] (make-irc-server {:port 8080})))

(comment
  (go)

  (stop)
  )

