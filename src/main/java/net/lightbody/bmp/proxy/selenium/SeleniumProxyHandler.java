package net.lightbody.bmp.proxy.selenium;

import net.lightbody.bmp.proxy.ProxyServer;
import net.lightbody.bmp.proxy.jetty.http.*;
import net.lightbody.bmp.proxy.jetty.http.handler.AbstractHttpHandler;
import net.lightbody.bmp.proxy.jetty.util.IO;
import net.lightbody.bmp.proxy.jetty.util.InetAddrPort;
import net.lightbody.bmp.proxy.jetty.util.StringMap;
import net.lightbody.bmp.proxy.jetty.util.URI;
import net.lightbody.bmp.proxy.util.ResourceExtractor;
import net.lightbody.bmp.proxy.util.TrustEverythingSSLTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import java.io.*;
import java.net.*;
import java.util.*;

/* ------------------------------------------------------------ */

/**
 * Proxy request handler. A HTTP/1.1 Proxy. This implementation uses the JVMs URL implementation to
 * make proxy requests.
 * <p>
 * The HttpTunnel mechanism is also used to implement the CONNECT method.
 *
 * @author Greg Wilkins (gregw)
 * @author giacof@tiscali.it (chained proxy)
 * @version $Id: ProxyHandler.java,v 1.34 2005/10/05 13:32:59 gregwilkins Exp $
 */
public class SeleniumProxyHandler extends AbstractHttpHandler {
    private final Logger log = LoggerFactory.getLogger(SeleniumProxyHandler.class);
    private final Map<String, SslRelay> _sslMap = new LinkedHashMap<String, SslRelay>();
    private final String dontInjectRegex;
    private final String debugURL;
    private final boolean proxyInjectionMode;
    private final boolean forceProxyChain;
    protected Set<String> _proxyHostsWhiteList;
    protected Set<String> _proxyHostsBlackList;
    protected int _tunnelTimeoutMs = 250;
    /**
     * Map of leg by leg headers (not end to end). Should be a set, but more efficient string map is
     * used instead.
     */
    protected StringMap _DontProxyHeaders = new StringMap();
    /**
     * Map of leg by leg headers (not end to end). Should be a set, but more efficient string map is
     * used instead.
     */
    protected StringMap _ProxyAuthHeaders = new StringMap();
    /**
     * Map of allows schemes to proxy Should be a set, but more efficient string map is used
     * instead.
     */
    protected StringMap _ProxySchemes = new StringMap();
    /**
     * Set of allowed CONNECT ports.
     */
    protected HashSet<Integer> _allowedConnectPorts = new HashSet<Integer>();
    private boolean _anonymous = false;
    private transient boolean _chained = false;
    @SuppressWarnings("unused")
    private String sslKeystorePath;

      /* ------------------------------------------------------------ */
    private boolean useCyberVillains = true;
    private boolean trustAllSSLCertificates = false;

      /* ------------------------------------------------------------ */
    private boolean fakeCertsGenerated;
    // see docs for the lock object on SeleniumServer for information on this and why it is IMPORTANT!
    private Object shutdownLock;

      /* ------------------------------------------------------------ */

    {
        Object o = new Object();
        _DontProxyHeaders.setIgnoreCase(true);
        _DontProxyHeaders.put(HttpFields.__ProxyConnection, o);
        _DontProxyHeaders.put(HttpFields.__Connection, o);
        _DontProxyHeaders.put(HttpFields.__KeepAlive, o);
        _DontProxyHeaders.put(HttpFields.__TransferEncoding, o);
        _DontProxyHeaders.put(HttpFields.__TE, o);
        _DontProxyHeaders.put(HttpFields.__Trailer, o);
        _DontProxyHeaders.put(HttpFields.__Upgrade, o);
    }

    {
        Object o = new Object();
        _ProxyAuthHeaders.put(HttpFields.__ProxyAuthorization, o);
        _ProxyAuthHeaders.put(HttpFields.__ProxyAuthenticate, o);
    }

      /* ------------------------------------------------------------ */

