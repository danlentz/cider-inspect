(ns inspector.inspect
  "Adapted from swank-clojure and javert"
  (:import (java.lang.reflect Field)))

;;
;; Navigating Inspector State
;;

(declare inspect-render inspect-value)

(defn- reset-index [inspector]
  (merge inspector {:counter 0 :index []}))

(defn clear
  "Clear an inspector's state"
  [inspector]
  (reset-index {:value nil :stack [] :rendered '()}))

(defn fresh 
  "Return an empty inspector "
  []
  (clear {}))

(defn start
  "Put a new value onto the inspector stack"
  [inspector value]
  (println value)
  (-> (clear inspector)
      (inspect-render value)))

(defn up
  "Pop the stack and re-render an earlier value"
  [inspector]
  (let [stack (:stack inspector)]
    (if (empty? stack)
      inspector
      (-> inspector
          (inspect-render (last stack))
          (update-in [:stack] pop)))))

(defn down
  "Drill down to an indexed object referred to by the previously
   rendered value"
  [inspector idx]
  (let [new (get (:index inspector) idx)
        val (:value inspector)]
    (-> (update-in inspector [:stack] conj val)
        (inspect-render new))))
               

(declare inspector-value-string)

;;
;; Render values onto the inspector's current state
;;
;; Good for method extenders to use

(defn inspect-value [value]
;;  (cond (some #(% value) [number? string? symbol? keyword?])
  (pr-str value))

(defn render-onto [inspector coll]
  (update-in inspector [:rendered] concat coll))

(defn render [inspector & values]
  (render-onto inspector values))

(defn render-ln [inspector & values]
  (render-onto inspector (concat values '((:newline)))))

(defn render-value [inspector value]
  (let [{:keys [counter]} inspector
        expr `(:value ~(inspect-value value) ~counter)]
    (-> inspector
        (update-in [:index] conj value)
        (update-in [:counter] inc)
        (update-in [:rendered] concat (list expr)))))

(defn render-labeled-value [inspector label value]
  (-> inspector
      (render label ": ")
      (render-value value)
      (render-ln)))
  
(defn render-indexed-values [inspector obj]
  (reduce (fn [ins [idx val]]
            (-> ins
                (render "  " idx ". ") 
                (render-value val)
                (render '(:newline))))
          inspector
          (map-indexed list obj)))

(defn render-map-values [inspector mappable]
  (reduce (fn [ins [key val]]
            (-> ins
                (render "  ")
                (render-value key)
                (render " = ")
                (render-value val)
                (render '(:newline))))
          inspector
          mappable))

(defn render-meta-information [inspector obj]
  (if (seq (meta obj))
    (-> inspector
        (render-ln "Meta Information: ")
        (render-map-values (meta obj)))
    inspector))


;; Inspector multimethod
(defn known-types [ins obj]
  (cond
   (map? obj) :map
   (vector? obj) :vector
   (var? obj) :var
   (string? obj) :string
   (seq? obj) :seq
   (instance? Class obj) :class
   (instance? clojure.lang.Namespace obj) :namespace
   (instance? clojure.lang.ARef obj) :aref
   (.isArray (class obj)) :array
   :default (or (:inspector-tag (meta obj))
                (type obj))))

(defmulti inspect #'known-types)

(defmethod inspect :map [inspector obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-labeled-value "Count" (count obj))
      (render-meta-information obj)
      (render-ln "Contents: ")
      (render-map-values obj)))

(defmethod inspect :vector [inspector obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-labeled-value "Count" (count obj))
      (render-meta-information obj)
      (render-ln "Contents: ")
      (render-indexed-values obj)))

(defmethod inspect :array [inspector ^"[Ljava.lang.Object;" obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-labeled-value "Count" (alength obj))
      (render-labeled-value "Component Type" (.getComponentType (class obj)))
      (render-ln "Contents: ")
      (render-indexed-values obj)))

(defmethod inspect :var [inspector ^clojure.lang.Var obj]
  (let [header-added
        (-> inspector
            (render-labeled-value "Class" (class obj))
            (render-meta-information obj))]
    (if (.isBound obj)
      (-> header-added
          (render "Value: ")
          (render-value (var-get obj)))
      header-added)))

(defmethod inspect :string [inspector ^java.lang.String obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render "Value: " (pr-str obj))))

(defmethod inspect :seq [inspector obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-meta-information obj)
      (render-ln "Contents: ")
      (render-indexed-values obj)))

(defmethod inspect :default [inspector obj]
  (let [^"[Ljava.lang.reflect.Field;" fields (. (class obj) getDeclaredFields)
        names (map #(.getName ^Field %) fields)
        get (fn [^Field f]
              (try (.setAccessible f true)
                   (catch java.lang.SecurityException e))
              (try (.get f obj)
                   (catch java.lang.IllegalAccessException e
                     "Access denied.")))
        vals (map get fields)]
    (-> inspector
        (render-labeled-value "Type" (class obj))
        (render-labeled-value "Value" (pr-str obj))
        (render-ln "---")
        (render-ln "Fields: ")
        (render-map-values (zipmap names vals)))))

(defn- inspect-class-section [inspector obj section]
  (let [method (symbol (str ".get" (name section)))
        elements (eval (list method obj))]
    (if (seq elements)
      `(~(name section) ": " (:newline)
        ~@(mapcat (fn [f] `("  " (:value ~f) (:newline))) elements)))))

(defmethod inspect :class [inspector ^Class obj]
  (apply concat (interpose ['(:newline) "--- "]
                           (cons `("Type: " (:value ~(class obj)) (:newline))
                                 (for [section [:Interfaces :Constructors
                                                :Fields :Methods]
                                       :let [elements (inspect-class-section
                                                       inspector obj section)]
                                       :when (seq elements)]
                                   elements)))))

(defmethod inspect :aref [inspector ^clojure.lang.ARef obj]
  `("Type: " (:value ~(class obj)) (:newline)
    "Value: " (:value ~(deref obj)) (:newline)))

(defn ns-refers-by-ns [inspector ^clojure.lang.Namespace ns]
  (group-by (fn [^clojure.lang.Var v] (. v ns))
            (map val (ns-refers ns))))

(defmethod inspect :namespace [inspector ^clojure.lang.Namespace obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-labeled-value "Count" (count (ns-map obj)))
      (render-ln "---")
      (render-ln "Refer from: ")
      (render-map-values (ns-refers-by-ns obj))
      (render-labeled-value "Imports" (ns-imports obj))
      (render-labeled-value "Interns" (ns-interns obj))))


;;
;; Entry point to inspect a value and get the serialized rep 
;;
(defn inspect-render [inspector value]
  (println inspector value)
  (-> (reset-index inspector)
      (assoc :rendered [])
      (assoc :value value)
      (inspect value)))

;; Get the string serialization of the rendered sequence
(defn serialize-render [inspector]
  (pr-str (:rendered inspector)))


;; Get a human readable printout of rendered sequence
(defmulti inspect-print-component first)

(defmethod inspect-print-component :newline [_]
  (prn))

(defmethod inspect-print-component :value [[_ & xs]]
  (print (str (first xs))))

(defmethod inspect-print-component :default [x]
  (print x))

(defn inspect-print [x]
  (doseq [component (:rendered (inspect-render (fresh) x))]
    (inspect-print-component component)))

