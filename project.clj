(defproject chronicle "0.1.0-SNAPSHOT"
  :description "Jepsen Testing For Chronicle"
  :main chronicle.core
  :plugins [[lein-cljfmt "0.6.7"]
            [lein-kibit "0.1.8"]
            [jonase/eastwood "0.3.11"]]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "1.0.194"]
                 [jepsen "0.2.3"]
                 [cheshire "5.10.0"]
                 [clj-http "3.10.1"]]
  :profiles {})