    {
        Object o = new Object();
        _ProxySchemes.setIgnoreCase(true);
        _ProxySchemes.put(HttpMessage.__SCHEME, o);
        _ProxySchemes.put(HttpMessage.__SSL_SCHEME, o);
        _ProxySchemes.put("ftp", o);
    }

    {
        _allowedConnectPorts.add(80);
        _allowedConnectPorts.add(4444);
        _allowedConnectPorts.add(8000);
        _allowedConnectPorts.add(8080);
        _allowedConnectPorts.add(8888);
        _allowedConnectPorts.add(443);
        _allowedConnectPorts.add(8443);
    }

    public SeleniumProxyHandler(boolean trustAllSSLCertificates, String dontInjectRegex, String debugURL, boolean proxyInjectionMode, boolean forceProxyChain) {
        super();
        this.trustAllSSLCertificates = trustAllSSLCertificates;
        this.dontInjectRegex = dontInjectRegex;
        this.debugURL = debugURL;
        this.proxyInjectionMode = proxyInjectionMode;
        this.forceProxyChain = forceProxyChain;
    }

    /* ------------------------------------------------------------ */
      /*
       */
    public void start() throws Exception {
        _chained = System.getProperty("http.proxyHost") != null || forceProxyChain;
        super.start();
    }

      /* ------------------------------------------------------------ */

    public int getTunnelTimeoutMs() {
        return _tunnelTimeoutMs;
    }

      /* ------------------------------------------------------------ */

    /**
     * Tunnel timeout. IE on win2000 has connections issues with normal timeout handling. This
     * timeout should be set to a low value that will expire to allow IE to see the end of the
     * tunnel connection.
     */
    public void setTunnelTimeoutMs(int ms) {
        _tunnelTimeoutMs = ms;
    }

    /* ------------------------------------------------------------ */
    public void handle(String pathInContext, String pathParams, HttpRequest request, HttpResponse response) throws HttpException, IOException {
        URI uri = request.getURI();

        // Is this a CONNECT request?
        if (HttpRequest.__CONNECT.equalsIgnoreCase(request.getMethod())) {
            if (!ProxyServer.getShouldKeepSslConnectionAlive()) {
                response.setField(HttpFields.__Connection, "close"); // TODO Needed for IE????
            }
            handleConnect(pathInContext, pathParams, request, response);
            return;
        }

        try {

            // Has the requested resource been found?
            if ("True".equals(response.getAttribute("NotFound"))) {
                response.removeAttribute("NotFound");
                sendNotFound(response);
                return;
            }

            // Do we proxy this?
            URL url = isProxied(uri);
            if (url == null) {
                if (isForbidden(uri))
                    sendForbid(request, response, uri);
                return;
            }

            // is this URL a /selenium URL?
            if (isSeleniumUrl(url.toString())) {
                request.setHandled(false);
                return;
            }

            proxyPlainTextRequest(url, pathInContext, pathParams, request, response);
        } catch (UnknownHostException e) {
            log.info("Couldn't proxy to " + uri + " because host not found");
            response.setStatus(400);
            String host = uri.getHost();
            response.setReason("Host " + host + " not found");
            OutputStreamWriter out = new OutputStreamWriter(response.getOutputStream());
            out.write("<html>" +
                    "<head><title>Problem loading page</title></head>" +
                    "<body style=\"background-color:#F0F0F0; font-family: sans-serif\">" +
                    "<div style=\"margin:auto; margin-top: 3em;width:600px; background-color:#FFF; padding:30px;border: 1px solid #DDD\">" +
                    "<h1 style=\"font-size: 18px;border-bottom:thin solid #DDD\">Server not found</h1>" +
                    "<p style=\"border-bottom: 1px solid #DDD; padding-bottom: 20px\">Selenium can't find the server at " + host + "</p>" +
                    "<ul style=\"list-style: square outside none;font-size:13px\">" +
                    "<li style=\"margin-bottom:6px;\">Check the address for typing errors such as ww.example.com instead of www.example.com</li>" +
                    "<li style=\"margin-bottom:6px;\">If you are unable to load any pages, check your computer's network connection.</li>" +
                    "<li style=\"margin-bottom:6px;\">If your computer or network is protected by a firewall or proxy, make sure that your browser is permitted to access the Web.</li>" +
                    "</ul>" +
                    "</div>" +
                    "</body>");
            out.close();
            response.getOutputStream().close();
        } catch (ConnectException e) {
            log.info("Couldn't proxy to " + uri + " because host not listening");
            response.setStatus(400);
            String host = uri.getHost();
            if (uri.getPort() > 0) {
                host = host + ":" + uri.getPort();
            }
            response.setReason("Couldn't connect to " + host);
            OutputStreamWriter out = new OutputStreamWriter(response.getOutputStream());
            out.write("<html>" +
                    "<head><title>Problem loading page</title></head>" +
                    "<body style=\"background-color:#F0F0F0; font-family: sans-serif\">" +
                    "<div style=\"margin:auto; margin-top: 3em;width:600px; background-color:#FFF; padding:30px;border: 1px solid #DDD\">" +
                    "<h1 style=\"font-size: 18px;border-bottom:thin solid #DDD\">Unable to connect</h1>" +
                    "<p style=\"border-bottom: 1px solid #DDD; padding-bottom: 20px\">Selenium can't establish a connection to the server at " + host + "</p>" +
                    "<ul style=\"list-style: square outside none;font-size:13px\">" +
                    "<li style=\"margin-bottom:6px;\">The site could be temporarily unavailable or too busy. Try again in a few moments.</li>" +
                    "<li style=\"margin-bottom:6px;\">If you are unable to load any pages, check your computer's network connection.</li>" +
                    "<li style=\"margin-bottom:6px;\">If your computer or network is protected by a firewall or proxy, make sure that your browser is permitted to access the Web.</li>" +
                    "</ul>" +
                    "</div>" +
                    "</body>");

            out.close();
            response.getOutputStream().close();
        } catch (Exception e) {
            log.debug("Could not proxy " + uri, e);
            if (!response.isCommitted())
                response.sendError(HttpResponse.__400_Bad_Request, "Could not proxy " + uri + "\n" + e);
        }
    }

