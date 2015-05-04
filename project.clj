(defproject aqua-node "0.1.0-SNAPSHOT"
  :description "anonymous quanta"
  :url "http://example.com/FIXME"
  :license {:name "BSD"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :plugins [[lein-cljsbuild "1.0.5"]
            [com.cemerick/piggieback "0.1.3"]]
                 
  :dependencies [[org.clojure/clojure "1.7.0-beta1"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-3211"]
                 [org.bodil/cljs-noderepl "0.1.11"]]
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :cljsbuild {:builds [{:source-paths ["src"]
                        :compiler {:target :nodejs
                                   :hashbang "/usr/bin/env node\nrequire('source-map-support').install();"
                                   :output-to "target/aqua.js"
                                   :source-map "target/aqua.js.map"
                                   ;:cache-analysis true  ;; latest cljs doesn't like this
                                   :optimizations :simple
                                   :static-fns true
                                   :pretty-print true}}]})
