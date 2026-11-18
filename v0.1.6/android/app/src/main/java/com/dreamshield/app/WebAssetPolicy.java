package com.dreamshield.app;

import java.net.URI;
import java.net.URISyntaxException;

/** The native bridge is available only to this packaged, network-isolated application. */
public final class WebAssetPolicy {
    private static final String HOST = "appassets.androidplatform.net";
    public static final String ENTRY = "https://" + HOST + "/mobile/index.html";

    private WebAssetPolicy() { }

    public static boolean allowsNavigation(String method, String url, boolean mainFrame) {
        return mainFrame && "GET".equals(method) && ENTRY.equals(url);
    }

    /** Returns an AssetManager-relative path, or null for any untrusted request. */
    public static String assetPath(String method, String input, boolean mainFrame) {
        if (!"GET".equals(method) || input == null || input.length() > 2048
                || (mainFrame && !ENTRY.equals(input))) return null;
        try {
            URI uri = new URI(input);
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getRawAuthority())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || !path.matches("/mobile/[A-Za-z0-9_./-]+")
                    || path.contains("..") || path.contains("//")) return null;
            return path.substring(1);
        } catch (URISyntaxException error) { return null; }
    }
}
