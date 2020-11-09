(ns chronicle.cli
  (:require [chronicle.util :as util]))

(def extra-cli-opts
  [[nil "--install SRC_DIR"
    "Copy the given source directory onto the test nodes and compile it prior to starting the test. If this argument is not provided then a previously compile copy must already be present on the nodes"
    :parse-fn util/get-install-package]])
