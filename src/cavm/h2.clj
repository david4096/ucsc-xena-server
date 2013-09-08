(ns cavm.h2
  (:require [korma.db :as kdb])
  (:require [korma.config :as kconf])
  (:require [org.clojars.smee.binary.core :as binary])
  (:require [clojure.java.io :as io])
  (:use [clj-time.format :only (formatter unparse)])
  (:use [cavm.hashable :only (ahashable)])
  (:use korma.core))

;(def db {:classname "org.h2.Driver"
;         :subprotocol "h2"
;         :subname "file:///data/TCGA/craft/h2/cavm.h2"})

;
; Table definitions.
;

(def float-size 4)
(def bin-size 100)
(def score-size (* float-size bin-size))

; Note H2 has one int type: signed, 4 byte. Max is approximately 2 billion.
(def probes-table ["CREATE TABLE IF NOT EXISTS `probes` (
                   `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                   `eid` INT NOT NULL,
                   FOREIGN KEY (eid) REFERENCES `experiments` (`id`),
                   `bin` INT(11),
                   `chrom` VARCHAR(255),
                   `chromStart` INT,
                   `chromEnd` INT,
                   `name` VARCHAR(255))"
                   "CREATE INDEX IF NOT EXISTS probe_name ON probes (eid, name)"
                   "CREATE INDEX IF NOT EXISTS probe_chrom ON probes (eid, chrom, bin)"])

(def scores-table [(format "CREATE TABLE IF NOT EXISTS `scores` (
                  `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `expScores` VARBINARY(%d) NOT NULL)" score-size)])

(def join-table ["CREATE TABLE IF NOT EXISTS `joins` (
                 `pid` INT,
                 `i` INT,
                 `sid` INT)"
                 "CREATE INDEX IF NOT EXISTS index_pid ON joins (`pid`, `i`)"])

(def cohorts-table ["CREATE TABLE IF NOT EXISTS `cohorts` (
                   `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                   `name` VARCHAR(2000) NOT NULL UNIQUE)"
                   "CREATE INDEX IF NOT EXISTS index_name ON cohorts (`name`)"])

(def samples-table ["CREATE TABLE IF NOT EXISTS `samples` (
                    `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    `cid` INT NOT NULL,
                    FOREIGN KEY (cid) REFERENCES `cohorts` (`id`),
                    `sample` VARCHAR(1000) NOT NULL)"
                    "CREATE INDEX IF NOT EXISTS index_cid ON samples (`cid`)"])

