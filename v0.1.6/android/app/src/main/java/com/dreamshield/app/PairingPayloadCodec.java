package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Input convenience only: host, certificate and one-time code validation remain mandatory. */
public final class PairingPayloadCodec {
    public static final int MAX_INPUT = 8192;
    private PairingPayloadCodec() { }

    public static String decode(String input) {
        if (input == null || input.length() > MAX_INPUT) throw invalid();
        String value = input.trim();
        if (value.startsWith("dreamshield:")) value = fromLink(value);
        if (value.startsWith("DS1:")) {
            String encoded = value.substring(4);
            if (!encoded.matches("[A-Za-z0-9_-]+={0,2}")) throw invalid();
            try {
                byte[] bytes = Base64.getUrlDecoder().decode(encoded);
                if (bytes.length > 4096) throw invalid();
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString().trim();
            } catch (Exception error) { throw invalid(); }
        }
        if (value.length() > 4096 || !value.startsWith("{") || !value.endsWith("}")) throw invalid();
        return value;
    }

    private static String fromLink(String link) {
        try {
            URI uri = new URI(link);
            String query = uri.getRawQuery();
            if (!"dreamshield".equals(uri.getScheme()) || !"pair".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    || query == null || !query.startsWith("payload=") || query.contains("&")) throw invalid();
            String value = URLDecoder.decode(query.substring(8), StandardCharsets.UTF_8.name());
            if (!value.startsWith("DS1:")) throw invalid();
            return value;
        } catch (Exception error) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(message("连接码无效，请完整复制电脑提供的DS1连接码或原配对JSON；过期请重新生成", "Copy the full DS1 code or pairing JSON. Generate a new code if it expires."));
    }
}
