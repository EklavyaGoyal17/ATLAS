package dev.atlas.platform;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Buffers at most 16 KiB + one sentinel byte, including requests without Content-Length. */
final class BoundedRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    BoundedRequest(HttpServletRequest request) throws IOException {
        super(request);
        body = request.getInputStream().readNBytes(16 * 1024 + 1);
    }

    boolean tooLarge() {
        return body.length > 16 * 1024;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return input.read(target, offset, length);
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException(
                    "Synchronous JSON API only"
                );
            }
        };
    }
}
