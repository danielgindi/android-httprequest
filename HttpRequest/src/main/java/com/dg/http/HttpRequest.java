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
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static java.net.Proxy.Type.HTTP;

@SuppressWarnings("unused")
public class HttpRequest
{
    private static final Charset UTF8_CHARSET = Charset.forName("UTF8");
    private static final byte[] CRLF_BYTES = {'\r', '\n'};
    private static final byte[] URL_SEPARATOR_BYTES = {'&'};
    private static final int BUFFER_SIZE = 4096;
    private static final int ESTIMATED_SIZE_TO_ALLOW_IN_MEMORY = 16384; // When we do not know the Content-Length in advance, we build the request in memory, or in file if it's too big or unknown.
    private static final String[] EMPTY_STRING_ARRAY = new String[]{ };
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[]{ };

    private URL mUrl;
    private String mHttpMethod;
    private Map<String, ArrayList<Object>> mParams = new HashMap<>();
    private Map<String, ArrayList<String>> mHeaders = new HashMap<>();
    private Map<String, ArrayList<MultipartBuilder.DynamicPart>> mMultipartParts = new HashMap<>();
    private Object mRequestBody = null;
    private long mRequestBodyLengthHint = -1; // Used for InputStream only
    private String mDefaultContentType;
    private String mHttpProxyHost;
    private int mHttpProxyPort;
    private int mReadTimeout = 0;
    private int mConnectTimeout = 0;

    private MultipartBuilder.Settings mSettings = new MultipartBuilder.Settings();

    private boolean mFollowRedirects = true;
    private int mChunkedStreamingModeSize = -1;
    private boolean mAutoDecompress = true;
    private boolean mUseCaches = true;
    private boolean mShouldTrustAllHttpsCertificates = false;
    private boolean mShouldTrustAllHttpsHosts = false;
    private SSLSocketFactory mCustomSSLSocketFactory = null;
    private long mIfModifiedSince = 0;

    private static boolean mTriedFixingHttpURLConnectionMethods = false;

    private void initialize()
    {
        if (mHttpMethod.equals(HttpMethod.POST) || mHttpMethod.equals(HttpMethod.PUT))
        {
            mDefaultContentType = ContentType.FORM_URL_ENCODED;
        }
        else if (mHttpMethod.equals(HttpMethod.PATCH))
        {
            mDefaultContentType = ContentType.JSON;
        }

        if (!mTriedFixingHttpURLConnectionMethods)
        {
            mTriedFixingHttpURLConnectionMethods = true;
            tryFixingHttpURLConnectionMethods();
        }
    }

    public HttpRequest(final CharSequence url, final String httpMethod) throws MalformedURLException
    {
        this.mUrl = new URL(url.toString());
        this.mHttpMethod = httpMethod;
        initialize();
    }

    public HttpRequest(final URL url, final String httpMethod) throws MalformedURLException
    {
        this.mUrl = url;
        this.mHttpMethod = httpMethod;
        initialize();
    }

    public URL getURL()
    {
        return mUrl;
    }

    public HttpRequest setURL(URL url)
    {
        this.mUrl = url;
        return this;
    }

    public String getHttpMethod()
    {
        return mHttpMethod;
    }

    public HttpRequest setHttpMethod(String httpMethod)
    {
        this.mHttpMethod = httpMethod;
        return this;
    }

    public HttpRequest clearParams()
    {
        this.mParams = new HashMap<>();
        return this;
    }

    public HttpRequest setParams(final Map<?, ?> params)
    {
        this.mParams = new HashMap<>();
        return addParams(params);
    }

    public HttpRequest setParams(final Object... params)
    {
        this.mParams = new HashMap<>();
        for (int i = 0; i < params.length; i += 2)
        {
            addParam(params[i].toString(), params.length > i + 1 ? params[i + 1] : null);
        }
        return this;
    }

    public HttpRequest addParams(final Map<?, ?> params)
    {
        if (params != null)
        {
            for (HashMap.Entry<?, ?> entry : params.entrySet())
            {
                addParam(entry.getKey().toString(), entry.getValue());
            }
        }
        return this;
    }

