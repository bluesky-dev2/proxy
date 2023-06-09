package website.magyar.mitm.proxy.response;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import website.magyar.mitm.proxy.ProxyServer;
import website.magyar.mitm.proxy.ResponseInterceptor;
import website.magyar.mitm.proxy.help.ClientServerBase;
import website.magyar.mitm.proxy.help.ContentEncoding;
import website.magyar.mitm.proxy.help.ProxyServerBase;
import website.magyar.mitm.proxy.help.TestUtils;
import website.magyar.mitm.proxy.http.MitmJavaProxyHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * This test checks if the response body can be accessed and altered by the response interceptors.
 * Tests:
 * - No additional header - body untouched
 * - If header "A" added - reduce body size to 5 chars
 * - If header "B" added - duplicate the body text
 * - If header "C" added - replace it with a json message (changes content-type too)
 * - If header "D" added - tries to get the response as byte[], meanwhile the content is not altered
 * - If header "E" added - tries to get the response as byte[], meanwhile the content is altered (replaced with a json message)
 * - If header "F" added - tries to change the status to E404, meanwhile the content is not altered
 * - If header "G" added - tries to change the status to E503, status text to "MITM" meanwhile the content is not altered
 * - If header "H" added - tries to change the status text to "MITM2" meanwhile don1t touch the status or the message content
 *
 * @author Tamas_Kohegyi
 */
public class ResponseBodyManipulationTest extends ClientServerBase {
    public static final String GET_REQUEST = "/anyUrl";
    private static final String REQ_JSON_BODY = "{ \"json\": \"simple text\" }";
    private final Logger logger = LoggerFactory.getLogger(ResponseBodyManipulationTest.class);
    private HttpGet request;

    @Override
    protected void setUp() throws Exception {
        TestResponseInterceptor testResponseInterceptor = new TestResponseInterceptor();
        getProxyServer().addResponseInterceptor(testResponseInterceptor);
        ProxyServer.setResponseVolatile(true); //this is a must !!!
        //this is a must have body available via getBodyString + need string type of Content-Type header
        getProxyServer().setCaptureContent(true);
        //this is a must have body available via getBodyString in case of non-text contents - Base64 encoding is used + need Content-Type header
        getProxyServer().setCaptureBinaryContent(true);
        //note that if response has no Content-Type header then response body is available via getBodyBytes() method.
        request = new HttpGet(GET_REQUEST);
    }

    @Override
    protected int getProxyTimeout() {
        return ProxyServerBase.PROXY_LONG_TIMEOUT;
    }

    @Override
    protected void tearDown() {
    }

    @Override
    protected void evaluateServerRequestResponse(HttpServletRequest request, HttpServletResponse response, String bodyString) {
    }

