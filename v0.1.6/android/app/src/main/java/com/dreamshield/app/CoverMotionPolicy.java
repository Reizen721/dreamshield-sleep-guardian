package com.dreamshield.app;

/** Pure intent policy for the cover boundary. Positive finger Y means returning to the cover. */
public final class CoverMotionPolicy {
    public static final float INTENT_DISTANCE_DP = 104f;
    public static final float RESISTANCE = .24f;
    public static final int ENTER_DURATION_MS = 1080;
    public static final int RETURN_DURATION_MS = 360;
    private float intent;
    private float dragDistance;
    private boolean unlocked;
    private boolean directFollow;
    private boolean towardContent = true;

    public float resistedDelta(float fingerDeltaY, float thresholdPx) {
        if (!Float.isFinite(fingerDeltaY) || !Float.isFinite(thresholdPx) || thresholdPx <= 0f) {
            throw new IllegalArgumentException("Finite drag and a positive threshold are required");
        }
        observeDirection(fingerDeltaY);
        if (directFollow) return -fingerDeltaY;
        float before = dragDistance;
        dragDistance = (float) Math.max(0d, Math.min(Float.MAX_VALUE, (double) dragDistance - fingerDeltaY));
        intent = Math.min(thresholdPx, dragDistance);
        if (intent >= thresholdPx) unlocked = true;
        return mappedDistance(dragDistance, thresholdPx) - mappedDistance(before, thresholdPx);
    }

    // Integrating a smooth response curve makes the unlock independent of input-frame size.
    // The gain is .24 at the intent threshold and reaches 1 over the following ~68dp.
    private static float mappedDistance(float distance, float threshold) {
        if (distance <= threshold) return distance * RESISTANCE;
        float blend = threshold * .65f;
        float after = distance - threshold;
        if (after >= blend) return threshold * RESISTANCE + blend * (1f + RESISTANCE) * .5f + after - blend;
        float x = after / blend;
        float integral = x * x * x - .5f * x * x * x * x;
        return threshold * RESISTANCE + after * RESISTANCE + (1f - RESISTANCE) * blend * integral;
    }

    /** Maps finger release speed through the same instantaneous gain as the last drag. */
    public float scrollVelocity(float fingerVelocityY, float thresholdPx) {
        if (!Float.isFinite(fingerVelocityY) || !Float.isFinite(thresholdPx) || thresholdPx <= 0f) {
            throw new IllegalArgumentException("Finite velocity and a positive threshold are required");
        }
        if (directFollow) return -fingerVelocityY;
        float x = Math.max(0f, Math.min(1f, (dragDistance - thresholdPx) / (thresholdPx * .65f)));
        float gain = RESISTANCE + (1f - RESISTANCE) * x * x * (3f - 2f * x);
        return -fingerVelocityY * gain;
    }

    public void observeDirection(float fingerDeltaY) {
        if (!Float.isFinite(fingerDeltaY)) throw new IllegalArgumentException("Finite direction required");
        if (Math.abs(fingerDeltaY) > .01f) towardContent = fingerDeltaY < 0f;
    }

    public void unlockForButton() { unlocked = true; directFollow = true; towardContent = true; }
    public boolean isUnlocked() { return unlocked; }
    public boolean shouldEnterContent() { return unlocked && towardContent; }
    public float intentPixels() { return intent; }
    public void reset() { intent = 0f; dragDistance = 0f; unlocked = false; directFollow = false; towardContent = true; }

    public static float normalizedProgress(int firstItemIndex, int firstItemOffset, float coverPixels) {
        if (!Float.isFinite(coverPixels) || coverPixels <= 0f) return 0f;
        if (firstItemIndex > 0) return 1f;
        return Math.max(0f, Math.min(1f, firstItemOffset / coverPixels));
    }
}