    public HttpRequest addParam(final String key, final Object value)
    {
        if (this.mParams.containsKey(key))
        {
            ArrayList<Object> values = this.mParams.get(key);
            values.add(value);
        }
        else
        {
            ArrayList<Object> values = new ArrayList<Object>();
            values.add(value);
            this.mParams.put(key, values);
        }
        return this;
    }

    public HttpRequest setParam(final String key, final Object value)
    {
        ArrayList<Object> values = new ArrayList<>();
        values.add(value);
        this.mParams.put(key, values);
        return this;
    }

    public Object getParam(final String key)
    {
        if (this.mParams.containsKey(key))
        {
            return this.mParams.get(key).get(0);
        }
        return null;
    }

    public Object[] getParams(final String key)
    {
        if (this.mParams.containsKey(key))
        {
            return this.mParams.get(key).toArray();
        }
        return EMPTY_OBJECT_ARRAY;
    }

    public HttpRequest removeParam(final String key)
    {
        this.mParams.remove(key);
        return this;
    }

    public HttpRequest cleartHeaders()
    {
        this.mHeaders = new HashMap<>();
        return this;
    }

    public HttpRequest setHeaders(final Map<?, ?> headers)
    {
        this.mHeaders = new HashMap<>();
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
        this.mHeaders = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2)
        {
            addHeader(headers[i].toString(), headers.length > i + 1 ? headers[i + 1] : null);
        }
        return this;
    }

    public HttpRequest addHeader(final String key, final Object value)
    {
        if (this.mHeaders.containsKey(key))
        {
            ArrayList<String> values = this.mHeaders.get(key);
            values.add(value.toString());
        }
        else
        {
            ArrayList<String> values = new ArrayList<>();
            values.add(value.toString());
            this.mHeaders.put(key, values);
        }
        return this;
    }

    public HttpRequest setHeader(final String key, final Object value)
    {
        ArrayList<String> values = new ArrayList<>();
        values.add(value.toString());
        this.mHeaders.put(key, values);
        return this;
    }

    public String getHeader(final String key)
    {
        if (this.mHeaders.containsKey(key))
        {
            return this.mHeaders.get(key).get(0);
        }
        return null;
    }

    public String[] getHeaders(final String key)
    {
        if (this.mHeaders.containsKey(key))
        {
            ArrayList<String> array = this.mHeaders.get(key);
            return array.toArray(new String[array.size()]);
        }
        return EMPTY_STRING_ARRAY;
    }

    public HttpRequest removeHeader(final String key)
    {
        this.mHeaders.remove(key);
        return this;
    }

    public HttpRequest removeAllParts()
    {
        this.mMultipartParts = new HashMap<>();
        return this;
    }

    public HttpRequest addPart(String name, Object data, Charset charset)
    {
        return addPart(name, data, null, null, -1, charset);
    }

    public HttpRequest addPart(String name, Object data, String contentType, long contentLength)
    {
        return addPart(name, data, null, contentType, contentLength, null);
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
        MultipartBuilder.Part part = new MultipartBuilder.Part(
                data,
                fileName,
                contentType,
                contentLength,
                charset);

        return addPart(name, part);
    }

    public HttpRequest addPart(String name, MultipartBuilder.DynamicPart part)
    {
        if (part != null)
        {
            if (this.mMultipartParts.containsKey(name))
            {
                ArrayList<MultipartBuilder.DynamicPart> values = this.mMultipartParts.get(name);
                values.add(part);
            }
            else
            {
                ArrayList<MultipartBuilder.DynamicPart> values = new ArrayList<>();
                values.add(part);
                this.mMultipartParts.put(name, values);
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
        this.mIfModifiedSince = ifModifiedSince;
        return this;
    }

    public long getIfModifiedSince()
    {
        return this.mIfModifiedSince;
    }

    public HttpRequest setRequestBody(String requestBody)
    {
        this.mRequestBody = requestBody;
        mRequestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setRequestBody(InputStream inputStream) throws IOException
    {
        return setRequestBody(inputStream, -1);
    }

    public HttpRequest setRequestBody(InputStream inputStream, long streamLength) throws IOException
    {
        this.mRequestBody = inputStream;
        this.mRequestBodyLengthHint = streamLength;
        return this;
    }

    public HttpRequest setRequestBody(File inputFile)
    {
        this.mRequestBody = inputFile;
        this.mRequestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setRequestBody(ByteBuffer byteBuffer)
    {
        this.mRequestBody = byteBuffer;
        this.mRequestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setRequestBody(byte[] data)
    {
        this.mRequestBody = data;
        this.mRequestBodyLengthHint = -1;
        return this;
    }

    public HttpRequest setContentType(String contentType)
    {
        return setContentType(contentType, UTF8_CHARSET);
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
        return mFollowRedirects;
    }

    public HttpRequest setFollowRedirects(boolean followRedirects)
    {
        this.mFollowRedirects = followRedirects;
        return this;
    }

    public int getChunkedStreamingModeSize()
    {
        return mChunkedStreamingModeSize;
    }

    public HttpRequest setChunkedStreamingModeSize(int chunkedStreamingModeSize)
    {
        this.mChunkedStreamingModeSize = chunkedStreamingModeSize;
        return this;
    }

    public HttpRequest setChunkedStreamingModeSize()
    {
        // This will get the system default chunk size
        this.mChunkedStreamingModeSize = 0;
        return this;
    }

    public HttpRequest setFixedLengthStreamingModeSize()
    {
        this.mChunkedStreamingModeSize = -1;
        return this;
    }

    public boolean getAutoDecompress()
    {
        return mAutoDecompress;
    }

    public HttpRequest setAutoDecompress(boolean autoDecompress)
    {
        this.mAutoDecompress = autoDecompress;
        return this;
    }

    public boolean getUseCaches()
    {
        return mUseCaches;
    }

    public HttpRequest setUseCaches(boolean useCaches)
    {
        this.mUseCaches = useCaches;
        return this;
    }

    public String getProxyHost()
    {
        return mHttpProxyHost;
    }

    public int getProxyPort()
    {
        return mHttpProxyPort;
    }

    public HttpRequest setProxy(String proxyHost, int proxyPort)
    {
        this.mHttpProxyHost = proxyHost;
        this.mHttpProxyPort = proxyPort;
        return this;
    }

    public boolean getShouldTrustAllHttpsCertificates()
    {
        return mShouldTrustAllHttpsCertificates;
    }

    public HttpRequest setShouldTrustAllHttpsCertificates(boolean shouldTrustAllHttpsCertificates)
    {
        this.mShouldTrustAllHttpsCertificates = shouldTrustAllHttpsCertificates;
        return this;
    }

    public boolean getShouldTrustAllHttpsHosts()
    {
        return mShouldTrustAllHttpsHosts;
    }

    public HttpRequest setShouldTrustAllHttpsHosts(boolean shouldTrustAllHttpsHosts)
    {
        this.mShouldTrustAllHttpsHosts = shouldTrustAllHttpsHosts;
        return this;
    }

    public SSLSocketFactory getCustomSSLSocketFactory()
    {
        return mCustomSSLSocketFactory;
    }

    public HttpRequest setCustomSSLSocketFactory(SSLSocketFactory customSSLSocketFactory)
    {
        this.mCustomSSLSocketFactory = customSSLSocketFactory;
        return this;
    }

    public int getReadTimeout()
    {
        return mReadTimeout;
    }

    public HttpRequest setReadTimeout(int readTimeout)
    {
        this.mReadTimeout = readTimeout;
        return this;
    }

    public int getConnectTimeout()
    {
        return mConnectTimeout;
    }

    public HttpRequest setConnectTimeout(int connectTimeout)
    {
        this.mConnectTimeout = connectTimeout;
        return this;
    }

    public int getJpegCompressionQuality()
    {
        return mSettings.getJpegCompressionQuality();
    }

    public HttpRequest setJpegCompressionQuality(int jpegCompressionQuality)
    {
        mSettings.setJpegCompressionQuality(jpegCompressionQuality);
        return this;
    }

    public boolean getAutoRecycleBitmaps()
    {
        return mSettings.getAutoRecycleBitmaps();
    }

    public HttpRequest setAutoRecycleBitmaps(boolean autoRecycleBitmaps)
    {
        mSettings.setAutoRecycleBitmaps(autoRecycleBitmaps);
        return this;
    }

    /**
     * @return Default jpeg compression quality (0-100), for when quality = 0. Bridges to MultipartBuilder.Settings.
     */
    public static int getDefaultJpegCompressionQuality()
    {
        return MultipartBuilder.Settings.getDefaultJpegCompressionQuality();
    }

    /**
     * Sets default jpeg compression quality (0-100), for when quality = 0.
     * Bridges to MultipartBuilder.Settings.
     * @param defaultJpegCompressionQuality
     */
    public static void setDefaultJpegCompressionQuality(int defaultJpegCompressionQuality)
    {
        MultipartBuilder.Settings.setDefaultJpegCompressionQuality(defaultJpegCompressionQuality);
    }

    @Deprecated
    public static HttpRequest getRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.GET);
    }

    @Deprecated
    public static HttpRequest getRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.GET);
    }

    @Deprecated
    public static HttpRequest getRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest getRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest getRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest getRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest postRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.POST);
    }

    @Deprecated
    public static HttpRequest postRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.POST);
    }

    @Deprecated
    public static HttpRequest postRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest postRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest postRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest postRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest putRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.PUT);
    }

    @Deprecated
    public static HttpRequest putRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.PUT);
    }

    @Deprecated
    public static HttpRequest putRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest putRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest putRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest putRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest headRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.HEAD);
    }

    @Deprecated
    public static HttpRequest headRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.HEAD);
    }

    @Deprecated
    public static HttpRequest headRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest headRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest headRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest headRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest deleteRequest(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.DELETE);
    }

    @Deprecated
    public static HttpRequest deleteRequest(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.DELETE);
    }

    @Deprecated
    public static HttpRequest deleteRequest(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest deleteRequest(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest deleteRequest(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    @Deprecated
    public static HttpRequest deleteRequest(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest get(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.GET);
    }

    public static HttpRequest get(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.GET);
    }

    public static HttpRequest get(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest get(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest get(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest get(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.GET);
        request.setParams(params);
        return request;
    }

    public static HttpRequest post(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.POST);
    }

    public static HttpRequest post(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.POST);
    }

    public static HttpRequest post(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest post(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest post(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest post(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.POST);
        request.setParams(params);
        return request;
    }

    public static HttpRequest put(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.PUT);
    }

    public static HttpRequest put(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.PUT);
    }

    public static HttpRequest put(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest put(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest put(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest put(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.PUT);
        request.setParams(params);
        return request;
    }

    public static HttpRequest head(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.HEAD);
    }

    public static HttpRequest head(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.HEAD);
    }

    public static HttpRequest head(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest head(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest head(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest head(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.HEAD);
        request.setParams(params);
        return request;
    }

    public static HttpRequest delete(final CharSequence url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.DELETE);
    }

    public static HttpRequest delete(final URL url) throws MalformedURLException
    {
        return new HttpRequest(url, HttpMethod.DELETE);
    }

    public static HttpRequest delete(final CharSequence url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest delete(final URL url, final Map<?, ?> params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest delete(final CharSequence url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    public static HttpRequest delete(final URL url, final Object... params) throws MalformedURLException
    {
        HttpRequest request = new HttpRequest(url, HttpMethod.DELETE);
        request.setParams(params);
        return request;
    }

    private static final ThreadLocal<SSLSocketFactory> trustAllSslFactorySynchronized = new ThreadLocal<>();
    private static SSLSocketFactory getTrustAllSSLFactory()
    {
        SSLSocketFactory factory = trustAllSslFactorySynchronized.get();

        if (factory == null)
        {
            X509TrustManager trustManager = new X509TrustManager()
            {
                @Override
                public X509Certificate[] getAcceptedIssuers()
                {
                    return null;
                }

                @Override
                public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException
                { /* Not implemented */ }

                @Override
                public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException
                { /* Not implemented */ }
            };

            TrustManager[] trustAllCerts = new TrustManager[]{trustManager};

            try
            {
                SSLContext sc = SSLContext.getInstance("TLS");

                sc.init(null, trustAllCerts, new SecureRandom());

                trustAllSslFactorySynchronized.set(factory = sc.getSocketFactory());
            }
            catch (NoSuchAlgorithmException e)
            {
                e.printStackTrace();
            }
            catch (KeyManagementException e)
            {
                e.printStackTrace();
            }
        }

        return factory;
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
        if (this.mUrl == null)
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
            charset = UTF8_CHARSET;
        }

        URL url = this.mUrl;

        boolean requestShouldHaveBody =
                mRequestBody != null ||
                mHttpMethod.equals(HttpMethod.POST) ||
                mHttpMethod.equals(HttpMethod.PUT) ||
                mHttpMethod.equals(HttpMethod.PATCH) ||
                !mMultipartParts.isEmpty();

        if (!requestShouldHaveBody)
        {
            url = new URL(urlWithParameters(url, mParams, charset));
        }

        if (mHttpProxyHost != null)
        {
            Proxy proxy = new Proxy(HTTP, new InetSocketAddress(mHttpProxyHost, mHttpProxyPort));
            connection = (HttpURLConnection) url.openConnection(proxy);
        }
        else
        {
            connection = (HttpURLConnection) url.openConnection();
        }

        try
        {
            connection.setRequestMethod(mHttpMethod);
        }
        catch (ProtocolException ex)
        {
            // HTTP Method not supported by HttpURLConnection which is only HTTP/1.1 compliant
            trySetHttpMethodUsingIntrospection(connection, mHttpMethod);
        }

        connection.setInstanceFollowRedirects(mFollowRedirects);
        connection.setUseCaches(mUseCaches);
        if (mReadTimeout > -1)
        {
            connection.setReadTimeout(mReadTimeout);
        }
        if (mConnectTimeout > -1)
        {
            connection.setConnectTimeout(mConnectTimeout);
        }
        if (mChunkedStreamingModeSize > -1)
        {
            // Accepts up to 2 GB...
            connection.setChunkedStreamingMode(mChunkedStreamingModeSize);
        }
        if (mShouldTrustAllHttpsCertificates)
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
        if (mShouldTrustAllHttpsHosts)
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
        if (mCustomSSLSocketFactory != null)
        {
            if (connection instanceof HttpsURLConnection)
            {
                ((HttpsURLConnection) connection).setSSLSocketFactory(mCustomSSLSocketFactory);
            }
        }

        for (Map.Entry<String, ArrayList<String>> entry : mHeaders.entrySet())
        {
            for (String header : entry.getValue())
            {
                connection.addRequestProperty(entry.getKey(), header == null ? "" : header);
            }
        }

        // Check if we need a multipart content type
        boolean needMultipart = mRequestBody == null &&
                (!mMultipartParts.isEmpty()
                        || MultipartBuilder.requiresMultipart(mParams));

        // We have data that must be encoded in multi-part form, so generate a boundary
        MultipartBuilder multipartBuilder = null;
        if (needMultipart)
        {
            multipartBuilder = new MultipartBuilder();
            multipartBuilder.addFieldArrays(mParams);
            multipartBuilder.addPartArrays(mMultipartParts);
        }

        // Set the "default" content type, determined by the convenience methods of this class
        if (mDefaultContentType != null && customContentType == null)
        {
            if (needMultipart)
            {
                connection.setRequestProperty(Headers.CONTENT_TYPE, multipartBuilder.getContentType());
            }
            else
            {
                connection.setRequestProperty(Headers.CONTENT_TYPE, mDefaultContentType + "; charset=" + charset.name());
            }
        }

        // Determine if we can encode the request in memory first to determine content length
        int minimumContentLength = -1;
        if (requestShouldHaveBody && mChunkedStreamingModeSize < 0)
        {
            minimumContentLength = 0;
            for (Map.Entry<String, ArrayList<Object>> entry : mParams.entrySet())
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
                    else if (param instanceof MultipartBuilder.DynamicPart)
                    {
                        long dynamicLength = ((MultipartBuilder.DynamicPart) param).contentLength();
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
                for (Map.Entry<String, ArrayList<MultipartBuilder.DynamicPart>> entry : mMultipartParts.entrySet())
                {
                    for (MultipartBuilder.DynamicPart part : entry.getValue())
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
            writeRequestBodyToStream(memoryStream, charset, multipartBuilder, customContentType, requestShouldAbort);

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
        else if (mRequestBody != null)
        {
            long contentLength = mRequestBodyLengthHint;
            if (contentLength < 0)
            {
                // Can we determine content length?
                if (mRequestBody instanceof File)
                {
                    contentLength = ((File) mRequestBody).length();
                }
                else if (mRequestBody instanceof ByteBuffer)
                {
                    contentLength = ((ByteBuffer) mRequestBody).limit();
                }
                else if (mRequestBody instanceof byte[])
                {
                    contentLength = ((byte[]) mRequestBody).length;
                }
                else if (mRequestBody instanceof Bitmap)
                {
                    contentLength = -1;
                }
                else if (mRequestBody instanceof MultipartBuilder.DynamicPart)
                {
                    contentLength = ((MultipartBuilder.DynamicPart) mRequestBody).contentLength();
                }
                else if (mRequestBody instanceof InputStream)
                {
                    contentLength = -1;
                }
                else
                {
                    mRequestBody = paramToString(mRequestBody);

                    ByteBuffer buffer = charset.encode(CharBuffer.wrap(mRequestBody.toString()));
                    contentLength = buffer.limit();

                    if (contentLength > ESTIMATED_SIZE_TO_ALLOW_IN_MEMORY)
                    {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                        {
                            connection.setFixedLengthStreamingMode(contentLength);
                        }
                        else if (contentLength <= 0x7FF89EC0)
                        {
                            // Android SDK < 19: 2 GiB limit
                            connection.setFixedLengthStreamingMode((int)contentLength);
                        }
                    }

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
                if (contentLength > ESTIMATED_SIZE_TO_ALLOW_IN_MEMORY)
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                    {
                        connection.setFixedLengthStreamingMode(contentLength);
                    }
                    else if (contentLength <= 0x7FF89EC0)
                    {
                        // Android SDK < 19: 2 GiB limit
                        connection.setFixedLengthStreamingMode((int)contentLength);
                    }
                }

                if (progressListener != null)
                {
                    progressListener.onRequestProgress(0L, contentLength);
                }

                connection.setRequestProperty(Headers.CONTENT_LENGTH, ((Long) contentLength).toString());
                connection.setDoOutput(true);

                ProgressOutputStream progressOutputStream = new ProgressOutputStream(connection.getOutputStream(), progressListener, contentLength);
                writeRequestBodyToStream(progressOutputStream, charset, multipartBuilder, customContentType, requestShouldAbort);
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
            if (mChunkedStreamingModeSize >= 0)
            {
                // Stream everything out, in chunked mode

                if (progressListener != null)
                {
                    progressListener.onRequestProgress(0L, -1);
                }

                connection.setDoOutput(true);

                ProgressOutputStream progressOutputStream = new ProgressOutputStream(connection.getOutputStream(), progressListener, -1);
                writeRequestBodyToStream(progressOutputStream, charset, multipartBuilder, customContentType, requestShouldAbort);
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
                    writeRequestBodyToStream(fileOutputStream, charset, multipartBuilder, customContentType, requestShouldAbort);
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

                    if (contentLength > ESTIMATED_SIZE_TO_ALLOW_IN_MEMORY)
                    {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                        {
                            connection.setFixedLengthStreamingMode(contentLength);
                        }
                        else if (contentLength <= 0x7FF89EC0)
                        {
                            // Android SDK < 19: 2 GiB limit
                            connection.setFixedLengthStreamingMode((int)contentLength);
                        }
                    }

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
        return new HttpResponse(connection, mAutoDecompress);
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
        final AtomicBoolean shouldAbortRequest = new AtomicBoolean(false);
        
        HttpAsyncTask task = new HttpAsyncTask<Object, Void, HttpResponse>()
        {
            private HttpResponse response = null;

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
                    catch (Exception e)
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
                        catch (Exception e)
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

        if (taskExecutor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
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
        for (Map.Entry<String, ArrayList<String>> entry : mHeaders.entrySet())
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

    private void writeRequestBodyToStream(
            OutputStream outputStream,
            Charset charset,
            MultipartBuilder multipart,
            String customContentType,
            AtomicBoolean shouldAbort) throws IOException
    {
        if (mRequestBody != null)
        {
            MultipartBuilder.writeDataToStream(mSettings, mRequestBody, outputStream, charset, shouldAbort);
        }
        else
        {
            if (multipart != null)
            {
                multipart.writeRequestBodyToStream(outputStream, charset, shouldAbort);
            }
            else
            {
                if ((customContentType != null && customContentType.equalsIgnoreCase(ContentType.TEXT_PLAIN))
                        || (mDefaultContentType != null && mDefaultContentType.equalsIgnoreCase(ContentType.TEXT_PLAIN)))
                {
                    boolean firstValue = true;
                    ByteBuffer byteBuffer;
                    for (Map.Entry<String, ArrayList<Object>> entry : mParams.entrySet())
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

                    for (Map.Entry<String, ArrayList<Object>> entry : mParams.entrySet())
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

    /*private static void sendDataOnStream(OutputStream outputStream, byte[] data, int count, ProgressListener progressListener, long[] progress) throws IOException
    {
        outputStream.write(data, 0, count);

        progress[0] += count;

        if (progressListener != null)
        {
            progressListener.onRequestProgress(progress[0], progress[1]);
        }
    }*/

    /**
     * Use to prepare the request in a file, or for debugging reasons etc.
     * @param outputStream The stream to write to
     * @return Content type of the request
     */
    public String writeRequestBodyToStream(OutputStream outputStream) throws IOException
    {
        Charset charset = null;

        String customContentType = null;
        for (Map.Entry<String, ArrayList<String>> entry : mHeaders.entrySet())
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
            charset = UTF8_CHARSET;
        }

        // Check if we need a multipart content type
        boolean needMultipart = mRequestBody == null &&
                (!mMultipartParts.isEmpty()
                        || MultipartBuilder.requiresMultipart(mParams));

        // We have data that must be encoded in multi-part form, so generate a boundary
        MultipartBuilder multipartBuilder = null;
        if (needMultipart)
        {
            multipartBuilder = new MultipartBuilder();
            multipartBuilder.addFieldArrays(mParams);
            multipartBuilder.addPartArrays(mMultipartParts);
        }

        String finalContentType = customContentType;

        // Set the "default" content type, determined by the convenience methods of this class
        if (mDefaultContentType != null && customContentType == null)
        {
            if (needMultipart)
            {
                finalContentType = multipartBuilder.getContentType();
            }
            else
            {
                finalContentType = mDefaultContentType + "; charset=" + charset.name();
            }
        }

        writeRequestBodyToStream(outputStream, charset, multipartBuilder, customContentType, null);

        return finalContentType;
    }

    public static String urlWithParameters(URL url, Map<String, ?> params)
    {
        return urlWithParameters(url.toExternalForm(), params, UTF8_CHARSET);
    }

    public static String urlWithParameters(URL url, Map<String, ?> params, Charset charset)
    {
        return urlWithParameters(url.toExternalForm(), params, charset);
    }

    public static String urlWithParameters(String url, Map<String, ?> params)
    {
        return urlWithParameters(url, params, UTF8_CHARSET);
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

    /**
     * HttpURLConnection does not allow more than a few basic HTTP Methods, we need to workaround that.
     * First we try to edit its internal list of supported HTTP methods, and as a fallback we try to directly set the "method" field using introspection
     */
    private static void tryFixingHttpURLConnectionMethods()
    {
        try
        {
            AccessController.doPrivileged(new PrivilegedExceptionAction<Object>()
            {
                @Override
                public Object run() throws NoSuchFieldException, IllegalAccessException
                {
                    try
                    {
                        Class<?> connectionClass = HttpURLConnection.class;

                        Field methodField = connectionClass.getDeclaredField("PERMITTED_USER_METHODS");
                        methodField.setAccessible(true);
                        String[] methodsArray = (String[])methodField.get(null);

                        ArrayList<String> newSupportedMethods = new ArrayList<String>();
                        for (String method : methodsArray)
                        {
                            newSupportedMethods.add(method);
                        }
                        if (!newSupportedMethods.contains("PATCH"))
                        {
                            newSupportedMethods.add("PATCH");
                        }
                        if (!newSupportedMethods.contains("CONNECT"))
                        {
                            newSupportedMethods.add("CONNECT");
                        }

                        methodsArray = newSupportedMethods.toArray(new String[newSupportedMethods.size()]);
                        methodField.set(null, methodsArray);
                    }
                    catch (Exception ignored)
                    {
                    }
                    return null;
                }
            });
        }
        catch (Exception ignored)
        {
        }
    }

    private static void trySetHttpMethodUsingIntrospection(final HttpURLConnection httpURLConnection, final String method)
    {
        try
        {
            AccessController.doPrivileged(new PrivilegedExceptionAction<Object>()
            {
                @Override
                public Object run() throws NoSuchFieldException, IllegalAccessException
                {
                    try
                    {
                        httpURLConnection.setRequestMethod(method);
                    }
                    catch (final ProtocolException pe)
                    {
                        Class<?> connectionClass = httpURLConnection.getClass();
                        try
                        {
                            Field delegateField = connectionClass.getDeclaredField("delegate");
                            delegateField.setAccessible(true);
                            HttpURLConnection delegateConnection = (HttpURLConnection) delegateField.get(httpURLConnection);
                            trySetHttpMethodUsingIntrospection(delegateConnection, method);
                        }
                        catch (NoSuchFieldException ignored)
                        {
                        }
                        catch (IllegalArgumentException e)
                        {
                            throw new RuntimeException(e);
                        }
                        catch (IllegalAccessException e)
                        {
                            throw new RuntimeException(e);
                        }
                        try
                        {
                            while (connectionClass != null)
                            {
                                try
                                {
                                    Field methodField = connectionClass.getDeclaredField("method");
                                    methodField.setAccessible(true);
                                    methodField.set(httpURLConnection, method);
                                    break;
                                }
                                catch (NoSuchFieldException e)
                                {
                                    connectionClass = connectionClass.getSuperclass();
                                }
                            }
                        }
                        catch (final Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    }
                    return null;
                }
            });
        }
        catch (final PrivilegedActionException e)
        {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException)
            {
                throw (RuntimeException) cause;
            }
            else
            {
                throw new RuntimeException(cause);
            }
        }
    }

    @Override
    public String toString()
    {
        return this.mHttpMethod + ' ' + this.mUrl;
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
        public void onRequestException(Exception exception)
        {
            // Do nothing. If you want printouts, override and print. By default we do not pollute the Logcat, and we do not expose app's data on the log.
        }

        /**
         * Called on the AsyncTask thread with any exception thrown during the request connection
         */
        public void onResponseException(Exception exception)
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
