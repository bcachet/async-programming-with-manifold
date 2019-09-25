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

(defrecord IRCServer [port    ; config
                     ]
  c/Lifecycle
  (start [this]
    (let [b (bus/event-bus)]
      (defn join- [s args]
        (let [chan-name (clojure.string/lower-case (first args))]
          (s/connect (bus/subscribe b chan-name) s {:timeout 100})))

      (defn quit- [args]
        (str "QUIT " args))

      (defn send- [args]
        (let [chan-name (clojure.string/lower-case (first args))
              message   (clojure.string/join " " (rest args))]
          (println "Message: " message)
          (-> (bus/publish! b chan-name message)
              (d/chain
               (fn [v]
                 (println "Success? " v)))
              (d/catch (fn [e]
                         (println "Error: " e))))))

      (defn handle-cmd [s {:keys [cmd args] :as c}]
        (println "Cmd: " c)
        (case cmd
          :join (join- s args)
          :send (send- args)
          :quit (quit- args)
          (throw (ex-info "Invalid CMD" {:cmd cmd}))))

      (assoc this :server (tcp/start-server
                           (fn [s info]
                             (->> (io/decode-stream s protocol)
                                  (s/map ->cmd)
                                  (s/map (partial handle-cmd s))
                                  ))
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

