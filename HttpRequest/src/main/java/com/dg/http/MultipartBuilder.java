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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public class MultipartBuilder
{
    private static final int BUFFER_SIZE = 4096;
    private static final byte[] CRLF_BYTES = {'\r', '\n'};
    private static final byte[] MULTIPART_HEADER_CONTENT_DISPOSITION_AND_NAME_BYTES = "Content-Disposition: form-mData; name=\"".getBytes();
    private static final byte[] MULTIPART_HEADER_END_NAME_AND_FILENAME = "\"; filename=\"".getBytes();
    private static final byte[] MULTIPART_HEADER_END = "\"".getBytes();
    private static final byte[] MULTIPART_HEADER_CONTENT_TYPE = "Content-Type: ".getBytes();
    private static final byte[] MULTIPART_HEADER_CONTENT_TYPE_END_CHARSET = "; charset=".getBytes();
    private static final char[] MULTIPART_BOUNDARY_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private String mBoundary = null;
    private Map<String, ArrayList<Object>> mFields = new HashMap<String, ArrayList<Object>>();
    private Settings mSettings = new Settings();

    public MultipartBuilder()
    {
        mBoundary = generateBoundary();
    }

    public MultipartBuilder(String boundary)
    {
        mBoundary = boundary;
    }

    public String getBoundary()
    {
        return mBoundary;
    }

    public Settings getSettings()
    {
        return mSettings;
    }

    public String getContentType()
    {
        return HttpRequest.ContentType.MULTIPART_FORM_DATA + "; boundary=" + getBoundary();
    }

    public Map<String, ArrayList<Object>> getFields()
    {
        return mFields;
    }

    public MultipartBuilder setFields(final Map<?, ?> fields)
    {
        mFields = new HashMap<>();
        return addFields(fields);
    }

    public MultipartBuilder setFields(final Object... params)
    {
        this.mFields = new HashMap<>();
        for (int i = 0; i < params.length; i += 2)
        {
            addField(params[i].toString(), params.length > i + 1 ? params[i + 1] : null);
        }
        return this;
    }

    public MultipartBuilder addFields(final Map<?, ?> fields)
    {
        if (fields != null)
        {
            for (HashMap.Entry<?, ?> entry : fields.entrySet())
            {
                addField(entry.getKey().toString(), entry.getValue());
            }
        }
        return this;
    }

    public MultipartBuilder addFieldArrays(final Map<String, ArrayList<Object>> fields)
    {
        if (fields != null)
        {
            for (HashMap.Entry<?, ArrayList<Object>> entry : fields.entrySet())
            {
                for (Object field : entry.getValue())
                {
                    addField(entry.getKey().toString(), field);
                }
            }
        }
        return this;
    }

    public MultipartBuilder addPartArrays(final Map<String, ArrayList<DynamicPart>> fields)
    {
        if (fields != null)
        {
            for (HashMap.Entry<?, ArrayList<DynamicPart>> entry : fields.entrySet())
            {
                for (Object field : entry.getValue())
                {
                    addField(entry.getKey().toString(), field);
                }
            }
        }
        return this;
    }

    public MultipartBuilder addField(final String key, final Object value)
    {
        if (mFields.containsKey(key))
        {
            ArrayList<Object> values = mFields.get(key);
            values.add(value);
        }
        else
        {
            ArrayList<Object> values = new ArrayList<>();
            values.add(value);
            mFields.put(key, values);
        }
        return this;
    }

    public MultipartBuilder setField(final String key, final Object value)
    {
        ArrayList<Object> values = new ArrayList<>();
        values.add(value);
        mFields.put(key, values);
        return this;
    }

    public Object getField(final String key)
    {
        if (mFields.containsKey(key))
        {
            return mFields.get(key).get(0);
        }
        return null;
    }

    public Object[] getFields(final String key)
    {
        if (mFields.containsKey(key))
        {
            return mFields.get(key).toArray();
        }
        return new Object[]{ };
    }

    public MultipartBuilder removeField(final String key)
    {
        mFields.remove(key);
        return this;
    }

    public MultipartBuilder addField(String name, Object data, Charset charset)
    {
        return addField(name, data, null, null, -1, charset);
    }

    public MultipartBuilder addField(String name, Object data, String contentType, long contentLength)
    {
        return addField(name, data, null, contentType, contentLength, null);
    }

    public MultipartBuilder addField(String name, Object data, String contentType, long contentLength, Charset charset)
    {
        return addField(name, data, null, contentType, contentLength, charset);
    }

    public MultipartBuilder addField(String name, Object data, String fileName)
    {
        return addField(name, data, fileName, null, -1, null);
    }

    public MultipartBuilder addField(String name, Object data, String fileName, String contentType)
    {
        return addField(name, data, fileName, contentType, -1, null);
    }

    public MultipartBuilder addField(String name, Object data, String fileName, String contentType, long contentLength)
    {
        return addField(name, data, fileName, contentType, contentLength, null);
    }

    public MultipartBuilder addField(String name, Object data, String fileName, String contentType, long contentLength, Charset charset)
    {
        Part part = new Part(
                data,
                fileName,
                contentType,
                contentLength,
                charset);

        return addField(name, part);
    }

    public static String generateBoundary()
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

    public void writeRequestBodyToStream(
            OutputStream outputStream,
            Charset charset,
            AtomicBoolean shouldAbort) throws IOException
    {
        byte [] boundaryBytes = ("--" + mBoundary).getBytes(charset);

        for (Map.Entry<String, ArrayList<Object>> entry : mFields.entrySet())
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
                    partContentType = HttpRequest.ContentType.OCTET_STREAM;
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
                        partContentType = HttpRequest.ContentType.OCTET_STREAM;
                    }
                }
                else if (param instanceof Bitmap)
                {
                    partContentType = ((Bitmap)param).hasAlpha()
                            ? HttpRequest.ContentType.IMAGE_PNG
                            : HttpRequest.ContentType.IMAGE_JPEG;
                }
                else
                {
                    partContentType = HttpRequest.ContentType.TEXT_PLAIN;
                }

                writePartHeader(outputStream, charset, entry.getKey(), partFileName, partContentType, partCharset);
                outputStream.write(CRLF_BYTES);

                // Multipart body
                if (param != null)
                {
                    writeDataToStream(mSettings, param, outputStream, charset, shouldAbort);
                }
                outputStream.write(CRLF_BYTES);
            }
        }

        // Prologue boundary...
        outputStream.write(boundaryBytes);
        outputStream.write("--".getBytes(charset));
        outputStream.write(CRLF_BYTES);
    }

    public static void writePartHeader(OutputStream outputStream,
                                 Charset charset,
                                 String name,
                                 String filename,
                                 String contentType,
                                 Charset contentCharset) throws IOException
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

    public static void writeDataToStream(MultipartBuilder.Settings settings,
                                         Object param,
                                         OutputStream outputStream,
                                         Charset charset,
                                         AtomicBoolean shouldAbort) throws IOException
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
                bmp.compress(bmp.hasAlpha()
                                     ? Bitmap.CompressFormat.PNG
                                     : Bitmap.CompressFormat.JPEG,
                             settings.getJpegCompressionQuality() == 0
                                     ? Settings.getDefaultJpegCompressionQuality()
                                     : settings.getJpegCompressionQuality(),
                             outputStream);
                if (settings.getAutoRecycleBitmaps())
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
            dynamicPart.sendPartToStream(settings, outputStream, partCharset, shouldAbort);
        }
        else
        {
            ByteBuffer byteBuffer = charset.encode(CharBuffer.wrap(paramToString(param)));
            outputStream.write(byteBuffer.array(), 0, byteBuffer.limit());
        }
    }

    public static boolean requiresMultipart(Map<String, ArrayList<Object>> fields)
    {
        for (Map.Entry<String, ArrayList<Object>> entry : fields.entrySet())
        {
            for (Object param : entry.getValue())
            {
                if (param instanceof File ||
                        param instanceof Bitmap ||
                        param instanceof ByteBuffer ||
                        param instanceof InputStream ||
                        param instanceof byte[] ||
                        param instanceof MultipartBuilder.DynamicPart)
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static String paramToString(Object param)
    {
        if (param instanceof Boolean)
        {
            return ((Boolean)param) ? "true" : "false";
        }

        return param.toString();
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
            return HttpRequest.ContentType.OCTET_STREAM;
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
         * @param settings The multipart builder settings
         * @param outputStream The stream to write to
         * @param charset The charset that should be used to write the data, if relevant at all. It represents the value returned from charset(), or default inherited from the request itself.
         * @param shouldAbort Indicates whether the streaming should be aborted. When this is set, stopping writing is safe at any point.
         */
        public abstract void sendPartToStream(Settings settings, OutputStream outputStream, Charset charset, AtomicBoolean shouldAbort) throws IOException;
    }
    
    public static class Part extends DynamicPart
    {
        private Object mData;
        private String mFileName;
        private String mContentType;
        private long mContentLength = -1;
        private Charset mCharset;

        public Part(Object data,
                         String fileName,
                         String contentType,
                         long contentLength,
                         Charset charset)
        {
            this.mFileName = fileName;

            if (contentType != null)
            {
                this.mContentType = contentType;
            }
            else
            {
                // Try to autodetect
                if (data instanceof byte[] ||
                        data instanceof InputStream ||
                        data instanceof ByteBuffer ||
                        data instanceof File ||
                        data instanceof Bitmap)
                {
                    this.mContentType = HttpRequest.ContentType.OCTET_STREAM;
                }
            }

            this.mContentLength = contentLength;
            this.mCharset = charset;
            this.mData = data;
        }

        @Override
        public long contentLength()
        {
            if (mContentLength < 0)
            {
                if (mData instanceof File)
                {
                    return ((File) mData).length();
                }
                else if (mData instanceof byte[])
                {
                    return ((byte[]) mData).length;
                }
                else if (mData instanceof ByteBuffer)
                {
                    return ((ByteBuffer) mData).limit();
                }
            }
            return mContentLength;
        }

        @Override
        public String contentType()
        {
            return mContentType;
        }

        @Override
        public String fileName()
        {
            if (mFileName == null)
            {
                if (mData instanceof File)
                {
                    return ((File) mData).getName();
                }
            }
            return mFileName;
        }

        @Override
        public Charset charset()
        {
            return mCharset;
        }

        @Override
        public void sendPartToStream(Settings settings,
                                     OutputStream outputStream,
                                     Charset charset,
                                     AtomicBoolean shouldAbort) throws IOException
        {
            writeDataToStream(settings, mData, outputStream, charset, shouldAbort);
        }
    }

    public static class Settings
    {
        private static int sDefaultJpegCompressionQuality = 90;
        private int mJpegCompressionQuality = 0;
        private boolean mAutoRecycleBitmaps = false;

        public Settings()
        {

        }

        /**
         * @return Default jpeg compression quality (0-100), for when quality = 0.
         */
        public static int getDefaultJpegCompressionQuality()
        {
            return sDefaultJpegCompressionQuality;
        }

        /**
         * Sets default jpeg compression quality (0-100), for when quality = 0.
         * @param defaultJpegCompressionQuality
         */
        public static void setDefaultJpegCompressionQuality(int defaultJpegCompressionQuality)
        {
            sDefaultJpegCompressionQuality = defaultJpegCompressionQuality;
        }

        /**
         * @return Jpeg compression quality (0-100).
         */
        public int getJpegCompressionQuality()
        {
            return mJpegCompressionQuality;
        }

        /**
         * Sets jpeg compression quality (0-100).
         * @param jpegCompressionQuality
         * @return self
         */
        public Settings setJpegCompressionQuality(int jpegCompressionQuality)
        {
            this.mJpegCompressionQuality = jpegCompressionQuality;
            return this;
        }

        /**
         * @return Should bitmaps be auto-recycled?
         */
        public boolean getAutoRecycleBitmaps()
        {
            return mAutoRecycleBitmaps;
        }

        /**
         * Sets whether or not bitmaps should be auto-recycled.
         * @param autoRecycleBitmaps
         * @return self
         */
        public Settings setAutoRecycleBitmaps(boolean autoRecycleBitmaps)
        {
            this.mAutoRecycleBitmaps = autoRecycleBitmaps;
            return this;
        }
    }
}
