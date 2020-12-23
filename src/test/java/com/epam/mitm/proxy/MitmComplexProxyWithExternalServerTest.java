package com.epam.mitm.proxy;

import com.epam.mitm.proxy.help.AbstractComplexProxyTool;
import com.epam.mitm.proxy.help.DefaultRequestInterceptor;
import com.epam.mitm.proxy.help.DefaultResponseInterceptor;
import com.epam.mitm.proxy.help.ResponseInfo;
import org.apache.http.HttpHost;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests just a single basic proxy running as a man in the middle.
 */
public class MitmComplexProxyWithExternalServerTest extends AbstractComplexProxyTool {

    @Override
    protected void setUp() {
        String stubUrl = "http://127.0.0.1:" + stubServerPort + "/stub";
        LOGGER.info("STUB URL used: {}", stubUrl);
        DefaultRequestInterceptor defaultRequestInterceptor = new DefaultRequestInterceptor(requestCount, NEED_STUB_RESPONSE, stubUrl);
        DefaultResponseInterceptor defaultResponseInterceptor = new DefaultResponseInterceptor(responseCount);
        proxyServer.addRequestInterceptor(defaultRequestInterceptor);
        proxyServer.addResponseInterceptor(defaultResponseInterceptor);
        proxyServer.setCaptureBinaryContent(false);
        proxyServer.setCaptureContent(false);
        ProxyServer.setResponseVolatile(true);
    }

    @Test
    public void testSimpleLocalGetRequestOverHTTPSThroughProxy() throws Exception {
        String CALL = "/ok";
        HttpHost externalHost = new HttpHost("127.0.0.1", 8443, "https");
        try {
            httpGetWithApacheClient(externalHost, CALL, false, false);
        } catch (Exception e) {
            externalHost = null;
        }
        org.junit.Assume.assumeTrue(externalHost != null);
        //do test if available
        ResponseInfo proxiedResponse = httpGetWithApacheClient(externalHost, CALL, true, false);
        assertEquals(200, proxiedResponse.getStatusCode());
        assertTrue(proxiedResponse.getBody().contains("Wilma Test Server"));
        assertEquals(1, responseCount.get());
        assertEquals(1, requestCount.get());
    }

    @Test
    public void testSimpleLocalGetRequestOverHTTPSWithoutProxy() throws Exception {
        String CALL = "/ok";
        HttpHost externalHost = new HttpHost("127.0.0.1", 8443, "https");
        try {
            httpGetWithApacheClient(externalHost, CALL, false, false);
        } catch (Exception e) {
            externalHost = null;
        }
        org.junit.Assume.assumeTrue(externalHost != null);
        //do test if available
        ResponseInfo proxiedResponse = httpGetWithApacheClient(externalHost, CALL, false, false);
        assertEquals(200, proxiedResponse.getStatusCode());
        assertTrue(proxiedResponse.getBody().contains("Wilma Test Server"));
        assertEquals(0, responseCount.get());
        assertEquals(0, requestCount.get());
    }

    @Test
    public void testSimpleRemoteGetRequestOverHTTPSThroughProxy() throws Exception {
        //check if external test server is available
        String CALL = "/search?q=mitmJavaProxy";
        HttpHost externalHost = new HttpHost("www.google.com", 443, "https");
        try {
            httpGetWithApacheClient(externalHost, CALL, false, false);
        } catch (Exception e) {
            externalHost = null;
        }
        org.junit.Assume.assumeTrue(externalHost != null);
        //do test if available
        ResponseInfo proxiedResponse = httpGetWithApacheClient(externalHost, CALL, true, false);
        assertEquals(200, proxiedResponse.getStatusCode());
        assertEquals(1, responseCount.get());
        assertEquals(1, requestCount.get());
    }

    @Test
    public void testSimpleRemoteGetRequestOverHTTPSWithoutProxy() throws Exception {
        //check if external test server is available
        String CALL = "/search?q=mitmJavaProxy";
        HttpHost externalHost = new HttpHost("www.google.com", 443, "https");
        try {
            httpGetWithApacheClient(externalHost, CALL, false, false);
        } catch (Exception e) {
            externalHost = null;
        }
        org.junit.Assume.assumeTrue(externalHost != null);
        //do test if available
        ResponseInfo proxiedResponse = httpGetWithApacheClient(externalHost, CALL, false, false);
        assertEquals(200, proxiedResponse.getStatusCode());
        assertEquals(0, responseCount.get());
        assertEquals(0, requestCount.get());
    }


}