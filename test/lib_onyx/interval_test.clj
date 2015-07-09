(ns lib-onyx.interval-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [midje.sweet :refer :all]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

(def config
  {:env-config
   {:zookeeper/address "127.0.0.1:2188"
    :zookeeper/server? true
    :zookeeper.server/port 2188}

   :peer-config
   {:zookeeper/address "127.0.0.1:2188"
    :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
    :onyx.peer/zookeeper-timeout 60000
    :onyx.messaging/impl :netty
    :onyx.messaging/peer-port-range [40200 40260]
    :onyx.messaging/peer-ports [40199]
    :onyx.messaging/bind-addr "localhost"}
   :logging {}})

(def id (java.util.UUID/randomUUID))

(def env-config (assoc (:env-config config) :onyx/id id))

(def peer-config (assoc (:peer-config config) :onyx/id id))

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

(def n-messages 100)

(def batch-size 20)

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def counter (atom []))

(defn start-task? [event lifecycle]
  (swap! counter (fn [counter] (conj counter :task-started)))
  true)

(defn before-task-start [event lifecycle]
  (swap! counter (fn [counter] (conj counter :task-before)))
  {})

(defn after-task-stop [event lifecycle]
  (swap! counter (fn [counter] (conj counter :task-after)))
  {})

(defn before-batch [event lifecycle]
  (swap! counter (fn [counter] (conj counter :batch-before)))
  {})

(defn after-batch [event lifecycle]
  (swap! counter (fn [counter] (conj counter :batch-after)))
  {})

(def catalog
  [{:onyx/name :in
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :inc
    :onyx/fn :onyx.peer.lifecycles-test/my-inc
    :onyx/type :function
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Writes segments to a core.async channel"}])

(def workflow [[:in :inc]
               [:inc :out]])

(def in-chan (chan (inc n-messages)))

(def out-chan (chan (sliding-buffer (inc n-messages))))

(defn inject-in-ch [event lifecycle]
  {:core.async/chan in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan out-chan})

(def calls
  {:lifecycle/start-task? start-task?
   :lifecycle/before-task-start before-task-start
   :lifecycle/before-batch before-batch
   :lifecycle/after-batch after-batch
   :lifecycle/after-task-stop after-task-stop})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :onyx.peer.lifecycles-test/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :inc
    :lifecycle/calls :onyx.peer.lifecycles-test/calls
    :lifecycle/doc "Test lifecycles that increment a counter in an atom"}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.peer.lifecycles-test/out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(doseq [n (range n-messages)]
  (>!! in-chan {:n n}))

(>!! in-chan :done)

(close! in-chan)

(def v-peers (onyx.api/start-peers 3 peer-group))

(onyx.api/submit-job
 peer-config
 {:catalog catalog
  :workflow workflow
  :lifecycles lifecycles
  :task-scheduler :onyx.task-scheduler/balanced})

(def results (take-segments! out-chan))

(let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
  (fact (set (butlast results)) => expected)
  (fact (last results) => :done))

(def expected-order
  [:task-started
   :task-before
   :batch-before
   :batch-after ; 1
   :batch-before
   :batch-after ; 2
   :batch-before
   :batch-after ; 3
   :batch-before
   :batch-after ; 4
   :batch-before
   :batch-after ; 5
   :batch-before
   :batch-after
   :task-after])


;; shutdown-peer ensure peers are fully shutdown so that
;; :task-after will have been set
(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(fact @counter => expected-order)

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)
