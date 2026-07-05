package com.example.fishautoclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

/**
 * Configuration manager.
 * All coordinates are stored as percentages (0.0 ~ 1.0) of screen width/height,
 * so they work regardless of screen size or orientation.
 */
public class ConfigManager {
    private final SharedPreferences sp;

    // === Coordinate keys (stored as percentages * 10000) ===
    private static final String KEY_CAST_X = "cast_x";
    private static final String KEY_CAST_Y = "cast_y";
    private static final String KEY_LEFT_X = "left_x";
    private static final String KEY_LEFT_Y = "left_y";
    private static final String KEY_RIGHT_X = "right_x";
    private static final String KEY_RIGHT_Y = "right_y";
    private static final String KEY_SCAN_L = "scan_l";
    private static final String KEY_SCAN_T = "scan_t";
    private static final String KEY_SCAN_R = "scan_r";
    private static final String KEY_SCAN_B = "scan_b";

    // === Calibrated pixel coordinates (stored with screen size for validation) ===
    private static final String KEY_CALIBRATED = "calibrated";           // boolean: has calibrated data
    private static final String KEY_CAL_CAST_X = "cal_cast_x";          // pixel
    private static final String KEY_CAL_CAST_Y = "cal_cast_y";          // pixel
    private static final String KEY_CAL_LEFT_X = "cal_left_x";          // pixel
    private static final String KEY_CAL_LEFT_Y = "cal_left_y";          // pixel
    private static final String KEY_CAL_RIGHT_X = "cal_right_x";        // pixel
    private static final String KEY_CAL_RIGHT_Y = "cal_right_y";        // pixel
    private static final String KEY_CAL_SCREEN_W = "cal_screen_w";      // screen width when calibrated
    private static final String KEY_CAL_SCREEN_H = "cal_screen_h";      // screen height when calibrated

    // === Default percentages (landscape game layout, can be changed in UI) ===
    // These are rough defaults - user should adjust them
    public static final float DEFAULT_CAST_X = 0.90f;   // 90% from left
    public static final float DEFAULT_CAST_Y = 0.88f;   // 88% from top
    public static final float DEFAULT_LEFT_X = 0.15f;   // 15% from left
    public static final float DEFAULT_LEFT_Y = 0.77f;   // 77% from top
    public static final float DEFAULT_RIGHT_X = 0.85f;  // 85% from left
    public static final float DEFAULT_RIGHT_Y = 0.77f;  // 77% from top
    // Scan region (progress bar area)
    public static final float DEFAULT_SCAN_L = 0.34f;
    public static final float DEFAULT_SCAN_T = 0.07f;
    public static final float DEFAULT_SCAN_R = 0.66f;
    public static final float DEFAULT_SCAN_B = 0.10f;

    // Target colors
    public static final int COLOR_YELLOW = 0xFFF9DD44;
    public static final int COLOR_GREEN = 0xFF00D4AA;

    // Default values
    public static final int DEFAULT_LONG_PRESS_DURATION = 5000;
    public static final int DEFAULT_CLICK_CHECK_INTERVAL = 50;
    public static final int DEFAULT_DETECT_INTERVAL = 80;
    public static final int DEFAULT_CASTING_DELAY = 500;
    public static final int DEFAULT_FISHING_TIMEOUT = 30000;

    public ConfigManager(Context context) {
        sp = context.getSharedPreferences("fish_cfg", Context.MODE_PRIVATE);
        // Migrate: old values < 500ms are too short for long press, reset to default
        int saved = sp.getInt("long_press_duration", DEFAULT_LONG_PRESS_DURATION);
        if (saved < 500) {
            sp.edit().putInt("long_press_duration", DEFAULT_LONG_PRESS_DURATION).apply();
        }
    }

    // ===== Coordinate getters (returns percentages 0~1) =====

