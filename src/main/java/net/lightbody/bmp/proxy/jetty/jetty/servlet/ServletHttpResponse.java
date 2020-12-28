// ========================================================================
// $Id: ServletHttpResponse.java,v 1.65 2006/04/04 22:28:05 gregwilkins Exp $
// Copyright 2000-2004 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package net.lightbody.bmp.proxy.jetty.jetty.servlet;

import net.lightbody.bmp.proxy.jetty.http.HttpContext;
import net.lightbody.bmp.proxy.jetty.http.HttpFields;
import net.lightbody.bmp.proxy.jetty.http.HttpOutputStream;
import net.lightbody.bmp.proxy.jetty.http.HttpResponse;
import net.lightbody.bmp.proxy.jetty.util.IO;
import net.lightbody.bmp.proxy.jetty.util.StringUtil;
import net.lightbody.bmp.proxy.jetty.util.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

/**
 * Servlet Response Wrapper.
 * This class wraps a Jetty HTTP response as a 2.2 Servlet response.
 * <p>
 * Note that this wrapper is not synchronized and if a response is to
 * be operated on by multiple threads, then higher level synchronizations may be required.
 *
 * @author Greg Wilkins (gregw)
 * @version $Id: ServletHttpResponse.java,v 1.65 2006/04/04 22:28:05 gregwilkins Exp $
 */
public class ServletHttpResponse implements HttpServletResponse {
    private static final Logger log = LoggerFactory.getLogger(ServletHttpResponse.class);
    public static final int
            DISABLED = -1,
            NO_OUT = 0,
            OUTPUTSTREAM_OUT = 1,
            WRITER_OUT = 2;
    private static ServletWriter __nullServletWriter;
    private static ServletOut __nullServletOut;

    static {
        try {
            __nullServletWriter = new ServletWriter(IO.getNullStream());
            __nullServletOut = new ServletOut(IO.getNullStream());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            System.exit(1);
        }
    }

    private final HttpResponse _httpResponse;
    private final ServletHttpRequest _servletHttpRequest;
    private int _outputState = NO_OUT;
    private ServletOut _out = null;
    private ServletWriter _writer = null;
    private HttpSession _session = null;
    private boolean _noSession = false;
    private Locale _locale = null;
    private boolean _charEncodingSetInContentType = false;

    public ServletHttpResponse(ServletHttpRequest request, HttpResponse response) {
        _servletHttpRequest = request;
        _servletHttpRequest.setServletHttpResponse(this);
        _httpResponse = response;
    }

    /**
     * Unwrap a ServletResponse.
     *
     * @param response
     * @return The core ServletHttpResponse which must be the underlying response object
     * @see javax.servlet.ServletResponseWrapper
     * @see javax.servlet.http.HttpServletResponseWrapper
     */
    public static ServletHttpResponse unwrap(ServletResponse response) {
        while (!(response instanceof ServletHttpResponse)) {
            if (response instanceof ServletResponseWrapper) {
                ServletResponseWrapper wrapper = (ServletResponseWrapper) response;
                response = wrapper.getResponse();
            } else {
                throw new IllegalArgumentException("Does not wrap ServletHttpResponse");
            }
        }

        return (ServletHttpResponse) response;
    }

    void recycle() {
        _outputState = NO_OUT;
        _out = null;
        _writer = null;
        _session = null;
        _noSession = false;
        _locale = null;
        _charEncodingSetInContentType = false;
    }

    int getOutputState() {
        return _outputState;
    }

    void setOutputState(int s)
            throws IOException {
        if (s < 0) {
            _outputState = DISABLED;
            if (_writer != null) {
                _writer.disable();
            }
            _writer = null;
            if (_out != null) {
                _out.disable();
            }
            _out = null;
        } else
            _outputState = s;
    }

    HttpResponse getHttpResponse() {
        return _httpResponse;
    }

    void commit() throws IOException {
        if (_writer != null && _writer.isWritten()) {
            _writer.flush();
        } else {
            _httpResponse.commit();
        }
    }

