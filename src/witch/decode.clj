(ns witch.decode
  "Decode and process the instructions.

  See http://www.computerconservationsociety.org/witch5.htm for a fairly
  comprehensive description of the arithmetic operations."

  (:require [witch.machine :as m]
            [witch.utils :as u]
            [clojure.pprint :as pp]
            [witch.nines :as n]))

; Values to multiply by when performing shifting
(def shift-values
  [0M 1M, 0M, -1M, -2M, -3M, -4M, -5M, -6M, -7M])

; Keywords used by the block markers
(def block-keywords
  [:block0 :block1 :block2 :block3 :block4
   :block5 :block6 :block7 :block8 :block9])


(defn invalid-stores
  "Check that a pair of stores are ok for an ALU order"
  [src dst]
  (if (or (>= src 10) (>= dst 10))
    (= (quot src 10) (quot dst 10))
    (not (some
           #(= dst %)
           (case src
             0 [9]
             (1 2 3 4 5 6 7) [0 9]
             (8 9) [0 1 2 3 4])))))

(defn address-a
  "Extract the 'src' field from the order (digits 2,3)"
  [opcode]
  (-> opcode (* 100M) (mod 100M) int))

(defn address-b
  "Extract the 'dst' field from the order (digits 4,5)"
  [opcode]
  (-> opcode (* 10000M) (mod 100M) int))

(defn exec-add
  [machine-state a b]
  (when (invalid-stores a b)
    (throw (ex-info "Invalid stores" machine-state)))

  (->
    machine-state
    (assoc :sending-clear false)
    (assoc :transfer-complement false)
    (m/read-sending-address a)
    (m/transfer)
    (m/write-address b)
    (assoc :transfer-shift 0M)
    (m/advance-pc)))

(defn exec-add-and-clear
  [machine-state a b]
  (when (invalid-stores a b)
    (throw (ex-info "Invalid stores" machine-state)))
  
  (->
    machine-state
    (assoc :sending-clear true)
    (assoc :transfer-complement false)
    (m/read-sending-address a)
    (m/transfer)
    (m/write-address b)
    (m/advance-pc)))

(defn exec-subtract
  [machine-state a b]
  (when (invalid-stores a b)
    (throw (ex-info "Invalid stores" machine-state)))

  (->
    machine-state
    (assoc :sending-clear false)
    (assoc :transfer-complement true)
    (m/read-sending-address a)
    (m/transfer)
    (m/write-address b)
    (assoc :transfer-shift 0M)
    (m/advance-pc)))

(defn exec-subtract-and-clear
  [machine-state a b]
  (when (invalid-stores a b)
    (throw (ex-info "Invalid stores" machine-state)))

  (->
    machine-state
    (assoc :sending-clear true)
    (assoc :transfer-complement true)
    (m/read-sending-address a)
    (m/transfer)
    (m/write-address b)
    (m/advance-pc)))

(defn- exec-muldiv-apply-summations
  [machine-state a b f]
  (as->
    machine-state $

    (assoc $ :sending-clear false)
    (assoc $ :transfer-complement (:muldiv-complement $))
    (assoc $ :transfer-shift (- (:muldiv-step $)))

    (u/apply-while
      #(-> %
           (m/read-sending-address a)
           (m/transfer)
           (m/write-address 9)
           (m/inc-dec-register-tube b (:muldiv-step %) (:muldiv-complement %)))
      $
      f)))

(defn- exec-muldiv-step-back
  [machine-state a]
  (->
    machine-state
    (assoc :transfer-complement (not (:muldiv-complement machine-state)))
    (m/read-sending-address a)
    (m/transfer)
    (m/write-address 9)))

