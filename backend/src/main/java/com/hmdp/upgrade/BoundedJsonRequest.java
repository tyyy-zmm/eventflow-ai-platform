package com.hmdp.upgrade;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class BoundedJsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    BoundedJsonRequest(HttpServletRequest request) throws IOException {
        super(request);body=request.getInputStream().readNBytes(16385);
        if(body.length>16384) throw new Problem(413,"BODY_TOO_LARGE");
    }
    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }
    @Override public ServletInputStream getInputStream() {
        var input=new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available()==0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON API"); }
        };
    }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
}