    void complete() throws IOException {
        _httpResponse.completing();
        commit();
        setOutputState(DISABLED);
    }

    public boolean isCommitted() {
        return _httpResponse.isCommitted();
    }
    
    boolean isDirty() {
        return _httpResponse.isDirty();
    }

    public int getBufferSize() {
        return ((HttpOutputStream) _httpResponse.getOutputStream()).getBufferSize();
    }

    public void setBufferSize(int size) {
        HttpOutputStream out = (HttpOutputStream) _httpResponse.getOutputStream();
        if (out.isWritten() || _writer != null && _writer.isWritten()) {
            throw new IllegalStateException("Output written");
        }
        out.setBufferSize(size);
    }

    public void flushBuffer() throws IOException {
        if (((HttpOutputStream) _httpResponse.getOutputStream()).isClosed()) {
            return;
        }

        if (_writer != null) {
            _writer.flush();
        }
        if (_out != null) {
            _out.flush();
        }
        if (_writer == null && _out == null) {
            _httpResponse.getOutputStream().flush();
        }
        if (!_httpResponse.isCommitted()) {
            _httpResponse.commit();
        }
    }

    public void resetBuffer() {
        if (isCommitted()) {
            throw new IllegalStateException("Committed");
        }

        ((HttpOutputStream) _httpResponse.getOutputStream()).resetBuffer();
        if (_writer != null) {
            _writer.reset();
        }
    }

    public void reset() {
        resetBuffer();
        _httpResponse.reset();
    }

    public Locale getLocale() {
        if (_locale == null) {
            return Locale.getDefault();
        }
        return _locale;
    }

    /**
     * Sets the locale of the response, setting the headers (including the Content-Type's charset) as appropriate.
     * This method should be called before a call to {@link #getWriter}.  By default, the response locale
     * is the default locale for the server.
     *
     * @param locale the Locale of the response
     * @see #getLocale
     */
    public void setLocale(Locale locale) {
        if (locale == null || isCommitted()) {
            return;
        }

        _locale = locale;
        setHeader(HttpFields.__ContentLanguage, locale.toString().replace('_', '-'));

        if (this._outputState == 0) {
            /* get current MIME type from Content-Type header */
            String type = _httpResponse.getField(HttpFields.__ContentType);
            if (type == null) {
                // servlet did not set Content-Type yet
                // so lets assume default one
                type = "application/octet-stream";
            }

            HttpContext httpContext = _servletHttpRequest.getServletHandler().getHttpContext();
            if (httpContext instanceof ServletHttpContext) {
                String charset = ((ServletHttpContext) httpContext).getLocaleEncoding(locale);
                if (charset != null && charset.length() > 0) {
                    int semi = type.indexOf(';');
                    if (semi < 0) {
                        type += "; charset=" + charset;
                    } else {
                        if (!_charEncodingSetInContentType) {
                            type = type.substring(0, semi) + "; charset=" + charset;
                        }
                    }
                    setHeader(HttpFields.__ContentType, type);
                }
            }
        }
    }

    public void addCookie(Cookie cookie) {
        _httpResponse.addSetCookie(cookie);
    }

    public boolean containsHeader(String name) {
        return _httpResponse.containsField(name);
    }

