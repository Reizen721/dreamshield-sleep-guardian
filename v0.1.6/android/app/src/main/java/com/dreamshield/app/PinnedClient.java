package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class PinnedClient {
    static final int MAX_BODY = 64 * 1024;
    static final int MAX_RESPONSE = 10 * 1024 * 1024;

    static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        Response(int status, String contentType, byte[] body) {
            this.status = status; this.contentType = contentType; this.body = body;
        }
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    static Response exchange(String baseUrl, String fingerprint, String token,
                             String method, String path, String body) throws Exception {
        NetworkPolicy.validateBaseUrl(baseUrl);
        String normalizedPin = NetworkPolicy.validateFingerprint(fingerprint);
        byte[] pin = new byte[32];
        for (int i = 0; i < pin.length; i++) pin[i] = (byte) Integer.parseInt(normalizedPin.substring(i * 2, i * 2 + 2), 16);
        // Intentional private-PC trust model: accept ONLY the exact user-confirmed certificate,
        // check its expiry, then preserve Android's separate SAN/IP hostname verification below.
        // This is not a trust-all manager and must not become an empty verification method.
        X509TrustManager pinned = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not supported");
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
                chain[0].checkValidity();
                try {
                    if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))) {
                        throw new CertificateException("Certificate does not match the explicitly paired PC fingerprint");
                    }
                } catch (java.security.GeneralSecurityException error) {
                    throw new CertificateException("Cannot verify the paired certificate", error);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {pinned}, new SecureRandom());
        HttpsURLConnection connection = (HttpsURLConnection) new URL(baseUrl + path).openConnection();
        connection.setSSLSocketFactory(context.getSocketFactory());
        // Keep Android's normal SAN/IP hostname verification; the explicit pin does not replace it.
        connection.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, image/jpeg");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if ("POST".equals(method)) {
                byte[] encoded = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
                if (encoded.length > MAX_BODY) throw new IllegalArgumentException(message("请求内容超过64KB", "Request exceeds 64 KB"));
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(encoded.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(encoded); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IllegalStateException(message("服务器重定向被安全策略拒绝", "Server redirect blocked"));
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_RESPONSE) throw new IllegalStateException(message("响应超过10MB限制", "Response exceeds 10 MB"));
            String contentType = connection.getContentType();
            byte[] response;
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) response = new byte[0];
            else try (InputStream stream = input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int length;
                while ((length = stream.read(block)) != -1) {
                    if (buffer.size() + length > MAX_RESPONSE) throw new IllegalStateException(message("响应超过10MB限制", "Response exceeds 10 MB"));
                    buffer.write(block, 0, length);
                }
                response = buffer.toByteArray();
            }
            return new Response(status, contentType == null ? "" : contentType, response);
        } finally { connection.disconnect(); }
    }
}
