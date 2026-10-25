package com.ansub.tvhome;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public class MainActivity extends Activity {
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList(
            "com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher"
    );
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final String ADD = "add";
    private static final String[] EDIT_ITEMS = {"Move", "Replace", "Remove from home", "Uninstall"};
    private static final String[] EDIT_ITEMS_SYSTEM = {"Move", "Replace", "Remove from home", "App info"};

    // Reference geometry (1920x1080 design space, scaled by scale = screenWidthPx / 1920f)
    // On 1280x720 (scale = 2/3):
    // CARD_W = 312 -> px(312) = 208px
    // CARD_H = 176 -> px(176) = 117px (exact 16:9 widescreen)
    // SPACING = 40 -> px(40) = 27px (identical horizontal gap between cards)
    // SAFE_X = 100 -> px(100) = 67px (centered side padding for cards)
    // SAFE_Y = 60 -> px(60) = 40px (clock safe area top margin)
    // PANEL_PAD = 42 -> px(42) = 28px (dock inner padding: 16px clear space on 1.12x focus scale)
    // DOCK: dockX = px(100 - 42) = 39px, dockW = 1203px -> right margin = 1280 - (39 + 1203) = 38px!
    // DOCK_OUTER_GAP = 72 -> px(72) = 48px (clear vertical gap between dock border and neighbouring rows)
    // ROW_STEP = CARD_H + PANEL_PAD + DOCK_OUTER_GAP = 290 -> px(290) = 193px
    // LAST_ROW_BOTTOM_PAD = 96 -> px(96) = 64px (bottom padding on last row)
    // ROW0_TOP = 720 -> px(720) = 480px (Row 1 peeks ~50px at bottom of 720p screen)
    private static final int SAFE_X = 100;
    private static final int SAFE_Y = 60;
    private static final int PER_ROW = 5;
    private static final int CARD_W = 312;
    private static final int CARD_H = 176;
    private static final int SPACING = 40;
    private static final int RADIUS = 26;
    private static final int PANEL_PAD = 42; // Generous dock padding (at least 16px clear on focus)
    private static final int DOCK_OUTER_GAP = 72; // Exact 48px gap to neighbouring rows in 720p
    private static final int ROW_STEP = CARD_H + PANEL_PAD + DOCK_OUTER_GAP;
    private static final int LAST_ROW_BOTTOM_PAD = 96;
    private static final int ROW0_TOP = 720;
    private static final int MAX_INPUTS = 4;

    private static final String QUICK = "quick:";
    private static final String[] QUICK_NAMES = {"bluetooth", "sound", "picture"};
    private static final String TCL_SHOW_WINDOW = "com.tcl.settings.SHOW_WINDOW";

    // INFERRED: Apple does not publish tvOS animation curves. Closest documented equivalents:
    // APPLE_EASE = Apple's standard ease (cubic-bezier(0.25, 0.1, 0.25, 1)), used for crossfades
    // and unfocus; FOCUS_SPRING = a gentle spring-like settle for the focus scale-up.
    private static final Interpolator APPLE_EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    private static final Interpolator FOCUS_SPRING = new OvershootInterpolator(0.7f);

    private float scale = 1f;
    private static Typeface regular, semibold;

    private ImageView sharpBg;
    private ImageView blurredBg;
    private View dimOverlay;
    private ScrollView scroller;
    private FrameLayout root;
    private FrameLayout dockView;
    private View timePill;
    private int tileW, tileH;
    private int currentFocusRow = 0;
    private int focusedWrapIndex = -1;
    private ObjectAnimator scrollAnimator;

    private final List<View> tiles = new ArrayList<>();
    private boolean dirty = true;
    private java.util.Locale shownLocale;
    private String focusedKey;
    private final List<String> rowApps = new ArrayList<>();
    private String movingKey;
    private TextView moveHint;
    private boolean longPressed;
    private String pressedKey;
    private long lastResumeTime;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            refreshApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        loadFonts();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        // Warm gradient, not solid black: no black flash before the wallpaper layers draw.
        getWindow().setBackgroundDrawable(background());
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        // Layer 0: Sharp wallpaper
        sharpBg = new ImageView(this);
        sharpBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        sharpBg.setImageDrawable(loadSharpWallpaper());

        // Layer 1: Pre-computed blurred wallpaper (initial alpha 0.0f)
        blurredBg = new ImageView(this);
        blurredBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blurredBg.setImageDrawable(loadBlurredWallpaper());
        blurredBg.setAlpha(0.0f);

        // Layer 2: Black dim overlay (initial alpha 0.0f)
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0.0f);

        // Layer 3: Smooth 60fps Scroller with viewport clipping for GPU culling
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setVerticalFadingEdgeEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(true);
        scroller.setClipToPadding(true);

        root = new FrameLayout(this) {
            {
                setChildrenDrawingOrderEnabled(true);
            }
            @Override
            protected int getChildDrawingOrder(int childCount, int i) {
                if (focusedWrapIndex >= 0 && focusedWrapIndex < childCount) {
                    if (i == childCount - 1) return focusedWrapIndex;
                    if (i >= focusedWrapIndex) return i + 1;
                }
                return i;
            }
        };
        root.setClipChildren(false);
        root.setClipToPadding(false);
        scroller.addView(root, new FrameLayout.LayoutParams(-1, -2));

        shownLocale = java.util.Locale.getDefault();

        moveHint = text(px(24), 0xA8FFFFFF, regular);
        moveHint.setText(Strings.t("Use the arrows to move it, then press OK"));
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, at(px(SAFE_X), px(ROW0_TOP - 64), -2, -2));

        // Root container holding wallpaper layers, scroller, and fixed clock pill
        FrameLayout mainContainer = new FrameLayout(this);
        mainContainer.setClipChildren(false);
        mainContainer.setClipToPadding(false);
        mainContainer.addView(sharpBg, new FrameLayout.LayoutParams(-1, -1));
        mainContainer.addView(blurredBg, new FrameLayout.LayoutParams(-1, -1));
        mainContainer.addView(dimOverlay, new FrameLayout.LayoutParams(-1, -1));

        mainContainer.addView(scroller, new FrameLayout.LayoutParams(-1, -1));

        // Layer 4: Fixed top-right clock pill (strictly outside scroller, fades out when scrolled down)
        timePill = createTimePill();
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(-2, -2);
        pillLp.gravity = Gravity.TOP | Gravity.END;
        pillLp.topMargin = px(SAFE_Y);
        pillLp.rightMargin = px(SAFE_X);
        mainContainer.addView(timePill, pillLp);

        setContentView(mainContainer);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        lastResumeTime = SystemClock.uptimeMillis();
        pressedKey = null;
        longPressed = false;
        setIntent(intent);
        saveConfig(intent);
        buildTiles();
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
        if (scroller != null) scroller.smoothScrollTo(0, 0);
        updateBackgroundForFocus(0);
    }

    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        String quick = intent == null ? null : intent.getStringExtra("quick");
        if (apps == null && quick == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        SharedPreferences.Editor e = prefs.edit();
        if (stamp != null) e.putString("stamp", stamp);
        if (apps != null) {
            apps = apps.trim();
            if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
            else e.putString("apps", apps);
        }
        if (quick != null) {
            quick = quick.trim();
            if (quick.isEmpty() || quick.equalsIgnoreCase("none")) e.remove("quick");
            else e.putString("quick", quick);
        }
        e.apply();
        dirty = true;
        return true;
    }

    private List<String> appList(PackageManager pm) {
        List<String> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("apps", null);
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        for (String p : wanted) if (!p.isEmpty() && !out.contains(p) && launchIntent(pm, p) != null) out.add(p);
        if (saved == null) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (out.size() >= 15) break;
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (stockSystem || out.contains(p) || NOT_APPS.contains(p) || p.equals(getPackageName())) continue;
                out.add(p);
            }
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastResumeTime = SystemClock.uptimeMillis();
        pressedKey = null;
        longPressed = false;
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pressedKey = null;
        longPressed = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        pressedKey = null;
        longPressed = false;
        if (hasFocus) {
            lastResumeTime = SystemClock.uptimeMillis();
            refreshApps();
        }
    }

    private void refreshApps() {
        if (!dirty || !hasWindowFocus() || movingKey != null) return;
        dirty = false;
        buildTiles();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!java.util.Locale.getDefault().equals(shownLocale)) recreate();
    }

    @Override
    public void onBackPressed() { }

    private File sharpWallpaperFile() {
        File wp = new File(getFilesDir(), "wallpaper.jpg");
        if (!wp.exists()) wp = new File("/data/local/tmp/wallpaper.jpg");
        if (!wp.exists()) wp = new File("/data/local/tmp/wallpaper.png");
        return wp.exists() ? wp : null;
    }

    private Drawable decode(File f) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) return new BitmapDrawable(getResources(), bmp);
        } catch (Exception ignored) {}
        return null;
    }

    private Drawable loadSharpWallpaper() {
        File wp = sharpWallpaperFile();
        Drawable d = wp == null ? null : decode(wp);
        return d != null ? d : background();
    }

    private Drawable loadBlurredWallpaper() {
        File wp = new File(getFilesDir(), "wallpaper_blurred.jpg");
        if (!wp.exists()) wp = new File("/data/local/tmp/wallpaper_blurred.jpg");
        if (!wp.exists()) wp = new File("/data/wallpaper/wallpaper_blurred.jpg");
        File sharp = sharpWallpaperFile();
        // Use the pushed pre-blurred asset only when it is at least as new as the sharp wallpaper.
        if (wp.exists() && (sharp == null || wp.lastModified() >= sharp.lastModified())) {
            Drawable d = decode(wp);
            if (d != null) return d;
        }
        // Otherwise reuse a previously generated blur when it still matches the sharp wallpaper.
        File cache = new File(getFilesDir(), "wallpaper_blur_cache.jpg");
        if (cache.exists() && (sharp == null || cache.lastModified() >= sharp.lastModified())) {
            Drawable d = decode(cache);
            if (d != null) return d;
        }
        // No usable blur: make one once on a background thread at 1/8 scale and cache it.
        // Never live per-frame blur, never RenderEffect.
        generateBlurAsync(sharp, cache);
        // Until it is ready, mirror the sharp layer: the scroll-down crossfade then lands on a
        // dimmed wallpaper instead of solid black.
        return loadSharpWallpaper();
    }

    private volatile boolean blurWorking;

    private void generateBlurAsync(final File sharp, final File cache) {
        if (blurWorking) return;
        blurWorking = true;
        new Thread(() -> {
            Bitmap out = null;
            try {
                Bitmap src = null;
                if (sharp != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    src = BitmapFactory.decodeFile(sharp.getAbsolutePath(), o);
                }
                if (src == null) {
                    // No wallpaper file at all: blur the fallback gradient instead, so the
                    // scrolled background is still never solid black.
                    src = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(src);
                    Drawable g = background();
                    g.setBounds(0, 0, 480, 270);
                    g.draw(c);
                }
                int w = Math.max(1, src.getWidth() / 4), h = Math.max(1, src.getHeight() / 4);
                out = Bitmap.createScaledBitmap(src, w, h, true);
                if (out != src) src.recycle();
                blurInPlace(out, 12);
            } catch (Throwable ignored) {
            } finally {
                blurWorking = false;
            }
            if (out == null) return;
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(cache);
                out.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.close();
            } catch (Exception ignored) {}
            final Bitmap ready = out;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // Small bitmap; CENTER_CROP upscaling keeps it soft and costs almost nothing.
                blurredBg.setImageBitmap(ready);
                blurredBg.setAlpha(currentFocusRow == 0 ? 0f : 1f);
            });
        }).start();
    }

    // Three separable box-blur passes: a true Gaussian approximation, run once, never per frame.
    private static void blurInPlace(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlurPass(pix, w, h, radius, true);
            boxBlurPass(pix, w, h, radius, false);
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private static void boxBlurPass(int[] pix, int w, int h, int r, boolean horizontal) {
        int[] tmp = new int[pix.length];
        int div = 2 * r + 1;
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        for (int o = 0; o < outer; o++) {
            int base = horizontal ? o * w : o;
            int step = horizontal ? 1 : w;
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int i = -r; i <= r; i++) {
                int c = pix[base + clampIdx(i, inner) * step];
                sumA += (c >>> 24) & 0xFF; sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF; sumB += c & 0xFF;
            }
            for (int i = 0; i < inner; i++) {
                tmp[base + i * step] = ((int) (sumA / div) << 24) | ((int) (sumR / div) << 16)
                        | ((int) (sumG / div) << 8) | (int) (sumB / div);
                int add = pix[base + clampIdx(i + r + 1, inner) * step];
                int sub = pix[base + clampIdx(i - r, inner) * step];
                sumA += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                sumR += ((add >>> 16) & 0xFF) - ((sub >>> 16) & 0xFF);
                sumG += ((add >>> 8) & 0xFF) - ((sub >>> 8) & 0xFF);
                sumB += (add & 0xFF) - (sub & 0xFF);
            }
        }
        System.arraycopy(tmp, 0, pix, 0, pix.length);
    }

    private static int clampIdx(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }

    private Drawable background() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF15100F, 0xFF0A0809, 0xFF050405});
        GradientDrawable warm = radial(0x36FF8048, 0.08f, -0.12f, px(1250));
        GradientDrawable plum = radial(0x2EAA3C8C, 1.0f, 1.1f, px(1000));
        for (GradientDrawable g : new GradientDrawable[] {base, warm, plum}) g.setDither(true);
        return new LayerDrawable(new Drawable[] {base, warm, plum});
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    private void updateBackgroundForFocus(int row) {
        if (row == currentFocusRow) return;
        currentFocusRow = row;
        boolean isDock = (row == 0);

        // Hide time clock pill when scrolled down; show only on Row 0 (Top Shelf / Dock)
        if (timePill != null) {
            timePill.animate()
                    .alpha(isDock ? 1.0f : 0.0f)
                    .setDuration(200)
                    .setInterpolator(APPLE_EASE)
                    .start();
        }

        if (dockView != null) {
            int targetDockY = px(ROW0_TOP + row * ROW_STEP - PANEL_PAD);
            dockView.animate()
                    .y(targetDockY)
                    .setDuration(200)
                    .setInterpolator(APPLE_EASE)
                    .start();
        }

        sharpBg.animate()
                .alpha(isDock ? 1.0f : 0.0f)
                .setDuration(200)
                .setInterpolator(APPLE_EASE)
                .start();

        blurredBg.animate()
                .alpha(isDock ? 0.0f : 1.0f)
                .setDuration(200)
                .setInterpolator(APPLE_EASE)
                .start();

        dimOverlay.animate()
                .alpha(isDock ? 0.0f : 0.25f)
                .setDuration(200)
                .setInterpolator(APPLE_EASE)
                .start();
    }

    private void animateScrollTo(int targetY) {
        if (scroller == null) return;
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }
        int startY = scroller.getScrollY();
        if (startY == targetY) return;
        scrollAnimator = ObjectAnimator.ofInt(scroller, "scrollY", startY, targetY);
        scrollAnimator.setDuration(200);
        scrollAnimator.setInterpolator(APPLE_EASE);
        scrollAnimator.start();
    }

    private void buildTiles() {
        String want = focusedKey;
        int wantAt = rowApps.indexOf(want);
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View t : tiles) {
            View wrap = (View) t.getParent();
            ((ViewGroup) wrap.getParent()).removeView(wrap);
        }
        tiles.clear();
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        PackageManager pm = getPackageManager();
        int w = px(CARD_W);
        int h = px(CARD_H);
        tileW = w;
        tileH = h;

        List<String> apps = appList(pm);

        if (dockView != null) {
            root.removeView(dockView);
            dockView = null;
        }

        // Apple TV Frosted Glass Panel behind focused row (5 apps with generous padding)
        int dockPad = px(PANEL_PAD);
        int dockW = px(PER_ROW * CARD_W + (PER_ROW - 1) * SPACING + 2 * PANEL_PAD);
        int dockH = px(CARD_H + 2 * PANEL_PAD);
        int dockX = px(SAFE_X - PANEL_PAD);
        int dockY = px(ROW0_TOP - PANEL_PAD);

        dockView = new FrameLayout(this);
        dockView.setClipChildren(false);
        dockView.setClipToPadding(false);
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setCornerRadius(px(34)); // Smooth rounded dock curve
        dockBg.setColor(0x1FFFFFFF); // 0.12 white fill
        dockBg.setStroke(Math.max(1, px(1)), 0x33FFFFFF); // 0.20 white stroke
        dockBg.setDither(true);
        dockView.setBackground(dockBg);
        root.addView(dockView, at(dockX, dockY, dockW, dockH));

        // Place all streaming apps in 5-column grid
        for (int i = 0; i < apps.size(); i++) {
            String pkg = apps.get(i);
            Intent li = launchIntent(pm, pkg);
            if (li == null) continue;

            // Optional layered card assets: <pkg>.bg.png (full-bleed background layer) and
            // <pkg>.logo.png (transparent logo layer, floats forward on focus). If present,
            // they take priority over the single flat banner; logo mapping is untouched.
            Drawable logoLayer = null, bgLayer = null;
            File customLogo = new File(getFilesDir(), "banners/" + pkg + ".logo.png");
            if (!customLogo.exists()) customLogo = new File("/data/local/tmp/banners/" + pkg + ".logo.png");
            if (customLogo.exists()) logoLayer = decode(customLogo);
            File customBg = new File(getFilesDir(), "banners/" + pkg + ".bg.png");
            if (!customBg.exists()) customBg = new File("/data/local/tmp/banners/" + pkg + ".bg.png");
            if (customBg.exists()) bgLayer = decode(customBg);

            Drawable banner = null;
            File customBanner = new File(getFilesDir(), "banners/" + pkg + ".png");
            if (!customBanner.exists()) customBanner = new File("/data/local/tmp/banners/" + pkg + ".png");
            if (!customBanner.exists()) customBanner = new File("/data/local/tmp/banners/" + pkg + ".jpg");
            if (customBanner.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(customBanner.getAbsolutePath());
                    if (bmp != null) banner = new BitmapDrawable(getResources(), bmp);
                } catch (Exception ignored) {}
            }
            if (banner == null) {
                try {
                    banner = pm.getActivityBanner(li);
                    if (banner == null) banner = pm.getApplicationBanner(pkg);
                } catch (Exception ignored) {}
            }
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            Drawable icon = null;
            if (banner == null) try { icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}

            int appIndex = rowApps.size();
            rowApps.add(pkg);

            int row = appIndex / PER_ROW;
            int col = appIndex % PER_ROW;
            int x = px(SAFE_X + col * (CARD_W + SPACING));
            int y = px(ROW0_TOP + row * ROW_STEP);
            boolean isLastInRow = (col == PER_ROW - 1) || (i == apps.size() - 1);

            addTile(root, pkg, label, banner, null, icon, logoLayer, bgLayer, x, y, w, h, row, col, isLastInRow, false);
        }

        // Last Row: Exactly two tiles: "Settings" and "Add apps"
        int lastRowIndex = (rowApps.size() + PER_ROW - 1) / PER_ROW;
        int lastRowY = px(ROW0_TOP + lastRowIndex * ROW_STEP);
        int xSettings = px(SAFE_X + 0 * (CARD_W + SPACING));
        int xAdd = px(SAFE_X + 1 * (CARD_W + SPACING));

        addTile(root, SETTINGS, Strings.t("Settings"), null, LineIcon.SETTINGS, null, null, null, xSettings, lastRowY, w, h, lastRowIndex, 0, false, true);
        addTile(root, ADD, Strings.t("Add app"), null, LineIcon.PLUS, null, null, null, xAdd, lastRowY, w, h, lastRowIndex, 1, true, true);

        int totalH = lastRowY + h + px(LAST_ROW_BOTTOM_PAD);
        root.setMinimumHeight(totalH);

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        if (wantAt >= 0 && !rowApps.contains(want))
            want = rowApps.isEmpty() ? ADD : rowApps.get(Math.min(wantAt, rowApps.size() - 1));
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore != null) {
            restore.requestFocus();
        } else if (!tiles.isEmpty()) {
            tiles.get(0).requestFocus();
        }
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         Drawable logoLayer, Drawable bgLayer,
                         int x, int y, int w, int h, final int row, final int col, final boolean isLastInRow, final boolean isLastRow) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));

        ImageView iv = null; // the card's content layer (logo, banner, line icon or app icon)
        final boolean isGlass = (banner == null && logoLayer == null);
        if (logoLayer != null) {
            // tvOS-style layered card: background layer, then a centered logo layer that the
            // focus parallax floats slightly forward. Background stays put = depth.
            if (bgLayer != null) {
                bg.setColor(0xFF14151B);
                ImageView bgv = new ImageView(this);
                bgv.setScaleType(ImageView.ScaleType.FIT_XY);
                bgv.setImageDrawable(fit(bgLayer, w, h));
                card.addView(bgv, new FrameLayout.LayoutParams(-1, -1));
            } else {
                bg.setColor(0xFF1B1E28);
                bg.setStroke(Math.max(1, px(1)), 0x22FFFFFF);
            }
            iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(logoLayer);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            lp.setMargins(Math.round(w * 0.18f), Math.round(h * 0.20f),
                    Math.round(w * 0.18f), Math.round(h * 0.20f));
            card.addView(iv, lp);
        } else if (banner != null) {
            bg.setColor(0xFF14151B);
            iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(fit(banner, w, h));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else if (lineIcon != null) {
            // Line icon tile (Settings / Add apps)
            bg.setColor(0x26FFFFFF); // Frosted fill for distinct contrast
            bg.setStroke(Math.max(1, px(1)), 0x40FFFFFF); // Crisp stroke
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            iv = new ImageView(this);
            int size = px(44);
            iv.setImageDrawable(new LineIcon(lineIcon));
            inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // App without banner (e.g. 3-dot App Drawer): centered uncropped logo at 60% height with inner padding
            bg.setColor(0xFF1B1E28);
            bg.setStroke(Math.max(1, px(1)), 0x22FFFFFF);
            iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize = Math.round(h * 0.60f); // Exactly 60% of card height
            if (appIcon != null) iv.setImageDrawable(fitCenter(appIcon, iconSize, iconSize));
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
            card.addView(iv, ip);
        }
        final ImageView content = iv;

        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);
        card.setAlpha(1.0f); // Always 100% vibrant, never faint!

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        // App title caption label below card: fades in only on focus
        final TextView caption = text(px(20), 0xE6FFFFFF, semibold != null ? semibold : regular);
        caption.setText(label);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setAlpha(0.0f); // Invisible when unfocused
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w + px(40), -2);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.topMargin = h + px(10);
        wrap.addView(caption, clp);

        card.setOnFocusChangeListener((v, has) -> {
            if (has) {
                focusedKey = key;
                focusedWrapIndex = root.indexOfChild(wrap);
                wrap.setTranslationZ(px(10));

                int screenH = getResources().getDisplayMetrics().heightPixels;
                int targetY = (row == 0) ? 0 : Math.max(0, y - Math.round(screenH * 0.42f) + h / 2);
                animateScrollTo(targetY);

                updateBackgroundForFocus(row);
            } else {
                wrap.setTranslationZ(0);
            }

            // Caption label fades in only on focus (snappy 160ms)
            caption.animate()
                    .alpha(has ? 1.0f : 0.0f)
                    .setDuration(has ? 180 : 140)
                    .setInterpolator(APPLE_EASE)
                    .start();

            // Refined glass border on focus
            if (isGlass) {
                bg.setColor(has ? 0x40FFFFFF : 0x26FFFFFF);
                bg.setStroke(Math.max(1, px(1)), has ? 0x59FFFFFF : 0x40FFFFFF);
            } else {
                bg.setStroke(0, Color.TRANSPARENT);
            }

            // 120fps butter-smooth Apple spring scale: pure 2D GPU affine transform
            v.animate()
                    .scaleX(has ? 1.14f : 1.0f)
                    .scaleY(has ? 1.14f : 1.0f)
                    .setInterpolator(has ? FOCUS_SPRING : APPLE_EASE)
                    .setDuration(has ? 200 : 160)
                    .start();

            // Layered depth: the floating logo lifts slightly forward on focus
            if (content != null) {
                content.animate()
                        .scaleX(has ? 1.05f : 1.0f)
                        .scaleY(has ? 1.05f : 1.0f)
                        .translationY(has ? px(-2) : 0)
                        .setInterpolator(has ? FOCUS_SPRING : APPLE_EASE)
                        .setDuration(has ? 200 : 160)
                        .start();
            }

            v.setAlpha(1.0f); // Ensure 100% opacity remains stable
        });

        final boolean editable = rowApps.contains(key);
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        card.setOnKeyListener((v, code, ev) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (key.equals(movingKey)) return moveKey(key, code, ok, down);
            if (editable && code == KeyEvent.KEYCODE_MENU) {
                if (!down) showEditMenu(key);
                return true;
            }

            // Strict tvOS focus boundary rules:
            if (down) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) return true;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && isLastInRow) return true;
                if (code == KeyEvent.KEYCODE_DPAD_UP && row == 0) return true; // Keep focus in dock
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && isLastRow) return true; // Keep focus in last row
            }

            if (!ok) return false;
            if (down) {
                if (ev.getRepeatCount() == 0) {
                    pressedKey = key;
                    longPressed = false;
                } else if (editable && !longPressed && (ev.isLongPress()
                        || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                    longPressed = true;
                    pressedKey = null;
                    showEditMenu(key);
                }
            } else {
                if (key.equals(pressedKey) && !longPressed) {
                    open(key);
                }
                pressedKey = null;
                longPressed = false;
            }
            return true;
        });

        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        parent.addView(wrap, at(x, y, w, h));
        tiles.add(card);
    }

    private void open(String key) {
        if (SystemClock.uptimeMillis() - lastResumeTime < 350) {
            return;
        }
        Intent i;
        if (ADD.equals(key)) {
            showAddMenu(-1);
            return;
        } else if (SETTINGS.equals(key)) {
            i = new Intent(Settings.ACTION_SETTINGS);
        } else if (isInput(key)) {
            openInput(key);
            return;
        } else if (key.startsWith(QUICK)) {
            openQuick(key.substring(QUICK.length()));
            return;
        } else {
            i = launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";

    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        ResolveInfo def = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && INPUT_PLAYER.equals(def.activityInfo.packageName))
            tries.add(new Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name));
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
    }

    private List<Intent> quickIntents(String name) {
        List<Intent> all = new ArrayList<>();
        String tclType = name.equals("sound") ? "SoundPreset" : name.equals("picture") ? "picture" : null;
        if (tclType != null)
            all.add(new Intent(TCL_SHOW_WINDOW).setClassName("com.tcl.settings", "com.tcl.settings.ShowWindowService")
                    .putExtra("Type", tclType));
        switch (name) {
            case "bluetooth":
                all.add(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                all.add(new Intent("com.google.android.intent.action.CONNECT_INPUT"));
                break;
            case "sound": all.add(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
            case "picture": all.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); break;
        }
        PackageManager pm = getPackageManager();
        List<Intent> out = new ArrayList<>();
        for (Intent i : all) {
            boolean service = TCL_SHOW_WINDOW.equals(i.getAction());
            if (service ? pm.resolveService(i, 0) != null : i.resolveActivity(pm) != null) out.add(i);
        }
        return out;
    }

    private void openQuick(String name) {
        for (Intent i : quickIntents(name)) {
            try {
                if (TCL_SHOW_WINDOW.equals(i.getAction())) startService(i);
                else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    interface Pick { void at(int which); }

    private void panel(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(92), px(48), px(48));

        TextView head = text(px(40), Color.WHITE, semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(px(24), 0, px(24), 0);
        box.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);

        final PackageManager pm = getPackageManager();
        final int size = px(52); // Calibrated between px(48) and px(56)
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : text(px(30), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setPadding(px(24), 0, px(24), 0);
                Drawable icon = null;
                if (iconPkgs != null) {
                    if (icons[pos] == null) {
                        try { icons[pos] = fit(pm.getApplicationIcon(iconPkgs.get(pos)), size, size); }
                        catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                        icons[pos].setBounds(0, 0, size, size);
                    }
                    icon = icons[pos];
                }
                t.setCompoundDrawablesRelative(icon, null, null, null);
                t.setCompoundDrawablePadding(px(24));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        w.setLayout(px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(0.5f);
        d.show();
    }

    private void dropdown(View anchor, String[] items, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(10), rowH = px(64);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        box.setBackground(bg);
        float widest = 0;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final TextView t = text(px(26), 0xD9FFFFFF, regular);
            t.setText(Strings.t(items[i]));
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            widest = Math.max(widest, t.getPaint().measureText(t.getText().toString()) + 2 * px(22) + 2 * pad);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(px(RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(box);

        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int w = Math.max(Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), px(320)), (int) Math.ceil(widest));
        int h = items.length * rowH + 2 * pad;
        int y = tileBottom + px(64);
        if (y + h > getResources().getDisplayMetrics().heightPixels - px(24)) y = at[1] - h - px(16);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = at[0];
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        d.show();
    }

    private void showEditMenu(final String key) {
        final int index = rowApps.indexOf(key);
        if (index < 0) return;
        final boolean user = userInstalled(key);
        dropdown(tile(key), user ? EDIT_ITEMS : EDIT_ITEMS_SYSTEM, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) showAddMenu(index);
            else if (which == 3) {
                Uri pkg = Uri.parse("package:" + key);
                try { startActivity(user ? new Intent(Intent.ACTION_DELETE, pkg)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); }
                catch (Exception ignored) {}
            } else {
                rowApps.remove(index);
                saveRow();
                focusedKey = rowApps.isEmpty() ? ADD : rowApps.get(Math.min(index, rowApps.size() - 1));
                buildTiles();
            }
        });
    }

    private void showAddMenu(final int replaceIndex) {
        final PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (rowApps.contains(p) || byLabel.containsValue(p) || p.equals(getPackageName())
                        || (NOT_APPS.contains(p) && !p.equals(PLAY_STORE))) continue;
                String label = labelOf(p);
                if (!byLabel.containsKey(label)) byLabel.put(label, p);
            }
        }
        if (byLabel.isEmpty()) {
            panel(Strings.t("Every app is already here"), Collections.singletonList("OK"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>(byLabel.values());
        String title = replaceIndex < 0 ? Strings.t("Add an app")
                : String.format(Strings.t("Replace %s"), labelOf(rowApps.get(replaceIndex)));
        panel(title, new ArrayList<>(byLabel.keySet()), pkgs, which -> {
            if (replaceIndex < 0) rowApps.add(pkgs.get(which));
            else rowApps.set(replaceIndex, pkgs.get(which));
            saveRow();
            focusedKey = pkgs.get(which);
            buildTiles();
        });
    }

    private String labelOf(String pkg) {
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    private void saveRow() {
        getSharedPreferences(PREFS, 0).edit().putString("apps", android.text.TextUtils.join(",", rowApps)).apply();
    }

    private int gridX(int i) {
        int col = i % PER_ROW;
        return px(SAFE_X + col * (CARD_W + SPACING));
    }

    private int gridY(int i) {
        int row = i / PER_ROW;
        return px(ROW0_TOP + row * ROW_STEP);
    }

    private View tile(String key) {
        for (View t : tiles) if (t.getTag().equals(key)) return t;
        return null;
    }

    private void startMove(String key) {
        View card = tile(key);
        if (card == null) return;
        movingKey = key;
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(RADIUS));
        outline.setStroke(px(4), Color.WHITE);
        ((FrameLayout) card).setForeground(outline);
        moveHint.setVisibility(View.VISIBLE);
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -PER_ROW : code == KeyEvent.KEYCODE_DPAD_DOWN ? PER_ROW : 0;
        if (step != 0) {
            int from = rowApps.indexOf(key), to = from + step;
            if (down && to >= 0 && to < rowApps.size()) {
                rowApps.add(to, rowApps.remove(from));
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    View wrap = (View) tile(rowApps.get(i)).getParent();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
                    lp.leftMargin = gridX(i);
                    lp.topMargin = gridY(i);
                    wrap.setLayoutParams(lp);
                }
                final View card = tile(key);
                card.post(() -> card.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight())));
            }
            return true;
        }
        if (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                movingKey = null;
                longPressed = false;
                ((FrameLayout) tile(key)).setForeground(null);
                moveHint.setVisibility(View.GONE);
                saveRow();
                refreshApps();
            }
            return true;
        }
        return true;
    }

    private static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    private boolean userInstalled(String pkg) {
        try { return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    private Drawable fit(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    private Drawable fitCenter(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            // Scale to fit completely inside with 15% inner padding so it is never cropped
            float s = Math.min((float) w / iw, (float) h / ih) * 0.85f;
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    private TextClock clock(String f12, String f24, int px, int color, Typeface tf) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(int px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private View createTimePill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(px(22), px(8), px(10), px(8));

        // Authentic Apple tvOS Liquid Glass Material
        final GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x544E5262, 0x402A2D3A, 0x52181A24 }
        );
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(px(28)); // Smooth capsule geometry
        bg.setStroke(Math.max(1, px(1)), 0x59FFFFFF); // Specular crisp glass edge
        pill.setBackground(bg);

        // Time Clock in Apple SF Pro Display Semibold
        final TextClock tc = clock("h:mm a", "H:mm", px(30), 0xF8FFFFFF, semibold != null ? semibold : regular);
        tc.setLetterSpacing(0.01f);
        tc.setFontFeatureSettings("tnum");
        tc.setIncludeFontPadding(false);
        pill.addView(tc);

        // Profile Avatar with Apple-calibrated dimensions
        int avatarSize = px(38);
        ImageView avatarIv = new ImageView(this);
        avatarIv.setScaleType(ImageView.ScaleType.FIT_CENTER);

        Drawable avatarDrawable = loadAvatar(avatarSize);
        if (avatarDrawable != null) avatarIv.setImageDrawable(avatarDrawable);

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        ap.leftMargin = px(14);
        pill.addView(avatarIv, ap);

        // Fixed element outside scroller; does not steal D-pad focus from apps
        pill.setFocusable(false);
        pill.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        });

        return pill;
    }

    private Drawable loadAvatar(int size) {
        File f = new File(getFilesDir(), "avatar.png");
        if (!f.exists()) f = new File("/data/local/tmp/avatar.png");
        if (!f.exists()) f = new File("/data/wallpaper/avatar.png");
        if (!f.exists()) f = new File("/data/local/tmp/avatar.jpg");
        if (f.exists()) {
            try {
                Bitmap raw = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (raw != null) {
                    Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(out);
                    android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                    float r = size / 2f;

                    // High-quality center-cropped circular shader
                    android.graphics.BitmapShader shader = new android.graphics.BitmapShader(raw, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    float s = Math.max((float) size / raw.getWidth(), (float) size / raw.getHeight());
                    float dx = (size - raw.getWidth() * s) * 0.5f;
                    float dy = (size - raw.getHeight() * s) * 0.5f;
                    m.setScale(s, s);
                    m.postTranslate(dx, dy);
                    shader.setLocalMatrix(m);
                    p.setShader(shader);
                    c.drawCircle(r, r, r - 1.2f, p);

                    // Refined Apple white glass ring
                    p.setShader(null);
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1.5f, size / 20f));
                    p.setColor(0xE6FFFFFF);
                    c.drawCircle(r, r, r - p.getStrokeWidth() / 2f, p);

                    return new BitmapDrawable(getResources(), out);
                }
            } catch (Exception ignored) {}
        }

        // Apple TV profile avatar fallback
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;

        p.setColor(0xFF2C2C2E);
        c.drawCircle(r, r, r - 1, p);

        p.setColor(0xFF8E8E93);
        c.drawCircle(r, r * 0.72f, r * 0.35f, p);

        android.graphics.RectF body = new android.graphics.RectF(r * 0.3f, r * 1.2f, r * 1.7f, r * 2.0f);
        c.drawRoundRect(body, r * 0.35f, r * 0.35f, p);

        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, size / 16f));
        p.setColor(0xFFFFFFFF);
        c.drawCircle(r, r, r - p.getStrokeWidth() / 2f, p);

        return new BitmapDrawable(getResources(), bmp);
    }
}
