package website.magyar.mitm.proxy.help;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.client.entity.DeflateDecompressingEntity;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import website.magyar.mitm.proxy.ProxyServer;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base for tests that test the proxy. This base class encapsulates all of the
 * testing infrastructure, so has:
 * - the proxy itself with default request/response interceptors, that is able to select between servers (server or stub)
 * - server that answers to the client (SERVER_BACKEND)
 * - a stub server that answers to the client (STUB_BACKEND)
 *
 * @author Tamas_Kohegyi
 */
public abstract class AbstractComplexProxyTool {

    /**
     * The server used by the tests.
     */
    public static final int PROXY_TIMEOUT = 1200000; //20 minute - giving time to debug
    private static final int GRACE_PERIOD = 500; //0.5 sec
    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractComplexProxyTool.class);
    protected static final String NO_NEED_STUB_RESPONSE = "/getServer";
    protected static final String NEED_STUB_RESPONSE = "/getStub";
    protected static final String SERVER_BACKEND = "server-backend";
    protected static final String STUB_BACKEND = "stub-backend";
    private ProxyServer proxyServer;
    private int webServerPort = -1;
    protected int stubServerPort = -1;
    protected int httpsWebServerPort = -1;
    private int proxyPort = -1;
    protected HttpHost webHost;
    protected HttpHost httpsWebHost;
    protected HttpHost stubHost;
    protected AtomicInteger requestCount;
    protected AtomicInteger responseCount;
    /**
     * The web server that provides the back-end.
     */
    private Server webServer;
    /**
     * The web server that provides the back-end.
     */
    private Server stubServer;

    public ProxyServer getProxyServer() {
        return proxyServer;
    }
    public int getWebServerPort() {
        return webServerPort;
    }

    public int getStubServerPort() {
        return stubServerPort;
    }

    public int getHttpsWebServerPort() {
        return httpsWebServerPort;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public HttpHost getWebHost() {
        return webHost;
    }

    public HttpHost getHttpsWebHost() {
        return httpsWebHost;
    }

    public HttpHost getStubHost() {
        return stubHost;
    }

    public AtomicInteger getRequestCount() {
        return requestCount;
    }

    public AtomicInteger getResponseCount() {
        return responseCount;
    }

    @BeforeEach
    public void runSetup() throws Exception {
        initializeCounters();
        startServers();
        startProxy();
        LOGGER.info("*** Backed http Server started on port: {}", webServerPort);
        LOGGER.info("*** Backed httpS Server started on port: {}", httpsWebServerPort);
        LOGGER.info("*** Backed http/stub Server started on port: {}", stubServerPort);
        LOGGER.info("*** Proxy Server started on port: {}", proxyPort);
        //and finally
        setUp();
        Thread.sleep(GRACE_PERIOD);
        LOGGER.info("*** Setup DONE - starting TEST");
    }

    protected abstract void setUp() throws Exception;

    private void initializeCounters() {
        requestCount = new AtomicInteger(0);
        responseCount = new AtomicInteger(0);
    }

    private void startProxy() throws Exception {
        proxyServer = new ProxyServer(0);
        proxyServer.start(PROXY_TIMEOUT);
        proxyPort = proxyServer.getPort();
        ProxyServer.setShouldKeepSslConnectionAlive(false);
        Thread.sleep(GRACE_PERIOD);
    }

    private void startServers() {
        webServer = TestUtils.startWebServerWithResponse(true, SERVER_BACKEND.getBytes());
        stubServer = TestUtils.startWebServerWithResponse(false, STUB_BACKEND.getBytes());
        // find out what ports the HTTP and HTTPS connectors were bound to
        httpsWebServerPort = TestUtils.findLocalHttpsPort(webServer);
        if (httpsWebServerPort < 0) {
            throw new RuntimeException("HTTPS connector should already be open and listening, but port was " + webServerPort);
        }

        webServerPort = TestUtils.findLocalHttpPort(webServer);
        if (webServerPort < 0) {
            throw new RuntimeException("HTTP connector should already be open and listening, but port was " + webServerPort);
        }

        stubServerPort = TestUtils.findLocalHttpPort(stubServer);
        if (stubServerPort < 0) {
            throw new RuntimeException("HTTP connector should already be open and listening, but port was " + stubServerPort);
        }

        stubHost = new HttpHost("127.0.0.1", stubServerPort);
        webHost = new HttpHost("127.0.0.1", webServerPort);
        httpsWebHost = new HttpHost("127.0.0.1", httpsWebServerPort, "https");

    }

    @AfterEach
    public void runTearDown() throws Exception {
        LOGGER.info("*** Test DONE - starting TEARDOWN");

        try {
            tearDown();
        } finally {
            try {
                if (this.proxyServer != null) {
                    this.proxyServer.stop();
                }
            } finally {
                if (this.webServer != null) {
                    webServer.stop();
                }
                if (this.stubServer != null) {
                    stubServer.stop();
                }
            }
        }

    }

    protected void tearDown() throws Exception {
    }

    protected ResponseInfo httpPostWithApacheClient(HttpHost host, String resourceUrl, boolean isProxied, ContentEncoding contentEncoding) throws Exception {
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(isProxied, proxyServer.getPort(), contentEncoding)) {
            final HttpPost request = new HttpPost(resourceUrl);
            final StringEntity entity = new StringEntity("adsf", "UTF-8");
            entity.setChunked(true);
            request.setEntity(entity);

            final HttpResponse response = httpClient.execute(host, request);
            Thread.sleep(GRACE_PERIOD);
            HttpEntity resEntity = response.getEntity();
            Header contentEncodingHeader = resEntity.getContentEncoding();

            if (contentEncodingHeader != null) {
                HeaderElement[] encodings = contentEncodingHeader.getElements();
                for (HeaderElement encoding : encodings) {
                    if (encoding.getName().equalsIgnoreCase("gzip")) {
                        resEntity = new GzipDecompressingEntity(resEntity);
                        break;
                    }
                    if (encoding.getName().equalsIgnoreCase("deflate")) {
                        resEntity = new DeflateDecompressingEntity(resEntity);
                        break;
                    }
                    if (encoding.getName().equalsIgnoreCase("br")) {
                        resEntity = new BrotliDecompressingEntity(resEntity);
                        break;
                    }
                }
            }

            String output = EntityUtils.toString(resEntity, Charset.forName("UTF-8").name());

            return new ResponseInfo(response.getStatusLine().getStatusCode(), output, contentEncodingHeader);
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw e;
        }
    }

    protected ResponseInfo httpGetWithApacheClient(HttpHost host, String resourceUrl, boolean isProxied, boolean callHeadFirst, ContentEncoding contentEncoding) throws Exception {
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(isProxied, proxyServer.getPort(), contentEncoding)) {

            Integer contentLength = null;
            if (callHeadFirst) {
                HttpHead request = new HttpHead(resourceUrl);
                HttpResponse response = httpClient.execute(host, request);
                contentLength = Integer.valueOf(response.getFirstHeader("Content-Length").getValue());
            }

            HttpGet request = new HttpGet(resourceUrl);

            HttpResponse response = httpClient.execute(host, request);
            if (contentLength != null) {
                Assertions.assertEquals(
                        contentLength,
                        Integer.valueOf(response.getFirstHeader("Content-Length").getValue()),
                        "Content-Length from GET should match that from HEAD");
            }

            HttpEntity resEntity = response.getEntity();
            Thread.sleep(GRACE_PERIOD);

            Header contentEncodingHeader = resEntity.getContentEncoding();

            if (contentEncodingHeader != null) {
                HeaderElement[] encodings = contentEncodingHeader.getElements();
                for (HeaderElement encoding : encodings) {
                    if (encoding.getName().equalsIgnoreCase("gzip")) {
                        resEntity = new GzipDecompressingEntity(resEntity);
                        break;
                    }
                    if (encoding.getName().equalsIgnoreCase("deflate")) {
                        resEntity = new DeflateDecompressingEntity(resEntity);
                        break;
                    }
                    if (encoding.getName().equalsIgnoreCase("br")) {
                        resEntity = new BrotliDecompressingEntity(resEntity);
                        break;
                    }
                }
            }

            String output = EntityUtils.toString(resEntity, Charset.forName("UTF-8").name());

            return new ResponseInfo(response.getStatusLine().getStatusCode(), output, contentEncodingHeader);
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw e;
        }
    }

}
