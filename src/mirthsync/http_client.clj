(ns mirthsync.http-client
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as cdzx]
            [mirthsync.logging :as log]
            [clojure.zip :as zip]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mxml])
  (:import [org.apache.http.impl.cookie BasicClientCookie]))

(defn- build-headers
  "Build headers map"
  []
  {:x-requested-with "XMLHttpRequest"})

(defn put-xml
  "HTTP PUTs the current api and el-loc to the server."
  [{:keys [server el-loc ignore-cert-warnings api]}
   params]
  (log/logf :debug "putting xml to: %s" (mi/rest-path api))
  (client/put (str server (mi/rest-path api) "/" (mi/find-id api el-loc))
              {:headers (build-headers)
               :insecure? ignore-cert-warnings
               :body (xml/indent-str (zip/node el-loc))
               :query-params params
               :content-type "application/xml"}))

(defn post-xml
  "HTTP multipart posts the params of the api to the server. Multiple
  params are supported and should be passed as one or more [name
  value] vectors. Name should be a string and value should be an xml
  string."
  [{:keys [server ignore-cert-warnings]} path params query-params multipart?]
  (log/logf :debug "posting xml to: %s" path)
  (let [base-params {:headers (build-headers)
                     :insecure? ignore-cert-warnings
                     :query-params query-params}]
    (client/post (str server path)
                 (if multipart?
                   (assoc base-params :multipart (map (fn
                                                        [[k v]]
                                                        {:name k
                                                         :content v
                                                         :mime-type "application/xml"
                                                         :encoding "UTF-8"})
                                                      params))
                   (assoc base-params
                          :body params
                          :content-type "application/xml"
                          :accept "application/xml")))))

(defn- add-cookie-to-store
  "Add a JSESSIONID cookie to the cookie store. Creates a cookie matching
  what Mirth Connect sets: Path from server URL (e.g. /api), Secure flag for HTTPS,
  and Domain matching the server host."
  [cookie-store server token]
  (let [uri (java.net.URI. server)
        cookie (doto (BasicClientCookie. "JSESSIONID" token)
                 (.setDomain (.getHost uri))
                 (.setPath (let [path (.getPath uri)]
                             (if (and path (not= path ""))
                               path
                               "/")))
                 (.setSecure (= "https" (.getScheme uri))))]
    (.addCookie cookie-store cookie)))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials or token, and returns the results of executing the
  supplied parameterless function for side effects"
  [{:as app-conf :keys [server username password token ignore-cert-warnings]} func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (if token
      ;; If token provided, add it to cookie store
      (add-cookie-to-store clj-http.core/*cookie-store* server token)
      ;; Otherwise perform login with username/password
      (client/post
       (str server "/users/_login")
       {:headers {:x-requested-with "XMLHttpRequest"}
        :form-params
        {:username username
         :password password}
        :insecure? ignore-cert-warnings}))
    (func)))

(defn api-url
  "Returns the constructed api url."
  [{:keys [server api]}]
  (str server (mi/rest-path api)))

(defn fetch-all
  "Fetch everything at url from remote server and return a sequence of
  locs based on the supplied function. In other words - grab the xml
  from the url via a GET request, extract the :body of the result,
  parse the XML, create a 'zipper', and return the result of the
  function on the xml zipper."
  [{:as app-conf :keys [ignore-cert-warnings]}
   find-elements]
  (-> (api-url app-conf)
      (client/get {:headers (build-headers)
                   :insecure? ignore-cert-warnings})
      :body
      mxml/to-zip
      find-elements))

(defn fetch-channel-by-id
  "Fetch a specific channel by its ID from the remote server."
  [{:keys [server ignore-cert-warnings]} channel-id]
  (log/logf :debug "Fetching channel with ID: %s" channel-id)
  (try
    (let [response (client/get (str server "/channels/" channel-id)
                               {:headers (build-headers)
                                :insecure? ignore-cert-warnings
                                :throw-exceptions false})
          status (:status response)
          channel-xml (:body response)]
      (cond
        (or (= status 404) (= status 204))
        (throw (ex-info (str "Channel with ID '" channel-id "' not found on server")
                        {:type :channel-not-found :channel-id channel-id :status status}))

        (not= status 200)
        (throw (ex-info (str "Failed to fetch channel with ID '" channel-id "'. Server returned status: " status)
                        {:type :channel-fetch-error :channel-id channel-id :status status :body channel-xml}))

        (or (nil? channel-xml) (empty? channel-xml))
        (throw (ex-info (str "Channel with ID '" channel-id "' returned empty response")
                        {:type :channel-empty :channel-id channel-id}))

        :else
        ;; Wrap the single channel in a list element and return as a list of locs
        [(-> (str "<list>" channel-xml "</list>")
             mxml/to-zip
             (cdzx/xml1-> :list :channel))]))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info (str "Error fetching channel with ID '" channel-id "': " (.getMessage e))
                      {:type :channel-fetch-error :channel-id channel-id :cause e})))))