    @Test
    public void bodyInterceptedAndAccessibleButResponseIsNotVolatile() throws Exception {
        ProxyServer.setResponseVolatile(false); //interceptor shall not influence the response !
        request.addHeader("A", "A"); //this orders interceptor to alter the response
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        }
    }

    @Test
    public void noRequestBodyChange() throws Exception {
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        }
    }

    @Test
    public void noRequestBodyChangeSecure() throws Exception {
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void reduceTo5Chars() throws Exception {
        request.addHeader("A", "A");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is reduced to 5 chars length
            assertEquals(SERVER_BACKEND.substring(0, 5), body);
        }
    }

    @Test
    public void reduceTo5CharsSecure() throws Exception {
        request.addHeader("A", "A");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is reduced to 5 chars length
            assertEquals(SERVER_BACKEND.substring(0, 5), body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void doubleBodySize() throws Exception {
        request.addHeader("B", "B");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is doubled
            assertEquals(SERVER_BACKEND + SERVER_BACKEND, body);
        }
    }

    @Test
    public void doubleBodySizeSecure() throws Exception {
        request.addHeader("B", "B");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is doubled
            assertEquals(SERVER_BACKEND + SERVER_BACKEND, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void replaceWithJson() throws Exception {
        request.addHeader("C", "C");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is a json string
            assertEquals(REQ_JSON_BODY, body);
            assertEquals("application/json", response.getEntity().getContentType().getValue());
        }
    }

    @Test
    public void replaceWithJsonSecure() throws Exception {
        request.addHeader("C", "C");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals("application/json", response.getEntity().getContentType().getValue());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is a json string
            assertEquals(REQ_JSON_BODY, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void getResponseAsByteResponseNotAltered() throws Exception {
        request.addHeader("D", "D");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            httpClient.close();
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        }
    }

    @Test
    public void getResponseAsByteResponseNotAlteredSecure() throws Exception {
        request.addHeader("D", "D");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void getResponseAsByteResponseIsAltered() throws Exception {
        request.addHeader("E", "E");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is a json string
            assertEquals(REQ_JSON_BODY, body);
            assertEquals("application/json", response.getEntity().getContentType().getValue());
        }
    }

    @Test
    public void getResponseAsByteResponseIsAlteredSecure() throws Exception {
        request.addHeader("E", "E");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertNull(getLastException());
            //check that answer is a json string
            assertEquals(REQ_JSON_BODY, body);
            assertEquals("application/json", response.getEntity().getContentType().getValue());
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void alterStatusStatusTextNotAltered() throws Exception {
        request.addHeader("F", "F");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            httpClient.close();
            assertEquals(404, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertEquals("OK", response.getStatusLine().getReasonPhrase(), "HTTP Response Reason Phrase is:" + response.getStatusLine().getReasonPhrase());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        }
    }

    @Test
    public void alterStatusStatusTextNotAlteredSecure() throws Exception {
        request.addHeader("F", "F");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(404, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertEquals("OK", response.getStatusLine().getReasonPhrase(), "HTTP Response Reason Phrase is:" + response.getStatusLine().getReasonPhrase());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void alterStatusStatusTextAltered() throws Exception {
        request.addHeader("G", "G");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            httpClient.close();
            assertEquals(503, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertEquals("MITM", response.getStatusLine().getReasonPhrase(), "HTTP Response Reason Phrase is:" + response.getStatusLine().getReasonPhrase());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        }
    }

    @Test
    public void alterStatusSecureStatusTextAltered() throws Exception {
        request.addHeader("G", "G");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(503, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertEquals("MITM", response.getStatusLine().getReasonPhrase(), "HTTP Response Reason Phrase is:" + response.getStatusLine().getReasonPhrase());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    @Test
    public void alterStatusTextStatusNotAltered() throws Exception {
        request.addHeader("H", "H");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getHttpHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            httpClient.close();
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertEquals("MITM2", response.getStatusLine().getReasonPhrase(), "HTTP Response Reason Phrase is:" + response.getStatusLine().getReasonPhrase());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        }
    }

    @Test
    public void alterStatusTextStatusNotAlteredSecure() throws Exception {
        request.addHeader("H", "H");
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(true, getProxyPort(), ContentEncoding.ANY)) {
            HttpResponse response = httpClient.execute(getSecureHost(), request); //request is here
            String body = EntityUtils.toString(response.getEntity());
            assertEquals(200, response.getStatusLine().getStatusCode(), "HTTP Response Status code is:" + response.getStatusLine().getStatusCode());
            assertEquals("MITM2", response.getStatusLine().getReasonPhrase(), "HTTP Response Reason Phrase is:" + response.getStatusLine().getReasonPhrase());
            assertNull(getLastException());
            //check that answer is not changed
            assertEquals(SERVER_BACKEND, body);
        } catch (SSLException | IndexOutOfBoundsException e) {
            logger.error("Ups", e);
            throw e;
        }
    }

    class TestResponseInterceptor implements ResponseInterceptor {

        @Override
        public void process(MitmJavaProxyHttpResponse response) {
            String body = response.getBodyString(); // getBody works only in case the response content type is a kind of text

            byte[] newBody = null;
            Header[] requestHeaders = response.getRequestHeaders();

            detectIssue(!SERVER_BACKEND.equals(body), "Cannot find the expected body");

            //alter body - if 'A' header - to 5 char long
            if (response.findHeader(requestHeaders, "A") != null) {
                newBody = body.substring(0, 5).getBytes(StandardCharsets.UTF_8);
            }

            //alter body - if 'B' header - double the body
            if (response.findHeader(requestHeaders, "B") != null) {
                newBody = (body + body).getBytes(StandardCharsets.UTF_8);
            }

            //alter body - if 'C' header - use json request
            if (response.findHeader(requestHeaders, "C") != null) {
                newBody = REQ_JSON_BODY.getBytes(StandardCharsets.UTF_8);
                response.setContentType("application/json");
            }

            //don't alter body - if 'D' header - but get body as byte[]
            if (response.findHeader(requestHeaders, "D") != null) {
                byte[] oldBody = response.getBodyBytes();
                String bodyString = new String(oldBody);
                detectIssue(!SERVER_BACKEND.equals(bodyString), "Cannot find the expected body");
            }

            //alter body - if 'E' header - use json request + get raw body too
            if (response.findHeader(requestHeaders, "E") != null) {
                byte[] oldBody = response.getBodyBytes();
                String bodyString = new String(oldBody);
                detectIssue(!SERVER_BACKEND.equals(bodyString), "Cannot find the expected body");
                newBody = REQ_JSON_BODY.getBytes(StandardCharsets.UTF_8);
                response.setContentType("application/json");
            }

            //alter body - if 'F' header - change response status to 404, don't touch status text
            if (response.findHeader(requestHeaders, "F") != null) {
                byte[] oldBody = response.getBodyBytes();
                String bodyString = new String(oldBody);
                detectIssue(!SERVER_BACKEND.equals(bodyString), "Cannot find the expected body");
                response.setStatus(404);
            }

            //alter body - if 'G' header - change response status to 503 and status text as "MITM"
            if (response.findHeader(requestHeaders, "G") != null) {
                byte[] oldBody = response.getBodyBytes();
                String bodyString = new String(oldBody);
                detectIssue(!SERVER_BACKEND.equals(bodyString), "Cannot find the expected body");
                response.setStatus(503);
                response.setReasonPhrase("MITM");
            }

            //alter body - if 'H' header - change status text as "MITM2", don't change the status
            if (response.findHeader(requestHeaders, "H") != null) {
                byte[] oldBody = response.getBodyBytes();
                String bodyString = new String(oldBody);
                detectIssue(!SERVER_BACKEND.equals(bodyString), "Cannot find the expected body");
                response.setReasonPhrase("MITM2");
            }

            try {
                response.setBody(newBody);
            } catch (IOException e) {
                registerIssue(e);
            }
        }
    }
}
