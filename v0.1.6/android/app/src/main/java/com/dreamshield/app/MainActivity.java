package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.text;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends androidx.activity.ComponentActivity {
    private static final String LOCAL_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int PERMISSION_REQUEST = 101;
    private static final int BLE_PERMISSION_REQUEST = 102;
    private static final int BLE_ENABLE_REQUEST = 103;
    private NativeState nativeState;
    private final Map<String, java.util.function.Consumer<JSONObject>> nativeCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private ProfileStore store;
    private volatile boolean destroyed;
    private AlertDialog pairingDialog;
    private String permissionRequestId;
    private String pendingPairingInput;
    private final PreviewCache previews = new PreviewCache();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.activityContext(base));
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        androidx.core.splashscreen.SplashScreen splash = androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        AppLanguage.INSTANCE.refresh(getApplicationContext());
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        store = ProfileStore.forApplication(this);
        acceptPairingIntent(getIntent());
        nativeState = NativeUiHost.install(this);
        // Rotation and other activity recreation should not replay a cold-launch curtain.
        if (savedInstanceState != null) nativeState.setLaunchVisible(false);
        nativeState.start();
        if (savedInstanceState != null) {
            nativeState.restoreDestination(savedInstanceState.getString("native_page", "home"),
                    savedInstanceState.getBoolean("setup_deferred"));
        }
        handleSleepIntent(getIntent());
        splash.setOnExitAnimationListener(provider -> {
            if (destroyed || nativeState == null || !nativeState.getLaunchVisible()) { provider.remove(); return; }
            View icon = provider.getIconView();
            int[] iconLocation = new int[2];
            int[] rootLocation = new int[2];
            icon.getLocationInWindow(iconLocation);
            getWindow().getDecorView().getLocationInWindow(rootLocation);
            nativeState.beginLaunchReveal(iconLocation[0] - rootLocation[0] + icon.getWidth() / 2f,
                    iconLocation[1] - rootLocation[1] + icon.getHeight() / 2f,
                    Math.min(icon.getWidth(), icon.getHeight()) * .22f);
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
                nativeState.setLaunchVisible(false); provider.remove(); return;
            }
            provider.getView().animate().alpha(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(provider::remove).start();
        });
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (nativeState != null) {
            outState.putString("native_page", nativeState.getPage());
            outState.putBoolean("setup_deferred", nativeState.isSetupDeferred());
        }
    }

    /** In-process commands only. Responses never enter a WebView or JavaScript runtime. */
    public void requestNative(String raw, java.util.function.Consumer<JSONObject> callback) {
        try {
            JSONObject message = new JSONObject(raw);
            String id = message.getString("request_id");
            if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException(text(R.string.copy_invalid_request_id));
            if (nativeCallbacks.putIfAbsent(id, callback) != null) throw new IllegalArgumentException(text(R.string.copy_duplicate_request_id));
            new NativeCommands().request(raw);
        } catch (Exception error) {
            try { callback.accept(new JSONObject().put("ok", false).put("status", 0).put("error", readableError(error))); }
            catch (Exception ignored) { }
        }
    }

    private void acceptPairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        try {
            String json = PairingPayloadCodec.decode(intent.getData().toString());
            synchronized (this) { pendingPairingInput = json; }
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, text(R.string.copy_invalid_link_generate_a_new_code_on_the_pc), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleSleepIntent(intent)) {
            acceptPairingIntent(intent);
            if (nativeState != null && intent.getData() != null) nativeState.onPairingLink();
        }
    }
    private boolean handleSleepIntent(Intent intent) {
        if (intent == null || nativeState == null) return false;
        String value = intent.getStringExtra("dreamshield_action");
        if (value == null && "sleep".equals(intent.getStringExtra("dreamshield_destination"))) value = "open_sleep";
        if (!"open_sleep".equals(value) && !"start_tonight".equals(value)) return false;
        intent.removeExtra("dreamshield_action");
        intent.removeExtra("dreamshield_destination");
        nativeState.onSleepAction(value);
        return true;
    }

    private boolean permissionGranted() {
        return Build.VERSION.SDK_INT < 37 || checkSelfPermission(LOCAL_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requirePermission() {
        if (!permissionGranted()) throw new IllegalStateException(text(R.string.copy_allow_local_network_access_before_pairing));
    }

    private JSONObject publicProfile() throws Exception {
        JSONObject saved = store.load();
        // The pinned computer owns authorization lifetime. Keep an existing grant
        // across restarts and old local timestamps; an explicit server 401 still
        // blocks it. Generating a new invitation never replaces this token.
        boolean paired = saved != null;
        JSONObject result = new JSONObject().put("paired", paired).put("permission_granted", permissionGranted());
        if (saved != null) {
            result.put("base_url", saved.getString("base_url")).put("device_name", saved.optString("device_name"))
                    .put("expires_at", saved.optString("expires_at", "")).put("persistent", true)
                    .put("server_binding", SetupPolicy.connectionKey(saved.getString("base_url"), saved.getString("certificate_sha256")));
        }
        result.put("app_version", BuildConfig.VERSION_NAME).put("app_version_code", BuildConfig.VERSION_CODE)
                .put("debug_build", BuildConfig.DEBUG);
        synchronized (this) {
            if (pendingPairingInput != null) {
                result.put("pending_pairing_input", pendingPairingInput);
                pendingPairingInput = null;
            }
        }
        return result;
    }

    private JSONObject requireProfile() throws Exception {
        requirePermission();
        JSONObject profile = store.load();
        if (profile == null) {
            throw new IllegalStateException(text(R.string.copy_get_a_pairing_code_from_the_pc_console));
        }
        return profile;
    }

    private void runTask(String requestId, Runnable task) {
        try { executor.execute(task); }
        catch (RejectedExecutionException error) { fail(requestId, text(R.string.copy_too_many_requests_try_again_shortly)); }
    }

    private final class NativeCommands {
        public void request(String raw) {
            if (destroyed) return;
            String id = "";
            try {
                if (raw == null || raw.length() > 100000) throw new IllegalArgumentException(text(R.string.copy_invalid_request_size));
                JSONObject request = new JSONObject(raw);
                id = request.getString("request_id");
                if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException(text(R.string.copy_invalid_request_id));
                String action = request.getString("action");
                final String requestId = id;
                if ("permission".equals(action)) { runOnUiThread(() -> requestPermission(requestId)); return; }
                if ("theme".equals(action)) {
                    runOnUiThread(() -> {
                        try { reply(requestId, 200, MaterialTheme.current(MainActivity.this)); }
                        catch (Exception error) { fail(requestId, readableError(error)); }
                    });
                    return;
                }
                final long generation;
                synchronized (previews) {
                    if ("forget".equals(action) || "pair".equals(action)) previews.invalidate();
                    generation = previews.generation();
                }
                runTask(requestId, () -> {
                    try {
                        previews.require(generation);
                        switch (action) {
                            case "profile": reply(requestId, 200, publicProfile(), generation); break;
                            case "forget":
                                synchronized (previews) { previews.require(generation); store.clear(); }
                                reply(requestId, 200, publicProfile(), generation); break;
                            case "pair": preparePair(requestId, request.getString("pairing_payload"), generation); break;
                            case "request": apiRequest(requestId, request, generation); break;
                            case "save_image": saveImage(requestId, request.getString("path"), request.optString("image_id", ""), generation); break;
                            default: throw new IllegalArgumentException(text(R.string.copy_unknown_phone_action));
                        }
                    } catch (Exception error) { fail(requestId, readableError(error)); }
                });
            } catch (Exception error) { fail(id, readableError(error)); }
        }
    }

    private void requestPermission(String requestId) {
        try {
            if (destroyed || isFinishing()) return;
            if (permissionGranted()) { reply(requestId, 200, new JSONObject().put("permission_granted", true)); return; }
            if (permissionRequestId != null) { fail(requestId, text(R.string.copy_permission_request_in_progress)); return; }
            permissionRequestId = requestId;
            requestPermissions(new String[] {LOCAL_PERMISSION}, PERMISSION_REQUEST);
        } catch (Exception error) { permissionRequestId = null; fail(requestId, readableError(error)); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLE_PERMISSION_REQUEST && nativeState != null) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            nativeState.onBleAccessResult(granted);
        }
        if (requestCode == PERMISSION_REQUEST && permissionRequestId != null) {
            String id = permissionRequestId; permissionRequestId = null;
            try { reply(id, 200, new JSONObject().put("permission_granted", permissionGranted())); }
            catch (Exception error) { fail(id, readableError(error)); }
        }
    }

    /** System-owned nearby-device and radio prompts, initiated by a visible user action. */
    @android.annotation.SuppressLint("MissingPermission")
    public boolean ensureBleAccess() {
        String[] required = Build.VERSION.SDK_INT >= 31
                ? new String[] {android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : required) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(required, BLE_PERMISSION_REQUEST); return false;
            }
        }
        android.bluetooth.BluetoothManager manager = getSystemService(android.bluetooth.BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) return true;
        if (!manager.getAdapter().isEnabled()) {
            startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), BLE_ENABLE_REQUEST);
            return false;
        }
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BLE_ENABLE_REQUEST && nativeState != null) nativeState.onBleAccessResult(resultCode == RESULT_OK);
    }

    private void preparePair(String id, String rawPayload, long generation) throws Exception {
        requirePermission();
        JSONObject payload = new JSONObject(PairingPayloadCodec.decode(rawPayload));
        if (payload.getInt("version") != 1) throw new IllegalArgumentException(text(R.string.copy_unsupported_pairing_version));
        String baseUrl = NetworkPolicy.validateBaseUrl(payload.getString("base_url"));
        String fingerprint = NetworkPolicy.validateFingerprint(payload.getString("certificate_sha256"));
        String code = payload.getString("pairing_code");
        if (!code.matches("[A-Za-z0-9_-]{6,128}")) throw new IllegalArgumentException(text(R.string.copy_invalid_pairing_code));
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) { fail(id, text(R.string.copy_pairing_canceled)); return; }
            if (!previews.isCurrent(generation)) { fail(id, text(R.string.copy_pairing_changed_this_request_was_canceled)); return; }
            if (pairingDialog != null) { fail(id, text(R.string.copy_finish_or_cancel_the_open_pairing_confirmation)); return; }
            pairingDialog = new AlertDialog.Builder(MainActivity.this).setTitle(text(R.string.copy_confirm_pc_pairing))
                    .setMessage(text(R.string.copy_pc_address_n) + baseUrl + text(R.string.copy_n_nsha_256_fingerprint_n) + fingerprint
                            + text(R.string.copy_n_nmatch_the_fingerprint_on_your_pc_pair_only_with))
                    .setPositiveButton(text(R.string.copy_match_confirmed_pair), (dialog, which) -> runTask(id, () -> {
                        try {
                            requirePermission();
                            previews.require(generation);
                            String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
                            JSONObject body = new JSONObject().put("pairing_code", code).put("device_name", deviceName);
                            PinnedClient.Response response = PinnedClient.exchange(baseUrl, fingerprint, null,
                                    "POST", "/api/mobile/pair", body.toString());
                            JSONObject paired = new JSONObject(response.text());
                            if (response.status != 200 || !paired.optBoolean("ok", false)) {
                                throw new IllegalStateException(paired.optString("error", text(R.string.copy_pairing_failed_get_a_new_code_from_the_pc)));
                            }
                            String token = paired.getString("token");
                            if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalStateException(text(R.string.copy_invalid_credentials_from_the_pc));
                            String expires = paired.getString("expires_at");
                            if (!Instant.parse(expires).isAfter(Instant.now())) throw new IllegalStateException(text(R.string.copy_the_pc_returned_expired_credentials));
                            JSONObject saved = new JSONObject().put("base_url", baseUrl).put("certificate_sha256", fingerprint)
                                    .put("token", token).put("expires_at", expires).put("device_name", paired.optString("device_name", deviceName));
                            long pairedGeneration;
                            synchronized (previews) {
                                previews.require(generation);
                                store.save(saved);
                                previews.invalidate();
                                pairedGeneration = previews.generation();
                            }
                            reply(id, 200, publicProfile(), pairedGeneration);
                        } catch (Exception error) { fail(id, readableError(error)); }
                    }))
                    .setNegativeButton(text(R.string.copy_cancel), (dialog, which) -> fail(id, text(R.string.copy_pairing_canceled_81)))
                    .setOnCancelListener(dialog -> fail(id, text(R.string.copy_pairing_canceled_81))).create();
            pairingDialog.setOnDismissListener(dialog -> pairingDialog = null);
            pairingDialog.show();
        });
    }

    private PinnedClient.Response exchange(JSONObject profile, String method, String path, String body) throws Exception {
        return PinnedClient.exchange(profile.getString("base_url"), profile.getString("certificate_sha256"),
                profile.getString("token"), method, path, body);
    }

    private void apiRequest(String id, JSONObject request, long generation) throws Exception {
        JSONObject profile = requireProfile();
        String method = request.optString("method", "GET");
        String path = NetworkPolicy.validatePath(method, request.getString("path"));
        JSONObject body = request.optJSONObject("body");
        if (request.has("body") && body == null) throw new IllegalArgumentException(text(R.string.copy_the_request_body_must_be_a_json_object));
        previews.require(generation);
        PinnedClient.Response response = exchange(profile, method, path, body == null ? null : body.toString());
        previews.require(generation);
        if (response.status == 401) {
            synchronized (previews) {
                previews.require(generation);
                if (!store.clearRevoked(profile)) throw new IllegalStateException(text(R.string.copy_pairing_changed_refresh_the_connection));
                previews.invalidate();
                generation = previews.generation();
            }
            deliver(new JSONObject().put("request_id", id).put("status", 401).put("ok", false)
                    .put("error", text(R.string.copy_pc_unpaired_pair_again_with_a_new_code)), generation);
            return;
        }
        JSONObject result = new JSONObject().put("request_id", id).put("status", response.status)
                .put("ok", response.status >= 200 && response.status < 300).put("content_type", response.contentType);
        if (response.contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/jpeg")) {
            if (!validJpeg(response.body)) throw new IllegalStateException(text(R.string.copy_the_pc_returned_an_invalid_jpeg));
            result.put("base64", Base64.encodeToString(response.body, Base64.NO_WRAP));
            if ("/api/device/snapshot".equals(path) && response.status == 200) {
                result.put("image_id", previews.put(generation, response.body));
            }
        } else if (response.contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            Object value = new JSONTokener(response.text()).nextValue();
            if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) throw new IllegalStateException(text(R.string.copy_invalid_json_from_the_pc));
            result.put("data", value);
            if (response.status >= 400 && value instanceof JSONObject) result.put("error", ((JSONObject) value).optString("error", text(R.string.copy_the_server_rejected_the_request)));
        } else throw new IllegalStateException(text(R.string.copy_unsupported_response_format));
        deliver(result, generation);
    }

    private static boolean validJpeg(byte[] image) {
        return image.length >= 4 && image[0] == (byte) 0xff && image[1] == (byte) 0xd8;
    }

    private void saveImage(String id, String path, String imageId, long generation) throws Exception {
        NetworkPolicy.validateImagePath(path);
        JSONObject profile = requireProfile();
        previews.require(generation);
        byte[] bytes;
        if ("/api/device/snapshot".equals(path)) {
            if (imageId.isEmpty()) throw new IllegalArgumentException(text(R.string.copy_refresh_the_preview_before_saving_this_image));
            bytes = previews.get(generation, imageId);
        } else {
            if (!imageId.isEmpty()) throw new IllegalArgumentException(text(R.string.copy_saved_captures_do_not_need_a_preview_id));
            PinnedClient.Response response = exchange(profile, "GET", path, null);
            if (response.status != 200 || !response.contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/jpeg")
                    || !validJpeg(response.body)) throw new IllegalStateException(text(R.string.copy_could_not_retrieve_the_jpeg));
            bytes = response.body;
        }
        previews.require(generation);
        String filename = "DreamShield_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
                .format(java.time.LocalDateTime.now()) + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DreamShield");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException(text(R.string.copy_could_not_create_a_gallery_file));
        try {
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IllegalStateException(text(R.string.copy_could_not_write_the_gallery_file));
                stream.write(bytes);
            }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            synchronized (previews) {
                previews.require(generation);
                if (getContentResolver().update(uri, values, null, null) != 1) {
                    throw new IllegalStateException(text(R.string.copy_could_not_save_to_the_gallery_try_again));
                }
            }
        } catch (Exception error) {
            try { getContentResolver().delete(uri, null, null); }
            catch (Exception cleanupError) { error.addSuppressed(cleanupError); }
            throw error;
        }
        reply(id, 200, new JSONObject().put("saved", true).put("filename", filename)
                .put("location", "Pictures/DreamShield"), generation);
    }

    private void reply(String id, int status, JSONObject data) {
        try { deliver(new JSONObject().put("request_id", id).put("ok", true).put("status", status).put("data", data)); }
        catch (Exception ignored) { /* Only primitive JSON fields are emitted. */ }
    }

    private void reply(String id, int status, JSONObject data, long generation) {
        try { deliver(new JSONObject().put("request_id", id).put("ok", true).put("status", status).put("data", data), generation); }
        catch (Exception ignored) { }
    }

    private void fail(String id, String error) {
        try { deliver(new JSONObject().put("request_id", id).put("ok", false).put("status", 0).put("error", error)); }
        catch (Exception ignored) { }
    }

    private static String readableError(Exception error) {
        // JSONException messages may embed the server response, including a pairing token.
        if (error instanceof org.json.JSONException) return text(R.string.copy_check_the_pairing_code_and_server_version);
        if (error instanceof javax.net.ssl.SSLException) return text(R.string.copy_tls_certificate_check_failed_verify_the_fingerprin);
        if (error instanceof java.net.SocketTimeoutException) return text(R.string.copy_connection_timed_out_check_the_server_firewall_and);
        if (error instanceof java.net.ConnectException || error instanceof java.net.NoRouteToHostException) return text(R.string.copy_cannot_reach_the_pc_check_its_address_wi_fi_and_fi);
        String message = error.getMessage();
        return message == null ? text(R.string.copy_try_again_after_checking_the_connection) : message.substring(0, Math.min(message.length(), 600));
    }

    private void deliver(JSONObject response) {
        deliver(response, null);
    }

    private void deliver(JSONObject response, Long generation) {
        runOnUiThread(() -> {
            if (generation != null && !previews.isCurrent(generation)) {
                fail(response.optString("request_id"), text(R.string.copy_pairing_changed_please_retry));
                return;
            }
            java.util.function.Consumer<JSONObject> callback = nativeCallbacks.remove(response.optString("request_id"));
            if (!destroyed && callback != null) callback.accept(response);
        });
    }

    public void updateNativeSystemBars(boolean cover) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean light = !cover && !dark;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? appearance : 0, appearance);
            }
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            int appearance = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(light ? flags | appearance : flags & ~appearance);
        }
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (nativeState != null) nativeState.onThemeChanged();
    }
    @Override protected void onPause() {
        if (nativeState != null) nativeState.onLifecycle(false);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (nativeState != null) nativeState.onLifecycle(true);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (nativeState != null) nativeState.close();
        executor.shutdownNow();
        if (pairingDialog != null) { pairingDialog.dismiss(); pairingDialog = null; }
        previews.invalidate(); nativeCallbacks.clear();
        super.onDestroy();
    }
}
