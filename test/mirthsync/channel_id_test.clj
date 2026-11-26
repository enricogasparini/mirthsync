(ns mirthsync.channel-id-test
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.zip :as zip]
            [mirthsync.core :as core]
            [mirthsync.git :as git]
            [mirthsync.http-client :as http]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as xml])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private channel-id "1b82a0e4-f80c-44b7-8147-43e1f1239fb6")
(def ^:private server "https://test.invalid/api")
(def ^:private channel
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<channel><id>" channel-id "</id><name>ADT: In</name>"
       "<preprocessingScript>// selected</preprocessingScript></channel>"))
(def ^:private groups
  (str "<list><channelGroup><id>group-1</id><name>Group</name>"
       "<channels><channel><id>" channel-id "</id></channel>"
       "<channel><id>other</id></channel></channels></channelGroup></list>"))

(defn- with-target [f]
  (let [target (.toFile (Files/createTempDirectory "mirthsync-channel-id-" (make-array FileAttribute 0)))]
    (try
      (f target)
      (finally
        (doseq [file (reverse (file-seq target))]
          (io/delete-file file))))))

(deftest fetch-single-channel
  (let [requests (atom [])]
    (with-redefs [client/get (fn [url opts]
                              (swap! requests conj [url opts])
                              {:status 200 :body channel})]
      (let [[loc] (http/fetch-channel-by-id {:server server :ignore-cert-warnings true} channel-id)]
        (is (= channel-id (mi/find-id :channels loc)))
        (is (= :channel (:tag (zip/root loc))))
        (is (= (str server "/channels/" channel-id) (ffirst @requests)))
        (is (true? (:insecure? (second (first @requests)))))))))

(deftest fetch-single-channel-errors
  (doseq [[response error-type]
          [[{:status 404 :body ""} :channel-not-found]
           [{:status 204 :body nil} :channel-not-found]
           [{:status 500 :body "server error"} :channel-fetch-error]
           [{:status 200 :body ""} :channel-empty]
           [{:status 200 :body nil} :channel-empty]
           [{:status 200 :body "<list/>"} :channel-fetch-error]
           [{:status 200 :body "<channel><id>other</id></channel>"} :channel-fetch-error]
           [{:status 200 :body "<channel>"} :channel-fetch-error]]]
    (with-redefs [client/get (fn [_ _] response)]
      (try
        (http/fetch-channel-by-id {:server server} channel-id)
        (is false (str "Expected failure for " response))
        (catch clojure.lang.ExceptionInfo e
          (is (= error-type (:type (ex-data e))))
          (is (= channel-id (:channel-id (ex-data e)))))))))

(deftest channel-pull-preserves-unrelated-local-files
  (doseq [mode ["code" "items"]]
    (with-target
      (fn [target]
        (let [requests (atom [])
              untouched {"Channels/Group/index.xml" "<channelGroup><id>local-group</id></channelGroup>"
                         "Channels/Group/Other.xml" "<channel><id>other</id><name>Other</name></channel>"
                         "Channels/Group/Other/PreprocessingScript.js" "// untouched"
                         "GlobalScripts/Preprocessor.js" "// untouched"}
              selected (io/file target "Channels/Group/ADT%3A In.xml")
              stale (io/file target "Channels/Group/ADT%3A In/stale.js")]
          (doseq [[path content] (assoc untouched "Channels/Group/ADT%3A In.xml" channel
                                                 "Channels/Group/ADT%3A In/stale.js" "// obsolete")]
            (let [file (io/file target path)]
              (io/make-parents file)
              (spit file content)))
          (with-redefs [http/with-authentication (fn [_ f] (f))
                        client/get (fn [url _]
                                     (swap! requests conj url)
                                     (cond
                                       (= url (str server "/channelgroups")) {:status 200 :body groups}
                                       (= url (str server "/channels/" channel-id)) {:status 200 :body channel}
                                       :else (throw (ex-info (str "Unexpected request: " url) {}))))]
            (is (= 0 (core/main-func "-s" server "-u" "admin" "-p" "test" "-t" (.getPath target)
                                      "--channel-id" channel-id "--disk-mode" mode
                                      "--delete-orphaned" "-f" "pull"))))
          (is (.exists selected))
          (is (not (.exists stale)))
          (is (= channel-id (mi/find-id :channels (xml/to-zip (slurp selected)))))
          (doseq [[path content] untouched]
            (is (= content (slurp (io/file target path)))))
          (is (= (if (= "code" mode) "// selected" nil)
                 (let [script (io/file target "Channels/Group/ADT%3A In/PreprocessingScript.js")]
                   (when (.exists script) (slurp script)))))
          (is (= #{(str server "/channelgroups") (str server "/channels/" channel-id)}
                 (set @requests))))))))

(deftest channel-fetch-failure-fails-command
  (with-target
    (fn [target]
      (let [commits (atom [])]
        (with-redefs [http/with-authentication (fn [_ f] (f))
                      client/get (fn [url _]
                                   (if (= url (str server "/channelgroups"))
                                     {:status 200 :body groups}
                                     {:status 404 :body ""}))
                      git/auto-commit-after-operation #(swap! commits conj %)]
          (is (= 1 (core/main-func "-s" server "-u" "admin" "-p" "test" "-t" (.getPath target)
                                    "--channel-id" channel-id "--auto-commit" "pull")))
          (is (empty? @commits))
          (is (empty? (rest (file-seq target)))))))))
