(ns cljs-http.core
  (:import [goog.net EventType ErrorCode XhrIo]
           [goog.net Jsonp]
           [goog.html TrustedResourceUrl]
           [goog.string Const])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs-http.util :as util]
            [cljs.core.async :as async]
            [clojure.string :as s]))

(def pending-requests (atom {}))

(defn abort!
  "Attempt to close the given channel and abort the pending HTTP request
  with which it is associated."
  [channel]
  (when-let [req (@pending-requests channel)]
    (swap! pending-requests dissoc channel)
    (async/close! channel)
    (if (.hasOwnProperty req "abort")
      (.abort req)
      (.cancel (:jsonp req) (:request req)))))

(defn- aborted? [xhr]
  (= (.getLastErrorCode xhr) goog.net.ErrorCode.ABORT))

(defn apply-default-headers!
  "Takes an XhrIo object and applies the default-headers to it."
  [xhr headers]
  (let [formatted-h (zipmap (map util/camelize (keys headers)) (vals headers))]
    (dorun
      (map (fn [[k v]]
             (.set (.-headers xhr) k v))
           formatted-h))))

(defn apply-response-type!
  "Takes an XhrIo object and sets response-type if not nil."
  [xhr response-type]
  (.setResponseType xhr
   (case response-type
     :array-buffer XhrIo.ResponseType.ARRAY_BUFFER
     :blob XhrIo.ResponseType.BLOB
     :document XhrIo.ResponseType.DOCUMENT
     :text XhrIo.ResponseType.TEXT
     :default XhrIo.ResponseType.DEFAULT
     nil XhrIo.ResponseType.DEFAULT)))

(defn build-xhr
  "Builds an XhrIo object from the request parameters."
  [{:keys [with-credentials? default-headers response-type] :as request}]
  (let [timeout (or (:timeout request) 0)
        send-credentials (if (nil? with-credentials?)
                           true
                           with-credentials?)]
    (doto (XhrIo.)
          (apply-default-headers! default-headers)
          (apply-response-type! response-type)
          (.setTimeoutInterval timeout)
          (.setWithCredentials send-credentials))))

;; goog.net.ErrorCode constants to CLJS keywords
(def error-kw
  {0 :no-error
   1 :access-denied
   2 :file-not-found
   3 :ff-silent-error
   4 :custom-error
   5 :exception
   6 :http-error
   7 :abort
   8 :timeout
   9 :offline})

(defn xhr
  "Execute the HTTP request corresponding to the given Ring request
  map and return a core.async channel."
  [{:keys [request-method headers body with-credentials? cancel progress] :as request}]
  (let [channel (async/chan)
        request-url (util/build-url request)
        method (name (or request-method :get))
        headers (util/build-headers headers)
        xhr (build-xhr request)]
    (swap! pending-requests assoc channel xhr)
    (.listen xhr EventType.COMPLETE
             (fn [evt]
               (let [target (.-target evt)
                     response {:status (.getStatus target)
                               :success (.isSuccess target)
                               :body (.getResponse target)
                               :headers (util/parse-headers (.getAllResponseHeaders target))
                               :trace-redirects [request-url (.getLastUri target)]
                               :error-code (error-kw (.getLastErrorCode target))
                               :error-text (.getLastError target)}]
                 (if-not (aborted? xhr)
                   (async/put! channel response))
                 (swap! pending-requests dissoc channel)
                 (if cancel (async/close! cancel))
                 (async/close! channel))))

    (when progress
      (let [listener (fn [direction evt]
                       (async/put! progress (merge {:direction direction :loaded (.-loaded evt)}
                                                   (if (.-lengthComputable evt) {:total (.-total evt)}))))]
        (doto xhr
          (.setProgressEventsEnabled true)
          (.listen EventType.UPLOAD_PROGRESS (partial listener :upload))
          (.listen EventType.DOWNLOAD_PROGRESS (partial listener :download)))))

    (.send xhr request-url method body headers)
    (if cancel
      (go
        (let [v (async/<! cancel)]
          (if (not (.isComplete xhr))
            (.abort xhr)))))
    channel))

(defn build-trusted-string [s]
  ; this circumvents the enforcement of Const objects from non-string-literals
  ;   without this, Google Closure compiler will generate an error at compile time (i.e., shadow-cljs release mode)
  ; https://github.com/google/closure-library/blob/master/closure/goog/string/const.js#L50
  (Const. Const.GOOG_STRING_CONSTRUCTOR_TOKEN_PRIVATE_ s))

(defn jsonp
  "Execute the JSONP request corresponding to the given Ring request
  map and return a core.async channel."
  [{:keys [timeout callback-name cancel keywordize-keys?]
    :or {keywordize-keys? true}
    :as request}]
  (let [channel (async/chan)
        ;trusted-url (TrustedResourceUrl/fromConstant (Const/from (:saved-url request)))
        trusted-url (TrustedResourceUrl/fromConstant
                      (build-trusted-string (:saved-url request)))
        jsonp (Jsonp. trusted-url callback-name)]
    (.setRequestTimeout jsonp timeout)
    (let [req (.send jsonp nil
                     (fn success-callback [data]
                       (let [response {:status 200
                                       :success true
                                       :body (js->clj data :keywordize-keys keywordize-keys?)}]
                         (async/put! channel response)
                         (swap! pending-requests dissoc channel)
                         (if cancel (async/close! cancel))
                         (async/close! channel)))
                     (fn error-callback []
                         (swap! pending-requests dissoc channel)
                         (if cancel (async/close! cancel))
                         (async/close! channel)))]
      (swap! pending-requests assoc channel {:jsonp jsonp :request req})
      (if cancel
        (go
          (let [v (async/<! cancel)]
            (.cancel jsonp req)))))
    channel))



(comment
  (def r1 {:scheme :https, :server-name "publish.twitter.com", :server-port nil, :uri "/oembed", :query-string "url=https%3A%2F%2Ftwitter.com%2Frealgenekim%2Fstatus%2F1223794932189671424&omit_script=true", :request-method :jsonp
           :saved-url "https://publish.twitter.com/oembed?url=https://twitter.com/realgenekim/status/1224310811633635329&omit_script=true"})
  (def jsonp (Jsonp. url callback-name))
  (.send jsonp nil (fn [] nil) (fn [] nil))

  (build-trusted-url "abc")

  (Const/from "abc")
  (.toString (Const/from "abc"))
  (def trul (Const))
  (TrustedResourceUrl/fromConstant (Const/from "abc"))
  (.toString (TrustedResourceUrl/fromConstant (Const/from "abc")))


  (let [channel (async/chan)
        ;; ↓ trusted url insted of just url
        trusted-url (->> request
                         (util/build-url)
                         Const/from
                         TrustedResourceUrl/fromConstant)
        jsonp   (Jsonp. trusted-url callback-name)]
    (.....)))



(comment
  (jsonp (build-trusted-url "https://publish.twitter.com/oembed?url=https://twitter.com/realgenekim/status/1223341376475222020&omit_script=true"))
  (jsonp {:scheme :https, :server-name "publish.twitter.com", :server-port nil, :uri "/oembed", :query-string "url=https%3A%2F%2Ftwitter.com%2Frealgenekim%2Fstatus%2F1223794932189671424&omit_script=true", :request-method :jsonp})
  (+ 1 1)
  (clojure.core/refer-clojure))




(defn request
  "Execute the HTTP request corresponding to the given Ring request
  map and return a core.async channel."
  [{:keys [request-method] :as request}]
  (if (= request-method :jsonp)
    (jsonp request)
    (xhr request)))
