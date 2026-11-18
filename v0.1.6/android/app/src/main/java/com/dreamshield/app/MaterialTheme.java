package com.dreamshield.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/** Read-only system colors; no wallpaper image, account, or personal data enters the WebView. */
final class MaterialTheme {
    private static final String[] ROLES = {
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
            "surface", "surfaceContainer", "surfaceContainerHigh", "onSurface", "onSurfaceVariant",
            "outline", "outlineVariant", "secondaryContainer", "onSecondaryContainer",
            "tertiaryContainer", "onTertiaryContainer", "error", "onError", "errorContainer", "onErrorContainer"
    };
    private static final int[] LIGHT = {0x6750a4, 0xffffff, 0xeaddff, 0x21005d,
            0xfffbfe, 0xf3edf7, 0xece6f0, 0x1d1b20, 0x49454f, 0x79747e, 0xcac4d0,
            0xe8def8, 0x1d192b, 0xffd8e4, 0x31111d, 0xb3261e, 0xffffff, 0xf9dedc, 0x410e0b};
    private static final int[] DARK = {0xd0bcff, 0x381e72, 0x4f378b, 0xeaddff,
            0x141218, 0x211f26, 0x2b2930, 0xe6e0e9, 0xcac4d0, 0x938f99, 0x49454f,
            0x4a4458, 0xe8def8, 0x633b48, 0xffd8e4, 0xf2b8b5, 0x601410, 0x8c1d18, 0xf9dedc};

    private MaterialTheme() { }

    static JSONObject current(Context context) throws JSONException {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int[] colors = (dark ? DARK : LIGHT).clone();
        if (Build.VERSION.SDK_INT >= 34) {
            int[] resources = dark ? new int[] {
                    android.R.color.system_primary_dark, android.R.color.system_on_primary_dark,
                    android.R.color.system_primary_container_dark, android.R.color.system_on_primary_container_dark,
                    android.R.color.system_surface_dark, android.R.color.system_surface_container_dark,
                    android.R.color.system_surface_container_high_dark, android.R.color.system_on_surface_dark,
                    android.R.color.system_on_surface_variant_dark, android.R.color.system_outline_dark,
                    android.R.color.system_outline_variant_dark, android.R.color.system_secondary_container_dark,
                    android.R.color.system_on_secondary_container_dark, android.R.color.system_tertiary_container_dark,
                    android.R.color.system_on_tertiary_container_dark, android.R.color.system_error_dark,
                    android.R.color.system_on_error_dark, android.R.color.system_error_container_dark,
                    android.R.color.system_on_error_container_dark
            } : new int[] {
                    android.R.color.system_primary_light, android.R.color.system_on_primary_light,
                    android.R.color.system_primary_container_light, android.R.color.system_on_primary_container_light,
                    android.R.color.system_surface_light, android.R.color.system_surface_container_light,
                    android.R.color.system_surface_container_high_light, android.R.color.system_on_surface_light,
                    android.R.color.system_on_surface_variant_light, android.R.color.system_outline_light,
                    android.R.color.system_outline_variant_light, android.R.color.system_secondary_container_light,
                    android.R.color.system_on_secondary_container_light, android.R.color.system_tertiary_container_light,
                    android.R.color.system_on_tertiary_container_light, android.R.color.system_error_light,
                    android.R.color.system_on_error_light, android.R.color.system_error_container_light,
                    android.R.color.system_on_error_container_light
            };
            for (int i = 0; i < resources.length; i++) colors[i] = context.getColor(resources[i]);
        } else if (Build.VERSION.SDK_INT >= 31) {
            // Android 12/13 expose dynamic tonal palettes, before public semantic role colors.
            int[] resources = dark ? new int[] {
                    android.R.color.system_accent1_200, android.R.color.system_accent1_800,
                    android.R.color.system_accent1_700, android.R.color.system_accent1_100,
                    android.R.color.system_neutral1_900, android.R.color.system_neutral1_900,
                    android.R.color.system_neutral1_800, android.R.color.system_neutral1_100,
                    android.R.color.system_neutral2_200, android.R.color.system_neutral2_400,
                    android.R.color.system_neutral2_700, android.R.color.system_accent2_700,
                    android.R.color.system_accent2_100, android.R.color.system_accent3_700,
                    android.R.color.system_accent3_100
            } : new int[] {
                    android.R.color.system_accent1_600, android.R.color.system_accent1_0,
                    android.R.color.system_accent1_100, android.R.color.system_accent1_900,
                    android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
                    android.R.color.system_neutral1_100, android.R.color.system_neutral1_900,
                    android.R.color.system_neutral2_700, android.R.color.system_neutral2_500,
                    android.R.color.system_neutral2_200, android.R.color.system_accent2_100,
                    android.R.color.system_accent2_900, android.R.color.system_accent3_100,
                    android.R.color.system_accent3_900
            };
            for (int i = 0; i < resources.length; i++) colors[i] = context.getColor(resources[i]);
        }
        JSONObject tokens = new JSONObject();
        for (int i = 0; i < ROLES.length; i++) {
            tokens.put(ROLES[i], String.format(Locale.ROOT, "#%06x", colors[i] & 0xffffff));
        }
        // Read the system animation preference; never change accessibility settings.
        return new JSONObject().put("dark", dark).put("dynamic", Build.VERSION.SDK_INT >= 31)
                .put("reduceMotion", !ValueAnimator.areAnimatorsEnabled()).put("colors", tokens);
    }
}
