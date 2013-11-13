(defproject aqua-node "0.1.0-SNAPSHOT"
  :description "anonymous quanta"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "0.3.4"]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1978"]]
  :cljsbuild {:builds [{:source-path "src"
                        :compiler {:target :nodejs
                                   :output-to "target/aqua.js"
                                   ;:optimizations :advanced
                                   :foreign-libs [{:file "resources/fin.js" :provides ["finalhack"]}]
                                   :optimizations :simple
                                   :pretty-print true}}]})
