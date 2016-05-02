(ns nal.deriver.backward-rules
  (:require [nal.deriver.key-path :refer [rule-path]]
            [clojure.string :as s]))

;http://pastebin.com/3zLX7rPx
(defn allow-backward?
  "Return true if rule allows backward inference."
  [{:keys [conclusions]}]
  (some #{:allow-backward} (:post (first conclusions))))

(defn has-prefix? [prefix cond]
  (when (keyword? cond)
    (s/starts-with? (str cond) prefix)))

(defn not-equal? [cond]
  (when (coll? cond)
    (= :!= (first cond))))

(defn check-not-equal [pre conclusion]
  (if (some not-equal? pre)
    (let [pre (remove not-equal? pre)]
      (if (and (coll? conclusion) (= 3 (count conclusion)))
        (let [[_ t1 t2] conclusion]
          (conj pre (list :!= t1 t2)))
        pre))
    pre))

(defn expand-backward-rules
  "If rule allows backward inference it will be expanded to three rules,
  where first one is the rule itself, and rest rules will be generated by
  swapping conclusion with every premise."
  [{:keys [p1 p2 conclusions pre] :as rule}]
  (mapcat (fn [{:keys [conclusion post] :as fc}]
            (let [post (reduce #(remove (partial has-prefix? %2) %1)
                               post [":t/" ":d/"])]
              (conj (map
                      (fn [r] (update r :pre conj :question?))
                      [(assoc rule :conclusions [(assoc fc :post post)])
                       (assoc rule :p1 conclusion
                                   :conclusions [{:conclusion p1
                                                  :post       post}]
                                   :full-path (rule-path conclusion p2)
                                   :pre (check-not-equal pre p1))
                       (assoc rule :p2 conclusion
                                   :conclusions [{:conclusion p2
                                                  :post       post}]
                                   :full-path (rule-path p1 conclusion)
                                   :pre (check-not-equal pre p2))])
                    rule)))
          conclusions))