/**
 The MIT License (MIT)

 Copyright (c) 2014 Daniel Cohen Gindi, danielgindi@gmail.com
 Repository is at: https://github.com/danielgindi/java-httprequest

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.
 */

package com.dg.http;

import android.graphics.Bitmap;
import android.os.AsyncTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static java.net.Proxy.Type.HTTP;

public class HttpRequest
{
    private static final Charset utf8Charset = Charset.forName("UTF8");
    private static final byte[] CRLF_BYTES = {'\r', '\n'};
    private static final byte[] URL_SEPARATOR_BYTES = {'&'};
    private static final int BUFFER_SIZE = 4096;
    private static final int ESTIMATED_SIZE_TO_ALLOW_IN_MEMORY = 16384; // When we do not know the Content-Length in advance, we build the request in memory, or in file if it's too big or unknown.
    private static final String[] EMPTY_STRING_ARRAY = new String[]{ };
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[]{ };

    private URL url;
    private String httpMethod;
    private Map<String, ArrayList<Object>> params = new HashMap<String, ArrayList<Object>>();
    private Map<String, ArrayList<String>> headers = new HashMap<String, ArrayList<String>>();
    private Map<String, ArrayList<DynamicPart>> multipartParts = new HashMap<String, ArrayList<DynamicPart>>();
    private Object requestBody = null;
    private long requestBodyLengthHint = -1; // Used for InputStream only
    private String defaultContentType;
    private String httpProxyHost;
    private int httpProxyPort;
    private int readTimeout = 0;
    private int connectTimeout = 0;

    private static int defaultJpegCompressionQuality = 90;
    private int jpegCompressionQuality = 0;
    private boolean autoRecycleBitmaps = false;

    private boolean followRedirects = true;
    private int chunkedStreamingModeSize = -1;
    private boolean autoDecompress = true;
    private boolean useCaches = true;
    private boolean shouldTrustAllHttpsCertificates = false;
    private boolean shouldTrustAllHttpsHosts = false;
    private long ifModifiedSince = 0;

    private void initialize()
    {
        if (httpMethod.equals(HttpMethod.POST) || httpMethod.equals(HttpMethod.PUT))
        {
            defaultContentType = ContentType.FORM_URL_ENCODED;
        }
        else if (httpMethod.equals(HttpMethod.PATCH))
        {
            defaultContentType = ContentType.JSON;
        }
    }

    public HttpRequest(final CharSequence url, final String httpMethod) throws MalformedURLException
    {
        this.url = new URL(url.toString());
        this.httpMethod = httpMethod;
        initialize();
    }

    public HttpRequest(final URL url, final String httpMethod) throws MalformedURLException
    {
        this.url = url;
        this.httpMethod = httpMethod;
        initialize();
    }

    public URL getURL()
    {
        return url;
    }

    public HttpRequest setURL(URL url)
    {
        this.url = url;
        return this;
    }

    public String getHttpMethod()
    {
        return httpMethod;
    }

    public HttpRequest setHttpMethod(String httpMethod)
    {
        this.httpMethod = httpMethod;
        return this;
    }

    public HttpRequest clearParams()
    {
        this.params = new HashMap<String, ArrayList<Object>>();
        return this;
    }

    public HttpRequest setParams(final Map<?, ?> params)
    {
        this.params = new HashMap<String, ArrayList<Object>>();
        if (params != null)
        {
            for (HashMap.Entry<?, ?> entry : params.entrySet())
            {
                addParam(entry.getKey().toString(), entry.getValue());
            }
        }
        return this;
    }

    public HttpRequest setParams(final Object... params)
    {
        this.params = new HashMap<String, ArrayList<Object>>();
        for (int i = 0; i < params.length; i += 2)
        {
            addParam(params[i].toString(), params.length > i + 1 ? params[i + 1] : null);
        }
        return this;
    }

    public HttpRequest addParam(final String key, final Object value)
    {
        if (this.params.containsKey(key))
        {
            ArrayList<Object> values = this.params.get(key);
            values.add(value);
        }
        else
        {
            ArrayList<Object> values = new ArrayList<Object>();
            values.add(value);
            this.params.put(key, values);
        }
        return this;
    }

    public HttpRequest setParam(final String key, final Object value)
    {
        ArrayList<Object> values = new ArrayList<Object>();
        values.add(value);
        this.params.put(key, values);
        return this;
    }

    public Object getParam(final String key)
    {
        if (this.params.containsKey(key))
        {
            return this.params.get(key).get(0);
        }
        return null;
    }

    public Object[] getParams(final String key)
    {
        if (this.params.containsKey(key))
        {
            return this.params.get(key).toArray();
        }
        return EMPTY_OBJECT_ARRAY;
    }

    public HttpRequest removeParam(final String key)
    {
        this.params.remove(key);
        return this;
    }

    public HttpRequest cleartHeaders()
    {
        this.headers = new HashMap<String, ArrayList<String>>();
        return this;
    }

    public HttpRequest setHeaders(final Map<?, ?> headers)
    {
        this.headers = new HashMap<String, ArrayList<String>>();
        if (headers != null)
        {
            for (HashMap.Entry<?, ?> entry : headers.entrySet())
            {
                addHeader(entry.getKey().toString(), entry.getValue());
            }
        }
        return this;
    }

    public HttpRequest setHeaders(final Object... headers)
    {
        this.headers = new HashMap<String, ArrayList<String>>();
        for (int i = 0; i < headers.length; i += 2)
        {
            addHeader(headers[i].toString(), headers.length > i + 1 ? headers[i + 1] : null);
        }
        return this;
    }

    public HttpRequest addHeader(final String key, final Object value)
    {
        if (this.headers.containsKey(key))
        {
            ArrayList<String> values = this.headers.get(key);
            values.add(value.toString());
        }
        else
        {
            ArrayList<String> values = new ArrayList<String>();
            values.add(value.toString());
            this.headers.put(key, values);
        }
        return this;
    }

    public HttpRequest setHeader(final String key, final Object value)
    {
        ArrayList<String> values = new ArrayList<String>();
        values.add(value.toString());
        this.headers.put(key, values);
        return this;
    }

    public String getHeader(final String key)
    {
        if (this.headers.containsKey(key))
        {
            return this.headers.get(key).get(0);
        }
        return null;
    }

    public String[] getHeaders(final String key)
    {
        if (this.headers.containsKey(key))
        {
            ArrayList<String> array = this.headers.get(key);
            return array.toArray(new String[array.size()]);
        }
        return EMPTY_STRING_ARRAY;
    }

    public HttpRequest removeHeader(final String key)
    {
        this.headers.remove(key);
        return this;
    }

    public HttpRequest removeAllParts()
    {
        this.multipartParts = new HashMap<String, ArrayList<DynamicPart>>();
        return this;
    }

    public HttpRequest addPart(String name, Object data, Charset charset)
    {
        return addPart(name, data, null, null, -1, charset);
    }

    public void addPart(String name, Object data, String contentType, long contentLength)
    {
        addPart(name, data, null, contentType, contentLength, null);
    }

    public HttpRequest addPart(String name, Object data, String contentType, long contentLength, Charset charset)
    {
        return addPart(name, data, null, contentType, contentLength, charset);
    }

