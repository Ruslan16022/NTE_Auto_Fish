package com.example.fishautoclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Auto fishing foreground service.
 * Flow:
 * 1. User taps floating "Start" -> click casting button at bottom-right
 * 2. Wait castingDelay ms then start screen capture
 * 3. Detect yellow cursor and green progress bar positions
 * 4. Click left/right buttons to keep yellow cursor inside green bar
 * 5. Green bar disappears -> fish caught -> random tap to reel -> back to step 1
 */
public class MediaProjectionForegroundService extends Service {

    private static final String TAG = "FishSvc";
    private static final String CHANNEL_ID = "fish_auto_channel";
    private static final int NOTIFY_ID = 1001;

    private static MediaProjection mediaProjection;
    private static MediaProjectionForegroundService staticInstance;

    // Store resultCode and resultData statically so MainActivity can access them
    public static int pendingResultCode = -1;
    public static Intent pendingResultData;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private HandlerThread clickThread;
    private Handler clickHandler;
    private Handler mainHandler;

    private volatile boolean serviceRunning = false;
    private volatile boolean fishingLoopActive = false;
    private volatile boolean frameDetectionActive = false;
    private volatile int actionNeeded = 0; // 0=NONE, 1=LEFT, 2=RIGHT
    private volatile boolean nudgeShortPress = false; // true = dispatch a 50ms short press (nudge) instead of full long press
    private volatile boolean clickLoopActive = false;
    
    private int[] pixelsBuffer = null;
    private byte[] rawBuffer = null;
    private byte[] yBuffer = null;
    private byte[] uBuffer = null;
    private byte[] vBuffer = null;
    private Bitmap reusableBitmap = null;

    private ConfigManager config;
    private long lastClickTime = 0;
    private long fishingStartTime = 0;

    private int screenWidth;
    private int screenHeight;
    private int cropTopOffset = 0;
    private int cropBottomOffset = 0;
    private int cropLeftOffset = 0;
    private int cropRightOffset = 0;
    private int originalScreenWidth;
    private int originalScreenHeight;
    // Cached game canvas dimensions after black-bar cropping
    private int gameCanvasWidth = -1;
    private int gameCanvasHeight = -1;
    private boolean blackBarCalibrated = false;
    
