package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Pure Java allowlist shared by networking and the offline security test. */
public final class NetworkPolicy {
    private static Set<String> strings(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
    private static final Set<String> HISTORY = strings("/api/captures", "/api/environment/history", "/api/automatic-ai/history");
    private static final Set<String> QUERY = strings("limit", "since", "from", "to", "source");
    private static final Set<String> GET = strings("/api/environment/current", "/api/app/status", "/api/device/status", "/api/device/snapshot",
            "/api/captures", "/api/environment/history", "/api/automatic-ai/history", "/api/policy-settings");
    private static final Set<String> POST = strings("/api/config", "/api/capture", "/api/automatic-ai",
            "/api/policy-settings", "/api/sensor-settings");
    private NetworkPolicy() { }

    public static String validateBaseUrl(String input) {
        if (input == null || input.length() > 120) throw new IllegalArgumentException(message("电脑地址不合法", "Invalid PC address"));
        try {
            URI url = new URI(input.trim());
            if (!"https".equals(url.getScheme()) || url.getRawUserInfo() != null
                    || url.getRawQuery() != null || url.getRawFragment() != null
                    || !(url.getRawPath() == null || url.getRawPath().isEmpty())
                    || url.getPort() < 8743 || url.getPort() > 65535
                    || !isPrivateIpv4(url.getHost())) {
                throw new IllegalArgumentException(message("只允许 https://家庭局域网IPv4:端口（8743–65535）", "Use https://local-IPv4:port (8743–65535)."));
            }
            return "https://" + url.getHost() + ":" + url.getPort();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(message("电脑地址不合法", "Invalid PC address"), error);
        }
    }

    public static boolean isPrivateIpv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 1 && parts[i].charAt(0) == '0') return false;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return false;
        }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    public static String validateFingerprint(String input) {
        if (input == null || !input.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException(message("证书指纹必须是64位SHA-256十六进制文本", "Use a 64-character SHA-256 hex fingerprint."));
        }
        return input.toLowerCase(Locale.ROOT);
    }

    public static String validatePath(String method, String input) {
        if (input == null || input.length() > 1024 || !input.startsWith("/")
                || input.startsWith("//") || input.contains("%") || input.contains("\\")
                || input.contains("#") || input.contains("..") || input.contains("\r")
                || input.contains("\n") || input.contains("\u0000")) {
            throw new IllegalArgumentException(message("请求路径不在允许范围内", "Request path not allowed"));
        }
        String[] pieces = input.split("\\?", -1);
        if (pieces.length > 2) throw new IllegalArgumentException(message("查询参数不合法", "Invalid query parameters"));
        String path = pieces[0];
        boolean history = HISTORY.contains(path);
        if (pieces.length == 2) {
            if (!"GET".equals(method) || !history || pieces[1].isEmpty()) throw new IllegalArgumentException(message("此接口不允许查询参数", "This endpoint accepts no query parameters."));
            Set<String> seen = new java.util.HashSet<>();
            for (String part : pieces[1].split("&", -1)) {
                String[] pair = part.split("=", -1);
                if (pair.length != 2 || !QUERY.contains(pair[0])
                        || !seen.add(pair[0]) || !pair[1].matches("[A-Za-z0-9_.:TZ-]{1,80}")) {
                    throw new IllegalArgumentException(message("查询参数不在允许范围内", "Query parameters out of range"));
                }
            }
        }
        boolean allowed = false;
        if ("GET".equals(method)) {
            allowed = GET.contains(path)
                    || path.matches("/captures/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[A-Za-z0-9_-]+\\.(jpg|json)")
                    || path.matches("/automatic-ai-results/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[A-Za-z0-9_-]+\\.json");
        } else if ("POST".equals(method)) {
            allowed = POST.contains(path);
        }
        if (!allowed) throw new IllegalArgumentException(message("此接口未开放给手机", "Endpoint unavailable to the app"));
        return input;
    }

    public static String validateImagePath(String path) {
        validatePath("GET", path);
        if (!"/api/device/snapshot".equals(path) && !path.matches("/captures/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[A-Za-z0-9_-]+\\.jpg")) {
            throw new IllegalArgumentException(message("只能保存设备抓拍或已保存的JPEG图片", "Choose a capture or saved JPEG."));
        }
        return path;
    }
}