    public String encodeURL(String url) {
        // should not encode if cookies in evidence
        if (_servletHttpRequest == null
                || _servletHttpRequest.isRequestedSessionIdFromCookie()
                && _servletHttpRequest.getServletHandler().isUsingCookies()) {
            return url;
        }

        // get session;
        if (_session == null && !_noSession) {
            _session = _servletHttpRequest.getSession(false);
            _noSession = (_session == null);
        }

        // no session or no url
        if (_session == null || url == null) {
            return url;
        }

        // invalid session
        String id = _session.getId();
        if (id == null) {
            return url;
        }

        // Check host and port are for this server
        // TODO not implemented

        // Already encoded
        int prefix = url.indexOf(SessionManager.__SessionUrlPrefix);
        if (prefix != -1) {
            int suffix = url.indexOf("?", prefix);
            if (suffix < 0) {
                suffix = url.indexOf("#", prefix);
            }

            if (suffix <= prefix) {
                return url.substring(0, prefix + SessionManager.__SessionUrlPrefix.length()) + id;
            }
            return url.substring(0, prefix + SessionManager.__SessionUrlPrefix.length()) + id + url.substring(suffix);
        }

        // edit the session
        int suffix = url.indexOf('?');
        if (suffix < 0) {
            suffix = url.indexOf('#');
        }
        if (suffix < 0) {
            return url + SessionManager.__SessionUrlPrefix + id;
        }
        return url.substring(0, suffix) + SessionManager.__SessionUrlPrefix + id + url.substring(suffix);
    }

    public String encodeRedirectURL(String url) {
        return encodeURL(url);
    }

    /**
     * @deprecated As of version 2.1, use encodeURL(String url) instead.
     */
    public String encodeUrl(String url) {
        return encodeURL(url);
    }

    /**
     * @deprecated As of version 2.1, use encodeRedirectURL(String url) instead.
     */
    public String encodeRedirectUrl(String url) {
        return encodeRedirectURL(url);
    }

    public void sendError(int status, String message) throws IOException {
        // Find  error page.
        String error_page = _servletHttpRequest.getServletHandler().getErrorPage(status, _servletHttpRequest);

        resetBuffer();

        // Handle error page?
        if (error_page == null) {
            // handle normally
            _httpResponse.sendError(status, message);
        } else {
            throw new UnsupportedEncodingException("This code part should not have been reached.");
        }
        complete();
    }

    public void sendError(int status) throws IOException {
        sendError(status, null);
    }

    public void sendRedirect(String url) throws IOException {
        if (url == null) {
            throw new IllegalArgumentException();
        }

        if (!URI.hasScheme(url)) {
            StringBuffer buf = _servletHttpRequest.getHttpRequest().getRootURL();
            if (url.startsWith("/")) {
                buf.append(URI.canonicalPath(url));
            } else {
                String path = _servletHttpRequest.getRequestURI();
                String parent = (path.endsWith("/")) ? path : URI.parentPath(path);
                url = URI.canonicalPath(URI.addPaths(parent, url));
                if (!url.startsWith("/")) {
                    buf.append('/');
                }
                buf.append(url);
            }

            url = buf.toString();
        }

        resetBuffer();

        _httpResponse.setField(HttpFields.__Location, url);
        _httpResponse.setStatus(HttpResponse.__302_Moved_Temporarily);
        complete();
    }

    public void setDateHeader(String name, long value) {
        try {
            _httpResponse.setDateField(name, value);
        } catch (IllegalStateException e) {
            //
        }
    }

    public void setHeader(String name, String value) {
        try {
            _httpResponse.setField(name, value);
        } catch (IllegalStateException e) {
            //
        }
    }

    public void setIntHeader(String name, int value) {
        try {
            _httpResponse.setIntField(name, value);
        } catch (IllegalStateException e) {
            //
        }
    }
    
    public void addDateHeader(String name, long value) {
        try {
            _httpResponse.addDateField(name, value);
        } catch (IllegalStateException e) {
            //
        }
    }
    
    public void addHeader(String name, String value) {
        try {
            _httpResponse.addField(name, value);
        } catch (IllegalStateException e) {
            //
        }
    }

    public void addIntHeader(String name, int value) {
        try {
            _httpResponse.addIntField(name, value);
        } catch (IllegalStateException e) {
            //
        }
    }

    public void setStatus(int status) {
        _httpResponse.setStatus(status);
    }

    /**
     * @param status  the status code
     * @param message the status message
     * @deprecated As of version 2.1 of the Servlet spec.
     * To set a status code
     * use <code>setStatus(int)</code>, to send an error with a description
     * use <code>sendError(int, String)</code>.
     * <p>
     * Sets the status code and message for this response.
     */
    public void setStatus(int status, String message) {
        setStatus(status);
        _httpResponse.setReason(message);
    }

