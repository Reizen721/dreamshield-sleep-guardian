package com.dreamshield.app;
import com.google.protobuf.ByteString;
import espressif.NetworkConfig.*;
import espressif.NetworkCtrl.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlePolicyTest {
    @Test public void identityRequiresNewNoCodeFirmwareProtocol() {
        assertTrue(BleSetupPolicy.identity("freenove_esp32s3_n8r8", 2, "dreamshield-0123456789ab"));
        assertFalse(BleSetupPolicy.identity("freenove_esp32s3_n8r8", 1, "dreamshield-0123456789ab"));
        assertFalse(BleSetupPolicy.identity("senxorESP32S3", 2, "dreamshield-0123456789ab"));
    }
    @Test public void validatesUtf8WifiAndExplicitOpenNetworks() {
        BleSetupPolicy.wifi("卧室 WiFi", "test-password", false);
        BleSetupPolicy.wifi("open", "", true);
        BleSetupPolicy.wifi("A".repeat(32), "a".repeat(64), false);
        for (String ssid : new String[]{"", "中".repeat(11), "abc\0def"})
            assertThrows(IllegalArgumentException.class, () -> BleSetupPolicy.wifi(ssid, "test-password", false));
        for (String password : new String[]{"", "1234567", "中".repeat(22), "g".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> BleSetupPolicy.wifi("home", password, false));
        assertThrows(IllegalArgumentException.class, () -> BleSetupPolicy.wifi("home", "password", true));
    }
    @Test public void successRequiresSavedMatchingFreenoveIdentityWifiAndPrivateIp() {
        String board = "freenove_esp32s3_n8r8", id = "dreamshield-0123456789ab";
        assertTrue(BleSetupPolicy.confirmed(board, 2, id, id, "192.168.1.20", "home", "home", true, true, true));
        assertFalse(BleSetupPolicy.confirmed(board, 2, id, id, "192.168.1.20", "home", "home", true, false, true));
        assertFalse(BleSetupPolicy.confirmed(board, 2, id, "dreamshield-111111111111", "192.168.1.20", "home", "home", true, true, true));
        assertFalse(BleSetupPolicy.confirmed(board, 2, id, id, "192.168.1.20", "old", "home", true, true, true));
        assertFalse(BleSetupPolicy.confirmed("ir_board", 2, id, id, "192.168.1.20", "home", "home", true, true, true));
        for (String ip : new String[]{"0.0.0.0", "127.0.0.1", "8.8.8.8", "192.168.1.256", "192.168.1.0", "10.1.2.255"})
            assertFalse(BleSetupPolicy.privateIp(ip));
        assertTrue(BleSetupPolicy.privateIp("172.16.1.1"));
        assertTrue(BleSetupPolicy.privateIp("10.0.0.1"));
    }
    @Test public void protobufUsesWifiConfigAndResetNotErase() throws Exception {
        byte[] wire = NetworkConfigPayload.newBuilder().setMsg(NetworkConfigMsgType.TypeCmdSetWifiConfig)
            .setCmdSetWifiConfig(CmdSetWifiConfig.newBuilder().setSsid(ByteString.copyFromUtf8("卧室 WiFi"))
                .setPassphrase(ByteString.copyFromUtf8("synthetic-test"))).build().toByteArray();
        NetworkConfigPayload parsed = NetworkConfigPayload.parseFrom(wire);
        assertTrue(parsed.hasCmdSetWifiConfig()); assertFalse(parsed.hasCmdSetThreadConfig());
        assertEquals("卧室 WiFi", parsed.getCmdSetWifiConfig().getSsid().toStringUtf8());
        NetworkCtrlPayload reset = NetworkCtrlPayload.newBuilder().setMsg(NetworkCtrlMsgType.TypeCmdCtrlWifiReset)
            .setCmdCtrlWifiReset(CmdCtrlWifiReset.getDefaultInstance()).build();
        assertTrue(reset.hasCmdCtrlWifiReset()); assertFalse(reset.hasCmdCtrlWifiReprov());
        assertTrue(wire.length < 125);
    }
    @Test public void restoredNetworkRequiresLiveConnectionToSameKnownBoard() {
        assertTrue(BleSetupPolicy.restored("freenove_esp32s3_n8r8", 2, "dreamshield-0123456789ab", "dreamshield-0123456789ab", "192.168.1.167", "synthetic", "synthetic", true, true));
        assertFalse(BleSetupPolicy.restored("freenove_esp32s3_n8r8", 2, "dreamshield-0123456789ab", "dreamshield-0123456789ab", "0.0.0.0", "synthetic", "synthetic", true, true));
        assertFalse(BleSetupPolicy.restored("freenove_esp32s3_n8r8", 2, "dreamshield-0123456789ab", "dreamshield-0123456789ab", "192.168.1.167", "synthetic", "synthetic", true, false));
    }
    @Test public void restoredNetworkRejectsDifferentBoardAndNetwork() {
        assertFalse(BleSetupPolicy.restored("freenove_esp32s3_n8r8", 2, "dreamshield-0123456789ab", "dreamshield-000000000000", "192.168.1.167", "synthetic", "synthetic", true, true));
        assertFalse(BleSetupPolicy.restored("freenove_esp32s3_n8r8", 2, "dreamshield-0123456789ab", "dreamshield-0123456789ab", "192.168.1.167", "other", "synthetic", true, true));
    }

}
