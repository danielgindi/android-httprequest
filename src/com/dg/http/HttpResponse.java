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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class HttpResponse
{
    private static final String DATE_FORMAT_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";
    private static final String DATE_FORMAT_RFC1036 = "EEEE, dd-MMM-yy HH:mm:ss zzz";
    private static final String DATE_FORMAT_ASCTIME = "EEE MMM d HH:mm:ss yyyy";
    private static final Charset utf8Charset = Charset.forName("UTF8");
    private static final int MAX_SIZE_TO_ALLOW_IN_MEMORY = 8192; // When we do not know the Content-Length in advance, we build the request in memory, or in file if it's too big or unknown.
    private static final int BUFFER_SIZE = 4096;

    private boolean autoDecompress = true;
    private boolean isBuffered = false;
    private byte[] memoryBuffer = null;
    private File fileBuffer = null;

    private HttpURLConnection connection;
    private int statusCode;
    private String statusMessage;
    private Map<String, List<String>> headers;
    private static final String[] EMPTY_STRING_ARRAY = new String[]{ };
    private String originalCharset;
    private Charset charset;
    private URL url;

    public HttpResponse(HttpURLConnection connection) throws IOException
    {
        this.connection = connection;
        this.autoDecompress = true;
        readHeaders();
    }

    public HttpResponse(HttpURLConnection connection, boolean autoDecompress) throws IOException
    {
        this.connection = connection;
        this.autoDecompress = autoDecompress;
        readHeaders();
    }

    private void readHeaders() throws IOException
    {
        statusCode = this.connection.getResponseCode();
        statusMessage = this.connection.getResponseMessage();
        headers = this.connection.getHeaderFields();
        originalCharset = getHeaderParameter(Headers.CONTENT_TYPE, "charset");
        if (originalCharset != null && originalCharset.length() > 0)
        {
            charset = Charset.forName(originalCharset);
        }
        else
        {
            charset = utf8Charset;
        }
        url = this.connection.getURL();
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public String getStatusMessage()
    {
        return statusMessage;
    }

    public URL getURL()
    {
        return url;
    }

    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    public String[] getHeaders(final String name)
    {
        List<String> list = headers.get(name);
        if (list != null)
        {
            return list.toArray(new String[list.size()]);
        }
        return EMPTY_STRING_ARRAY;
    }

    public String getHeader(final String name)
    {
        List<String> list = headers.get(name);
        if (list != null)
        {
            return list.isEmpty() ? null : list.get(0);
        }
        return null;
    }

    public Date getDateHeader(final String name)
    {
        String date = getHeader(name);
        if (date == null)
        {
            return null;
        }
        return parseHttpDate(date);
    }

    public int getIntHeader(final String name, final int defaultValue)
    {
        try
        {
            return Integer.parseInt(getHeader(name));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    public long getLongHeader(final String name, final long defaultValue)
    {
        try
        {
            return Long.parseLong(getHeader(name));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    public String getHeaderParameter(final String headerName, final String paramName)
    {
        String header = getHeader(headerName);
        if (header != null)
        {
            Map<String, String> params = parseHeaderParams(header);
            return params.get(paramName);
        }
        return null;
    }

    public Map<String, String> getHeaderParameters(final String headerName)
    {
        return parseHeaderParams(getHeader(headerName));
    }

    public boolean isSuccessful()
    {
        return statusCode == StatusCodes.OK ||
                statusCode == StatusCodes.CREATED ||
                statusCode == StatusCodes.NO_CONTENT ||
                statusCode == StatusCodes.NON_AUTHORITATIVE_INFORMATION ||
                statusCode == StatusCodes.RESET_CONTENT ||
                statusCode == StatusCodes.PARTIAL_CONTENT;
    }

    public String getContentType()
    {
        return getHeader(Headers.CONTENT_TYPE);
    }

    public long getContentLength()
    {
        return getLongHeader(Headers.CONTENT_LENGTH, -1);
    }

    public boolean hasContentLength()
    {
        return getContentLength() > -1;
    }

    public String getContentEncoding()
    {
        return getHeader(Headers.CONTENT_ENCODING);
    }

    public String getServer()
    {
        return getHeader(Headers.SERVER);
    }

    public Date getDate()
    {
        return getDateHeader(Headers.DATE);
    }

    public String getCacheControl()
    {
        return getHeader(Headers.CACHE_CONTROL);
    }

    public String getETag()
    {
        return getHeader(Headers.ETAG);
    }

    public Date getExpires()
    {
        return getDateHeader(Headers.EXPIRES);
    }

    public Date getLastModified()
    {
        return getDateHeader(Headers.LAST_MODIFIED);
    }

    public String getLocation()
    {
        return getHeader(Headers.LOCATION);
    }

    public String getOriginalCharset()
    {
        return originalCharset;
    }

    public Charset getCharset()
    {
        return charset;
    }

    public InputStream getInputStream() throws IOException
    {
        return getInputStream(null);
    }

    public InputStream getInputStream(HttpRequest.ProgressListener progressListener) throws IOException
    {
        if (isBuffered)
        {
            if (memoryBuffer != null)
            {
                return new ByteArrayInputStream(memoryBuffer);
            }
            else if (fileBuffer != null)
            {
                return new FileInputStream(fileBuffer);
            }
            return new ByteArrayInputStream(new byte[0]);
        }
        else
        {
            InputStream stream;
            if (statusCode < 400)
            {
                stream = connection.getInputStream();
            }
            else
            {
                stream = connection.getErrorStream();
                if (stream == null)
                {
                    try
                    {
                        stream = connection.getInputStream();
                    }
                    catch (IOException e)
                    {
                        if (getContentLength() > 0)
                        {
                            disconnect();
                            throw e;
                        }
                        else
                        {
                            stream = new ByteArrayInputStream(new byte[0]);
                        }
                    }
                }
            }

            boolean isCompressedStream = false;
            
            if (autoDecompress && "gzip".equals(getContentEncoding()))
            {
                stream = new GZIPInputStream(stream);
                isCompressedStream = true;
            }

            if (progressListener != null)
            {
                stream = new ProgressInputStream(stream, progressListener, isCompressedStream ? -1L : getContentLength());
            }

            return stream;
        }
    }

    public String getResponseText() throws IOException
    {
        InputStreamReader reader = new InputStreamReader(getInputStream(), getCharset());
        StringWriter writer = new StringWriter();

        int firstChar = reader.read();
        if (firstChar > -1)
        {
            if (firstChar != 0xFEFF) // 0xFEFF is the BOM, encoded in whatever encoding
            {
                writer.write(firstChar);
            }
        }

        char[] buffer = new char[BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer, 0, BUFFER_SIZE)) > -1)
        {
            writer.write(buffer, 0, read);
        }

        String theString = writer.toString();

        writer.close();
        reader.close();

        return theString;
    }

    public byte[] getResponseBytes() throws IOException
    {
        if (isBuffered && memoryBuffer != null)
        {
            return memoryBuffer;
        }
        else
        {
            InputStream inputStream = getInputStream();
            long contentLength = inputStream.isCompressedStream() ? -1L : getContentLength();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(contentLength >= 0 ? (int)contentLength : 64);

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer, 0, BUFFER_SIZE)) > -1)
            {
                outputStream.write(buffer, 0, read);
            }

            byte [] theData = outputStream.toByteArray();

            outputStream.close();
            inputStream.close();

            return theData;
        }
    }

    public void prebuffer() throws IOException
    {
        prebuffer(null);
    }

    public void prebuffer(HttpRequest.ProgressListener progressListener) throws IOException
    {
        if (isBuffered) return;

        InputStream stream = getInputStream(progressListener);

        long contentLength = inputStream.isCompressedStream() ? -1L : getContentLength();
        if (contentLength >= 0L && contentLength <= MAX_SIZE_TO_ALLOW_IN_MEMORY)
        {
            memoryBuffer = new byte[(int)contentLength];
            int read, totalRead = 0, toRead = (int)contentLength;
            while ((read = stream.read(memoryBuffer, totalRead, toRead)) > 0)
            {
                totalRead += read;
                toRead -= read;
            }
        }
        else
        {
            fileBuffer = File.createTempFile("response-buffer", ".http", null);
            fileBuffer.deleteOnExit();
            FileOutputStream fileOutputStream = null;
            
            IOException thrownException = null;
            
            try
            {
                fileOutputStream = new FileOutputStream(fileBuffer);
            }
            catch (IOException e)
            {
                thrownException = e;
                fileBuffer.delete();
                fileBuffer = null;
            }

            if (fileOutputStream != null)
            {
                byte [] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = stream.read(buffer, 0, BUFFER_SIZE)) > 0)
                {
                    fileOutputStream.write(buffer, 0, read);
                }
                fileOutputStream.flush();
                fileOutputStream.close();
            }
            else
            {
                if (contentLength >= 0L)
                {
                    memoryBuffer = new byte[(int)contentLength];
                    int read, totalRead = 0, toRead = (int)contentLength;
                    while ((read = stream.read(memoryBuffer, totalRead, toRead)) > 0)
                    {
                        totalRead += read;
                        toRead -= read;
                    }
                }
                else
                {
                    throw thrownException;
                }
            }
        }

        stream.close();
        disconnect();

        isBuffered = true;
    }

    public void disconnect()
    {
        if (connection != null)
        {
            try
            {
                connection.disconnect();
            }
            catch (Exception ignored)
            {

            }
            connection = null;
        }
    }

    protected void finalize ()
    {
        if (fileBuffer != null)
        {
            fileBuffer.delete();
            fileBuffer = null;
        }
    }

    public static Date parseHttpDate(final String date)
    {
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT_RFC1123);
        try
        {
            return format.parse(date);
        }
        catch (ParseException ignored)
        {
        }
        format = new SimpleDateFormat(DATE_FORMAT_RFC1036);
        try
        {
            return format.parse(date);
        }
        catch (ParseException ignored)
        {
        }
        format = new SimpleDateFormat(DATE_FORMAT_ASCTIME);
        try
        {
            return format.parse(date);
        }
        catch (ParseException ignored)
        {
        }
        return null;
    }

    public static Map<String, String> parseHeaderParams(final String header)
    {
        Map<String, String> params = new LinkedHashMap<String, String>();

        if (header == null || header.length() == 0)
        {
            return params;
        }

        final int totalLength = header.length();

        int semicolon = header.indexOf(';');
        if (semicolon == -1 || semicolon == totalLength - 1)
        {
            return params;
        }

        int nameStart = semicolon + 1, nameEnd;
        int nextSemicolon = header.indexOf(';', nameStart);
        if (nextSemicolon == -1)
        {
            nextSemicolon = totalLength;
        }

        while (nameStart < nextSemicolon)
        {
            nameEnd = header.indexOf('=', nameStart);
            if (nameEnd != -1 && nameEnd < nextSemicolon)
            {
                String name = header.substring(nameStart, nameEnd).trim();
                if (name.length() > 0)
                {
                    String value = header.substring(nameEnd + 1, nextSemicolon).trim();
                    int length = value.length();
                    if (length != 0)
                    {
                        if (length > 2 && '"' == value.charAt(0) && '"' == value.charAt(length - 1))
                        {
                            params.put(name, value.substring(1, length - 1));
                        }
                        else
                        {
                            params.put(name, value);
                        }
                    }
                }
            }

            nameStart = nextSemicolon + 1;
            nextSemicolon = header.indexOf(';', nameStart);
            if (nextSemicolon == -1)
            {
                nextSemicolon = totalLength;
            }
        }

        return params;
    }

    public abstract static class Headers
    {
        public static final String CACHE_CONTROL = "Cache-Control";
        public static final String CONTENT_ENCODING = "Content-Encoding";
        public static final String CONTENT_LENGTH = "Content-Length";
        public static final String CONTENT_TYPE = "Content-Type";
        public static final String DATE = "Date";
        public static final String ETAG = "ETag";
        public static final String EXPIRES = "Expires";
        public static final String LAST_MODIFIED = "Last-Modified";
        public static final String LOCATION = "Location";
        public static final String SERVER = "Server";
    }

    public abstract static class StatusCodes
    {
        public static final int OK = 200;
        public static final int CREATED = 201;
        public static final int ACCEPTED = 202;
        public static final int NON_AUTHORITATIVE_INFORMATION = 203;
        public static final int NO_CONTENT = 204;
        public static final int RESET_CONTENT = 205;
        public static final int PARTIAL_CONTENT = 206;
        public static final int MULTIPLE_CHOICES = 300;
        public static final int MOVED_PERMANENTLY = 301;
        public static final int FOUND = 302;
        public static final int SEE_OTHER = 303;
        public static final int NOT_MODIFIED = 304;
        public static final int USE_PROXY = 305;
        public static final int TEMPORARY_REDIRECT = 307;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int PAYMENT_REQUIRED = 402;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int METHOD_NOT_ALLOWED = 405;
        public static final int NOT_ACCEPTABLE = 406;
        public static final int PROXY_AUTHENTICATION_REQUIRED = 407;
        public static final int REQUEST_TIMEOUT = 408;
        public static final int CONFLICT = 409;
        public static final int GONE = 410;
        public static final int LENGTH_REQUIRED = 411;
        public static final int PRECONFITION_FAILED = 412;
        public static final int REQUEST_ENTITY_TOO_LARGE = 413;
        public static final int REQUEST_URI_TOO_LONG = 414;
        public static final int UNSUPPORTED_MEDIA_TYPE = 415;
        public static final int REQUEST_RANGE_NOT_SATISFIABLE = 416;
        public static final int EXPECTATION_FAILED = 417;
        public static final int INTERNAL_SERVER_ERROR = 500;
        public static final int NOT_IMPLEMENTED = 501;
        public static final int BAD_GATEWAY = 502;
        public static final int SERVICE_UNAVAILABLE = 503;
        public static final int GATEWAY_TIMEOUT = 504;
        public static final int HTTP_VERSION_NOT_SUPPORTED = 505;
    }

    public static class ProgressInputStream extends InputStream
    {
        InputStream inputStream;
        long totalBytes;
        long totalRead;
        HttpRequest.ProgressListener progressListener;

        public ProgressInputStream(InputStream inputStream, HttpRequest.ProgressListener progressListener, long totalBytes)
        {
            super();

            this.inputStream = inputStream;
            this.totalBytes = totalBytes;
            this.totalRead = 0;
            this.progressListener = progressListener;
        }

        @Override
        public void close() throws IOException
        {
            inputStream.close();
        }

        @Override
        public int read() throws IOException
        {
            int read = inputStream.read();

            totalRead += 1;
            if (progressListener != null)
            {
                progressListener.onResponseProgress(totalRead, totalBytes);
            }

            return read;
        }

        @Override
        public int read(byte[] buffer) throws IOException
        {
            int read = inputStream.read(buffer);

            totalRead += read;
            if (progressListener != null)
            {
                progressListener.onResponseProgress(totalRead, totalBytes);
            }

            return read;
        }

        @Override
        public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException
        {
            int read = inputStream.read(buffer, byteOffset, byteCount);

            totalRead += read;
            if (progressListener != null)
            {
                progressListener.onResponseProgress(totalRead, totalBytes);
            }

            return read;
        }

        @Override
        public long skip(long byteCount) throws IOException
        {
            long read = inputStream.skip(byteCount);

            if (read > -1)
            {
                totalRead += read;
                if (progressListener != null)
                {
                    progressListener.onResponseProgress(totalRead, totalBytes);
                }
            }

            return read;
        }
    }
}
