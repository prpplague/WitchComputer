(ns witch.nines-test
  (:require [clojure.test :refer :all]
            [witch.test-helper :as h]
            [witch.nines :as n]))

(deftest to-nines
  (is (= (h/value-and-scale (n/to-nines 1.0000000M))
         [1.00000000M 7]))

  (is (= (h/value-and-scale (n/to-nines 0.0100000M))
         [0.01000000M 7]))

  (is (= (h/value-and-scale (n/to-nines -1.0000000M))
         [98.9999999M 7]))

  (is (= (h/value-and-scale (n/to-nines -0.0010000M))
         [99.9989999M 7]))

  (is (= (h/value-and-scale (n/to-nines -0.0000001M))
         [99.9999998M 7]))
  )

(deftest from-nines
  (is (= (n/from-nines 1.0000000M)
         1M))

  (is (= (n/from-nines 98.9999999M)
         -1M))

  (is (= (n/from-nines 99.9989999M)
         -0.001M))

  (is (= (n/from-nines 99.9999998M)
         -0.0000001M))
  )

(deftest round-places
  (is (= (h/value-and-scale (n/adjust-places 0.00000001M 4))
         [0.0000M 4]))

  (is (= (h/value-and-scale (n/adjust-places 0.22222222M 5))
         [0.22222M 5]))

  (is (= (h/value-and-scale (n/adjust-places 99.22222222M 5))
         [99.22222M 5]))

  (is (= (h/value-and-scale (n/adjust-places 9999.222222222222M 5))
         [99.22222M 5]))
  )


(deftest examine-sign
  (is (= (n/positive? (n/to-nines 1M))
         true))

  (is (= (n/positive? (n/to-nines -1M))
         false))

  (is (= (n/positive? 00.00000000)
         true))

  (is (= (n/positive? 99.99999999)
         false))
  )

(deftest negate
  (is (= (h/value-and-scale (n/negate 1.111M))
         [98.888M 3]))

  (is (= (h/value-and-scale (n/negate 1.1111111M))
         [98.8888888M 7]))

  (is (= (h/value-and-scale (n/negate 2.22222222222222M))
         [97.77777777777777M 14]))
  )

(deftest carry-over
  (is (= (h/value-and-scale (n/carry-over 100.000000M 2 7))
         [00.0000001M 7]))

  (is (= (h/value-and-scale (n/carry-over 100.0000000000000M 2 14))
         [00.00000000000001M 14]))

  (is (= (h/value-and-scale (n/carry-over 23400.0000000000000M 2 14))
         [00.00000000000234M 14]))

  (is (= (h/value-and-scale (n/carry-over 199.99999999999998M 2 14))
         [99.99999999999999M 14]))
  )

(deftest pow10
  (is (= (n/pow10 0M)
         1.0M))
  (is (= (n/pow10 1M)
         10.0M))

  (is (= (n/pow10 5M)
         100000.0M))

  (is (= (n/pow10 -5M)
         0.00001M))
  )

; TODO this
(deftest sign-extend)