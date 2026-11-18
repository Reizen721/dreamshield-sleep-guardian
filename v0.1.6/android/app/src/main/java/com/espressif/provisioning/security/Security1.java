// Copyright 2020 Espressif Systems (Shanghai) PTE LTD
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by law or agreed in writing, software is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
// DreamShield modifications (2026): fail-closed parsing, bounded frames,
// explicit UTF-8, constant-time verification, and transient key cleanup.
package com.espressif.provisioning.security;

import com.google.crypto.tink.subtle.X25519;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import espressif.Constants;
import espressif.Sec1;
import espressif.Session;

/** Espressif Security 1: X25519 + optional PoP + one shared AES-CTR stream. */
public final class Security1 implements Security {
    private int state;
    private byte[] privateKey, publicKey, pop;
    private Cipher cipher;

    public Security1() { this(null); }
    public Security1(String proofOfPossession) {
        pop = proofOfPossession == null ? new byte[0] : proofOfPossession.getBytes(StandardCharsets.UTF_8);
    }
    @Override public byte[] getNextRequestInSession(byte[] data) {
        try {
            if (state == 0) {
                if (data != null) throw new IllegalStateException("Unexpected handshake data");
                privateKey = X25519.generatePrivateKey();
                publicKey = X25519.publicFromPrivate(privateKey);
                state = 1;
                return frame(Sec1.Sec1Payload.newBuilder().setMsg(Sec1.Sec1MsgType.Session_Command0)
                    .setSc0(Sec1.SessionCmd0.newBuilder().setClientPubkey(ByteString.copyFrom(publicKey))).build());
            }
            Session.SessionData response = parse(data);
            if (state == 1) {
                Sec1.Sec1Payload payload = response.getSec1();
                if (payload.getMsg() != Sec1.Sec1MsgType.Session_Response0 || !payload.hasSr0()
                    || payload.getSr0().getStatus() != Constants.Status.Success) throw new IllegalStateException("Handshake rejected");
                byte[] peer = payload.getSr0().getDevicePubkey().toByteArray();
                byte[] nonce = payload.getSr0().getDeviceRandom().toByteArray();
                if (peer.length != 32 || nonce.length != 16) throw new IllegalStateException("Invalid handshake keys");
                byte[] shared = X25519.computeSharedSecret(privateKey, peer);
                byte[] digest = pop.length == 0 ? new byte[0] : MessageDigest.getInstance("SHA-256").digest(pop);
                for (int i = 0; i < digest.length; i++) shared[i] ^= digest[i];
                cipher = Cipher.getInstance("AES/CTR/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(shared, "AES"), new IvParameterSpec(nonce));
                Arrays.fill(shared, (byte) 0); Arrays.fill(digest, (byte) 0);
                byte[] verify = cipher.update(peer);
                state = 2;
                return frame(Sec1.Sec1Payload.newBuilder().setMsg(Sec1.Sec1MsgType.Session_Command1)
                    .setSc1(Sec1.SessionCmd1.newBuilder().setClientVerifyData(ByteString.copyFrom(verify))).build());
            }
            if (state != 2 || response.getSec1().getMsg() != Sec1.Sec1MsgType.Session_Response1
                || !response.getSec1().hasSr1() || response.getSec1().getSr1().getStatus() != Constants.Status.Success)
                throw new IllegalStateException("Invalid handshake state");
            byte[] verify = response.getSec1().getSr1().getDeviceVerifyData().toByteArray();
            if (verify.length != 32 || !MessageDigest.isEqual(publicKey, cipher.update(verify)))
                throw new IllegalStateException("Session verification failed");
            state = 3;
            wipe(privateKey); privateKey = null; wipe(pop); pop = null;
            return null;
        } catch (Exception failure) {
            clear();
            throw new IllegalStateException("Secure session failed");
        }
    }
    private static Session.SessionData parse(byte[] data) throws Exception {
        if (data == null || data.length == 0 || data.length > 1024) throw new IllegalArgumentException("Invalid frame");
        Session.SessionData message = Session.SessionData.parseFrom(data);
        if (message.getSecVer() != Session.SecSchemeVersion.SecScheme1 || !message.hasSec1())
            throw new IllegalArgumentException("Wrong security scheme");
        return message;
    }
    private static byte[] frame(Sec1.Sec1Payload data) {
        return Session.SessionData.newBuilder().setSecVer(Session.SecSchemeVersion.SecScheme1).setSec1(data).build().toByteArray();
    }
    @Override public byte[] encrypt(byte[] data) { return transform(data); }
    @Override public byte[] decrypt(byte[] data) { return transform(data); }
    private byte[] transform(byte[] data) {
        if (state != 3 || cipher == null || data == null || data.length == 0 || data.length > 4096)
            throw new IllegalStateException("Established session required");
        return cipher.update(data);
    }
    public void clear() {
        state = -1; cipher = null;
        wipe(privateKey); wipe(publicKey); wipe(pop);
        privateKey = null; publicKey = null; pop = null;
    }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
