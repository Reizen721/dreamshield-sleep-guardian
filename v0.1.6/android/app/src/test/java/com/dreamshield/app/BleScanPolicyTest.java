package com.dreamshield.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class BleScanPolicyTest {
    @Test public void resumesWhenPermissionResultPrecedesActivityResume() {
        BleScanPolicy policy = new BleScanPolicy();
        policy.foreground(false); policy.accessResult(true);
        assertFalse(policy.consumeApprovedScan());
        policy.foreground(true);
        assertTrue(policy.consumeApprovedScan()); assertFalse(policy.consumeApprovedScan());
    }
    @Test public void resumesWhenActivityResumePrecedesPermissionResult() {
        BleScanPolicy policy = new BleScanPolicy();
        policy.foreground(false); policy.foreground(true);
        assertFalse(policy.consumeApprovedScan());
        policy.accessResult(true);
        assertTrue(policy.consumeApprovedScan()); assertFalse(policy.consumeApprovedScan());
    }
    @Test public void denialAndExplicitCancelDoNotStartScanningLater() {
        BleScanPolicy policy = new BleScanPolicy();
        policy.accessResult(false); assertFalse(policy.consumeApprovedScan());
        policy.foreground(false); policy.accessResult(true); policy.cancelPending();
        policy.foreground(true); assertFalse(policy.consumeApprovedScan());
    }
    @Test public void backgroundingBeforeQueuedScanPreservesApprovedRequest() {
        BleScanPolicy policy = new BleScanPolicy();
        policy.accessResult(true); policy.foreground(false);
        assertFalse(policy.consumeApprovedScan());
        policy.foreground(true); assertTrue(policy.consumeApprovedScan());
    }
    @Test public void recognizesNameOnlyAndServiceOnlyAdvertisements() {
        assertTrue(BleScanPolicy.isCandidate("DreamShield-FN-D606FC", false));
        assertTrue(BleScanPolicy.isCandidate("dreamshield-fn-d606fc", false));
        assertTrue(BleScanPolicy.isCandidate("", true));
        assertTrue(BleScanPolicy.isCandidate(null, true));
        assertFalse(BleScanPolicy.isCandidate("unrelated speaker", false));
        assertFalse(BleScanPolicy.isCandidate(null, false));
    }
    @Test public void prefersCurrentAdvertisementOverStaleCachedName() {
        assertEquals("DreamShield-FN-D606FC", BleScanPolicy.displayName("DreamShield-FN-D606FC", "old name", "44:B1:76:D6:06:FE"));
        assertEquals("cached", BleScanPolicy.displayName("", "cached", "44:B1:76:D6:06:FE"));
        assertEquals("Freenove · D6:06:FE", BleScanPolicy.displayName(null, null, "44:B1:76:D6:06:FE"));
    }
}