    public ServletOutputStream getOutputStream() {
        if (_outputState == DISABLED) {
            return __nullServletOut;
        }

        if (_outputState != NO_OUT && _outputState != OUTPUTSTREAM_OUT) {
            throw new IllegalStateException();
        }

        if (_writer != null) {
            _writer.flush();
            _writer.disable();
            _writer = null;
        }

        if (_out == null) {
            _out = new ServletOut(_servletHttpRequest.getHttpRequest().getOutputStream());
        }
        _outputState = OUTPUTSTREAM_OUT;
        return _out;
    }

    public PrintWriter getWriter() throws java.io.IOException {
        if (_outputState == DISABLED) {
            return __nullServletWriter;
        }

        if (_outputState != NO_OUT && _outputState != WRITER_OUT) {
            throw new IllegalStateException();
        }

        // If we are switching modes, flush output to try avoid overlaps.
        if (_out != null) {
            _out.flush();
        }

        /* if there is no writer yet */
        if (_writer == null) {
            /* get encoding from Content-Type header */
            String encoding = _httpResponse.getCharacterEncoding();

            if (encoding == null) {
                if (_servletHttpRequest != null) {
                    /* implementation of educated defaults */
                    String mimeType = _httpResponse.getMimeType();
                    encoding = _servletHttpRequest.getServletHandler().getHttpContext().getEncodingByMimeType(mimeType);
                }
                if (encoding == null) {
                    encoding = StringUtil.__ISO_8859_1;
                }
                _httpResponse.setCharacterEncoding(encoding, true);
            }

            /* construct Writer using correct encoding */
            _writer = new ServletWriter(_httpResponse.getOutputStream(), encoding);
        }
        _outputState = WRITER_OUT;
        return _writer;
    }

    public void setContentLength(int len) {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets content length, even
        // if the getHandling committed the response!
        if (!isCommitted()) {
            setIntHeader(HttpFields.__ContentLength, len);
        }
    }

    public String getContentType() {
        return _httpResponse.getContentType();
    }

    
    public void setContentType(String contentType) {
        if (isCommitted() || contentType == null) {
            return;
        }

        int semi = contentType.indexOf(';');
        if (semi > 0) {
            int charset0 = contentType.indexOf("charset=", semi);
            if (charset0 > 0) {
                if (_outputState == WRITER_OUT) {
                    // need to strip charset= from params
                    int charset1 = contentType.indexOf(' ', charset0);

                    if ((charset0 == semi + 1 && charset1 < 0) ||
                            (charset0 == semi + 2 && charset1 < 0 && contentType.charAt(semi + 1) == ' ')) {
                        _httpResponse.setContentType(contentType.substring(0, semi));
                    } else {
                        if (charset1 < 0) {
                            _httpResponse.setContentType(contentType.substring(0, charset0).trim());
                        } else {
                            _httpResponse.setContentType(contentType.substring(0, charset0) + contentType.substring(charset1));
                        }
                    }
                } else {
                    _charEncodingSetInContentType = true;
                    _httpResponse.setContentType(contentType);
                }
            } else {
                _httpResponse.setContentType(contentType);
            }
        } else {
            _httpResponse.setContentType(contentType);
        }

        if (_locale != null) {
            setLocale(_locale);
        }
    }

    public String getCharacterEncoding() {
        String encoding = _httpResponse.getCharacterEncoding();
        return (encoding == null) ? StringUtil.__ISO_8859_1 : encoding;
    }
    
    public void setCharacterEncoding(String encoding) {
        if (this._outputState == 0 && !isCommitted()) {
            _charEncodingSetInContentType = true;
            _httpResponse.setCharacterEncoding(encoding, true);
        }
    }

    public String toString() {
        return _httpResponse.toString();
    }

}
