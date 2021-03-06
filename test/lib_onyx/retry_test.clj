(ns lib-onyx.retry-test
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.peer.pipeline-extensions :as p-ext]
            [onyx.extensions :as extensions]
            [onyx.plugin.core-async]
            [onyx.api]
            [lib-onyx.retry :as retry]))

(defn exciting-name-impl [{:keys [name] :as segment}]
  (when (.startsWith name "X")
    (throw (ex-info "Name started with X" {:reason :x-name :segment segment})))
  {:name (str name "!")})

(defn exciting-name [produce-f segment]
  (retry/retry-on-failure exciting-name-impl produce-f segment))

(def workflow {:in {:exciting-name :out}})

(def capacity 1000)

(def input-chan (chan capacity))

(def output-chan (chan capacity))

(defmethod l-ext/inject-lifecycle-resources :in
  [_ _] {:core-async/in-chan input-chan})

(defmethod l-ext/inject-lifecycle-resources :out
  [_ _] {:core-async/out-chan output-chan})

(def batch-size 10)

(def catalog
  [{:onyx/name :in
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/consumption :sequential
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :exciting-name
    :onyx/ident :lib-onyx.join/requeue-and-retry
    :onyx/fn :lib-onyx.retry-test/exciting-name
    :onyx/type :function
    :onyx/consumption :concurrent
    :lib-onyx.retry/n 3
    :onyx/batch-size batch-size
    :onyx/doc "Requeues segments that throw exceptions, at most :lib-onyx.retry/n times"}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/consumption :sequential
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

(def input-segments
  [{:name "Mike"}
   {:name "Xiu"}
   {:name "Phil"}
   {:name "Julia"}
   :done])

(doseq [segment input-segments]
  (>!! input-chan segment))

(close! input-chan)

(def id (java.util.UUID/randomUUID))

(def scheduler :onyx.job-scheduler/round-robin)

(def env-config
  {:hornetq/mode :vm
   :hornetq.server/type :vm
   :hornetq/server? true
   :zookeeper/address "127.0.0.1:2186"
   :zookeeper/server? true
   :zookeeper.server/port 2186
   :onyx/id id
   :onyx.peer/job-scheduler scheduler})

(def peer-config
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2186"
   :onyx/id id
   :onyx.peer/job-scheduler scheduler})

(def env (onyx.api/start-env env-config))

(def v-peers (onyx.api/start-peers! 1 peer-config))

(onyx.api/submit-job
 peer-config
 {:catalog catalog :workflow workflow
  :task-scheduler :onyx.task-scheduler/round-robin})

(def results (onyx.plugin.core-async/take-segments! output-chan))

(clojure.pprint/pprint results)

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-env env)

