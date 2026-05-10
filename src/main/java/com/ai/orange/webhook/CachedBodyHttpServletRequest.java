package com.ai.orange.webhook;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Reads the request body into a byte array on construction so it can be replayed
 * through {@link #getInputStream()} or {@link #getReader()} repeatedly.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = StreamUtils.copyToByteArray(request.getInputStream());
    }

    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream src = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return src.read(); }
            @Override public boolean isFinished() { return src.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { /* sync model */ }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset cs = getCharacterEncoding() != null
                ? Charset.forName(getCharacterEncoding())
                : Charset.defaultCharset();
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), cs));
    }
}