(defn- exec-multiply-step
  [machine-state a b]

  (as->
    machine-state $

    ; First perform the summations
    (exec-muldiv-apply-summations
      $ a b
      #(not= (m/read-register-tube % b (:muldiv-step %)) 0M))

    ; Step back one place if stepping the register forwards
    (if (:muldiv-complement $) (exec-muldiv-step-back $ a) $)

    (update $ :muldiv-step inc)))

(defn exec-multiply
  "Perform long multiplication on 2 values into the accumulator."
  [machine-state a b]
  (when (or (invalid-stores a b)
            (< a 10)
            (< b 10))
    (throw (ex-info "Invalid stores" machine-state)))

  (as->
      machine-state $
      (m/read-sending-address $ b)
      (assoc $ :muldiv-complement (n/negative? (:sending-value $)))
      (m/set-sign $ b true)
      (assoc $ :muldiv-step 0)

      (u/apply-while #(exec-multiply-step % a b) $ #(< (:muldiv-step %) 8))

      (assoc $ :transfer-shift 0M)
      (m/advance-pc $)))


(defn- exec-divide-step
  [machine-state a b acc-sign]

  (as->
    machine-state $

    ; First perform the summations and step back
    (exec-muldiv-apply-summations
      $ a b
      #(= acc-sign (n/sign (:sending-value (m/read-sending-address % 9)))))

    ; Step back
    (exec-muldiv-step-back $ a)

    ; Correct register if going up
    (if (:muldiv-complement $)
      (m/inc-dec-register-tube $ b (:muldiv-step $) (not (:muldiv-complement $)))
      $)

    (update $ :muldiv-step inc)))

(defn exec-divide
  [machine-state a b]
  (when (or (invalid-stores a b)
            (< a 10)
            (< b 10))
    (throw (ex-info "Invalid stores" machine-state)))

  (let [snd-sign (n/sign (:sending-value (m/read-sending-address machine-state a)))
        acc-sign (n/sign (:sending-value (m/read-sending-address machine-state 9)))]
    (as->
      machine-state $
      (assoc $ :muldiv-complement (= snd-sign acc-sign))
      (m/set-sign $ b (= snd-sign acc-sign))
      (assoc $ :muldiv-step 0)

      (u/apply-while #(exec-divide-step % a b acc-sign) $ #(< (:muldiv-step %) 8))

      (assoc $ :transfer-shift 0M)
      (m/advance-pc $))))

(defn exec-transfer-positive-modulus
  [machine-state a b]
  (when (invalid-stores a b)
    (throw (ex-info "Invalid stores" machine-state)))

  (->
    machine-state
    (assoc :sending-clear false)
    (assoc :transfer-complement :sending)
    (m/read-sending-address a)
    (m/transfer)
    (m/write-address b)
    (assoc :transfer-shift 0M)
    (m/advance-pc)))

; Control instruction decodes

(defn exec-signal
  [machine-state a]
  (when (> (mod a 10) 2)
    (throw (ex-info "Invalid signal value" machine-state)))

  (pp/cl-format true ">>> Signal ~a <<<~%" a)

  (->
    machine-state
    (assoc :finished (> (mod a 10) 0))
    (m/advance-pc)))

(defn exec-sign-examination
  [machine-state a b]
  (when-not (#{1 2} (mod a 10))
    (throw (ex-info "Invalid sign examination opcode" machine-state)))

  (as->
    machine-state $
    (m/read-sending-address $ b)
    (assoc $ :sign-test (if (= 1 (mod a 10))
                          (n/positive? (:sending-value $))
                          (n/negative? (:sending-value $))))
    (m/advance-pc $)))

(defn exec-transfer-control
  [machine-state a b]
  (if (or (= 1 (rem a 10)) (:sign-test machine-state))
    (assoc machine-state :pc b)
    (m/advance-pc machine-state)))

(defn exec-search-tape
  [machine-state a b]
  (->
    machine-state
    (m/search-tape b (get block-keywords (mod a 10)))
    (m/advance-pc)))

(defn exec-search-tape-conditional
  [machine-state a b]
  (as->
    machine-state $
    (if (:sign-test $)
        (m/search-tape $ b (get block-keywords (mod a 10)))
        $)
    (m/advance-pc $)))

(defn exec-change-print-layout
  [machine-state a]
  (->
    machine-state
    (assoc :printing-layout (mod a 10))
    (m/advance-pc)))

(defn exec-set-shift-selection
  [machine-state a]
  (when (= (mod a 10) 0)
    (throw (ex-info "Invalid shift value" machine-state)))

  (->
    machine-state
    (assoc :transfer-shift (get shift-values (mod a 10)))
    (m/advance-pc)))

(defn decode-control
  "Decode a 'control' order. Control orders start with a '0'"
  [machine-state a b]
  (case (quot a 10)
    8 (exec-set-shift-selection machine-state a)
    7 (exec-change-print-layout machine-state a)
    5 (exec-search-tape-conditional machine-state a b)
    3 (exec-search-tape machine-state a b)
    2 (exec-transfer-control machine-state a b)
    1 (exec-sign-examination machine-state a b)
    0 (exec-signal machine-state a)
    (throw (ex-info (str "Cannot decode control opcode 0" a) machine-state))))

(defn decode
  "Decode an order"
  [machine-state opcode]

  (when (:trace machine-state)
    (pp/cl-format true "Executing ~,4F on:~%" opcode)
    (m/dump-machine-state machine-state)
    (pp/cl-format true "------------------~%"))

  (let [o (int opcode)
        a (address-a opcode)
        b (address-b opcode)]
    (case o
      1 (exec-add machine-state a b)
      2 (exec-add-and-clear machine-state a b)
      3 (exec-subtract machine-state a b)
      4 (exec-subtract-and-clear machine-state a b)
      5 (exec-multiply machine-state a b)
      6 (exec-divide machine-state a b)
      7 (exec-transfer-positive-modulus machine-state a b)
      0 (decode-control machine-state a b)
      (throw (ex-info (str "Cannot decode opcode " o) machine-state)))))

(defn step
  "Fetch and decode one order"
  [machine-state]
  (as->
    machine-state $
    (assoc $ :sending-clear false)
    (m/read-sending-address $ (:pc $))
    (decode $ (:sending-value $))))

(defn run
  "Run the machine until it reaches a terminating instruction"
  [machine-state]
  (loop [m machine-state]
    (if-not (:finished m)
      (recur (step m))
      (identity m))))

