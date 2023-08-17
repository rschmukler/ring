(ns ring.adapter.jetty
  "A Ring adapter that uses the Jetty 9 embedded web server.

  Adapters are used to convert Ring handlers into running web servers."
  (:require [ring.util.jakarta.servlet :as servlet]
            [ring.websocket :as ws])
  (:import [java.nio ByteBuffer]
           [org.eclipse.jetty.server
            Request
            Server
            ServerConnector
            ConnectionFactory
            HttpConfiguration
            HttpConnectionFactory
            SslConnectionFactory
            SecureRequestCustomizer]
           [org.eclipse.jetty.servlet ServletContextHandler ServletHandler]
           [org.eclipse.jetty.server.handler AbstractHandler]
           [org.eclipse.jetty.util BlockingArrayQueue]
           [org.eclipse.jetty.util.thread ThreadPool QueuedThreadPool]
           [org.eclipse.jetty.util.ssl SslContextFactory$Server KeyStoreScanner]
           [org.eclipse.jetty.websocket.server
            JettyWebSocketServerContainer
            JettyWebSocketCreator]
           [org.eclipse.jetty.websocket.api
            Session
            WebSocketConnectionListener
            WebSocketListener
            WebSocketPingPongListener]
           [org.eclipse.jetty.websocket.server.config
            JettyWebSocketServletContainerInitializer]
           [jakarta.servlet AsyncContext DispatcherType AsyncEvent AsyncListener]
           [jakarta.servlet.http HttpServletRequest HttpServletResponse]))

(defn- websocket-socket [^Session session]
  (let [remote (.getRemote session)]
    (reify ws/Socket
      (-send [_ message]
        (if (string? message)
          (.sendString remote message)
          (.sendBytes remote message)))
      (-ping [_ data]
        (.sendPing remote data))
      (-pong [_ data]
        (.sendPong remote data))
      (-close [_ status reason]
        (.close session status reason)))))

(defn- websocket-listener [listener]
  (let [socket (volatile! nil)]
    (reify
      WebSocketConnectionListener
      (onWebSocketConnect [_ session]
        (vreset! socket (websocket-socket session))
        (ws/on-open listener @socket))
      (onWebSocketClose [_ status reason]
        (ws/on-close listener @socket status reason))
      (onWebSocketError [_ throwable]
        (ws/on-error listener @socket throwable))
      WebSocketListener
      (onWebSocketText [_ message]
        (ws/on-message listener @socket message))
      (onWebSocketBinary [_ payload offset length]
        (let [buffer (ByteBuffer/wrap payload offset length)]
          (ws/on-message listener @socket buffer)))
      WebSocketPingPongListener
      (onWebSocketPing [_ _])
      (onWebSocketPong [_ payload]
        (ws/on-pong listener @socket payload)))))

(defn- websocket-creator [{listener ::ws/listener}]
  (reify JettyWebSocketCreator
    (createWebSocket [_ _ _]
      (websocket-listener listener))))

(defn- upgrade-to-websocket [^HttpServletRequest request response response-map]
  (let [context   (.getServletContext request)
        container (JettyWebSocketServerContainer/getContainer context)
        creator   (websocket-creator response-map)]
    (.upgrade container creator request response)))

(defn- ^ServletHandler proxy-handler [handler]
  (proxy [ServletHandler] []
    (doHandle [_ ^Request base-request request response]
      (let [request-map  (servlet/build-request-map request)
            response-map (handler request-map)]
        (try
          (if (ws/websocket-response? response-map)
            (upgrade-to-websocket request response response-map)
            (servlet/update-servlet-response response response-map))
          (finally
            (.setHandled base-request true)))))))

(defn- async-jetty-raise [^AsyncContext context ^HttpServletResponse response]
  (fn [^Throwable exception]
    (.sendError response 500 (.getMessage exception))
    (.complete context)))

(defn- async-jetty-respond [context request response]
  (fn [response-map]
    (if (ws/websocket-response? response-map)
      (upgrade-to-websocket request response response-map)
      (servlet/update-servlet-response response context response-map))))

(defn- async-timeout-listener [request context response handler]
  (proxy [AsyncListener] []
    (onTimeout [^AsyncEvent _]
      (handler (servlet/build-request-map request)
               (async-jetty-respond context request response)
               (async-jetty-raise context response)))
    (onComplete [^AsyncEvent _])
    (onError [^AsyncEvent _])
    (onStartAsync [^AsyncEvent _])))

