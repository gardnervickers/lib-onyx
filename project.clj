(defproject com.mdrogalis/lib-onyx "0.5.3"
  :description "A library to support additional functionality in Onyx"
  :url "https://github.com/MichaelDrogalis/lib-onyx"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.onyxplatform/onyx "0.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]]
                   :plugins [[lein-midje "3.1.1"]]}})
