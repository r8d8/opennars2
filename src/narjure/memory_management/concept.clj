(ns narjure.memory-management.concept
  (:require
    [co.paralleluniverse.pulsar
     [core :refer :all]
     [actors :refer :all]]
    [taoensso.timbre :refer [debug info]]
    [narjure.global-atoms :refer :all]
    [narjure.bag :as b]
    [clojure.core.unify :refer [unifier]]
    [narjure.debug-util :refer :all]
    [narjure.control-utils :refer :all]
    [narjure.defaults :refer :all]
    [nal.term_utils :refer [syntactic-complexity]]
    [narjure.memory-management.local-inference.local-inference-utils :refer [get-task-id get-tasks]]
    [narjure.memory-management.local-inference.belief-processor :refer [process-belief]]
    [narjure.memory-management.local-inference.goal-processor :refer [process-goal]]
    [narjure.memory-management.local-inference.quest-processor :refer [process-quest]]
    [narjure.memory-management.local-inference.question-processor :refer [process-question]]
    [nal.deriver.truth :refer [w2c t-or t-and confidence frequency expectation revision]]
    [nal.deriver.projection-eternalization :refer [project-eternalize-to]])
  (:refer-clojure :exclude [promise await]))

(def display (atom '()))
(def search (atom ""))

(defn concept-quality []
  (:quality ((:elements-map @c-bag) (:id @state))))

(defn forget-termlinks []
  (while (> (count (:termlinks @state)) concept-max-termlinks)
    (let [worst (apply min-key (comp first second) (:termlinks @state))]
      (set-state! (assoc @state :termlinks (dissoc (:termlinks @state) (first worst))))))
  ;apply weak forget also:
  #_(set-state!
    (assoc @state :termlinks
                  (apply merge (for [[tl [p d]] (:termlinks @state)]
                                 {tl [(* p d) d]}))))
  )

(defn add-termlink [tl strength]
  (set-state! (assoc @state :termlinks (assoc (:termlinks @state)
                                         tl strength)))
  (forget-termlinks))

(defn truth-to-quality [t]
  (let [exp (expectation t)
        positive-truth-bias 0.75]
        (max exp (* (- 1.0 exp) positive-truth-bias))))

(defn link-feedback-handler
  [from [_ [derived-task belief-concept-id]]]                       ;this one uses the usual priority durability semantics
  (try
    ;TRADITIONAL BUDGET INFERENCE (BLINK PART)
    (let [complexity (if (:truth derived-task) (syntactic-complexity belief-concept-id) 1.0)
          qual (if (:truth derived-task)
                    (truth-to-quality (:truth derived-task))
                    (w2c 1.0))
          quality (/ qual complexity)
          #_[target _] #_(b/get-by-id @c-bag belief-concept-id)
          #_[source _] #_(b/get-by-id @c-bag (:id @state))
          [result-concept _]  (b/get-by-id @c-bag (:statement derived-task))
          activation (:priority result-concept) #_(:priority result-concept) #_(Peis)  #_(t-and (:priority target) (:priority source)) #_("1.7.0")
          [p d] ((:termlinks @state) belief-concept-id)]
      (when (and p d qual)
        (add-termlink belief-concept-id [(t-or p (t-or quality activation))
                                         (t-or d quality)]))
      )
    (catch Exception e (println "fail"))))

(defn max-statement-confidence-projected-to-now [task-type]
  (let [li (filter (fn [z] (and (= (:task-type (:task (second z))) task-type)
                                (= (:statement (:task (second z))) (:id @state))))
                   (:elements-map (:tasks @state)))]
    (if (= (count li) 0)
      {:truth [0.5 0.0]}
      (project-eternalize-to
        (deref nars-time)
        (:task (second (apply max-key (fn [y]
                                        (second (:truth (:task (second y)))))
                              li)))
        (deref nars-time)))))

(defn update-concept-budget []
  "Update the concept budget"
  (let [concept-state @state
        tasks (:priority-index (:tasks concept-state))      ; :priority-index ok here
        priority-sum (round2 3 (reduce t-or (for [x tasks] (:priority x))))
        quality-rescale 0.1
        el {:id       (:id @state)
            :priority priority-sum
            :quality  (round2 3 (Math/max (concept-quality) (* quality-rescale priority-sum)))
            :ref      @self
            :strongest-belief-about-now (max-statement-confidence-projected-to-now :belief)
            ;:strongest-desire-about-now
            }]
    ;update c-bag directly instead of message passing
    (swap! c-bag b/add-element el)))

(defn task-handler
  [from [_ [task]]]
  (debuglogger search display ["task processed:" task])
  ; check observable and set if necessary
  (when-not (:observable @state)
    (let [{:keys [occurrence source]} task]
      (when (and (not= occurrence :eternal) (= source :input) (= (:statement task) (:id @state)))
       (set-state! (assoc @state :observable true)))))

  (case (:task-type task)
    :belief (process-belief state task 0)
    :goal (process-goal state task 0)
    :question (process-question state task)
    :quest (process-quest state task))

  (try
    (let [concept-state @state
          task-bag (:tasks concept-state)]
      (let [newtermlinks (merge (apply merge (for [tl (filter (fn [z] (not= z (:id @state))) (:terms task))] ;prefer existing termlinks strengths
                                               {tl  termlink-default-budget ;priority durability model testing now!!
                                                #_[1.0 termlink-single-sample-evidence-amount]})) (:termlinks concept-state))]
        (set-state! (merge concept-state {:termlinks (select-keys newtermlinks
                                                                  (filter #(b/exists? @c-bag %) (keys newtermlinks))) ;only these keys which exist in concept bag
                                          }))))
    (catch Exception e (debuglogger search display (str "task add error " (.toString e)))))
  (forget-termlinks)
  (update-concept-budget))

(defn unifies [b a]
  (= a (unifier a b)))

(defn qu-var-transform [term]
  (if (coll? term)
    (if (= (first term) 'qu-var)
      (symbol (str "?" (second term)))
      (apply vector (for [x term]
                      (qu-var-transform x))))
    term))

(defn question-unifies [question solution]
  (unifies (qu-var-transform question) solution))

(defn solution-update-handler
  ""
  [from [_ oldtask newtask]]
  (try
    (let [concept-state @state
          task (first (filter (fn [a] (let [it (:task (second a))]
                                           (and (= (:statement it) (:statement oldtask))
                                                (= (:occurrence it) (:occurrence oldtask))
                                                (= (:task-type it) (:task-type oldtask))
                                                (= (:solution it) (:solution oldtask)))))
                                 (:elements-map (:tasks concept-state))))]
      (println "in solution-update-handler")
      (when (not= nil task)
        (let [[_ bag2] (b/get-by-id (:tasks concept-state) (get-task-id task)) ;todo merge old and new tasks?
              bag3 (b/add-element bag2 {:id (get-task-id newtask) :priority (first (:budget newtask)) :task newtask})]
          (set-state! (assoc concept-state :tasks bag3)))))
    (catch Exception e (debuglogger search display (str "solution update error " (.toString e))))))

#_(defn update-termlink [tl]                                  ;term
 (let [prio-me (:priority ((:elements-map @c-bag) (:id @state)))
       [p d] ((:termlinks @state) tl)
       prio-other (:priority ((:elements-map @c-bag) tl))
       association (t-and prio-me prio-other)
       disassocation (t-and prio-me (- 1.0 prio-other))
       frequency (+ 0.5 (/ (- association disassocation) 2.0))
       newstrength [(+ p (* termlink-context-adaptations-speed frequency)) d]]
   (add-termlink tl newstrength)))

(defn belief-request-handler
  ""
  [from [_ [task-concept-id termlink-strength task]]]
  ;todo get a belief which has highest confidence when projected to task time
  (try                                                      ;update termlinks at first
    #_(update-termlink (:statement task))          ;task concept here
    (catch Exception e (debuglogger search display (str "belief side termlink strength error " (.toString e)))))
  (try (let [tasks (get-tasks state)
             beliefs (filter #(and (= (:statement %) (:id @state))
                                   (= (:task-type %) :belief)) tasks)
             projected-belief-tuples (map (fn [z] [z (project-eternalize-to (:occurrence task) z @nars-time)]) beliefs)]
         (if (not-empty projected-belief-tuples)
           ;(println projected-beliefs)
           ;(println "not empty pb: " projected-beliefs)
           (let [#_[not-projected-belief belief] #_(apply max-key (comp confidence second) projected-belief-tuples)]
             #_(cast! (:general-inferencer @state) [:do-inference-msg [task not-projected-belief]])
             (doseq [belief beliefs]
               (debuglogger search display ["selected belief:" belief "§"])
               (cast! (:inference-request-router @state) [:do-inference-msg [task-concept-id (:id @state) termlink-strength task belief]])
             (try
               ;1. check whether belief matches by unifying the question vars in task
               (when (and (= (:task-type task) :question)
                          (some #{'qu-var} (flatten (:statement task)))
                          (question-unifies (:statement task) (:statement belief)))
                 ;2. if it unifies, check whether it is a better solution than the solution we have
                 (let [answer-fqual (fn [answer] (if (= nil answer)
                                                   0
                                                   (/ (expectation (:truth answer)) (syntactic-complexity (:statement answer)))))
                       newqual (answer-fqual (project-eternalize-to (:occurrence task) belief @nars-time))
                       oldqual (answer-fqual (project-eternalize-to (:occurrence task) (:solution task) @nars-time))] ;PROJECT!!
                   (when (> newqual oldqual)
                     ;3. if it is a better solution, set belief as solution of task
                     (let [budget (:budget task)
                           new-prio (* (- 1.0 (expectation (:truth belief))) (first budget))
                           new-budget [new-prio (second budget) (nth budget 2)]
                           newtask (assoc task :solution belief :priority new-prio :budget new-budget)]
                       ;4. print our result
                       (output-task [:answer-to (str (narsese-print (:statement task)) "?")] (:solution newtask))
                       ;5. send answer-update-msg OLD NEW to the task concept so that it can remove the old task bag entry
                       ;and replace it with the one having the better solution. (reducing priority here though according to solution before send)
                       (when-let [{c-ref :ref} ((:elements-map @c-bag) (:statement task))]
                         (cast! c-ref [:solution-update-msg task newtask]))))))
               (catch Exception e (debuglogger search display (str "what-question error " (.toString e))))))

             ))
         ;dummy? belief as "empty" termlink belief selection for structural inference
         (let [belief {:statement (:id @state) :task-type :question :occurrence @nars-time :evidence '()}]
           (debuglogger search display ["selected belief:" belief "§"])
           (cast! (:inference-request-router @state) [:do-inference-msg [task-concept-id (:id @state) termlink-strength task belief]]))
         )
       (catch Exception e (debuglogger search display (str "belief request error " (.toString e))))))

(defn forget-task [el last-forgotten]
  (let [task (:task el)
       budget (:budget task)
       lambda (/ (- 1.0 (second budget)) decay-rate)
       fr (Math/exp (* -1.0 (* lambda (- @nars-time last-forgotten))))
       new-priority (max (round2 4 (* (:priority el) fr))
                         (/ (concept-quality) (+ 1.0 (b/count-elements (:tasks @state))))
                         (nth budget 2))                ;dont fall below 1/N*quality
       new-budget [new-priority (second budget) (nth budget 2)]]
   (let [updated-task (assoc task :budget new-budget)]
     (assoc el :priority new-priority
               :task updated-task))))

(defn forget-tasks []
  (let [tasks (:elements-map (:tasks @state))
        last-forgotten (:last-forgotten @state)]
    (set-state! (assoc @state :tasks (b/default-bag max-tasks)))
    (doseq [[id el] tasks]                       ;{ id {:staement :type :occurrence}
      (let [el' (forget-task el last-forgotten)]
        ;(println (str "forgetting: " (get-in el' [:task :statement])))
        (set-state! (assoc @state :tasks (b/add-element (:tasks @state) el')))))
    (set-state! (assoc @state :last-forgotten @nars-time))))

(defn inference-request-handler
  ""
  [from message]
  (let [concept-state @state
        task-bag (:tasks concept-state)
        concept-priority (first (:budget @state))]
    ; and sending budget update message to concept mgr
    (try
      (when (pos? (b/count-elements task-bag))
        (let [[el] (b/lookup-by-index task-bag (selection-fn task-bag))]
          (try
            (forget-tasks)
            (update-concept-budget)
            (catch Exception e (debuglogger search display (str "forget/update error " (.toString e)))))
          (debuglogger search display ["selected inference task:" el])
          ;now search through termlinks, get the endpoint concepts, and form a bag of them
          (let [initbag (b/default-bag concept-max-termlinks)
                resbag (reduce (fn [a b] (b/add-element a b)) initbag (for [[k v] (:termlinks @state)]
                                                                        {:priority (t-or (first v)
                                                                                      (:priority (first (b/get-by-id @c-bag k))))
                                                                         :id       k}))
                ;now select an element from this bag
                [beliefconcept bag1] (b/get-by-index resbag (selection-fn resbag))]
            ;and create a belief request message
            (set-state! (assoc @state :termlinks
                                      (assoc (:termlinks @state)
                                        (:id beliefconcept)
                                        (let [[p d] ((:termlinks @state) (:id beliefconcept))] ;apply forgetting for termlinks only on selection
                                          [(* p d) d]))))
            (when-let [{c-ref :ref} ((:elements-map @c-bag) (:id beliefconcept))]
              (try
                #_(update-termlink (:id beliefconcept))          ;belief concept here
                (catch Exception e (debuglogger search display (str "task side termlink strength error " (.toString e)))))
              (cast! c-ref [:belief-request-msg [(:id @state) ((:termlinks @state) (:id beliefconcept)) (:task el)]])
              ))))
      (catch Exception e (debuglogger search display (str "inference request error " (.toString e)))))
    )
  )

(defn termlink-create-handler
  ""
  [from [_ [term]]]
  ;todo get a belief which has highest confidence when projected to task time

  (when-not ((:termlinks @state) term)
    (set-state! (assoc @state :termlinks (assoc (:termlinks @state) term concept-selection-introduced-termlink-default-budget)))))

(defn concept-state-handler
  "Sends a copy of the actor state to requesting actor"
  [from _]
  (let [concept-state @state]
    (cast! from [:concept-state-msg concept-state])))

(defn set-concept-state-handler
  "set concept state to value passed in message"
  [from [_ new-state]]
  (set-state! (merge @state new-state)))

(defn shutdown-handler
  "Processes :shutdown-msg and shuts down actor"
  [from msg]
  (set-state! {})
  (unregister!)
  (shutdown!))

(defn initialise
  "Initialises actor: registers actor and sets actor state"
  [name]
  (set-state! {:id                 name
               :quality            0.0
               :tasks              (b/default-bag max-tasks)
               :termlinks          {}
               :anticipation       nil
               :concept-manager    (whereis :concept-manager)
               :inference-request-router (whereis :inference-request-router)
               :last-forgotten     @nars-time
               :observable false}))

(defn msg-handler
  "Identifies message type and selects the correct message handler.
   if there is no match it generates a log message for the unhandled message"
  [from [type :as message]]
  (debuglogger search display message)
  (case type
    :termlink-create-msg (termlink-create-handler from message)
    :task-msg (task-handler from message)
    :link-feedback-msg  (link-feedback-handler from message)
    :belief-request-msg (belief-request-handler from message)
    :inference-request-msg (inference-request-handler from message)
    :concept-state-request-msg (concept-state-handler from message)
    :set-concept-state-msg (set-concept-state-handler from message)
    :solution-update-msg (solution-update-handler from message)
    :shutdown (shutdown-handler from message)
    (debug (str "unhandled msg: " type)))
  (when (pos? debug-messages)
    ;(reset! lense-anticipations (:anticipation @state))
    (swap! lense-taskbags
           (fn [dic]
             (assoc dic (:id @state) (:tasks @state))))
    (swap! lense-termlinks
           (fn [dic]
             (assoc dic (:id @state) (:termlinks @state)))))
  )

(defn concept [name]
  (gen-server
    (reify Server
      (init [_] (initialise name))
      (terminate [_ cause] #_(info (str aname " terminated.")))
      (handle-cast [_ from id message] (msg-handler from message)))))