    public HttpRequest addPart(String name, Object data, String fileName)
    {
        return addPart(name, data, fileName, null, -1, null);
    }

    public HttpRequest addPart(String name, Object data, String fileName, String contentType)
    {
        return addPart(name, data, fileName, contentType, -1, null);
    }

    public HttpRequest addPart(String name, Object data, String fileName, String contentType, long contentLength)
    {
        return addPart(name, data, fileName, contentType, contentLength, null);
    }

    public HttpRequest addPart(String name, Object data, String fileName, String contentType, long contentLength, Charset charset)
    {
        Part part = new Part();
        part.fileName = fileName;
        if (data instanceof byte[] || data instanceof InputStream || data instanceof ByteBuffer || data instanceof File || data instanceof Bitmap)
        {
            part.contentType = ContentType.OCTET_STREAM;
        }
        part.contentLength = contentLength;
        part.charset = charset;
        part.data = data;

        return addPart(name, part);
    }

    public HttpRequest addPart(String name, DynamicPart part)
    {
        if (part != null)
        {
            if (this.multipartParts.containsKey(name))
            {
                ArrayList<DynamicPart> values = this.multipartParts.get(name);
                values.add(part);
            }
            else
            {
                ArrayList<DynamicPart> values = new ArrayList<DynamicPart>();
                values.add(part);
                this.multipartParts.put(name, values);
            }
        }
        return this;
    }

    public HttpRequest setAcceptEncoding(String encoding)
    {
        return setHeader(Headers.ACCEPT_ENCODING, encoding);
    }

    public HttpRequest setAcceptGzipEncoding()
    {
        return setAcceptEncoding(AcceptEncodings.GZIP);
    }

    public HttpRequest setAuthorization(String authorization)
    {
        return setHeader(Headers.AUTHORIZATION, authorization);
    }

    public HttpRequest setProxyAuthorization(String proxyAuthorization)
    {
        return setHeader(Headers.PROXY_AUTHORIZATION, proxyAuthorization);
    }

    public HttpRequest setBasicAuthorization(String name, String password)
    {
        return setAuthorization("Basic " + Base64.encode(name + ':' + password));
    }

    public HttpRequest setProxyBasicAuthorization(String name, String password)
    {
        return setProxyAuthorization("Basic " + Base64.encode(name + ':' + password));
    }

    public HttpRequest setUserAgent(String userAgent)
    {
        return setHeader(Headers.USER_AGENT, userAgent);
    }

    public HttpRequest setAccept(String accept)
    {
        return setHeader(Headers.ACCEPT, accept);
    }

    /**
     * The misspelling of the "Referrer" as "Referer" is in the original implementation of HTTP.
     * Yeah, I know, it sucks.
     */
    public HttpRequest setReferer(String referer)
    {
        return setHeader(Headers.REFERER, referer);
    }

    public HttpRequest setIfNoneMatch(String ifNoneMatch)
    {
        return setHeader(Headers.IF_NONE_MATCH, ifNoneMatch);
    }

    public HttpRequest setIfModifiedSince(long ifModifiedSince)
    {
        this.ifModifiedSince = ifModifiedSince;
        return this;
    }

    public long getIfModifiedSince()
    {
        return this.ifModifiedSince;
    }