    private boolean isSeleniumUrl(String url) {
        int slashSlash = url.indexOf("//");
        if (slashSlash == -1) {
            return false;
        }

        int nextSlash = url.indexOf("/", slashSlash + 2);
        if (nextSlash == -1) {
            return false;
        }

        int seleniumServer = url.indexOf("/selenium-server/");
        if (seleniumServer == -1) {
            return false;
        }

        // we do this complex checking because sometimes some sites/pages (such as ominture ads) embed the referrer URL,
        // which will include selenium stuff, in to the query parameter, which would fake out a simple String.contains()
        // call. This method is more robust and will catch this stuff.
        return seleniumServer == nextSlash;
    }

    protected long proxyPlainTextRequest(URL url, String pathInContext, String pathParams, HttpRequest request, HttpResponse response) throws IOException {
        log.debug("PROXY URL=" + url);

        URLConnection connection = url.openConnection();
        if (System.getProperty("http.proxyHost") != null &&
                System.getProperty("https.proxyHost") == null &&
                "https".equals(url.getProtocol())) {
              /* Proxy HTTPS connections even if https.proxyHost isn't set because that's
                 what Selenium used to do. */
            String proxyHost = System.getProperty("http.proxyHost");
            int proxyPort = Integer.getInteger("http.proxyPort");
            InetSocketAddress proxyAddress = new InetSocketAddress(proxyHost, proxyPort);
            connection = url.openConnection(new Proxy(Proxy.Type.HTTP, proxyAddress));
        }

        connection.setAllowUserInteraction(false);

        if (proxyInjectionMode) {
            adjustRequestForProxyInjection(request, connection);
        }

        // Set method
        HttpURLConnection http = null;
        if (connection instanceof HttpURLConnection) {
            http = (HttpURLConnection) connection;
            http.setRequestMethod(request.getMethod());
            http.setInstanceFollowRedirects(false);
            if (trustAllSSLCertificates && connection instanceof HttpsURLConnection) {
                TrustEverythingSSLTrustManager.trustAllSSLCertificates((HttpsURLConnection) connection);
            }
        }

        // check connection header
        String connectionHdr = request.getField(HttpFields.__Connection);
        if (connectionHdr != null && (connectionHdr.equalsIgnoreCase(HttpFields.__KeepAlive) || connectionHdr.equalsIgnoreCase(HttpFields.__Close)))
            connectionHdr = null;

        // copy headers
        boolean xForwardedFor = false;
        boolean isGet = "GET".equals(request.getMethod());
        boolean hasContent = false;
        Enumeration enm = request.getFieldNames();
        while (enm.hasMoreElements()) {
            // TODO could be better than this!
            String hdr = (String) enm.nextElement();

            if (_DontProxyHeaders.containsKey(hdr) || !_chained && _ProxyAuthHeaders.containsKey(hdr))
                continue;
            if (connectionHdr != null && connectionHdr.indexOf(hdr) >= 0)
                continue;

            if (!isGet && HttpFields.__ContentType.equals(hdr))
                hasContent = true;

            Enumeration vals = request.getFieldValues(hdr);
            while (vals.hasMoreElements()) {
                String val = (String) vals.nextElement();
                if (val != null) {
                    // don't proxy Referer headers if the referer is Selenium!
                    if ("Referer".equals(hdr) && (-1 != val.indexOf("/selenium-server/"))) {
                        continue;
                    }
                    if (!isGet && HttpFields.__ContentLength.equals(hdr) && Integer.parseInt(val) > 0) {
                        hasContent = true;
                    }

                    connection.addRequestProperty(hdr, val);
                    xForwardedFor |= HttpFields.__XForwardedFor.equalsIgnoreCase(hdr);
                }
            }
        }

        // Proxy headers
        if (!_anonymous)
            connection.setRequestProperty("Via", "1.1 (jetty)");
        if (!xForwardedFor)
            connection.addRequestProperty(HttpFields.__XForwardedFor, request.getRemoteAddr());

        // a little bit of cache control
        String cache_control = request.getField(HttpFields.__CacheControl);
        if (cache_control != null && (cache_control.indexOf("no-cache") >= 0 || cache_control.indexOf("no-store") >= 0))
            connection.setUseCaches(false);

        // customize Connection
        customizeConnection(pathInContext, pathParams, request, connection);

        try {
            connection.setDoInput(true);

            // do input thang!
            InputStream in = request.getInputStream();
            if (hasContent) {
                connection.setDoOutput(true);
                IO.copy(in, connection.getOutputStream());
            }

            // Connect
            connection.connect();
        } catch (Exception e) {
            // TODO(simon): Whhhaaaat?
            // LogSupport.ignore(log, e);
        }

        InputStream proxy_in = null;

        // handler status codes etc.
        int code = -1;
        if (http != null) {
            proxy_in = http.getErrorStream();

            try {
                code = http.getResponseCode();
            } catch (SSLHandshakeException e) {
                throw new RuntimeException("Couldn't establish SSL handshake.  Try using trustAllSSLCertificates.\n" + e.getLocalizedMessage(), e);
            }
            response.setStatus(code);
            response.setReason(http.getResponseMessage());

            String contentType = http.getContentType();
            log.debug("Content-Type is: " + contentType);
        }

        if (proxy_in == null) {
            try {
                proxy_in = connection.getInputStream();
            } catch (Exception e) {
                // TODO(simon): Whhaaattt?
//                LogSupport.ignore(log, e);
                proxy_in = http.getErrorStream();
            }
        }

        // clear response defaults.
        response.removeField(HttpFields.__Date);
        response.removeField(HttpFields.__Server);

        // set response headers
        int h = 0;
        String hdr = connection.getHeaderFieldKey(h);
        String val = connection.getHeaderField(h);
        while (hdr != null || val != null) {
            if (hdr != null && val != null && !_DontProxyHeaders.containsKey(hdr) && (_chained || !_ProxyAuthHeaders.containsKey(hdr)))
                response.addField(hdr, val);
            h++;
            hdr = connection.getHeaderFieldKey(h);
            val = connection.getHeaderField(h);
        }
        if (!_anonymous)
            response.setField("Via", "1.1 (jetty)");

        response.removeField(HttpFields.__ETag); // possible cksum?  Stop caching...
        response.removeField(HttpFields.__LastModified); // Stop caching...

        // Handled
        long bytesCopied = -1;
        request.setHandled(true);
        if (proxy_in != null) {
            bytesCopied = ModifiedIO.copy(proxy_in, response.getOutputStream());
        }

        return bytesCopied;
    }


