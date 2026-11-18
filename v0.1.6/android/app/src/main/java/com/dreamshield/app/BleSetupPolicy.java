package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import java.nio.charset.StandardCharsets;

/** Platform-independent validation for the Freenove-only setup receipt. */
public final class BleSetupPolicy {
    private BleSetupPolicy() {}
    public static void wifi(String ssid, String password, boolean open) {
        int length = ssid.getBytes(StandardCharsets.UTF_8).length;
        if (length < 1 || length > 32 || ssid.indexOf('\0') >= 0) throw new IllegalArgumentException(message("Wi-Fi 名称需为 1–32 字节。", "Use a Wi-Fi name of 1–32 bytes."));
        int count = password.getBytes(StandardCharsets.UTF_8).length;
        if (open ? !password.isEmpty() : !((count >= 8 && count <= 63) || password.matches("[0-9A-Fa-f]{64}")))
            throw new IllegalArgumentException(message("Wi-Fi 密码需为 8–63 字节；无密码网络请明确勾选。", "Use 8–63 password bytes, or mark the network as open."));
    }
    public static boolean identity(String board, int protocol, String id) {
        return "freenove_esp32s3_n8r8".equals(board) && protocol == 2
            && id != null && id.matches("dreamshield-[0-9a-f]{12}");
    }
    public static boolean privateIp(String address) {
        if (address == null || !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] octets = address.split("\\."); int[] n = new int[4];
        for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(octets[i]); if (n[i] > 255) return false; }
        return n[3] > 0 && n[3] < 255 && (n[0] == 10 || n[0] == 192 && n[1] == 168 || n[0] == 172 && n[1] >= 16 && n[1] <= 31);
    }
    public static boolean confirmed(String board, int protocol, String id, String expectedId,
        String ip, String ssid, String expectedSsid, boolean configured, boolean committed, boolean connected) {
        return identity(board, protocol, id) && id.equals(expectedId) && privateIp(ip)
            && ssid != null && !ssid.isEmpty() && ssid.equals(expectedSsid) && configured && committed && connected;
    }
    public static boolean restored(String board, int protocol, String id, String expectedId,
        String ip, String ssid, String expectedSsid, boolean configured, boolean connected) {
        return confirmed(board, protocol, id, expectedId, ip, ssid, expectedSsid, configured, true, connected);
    }

}