    public HttpRequest setRequestBody(String requestBody)
    {
        this.requestBody = requestBody;
        requestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setRequestBody(InputStream inputStream) throws IOException
    {
        return setRequestBody(inputStream, -1);
    }

    public HttpRequest setRequestBody(InputStream inputStream, long streamLength) throws IOException
    {
        this.requestBody = inputStream;
        this.requestBodyLengthHint = streamLength;
        return this;
    }

    public HttpRequest setRequestBody(File inputFile)
    {
        this.requestBody = inputFile;
        this.requestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setRequestBody(ByteBuffer byteBuffer)
    {
        this.requestBody = byteBuffer;
        this.requestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setRequestBody(byte[] data)
    {
        this.requestBody = data;
        this.requestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setContentType(String contentType)
    {
        return setContentType(HeaderscontentType, utf8Charset);
    }

    public HttpRequest setContentType(String contentType, String charset)
    {
        if (charset != null && charset.length() > 0)
        {
            return setHeader(Headers.CONTENT_TYPE, contentType + "; charset=" + charset);
        }
        else
        {
            return setHeader(Headers.CONTENT_TYPE, contentType);
        }
    }

    public HttpRequest setContentType(String contentType, Charset charset)
    {
        setContentType(contentType, charset == null ? null : charset.name());
        return this;
    }

    public boolean getFollowRedirects()
    {
        return followRedirects;
    }

    public HttpRequest setFollowRedirects(boolean followRedirects)
    {
        this.followRedirects = followRedirects;
        return this;
    }

    public int getChunkedStreamingModeSize()
    {
        return chunkedStreamingModeSize;
    }

    public HttpRequest setChunkedStreamingModeSize(int chunkedStreamingModeSize)
    {
        this.chunkedStreamingModeSize = chunkedStreamingModeSize;
        return this;
    }

    public HttpRequest setChunkedStreamingModeSize()
    {
        // This will get the system default chunk size
        this.chunkedStreamingModeSize = 0;
        return this;
    }

    public HttpRequest setFixedLengthStreamingModeSize()
    {
        this.chunkedStreamingModeSize = -1;
        return this;
    }

    public boolean getAutoDecompress()
    {
        return autoDecompress;
    }

    public HttpRequest setAutoDecompress(boolean autoDecompress)
    {
        this.autoDecompress = autoDecompress;
        return this;
    }

    public boolean getUseCaches()
    {
        return useCaches;
    }

    public HttpRequest setUseCaches(boolean useCaches)
    {
        this.useCaches = useCaches;
        return this;
    }

    public String getProxyHost()
    {
        return httpProxyHost;
    }

    public int getProxyPort()
    {
        return httpProxyPort;
    }

    public HttpRequest setProxy(String proxyHost, int proxyPort)
    {
        this.httpProxyHost = proxyHost;
        this.httpProxyPort = proxyPort;
        return this;
    }

    public boolean getShouldTrustAllHttpsCertificates()
    {
        return shouldTrustAllHttpsCertificates;
    }

    public HttpRequest setShouldTrustAllHttpsCertificates(boolean shouldTrustAllHttpsCertificates)
    {
        this.shouldTrustAllHttpsCertificates = shouldTrustAllHttpsCertificates;
        return this;
    }

    public boolean getShouldTrustAllHttpsHosts()
    {
        return shouldTrustAllHttpsHosts;
    }

    public HttpRequest setShouldTrustAllHttpsHosts(boolean shouldTrustAllHttpsHosts)
    {
        this.shouldTrustAllHttpsHosts = shouldTrustAllHttpsHosts;
        return this;
    }

    public int getReadTimeout()
    {
        return readTimeout;
    }

    public HttpRequest setReadTimeout(int readTimeout)
    {
        this.readTimeout = readTimeout;
        return this;
    }

    public int getConnectTimeout()
    {
        return connectTimeout;
    }

    public HttpRequest setConnectTimeout(int connectTimeout)
    {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public int getJpegCompressionQuality()
    {
        return jpegCompressionQuality;
    }

    public HttpRequest setJpegCompressionQuality(int jpegCompressionQuality)
    {
        this.jpegCompressionQuality = jpegCompressionQuality;
        return this;
    }

    public boolean getAutoRecycleBitmaps()
    {
        return autoRecycleBitmaps;
    }

    public HttpRequest setAutoRecycleBitmaps(boolean autoRecycleBitmaps)
    {
        this.autoRecycleBitmaps = autoRecycleBitmaps;
        return this;
    }

    public static int getDefaultJpegCompressionQuality()
    {
        return defaultJpegCompressionQuality;
    }

    public static void setDefaultJpegCompressionQuality(int defaultJpegCompressionQuality)
    {
        HttpRequest.defaultJpegCompressionQuality = defaultJpegCompressionQuality;
    }

    public static HttpRequest getRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.GET);
    }

    public static HttpRequest getRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.GET);
    }

    public static HttpRequest getRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest getRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest getRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest getRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest postRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.POST);
    }

    public static HttpRequest postRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.POST);
    }

    public static HttpRequest postRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest postRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest postRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest postRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest putRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.PUT);
    }

    public static HttpRequest putRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.PUT);
    }

    public static HttpRequest putRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest putRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest putRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest putRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest headRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.HEAD);
    }

    public static HttpRequest headRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.HEAD);
    }

    public static HttpRequest headRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest headRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest headRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest headRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest deleteRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.DELETE);
    }

    public static HttpRequest deleteRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.DELETE);
    }

    public static HttpRequest deleteRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest deleteRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest deleteRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest deleteRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    private final static char[] MULTIPART_BOUNDARY_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private String generateBoundary()
    {
        final StringBuilder buffer = new StringBuilder();
        final Random random = new Random();
        final int count = random.nextInt(11) + 30;
        for (int i = 0; i < count; i++)
        {
            buffer.append(MULTIPART_BOUNDARY_CHARS[random.nextInt(MULTIPART_BOUNDARY_CHARS.length)]);
        }
        return buffer.toString();
    }

    private static final ThreadLocal<SSLSocketFactory> trustAllSslFactorySynchronized = new ThreadLocal<SSLSocketFactory>();
    private static SSLSocketFactory getTrustAllSSLFactory()
    {
        final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager()
        {
            public X509Certificate[] getAcceptedIssuers()
            {
                return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] chain, String authType)
            {
                // No check, we trust them all
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType)
            {
                // No check, we trust them all
            }
        }};

        KeyManagerFactory keyManagerFactory = null;
        try
        {
            keyManagerFactory = getDefaultKeyStoreManager();
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), trustAllCerts, new SecureRandom());
            trustAllSslFactorySynchronized.set(context.getSocketFactory());
        }
        catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
        }
        catch (KeyStoreException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        catch (CertificateException e)
        {
            e.printStackTrace();
        }
        catch (UnrecoverableKeyException e)
        {
            e.printStackTrace();
        }
        catch (KeyManagementException e)
        {
            e.printStackTrace();
        }

        return trustAllSslFactorySynchronized.get();
    }

    public static final String SSL_KEY_STORE = System.getProperty("javax.net.ssl.keyStore");
    public static final String SSL_KEY_STORE_PASSWORD = System.getProperty("javax.net.ssl.keyStorePassword");
    public static final String SSL_KEY_STORE_TYPE = System.getProperty("javax.net.ssl.keyStoreType");

    private static KeyManagerFactory getDefaultKeyStoreManager() throws NoSuchAlgorithmException, KeyStoreException, IOException, CertificateException, UnrecoverableKeyException
    {
        if (SSL_KEY_STORE == null || SSL_KEY_STORE_PASSWORD == null || SSL_KEY_STORE_TYPE == null) return null;

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
        KeyStore keyStore = KeyStore.getInstance(SSL_KEY_STORE_TYPE);

        InputStream keyInput = HttpRequest.class.getResourceAsStream(SSL_KEY_STORE);
        keyStore.load(keyInput, SSL_KEY_STORE_PASSWORD.toCharArray());

        if (null == keyInput) throw new FileNotFoundException();

        keyInput.close();
        keyManagerFactory.init(keyStore, SSL_KEY_STORE_PASSWORD.toCharArray());

        return keyManagerFactory;
    }

    /**
     * Send the request and get the response, synchronously.
     * You should NOT call this on the UI thread.
     * @return HttpResponse
     * @throws IOException
     */
    public HttpResponse getResponse() throws IOException
    {
        return getResponse(null);
    }

    /**
     * Send the request and get the response, synchronously.
     * You should NOT call this on the UI thread.
     * @param progressListener A listener for progress, or null if you do not want that. Be careful not to do too much work in progress listener so you won't slow down the progress!
     * @return HttpResponse
     * @throws IOException
     */
    public HttpResponse getResponse(ProgressListener progressListener) throws IOException
    {
        return getResponse(progressListener, null);
    }

    /**
     * Send the request and get the response, synchronously.
     * You should NOT call this on the UI thread.
     * @param progressListener A listener for progress, or null if you do not want that. Be careful not to do too much work in progress listener so you won't slow down the progress!
     * @param requestShouldAbort A thread-safe boolean object, which indicates if the request should abort and disconnect.
     * @return HttpResponse, or null if aborted.
     * @throws IOException
     */
    public HttpResponse getResponse(ProgressListener progressListener, AtomicBoolean requestShouldAbort) throws IOException
    {
        if (this.url == null)
        {
            return null;
        }

        HttpURLConnection connection;
        Charset charset = null;

        String[] parsedContentTypeAndCharset = new String[2];
        extractContentTypeFromHeaders(parsedContentTypeAndCharset);

        String customContentType = parsedContentTypeAndCharset[0];
        try
        {
            charset = Charset.forName(parsedContentTypeAndCharset[1]);
        }
        catch (Exception ignored)
        {
        }
        if (charset == null)
        {
            charset = utf8Charset;
        }

        URL url = this.url;

        boolean requestShouldHaveBody =
                requestBody != null ||
                httpMethod.equals(HttpMethod.POST) ||
                httpMethod.equals(HttpMethod.PUT) ||
                httpMethod.equals(HttpMethod.PATCH) ||
                !multipartParts.isEmpty();

        if (!requestShouldHaveBody)
        {
            url = new URL(urlWithParameters(url, params, charset));
        }

        if (httpProxyHost != null)
        {
            Proxy proxy = new Proxy(HTTP, new InetSocketAddress(httpProxyHost, httpProxyPort));
            connection = (HttpURLConnection) url.openConnection(proxy);
        }
        else
        {
            connection = (HttpURLConnection) url.openConnection();
        }

        connection.setRequestMethod(httpMethod);
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setUseCaches(useCaches);
        if (readTimeout > -1)
        {
            connection.setReadTimeout(readTimeout);
        }
        if (connectTimeout > -1)
        {
            connection.setConnectTimeout(connectTimeout);
        }
        if (chunkedStreamingModeSize > -1)
        {
            // Accepts up to 2 GB...
            connection.setChunkedStreamingMode(chunkedStreamingModeSize);
        }
        if (shouldTrustAllHttpsCertificates)
        {
            if (connection instanceof HttpsURLConnection)
            {
                SSLSocketFactory sslSocketFactory = getTrustAllSSLFactory();
                if (sslSocketFactory != null)
                {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(sslSocketFactory);
                }
            }
        }
        if (shouldTrustAllHttpsHosts)
        {
            if (connection instanceof HttpsURLConnection)
            {
                ((HttpsURLConnection)connection).setHostnameVerifier(new HostnameVerifier()
                {
                    public boolean verify(String hostname, SSLSession session)
                    {
                        return true;
                    }
                });
            }
        }

        for (Map.Entry<String, ArrayList<String>> entry : headers.entrySet())
        {
            for (String header : entry.getValue())
            {
                connection.addRequestProperty(entry.getKey(), header == null ? "" : header);
            }
        }

        boolean needMultipart = requestBody == null && !multipartParts.isEmpty();

        // Check if we need a multipart content type
        if (requestBody == null && !needMultipart)
        {
            for (Map.Entry<String, ArrayList<Object>> entry : params.entrySet())
            {
                for (Object param : entry.getValue())
                {
                    if (param instanceof File ||
                        param instanceof Bitmap ||
                        param instanceof ByteBuffer ||
                        param instanceof InputStream ||
                        param instanceof byte[] ||
                        param instanceof DynamicPart)
                    {
                        needMultipart = true;
                        break;
                    }
                }
            }
        }

        // We have data that must be encoded in multi-part form, so generate a boundary
        String multipartBoundary = null;
        if (needMultipart)
        {
            multipartBoundary = generateBoundary();
        }

        // Set the "default" content type, determined by the convenience methods of this class
        if (defaultContentType != null && customContentType == null)
        {
            if (needMultipart)
            {
                connection.setRequestProperty(Headers.CONTENT_TYPE, ContentType.MULTIPART_FORM_DATA + "; boundary=" + multipartBoundary);
            }
            else
            {
                connection.setRequestProperty(Headers.CONTENT_TYPE, defaultContentType + "; charset=" + charset.name());
            }
        }

        // Determine if we can encode the request in memory first to determine content length
        int minimumContentLength = -1;
        if (requestShouldHaveBody && chunkedStreamingModeSize < 0)
        {
            minimumContentLength = 0;
            for (Map.Entry<String, ArrayList<Object>> entry : params.entrySet())
            {
                for (Object param : entry.getValue())
                {
                    if (param instanceof InputStream)
                    {
                        minimumContentLength = -1;
                        break;
                    }
                    else if (param instanceof File)
                    {
                        minimumContentLength += ((File) param).length();
                    }
                    else if (param instanceof ByteBuffer)
                    {
                        minimumContentLength += ((ByteBuffer) param).limit();
                    }
                    else if (param instanceof byte[])
                    {
                        minimumContentLength += ((byte[]) param).length;
                    }
                    else if (param instanceof Bitmap)
                    {
                        minimumContentLength = -1;
                        break;
                    }
                    else if (param instanceof DynamicPart)
                    {
                        long dynamicLength = ((DynamicPart) param).contentLength();
                        if (dynamicLength == -1)
                        {
                            minimumContentLength = -1;
                            break;
                        }
                        else
                        {
                            minimumContentLength += dynamicLength;
                        }
                    }
                    else
                    {
                        minimumContentLength += param.toString().length();
                    }
                }

                if (minimumContentLength == -1)
                {
                    break;
                }
            }
            if (minimumContentLength > -1)
            {
                for (Map.Entry<String, ArrayList<DynamicPart>> entry : multipartParts.entrySet())
                {
                    for (DynamicPart part : entry.getValue())
                    {
                        long length = part.contentLength();
                        if (length == -1)
                        {
                            minimumContentLength = -1;
                            break;
                        }
                        else
                        {
                            minimumContentLength += length;
                        }
                    }

                    if (minimumContentLength == -1)
                    {
                        break;
                    }
                }
            }
        }

        if (requestShouldAbort != null && requestShouldAbort.get())
        {
            return null;
        }

        boolean wasRequestHandled = false;

        if (!requestShouldHaveBody)
        {
            // There's not supposed to be a request body, do not open an output stream at all, as HttpURLConnection behaves strangely and may change the HTTP method...
            if (progressListener != null)
            {
                progressListener.onRequestProgress(0, -1);
            }

            wasRequestHandled = true;
        }
        else if (minimumContentLength > -1 && minimumContentLength < ESTIMATED_SIZE_TO_ALLOW_IN_MEMORY)
        {
            ByteArrayOutputStream memoryStream = new ByteArrayOutputStream((int)(minimumContentLength * 0.1));
            writeRequestBodyToStream(memoryStream, charset, multipartBoundary, customContentType, requestShouldAbort);

            if (requestShouldAbort != null && requestShouldAbort.get())
            {
                try
                {
                    connection.disconnect();
                }
                catch (Exception ignored)
                {

                }
                return null;
            }

            long contentLength = memoryStream.size();

            connection.setRequestProperty(Headers.CONTENT_LENGTH, ((Long) contentLength).toString());

            if (progressListener != null)
            {
                progressListener.onRequestProgress(0L, contentLength);
            }

            if (contentLength > 0L)
            {
                connection.setDoOutput(true);

                OutputStream outputStream = connection.getOutputStream();
                memoryStream.writeTo(outputStream);
                outputStream.close();
            }

            if (progressListener != null)
            {
                progressListener.onRequestProgress(contentLength, contentLength);
            }

            wasRequestHandled = true;
        }
        else if (requestBody != null)
        {
            long contentLength = requestBodyLengthHint;
            if (contentLength < 0)
            {
                // Can we determine content length?
                if (requestBody instanceof File)
                {
                    contentLength = ((File)requestBody).length();
                }
                else if (requestBody instanceof ByteBuffer)
                {
                    contentLength = ((ByteBuffer)requestBody).limit();
                }
                else if (requestBody instanceof byte[])
                {
                    contentLength = ((byte[])requestBody).length;
                }
                else if (requestBody instanceof Bitmap)
                {
                    contentLength = -1;
                }
                else if (requestBody instanceof DynamicPart)
                {
                    contentLength = ((DynamicPart)requestBody).contentLength();
                }
                else if (requestBody instanceof InputStream)
                {
                    contentLength = -1;
                }
                else
                {
                    requestBody = paramToString(requestBody);

                    ByteBuffer buffer = charset.encode(CharBuffer.wrap(requestBody.toString()));
                    contentLength = buffer.limit();

                    if (progressListener != null)
                    {
                        progressListener.onRequestProgress(0L, contentLength);
                    }

                    // We have encoded the string to determine its length, so use the encoded data and write it already
                    connection.setRequestProperty(Headers.CONTENT_LENGTH, ((Long) contentLength).toString());
                    connection.setDoOutput(true);

                    OutputStream outputStream = connection.getOutputStream();
                    outputStream.write(buffer.array(), 0, buffer.limit());
                    outputStream.close();

                    if (progressListener != null)
                    {
                        progressListener.onRequestProgress(contentLength, contentLength);
                    }

                    wasRequestHandled = true;
                }
            }

            if (!wasRequestHandled && contentLength > -1)
            {
                if (progressListener != null)
                {
                    progressListener.onRequestProgress(0L, contentLength);
                }

                connection.setRequestProperty(Headers.CONTENT_LENGTH, ((Long) contentLength).toString());
                connection.setDoOutput(true);

                ProgressOutputStream progressOutputStream = new ProgressOutputStream(connection.getOutputStream(), progressListener, contentLength);
                writeRequestBodyToStream(progressOutputStream, charset, multipartBoundary, customContentType, requestShouldAbort);
                progressOutputStream.close();

                wasRequestHandled = true;

                if (requestShouldAbort != null && requestShouldAbort.get())
                {
                    try
                    {
                        connection.disconnect();
                    }
                    catch (Exception ignored)
                    {

                    }
                    return null;
                }
            }
        }

        if (!wasRequestHandled)
        {
            if (chunkedStreamingModeSize >= 0)
            {
                // Stream everything out, in chunked mode

                if (progressListener != null)
                {
                    progressListener.onRequestProgress(0L, -1);
                }

                connection.setDoOutput(true);

                ProgressOutputStream progressOutputStream = new ProgressOutputStream(connection.getOutputStream(), progressListener, -1);
                writeRequestBodyToStream(progressOutputStream, charset, multipartBoundary, customContentType, requestShouldAbort);
                progressOutputStream.close();

                if (requestShouldAbort != null && requestShouldAbort.get())
                {
                    try
                    {
                        connection.disconnect();
                    }
                    catch (Exception ignored)
                    {

                    }
                    return null;
                }
            }
            else
            {
                // First stream to a temporary file
                File tempFile = File.createTempFile("request-buffer", ".http", null);
                tempFile.deleteOnExit();
                try
                {
                    FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
                    writeRequestBodyToStream(fileOutputStream, charset, multipartBoundary, customContentType, requestShouldAbort);
                    fileOutputStream.close();

                    if (requestShouldAbort != null && requestShouldAbort.get())
                    {
                        try
                        {
                            tempFile.delete();
                            connection.disconnect();
                        }
                        catch (Exception ignored)
                        {

                        }
                        return null;
                    }

                    long contentLength = tempFile.length();

                    if (progressListener != null)
                    {
                        progressListener.onRequestProgress(0L, contentLength);
                    }

                    connection.setRequestProperty(Headers.CONTENT_LENGTH, ((Long) contentLength).toString());
                    connection.setDoOutput(true);

                    FileInputStream fileInputStream = new FileInputStream(tempFile);
                    OutputStream outputStream = connection.getOutputStream();
                    byte [] buffer = new byte[BUFFER_SIZE];
                    int read;
                    long totalRead = 0L;
                    while ((read = fileInputStream.read(buffer, 0, BUFFER_SIZE)) > 0)
                    {
                        if (requestShouldAbort != null && requestShouldAbort.get())
                        {
                            try
                            {
                                fileInputStream.close();
                                tempFile.delete();
                                connection.disconnect();
                            }
                            catch (Exception ignored)
                            {

                            }
                            return null;
                        }

                        outputStream.write(buffer, 0, read);
                        totalRead += read;

                        if (progressListener != null)
                        {
                            progressListener.onRequestProgress(totalRead, contentLength);
                        }
                    }
                    fileInputStream.close();

                    outputStream.close();
                }
                finally
                {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            }
        }

        // Finish request, start streaming back the response
        return new HttpResponse(connection, autoDecompress);
    }

    public HttpAsyncTask getResponseAsync(final AsyncHttpRequestResponseListener asyncListener)
    {
        return getResponseAsync(asyncListener, null, null);
    }

    public HttpAsyncTask getResponseAsync(final AsyncHttpRequestResponseListener asyncListener, final ProgressListener progressListener)
    {
        return getResponseAsync(asyncListener, null, progressListener);
    }

    public HttpAsyncTask getResponseAsync(final AsyncHttpRequestResponseListener asyncListener, final Executor taskExecutor)
    {
        return getResponseAsync(asyncListener, taskExecutor, null);
    }

    public HttpAsyncTask getResponseAsync(final AsyncHttpRequestResponseListener asyncListener, final Executor taskExecutor, final ProgressListener progressListener)
    {
        HttpAsyncTask task = new HttpAsyncTask<Object, Void, HttpResponse>()
        {
            private HttpResponse response = null;
            private AtomicBoolean shouldAbortRequest = new AtomicBoolean(false);

            @Override
            protected HttpResponse doInBackground(Object... params)
            {
                if (!isCancelled())
                {
                    if (asyncListener != null)
                    {
                        asyncListener.onSetup();
                    }
                }

                if (!isCancelled())
                {
                    try
                    {
                        response = getResponse(progressListener, shouldAbortRequest);
                    }
                    catch (IOException e)
                    {
                        if (asyncListener != null)
                        {
                            asyncListener.onRequestException(e);
                        }
                    }
                }

                if (!isCancelled())
                {
                    if (response != null)
                    {
                        try
                        {
                            response.prebuffer();
                        }
                        catch (IOException e)
                        {
                            if (asyncListener != null)
                            {
                                asyncListener.onResponseException(e);
                            }
                        }
                    }
                }

                return response;
            }

            @Override
            protected void onPostExecute(HttpResponse result)
            {
                super.onPostExecute(result);
                if (asyncListener != null)
                {
                    asyncListener.onResponse(result);
                }
                shouldAbortRequest.set(true);
            }

            @Override
            public void disconnect()
            {
                cancel(false);
                if (response != null)
                {
                    response.disconnect();
                    response = null;
                }
            }
        };

        if (taskExecutor != null)
        {
            task.executeOnExecutor(taskExecutor);
        }
        else
        {
            task.execute();
        }

        return task;
    }

    private static String paramToString(Object param)
    {
        if (param instanceof Boolean)
        {
            return ((Boolean)param) ? "true" : "false";
        }

        return param.toString();
    }

    private void extractContentTypeFromHeaders(String[] contentTypeAndCharset)
    {
        String contentType = null;
        String charsetName = null;
        for (Map.Entry<String, ArrayList<String>> entry : headers.entrySet())
        {
            for (String header : entry.getValue())
            {
                if (contentType == null && entry.getKey().equalsIgnoreCase(Headers.CONTENT_TYPE))
                {
                    int idx;

                    contentType = header;
                    if (contentType == null)
                    {
                        contentType = "";
                    }
                    else
                    {
                        idx = contentType.indexOf(";");
                        if (idx > -1)
                        {
                            contentType = contentType.substring(0, idx);
                        }
                    }
                    try
                    {
                        idx = header.toLowerCase().indexOf("; charset=");
                        if (idx > -1)
                        {
                            charsetName = header.substring(idx + 10);
                            if (charsetName.contains(";"))
                            {
                                charsetName = charsetName.substring(charsetName.indexOf(";"));
                            }
                        }
                    }
                    catch (Exception e)
                    {

                    }
                }
            }
        }

        contentTypeAndCharset[0] = contentType;
        contentTypeAndCharset[1] = charsetName;
    }

    private void writeRequestBodyToStream(OutputStream outputStream, Charset charset, String multipartBoundary, String customContentType, AtomicBoolean shouldAbort) throws IOException
    {
        if (requestBody != null)
        {
            writeDataToStream(this, requestBody, outputStream, charset, shouldAbort);
        }
        else
        {
            if (multipartBoundary != null)
            {
                byte [] boundaryBytes = ("--" + multipartBoundary).getBytes(charset);

                for (Map.Entry<String, ArrayList<Object>> entry : params.entrySet())
                {
                    for (Object param : entry.getValue())
                    {
                        if (shouldAbort != null && shouldAbort.get())
                        {
                            return;
                        }

                        // Multipart boundary
                        outputStream.write(boundaryBytes);
                        outputStream.write(CRLF_BYTES);

                        // Multipart header
                        String partContentType;
                        String partFileName = null;
                        Charset partCharset = null;
                        if (param instanceof InputStream || param instanceof File || param instanceof ByteBuffer)
                        {
                            partContentType = ContentType.OCTET_STREAM;
                            if (param instanceof File)
                            {
                                partFileName = ((File)param).getName();
                            }
                        }
                        else if (param instanceof DynamicPart)
                        {
                            DynamicPart dynamicPart = (DynamicPart)param;
                            partContentType = dynamicPart.contentType();
                            partFileName = dynamicPart.fileName();
                            partCharset = dynamicPart.charset();
                            if (partContentType == null)
                            {
                                partContentType = ContentType.OCTET_STREAM;
                            }
                        }
                        else if (param instanceof Bitmap)
                        {
                            partContentType = ((Bitmap)param).hasAlpha() ? ContentType.IMAGE_PNG : ContentType.IMAGE_JPEG;
                        }
                        else
                        {
                            partContentType = ContentType.TEXT_PLAIN;
                        }

                        writePartHeader(outputStream, charset, entry.getKey(), partFileName, partContentType, partCharset);
                        outputStream.write(CRLF_BYTES);

                        // Multipart body
                        if (param != null)
                        {
                            writeDataToStream(this, param, outputStream, charset, shouldAbort);
                        }
                        outputStream.write(CRLF_BYTES);
                    }
                }

                for (Map.Entry<String, ArrayList<DynamicPart>> entry : multipartParts.entrySet())
                {
                    for (DynamicPart part : entry.getValue())
                    {
                        if (shouldAbort != null && shouldAbort.get())
                        {
                            return;
                        }

                        // Multipart boundary
                        outputStream.write(boundaryBytes);
                        outputStream.write(CRLF_BYTES);

                        // Multipart header
                        writePartHeader(outputStream, charset, entry.getKey(), part.fileName(), part.contentType(), part.charset());
                        outputStream.write(CRLF_BYTES);

                        // Multipart body
                        writeDataToStream(this, part, outputStream, charset, shouldAbort);
                        outputStream.write(CRLF_BYTES);
                    }
                }

                // Prologue boundary...
                outputStream.write(boundaryBytes);
                outputStream.write("--".getBytes(charset));
                outputStream.write(CRLF_BYTES);
            }
            else
            {
                if ((customContentType != null && customContentType.equalsIgnoreCase(ContentType.TEXT_PLAIN)) || (defaultContentType != null && defaultContentType.equalsIgnoreCase(ContentType.TEXT_PLAIN)))
                {
                    boolean firstValue = true;
                    ByteBuffer byteBuffer;
                    for (Map.Entry<String, ArrayList<Object>> entry : params.entrySet())
                    {
                        for (Object param : entry.getValue())
                        {
                            if (shouldAbort != null && shouldAbort.get())
                            {
                                return;
                            }

                            if (firstValue)
                            {
                                firstValue = false;
                            }
                            else
                            {
                                outputStream.write(CRLF_BYTES);
                            }

                            byteBuffer = charset.encode(CharBuffer.wrap(entry.getKey() + "="));
                            outputStream.write(byteBuffer.array(), 0, byteBuffer.limit());

                            if (param != null)
                            {
                                byteBuffer = charset.encode(CharBuffer.wrap(paramToString(param)));
                                outputStream.write(byteBuffer.array(), 0, byteBuffer.limit());
                            }
                        }
                    }
                }
                else
                {
                    boolean firstValue = true;
                    String charsetName = charset.name();

                    for (Map.Entry<String, ArrayList<Object>> entry : params.entrySet())
                    {
                        for (Object param : entry.getValue())
                        {
                            if (shouldAbort != null && shouldAbort.get())
                            {
                                return;
                            }

                            if (firstValue)
                            {
                                firstValue = false;
                            }
                            else
                            {
                                outputStream.write(URL_SEPARATOR_BYTES);
                            }

                            outputStream.write((URLEncoder.encode(entry.getKey(), charsetName) + "=").getBytes());

                            if (param != null)
                            {
                                outputStream.write(URLEncoder.encode(paramToString(param), charsetName).getBytes());
                            }
                        }
                    }
                }
            }
        }
    }

    private static void writeDataToStream(HttpRequest httpRequest, Object param, OutputStream outputStream, Charset charset, AtomicBoolean shouldAbort) throws IOException
    {
        if (param == null) return;
        if (param instanceof InputStream)
        {
            byte [] buffer = new byte[BUFFER_SIZE];
            InputStream inputStream = (InputStream)param;
            int read;
            while ((read = inputStream.read(buffer, 0, BUFFER_SIZE)) > 0)
            {
                if (shouldAbort != null && shouldAbort.get())
                {
                    return;
                }

                outputStream.write(buffer, 0, read);
            }
        }
        else if (param instanceof File)
        {
            File file = (File)param;
            FileInputStream fileInputStream = new FileInputStream(file);
            byte [] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = fileInputStream.read(buffer, 0, BUFFER_SIZE)) > 0)
            {
                if (shouldAbort != null && shouldAbort.get())
                {
                    return;
                }

                outputStream.write(buffer, 0, read);
            }
            fileInputStream.close();
        }
        else if (param instanceof ByteBuffer)
        {
            ByteBuffer byteBuffer = (ByteBuffer)param;
            outputStream.write(byteBuffer.array(), 0, byteBuffer.limit());
        }
        else if (param instanceof byte[])
        {
            byte [] buffer = (byte[])param;
            outputStream.write(buffer);
        }
        else if (param instanceof Bitmap)
        {
            Bitmap bmp = (Bitmap)param;
            if (!bmp.isRecycled())
            {
                bmp.compress(bmp.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, httpRequest.getJpegCompressionQuality(), outputStream);
                if (httpRequest.getAutoRecycleBitmaps())
                {
                    bmp.recycle();
                }
            }
        }
        else if (param instanceof DynamicPart)
        {
            DynamicPart dynamicPart = (DynamicPart)param;
            Charset partCharset = dynamicPart.charset();
            if (partCharset == null)
            {
                partCharset = charset;
            }
            dynamicPart.sendPartToStream(httpRequest, outputStream, partCharset, shouldAbort);
        }
        else
        {
            ByteBuffer byteBuffer = charset.encode(CharBuffer.wrap(paramToString(param)));
            outputStream.write(byteBuffer.array(), 0, byteBuffer.limit());
        }
    }

    private static void sendDataOnStream(OutputStream outputStream, byte[] data, int count, ProgressListener progressListener, long[] progress) throws IOException
    {
        outputStream.write(data, 0, count);

        progress[0] += count;

        if (progressListener != null)
        {
            progressListener.onRequestProgress(progress[0], progress[1]);
        }
    }

    private static final byte [] MULTIPART_HEADER_CONTENT_DISPOSITION_AND_NAME_BYTES = "Content-Disposition: form-data; name=\"".getBytes();
    private static final byte [] MULTIPART_HEADER_END_NAME_AND_FILENAME = "\"; filename=\"".getBytes();
    private static final byte [] MULTIPART_HEADER_END = "\"".getBytes();
    private static final byte [] MULTIPART_HEADER_CONTENT_TYPE = "Content-Type: ".getBytes();
    private static final byte [] MULTIPART_HEADER_CONTENT_TYPE_END_CHARSET = "; charset=".getBytes();
    private void writePartHeader(OutputStream outputStream, Charset charset, String name, String filename, String contentType, Charset contentCharset) throws IOException
    {
        String charsetName = charset.name();
        if (contentCharset == null)
        {
            contentCharset = charset;
        }
        outputStream.write(MULTIPART_HEADER_CONTENT_DISPOSITION_AND_NAME_BYTES);
        outputStream.write(URLEncoder.encode(name, charsetName).getBytes());
        if (filename != null && !filename.isEmpty())
        {
            outputStream.write(MULTIPART_HEADER_END_NAME_AND_FILENAME);
            outputStream.write(URLEncoder.encode(filename.replace("\"", ""), charsetName).getBytes());
            outputStream.write(MULTIPART_HEADER_END);
        }
        else
        {
            outputStream.write(MULTIPART_HEADER_END);
        }
        outputStream.write(CRLF_BYTES);

        if (contentType != null)
        {
            outputStream.write(MULTIPART_HEADER_CONTENT_TYPE);
            outputStream.write(contentType.getBytes(charset));
            outputStream.write(MULTIPART_HEADER_CONTENT_TYPE_END_CHARSET);
            outputStream.write(contentCharset.name().getBytes());
            outputStream.write(CRLF_BYTES);
        }
    }

    /**
     * Use to prepare the request in a file, or for debugging reasons etc.
     * @param outputStream The stream to write to
     * @return Content type of the request
     */
    public String writeRequestBodyToStream(OutputStream outputStream) throws IOException
    {
        Charset charset = null;

        String customContentType = null;
        for (Map.Entry<String, ArrayList<String>> entry : headers.entrySet())
        {
            for (String header : entry.getValue())
            {
                if (entry.getKey().equalsIgnoreCase(Headers.CONTENT_TYPE))
                {
                    int idx;

                    customContentType = header;
                    if (customContentType == null)
                    {
                        customContentType = "";
                    }
                    else
                    {
                        idx = customContentType.indexOf(";");
                        if (idx > -1)
                        {
                            customContentType = customContentType.substring(0, idx);
                        }
                    }
                    try
                    {
                        idx = header.toLowerCase().indexOf("; charset=");
                        if (idx > -1)
                        {
                            String charsetName = header.substring(idx + 10);
                            if (charsetName.contains(";"))
                            {
                                charsetName = charsetName.substring(charsetName.indexOf(";"));
                            }
                            charset = Charset.forName(charsetName);
                        }
                    }
                    catch (Exception ignored)
                    {

                    }

                    break;
                }
            }

            if (customContentType != null)
            {
                break;
            }
        }

        if (charset == null)
        {
            charset = utf8Charset;
        }

        boolean needMultipart = requestBody == null && !multipartParts.isEmpty();

        // Check if we need a multipart content type
        if (requestBody == null && !needMultipart)
        {
            for (Map.Entry<String, ArrayList<Object>> entry : params.entrySet())
            {
                for (Object param : entry.getValue())
                {
                    if (param instanceof File ||
                            param instanceof Bitmap ||
                            param instanceof ByteBuffer ||
                            param instanceof InputStream ||
                            param instanceof byte[] ||
                            param instanceof DynamicPart)
                    {
                        needMultipart = true;
                        break;
                    }
                }
            }
        }

        // We have data that must be encoded in multi-part form, so generate a boundary
        String multipartBoundary = null;
        if (needMultipart)
        {
            multipartBoundary = generateBoundary();
        }

        String finalContentType = customContentType;

        // Set the "default" content type, determined by the convenience methods of this class
        if (defaultContentType != null && customContentType == null)
        {
            if (needMultipart)
            {
                finalContentType = ContentType.MULTIPART_FORM_DATA + "; boundary=" + multipartBoundary;
            }
            else
            {
                finalContentType = defaultContentType + "; charset=" + charset.name();
            }
        }

        writeRequestBodyToStream(outputStream, charset, multipartBoundary, customContentType, null);

        return finalContentType;
    }

    public static String urlWithParameters(URL url, Map<String, ?> params)
    {
        return urlWithParameters(url.toExternalForm(), params, utf8Charset);
    }

    public static String urlWithParameters(URL url, Map<String, ?> params, Charset charset)
    {
        return urlWithParameters(url.toExternalForm(), params, charset);
    }

    public static String urlWithParameters(String url, Map<String, ?> params)
    {
        return urlWithParameters(url, params, utf8Charset);
    }

    public static String urlWithParameters(String url, Map<String, ?> params, Charset charset)
    {
        StringBuilder sb = new StringBuilder(url.length());
        sb.append(url);

        boolean firstValue = true;
        String charsetName = charset.name();

        for (Map.Entry<String, ?> entry : params.entrySet())
        {
            if (entry.getValue() instanceof Collection)
            {
                for (Object param : (Collection)entry.getValue())
                {
                    if (firstValue)
                    {
                        firstValue = false;
                        sb.append(url.contains("?") ? "&" : "?");
                    }
                    else
                    {
                        sb.append("&");
                    }

                    try
                    {
                        sb.append(URLEncoder.encode(entry.getKey(), charsetName)).append("=");

                        if (param != null)
                        {
                            sb.append(URLEncoder.encode(paramToString(param), charsetName));
                        }
                    }
                    catch (UnsupportedEncodingException ignored)
                    {
                    }
                }
            }
            else
            {
                if (firstValue)
                {
                    firstValue = false;
                    sb.append(url.contains("?") ? "&" : "?");
                }
                else
                {
                    sb.append("&");
                }

                try
                {
                    sb.append(URLEncoder.encode(entry.getKey(), charsetName)).append("=");

                    if (entry.getValue() != null)
                    {
                        sb.append(URLEncoder.encode(paramToString(entry.getValue()), charsetName));
                    }
                }
                catch (UnsupportedEncodingException ignored)
                {
                }
            }
        }

        return sb.toString();
    }

    @Override
    public String toString()
    {
        return this.httpMethod + ' ' + this.url;
    }

    /**
     * Pass an instance of DynamicPart as a part if you want to encode something on-the-fly for the output stream.
     * A case where you would want to use this is where you need to encode a large chunk of data,
     * and want to avoid OutOfMemory exception.
     * Then you could pass a contentLength of -1, and HttpRequest will be force to first buffer to a file in order to determine the ContentLength.
     */
    public abstract static class DynamicPart
    {
        /**
         * @return the content length that you intend to send. This must be accurate, or -1 if you cannot tell.
         */
        public long contentLength()
        {
            return -1;
        }

        /**
         * @return the content type that you to represent in the header for this part. This can be null. The default is binary (application/octet-stream)
         */
        public String contentType()
        {
            return ContentType.OCTET_STREAM;
        }

        /**
         * @return The file name that you want to represent in the header for this part. This can be null.
         */
        public String fileName()
        {
            return null;
        }

        /**
         * @return The charset that you want to use for this specific part. If null - it will inherit the charset from the request.
         */
        public Charset charset()
        {
            return null;
        }

        /**
         * Send the data to the stream. If you specified a contentLength, be careful to be exact.
         * @param httpRequest The origin HttpRequest
         * @param outputStream The stream to write to
         * @param charset The charset that should be used to write the data, if relevant at all. It represents the value returned from charset(), or default inherited from the request itself.
         * @param shouldAbort Indicates whether the streaming should be aborted. When this is set, stopping writing is safe at any point.
         */
        public abstract void sendPartToStream(HttpRequest httpRequest, OutputStream outputStream, Charset charset, AtomicBoolean shouldAbort) throws IOException;
    }

    private static class Part extends DynamicPart
    {
        public String fileName;
        public Object data;
        public String contentType;
        public long contentLength = -1;
        public Charset charset;

        @Override
        public long contentLength()
        {
            if (contentLength < 0)
            {
                if (data instanceof File)
                {
                    return ((File)data).length();
                }
                else if (data instanceof byte[])
                {
                    return ((byte[])data).length;
                }
                else if (data instanceof ByteBuffer)
                {
                    return ((ByteBuffer)data).limit();
                }
            }
            return contentLength;
        }

        @Override
        public String contentType()
        {
            return contentType;
        }

        @Override
        public String fileName()
        {
            if (fileName == null)
            {
                if (data instanceof File)
                {
                    return ((File)data).getName();
                }
            }
            return fileName;
        }

        @Override
        public Charset charset()
        {
            return charset;
        }

        @Override
        public void sendPartToStream(HttpRequest httpRequest, OutputStream outputStream, Charset charset, AtomicBoolean shouldAbort) throws IOException
        {
            writeDataToStream(httpRequest, data, outputStream, charset, shouldAbort);
        }
    }

    public abstract static class ProgressListener
    {
        /**
         * @param sent How much we have sent already
         * @param total How much are we supposed to send in total. Be aware that this may be -1 if the request was specified as chunked, or if there's no request body (GET, HEAD...)
         */
        public abstract void onRequestProgress(long sent, long total);

        /**
         * @param received How much we have received already
         * @param total How long is the response. Be aware that this may be -1 in some responses.
         */
        public abstract void onResponseProgress(long received, long total);
    }

    public static class ProgressOutputStream extends OutputStream
    {
        OutputStream outputStream;
        long totalBytes;
        long totalWritten;
        ProgressListener progressListener;

        public ProgressOutputStream(OutputStream outputStream, ProgressListener progressListener, long totalBytes)
        {
            super();

            this.outputStream = outputStream;
            this.totalBytes = totalBytes;
            this.totalWritten = 0;
            this.progressListener = progressListener;
        }

        @Override
        public void close() throws IOException
        {
            outputStream.close();
        }

        @Override
        public void flush() throws IOException
        {
            outputStream.flush();
        }

        @Override
        public void write(byte[] buffer) throws IOException
        {
            outputStream.write(buffer);

            totalWritten += buffer.length;
            if (progressListener != null)
            {
                progressListener.onRequestProgress(totalWritten, totalBytes);
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException
        {
            outputStream.write(buffer, offset, count);

            totalWritten += count;
            if (progressListener != null)
            {
                progressListener.onRequestProgress(totalWritten, totalBytes);
            }
        }

        @Override
        public void write(int oneByte) throws IOException
        {
            outputStream.write(oneByte);

            totalWritten += 1;
            if (progressListener != null)
            {
                progressListener.onRequestProgress(totalWritten, totalBytes);
            }
        }
    }

    public abstract static class AsyncHttpRequestResponseListener
    {
        /**
         * Called on the AsyncTask thread, to allow making any setup to the request if we do not want it on the UI thread
         */
        public void onSetup()
        {
            // Do nothing
        }

        /**
         * Called on the AsyncTask thread with any exception thrown during the request connection
         */
        public void onRequestException(IOException ioException)
        {
            // Do nothing. If you want printouts, override and print. By default we do not pollute the Logcat, and we do not expose app's data on the log.
        }

        /**
         * Called on the AsyncTask thread with any exception thrown during the request connection
         */
        public void onResponseException(IOException ioException)
        {
            // Do nothing. If you want printouts, override and print. By default we do not pollute the Logcat, and we do not expose app's data on the log.
        }

        /**
         * Called with the response when the connection is finished, and after buffering all the data (to disk if too large).
         * @param response The response object. May be null if onRequestException or onResponseException was called, or if onSetup has thrown.
         */
        public abstract void onResponse(HttpResponse response);
    }

    public abstract static class HttpAsyncTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result>
    {
        /**
         * Will try to disconnect and cancel the AsyncTask
         */
        public abstract void disconnect();

        /**
         * Same as disconnect - will try to disconnect and cancel the AsyncTask
         */
        public void abort()
        {
            disconnect();
        }
    }

    public abstract static class ContentType
    {
        public static final String FORM_URL_ENCODED = "application/x-www-form-urlencoded";
        public static final String MULTIPART_FORM_DATA = "multipart/form-data";
        public static final String JSON = "application/json";
        public static final String TEXT_PLAIN = "text/plain";
        public static final String OCTET_STREAM = "application/octet-stream";
        public static final String IMAGE_JPEG = "image/jpeg";
        public static final String IMAGE_PNG = "image/png";
    }

    public abstract static class HttpMethod
    {
        public static final String GET = "GET";
        public static final String POST = "POST";
        public static final String PUT = "PUT";
        public static final String HEAD = "HEAD";
        public static final String DELETE = "DELETE";

        // These are not permitted by HttpURLConnection...
        public static final String PATCH = "PATCH";
        public static final String OPTIONS = "OPTIONS";
        public static final String CONNECT = "CONNECT";
        public static final String TRACE = "TRACE";
    }

    public abstract static class Headers
    {
        public static final String ACCEPT = "Accept";
        public static final String ACCEPT_CHARSET = "Accept-Charset";
        public static final String ACCEPT_ENCODING = "Accept-Encoding";
        public static final String AUTHORIZATION = "Authorization";
        public static final String CONTENT_LENGTH = "Content-Length";
        public static final String CONTENT_TYPE = "Content-Type";
        public static final String IF_NONE_MATCH = "If-None-Match";
        public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
        public static final String REFERER = "Referer";
        public static final String USER_AGENT = "User-Agent";
    }

    public abstract static class AcceptEncodings
    {
        public static final String GZIP = "gzip";
    }
}
