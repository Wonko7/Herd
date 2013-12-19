(defproject aqua-node "0.1.0-SNAPSHOT"
  :description "anonymous quanta"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "0.3.4"]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.clojure/clojurescript "0.0-1978"]]
  :cljsbuild {:builds [{:source-path "src"
                        :compiler {:target :nodejs
                                   :hashbang "/usr/bin/env node\nrequire('source-map-support').install();"
                                   :output-to "target/aqua.js"
                                   :source-map "target/aqua.js.map"
                                   :optimizations :simple
                                   :static-fns true
                                   :pretty-print true}}]})
