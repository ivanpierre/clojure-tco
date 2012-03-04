;;----------------------------------------------------------------------
;; File tramp.clj
;; Written by Chris Frisz
;; 
;; Created  6 Feb 2012
;; Last modified  4 Mar 2012
;; 
;; Defines utilities for trampolining Clojure code.
;;----------------------------------------------------------------------

(ns clojure-tco.tramp
  (:use [clojure.core.match
         :only (match)])
  (:use [clojure-tco.util
         :only (reset-var-num new-var)]))

(defn simple-op?
  "Returns a boolean whether s is a simple-op"
  [s]
  (let [simple-ops '(+ - * / < <= = >= > zero? inc dec)]
    (some #{s} simple-ops)))

(defn alpha-rename
  "Performs alpha-renaming from old to new in expr. The expr argument
  is expected to be a sequence representing a Clojure expression.
  Returns expr with the proper renaming done."
  [old new expr]
  (match [expr]
    [(s :when symbol?)] (if (= s old) new s)
    [(:or true false)] expr
    [(n :when number?)] n
    [(['fn fml* body] :seq)]
    (cond
      (some #{old} fml*) `(~'fn ~fml* ~body)
      (some #{new} fml*)
      (let [alt (new-var new)
            FML* (replace {new alt} fml*)
            body-alt (alpha-rename new alt body)
            BODY-ALT (alpha-rename old new body)]
        `(~'fn ~FML* ~BODY-ALT))
      :else
      (let [BODY (alpha-rename new old body)]
        `(~'fn ~fml* ~BODY)))
    [(['if test conseq alt] :seq)]
    (let [TEST (alpha-rename old new test)
          CONSEQ (alpha-rename old new conseq)
          ALT (alpha-rename old new alt)]
      `(if ~TEST ~CONSEQ ~ALT))
    [([(op :when simple-op?) & opnd*] :seq)]
    (let [OPND* (map (fn [n] (alpha-rename old new n)) opnd*)]
      `(~op ~@OPND*))
    [([rator & rand*] :seq)]
    (let [RATOR (alpha-rename old new rator)
          RAND* (map (fn [n] (alpha-rename old new n)) rand*)]
      `(~RATOR ~@RAND*))
    :else (throw
           (Exception.
            (str "Invalid expression in alpha-rename: " expr)))))

(defn tramp-fn
  [th done]
  (loop [th th]
    (if (true? done)
        th
        (recur (th)))))

(defn tramp
  "Takes a sequence representing a Clojure expression (assumed to be
  CPSed) and returns the trampolined version of the expression. That
  is, it returns the expression such that it executes one step at a
  time."
  [expr tramp-fn]
  (letfn [(tramp-helper [expr done kv]
            (match [expr]
              [(:or true false)] expr
              [(n :when number?)] n
              [(s :when symbol?)] s
              [(['fn fml* body] :seq)]
              (let [done (new-var 'done)
                    kv (last body)
                    fnv (new-var 'fnv)
                    thunk (new-var 'th)
                    BODY (tramp-helper body done kv)] 
                `(fn ~fml*
                   (with-local-vars [~done false]
                     (let [~fnv (~'fn [fml*] ~BODY)
                           th (~fnv ~@fml*)]
                       (~tramp-fn th ~done)))))
              [(['if test conseq alt] :seq)]
              (let [TEST (tramp-helper test done kv)
                    CONSEQ (tramp-helper conseq done kv)
                    ALT (tramp-helper alt done kv)]
                `(if ~TEST ~CONSEQ ~ALT))
              [([(op :when simple-op?) & opnd*] :seq)]
              (let [OPND* (map
                           (fn [opnd] (tramp-helper opnd done kv))
                           opnd*)]
                `(~op ~OPND*))
              [([(:or 'defn 'defn-) name fml* body] :seq)]
              (let [deftype (first expr)
                    done (new-var 'done)
                    kv (last fml*)
                    fnv (new-var name)
                    thunk (new-var 'th)
                    body-rn (alpha-rename name fnv body)
                    BODY-RN (tramp-helper body-rn done kv)]
                `(~deftype ~name
                     ~fml*
                   (with-local-vars [~done false]
                     (letfn [(~fnv ~fml* ~BODY-RN)
                             th (fnv ~@fml*)]
                       (~tramp-fn th ~done)))))
              [([rator & rand*] :seq)]
              (if (= rator kv)
                  `(do (var-set ~done true) (~rator ~@rand*))
                  (let [RATOR (tramp-helper rator done kv)
                        RAND* (map
                               (fn [n] (tramp-helper n done kv))
                               rand*)]
                    `(~RATOR ~RAND*)))
              :else (throw
                     (Exception.
                      (str
                       "Invalid expression in tramp: "
                       expr)))))]
    (tramp-helper expr 'hukarz 'hukarz)))

(defn thunkify
  "Takes a sequence representing a Clojure expression, assumed to be
  in CPS, and returns the expression such that any function returns a
  function of no arguments (called a thunk). Invoking the thunk
  either returns the value as it would have been produced by the
  original expression or another thunk. Any returned thunks can be
  invoked in turn until a value is produced. This can be seen as
  performing the computation in steps and is useful in conjunction
  with trampolining."
  [expr]
  (match [expr]
    [(:or true false)] expr
    [(n :when number?)] n
    [(s :when symbol?)] s
    [(['fn fml* body] :seq)]
      (let [BODY (thunkify body)]
        `(~'fn ~fml* (~'fn [] ~BODY)))
    [(['if test conseq alt] :seq)]
      (let [TEST (thunkify test)
            CONSEQ (thunkify conseq)
            ALT (thunkify alt)]
        `(~'if ~TEST ~CONSEQ ~ALT))
    [([(op :when simple-op?) & opnd*] :seq)]
      (let [OPND* (map thunkify opnd*)]
        `(~op ~@OPND*))
    [([(:or 'defn 'defn-) name fml* body] :seq)]
      (let [deftype (first expr)
            BODY (thunkify body)]
        `(~deftype ~name ~fml* (~'fn [] ~BODY)))
    [([rator & rand*] :seq)]
    ;; Assuming that the expression is in CPS, the rator will either
    ;; be a variable bound to a procedure that has been or will be
    ;; thunkified or an anonymous fn.
    ;; The rand will only be simple values (boolean, number, symbol)
    ;; or an anonymous fn. These also need to be thunkified.
    ;; The final argument should be the continuation argument, and
    ;; we don't actually need to thunkify that, so we skip it.
    (let [rand-bl* (butlast rand*)
          k (last rand*)
          RATOR (thunkify rator)
          RAND-BL* (map thunkify rand-bl*)]
      `(~RATOR ~@RAND-BL* ~k))
    :else (throw
           (Exception. (str "Invalid expression: " expr)))))
