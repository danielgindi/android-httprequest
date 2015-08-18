HttpRequest
===========

A Java based HttpRequest/Response library. 
This is currently only tested on the Android platform, but should work generally on any Java platform.

Why did I create this library?
Mainly because I got tired of what was out there... Libraries that limit you in so many ways, or provide ugly API, or have bugs with encoding the multipart entities (like apache...).
And one of the other reasons was that I just had to have a large request that does not fill up the device's memory, but prepares on disk and streams out to the connection.

I do not have the time yet for documentation, but it should be pretty self explanatory how to use this library.
You have the `HttpRequest` class which, well, represents the HTTP request... 
You pass in parameters of any kind, headers, multi-parts, etc. and fire the connection.
You can use the `getResponse` function synchronously on any thread you wish (except the UI thread!),
or you can use the `getResponseAsync` which will internally use the AsyncTask mechanism.

Features:
* Taking streams as request body or as multi-parts
* Chunked encoding mode
* Large requests will be created on disk first, to prevent wasting memory. (Content-Length must be calculated exactly, and so many libraries hold the whole request in memory and then calculate the length...)
* Many helper functions to do stuff with the request and response.
* Taking params with many types
* Encoding Bitmap params
* The `HttpResponse` class to wrap the response
* `HttpResponse` will take care of caching the response to disk first if you work with `getResponseAsync` (or call `prebuffer()`)
* The connection is abortable at any stage of request of response
* Most functions in `HttpRequest` are chainable

I strongly encourage getting involved in this project to make it better!

Have fun and a good luck with your projects!

## Dependency

[Download from Maven Central (.aar)](https://oss.sonatype.org/index.html#view-repositories;releases~browsestorage~/com/github/danielgindi/httprequest/1.0.0/httprequest-1.0.0.aar)

**or**

```java
	dependencies {
    	compile 'com.github.danielgindi:httprequest:1.0.0'
	}
```

## License

All the code here is under MIT license. Which means you could do virtually anything with the code.
I will appreciate it very much if you keep an attribution where appropriate.

    The MIT License (MIT)
    
    Copyright (c) 2013 Daniel Cohen Gindi (danielgindi@gmail.com)
    
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