    private void adjustRequestForProxyInjection(HttpRequest request, URLConnection connection) {
        request.setState(HttpMessage.__MSG_EDITABLE);
        if (request.containsField("If-Modified-Since")) {
            // TODO: still need to disable caching?  I want to prevent 304s during this development phase where
            // I'm often changing the injection, and so need HTML caching to be absolutely defeated
            request.removeField("If-Modified-Since");
            request.removeField("If-None-Match");
            connection.setUseCaches(false);  // maybe I don't need the stuff above?
        }
        request.removeField("Accept-Encoding");    // js injection is hard w/ gzip'd data, so try to prevent it ahead of time
        request.setState(HttpMessage.__MSG_RECEIVED);
    }

    /* ------------------------------------------------------------ */
    public void handleConnect(String pathInContext, String pathParams, HttpRequest request, HttpResponse response) throws HttpException, IOException {
        URI uri = request.getURI();

        try {
            log.debug("CONNECT: " + uri);
            InetAddrPort addrPort;
            // When logging, we'll attempt to send messages to hosts that don't exist
            if (uri.toString().endsWith(".selenium.doesnotexist:443")) {
                // so we have to do set the host to be localhost (you can't new up an IAP with a non-existent hostname)
                addrPort = new InetAddrPort(443);
            } else {
                addrPort = new InetAddrPort(uri.toString());
            }

            if (isForbidden(HttpMessage.__SSL_SCHEME, addrPort.getHost(), addrPort.getPort(), false)) {
                sendForbid(request, response, uri);
            } else {
                HttpConnection http_connection = request.getHttpConnection();
                if (!ProxyServer.getShouldKeepSslConnectionAlive()) {
                    http_connection.forceClose();
                }

                HttpServer server = http_connection.getHttpServer();

                SslRelay listener = getSslRelayOrCreateNew(uri, addrPort, server);

                int port = listener.getPort();

                // Get the timeout
                int timeoutMs = 30000;
                Object maybesocket = http_connection.getConnection();
                if (maybesocket instanceof Socket) {
                    Socket s = (Socket) maybesocket;
                    timeoutMs = s.getSoTimeout();
                }

                // Create the tunnel
                HttpTunnel tunnel = newHttpTunnel(request, response, InetAddress.getByName(null), port, timeoutMs);

                if (tunnel != null) {
                    // TODO - need to setup semi-busy loop for IE.
                    if (_tunnelTimeoutMs > 0) {
                        tunnel.getSocket().setSoTimeout(_tunnelTimeoutMs);
                        if (maybesocket instanceof Socket) {
                            Socket s = (Socket) maybesocket;
                            s.setSoTimeout(_tunnelTimeoutMs);
                        }
                    }
                    tunnel.setTimeoutMs(timeoutMs);

                    customizeConnection(pathInContext, pathParams, request, tunnel.getSocket());
                    request.getHttpConnection().setHttpTunnel(tunnel);
                    response.setStatus(HttpResponse.__200_OK);
                    response.setContentLength(0);
                }
                request.setHandled(true);
            }
        } catch (Exception e) {
            log.debug("error during handleConnect", e);
            response.sendError(HttpResponse.__500_Internal_Server_Error, e.toString());
        }
    }

