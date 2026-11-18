package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** First-run state belongs to the pinned computer, never to a short-lived token. */
public final class SetupPolicy {
    public static final int VERSION = 1;
    private SetupPolicy() { }

    public static String connectionKey(String baseUrl, String fingerprint) {
        String endpoint = NetworkPolicy.validateBaseUrl(baseUrl);
        String certificate = NetworkPolicy.validateFingerprint(fingerprint);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((endpoint + "\n" + certificate).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte value : bytes) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(message("无法建立连接标识", "Could not create a connection ID"), impossible);
        }
    }

    public static boolean isComplete(int version, String savedBinding, String currentBinding,
                                     boolean paired, boolean knownModel) {
        return version == VERSION && paired && knownModel && currentBinding != null
                && currentBinding.matches("[0-9a-f]{64}") && currentBinding.equals(savedBinding);
    }

    public static int resumedStep(int savedStep, boolean paired, boolean knownModel) {
        if (!paired || savedStep < 0 || savedStep > 2) return 0;
        return savedStep == 2 && !knownModel ? 1 : savedStep;
    }
}
