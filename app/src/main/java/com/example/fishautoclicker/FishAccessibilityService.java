package com.example.fishautoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import android.content.Intent;

public class FishAccessibilityService extends AccessibilityService {

    private static final String TAG = "FishAS";
    private static FishAccessibilityService instance;

    private WindowManager windowManager;
    private View floatingView;
    private Button btnStart, btnStop;
    private int startDragX, startDragY, lastDragX, lastDragY;

    private volatile boolean fishingActive = false;
    private Handler mainHandler;
    private volatile boolean gestureInProgress = false;
    private volatile int gestureId = 0;

    // Screen dimensions
    private int screenWidth;
    private int screenHeight;
    // Cutout/notch/hole-punch offset
    private int insetOffsetX = 0;
    private int insetOffsetY = 0;
    private ConfigManager config;

    // Drawing toggle (controlled from MainActivity)
    private boolean drawingEnabled = true;

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        if (!enabled) {
            hideDebugOverlay();
            hideClickIndicator();
            hideCenterMarker();
        }
    }

    public boolean isDrawingEnabled() {
        return drawingEnabled;
    }

    /**
     * Get the correct overlay window type.
     * Android 12+ requires TYPE_ACCESSIBILITY_OVERLAY for touch passthrough with FLAG_NOT_TOUCH_MODAL.
     * Lower versions use TYPE_APPLICATION_OVERLAY or TYPE_PHONE.
     */
    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    public int getInsetOffsetX() { return insetOffsetX; }
    public int getInsetOffsetY() { return insetOffsetY; }

    public interface FishingCallback {
        void onStartFishing();
        void onStopFishing();
    }
    private FishingCallback callback;

    public static FishAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        config = new ConfigManager(this);

        // Get screen dimensions
        refreshScreenDimensions();
        Log.d(TAG, "Screen: " + screenWidth + "x" + screenHeight);
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "onStartCommand called");
        return START_STICKY;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service connected, waiting for MainActivity to trigger floating window");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        fishingActive = false;
        instance = null;
        removeFloatingWindow();
        Log.d(TAG, "Service destroyed");
    }

    public void setFishingCallback(FishingCallback cb) {
        this.callback = cb;
    }

    /** Called by MainActivity after service is ready to show floating window */
    public void showFloatingWindowPublic() {
        Log.d(TAG, "showFloatingWindowPublic called, floatingView exists=" + (floatingView != null));
        mainHandler.post(this::showFloatingWindow);
    }

    /** Stop fishing but keep floating window */
    public void stopFishing() {
        fishingActive = false;
        mainHandler.post(this::updateButtonState);
        hideDebugOverlay();
        if (callback != null) {
            callback.onStopFishing();
        }
    }

    private void showFloatingWindow() {
        if (floatingView != null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_control, null);

        btnStart = floatingView.findViewById(R.id.floatStartBtn);
        btnStop = floatingView.findViewById(R.id.floatStopBtn);

        btnStart.setOnClickListener(v -> {
            Log.d(TAG, "Floating Start clicked");
            if (fishingActive) return;
            fishingActive = true;
            updateButtonState();

            showDebugOverlay();

            Log.d(TAG, "Callback exists: " + (callback != null));
            if (callback != null) {
                Log.d(TAG, "Calling callback.onStartFishing()");
                callback.onStartFishing();
            } else {
                Log.e(TAG, "Callback is null, cannot start fishing!");
                Toast.makeText(FishAccessibilityService.this, "Error: Callback not set", Toast.LENGTH_SHORT).show();
            }
        });

        btnStop.setOnClickListener(v -> {
            Log.d(TAG, "Stop clicked, stopping everything");
            fishingActive = false;
            updateButtonState();
            hideDebugOverlay();
            if (callback != null) {
                callback.onStopFishing();
            }
            removeFloatingWindow();
            // Stop the foreground service too
            MediaProjectionForegroundService.stop(this);
        });

        // Drag to move
        floatingView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startDragX = (int) event.getRawX();
                    startDragY = (int) event.getRawY();
                    lastDragX = startDragX;
                    lastDragY = startDragY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - lastDragX;
                    int dy = (int) event.getRawY() - lastDragY;
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) floatingView.getLayoutParams();
                    lp.x += dx;
                    lp.y += dy;
                    windowManager.updateViewLayout(floatingView, lp);
                    lastDragX = (int) event.getRawX();
                    lastDragY = (int) event.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    int totalDx = Math.abs((int) event.getRawX() - startDragX);
                    int totalDy = Math.abs((int) event.getRawY() - startDragY);
                    if (totalDx < 10 && totalDy < 10) {
                        return false;
                    }
                    return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        params.x = 20;
        params.y = 0;

        windowManager.addView(floatingView, params);
        updateButtonState();
        Log.d(TAG, "Floating window shown");
    }

    private void removeFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.e(TAG, "Remove floating view error", e);
            }
            floatingView = null;
            btnStart = null;
            btnStop = null;
        }
    }

    private void updateButtonState() {
        if (btnStart != null) {
            btnStart.setVisibility(fishingActive ? View.GONE : View.VISIBLE);
        }
        if (btnStop != null) {
            btnStop.setVisibility(fishingActive ? View.VISIBLE : View.GONE);
        }
    }

    public boolean isFishingActive() {
        return fishingActive;
    }

    public void setFishingActive(boolean active) {
        this.fishingActive = active;
        mainHandler.post(this::updateButtonState);
    }

    /**
     * Perform a simulated tap at screen coordinates (x, y).
     * Uses AccessibilityService.dispatchGesture directly (input tap requires INJECT_EVENTS which is system-only).
     */
    public void performClick(int x, int y) {
        Log.d(TAG, "performClick at (" + x + ", " + y + ") thread=" + Thread.currentThread().getName());
        
        if (gestureInProgress) {
            Log.d(TAG, "Gesture in progress, skipping click");
            return;
        }
        dispatchGestureClick(x, y);
    }

    public void performLongPress(int x, int y, int durationMs) {
        Log.d(TAG, "performLongPress at (" + x + ", " + y + ") duration=" + durationMs + "ms");
        
        if (gestureInProgress) {
            Log.d(TAG, "Gesture in progress, skipping long press");
            return;
        }
        dispatchGestureLongPress(x, y, durationMs);
    }

    // Force dispatch a gesture, ignoring gestureInProgress (used for interrupting opposite direction)
    public void performInterruptingPress(int x, int y, int durationMs) {
        Log.d(TAG, "performInterruptingPress at (" + x + ", " + y + ") duration=" + durationMs + "ms - interrupting current gesture");
        if (gestureInProgress) {
            cancelCurrentGesture();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        dispatchGestureLongPress(x, y, durationMs);
    }

    public boolean isGestureInProgress() {
        return gestureInProgress;
    }

    public void cancelCurrentGesture() {
        if (!gestureInProgress) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            gestureInProgress = false;
            return;
        }
        // Immediately mark as not in progress so ClickRunnable can dispatch new gestures
        // without waiting for the cancel gesture's callback (which may be delayed).
        gestureId++;
        gestureInProgress = false;
        // Dispatch a 1ms micro-gesture to force-cancel the in-progress long press.
        // Android guarantees: dispatching a new gesture cancels any in-progress gesture.
        // The 1ms duration is too short for the game to recognize as an effective tap.
        Path path = new Path();
        path.moveTo(0, 0);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 1);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        final int myId = gestureId;
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                // State already reset; callback is just for logging.
                Log.d(TAG, "Cancel gesture COMPLETED (long press interrupted)");
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Cancel gesture CANCELLED");
            }
        }, null);
    }

    private void dispatchGestureClick(int x, int y) {
        gestureId++;
        final int myId = gestureId;
        gestureInProgress = true;
        Log.d(TAG, "dispatchGesture at (" + x + ", " + y + ")");
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "SDK too old for dispatchGesture");
            gestureInProgress = false;
            return;
        }
        
        showClickIndicator(x, y);
        
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + 1, y + 1);
        path.lineTo(x, y);
        
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (myId == gestureId) {
                    gestureInProgress = false;
                }
                Log.d(TAG, "Click COMPLETED at (" + x + ", " + y + ")");
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (myId == gestureId) {
                    gestureInProgress = false;
                }
                Log.w(TAG, "Click CANCELLED at (" + x + ", " + y + ")");
            }
        }, null);
    }

    private void dispatchGestureLongPress(int x, int y, int durationMs) {
        gestureId++;
        final int myId = gestureId;
        gestureInProgress = true;
        Log.d(TAG, "dispatchGestureLongPress at (" + x + ", " + y + ") duration=" + durationMs + "ms");
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "SDK too old for dispatchGesture");
            gestureInProgress = false;
            return;
        }
        
        showClickIndicator(x, y);
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (myId == gestureId) {
                    gestureInProgress = false;
                }
                Log.d(TAG, "LongPress COMPLETED at (" + x + ", " + y + ")");
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (myId == gestureId) {
                    gestureInProgress = false;
                }
                Log.w(TAG, "LongPress CANCELLED at (" + x + ", " + y + ")");
            }
        }, null);
        
        // Safety timeout: force reset gestureInProgress if callback never fires
        // (prevents ClickRunnable from being stuck waiting forever)
        mainHandler.postDelayed(() -> {
            if (myId == gestureId && gestureInProgress) {
                gestureInProgress = false;
                Log.w(TAG, "LongPress callback timeout, force reset (id=" + myId + ")");
            }
        }, durationMs + 500);
    }

    // ===== Debug: visual click indicator (green dot, stays 1s) =====
    private View clickIndicator;

    private void showClickIndicator(int x, int y) {
        if (!drawingEnabled) return;
        mainHandler.post(() -> {
            try {
                if (clickIndicator != null) {
                    try { windowManager.removeView(clickIndicator); } catch (Exception ignored) {}
                    clickIndicator = null;
                }
                int size = 50;
                View dot = new View(this) {
                    private final Paint paint = new Paint();
                    {
                        paint.setColor(Color.MAGENTA);
                        paint.setAntiAlias(true);
                        paint.setStyle(Paint.Style.FILL);
                    }
                    @Override
                    protected void onDraw(Canvas canvas) {
                        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, paint);
                    }
                };

                WindowManager.LayoutParams dotParams = new WindowManager.LayoutParams(
                        size, size,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );
                dotParams.gravity = Gravity.TOP | Gravity.START;
                dotParams.x = x - size / 2;
                dotParams.y = y - size / 2;
                dotParams.alpha = 0.9f;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dotParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                }

                windowManager.addView(dot, dotParams);
                clickIndicator = dot;

                mainHandler.postDelayed(() -> {
                    try {
                        windowManager.removeView(dot);
                        if (clickIndicator == dot) clickIndicator = null;
                    } catch (Exception ignored) {}
                }, 300);
            } catch (Exception e) {
                Log.e(TAG, "Failed to show click indicator: " + e.getMessage());
            }
        });
    }

    public void performLongClick(int x, int y, long durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            dispatchGesture(
                    new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                            .build(),
                    null, null
            );
        }
    }

    // ===== Debug: show all key regions as red boxes =====
    private View debugOverlayView;
    private boolean debugOverlayVisible = false;

    private void hideClickIndicator() {
        if (clickIndicator != null) {
            try { windowManager.removeView(clickIndicator); } catch (Exception ignored) {}
            clickIndicator = null;
        }
    }

    private void hideCenterMarker() {
        if (centerMarkerView != null) {
            try { windowManager.removeView(centerMarkerView); } catch (Exception ignored) {}
            centerMarkerView = null;
        }
    }

    // ===== Debug: show a big yellow dot at center =====
    private View centerMarkerView;

    public void showCenterMarker() {
        if (!drawingEnabled) return;
        mainHandler.post(() -> {
            try {
                refreshScreenDimensions();

                if (centerMarkerView != null) {
                    try { windowManager.removeView(centerMarkerView); } catch (Exception ignored) {}
                }

                View marker = new View(this) {
                    private final Paint circlePaint = new Paint();
                    private final Paint textPaint = new Paint();
                    {
                        circlePaint.setColor(Color.YELLOW);
                        circlePaint.setAntiAlias(true);
                        circlePaint.setStyle(Paint.Style.FILL);
                        textPaint.setColor(Color.BLACK);
                        textPaint.setTextSize(36f);
                        textPaint.setAntiAlias(true);
                        textPaint.setFakeBoldText(true);
                        textPaint.setTextAlign(Paint.Align.CENTER);
                    }
                    @Override
                    protected void onDraw(Canvas canvas) {
                        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, 30f, circlePaint);
                        canvas.drawText("W:" + getWidth() + " H:" + getHeight(),
                                getWidth() / 2f, getHeight() / 2f + 80, textPaint);
                        canvas.drawText("Center: " + (getWidth() / 2) + "," + (getHeight() / 2),
                                getWidth() / 2f, getHeight() / 2f - 40, textPaint);
                    }
                };

                WindowManager.LayoutParams markerParams = new WindowManager.LayoutParams(
                        screenWidth, screenHeight,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );
                markerParams.gravity = Gravity.TOP | Gravity.START;
                markerParams.x = 0;
                markerParams.y = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    markerParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                }

                windowManager.addView(marker, markerParams);
                centerMarkerView = marker;
                Log.d(TAG, "Center marker shown at " + (screenWidth / 2) + "," + (screenHeight / 2));
            } catch (Exception e) {
                Log.e(TAG, "Failed to show center marker: " + e.getMessage());
            }
        });
    }

    private void refreshScreenDimensions() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    private volatile int detectedYellowX = -1;
    private volatile int detectedGreenLeft = -1;
    private volatile int detectedGreenRight = -1;
    private volatile int detectedGreenTop = -1;
    private volatile int detectedGreenBottom = -1;

    public void updateDetectionResults(int yellowX, int greenLeft, int greenRight, int greenTop, int greenBottom) {
        this.detectedYellowX = yellowX;
        this.detectedGreenLeft = greenLeft;
        this.detectedGreenRight = greenRight;
        this.detectedGreenTop = greenTop;
        this.detectedGreenBottom = greenBottom;
        mainHandler.post(() -> {
            if (debugOverlayVisible && debugOverlayView != null) {
                debugOverlayView.invalidate();
            }
        });
    }

    public void showDebugOverlay() {
        if (!drawingEnabled) return;
        mainHandler.post(() -> {
            try {
                refreshScreenDimensions();

                hideDebugOverlayOnly();

                final int refW = screenWidth;
                final int refH = screenHeight;

                View overlay = new View(this) {
                    private final Paint boxPaint = new Paint();
                    private final Paint textPaint = new Paint();
                    private final Paint greenBoxPaint = new Paint();
                    private final Paint yellowBoxPaint = new Paint();
                    private final Paint buttonPaint = new Paint();
                    {
                        boxPaint.setColor(Color.RED);
                        boxPaint.setStyle(Paint.Style.STROKE);
                        boxPaint.setStrokeWidth(4f);
                        boxPaint.setAntiAlias(true);
                        textPaint.setColor(Color.RED);
                        textPaint.setTextSize(28f);
                        textPaint.setAntiAlias(true);
                        textPaint.setFakeBoldText(true);
                        greenBoxPaint.setColor(Color.GREEN);
                        greenBoxPaint.setStyle(Paint.Style.STROKE);
                        greenBoxPaint.setStrokeWidth(6f);
                        greenBoxPaint.setAntiAlias(true);
                        yellowBoxPaint.setColor(Color.YELLOW);
                        yellowBoxPaint.setStyle(Paint.Style.STROKE);
                        yellowBoxPaint.setStrokeWidth(6f);
                        yellowBoxPaint.setAntiAlias(true);
                        buttonPaint.setColor(Color.RED);
                        buttonPaint.setStyle(Paint.Style.STROKE);
                        buttonPaint.setStrokeWidth(4f);
                        buttonPaint.setAntiAlias(true);
                    }
                    @Override
                    protected void onAttachedToWindow() {
                        super.onAttachedToWindow();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            android.view.WindowInsets insets = getRootWindowInsets();
                            if (insets != null) {
                                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                                if (cutout != null) {
                                    insetOffsetX = cutout.getSafeInsetLeft();
                                    insetOffsetY = cutout.getSafeInsetTop();
                                    Log.d(TAG, "Display cutout: safeInset left=" + insetOffsetX + " top=" + insetOffsetY);
                                } else {
                                    insetOffsetX = 0;
                                    insetOffsetY = 0;
                                }
                            }
                        }
                        post(() -> {
                            int[] loc = new int[2];
                            getLocationOnScreen(loc);
                            insetOffsetX = loc[0];
                            insetOffsetY = loc[1];
                            Log.d(TAG, "Overlay actual screen location: (" + insetOffsetX + "," + insetOffsetY + ")");
                            invalidate();
                        });
                    }
                    @Override
                    protected void onDraw(Canvas canvas) {
                        int w = refW;
                        int h = refH;
                        final int shiftX = insetOffsetX;
                        final int shiftY = insetOffsetY;

                        // Scan region (use original screen dimensions - calibrated by user)
                        int sl = (int)(config.getScanLPct() * w) - shiftX;
                        int st = (int)(config.getScanTPct() * h) - shiftY;
                        int sr = (int)(config.getScanRPct() * w) - shiftX;
                        int sb = (int)(config.getScanBPct() * h) - shiftY;
                        canvas.drawRect(sl, st, sr, sb, boxPaint);
                        canvas.drawText("SCAN", sl, st - 5, textPaint);

                        // CAST button
                        int cbw = 120;
                        int cbh = 120;
                        int cbX = (int)(config.getCastXPct() * w) - shiftX - cbw / 2;
                        int cbY = (int)(config.getCastYPct() * h) - shiftY - cbh / 2;
                        Paint castPaint = new Paint();
                        castPaint.setColor(Color.parseColor("#FF5722")); // orange-red
                        castPaint.setStyle(Paint.Style.STROKE);
                        castPaint.setStrokeWidth(5f);
                        castPaint.setAntiAlias(true);
                        canvas.drawRect(cbX, cbY, cbX + cbw, cbY + cbh, castPaint);
                        canvas.drawText("CAST", cbX, cbY - 5, castPaint);

                        // Left button
                        int lbw = 120;
                        int lbh = 120;
                        int lbX = (int)(config.getLeftXPct() * w) - shiftX - lbw / 2;
                        int lbY = (int)(config.getLeftYPct() * h) - shiftY - lbh / 2;
                        canvas.drawRect(lbX, lbY, lbX + lbw, lbY + lbh, buttonPaint);
                        canvas.drawText("LEFT", lbX, lbY - 5, textPaint);

                        // Right button
                        int rbw = 120;
                        int rbh = 120;
                        int rbX = (int)(config.getRightXPct() * w) - shiftX - rbw / 2;
                        int rbY = (int)(config.getRightYPct() * h) - shiftY - rbh / 2;
                        canvas.drawRect(rbX, rbY, rbX + rbw, rbY + rbh, buttonPaint);
                        canvas.drawText("RIGHT", rbX, rbY - 5, textPaint);

                        // Detected green bar (detected coordinates are already in absolute screen coords)
                        if (detectedGreenLeft >= 0 && detectedGreenRight >= 0 && detectedGreenTop >= 0 && detectedGreenBottom >= 0) {
                            int gl = detectedGreenLeft - shiftX;
                            int gt = st - 40;
                            int gr = detectedGreenRight - shiftX;
                            int gb = st;
                            canvas.drawRect(gl, gt, gr, gb, greenBoxPaint);
                            canvas.drawText("GREEN", gl, gt - 5, textPaint);
                        }

                        // Detected yellow bar (detected coordinates are already in absolute screen coords)
                        if (detectedYellowX >= 0) {
                            int yx = detectedYellowX - shiftX;
                            int yt = st - 40;
                            int yb = st;
                            canvas.drawRect(yx - 5, yt, yx + 5, yb, yellowBoxPaint);
                            canvas.drawText("YELLOW", yx, yt - 5, textPaint);
                        }

                        String info = "Screen: " + w + "x" + h;
                        canvas.drawText(info, 10, h - 20 - shiftY, textPaint);
                    }
                };

                WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                        screenWidth, screenHeight,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );
                overlayParams.gravity = Gravity.TOP | Gravity.START;
                overlayParams.x = 0;
                overlayParams.y = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    overlayParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                }

                windowManager.addView(overlay, overlayParams);
                debugOverlayView = overlay;
                debugOverlayVisible = true;
                Log.d(TAG, "Debug overlay shown - screen=" + screenWidth + "x" + screenHeight);
            } catch (Exception e) {
                Log.e(TAG, "Failed to show debug overlay: " + e.getMessage());
            }
        });
    }

    private void hideDebugOverlayOnly() {
        if (debugOverlayView != null) {
            try {
                windowManager.removeView(debugOverlayView);
            } catch (Exception ignored) {}
            debugOverlayView = null;
            debugOverlayVisible = false;
        }
    }

    public void hideDebugOverlay() {
        mainHandler.post(() -> {
            hideDebugOverlayOnly();
            if (centerMarkerView != null) {
                try {
                    windowManager.removeView(centerMarkerView);
                } catch (Exception ignored) {}
                centerMarkerView = null;
            }
        });
    }

    public boolean isDebugOverlayVisible() {
        return debugOverlayVisible;
    }
}