(defn- ^ServletHandler async-proxy-handler [handler timeout timeout-handler]
  (proxy [ServletHandler] []
    (doHandle [_ ^Request base-request request response]
      (let [^AsyncContext context (.startAsync request)]
        (.setTimeout context timeout)
        (when timeout-handler
          (.addListener
           context
           (async-timeout-listener request context response timeout-handler)))
        (try
          (handler
           (servlet/build-request-map request)
           (async-jetty-respond context request response)
           (async-jetty-raise context response))
          (finally
            (.setHandled base-request true)))))))

(defn- ^ServletContextHandler context-handler [proxy-handler]
  (doto (ServletContextHandler.)
    (.setServletHandler proxy-handler)
    (.setAllowNullPathInfo true)
    (JettyWebSocketServletContainerInitializer/configure nil)))

(defn- ^ServerConnector server-connector [^Server server & factories]
  (ServerConnector. server #^"[Lorg.eclipse.jetty.server.ConnectionFactory;" (into-array ConnectionFactory factories)))

(defn- ^HttpConfiguration http-config [options]
  (doto (HttpConfiguration.)
    (.setSendDateHeader (:send-date-header? options true))
    (.setOutputBufferSize (:output-buffer-size options 32768))
    (.setRequestHeaderSize (:request-header-size options 8192))
    (.setResponseHeaderSize (:response-header-size options 8192))
    (.setSendServerVersion (:send-server-version? options true))))

(defn- ^ServerConnector http-connector [server options]
  (let [http-factory (HttpConnectionFactory. (http-config options))]
    (doto (server-connector server http-factory)
      (.setPort (options :port 80))
      (.setHost (options :host))
      (.setIdleTimeout (options :max-idle-time 200000)))))

(defn- ^SslContextFactory$Server ssl-context-factory [options]
  (let [context-server (SslContextFactory$Server.)]
    (if (string? (options :keystore))
      (.setKeyStorePath context-server (options :keystore))
      (.setKeyStore context-server ^java.security.KeyStore (options :keystore)))
    (when (string? (options :keystore-type))
      (.setKeyStoreType context-server (options :keystore-type)))
    (.setKeyStorePassword context-server (options :key-password))
    (cond
      (string? (options :truststore))
      (.setTrustStorePath context-server (options :truststore))
      (instance? java.security.KeyStore (options :truststore))
      (.setTrustStore context-server ^java.security.KeyStore (options :truststore)))
    (when (options :trust-password)
      (.setTrustStorePassword context-server (options :trust-password)))
    (when (options :ssl-context)
      (.setSslContext context-server (options :ssl-context)))
    (case (options :client-auth)
      :need (.setNeedClientAuth context-server true)
      :want (.setWantClientAuth context-server true)
      nil)
    (when-let [exclude-ciphers (options :exclude-ciphers)]
      (let [ciphers (into-array String exclude-ciphers)]
        (if (options :replace-exclude-ciphers?)
          (.setExcludeCipherSuites context-server ciphers)
          (.addExcludeCipherSuites context-server ciphers))))
    (when-let [exclude-protocols (options :exclude-protocols)]
      (let [protocols (into-array String exclude-protocols)]
        (if (options :replace-exclude-protocols?)
          (.setExcludeProtocols context-server protocols)
          (.addExcludeProtocols context-server protocols))))
    context-server))


(defn- ^ServerConnector ssl-connector [^Server server options]
  (let [ssl-port     (options :ssl-port 443)
        customizer   (doto (SecureRequestCustomizer.)
                       (.setSniHostCheck (options :sni-host-check? true)))
        http-factory (HttpConnectionFactory.
                      (doto (http-config options)
                        (.setSecureScheme "https")
                        (.setSecurePort ssl-port)
                        (.addCustomizer customizer)))
        ssl-context  (ssl-context-factory options)
        ssl-factory  (SslConnectionFactory. ssl-context "http/1.1")]
    (when-let [scan-interval (options :keystore-scan-interval)]
      (.addBean server (doto (KeyStoreScanner. ssl-context)
                         (.setScanInterval scan-interval))))
    (doto (server-connector server ssl-factory http-factory)
      (.setPort ssl-port)
      (.setHost (options :host))
      (.setIdleTimeout (options :max-idle-time 200000)))))

(defn- ^ThreadPool create-threadpool [options]
  (let [min-threads         (options :min-threads 8)
        max-threads         (options :max-threads 50)
        queue-max-capacity  (-> (options :max-queued-requests Integer/MAX_VALUE) (max 8))
        queue-capacity      (-> min-threads (max 8) (min queue-max-capacity))
        blocking-queue      (BlockingArrayQueue. queue-capacity
                                                 queue-capacity
                                                 queue-max-capacity)
        thread-idle-timeout (options :thread-idle-timeout 60000)
        pool                (QueuedThreadPool. max-threads
                                               min-threads
                                               thread-idle-timeout
                                               blocking-queue)]
    (when (:daemon? options false)
      (.setDaemon pool true))
    pool))

(defn- ^Server create-server [options]
  (let [pool   (or (:thread-pool options) (create-threadpool options))
        server (Server. pool)]
    (when (:http? options true)
      (.addConnector server (http-connector server options)))
    (when (or (options :ssl?) (options :ssl-port))
      (.addConnector server (ssl-connector server options)))
    server))

(defn ^Server run-jetty
  "Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :configurator           - a function called with the Jetty Server instance
  :async?                 - if true, treat the handler as asynchronous
  :async-timeout          - async context timeout in ms
                            (defaults to 0, no timeout)
  :async-timeout-handler  - an async handler to handle an async context timeout
  :port                   - the port to listen on (defaults to 80)
  :host                   - the hostname to listen on
  :join?                  - blocks the thread until server ends
                            (defaults to true)
  :daemon?                - use daemon threads (defaults to false)
  :http?                  - listen on :port for HTTP traffic (defaults to true)
  :ssl?                   - allow connections over HTTPS
  :ssl-port               - the SSL port to listen on (defaults to 443, implies
                            :ssl? is true)
  :ssl-context            - an optional SSLContext to use for SSL connections
  :sni-host-check?        - use SNI to check the hostname (default true)
  :exclude-ciphers        - when :ssl? is true, additionally exclude these
                            cipher suites
  :exclude-protocols      - when :ssl? is true, additionally exclude these
                            protocols
  :replace-exclude-ciphers?   - when true, :exclude-ciphers will replace rather
                                than add to the cipher exclusion list (defaults
                                to false)
  :replace-exclude-protocols? - when true, :exclude-protocols will replace
                                rather than add to the protocols exclusion list
                                (defaults to false)
  :keystore               - the keystore to use for SSL connections
  :keystore-type          - the keystore type (default jks)
  :key-password           - the password to the keystore
  :keystore-scan-interval - if not nil, the interval in seconds to scan for an
                            updated keystore
  :thread-pool            - custom thread pool instance for Jetty to use
  :truststore             - a truststore to use for SSL connections
  :trust-password         - the password to the truststore
  :max-threads            - the maximum number of threads to use (default 50)
  :min-threads            - the minimum number of threads to use (default 8)
  :max-queued-requests    - the maximum number of requests to be queued
  :thread-idle-timeout    - Set the maximum thread idle time. Threads that are
                            idle for longer than this period may be stopped
                            (default 60000)
  :max-idle-time          - the maximum idle time in milliseconds for a
                            connection (default 200000)
  :client-auth            - SSL client certificate authenticate, may be set to
                            :need,:want or :none (defaults to :none)
  :send-date-header?      - add a date header to the response (default true)
  :output-buffer-size     - the response body buffer size (default 32768)
  :request-header-size    - the maximum size of a request header (default 8192)
  :response-header-size   - the maximum size of a response header (default 8192)
  :send-server-version?   - add Server header to HTTP response (default true)"
  [handler options]
  (let [server (create-server (dissoc options :configurator))
        proxy  (if (:async? options)
                 (async-proxy-handler handler
                                      (:async-timeout options 0)
                                      (:async-timeout-handler options))
                 (proxy-handler handler))]
    (.setHandler server (context-handler proxy))
    (when-let [configurator (:configurator options)]
      (configurator server))
    (try
      (.start server)
      (when (:join? options true)
        (.join server))
      server
      (catch Exception ex
        (.stop server)
        (throw ex)))))