    public static void start(Context ctx, int code, Intent data) {
        // Store statically first, since Intent extras may not survive cross-process on MIUI
        pendingResultCode = code;
        pendingResultData = data;
        Intent intent = new Intent(ctx, MediaProjectionForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, MediaProjectionForegroundService.class));
    }

    public static boolean isRunning() {
        return mediaProjection != null;
    }

    public static MediaProjectionForegroundService getStaticInstance() {
        return staticInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        staticInstance = this;
        config = new ConfigManager(this);
        mainHandler = new Handler(Looper.getMainLooper());

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        originalScreenWidth = screenWidth;
        originalScreenHeight = screenHeight;
        Log.d(TAG, "Screen: " + screenWidth + "x" + screenHeight);

        createNotificationChannel();
        startForeground(NOTIFY_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        serviceRunning = true;
        // initCapture reads from static pendingResultCode/pendingResultData
        mainHandler.postDelayed(this::initCapture, 500);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        staticInstance = null;
        pendingResultCode = -1;
        pendingResultData = null;
        stopFishingLoop();
        releaseCapture();
        serviceRunning = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Auto Fishing", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Auto fishing background service");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Auto Fishing Running")
                .setContentText("Screen capture ready. Switch to game and tap floating Start")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void initCapture() {
        Log.d(TAG, "initCapture: pendingResultCode=" + pendingResultCode + " dataNull=" + (pendingResultData == null));
        if (pendingResultData == null) {
            Log.e(TAG, "initCapture failed: missing resultData, retrying in 500ms");
            // Retry once - on MIUI the Intent extras may arrive late
            mainHandler.postDelayed(this::initCapture, 500);
            return;
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager is null");
            return;
        }
        mediaProjection = mpm.getMediaProjection(pendingResultCode, pendingResultData);
        if (mediaProjection == null) {
            Log.e(TAG, "getMediaProjection returned null");
            return;
        }
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection stopped by system");
                releaseCapture();
            }
        }, mainHandler);

        // Get current display rotation
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.view.Display display = wm.getDefaultDisplay();
        int rotation = display.getRotation();
        Log.d(TAG, "Display rotation: " + rotation);
        
        // Get display size (use getRealMetrics for actual screen size)
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        Log.d(TAG, "Screen metrics: " + width + "x" + height);
        
        // For landscape games, width should be larger than height
        // If current orientation is portrait (rotation 0 or 2), swap dimensions
        int captureWidth, captureHeight;
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            // Portrait orientation - swap for landscape capture
            captureWidth = height;
            captureHeight = width;
            Log.d(TAG, "Portrait detected, using landscape dimensions: " + captureWidth + "x" + captureHeight);
        } else {
            // Landscape orientation
            captureWidth = width;
            captureHeight = height;
            Log.d(TAG, "Landscape detected, dimensions: " + captureWidth + "x" + captureHeight);
        }
        
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        captureThread = new HandlerThread("FishCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        clickThread = new HandlerThread("FishClick");
        clickThread.start();
        clickHandler = new Handler(clickThread.getLooper());

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "FishCapture", captureWidth, captureHeight, getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, captureHandler);
        
        screenWidth = captureWidth;
        screenHeight = captureHeight;
        originalScreenWidth = captureWidth;
        originalScreenHeight = captureHeight;

        Log.d(TAG, "Capture initialized: " + captureWidth + "x" + captureHeight);
    }

    private void releaseCapture() {
        frameDetectionActive = false;
        clickLoopActive = false;
        actionNeeded = 0;
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        if (clickHandler != null) clickHandler.removeCallbacksAndMessages(null);
        if (captureThread != null) captureThread.quitSafely();
        if (clickThread != null) clickThread.quitSafely();
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
    }

    // ========== Fishing loop ==========

    /** Start the fishing loop (triggered by floating Start button) */
    public void startFishingLoop() {
        Log.d(TAG, "startFishingLoop called, active=" + fishingLoopActive + " running=" + serviceRunning + " mp=" + (mediaProjection != null));
        if (fishingLoopActive) return;
        if (!serviceRunning || mediaProjection == null) {
            Toast.makeText(this, "Service not ready, please restart", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Cannot start fishing loop: serviceRunning=" + serviceRunning + " mediaProjection=" + (mediaProjection != null));
            return;
        }
        fishingLoopActive = true;
        
        // Refresh screen dimensions from real display metrics
        // dispatchGesture uses the real screen coordinate system,
        // so we MUST use real screen pixels, not overlay layout dimensions.
        refreshScreenScale();

        // Show debug overlay for visual reference
        FishAccessibilityService svc = FishAccessibilityService.getInstance();
        if (svc != null) {
            svc.showDebugOverlay();
        }

        // Delay first cycle to allow overlay layout + offset detection to complete
        // Overlay's onAttachedToWindow -> getLocationOnScreen needs time
        Log.d(TAG, "Fishing loop started, screen=" + screenWidth + "x" + screenHeight + ", waiting 500ms for overlay init");
        mainHandler.postDelayed(() -> {
            if (fishingLoopActive) {
                Log.d(TAG, "Executing first cycle, insetOffset=" + (svc != null ? svc.getInsetOffsetX() : 0) + "," + (svc != null ? svc.getInsetOffsetY() : 0));
                executeOneFishingCycle();
            }
        }, 500);
    }

    /** Re-fetch screen dimensions (orientation may have changed) */
    private void refreshScreenScale() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int newW = metrics.widthPixels;
        int newH = metrics.heightPixels;
        if (newW != screenWidth || newH != screenHeight) {
            screenWidth = newW;
            screenHeight = newH;
            originalScreenWidth = newW;
            originalScreenHeight = newH;
            Log.d(TAG, "Screen refreshed: " + screenWidth + "x" + screenHeight);
        }
    }

    public void stopFishingLoop() {
        fishingLoopActive = false;
        frameDetectionActive = false;
        blackBarCalibrated = false; // Reset so next session re-calibrates
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        FishAccessibilityService svc = FishAccessibilityService.getInstance();
        if (svc != null) {
            svc.setFishingActive(false);
            svc.hideDebugOverlay();
        }
        Log.d(TAG, "Fishing loop stopped");
    }

    /** Cast -> 6s delay -> cast again -> start detection -> control -> fish caught -> loop */
    private void executeOneFishingCycle() {
        if (!fishingLoopActive) return;

        FishAccessibilityService svc = FishAccessibilityService.getInstance();
        if (svc == null || !svc.isFishingActive()) {
            stopFishingLoop();
            return;
        }

        int castX = config.castX(originalScreenWidth);
        int castY = config.castY(originalScreenHeight);
        Log.d(TAG, "First cast at (" + castX + ", " + castY + ")");
        svc.performClick(castX, castY);

        captureHandler.postDelayed(() -> {
            if (!fishingLoopActive) return;
            Log.d(TAG, "Second cast at (" + castX + ", " + castY + ")");
            svc.performClick(castX, castY);

            int castingDelay = 500;
            Log.d(TAG, "Waiting " + castingDelay + "ms before starting detection");
            captureHandler.postDelayed(() -> {
                if (!fishingLoopActive) return;
                Log.d(TAG, "Starting frame detection now");
                startFrameDetection();
            }, castingDelay);
        }, 7000);
    }

    private void startFrameDetection() {
        if (!fishingLoopActive) return;
        frameDetectionActive = true;
        clickLoopActive = true;
        actionNeeded = 0;
        fishingStartTime = System.currentTimeMillis();
        lastClickTime = 0;
        barWasDetected = false;
        barDisappearedTime = 0;
        Log.d(TAG, "Frame detection started");
        captureHandler.post(new DetectionRunnable());
        clickHandler.post(new ClickRunnable());
    }

    private boolean barWasDetected = false;
    private long barDisappearedTime = 0;
    private int consecutiveFailures = 0;
    private DetectionResult lastValidResult = null;

    private class DetectionRunnable implements Runnable {
        @Override
        public void run() {
            if (!frameDetectionActive || !fishingLoopActive) return;

            DetectionResult result = detectAndControl();

            boolean greenDetected = result != null && result.greenLeft >= 0 && result.greenRight >= 0;
            boolean yellowDetected = result != null && result.yellowX >= 0;
            
            if (greenDetected || yellowDetected) {
                barWasDetected = true;
                barDisappearedTime = 0;
                consecutiveFailures = 0;
                if (greenDetected && yellowDetected) {
                    lastValidResult = result;
                }
                controlCursor(result);
                captureHandler.postDelayed(this, config.getDetectionInterval());
            } else {
                consecutiveFailures++;
                
                if (consecutiveFailures <= 5 && lastValidResult != null) {
                    controlCursor(lastValidResult);
                } else {
                    FishAccessibilityService svc = FishAccessibilityService.getInstance();
                    if (svc != null) {
                        svc.updateDetectionResults(-1, -1, -1, -1, -1);
                    }
                }
                
                if (barWasDetected) {
                    if (barDisappearedTime == 0) {
                        barDisappearedTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - barDisappearedTime > 2000) {
                        Log.d(TAG, "Fish caught!");
                        frameDetectionActive = false;
                        consecutiveFailures = 0;
                        lastValidResult = null;
                        handleFishCaught();
                        return;
                    }
                } else {
                    long elapsed = System.currentTimeMillis() - fishingStartTime;
                    if (elapsed > config.getFishingTimeout()) {
                        Log.d(TAG, "Timeout, retrying...");
                        frameDetectionActive = false;
                        consecutiveFailures = 0;
                        lastValidResult = null;
                        captureHandler.postDelayed(() -> {
                            if (fishingLoopActive) executeOneFishingCycle();
                        }, 1000);
                        return;
                    }
                }
                
                captureHandler.postDelayed(this, config.getDetectionInterval());
            }
        }
    }

    private volatile int lastAction = 0; // 0=NONE, 1=LEFT, 2=RIGHT

    private class ClickRunnable implements Runnable {
        private static final int NUDGE_DURATION_MS = 50;
        
        @Override
        public void run() {
            Log.d(TAG, "ClickRunnable started, clickLoopActive=" + clickLoopActive + " fishingLoopActive=" + fishingLoopActive);
            int loopCount = 0;
            while (clickLoopActive && fishingLoopActive) {
                int action = actionNeeded;
                boolean nudge = nudgeShortPress;
                FishAccessibilityService svc = FishAccessibilityService.getInstance();
                int pressDuration = nudge ? NUDGE_DURATION_MS : config.getLongPressDuration();
                loopCount++;
                if (loopCount % 100 == 0) {
                    Log.d(TAG, "ClickRunnable heartbeat: action=" + action + " nudge=" + nudge
                            + " lastAction=" + lastAction
                            + " gestureInProgress=" + (svc != null ? svc.isGestureInProgress() : "svc=null")
                            + " pressDuration=" + pressDuration);
                }

                if (svc != null) {
                    if (action == 0) {
                        if (svc.isGestureInProgress()) {
                            svc.cancelCurrentGesture();
                        }
                        lastAction = 0;
                    } else {
                        boolean directionChanged = (lastAction != 0 && lastAction != action);
                        
                        if (directionChanged) {
                            if (svc.isGestureInProgress()) {
                                svc.cancelCurrentGesture();
                                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                            }
                            Log.d(TAG, "ClickRunnable dispatching (direction change) action=" + action + " nudge=" + nudge + " duration=" + pressDuration);
                            dispatchLongPress(svc, action, pressDuration);
                            lastAction = action;
                            if (nudge) {
                                // After a nudge, wait for it to complete before next check.
                                // Nudge is short (50ms); sleep through to avoid spamming.
                                try { Thread.sleep(NUDGE_DURATION_MS + 10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                                // After nudge completes, clear the flag so we don't repeat nudge indefinitely.
                                // If yellow is still not detected, controlCursor will set nudgeShortPress=true again next cycle.
                                nudgeShortPress = false;
                                actionNeeded = 0;
                            }
                        } else if (!svc.isGestureInProgress()) {
                            Log.d(TAG, "ClickRunnable dispatching (new) action=" + action + " nudge=" + nudge + " duration=" + pressDuration);
                            dispatchLongPress(svc, action, pressDuration);
                            lastAction = action;
                            if (nudge) {
                                try { Thread.sleep(NUDGE_DURATION_MS + 10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                                nudgeShortPress = false;
                                actionNeeded = 0;
                            }
                        }
                    }
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Log.d(TAG, "ClickRunnable exited, clickLoopActive=" + clickLoopActive + " fishingLoopActive=" + fishingLoopActive);
            FishAccessibilityService svc = FishAccessibilityService.getInstance();
            if (svc != null && svc.isGestureInProgress()) {
                svc.cancelCurrentGesture();
            }
            actionNeeded = 0;
            lastAction = 0;
            nudgeShortPress = false;
        }
        
        private void dispatchLongPress(FishAccessibilityService svc, int action, int durationMs) {
            if (action == 1) {
                int lx = config.leftX(originalScreenWidth);
                int ly = config.leftY(originalScreenHeight);
                svc.performLongPress(lx, ly, durationMs);
            } else if (action == 2) {
                int rx = config.rightX(originalScreenWidth);
                int ry = config.rightY(originalScreenHeight);
                svc.performLongPress(rx, ry, durationMs);
            }
        }
    }

    /** Only detect bar/cursor positions, return DetectionResult. */
    private DetectionResult detectShapes(Bitmap bmp) {
        DetectionResult result = new DetectionResult();
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        
        int sampleY = h / 2;
        
        int sampleInterval = 1;
        int numSamples = (w + sampleInterval - 1) / sampleInterval;
        
        int[] greenVotes = new int[numSamples];
        int[] yellowVotes = new int[numSamples];
        
        for (int i = 0; i < numSamples; i++) {
            int x = i * sampleInterval;
            if (x >= w) x = w - 1;
            
            int pixel = bmp.getPixel(x, sampleY);
            
            if (isGreen(pixel)) greenVotes[i]++;
            if (isYellow(pixel)) yellowVotes[i]++;
        }
        
        int greenStart = -1, greenEnd = -1;
        for (int i = 0; i < numSamples; i++) {
            if (greenVotes[i] >= 1) {
                if (greenStart < 0) greenStart = i;
                greenEnd = i;
            }
        }
        
        int yellowStart = -1, yellowEnd = -1;
        for (int i = 0; i < numSamples; i++) {
            if (yellowVotes[i] >= 1) {
                if (yellowStart < 0) yellowStart = i;
                yellowEnd = i;
            }
        }

        if (greenStart >= 0 && greenEnd >= 0) {
            int greenStripWidth = greenEnd - greenStart + 1;
            if (greenStripWidth >= 2) {
                int greenCenterStrip = (greenStart + greenEnd) / 2;
                result.greenLeft = greenStart * sampleInterval;
                result.greenRight = greenEnd * sampleInterval + sampleInterval;
                result.greenWidth = result.greenRight - result.greenLeft;
                result.greenCenter = greenCenterStrip * sampleInterval + sampleInterval / 2;
                result.greenTop = 0;
                result.greenBottom = h;
            }
        }

        if (yellowStart >= 0 && yellowEnd >= 0) {
            int yellowCenterStrip = (yellowStart + yellowEnd) / 2;
            result.yellowX = yellowCenterStrip * sampleInterval + sampleInterval / 2;
        }

        Log.d(TAG, "Detect: green=" + (greenStart >= 0 ? greenStart + "-" + greenEnd : "none") 
            + " yellow=" + (yellowStart >= 0 ? yellowStart + "-" + yellowEnd : "none"));

        return result;
    }

    /** Detect cursor/bar positions. Returns DetectionResult. */
    private DetectionResult detectAndControl() {
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            return null;
        }

        Bitmap bmp = null;
        Bitmap croppedBmp = null;
        Bitmap roi = null;
        try {
            bmp = imageToBitmap(image);
            if (bmp == null) {
                return null;
            }
            
            // Dynamic black-bar cropping: detect actual game canvas on first few frames
            if (!blackBarCalibrated) {
                calibrateBlackBars(bmp);
            }
            
            // Apply cached black-bar crop if calibrated
            if (blackBarCalibrated && gameCanvasWidth > 0 && gameCanvasHeight > 0) {
                croppedBmp = Bitmap.createBitmap(bmp, cropLeftOffset, cropTopOffset, gameCanvasWidth, gameCanvasHeight);
                screenWidth = gameCanvasWidth;
                screenHeight = gameCanvasHeight;
            } else {
                croppedBmp = bmp;
            }
            
            Rect scanRect = config.scanRect(originalScreenWidth, originalScreenHeight);
            int scanLeft = Math.max(0, scanRect.left - cropLeftOffset);
            int scanTop = Math.max(0, scanRect.top - cropTopOffset);
            int scanRight = Math.min(croppedBmp.getWidth(), scanRect.right - cropLeftOffset);
            int scanBottom = Math.min(croppedBmp.getHeight(), scanRect.bottom - cropTopOffset);
            
            scanRect.left = scanLeft;
            scanRect.top = scanTop;
            scanRect.right = scanRight;
            scanRect.bottom = scanBottom;

            if (scanRect.width() <= 0 || scanRect.height() <= 0) {
                return null;
            }

            roi = Bitmap.createBitmap(croppedBmp, scanRect.left, scanRect.top, scanRect.width(), scanRect.height());
            return detectShapes(roi);
        } finally {
            // Only recycle croppedBmp if it was a new allocation (different from reusable bmp)
            if (croppedBmp != null && croppedBmp != bmp) croppedBmp.recycle();
            // roi is always a new allocation from createBitmap
            if (roi != null) roi.recycle();
            // IMPORTANT: bmp is the reusable bitmap from imageToBitmap, do NOT recycle it
            image.close();
        }
    }
    
    /**
     * Detect black bars on the captured frame using sparse sampling.
     * This determines the actual game canvas boundaries (top/bottom/left/right black bars).
     * Runs once per fishing session - caches the result.
     */
    private void calibrateBlackBars(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        
        // Sample rows at sparse intervals to find top/bottom black bars
        int topBar = 0;
        int bottomBar = 0;
        int leftBar = 0;
        int rightBar = 0;
        
        // Sample several columns across the width
        int[] sampleCols = new int[]{w / 4, w / 2, w * 3 / 4};
        
        // Find top black bar: scan down from top
        for (int y = 0; y < h / 3; y++) {
            boolean allBlack = true;
            for (int col : sampleCols) {
                int pixel = bmp.getPixel(col, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                if (r > 15 || g > 15 || b > 15) {
                    allBlack = false;
                    break;
                }
            }
            if (!allBlack) {
                topBar = y;
                break;
            }
        }
        
        // Find bottom black bar: scan up from bottom
        for (int y = h - 1; y > h * 2 / 3; y--) {
            boolean allBlack = true;
            for (int col : sampleCols) {
                int pixel = bmp.getPixel(col, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                if (r > 15 || g > 15 || b > 15) {
                    allBlack = false;
                    break;
                }
            }
            if (!allBlack) {
                bottomBar = h - 1 - y;
                break;
            }
        }
        
        // Find left/right black bars: sample middle row
        int midY = h / 2;
        for (int x = 0; x < w / 4; x++) {
            int pixel = bmp.getPixel(x, midY);
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);
            if (r > 15 || g > 15 || b > 15) {
                leftBar = x;
                break;
            }
        }
        for (int x = w - 1; x > w * 3 / 4; x--) {
            int pixel = bmp.getPixel(x, midY);
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);
            if (r > 15 || g > 15 || b > 15) {
                rightBar = w - 1 - x;
                break;
            }
        }
        
        cropTopOffset = topBar;
        cropBottomOffset = bottomBar;
        cropLeftOffset = leftBar;
        cropRightOffset = rightBar;
        gameCanvasWidth = w - leftBar - rightBar;
        gameCanvasHeight = h - topBar - bottomBar;
        
        if (gameCanvasWidth > 0 && gameCanvasHeight > 0 && gameCanvasWidth >= w * 0.5f && gameCanvasHeight >= h * 0.5f) {
            blackBarCalibrated = true;
            Log.d(TAG, "Black bar calibrated: canvas=" + gameCanvasWidth + "x" + gameCanvasHeight 
                    + " (original=" + w + "x" + h + ") "
                    + "crop: top=" + topBar + " bottom=" + bottomBar + " left=" + leftBar + " right=" + rightBar);
        } else {
            // Calibration failed (e.g., full-screen game with no black bars), use full image
            cropTopOffset = 0;
            cropBottomOffset = 0;
            cropLeftOffset = 0;
            cropRightOffset = 0;
            gameCanvasWidth = w;
            gameCanvasHeight = h;
            blackBarCalibrated = true;
            Log.d(TAG, "No black bars detected, using full image: " + w + "x" + h);
        }
    }
    
    /** Convert Image to Bitmap */
    private Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        
        Image.Plane[] planes = image.getPlanes();
        
        if (planes.length == 1) {
            ByteBuffer buffer = planes[0].getBuffer();
            int stride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            
            int rawLen = buffer.remaining();
            if (rawBuffer == null || rawBuffer.length < rawLen) {
                rawBuffer = new byte[rawLen];
            }
            buffer.position(0);
            buffer.get(rawBuffer, 0, rawLen);
            
            boolean hasData = false;
            for (int i = 0; i < Math.min(100, rawLen); i++) {
                if (rawBuffer[i] != 0) {
                    hasData = true;
                    break;
                }
            }
            if (!hasData) {
                Log.w(TAG, "Image buffer is empty (all zeros), skipping frame");
                return null;
            }
            
            int pixelCount = w * h;
            if (pixelsBuffer == null || pixelsBuffer.length < pixelCount) {
                pixelsBuffer = new int[pixelCount];
            }
            
            for (int y = 0; y < h; y++) {
                int rowOffset = y * stride;
                for (int x = 0; x < w; x++) {
                    int idx = rowOffset + x * pixelStride;
                    if (idx + 3 < rawLen) {
                        int r = rawBuffer[idx] & 0xFF;
                        int g = rawBuffer[idx + 1] & 0xFF;
                        int b = rawBuffer[idx + 2] & 0xFF;
                        pixelsBuffer[y * w + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            }
            
            if (reusableBitmap == null || reusableBitmap.getWidth() != w || reusableBitmap.getHeight() != h) {
                if (reusableBitmap != null) {
                    reusableBitmap.recycle();
                }
                reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            reusableBitmap.setPixels(pixelsBuffer, 0, w, 0, 0, w, h);
            return reusableBitmap;
        } else if (planes.length >= 3) {
            ByteBuffer yPlane = planes[0].getBuffer();
            ByteBuffer uPlane = planes[1].getBuffer();
            ByteBuffer vPlane = planes[2].getBuffer();
            
            int ySize = yPlane.remaining();
            int uSize = uPlane.remaining();
            int vSize = vPlane.remaining();
            
            if (yBuffer == null || yBuffer.length < ySize) yBuffer = new byte[ySize];
            if (uBuffer == null || uBuffer.length < uSize) uBuffer = new byte[uSize];
            if (vBuffer == null || vBuffer.length < vSize) vBuffer = new byte[vSize];
            
            yPlane.get(yBuffer, 0, ySize);
            uPlane.get(uBuffer, 0, uSize);
            vPlane.get(vBuffer, 0, vSize);
            
            int pixelCount = w * h;
            if (pixelsBuffer == null || pixelsBuffer.length < pixelCount) {
                pixelsBuffer = new int[pixelCount];
            }
            
            int yStride = planes[0].getRowStride();
            int uStride = planes[1].getRowStride();
            int vStride = planes[2].getRowStride();
            
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int yIdx = y * yStride + x;
                    int uIdx = (y / 2) * uStride + (x / 2);
                    int vIdx = (y / 2) * vStride + (x / 2);
                    
                    if (yIdx < ySize && uIdx < uSize && vIdx < vSize) {
                        int yVal = yBuffer[yIdx] & 0xFF;
                        int uVal = uBuffer[uIdx] & 0xFF;
                        int vVal = vBuffer[vIdx] & 0xFF;
                        
                        int r = (int) (yVal + 1.370705 * (vVal - 128));
                        int g = (int) (yVal - 0.698001 * (vVal - 128) - 0.337633 * (uVal - 128));
                        int b = (int) (yVal + 1.732446 * (uVal - 128));
                        
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));
                        
                        pixelsBuffer[y * w + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            }
            
            if (reusableBitmap == null || reusableBitmap.getWidth() != w || reusableBitmap.getHeight() != h) {
                if (reusableBitmap != null) {
                    reusableBitmap.recycle();
                }
                reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            reusableBitmap.setPixels(pixelsBuffer, 0, w, 0, 0, w, h);
            return reusableBitmap;
        }
        
        return null;
    }
    
    /** Control cursor position based on detection result */
    private void controlCursor(DetectionResult result) {
        // Use cropped scanRect that matches what detectAndControl uses
        Rect scanRect = config.scanRect(originalScreenWidth, originalScreenHeight);
        int scanLeft = Math.max(0, scanRect.left - cropLeftOffset);
        int scanTop = Math.max(0, scanRect.top - cropTopOffset);
        
        int cursorAbsX = result.yellowX >= 0 ? scanLeft + result.yellowX : -1;
        int greenAbsLeft = result.greenLeft >= 0 ? scanLeft + result.greenLeft : -1;
        int greenAbsRight = result.greenRight >= 0 ? scanLeft + result.greenRight : -1;
        int greenAbsTop = result.greenTop >= 0 ? scanTop + result.greenTop : -1;
        int greenAbsBottom = result.greenBottom >= 0 ? scanTop + result.greenBottom : -1;

        FishAccessibilityService svc = FishAccessibilityService.getInstance();
        if (svc != null) {
            svc.updateDetectionResults(cursorAbsX, greenAbsLeft, greenAbsRight, greenAbsTop, greenAbsBottom);
        }

        if (result.greenLeft < 0) {
            actionNeeded = 0;
            return;
        }

        if (result.yellowX < 0) {
            // Yellow not detected. Only act if no gesture is in progress (press stopped).
            // This avoids interrupting an ongoing long press.
            if (svc != null && !svc.isGestureInProgress()) {
                int direction = 0;
                // Determine direction from last known action
                if (lastAction != 0) {
                    direction = lastAction;
                } else if (lastValidResult != null && lastValidResult.yellowX >= 0) {
                    // No last action: compute from last valid yellow position relative to green
                    int lastCursorAbsX = scanLeft + lastValidResult.yellowX;
                    int lastGreenCenterAbs = scanLeft + lastValidResult.greenCenter;
                    int lastDistance = lastCursorAbsX - lastGreenCenterAbs;
                    if (Math.abs(lastDistance) >= 10) {
                        direction = lastDistance > 0 ? 1 : 2;
                    }
                }
                if (direction != 0) {
                    actionNeeded = direction;
                    nudgeShortPress = true; // 50ms short nudge, not a full long press
                }
            }
            // If a gesture is in progress, let it continue; don't touch actionNeeded.
            return;
        }

        int greenCenterAbs = scanLeft + result.greenCenter;
        int greenHalfWidth = result.greenWidth / 2;
        int distance = cursorAbsX - greenCenterAbs;
        
        int trackThreshold = greenHalfWidth / 3;
        
        if (Math.abs(distance) < trackThreshold) {
            actionNeeded = 0;
        } else {
            actionNeeded = distance > 0 ? 1 : 2;
        }
        // Yellow is detected again: exit nudge mode, resume normal long press behavior
        nudgeShortPress = false;
    }

    private boolean isGreen(int pixel) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        return r >= 10 && r <= 80 && g >= 180 && g <= 255 && b >= 140 && b <= 210 && Math.abs(g - b) <= 60;
    }

    private boolean isYellow(int pixel) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        // Relaxed thresholds for cloud version (YiHuan Cloud) which has paler/yellower-white colors
        // Original: r>=200 g>=200 b:50-130 |r-g|<=30
        // Relaxed: lower R/G min to catch pale yellow, raise B max for whitish tones
        boolean isBright = (r >= 160 && g >= 160);           // bright enough (was 200)
        boolean notTooBlue = (b < 180);                       // allow more blue/white (was 130)
        boolean notTooDark = (b >= 20);                        // minimum blue
        boolean rgBalanced = (Math.abs(r - g) <= 45);         // R and G similar (was 30)
        boolean yellowDominant = (r > b + 40 && g > b + 40);  // R,G clearly above B
        return isBright && notTooBlue && notTooDark && rgBalanced && yellowDominant;
    }

    private Bitmap cropBlackBars(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        
        int top = h, bottom = 0, left = w, right = 0;
        
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = pixels[y * w + x];
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                
                if (r > 10 || g > 10 || b > 10) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        
        if (top <= bottom && left <= right) {
            int newW = right - left + 1;
            int newH = bottom - top + 1;
            Log.d(TAG, "Cropped black bars: " + w + "x" + h + " -> " + newW + "x" + newH + " (top=" + top + ", left=" + left + ")");
            return Bitmap.createBitmap(bmp, left, top, newW, newH);
        }
        
        return bmp;
    }

    private void handleFishCaught() {
        clickLoopActive = false;
        actionNeeded = 0;
        Log.d(TAG, "Handling fish caught - waiting 1s before clicking cast");

        captureHandler.postDelayed(() -> {
            if (!fishingLoopActive) return;

            FishAccessibilityService svc = FishAccessibilityService.getInstance();
            if (svc == null) return;

            int castX = config.castX(originalScreenWidth);
            int castY = config.castY(originalScreenHeight);
            Log.d(TAG, "Fish caught, clicking cast button at (" + castX + ", " + castY + ")");
            svc.performClick(castX, castY);

            captureHandler.postDelayed(() -> {
                if (fishingLoopActive) executeOneFishingCycle();
            }, 3000);
        }, 1000);
    }

    private void clickRandomSpot() {
        FishAccessibilityService svc = FishAccessibilityService.getInstance();
        if (svc == null) return;
        int rx = (int) (screenWidth * 0.3 + Math.random() * screenWidth * 0.4);
        int ry = (int) (screenHeight * 0.3 + Math.random() * screenHeight * 0.4);
        svc.performClick(rx, ry);
    }

    // ========== Color-based shape detection helpers ==========

    private static class DetectionResult {
        int yellowX = -1;
        int greenLeft = -1, greenRight = -1, greenTop = -1, greenBottom = -1;
        int greenCenter = -1;
        int greenWidth = -1;
    }

    private DetectionResult detectShapesFromPixels(int[] pixels, int w, int h, Rect scanRect) {
        DetectionResult result = new DetectionResult();
        
        int roiW = scanRect.width();
        int roiH = scanRect.height();
        
        int[] greenCounts = new int[roiW];
        int[] yellowCounts = new int[roiW];
        
        int greenTop = roiH, greenBottom = -1;
        int yellowTop = roiH, yellowBottom = -1;
        
        boolean printedSample = false;
        int sampleRow = roiH / 2;
        
        for (int rx = 0; rx < roiW; rx++) {
            int x = scanRect.left + rx;
            for (int ry = 0; ry < roiH; ry++) {
                int y = scanRect.top + ry;
                int pixel = pixels[y * w + x];
                
                if (isGreen(pixel)) {
                    greenCounts[rx]++;
                    if (ry < greenTop) greenTop = ry;
                    if (ry > greenBottom) greenBottom = ry;
                }
                
                if (isYellow(pixel)) {
                    yellowCounts[rx]++;
                    if (ry < yellowTop) yellowTop = ry;
                    if (ry > yellowBottom) yellowBottom = ry;
                }
            }
        }
        
        result.greenTop = greenTop;
        result.greenBottom = greenBottom;
        
        int minBarHeight = Math.max(2, roiH / 20);
        int greenThreshold = minBarHeight;
        int yellowThreshold = minBarHeight;
        
        int greenLeft = -1, greenRight = -1;
        for (int rx = 0; rx < roiW; rx++) {
            if (greenCounts[rx] >= greenThreshold) {
                if (greenLeft < 0) greenLeft = rx;
                greenRight = rx;
            }
        }
        
        int yellowLeft = -1, yellowRight = -1;
        for (int rx = 0; rx < roiW; rx++) {
            if (yellowCounts[rx] >= yellowThreshold) {
                if (yellowLeft < 0) yellowLeft = rx;
                yellowRight = rx;
            }
        }
        
        if (greenLeft >= 0 && greenRight >= 0) {
            int greenWidth = greenRight - greenLeft + 1;
            if (greenWidth >= 3) {
                result.greenLeft = greenLeft;
                result.greenRight = greenRight;
                result.greenWidth = greenWidth;
                result.greenCenter = (greenLeft + greenRight) / 2;
            }
        }
        
        if (yellowLeft >= 0 && yellowRight >= 0) {
            int yellowWidth = yellowRight - yellowLeft + 1;
            if (yellowWidth >= 1) {
                result.yellowX = (yellowLeft + yellowRight) / 2;
            }
        }
        
        Log.d(TAG, "Detection: green=" + (result.greenLeft >= 0 ? "[" + result.greenLeft + "-" + result.greenRight + "]" : "none") 
                + " yellow=" + (result.yellowX >= 0 ? result.yellowX : "none"));
        
        return result;
    }
}
