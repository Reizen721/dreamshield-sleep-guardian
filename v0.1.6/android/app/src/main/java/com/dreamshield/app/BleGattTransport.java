package com.dreamshield.app;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** One serialized write/read exchange at a time; callbacks from closed GATTs are ignored. */
@SuppressLint("MissingPermission")
public final class BleGattTransport {
    public static final UUID SERVICE = UUID.fromString("021a9004-0382-4aea-bff4-6b3f1c5adfb4");
    private static final UUID DESCRIPTION = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothGattCharacteristic> endpoints = new HashMap<>();
    private final ArrayDeque<BluetoothGattCharacteristic> descriptions = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic pending;
    private Consumer<byte[]> reply;
    private Runnable ready, failed;
    private int mtu = 23;
    private boolean reading;
    private final Runnable timeout = this::fail;

    public void connect(Context context, BluetoothDevice device, Runnable onReady, Runnable onFailure) {
        close(); ready = onReady; failed = onFailure;
        try {
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            arm(25_000);
            if (gatt == null) fail();
        } catch (Exception ignored) { fail(); }
    }
    private void arm(long milliseconds) { main.removeCallbacks(timeout); main.postDelayed(timeout, milliseconds); }
    private void accept(BluetoothGatt source, Runnable work) {
        main.post(() -> { if (source == gatt) { try { work.run(); } catch (Exception ignored) { fail(); } } });
    }
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
            accept(source, () -> {
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) { fail(); return; }
                if (state == BluetoothProfile.STATE_CONNECTED && !source.requestMtu(512)) fail();
            });
        }
        @Override public void onMtuChanged(BluetoothGatt source, int value, int status) {
            accept(source, () -> {
                if (status != BluetoothGatt.GATT_SUCCESS || value < 128) { fail(); return; }
                mtu = value;
                if (!source.discoverServices()) fail();
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            accept(source, () -> {
                BluetoothGattService service = source.getService(SERVICE);
                if (status != BluetoothGatt.GATT_SUCCESS || service == null) { fail(); return; }
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics())
                    if (characteristic.getDescriptor(DESCRIPTION) != null) descriptions.add(characteristic);
                nextDescription();
            });
        }
        @Override public void onDescriptorRead(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status, byte[] value) {
            descriptorRead(source, descriptor, status, value.clone());
        }
        @Override @SuppressWarnings("deprecation") public void onDescriptorRead(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            if (Build.VERSION.SDK_INT < 33) descriptorRead(source, descriptor, status,
                descriptor.getValue() == null ? new byte[0] : descriptor.getValue().clone());
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            accept(source, () -> {
                if (pending != characteristic || reply == null || reading) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(); return; }
                reading = true;
                main.postDelayed(() -> {
                    try {
                        if (source == gatt && pending == characteristic && reply != null && !source.readCharacteristic(characteristic)) fail();
                    } catch (Exception ignored) { fail(); }
                }, 60);
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            characteristicRead(source, characteristic, status, value.clone());
        }
        @Override @SuppressWarnings("deprecation") public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            if (Build.VERSION.SDK_INT < 33) characteristicRead(source, characteristic, status,
                characteristic.getValue() == null ? new byte[0] : characteristic.getValue().clone());
        }
    };
    private void descriptorRead(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status, byte[] value) {
        accept(source, () -> {
            if (status != BluetoothGatt.GATT_SUCCESS || !DESCRIPTION.equals(descriptor.getUuid()) || value.length > 64) { fail(); return; }
            String name = new String(value, StandardCharsets.UTF_8).replace("\0", "").trim();
            if (endpoints.put(name, descriptor.getCharacteristic()) != null) { fail(); return; }
            nextDescription();
        });
    }
    private void nextDescription() {
        if (descriptions.isEmpty()) {
            if (!endpoints.keySet().containsAll(Arrays.asList("prov-session", "prov-config", "prov-scan", "prov-ctrl", "dreamshield-info"))) { fail(); return; }
            main.removeCallbacks(timeout); Runnable callback = ready; ready = null;
            if (callback != null) callback.run();
        } else if (!gatt.readDescriptor(descriptions.remove().getDescriptor(DESCRIPTION))) fail();
    }
    public void exchange(String endpoint, byte[] request, Consumer<byte[]> callback) {
        if (gatt == null || reply != null || request == null || request.length == 0 || request.length > mtu - 3) { fail(); return; }
        BluetoothGattCharacteristic characteristic = endpoints.get(endpoint);
        if (characteristic == null) { fail(); return; }
        reply = callback; pending = characteristic; reading = false; arm(12_000);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (gatt.writeCharacteristic(characteristic, request, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothStatusCodes.SUCCESS) fail();
            } else writeLegacy(characteristic, request);
        } catch (Exception ignored) { fail(); }
    }
    @SuppressWarnings("deprecation") private void writeLegacy(BluetoothGattCharacteristic characteristic, byte[] value) {
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); characteristic.setValue(value);
        if (!gatt.writeCharacteristic(characteristic)) fail();
    }
    private void characteristicRead(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status, byte[] value) {
        accept(source, () -> {
            if (pending != characteristic || reply == null || !reading) return;
            if (status != BluetoothGatt.GATT_SUCCESS || value.length == 0 || value.length > 4096) { fail(); return; }
            main.removeCallbacks(timeout); Consumer<byte[]> callback = reply;
            reply = null; pending = null; reading = false; callback.accept(value);
        });
    }
    private void fail() { Runnable callback = failed; close(); if (callback != null) callback.run(); }
    public void close() {
        main.removeCallbacksAndMessages(null);
        BluetoothGatt old = gatt; gatt = null; ready = null; failed = null; reply = null; pending = null;
        descriptions.clear(); endpoints.clear(); mtu = 23; reading = false;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
    }
}
