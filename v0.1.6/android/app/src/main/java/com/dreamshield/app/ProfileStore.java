package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The bearer token and certificate pin never leave native private storage. */
final class ProfileStore {
    private static final String ALIAS = "DreamShieldProfileV1";
    private static ProfileStore instance;
    private final SharedPreferences preferences;

    static synchronized ProfileStore forApplication(Context context) {
        if (instance == null) instance = new ProfileStore(context.getApplicationContext());
        return instance;
    }

    private ProfileStore(Context context) {
        preferences = context.getSharedPreferences("paired_server_v1", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    synchronized JSONObject load() throws Exception {
        String ciphertext = preferences.getString("ciphertext", null);
        String iv = preferences.getString("iv", null);
        if (ciphertext == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8));
        } catch (Exception error) {
            // A restored/invalid keystore must fail closed rather than use a plaintext fallback.
            // Runs on the bounded worker executor. Durable cleanup must finish before reporting failure.
            if (!preferences.edit().clear().commit()) {
                throw new IllegalStateException(message("失效配对凭据无法清除，请清除应用数据后重新配对", "Could not clear expired credentials. Clear app data and pair again."), error);
            }
            throw new IllegalStateException(message("本机配对凭据无法解密，请重新配对电脑", "Could not read credentials. Pair the PC again."), error);
        }
    }

    synchronized void save(JSONObject profile) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(profile.toString().getBytes(StandardCharsets.UTF_8));
        if (!preferences.edit().putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit()) {
            throw new IllegalStateException(message("无法保存配对凭据", "Could not save pairing credentials"));
        }
    }

    /** A late 401 from an earlier Activity or pairing must never erase the new grant. */
    synchronized boolean clearRevoked(JSONObject rejected) throws Exception {
        JSONObject current = load();
        if (current == null || !current.getString("token").equals(rejected.getString("token"))
                || !current.getString("base_url").equals(rejected.getString("base_url"))
                || !current.getString("certificate_sha256").equals(rejected.getString("certificate_sha256"))) return false;
        clear();
        return true;
    }

    synchronized void clear() throws Exception {
        // Keep synchronous persistence: forget must finish before deleting its Keystore encryption key.
        if (!preferences.edit().clear().commit()) throw new IllegalStateException(message("无法清除本机配对凭据，请重试", "Could not clear pairing. Try again."));
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
}
