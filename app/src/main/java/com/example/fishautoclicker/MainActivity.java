package com.example.fishautoclicker;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_OVERLAY = 1002;
    private static final int REQUEST_ACCESSIBILITY = 1003;

    private EditText etInterval, etLongPressDuration;
    private EditText etCastX, etCastY, etLeftX, etLeftY, etRightX, etRightY;
    private EditText etScanL, etScanT, etScanR, etScanB;
    private Button btnStart, btnStop;
    private Button btnAccessibility;
    private TextView tvStatus, tvScreenSize;
    private Switch switchDrawing;
    private ConfigManager config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = new ConfigManager(this);
        initViews();

        // Auto-fix corrupted data from old calibration (values > 100%)
        if (config.hasCorruptedData()) {
            Log.w(TAG, "Corrupted coordinate data detected, resetting to defaults");
            config.resetCoordinatesToDefaults();
            Toast.makeText(this, "Detected old calibration data, reset to defaults", Toast.LENGTH_LONG).show();
        }

        loadConfigToUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        updatePermissionButtonStates();

        // Sync drawing switch state
        if (switchDrawing != null) {
            switchDrawing.setChecked(config.isDrawingEnabled());
        }

        if (isAccessibilityEnabled() && FishAccessibilityService.getInstance() == null) {
            Log.d(TAG, "Accessibility enabled but service instance is null, waiting for it to start...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                FishAccessibilityService svc = FishAccessibilityService.getInstance();
                if (svc != null) {
                    Log.d(TAG, "Service instance now available, showing floating window");
                    svc.showFloatingWindowPublic();
                } else {
                    Log.d(TAG, "Service instance still null after wait");
                }
            }, 1000);
        }
    }

    private void initViews() {
        etInterval = findViewById(R.id.intervalEditText);
        etLongPressDuration = findViewById(R.id.longPressDurationEditText);

        // Coordinate editors
        etCastX = findViewById(R.id.castXEdit);
        etCastY = findViewById(R.id.castYEdit);
        etLeftX = findViewById(R.id.leftXEdit);
        etLeftY = findViewById(R.id.leftYEdit);
        etRightX = findViewById(R.id.rightXEdit);
        etRightY = findViewById(R.id.rightYEdit);
        etScanL = findViewById(R.id.scanLEdit);
        etScanT = findViewById(R.id.scanTEdit);
        etScanR = findViewById(R.id.scanREdit);
        etScanB = findViewById(R.id.scanBEdit);

        btnStart = findViewById(R.id.startButton);
        btnStop = findViewById(R.id.stopButton);
        tvStatus = findViewById(R.id.statusTextView);
        tvScreenSize = findViewById(R.id.screenSizeText);
        switchDrawing = findViewById(R.id.drawingSwitch);

        // Show current screen size (landscape = game orientation)
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        tvScreenSize.setText("Game: " + w + "x" + h);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());

        // Drawing toggle switch
        switchDrawing.setChecked(config.isDrawingEnabled());
        switchDrawing.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.saveDrawingEnabled(isChecked);
            FishAccessibilityService svc = FishAccessibilityService.getInstance();
            if (svc != null) {
                svc.setDrawingEnabled(isChecked);
            }
        });

        // Enable Accessibility button
        btnAccessibility = findViewById(R.id.enableAccessibilityButton);
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        updatePermissionButtonStates();
    }

    /**
     * Update the accessibility button state so user can see if it's already enabled.
     */
    private void updatePermissionButtonStates() {
        if (btnAccessibility != null) {
            if (isAccessibilityEnabled()) {
                btnAccessibility.setText("? Accessibility Service (Enabled)");
                ViewCompat.setBackgroundTintList(btnAccessibility, android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32"))); // dark green
                btnAccessibility.setEnabled(false);
            } else {
                btnAccessibility.setText("Enable Accessibility Service");
                ViewCompat.setBackgroundTintList(btnAccessibility, android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0"))); // blue
                btnAccessibility.setEnabled(true);
            }
        }
    }

    private void loadConfigToUI() {
        etInterval.setText(String.valueOf(config.getDetectionInterval()));
        etLongPressDuration.setText(String.valueOf(config.getLongPressDuration()));

        // Load coordinate percentages (multiply by 100 for display)
        etCastX.setText(String.valueOf((int)(config.getCastXPct() * 100)));
        etCastY.setText(String.valueOf((int)(config.getCastYPct() * 100)));
        etLeftX.setText(String.valueOf((int)(config.getLeftXPct() * 100)));
        etLeftY.setText(String.valueOf((int)(config.getLeftYPct() * 100)));
        etRightX.setText(String.valueOf((int)(config.getRightXPct() * 100)));
        etRightY.setText(String.valueOf((int)(config.getRightYPct() * 100)));
        etScanL.setText(String.valueOf((int)(config.getScanLPct() * 100)));
        etScanT.setText(String.valueOf((int)(config.getScanTPct() * 100)));
        etScanR.setText(String.valueOf((int)(config.getScanRPct() * 100)));
        etScanB.setText(String.valueOf((int)(config.getScanBPct() * 100)));
    }

    private void saveConfigFromUI() {
        try {
            config.saveDetectionInterval(Integer.parseInt(etInterval.getText().toString()));
            config.saveLongPressDuration(Integer.parseInt(etLongPressDuration.getText().toString()));

            // Save coordinate percentages (input is 0~100, store as 0~1)
            config.saveCastXPct(Float.parseFloat(etCastX.getText().toString()) / 100f);
            config.saveCastYPct(Float.parseFloat(etCastY.getText().toString()) / 100f);
            config.saveLeftXPct(Float.parseFloat(etLeftX.getText().toString()) / 100f);
            config.saveLeftYPct(Float.parseFloat(etLeftY.getText().toString()) / 100f);
            config.saveRightXPct(Float.parseFloat(etRightX.getText().toString()) / 100f);
            config.saveRightYPct(Float.parseFloat(etRightY.getText().toString()) / 100f);
            config.saveScanLPct(Float.parseFloat(etScanL.getText().toString()) / 100f);
            config.saveScanTPct(Float.parseFloat(etScanT.getText().toString()) / 100f);
            config.saveScanRPct(Float.parseFloat(etScanR.getText().toString()) / 100f);
            config.saveScanBPct(Float.parseFloat(etScanB.getText().toString()) / 100f);

            Log.d(TAG, "Config saved: cast=" + config.getCastXPct() + "," + config.getCastYPct());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private void onStartClicked() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service first (Button 1)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable Overlay permission", Toast.LENGTH_SHORT).show();
            startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())),
                    REQUEST_OVERLAY);
            return;
        }
        saveConfigFromUI();
        requestScreenCapture();
    }

    private void onStopClicked() {
        MediaProjectionForegroundService.stop(this);
        FishAccessibilityService svc = FishAccessibilityService.getInstance();
        if (svc != null) {
            svc.stopFishing();
        }
        updateUI();
        Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show();
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    private boolean isAccessibilityEnabled() {
        if (FishAccessibilityService.getInstance() != null) {
            Log.d(TAG, "isAccessibilityEnabled: service instance alive -> true");
            return true;
        }
        try {
            String pkg = getPackageName();
            String serviceName = FishAccessibilityService.class.getName();
            String simpleName = FishAccessibilityService.class.getSimpleName();

            String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Log.d(TAG, "isAccessibilityEnabled: list=[" + (list != null ? list : "null") + "]");
            if (list != null && !list.isEmpty()) {
                String[] services = list.split(":");
                for (String service : services) {
                    service = service.trim();
                    if (service.contains(pkg) && (service.contains(serviceName) || service.contains(simpleName))) {
                        Log.d(TAG, "isAccessibilityEnabled: found matching service -> true");
                        return true;
                    }
                }
                Log.d(TAG, "isAccessibilityEnabled: pkg found but service not matching, list=" + list);
            }
        } catch (Exception e) {
            Log.e(TAG, "isAccessibilityEnabled error", e);
        }
        Log.d(TAG, "isAccessibilityEnabled: all checks failed -> false");
        return false;
    }

    private void openAccessibilitySettings() {
        startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQUEST_ACCESSIBILITY);
    }

    private void updateUI() {
        boolean running = MediaProjectionForegroundService.isRunning();
        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        tvStatus.setText(running ? "Running - switch to game and tap floating Start" : "Stopped");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                MediaProjectionForegroundService.start(this, resultCode, data);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    FishAccessibilityService svc = FishAccessibilityService.getInstance();
                    if (svc != null) {
                        Log.d(TAG, "Setting up callback and showing floating window");
                        svc.setFishingCallback(new FishAccessibilityService.FishingCallback() {
                            @Override
                            public void onStartFishing() {
                                Log.d(TAG, "onStartFishing callback triggered");
                                MediaProjectionForegroundService service = MediaProjectionForegroundService.getStaticInstance();
                                if (service != null) {
                                    service.startFishingLoop();
                                } else {
                                    Log.e(TAG, "MediaProjectionForegroundService staticInstance is null!");
                                }
                            }
                            @Override
                            public void onStopFishing() {
                                Log.d(TAG, "onStopFishing callback triggered");
                                MediaProjectionForegroundService service = MediaProjectionForegroundService.getStaticInstance();
                                if (service != null) {
                                    service.stopFishingLoop();
                                }
                            }
                        });
                        svc.showFloatingWindowPublic();
                    } else {
                        Log.e(TAG, "FishAccessibilityService instance is null!");
                    }
                }, 500);

                updateUI();
                Toast.makeText(this, "Service started. Switch to game, tap floating Start",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
