(ns simulflow.core
  (:require [clansi.core :refer :all]
            [clojure.core.async :refer [go <! <!! put! alt! timeout go-loop chan close! mult tap] :as async]
            [clojure.java.io :refer [file]]
            [clojure.string :as string]
            [juxt.dirwatch :refer [watch-dir close-watcher]]
            [org.tobereplaced.nio.file :as nio]
            [plumbing.core :refer [map-vals for-map fnk]]
            [plumbing.fnk.pfnk :as pfnk]
            [plumbing.graph-async :refer [async-compile]]
            [schema.core :as s]
            [simulflow.async :refer [read-events batch-events]]
            [simulflow.config :refer [coerce-config!]]
            simulflow.wrappers))

(defn ts [] (System/currentTimeMillis))

(defn execute
  [<return <out queue [task-k v]]
  (let [start (ts)
        {:keys [fun last-modified]} (get queue task-k)]
    (go
      (put! <out [:started-task task-k])
      (try
        (let [state (<! (fun v))]
          (put! <return [task-k (or last-modified start) state]))
        (catch Exception e
          (put! <out [:exception task-k (.getMessage e) (- (ts) start)])
          (put! <return [task-k (or last-modified start)]))
        (finally
          (put! <out [:finished-task task-k (- (ts) start)]))))
    (assoc-in queue [task-k :active?] true)))

(defn- changed?
  [[_ {:keys [last last-modified]}]]
  (or (not last) (and last-modified (> last-modified last))))

(defn- dep-pending?
  [changed-or-active [_ v]]
  (some (partial contains? changed-or-active) (:deps v)))

(defn- should-run?
  [changed-or-active [_ {:keys [active?]} :as x]]
  (and (not active?)
       (changed? x)
       (not (dep-pending? changed-or-active x))))

(defn select-tasks
  [queue]
  (let [active-tasks (->> queue (filter :active?) keys)
        changed      (->> queue (filter changed?) keys)
        changed-or-active (into #{} (concat active-tasks changed))]
    (filter (partial should-run? changed-or-active) queue)))

(defn skip-event?
  [event]
  (or
    ; Backup files
    (re-matches #".*~$" (str (:file event)))))

(defn watch
  [dirs]
  (let [<events (chan)
        <stop (chan 1)
        watcher (apply watch-dir
                       (fn [v]
                         (when-not (skip-event? v)
                           (put! <events (nio/path (:file v)))))
                       dirs)]
    (go
      (<! <stop)
      (close-watcher watcher)
      (close! <events))
    [<events <stop]))

(defn- watched?
  "Test if given task is watching for changes in the given path."
  [event-path [_ {:keys [watch]}]]
  (some (partial nio/starts-with? event-path) watch))

(defn path->tasks
  "Get set of task-keys watching the given path."
  [event-path queue]
  (->> queue
       (filter (partial watched? event-path))
       keys
       set))

(defn start-tasks
  [<return <out queue]
  (reduce (partial execute <return <out)
          queue (select-tasks queue)))

(defn add-events
  [events queue]
  (reduce (fn [queue event]
            (let [tasks (path->tasks event queue)]
              (reduce (fn [queue task]
                        (assoc-in queue [task :last-modified] (ts)))
                      queue tasks)))
          queue events))

(defn task-ready
  [[k last-modified state] queue]
  (-> queue
      (update-in [k :state] #(or state %))
      (assoc-in [k :last] last-modified)
      (assoc-in [k :active?] false)))

(defn log-changes
  [<out <events]
  (go-loop []
    (let [v (<! <events)]
      (when v
        (put! <out [:file-changed v])
        (recur)))))

(defn main-loop
  "Creates a loop which will read <events as long as the channel is open.
   Executes tasks from events."
  [options <events]
  (let [<out (chan)
        events-mult (mult (batch-events <events 50))
        <events (tap events-mult (chan))
        <return (chan)]
    (log-changes <out (tap events-mult (chan)))
    (go-loop [queue (:flows options)]
      (let [queue (start-tasks <return <out queue)]
        (alt!
          <events ([v] (if v
                         (recur (add-events v queue))
                         (do
                           (put! <out [:exit (ts)])
                           (close! <out))))
          <return ([v] (recur (task-ready v queue))))))
    <out))

(defn get-watch-dirs
  "Given options map, create vector of :watch values."
  [options]
  (->> (:flows options)
       (map (comp :watch val))
       (apply concat)))

(defn start
  [project]
  (let [root (:root project)
        options (coerce-config! root (:simulflow project))
        watches (get-watch-dirs options)
        [<events <stop] (watch (map (comp file str) watches))
        <out (main-loop options <events)]
    [<out <stop]))


(def events {:init (style "Simulflow started" :green)
             :started-task (style ">>> %s" :green)
             :finished-task (style "<<< %s (%d ms)" :green)
             :file-changed (fn [root [files]]
                             (style (str "+++ " (clojure.string/join ", " (map (partial nio/relativize root) files))) :yellow))
             :exception (style "!!! %s: %s" :red)
             :exit (style "Good bye" :red)
             :task (str (ansi :blue) "%s: " (ansi :reset) "%s")})

(defn output [root [event & args]]
  (let [e (get events event)
        args (map (fn [v]
                    (if (keyword? v)
                      (name v)
                      v))
                  args)]
    (println (if (fn? e)
               (e root args)
               (apply format e args)))))

(defn plugin-loop [opts]
  (let [; Map lein task vectors to functions
        <task-out (chan)
        root (:root opts)
        opts
        (update-in opts [:simulflow :flows]
                   (fn [flows]
                     (into {} (map (fn [[k v]]
                                     [k (assoc v
                                               :fun (simulflow.wrappers/task-wrapper <task-out k)
                                               :state (simulflow.wrappers/task-init v))])
                                   flows))))
        [<out <stop] (start opts)]
    (<!! (go-loop []
           (alt!
             <out ([v] (when v
                         (output root v)
                         (recur)))
             <task-out ([v]
                        (output root v)
                        (recur)))))))