    protected SslRelay getSslRelayOrCreateNew(URI uri, InetAddrPort addrPort, HttpServer server) throws Exception {
        SslRelay listener;
        synchronized (_sslMap) {
            listener = _sslMap.get(uri.toString());
            if (listener == null) {
                // we do this because the URI above doesn't actually have the host broken up (it returns null on getHost())
                String host = new URL("https://" + uri.toString()).getHost();

                listener = new SslRelay(addrPort);

                if (useCyberVillains) {
                    wireUpSslWithCyberVilliansCA(host, listener);
                } else {
                    wireUpSslWithRemoteService(host, listener);
                }

                listener.setPassword("password");
                listener.setKeyPassword("password");
                server.addListener(listener);

                synchronized (shutdownLock) {
                    try {
                        if (server.isStarted()) {
                            listener.start();
                        } else {
                            throw new RuntimeException("Can't start SslRelay: server is not started (perhaps it was just shut down?)");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                    }
                }
                _sslMap.put(uri.toString(), listener);
            }
        }
        return listener;
    }

    protected void wireUpSslWithRemoteService(String host, SslRelay listener) throws IOException {
        // grab a keystore that has been signed by a CA cert that has already been imported in to the browser
        // note: this logic assumes the tester is using *custom and has imported the CA cert in to IE/Firefox/etc
        // the CA cert can be found at http://dangerous-certificate-authority.openqa.org
        File keystore = File.createTempFile("selenium-rc-" + host, "keystore");
        String urlString = "http://dangerous-certificate-authority.openqa.org/genkey.jsp?padding=" + _sslMap.size() + "&domain=" + host;

        URL url = new URL(urlString);
        URLConnection conn = url.openConnection();
        conn.connect();
        InputStream is = conn.getInputStream();
        byte[] buffer = new byte[1024];
        int length;
        FileOutputStream fos = new FileOutputStream(keystore);
        while ((length = is.read(buffer)) != -1) {
            fos.write(buffer, 0, length);
        }
        fos.close();
        is.close();

        listener.setKeystore(keystore.getAbsolutePath());
        //listener.setKeystore("c:\\" + (_sslMap.size() + 1) + ".keystore");
        listener.setNukeDirOrFile(keystore);
    }

    protected void wireUpSslWithCyberVilliansCA(String host, SslRelay listener) {
        try {
            File root = File.createTempFile("seleniumSslSupport", host);
            root.delete();
            root.mkdirs();

            ResourceExtractor.extractResourcePath(getClass(), "/sslSupport", root);


            KeyStoreManager mgr = new KeyStoreManager(root);
            mgr.getCertificateByHostname(host);
            mgr.getKeyStore().deleteEntry(KeyStoreManager._caPrivKeyAlias);
            mgr.persist();

            listener.setKeystore(new File(root, "cybervillainsCA.jks").getAbsolutePath());
            listener.setNukeDirOrFile(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    protected HttpTunnel newHttpTunnel(HttpRequest request, HttpResponse response, InetAddress iaddr, int port, int timeoutMS) throws IOException {
        try {
            Socket socket = new Socket(iaddr, port);
            socket.setSoTimeout(timeoutMS);
            socket.setTcpNoDelay(true);
            return new HttpTunnel(socket, null, null);
        } catch (IOException e) {
            log.debug("Exception thrown", e);
            response.sendError(HttpResponse.__400_Bad_Request);
            return null;
        }
    }

      /* ------------------------------------------------------------ */

    /**
     * Customize proxy Socket connection for CONNECT. Method to allow derived handlers to customize
     * the tunnel sockets.
     */
    protected void customizeConnection(String pathInContext, String pathParams, HttpRequest request, Socket socket) {
    }

      /* ------------------------------------------------------------ */

    /**
     * Customize proxy URL connection. Method to allow derived handlers to customize the connection.
     */
    protected void customizeConnection(String pathInContext, String pathParams, HttpRequest request, URLConnection connection) {
    }

      /* ------------------------------------------------------------ */

    /**
     * Is URL Proxied. Method to allow derived handlers to select which URIs are proxied and to
     * where.
     *
     * @param uri The requested URI, which should include a scheme, host and port.
     * @return The URL to proxy to, or null if the passed URI should not be proxied. The default
     * implementation returns the passed uri if isForbidden() returns true.
     */
    protected URL isProxied(URI uri) throws MalformedURLException {
        // Is this a proxy request?
        if (isForbidden(uri))
            return null;

        // OK return URI as untransformed URL.
        return new URL(uri.toString());
    }

      /* ------------------------------------------------------------ */

    /**
     * Is URL Forbidden.
     *
     * @return True if the URL is not forbidden. Calls isForbidden(scheme,host,port,true);
     */
    protected boolean isForbidden(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        return isForbidden(scheme, host, port, true);
    }

      /* ------------------------------------------------------------ */

    /**
     * Is scheme,host & port Forbidden.
     *
     * @param scheme           A scheme that mast be in the proxySchemes StringMap.
     * @param host             A host that must pass the white and black lists
     * @param port             A port that must in the allowedConnectPorts Set
     * @param openNonPrivPorts If true ports greater than 1024 are allowed.
     * @return True if the request to the scheme,host and port is not forbidden.
     */
    protected boolean isForbidden(String scheme, String host, int port, boolean openNonPrivPorts) {
        // Check port
        if (false) { // DGF Don't check the port, SRC-354
            if (port > 0 && !_allowedConnectPorts.contains(new Integer(port))) {
                if (!openNonPrivPorts || port <= 1024)
                    return true;
            }
        }

        // Must be a scheme that can be proxied.
        if (scheme == null || !_ProxySchemes.containsKey(scheme))
            return true;

        // Must be in any defined white list
        if (_proxyHostsWhiteList != null && !_proxyHostsWhiteList.contains(host))
            return true;

        // Must not be in any defined black list
        return _proxyHostsBlackList != null && _proxyHostsBlackList.contains(host);

    }

      /* ------------------------------------------------------------ */

    /**
     * Send Forbidden. Method called to send forbidden response. Default implementation calls
     * sendError(403)
     */
    protected void sendForbid(HttpRequest request, HttpResponse response, URI uri) throws IOException {
        response.sendError(HttpResponse.__403_Forbidden, "Forbidden for Proxy");
    }

    /**
     * Send not found. Method called to send not found response. Default implementation calls
     * sendError(404)
     */
    protected void sendNotFound(HttpResponse response) throws IOException {
        response.sendError(HttpResponse.__404_Not_Found, "Not found");
    }

      /* ------------------------------------------------------------ */

    /**
     * @return Returns the anonymous.
     */
    public boolean isAnonymous() {
        return _anonymous;
    }

      /* ------------------------------------------------------------ */

    /**
     * @param anonymous The anonymous to set.
     */
    public void setAnonymous(boolean anonymous) {
        _anonymous = anonymous;
    }

    public void setSslKeystorePath(String sslKeystorePath) {
        this.sslKeystorePath = sslKeystorePath;
    }

    public void setShutdownLock(Object shutdownLock) {

        this.shutdownLock = shutdownLock;
    }

    public static class SslRelay extends SslListener {
        InetAddrPort _addr;
        File nukeDirOrFile;

        SslRelay(InetAddrPort addr) {
            _addr = addr;
        }

        public void setNukeDirOrFile(File nukeDirOrFile) {
            this.nukeDirOrFile = nukeDirOrFile;
        }

        protected void customizeRequest(Socket socket, HttpRequest request) {
            super.customizeRequest(socket, request);
            URI uri = request.getURI();

            // Convert the URI to a proxy URL
            //
            // NOTE: Don't just add a host + port to the request URI, since this causes the URI to
            // get "dirty" and be rewritten, potentially breaking the proxy slightly. Instead,
            // create a brand new URI that includes the protocol, the host, and the port, but leaves
            // intact the path + query string "as is" so that it does not get rewritten.
            request.setURI(new URI("https://" + _addr.getHost() + ":" + _addr.getPort() + uri.toString()));
        }

        public void stop() throws InterruptedException {
            super.stop();

            if (nukeDirOrFile != null) {
                if (nukeDirOrFile.isDirectory()) {
                    LauncherUtils.recursivelyDeleteDir(nukeDirOrFile);
                } else {
                    nukeDirOrFile.delete();
                }
            }
        }
    }
}
