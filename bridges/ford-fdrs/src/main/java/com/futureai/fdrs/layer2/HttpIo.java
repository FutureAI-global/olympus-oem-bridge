package com.futureai.fdrs.layer2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpIo {
    private static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private HttpIo() {}

    public static final class Request {
        public String method = "";
        public String path = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body;

        public String bodyAsString() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    public static final class Response {
        public int status;
        public String reason = "";
        public String contentType = "application/json; charset=utf-8";
        public byte[] body = new byte[0];
        public final Map<String, String> headers = new LinkedHashMap<>();

        public static Response json(int status, String reason, String jsonBody) {
            Response r = new Response();
            r.status = status;
            r.reason = reason;
            r.body = jsonBody.getBytes(StandardCharsets.UTF_8);
            return r;
        }
    }

    public static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            headBuf.write(b);
            switch (state) {
                case 0: state = (b == '\r') ? 1 : 0; break;
                case 1: state = (b == '\n') ? 2 : 0; break;
                case 2: state = (b == '\r') ? 3 : 0; break;
                case 3: state = (b == '\n') ? 4 : 0; break;
                default: state = 0;
            }
            if (state == 4) break;
            if (headBuf.size() > MAX_HEAD_BYTES) {
                throw new IOException("request headers exceed " + MAX_HEAD_BYTES + " bytes");
            }
        }
        if (state != 4) throw new IOException("incomplete request headers (EOF before \\r\\n\\r\\n)");
        String head = headBuf.toString(StandardCharsets.ISO_8859_1.name());
        String[] lines = head.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) throw new IOException("empty request line");

        Request r = new Request();
        String[] rl = lines[0].split(" ", 3);
        if (rl.length < 3) throw new IOException("bad request line: " + lines[0]);
        r.method = rl[0].toUpperCase(Locale.ROOT);
        r.path = rl[1];

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(colon + 1).trim();
            r.headers.put(k, v);
        }

        String cl = r.headers.get("content-length");
        if (cl != null) {
            int len;
            try { len = Integer.parseInt(cl); } catch (NumberFormatException e) {
                throw new IOException("bad Content-Length: " + cl);
            }
            if (len < 0 || len > MAX_BODY_BYTES) {
                throw new IOException("Content-Length out of range: " + len);
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) throw new IOException("unexpected EOF reading body (got " + off + "/" + len + ")");
                off += n;
            }
            r.body = body;
        }
        return r;
    }

    public static void writeResponse(OutputStream out, Response r) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason == null ? "" : r.reason).append("\r\n");
        head.append("Content-Type: ").append(r.contentType).append("\r\n");
        head.append("Content-Length: ").append(r.body == null ? 0 : r.body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("X-Bridge-Bundle: futureai-fdrs-layer2/").append(Activator.BUNDLE_VERSION).append("\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (r.body != null) out.write(r.body);
        out.flush();
    }
}
