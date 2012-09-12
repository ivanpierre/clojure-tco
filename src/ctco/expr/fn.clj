;;----------------------------------------------------------------------
;; File fn.clj
;; Written by Chris Frisz
;; 
;; Created 30 Mar 2012
;; Last modified  7 Sep 2012
;; 
;; Defines the FnBody record type for representing 'fn' expressions in the
;; Clojure TCO compiler.
;;
;; It implements the following protocols:
;;
;;      PAbstractK:
;;              Recursively applies abstract-k to the body expression,
;;              returning a new FnBody record.
;;
;;      PUnparse:
;;              Unparses (recursively) the syntax for the expression as
;;              `(fn ~fml* body).
;;
;;      PCpsTriv:
;;              Applies the CPS transformation to the body expression
;;              and extends the formal parameters list with an
;;              additional 'k' argument for the continuation.
;;
;;      PThunkify:
;;              Simply calls thunkify on the body and returns a new FnBody
;;              record with that body value. 
;;----------------------------------------------------------------------

(ns ctco.expr.fn
  (:require [ctco.expr
             cont thunk]
            [ctco.protocol :as proto]
            [ctco.util :as util])
  (:import [ctco.expr.cont
            Cont AppCont]
           [ctco.expr.thunk
            Thunk]))

(defrecord FnBody [fml* cmap bexpr*]
  proto/PAbstractK
    (abstract-k [this app-k]
      (proto/walk-expr this #(proto/abstract-k % app-k) nil))

  proto/PAlphaRename
    (alpha-rename [this old new]
      (if (some #{old} (:fml* this)) 
        this
        (letfn [(alpha-rename-reduce [expr* old new] 
                  (reduce 
                    (fn [acc bexpr]
                      (conj acc (proto/alpha-rename bexpr old new)))
                    []
                    expr*))]
          (alpha-rename-reduce
            (reduce 
              (fn [expr var]
                (let [new-var (util/new-var (gensym (:val var)))]
                  (FnBody. 
                    (replace {var new-var} (:fml* this))
                    (:cmap this)
                    (alpha-rename-reduce (:bexpr* this) var new-var))))
                this 
                (remove (set (:fml* this)) (proto/gather-free-vars (:body this))))
            old
            new))))

  proto/PGatherFreeVars
    (gather-free-vars [this]
      (remove (set (:fml* this))
              (reduce
               (fn [acc bexpr]
                 (concat acc (proto/gather-free-vars bexpr)))
               nil
               (:bexpr* this))))

  proto/PUnparse
    (unparse [this]
      `(~(vec (map proto/unparse (:fml* this)))
          ~@(let [cmap (:cmap this)]
              (if cmap (list cmap) '()))
          ~@(map proto/unparse (:bexpr* this))))
  
  proto/PCpsTriv
    (cps-triv [this]
      (let [k (util/new-var 'k)]
        (FnBody. (conj (:fml* this) k)
                 (:cmap this)
                 (vec (map #(condp extends? (type %)
                              proto/PCpsTriv (AppCont. k (proto/cps-triv %))
                              proto/PCpsSrs (proto/cps-srs % k))
                           (:bexpr* this))))))

  proto/PThunkify
    (thunkify [this]
      (proto/walk-expr this proto/thunkify nil))

  proto/PWalkable
    (walk-expr [this f _]
      (FnBody. (:fml* this) (:cmap this) (vec (map f (:bexpr* this))))))

(defrecord Fn [name body*]
  proto/PAbstractK
    (abstract-k [this app-k]
      (proto/walk-expr this #(proto/abstract-k % app-k) nil))

  proto/PAlphaRename
    (alpha-rename [this old new]
      (proto/walk-expr this #(proto/alpha-rename % old new) nil))

  proto/PGatherFreeVars
    (gather-free-vars [this]
      (reduce
       (fn [acc fn-body] (concat acc (proto/gather-free-vars fn-body)))
       nil
       (:body* this)))

  proto/PCpsTriv
    (cps-triv [this]
      (proto/walk-expr this proto/cps-triv nil))

  proto/PUnparse
    (unparse [this]
      (let [name (:name this)]
        `(fn ~@(if name (list (proto/unparse name)) '())
           ~@(map proto/unparse (:body* this)))))

  proto/PThunkify
    (thunkify [this]
      (proto/walk-expr this proto/thunkify nil))

  proto/PWalkable
    (walk-expr [this f _]
      (Fn. (:name this) (vec (map f (:body* this))))))
