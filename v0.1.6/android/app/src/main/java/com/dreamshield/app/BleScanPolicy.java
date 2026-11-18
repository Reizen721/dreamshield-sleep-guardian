package com.dreamshield.app;

/** Scan-response packets may expose name and service separately; device identity is checked after connection. */
public final class BleScanPolicy {
    private boolean foreground = true;
    private boolean approvedScanPending;

    public void foreground(boolean value) { foreground = value; }
    public void accessResult(boolean granted) { approvedScanPending = granted; }
    public void cancelPending() { approvedScanPending = false; }
    public boolean consumeApprovedScan() {
        if (!foreground || !approvedScanPending) return false;
        approvedScanPending = false;
        return true;
    }
    public static boolean isCandidate(String name, boolean hasProvisioningService) {
        String prefix = "DreamShield-FN-";
        return hasProvisioningService || name != null && name.trim().regionMatches(true, 0, prefix, 0, prefix.length());
    }
    public static String displayName(String advertised, String cached, String address) {
        if (advertised != null && !advertised.trim().isEmpty()) return advertised.trim();
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();
        String suffix = address == null ? "" : address.substring(Math.max(0, address.length() - 8));
        return "Freenove · " + suffix;
    }
}