    public float getCastXPct() { return getFloat(KEY_CAST_X, DEFAULT_CAST_X); }
    public float getCastYPct() { return getFloat(KEY_CAST_Y, DEFAULT_CAST_Y); }
    public float getLeftXPct()  { return getFloat(KEY_LEFT_X, DEFAULT_LEFT_X); }
    public float getLeftYPct()  { return getFloat(KEY_LEFT_Y, DEFAULT_LEFT_Y); }
    public float getRightXPct() { return getFloat(KEY_RIGHT_X, DEFAULT_RIGHT_X); }
    public float getRightYPct() { return getFloat(KEY_RIGHT_Y, DEFAULT_RIGHT_Y); }
    public float getScanLPct()  { return getFloat(KEY_SCAN_L, DEFAULT_SCAN_L); }
    public float getScanTPct()  { return getFloat(KEY_SCAN_T, DEFAULT_SCAN_T); }
    public float getScanRPct()  { return getFloat(KEY_SCAN_R, DEFAULT_SCAN_R); }
    public float getScanBPct()  { return getFloat(KEY_SCAN_B, DEFAULT_SCAN_B); }

    // ===== Coordinate setters =====

    public void saveCastXPct(float v)  { saveFloat(KEY_CAST_X, v); }
    public void saveCastYPct(float v)  { saveFloat(KEY_CAST_Y, v); }
    public void saveLeftXPct(float v)  { saveFloat(KEY_LEFT_X, v); }
    public void saveLeftYPct(float v)  { saveFloat(KEY_LEFT_Y, v); }
    public void saveRightXPct(float v) { saveFloat(KEY_RIGHT_X, v); }
    public void saveRightYPct(float v) { saveFloat(KEY_RIGHT_Y, v); }
    public void saveScanLPct(float v)  { saveFloat(KEY_SCAN_L, v); }
    public void saveScanTPct(float v)  { saveFloat(KEY_SCAN_T, v); }
    public void saveScanRPct(float v)  { saveFloat(KEY_SCAN_R, v); }
    public void saveScanBPct(float v)  { saveFloat(KEY_SCAN_B, v); }

    // ===== Convert percentage to pixel for given screen size =====
    // Caller provides actual screen width/height

    public int castX(int screenW) { return (int)(getCastXPct() * screenW); }
    public int castY(int screenH) { return (int)(getCastYPct() * screenH); }
    public int leftX(int screenW)  { return (int)(getLeftXPct() * screenW); }
    public int leftY(int screenH)  { return (int)(getLeftYPct() * screenH); }
    public int rightX(int screenW) { return (int)(getRightXPct() * screenW); }
    public int rightY(int screenH) { return (int)(getRightYPct() * screenH); }

    public Rect scanRect(int screenW, int screenH) {
        return new Rect(
                (int)(getScanLPct() * screenW),
                (int)(getScanTPct() * screenH),
                (int)(getScanRPct() * screenW),
                (int)(getScanBPct() * screenH)
        );
    }

    // ===== Timing settings =====

    public int getLongPressDuration() {
        return sp.getInt("long_press_duration", DEFAULT_LONG_PRESS_DURATION);
    }
    public void saveLongPressDuration(int ms) {
        sp.edit().putInt("long_press_duration", Math.max(20, ms)).apply();
    }

    public int getClickCheckInterval() {
        return sp.getInt("click_check_interval", DEFAULT_CLICK_CHECK_INTERVAL);
    }
    public void saveClickCheckInterval(int ms) {
        sp.edit().putInt("click_check_interval", Math.max(20, ms)).apply();
    }

    public int getDetectionInterval() {
        return sp.getInt("detect_interval", DEFAULT_DETECT_INTERVAL);
    }
    public void saveDetectionInterval(int ms) {
        sp.edit().putInt("detect_interval", Math.max(20, ms)).apply();
    }

    public int getCastingDelay() {
        return sp.getInt("casting_delay", DEFAULT_CASTING_DELAY);
    }
    public void saveCastingDelay(int ms) {
        sp.edit().putInt("casting_delay", Math.max(500, ms)).apply();
    }

    public int getFishingTimeout() {
        return sp.getInt("fishing_timeout", DEFAULT_FISHING_TIMEOUT);
    }
    public void saveFishingTimeout(int ms) {
        sp.edit().putInt("fishing_timeout", Math.max(5000, ms)).apply();
    }

    // ===== Calibrated coordinates (pixel-based, tied to screen resolution) =====

    /** Whether a calibration exists and matches the current screen resolution */
    public boolean hasValidCalibration(int screenW, int screenH) {
        if (!sp.getBoolean(KEY_CALIBRATED, false)) return false;
        int calW = sp.getInt(KEY_CAL_SCREEN_W, 0);
        int calH = sp.getInt(KEY_CAL_SCREEN_H, 0);
        return calW == screenW && calH == screenH;
    }

