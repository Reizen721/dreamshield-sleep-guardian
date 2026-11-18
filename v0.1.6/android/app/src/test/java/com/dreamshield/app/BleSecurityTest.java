package com.dreamshield.app;

import com.espressif.provisioning.security.Security1;
import com.google.crypto.tink.subtle.X25519;
import com.google.protobuf.ByteString;
import espressif.Constants;
import espressif.Sec1;
import espressif.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic independent peer exercises the actual Espressif handshake and shared CTR stream. */
public class BleSecurityTest {
    private static final String POP = "0123456789aBCDEF0123456789abcdef";
    private static byte[] frame(Sec1.Sec1Payload data) {
        return Session.SessionData.newBuilder().setSecVer(Session.SecSchemeVersion.SecScheme1).setSec1(data).build().toByteArray();
    }
    private static class Peer {
        final byte[] privateKey = X25519.generatePrivateKey();
        final byte[] publicKey = X25519.publicFromPrivate(privateKey);
        final byte[] nonce = new byte[16];
        final byte[] clientPublic;
        final Cipher stream;
        Peer(byte[] request, String pop) throws Exception {
            Session.SessionData parsed = Session.SessionData.parseFrom(request);
            assertEquals(Session.SecSchemeVersion.SecScheme1, parsed.getSecVer());
            assertEquals(Sec1.Sec1MsgType.Session_Command0, parsed.getSec1().getMsg());
            clientPublic = parsed.getSec1().getSc0().getClientPubkey().toByteArray();
            byte[] key = X25519.computeSharedSecret(privateKey, clientPublic);
            if (pop != null && !pop.isEmpty()) {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(pop.getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < key.length; i++) key[i] ^= hash[i];
            }
            Arrays.fill(nonce, (byte) 7);
            stream = Cipher.getInstance("AES/CTR/NoPadding");
            stream.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(nonce));
        }
        byte[] first() {
            return frame(Sec1.Sec1Payload.newBuilder().setMsg(Sec1.Sec1MsgType.Session_Response0)
                .setSr0(Sec1.SessionResp0.newBuilder().setStatus(Constants.Status.Success)
                    .setDevicePubkey(ByteString.copyFrom(publicKey)).setDeviceRandom(ByteString.copyFrom(nonce))).build());
        }
        byte[] last(byte[] request, boolean verifyClient) throws Exception {
            Session.SessionData parsed = Session.SessionData.parseFrom(request);
            assertEquals(Sec1.Sec1MsgType.Session_Command1, parsed.getSec1().getMsg());
            byte[] clientVerify = stream.update(parsed.getSec1().getSc1().getClientVerifyData().toByteArray());
            if (verifyClient) assertArrayEquals(publicKey, clientVerify);
            return frame(Sec1.Sec1Payload.newBuilder().setMsg(Sec1.Sec1MsgType.Session_Response1)
                .setSr1(Sec1.SessionResp1.newBuilder().setStatus(Constants.Status.Success)
                    .setDeviceVerifyData(ByteString.copyFrom(stream.update(clientPublic)))).build());
        }
    }
    @Test public void authenticatesAndKeepsBidirectionalStreamInSync() throws Exception {
        Security1 client = new Security1(POP);
        Peer peer = new Peer(client.getNextRequestInSession(null), POP);
        assertNull(client.getNextRequestInSession(peer.last(client.getNextRequestInSession(peer.first()), true)));
        for (int i = 0; i < 10; i++) {
            byte[] request = ("Wi-Fi 配置 " + i).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(request, peer.stream.update(client.encrypt(request)));
            byte[] response = ("saved " + i).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(response, client.decrypt(peer.stream.update(response)));
        }
        client.clear(); assertThrows(IllegalStateException.class, () -> client.encrypt(new byte[]{1}));
    }
    @Test public void noCodeSessionUsesUnmodifiedSharedSecretAndEncryptedExchange() throws Exception {
        for (String omitted : new String[]{null, ""}) {
            Security1 client = new Security1();
            Peer peer = new Peer(client.getNextRequestInSession(null), omitted);
            assertNull(client.getNextRequestInSession(peer.last(client.getNextRequestInSession(peer.first()), true)));
            byte[] wifi = "synthetic-wifi-password".getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = client.encrypt(wifi);
            assertFalse(Arrays.equals(wifi, encrypted));
            assertArrayEquals(wifi, peer.stream.update(encrypted));
            assertArrayEquals(new byte[]{1,2,3}, client.decrypt(peer.stream.update(new byte[]{1,2,3})));
        }
    }
    @Test public void wrongProofFailsClosed() throws Exception {
        Security1 client = new Security1(POP.toLowerCase());
        Peer peer = new Peer(client.getNextRequestInSession(null), POP);
        byte[] last = peer.last(client.getNextRequestInSession(peer.first()), false);
        assertThrows(IllegalStateException.class, () -> client.getNextRequestInSession(last));
        assertThrows(IllegalStateException.class, () -> client.encrypt(new byte[]{1}));
    }
    @Test public void malformedResponsesCannotCompleteHandshake() {
        byte[][] bad = {new byte[0], new byte[]{(byte)255}, new byte[1025],
            Session.SessionData.newBuilder().setSecVer(Session.SecSchemeVersion.SecScheme0).build().toByteArray(),
            frame(Sec1.Sec1Payload.newBuilder().setMsg(Sec1.Sec1MsgType.Session_Response0)
                .setSr0(Sec1.SessionResp0.newBuilder().setDevicePubkey(ByteString.copyFrom(new byte[31]))
                    .setDeviceRandom(ByteString.copyFrom(new byte[16]))).build())};
        for (byte[] response : bad) {
            Security1 client = new Security1(POP); client.getNextRequestInSession(null);
            assertThrows(IllegalStateException.class, () -> client.getNextRequestInSession(response));
            assertThrows(IllegalStateException.class, () -> client.decrypt(new byte[]{1}));
        }
    }
    @Test public void rejectsNegativeDeviceStatus() throws Exception {
        Security1 client = new Security1(POP); Peer peer = new Peer(client.getNextRequestInSession(null), POP);
        Session.SessionData first = Session.SessionData.parseFrom(peer.first());
        byte[] rejected = frame(first.getSec1().toBuilder().setSr0(first.getSec1().getSr0().toBuilder().setStatusValue(1)).build());
        assertThrows(IllegalStateException.class, () -> client.getNextRequestInSession(rejected));
    }
}
