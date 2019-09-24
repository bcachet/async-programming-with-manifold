(ns async-programming.irc
  (:require [aleph.tcp :as tcp]
            [manifold.deferred :as d]
            [manifold.stream :as s]
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
    (let [state (atom {})]
      (defmethod handle-cmd :join [{:keys [args]}]
        (str "JOIN " args))

      (defmethod handle-cmd :quit [{:keys [args]}]
        (str "QUIT " args))

      (defmethod handle-cmd :default [{:keys [cmd]}]
        (throw (ex-info "Invalid CMD" {:cmd cmd})))

      (assoc this :server (tcp/start-server
                           (fn [s _]
                             (s/connect (->> (io/decode-stream s protocol)
                                             (s/map ->cmd)
                                             (s/map handle-cmd))
                                        s))
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

