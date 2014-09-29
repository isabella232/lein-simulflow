(ns support.async-test
  (:require [midje.sweet :refer :all]
            [clojure.core.async :as async :refer [go <! put! timeout chan alts!! close!]]
            [support.async-test-utils :refer :all]
            [support.async :refer :all]))

(facts "batch-events"
  (let [<c (chan)
        batch (batch-events <c 100)]
    (go
      (put! <c :a)
      (put! <c :b)
      (<! (timeout 50))
      (put! <c :c)
      (<! (timeout 120))
      (put! <c :d)
      (put! <c :d)
      (<! (timeout 120))
      (put! <c :a)
      (put! <c :b)
      (close! <c))
    (chan->vec batch) => [#{:a :b :c} #{:d} #{:a :b}]))