; XXX What should max file name length be?
(def experiments-table ["CREATE TABLE IF NOT EXISTS `experiments` (
                       `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                       `file` VARCHAR(2000) NOT NULL UNIQUE,
                       `time` TIMESTAMP NOT NULL,
                       `hash` VARCHAR(40) NOT NULL,
                       `cid` INT NOT NULL,
                        `loaded` BOOLEAN NOT NULL,
                       FOREIGN KEY (cid) REFERENCES `cohorts` (`id`))"])

(def exp-samples-table ["CREATE TABLE IF NOT EXISTS `exp_samples` (
                        `eid` INT NOT NULL,
                        FOREIGN KEY (eid) REFERENCES `experiments` (`id`),
                        `sid` INT NOT NULL,
                        FOREIGN KEY (sid) REFERENCES `samples` (`id`),
                        `i` INT NOT NULL,
                        PRIMARY KEY(`eid`, `sid`))"])


;
; Table models
;

(declare probes scores experiments samples exp_samples)

(defentity cohorts
  (has-many experiments {:fk :cid})
  (has-many samples {:fk :cid}))

(defentity experiments
  (belongs-to cohorts {:fk :cid})
  (has-many exp_samples {:fk :eid})
  (has-many probes))

(defentity samples
  (has-many exp_samples {:fk :sid})
  (belongs-to cohorts {:fk :cid}))

(defentity exp_samples
  (belongs-to samples {:fk :sid})
  (belongs-to experiments {:fk :eid}))

(declare score-decode)

(defentity probes
  (many-to-many scores :joins  {:lfk 'pid :rfk 'sid})
  (belongs-to experiments {:fk :eid})
  (transform (fn [{scores :EXPSCORES :as v}]
               (if scores
                 (assoc v :EXPSCORES (float-array (score-decode scores)))
                 v))))

(defentity scores
  (many-to-many probes :joins  {:lfk 'sid :rfk 'pid}))

(defentity joins)

;
;
;

(defn- probes-in-exp [exp]
  (subselect probes (fields :id) (where {:eid exp})))

(defn- scores-with-probes [probes]
  (subselect joins (fields :sid) (where {:pid [in probes]})))

(defn- clear-by-exp [exp]
  (let [p (probes-in-exp exp)]
    (delete exp_samples (where {:eid exp}))
    ; XXX This is a monster delete. Should limit it by cohort, or do it off-line.
    (delete samples  (where (not (in :id (subselect exp_samples (fields :sid) (modifier "DISTINCT"))))))
    (delete scores (where {:id [in (scores-with-probes p)]}))
    (delete joins (where {:pid [in p]}))
    (delete probes (where {:id [in p]}))))

; Merge cohort, returning id
(defn- merge-cohort [cohort]
  (exec-raw ["MERGE INTO cohorts(name) KEY(name) VALUES (?)" [cohort]])
  (let [[{cid :ID}] (select cohorts (where {:name cohort}))]
    cid))

; Delete experiment data & update experiment record.
(defn- merge-exp [file timestamp filehash cohort]
  (exec-raw ["MERGE INTO experiments (file, time, hash, cid, loaded) KEY(file) VALUES (?, ?, ?, ?, ?)"
             [file timestamp filehash cohort false]])
  (let [[{exp :ID}] (select experiments (where {:file file}))]
    (clear-by-exp exp)
    exp))

; Coerce this sql fn name to a keyword so we can reference it.
(def KEY-ID (keyword "SCOPE_IDENTITY()"))

; Insert probe & return id
; expand this for chrom info later.
(defn- insert-probe [exp name]
  (let [{pid KEY-ID} (insert probes (values {:eid exp :name name}))]
    pid))

(defn- insert-scores [slist]
  (let [{sid KEY-ID} (insert scores (values {:expScores slist}))]
    sid))

(defn- insert-join [pid i sid]
  (insert joins (values {:pid pid :i i :sid sid})))

;
; Binary buffer manipulation.
;

(def codec-length (memoize (fn [len]
                             (binary/repeated :float-le :length len))))

(defn- codec [blob]
  (codec-length (/ (count blob) float-size)))

(defn- score-decode [blob]
  (binary/decode (codec blob) (io/input-stream blob)))

(defn- score-encode [slist]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (binary/encode (codec-length (count slist)) baos slist)
    (.toByteArray baos)))

; Pick elements of float array, by index.
(defn apick-float [^floats a idxs]
 (let [ia (float-array idxs)]
  (amap ia i ret (aget a i))))

(defn ashuffle-float [mapping ^floats out ^floats in]
  (dorun (map #(aset out (% 1) (aget in (% 0))) mapping))
  out)

(defn bytes-to-floats [^bytes barr]
  (let [bb (java.nio.ByteBuffer/allocate (alength barr))
        fb (.asFloatBuffer bb)
        out (float-array (quot (alength barr) 4))]
    (.put bb barr)
    (.get fb out)
    out))

(defn floats-to-bytes [^floats farr]
  (let [bb (java.nio.ByteBuffer/allocate (* (alength farr) 4))
        fb (.asFloatBuffer bb)]
    (.put fb farr)
    (.array bb)))

;
;
;
(defn- insert-scores-block [block]
  (insert-scores (.ba block)))

(defn- insert-unique-scores-fn []
  (memoize insert-scores-block))

(defn- load-probe [insert-scores-fn exp prow]
  (let [pid (insert-probe exp (:probe (meta prow)))
        blocks (partition-all bin-size prow)
        indx (range (count blocks))]
    (dorun (map (fn [block i]
           (let [sid (insert-scores-fn (ahashable (score-encode block)))]
             (insert-join pid i sid))) blocks indx))))

(def ROWS 100)

; insert matrix, updating scores, probes, and joins tables
(defn- load-exp-matrix [exp matrix]
  (let [loadp (partial load-probe (insert-unique-scores-fn) exp)]
    (dorun (map #(kdb/transaction (dorun (map loadp %))) (partition ROWS ROWS nil matrix)))))

(defn- cohort-sample-list [cid sample-list]
  (select samples (fields :id :sample)
          (where {:cid cid :sample [in sample-list]})))

; hash seq of maps by given key
(defn- hash-by-key [k s]
  (zipmap (map #(% k) s) s))

; return values in order of the given keys
(defn- in-order [order hm]
  (map hm order))

; return value of the given key for all objects in seq
(defn- select-val [k hms]
  (map #(% k) hms))

(defn- sample-ids [cid sample-list]
  (->> sample-list
       (cohort-sample-list cid)
       (hash-by-key :SAMPLE)
       (in-order sample-list)
       (select-val :ID)))

(defn- load-samples [cid sample-list]
  (dorun
    (map #(exec-raw ["MERGE INTO samples (cid, sample) KEY(cid, sample) VALUES (?, ?)" [cid %]])
         sample-list))
  (sample-ids cid sample-list))

(defn- insert-exp-sample [eid sid i]
  (insert exp_samples (values {:eid eid :sid sid :i i})))

(defn- load-exp-samples [exp sids]
  (dorun (map #(apply (partial insert-exp-sample exp) %)
              (map vector sids (range)))))

(let [fmtr (formatter "yyyy-MM-dd hh:mm:ss")]
  (defn- format-timestamp [timestamp]
    (unparse fmtr timestamp)))

(defn lock-matrix [file func]
  (kdb/transaction (update experiments (where {:file file}) (set-fields {:loaded false})))
  (func)
  (kdb/transaction (update experiments (where {:file file}) (set-fields {:loaded true}))))

(defn load-exp [file timestamp filehash matrix]
  (lock-matrix
    file
    (fn []
      (let [cid (kdb/transaction (merge-cohort file))
            exp (kdb/transaction (merge-exp file (format-timestamp timestamp) filehash cid))
            sids (kdb/transaction (load-samples cid (:samples (meta matrix))))]
        (kdb/transaction (load-exp-samples exp sids))
        (load-exp-matrix exp matrix)))))

; XXX factor out common parts with merge, above?
(defn del-exp [file]
  (kdb/transaction
    (let [[{exp :ID}] (select experiments (where {:file file}))]
      (clear-by-exp exp)
      (delete experiments (where {:id exp})))))

(defn create-db [file]
  (kdb/create-db  {:classname "org.h2.Driver"
                   :subprotocol "h2"
                   :subname file}))

(defmacro with-db [db & body]
  `(kdb/with-db ~db ~@body))

(kconf/set-delimiters "`") ; for h2

; execute a sequence of sql statements
(defn- exec-statements [stmts]
  (dorun (map (partial exec-raw) stmts)))

; TODO look up N
(defn- dataset-transform [ds]
  (-> ds
      (clojure.set/rename-keys {:FILE :name :COHORT :cohort})
      (#(assoc % :longlabel (% :name) :shortlabel (% :name) :N 100))))

; XXX Should this be in a transaction to ensure data consistency?
(defn datasets []
  (->> (select experiments (with cohorts) (fields :FILE [:cohorts.name :cohort]))
       (map dataset-transform)))

; Intersect experiment samples with the given set of samples.
(defn exp-samples-in-list [exp samps]
  (select exp_samples (with samples) (fields :i :samples.sample)
          (where  {:eid exp}) (where (in :samples.sample samps))))

(defn sample-bins [samps]
  (set (map #(quot (% :I) bin-size) samps)))

(defn select-scores []
  (-> (select* probes)
      (fields :name [:scores.expScores :expScores] [:joins.i :i])
      (join scores)))

(defn with-bins [q bins]
  (where q (in :i bins)))

(defn for-experiment [q exp]
  (where q {:probes.eid exp}))

(defn for-experiment-named [q exp]
  (for-experiment q (subselect experiments (fields "id") (where {:file exp}))))

(defn exp-by-name [exp]
  (let [[{id :ID}]
        (select experiments (fields "id") (where {:file exp}))]
    id))

; XXX expand model for probes/genes??
(defn with-genes [q genes]
  (fields (where q {:probes.name [in genes]}) [:probes.name :gene]))

(defn do-select [q]
  (exec q))

; merge bin number and bin offset for a sample.
(defn- merge-bin-off [sample]
  (let [{i :I} sample]
    (assoc sample :bin (quot i bin-size) :off (rem i bin-size))))

(defn- bin-mapping [order {off :off sample :SAMPLE}]
  [off (order sample)])

(defn- pick-samples-fn [order [bin samples]]
  [bin (partial ashuffle-float
                (map (partial bin-mapping order) samples))])

; Take an ordered list of requested samples, a list of samples (with indexes) in the
; experiment, and generate fns to copy values from a score bin to an output array.
; Returns one function for each score bin in the request.

(defn- pick-samples-fns [s-req s-exp]
  (let [order (zipmap s-req (range))
        by-bin (group-by :bin s-exp)]
    (apply hash-map (mapcat (partial pick-samples-fn order) by-bin))))

; Take map of bin copy fns, list of scores rows, and a map of output arrays,
; copying the scores to the output arrays via the bin copy fns.
(defn- build-score-arrays [rows bfns out]
  (dorun (map (fn [{i :I scores :EXPSCORES gene :GENE}]
                ((bfns i) (out gene) scores))
              rows))
  out)

(defn- col-arrays [columns n]
  (zipmap columns (repeatedly (partial float-array n))))

; XXX Need to fill in NAN for unknown samples.
(defn genomic-read-req [req]
  (let [{samples 'samples table 'table columns 'columns} req
        eid (exp-by-name table)
        s-in-exp (map merge-bin-off (exp-samples-in-list eid samples))
        bins (map :bin s-in-exp)
        bfns (pick-samples-fns samples s-in-exp)]

    (-> (select-scores)
        (for-experiment eid)
        (with-genes columns)
        (with-bins bins)
        (do-select)
        (build-score-arrays bfns (col-arrays columns (count samples))))))

; doall is required so the seq is evaluated in db context.
; Otherwise lazy map may not be evaluted until the context is lost.
(defn genomic-source [reqs]
  (doall (map #(update-in % ['data] merge (genomic-read-req %)) reqs)))

(defn create[]
  (kdb/transaction
    (exec-statements cohorts-table)
    (exec-statements samples-table)
    (exec-statements experiments-table)
    (exec-statements exp-samples-table)
    (exec-statements scores-table)
    (exec-statements join-table)
    (exec-statements probes-table)))

; XXX monkey-patch korma to work around h2 bug.
; h2 will fail to select an index if joins are grouped, e.g.
; FROM (a JOIN b) JOIN c
; We work around it by replacing the function that adds the
; grouping.
(in-ns 'korma.sql.utils)
(defn left-assoc [v]
  (clojure.string/join " " v))
(in-ns 'cavm.h2)