    /** Save calibrated pixel coordinates with current screen size */
    public void saveCalibration(int castX, int castY, int leftX, int leftY, int rightX, int rightY,
                                int screenW, int screenH) {
        sp.edit()
                .putBoolean(KEY_CALIBRATED, true)
                .putInt(KEY_CAL_CAST_X, castX)
                .putInt(KEY_CAL_CAST_Y, castY)
                .putInt(KEY_CAL_LEFT_X, leftX)
                .putInt(KEY_CAL_LEFT_Y, leftY)
                .putInt(KEY_CAL_RIGHT_X, rightX)
                .putInt(KEY_CAL_RIGHT_Y, rightY)
                .putInt(KEY_CAL_SCREEN_W, screenW)
                .putInt(KEY_CAL_SCREEN_H, screenH)
                .apply();
    }

    /** Clear calibrated data */
    public void clearCalibration() {
        sp.edit().putBoolean(KEY_CALIBRATED, false).apply();
    }

    /** Get calibrated CAST X pixel */
    public int calCastX() { return sp.getInt(KEY_CAL_CAST_X, 0); }
    /** Get calibrated CAST Y pixel */
    public int calCastY() { return sp.getInt(KEY_CAL_CAST_Y, 0); }
    /** Get calibrated LEFT X pixel */
    public int calLeftX()  { return sp.getInt(KEY_CAL_LEFT_X, 0); }
    /** Get calibrated LEFT Y pixel */
    public int calLeftY()  { return sp.getInt(KEY_CAL_LEFT_Y, 0); }
    /** Get calibrated RIGHT X pixel */
    public int calRightX() { return sp.getInt(KEY_CAL_RIGHT_X, 0); }
    /** Get calibrated RIGHT Y pixel */
    public int calRightY() { return sp.getInt(KEY_CAL_RIGHT_Y, 0); }

    // ===== Internal helpers =====

    private float getFloat(String key, float def) {
        // Store as int (percentage * 10000) for precision
        int raw = sp.getInt(key, (int)(def * 10000));
        return raw / 10000f;
    }

    private void saveFloat(String key, float val) {
        sp.edit().putInt(key, (int)(val * 10000)).apply();
    }

    /** Check if stored values are corrupted (outside 0~1 range from old calibration data) */
    public boolean hasCorruptedData() {
        float cx = getCastXPct(), cy = getCastYPct();
        float lx = getLeftXPct(), ly = getLeftYPct();
        float rx = getRightXPct(), ry = getRightYPct();
        float sl = getScanLPct(), st = getScanTPct(), sr = getScanRPct(), sb = getScanBPct();
        if (cx < 0 || cx > 1.0f || cy < 0 || cy > 1.0f) return true;
        if (lx < 0 || lx > 1.0f || ly < 0 || ly > 1.0f) return true;
        if (rx < 0 || rx > 1.0f || ry < 0 || ry > 1.0f) return true;
        if (sl < 0 || sl > 1.0f || st < 0 || st > 1.0f) return true;
        if (sr < 0 || sr > 1.0f || sb < 0 || sb > 1.0f) return true;
        return false;
    }

    /** Reset all coordinate values to defaults */
    public void resetCoordinatesToDefaults() {
        sp.edit()
                .remove(KEY_CAST_X).remove(KEY_CAST_Y)
                .remove(KEY_LEFT_X).remove(KEY_LEFT_Y)
                .remove(KEY_RIGHT_X).remove(KEY_RIGHT_Y)
                .remove(KEY_SCAN_L).remove(KEY_SCAN_T)
                .remove(KEY_SCAN_R).remove(KEY_SCAN_B)
                .remove(KEY_CALIBRATED)
                .apply();
    }

    // ===== Drawing toggle =====
    public boolean isDrawingEnabled() {
        return sp.getBoolean("drawing_enabled", true);
    }
    public void saveDrawingEnabled(boolean enabled) {
        sp.edit().putBoolean("drawing_enabled", enabled).apply();
    }

    // ===== Legacy stubs =====
    public int getCatchDelay() { return sp.getInt("catch_delay", 500); }
    public void saveCatchDelay(int ms) { sp.edit().putInt("catch_delay", ms).apply(); }
    public int getRecastDelay() { return sp.getInt("recast_delay", 1000); }
    public void saveRecastDelay(int ms) { sp.edit().putInt("recast_delay", ms).apply(); }
}
