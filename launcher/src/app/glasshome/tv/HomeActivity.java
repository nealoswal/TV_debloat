package app.glasshome.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tvOS-style home screen: a full-bleed photo wallpaper, a greeting clock, frosted-glass
 * controls and a frosted dock of favourite apps, with every other app in a grid below.
 */
public class HomeActivity extends Activity {

    static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "com.sonyliv",
            "com.graymatrix.did",
            "com.spotify.tv.android",
    };
    static final String SETTINGS_KEY = "#settings";
    static final int COLS = 5;
    static final int TILE_W = 150, TILE_H = 90, GAP = 23;
    static final int EDGE = 40;                  // screen edge to the dock's glass
    static final int PANEL_PAD = 18;             // dock glass edge to its tiles
    static final int SIDE = EDGE + PANEL_PAD;    // screen edge to the first tile column
    static final int PANEL_H = TILE_H + PANEL_PAD * 2;
    static final int BOTTOM = 34, NAME_H = 46;
    static final long WALL_ROTATE_MS = 15 * 60 * 1000L;
    static final long WALL_NEW_ON_RETURN_MS = 5 * 60 * 1000L;

    static class App {
        String key, label;
        Intent launch;
        Drawable banner, icon;
    }

    // ------------------------------------------------------------- helper classes
    // Static nested classes only: d8 in build-tools 34 can't read inner-class
    // constructors produced by recent javac versions.

    static class PkgReceiver extends BroadcastReceiver {
        final HomeActivity home;
        PkgReceiver(HomeActivity home) { this.home = home; }
        @Override public void onReceive(Context c, Intent i) {
            home.ui.removeCallbacks(home.rebuild);
            home.ui.postDelayed(home.rebuild, 600);
        }
    }

    /** Listens for Wi-Fi / connectivity changes; only registered while Home is visible. */
    static class NetReceiver extends BroadcastReceiver {
        final HomeActivity home;
        NetReceiver(HomeActivity home) { this.home = home; }
        @Override public void onReceive(Context c, Intent i) { home.updateNetwork(); }
    }

    /** Catches "connected but no internet" changes, which have no broadcast. */
    static class NetCallback extends ConnectivityManager.NetworkCallback {
        final HomeActivity home;
        NetCallback(HomeActivity home) { this.home = home; }
        @Override public void onAvailable(Network n) { home.ui.post(home::updateNetwork); }
        @Override public void onLost(Network n) { home.ui.post(home::updateNetwork); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { home.ui.post(home::updateNetwork); }
    }

    /** Vertical scroller that never scrolls on its own; focus handlers drive it. */
    static class ManualScrollView extends ScrollView {
        ManualScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
    }

    /** Dock scroller that never scrolls on its own; scrollDock() animates it column by column. */
    static class ManualHScrollView extends HorizontalScrollView {
        ManualHScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
    }

    static class RoundOutline extends ViewOutlineProvider {
        final float radius, alpha;
        RoundOutline(float radius) { this(radius, 1f); }
        RoundOutline(float radius, float alpha) { this.radius = radius; this.alpha = alpha; }
        @Override public void getOutline(View view, Outline o) {
            o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            o.setAlpha(alpha);
        }
    }

    /**
     * Full-screen photo wallpaper with cross-fades, parallax and a frosted mode. It also keeps
     * a tiny blurred copy of the photo that glass surfaces sample, so "live" frosted glass costs
     * one small bitmap draw instead of a real-time blur.
     */
    static class WallpaperView extends View {
        Bitmap cur, curBlur, next, nextBlur;
        float fade = 0f;          // how far `next` has faded in over `cur`
        float zoom = 1.07f, nextZoom = 1.07f;
        float px, py;             // parallax offset in pixels
        float frost;              // 0 = sharp photo, 1 = blurred and dimmed (grid mode)
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Matrix m = new Matrix();
        final List<View> glass = new ArrayList<>();

        WallpaperView(Context c) { super(c); }

        void matrixFor(Bitmap b, float z, Matrix out) {
            float s = Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight()) * z;
            out.setScale(s, s);
            out.postTranslate((getWidth() - b.getWidth() * s) / 2f + px, (getHeight() - b.getHeight() * s) / 2f + py);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF0B0B10);
            if (cur != null) layer(c, cur, curBlur, zoom, 255);
            if (next != null) layer(c, next, nextBlur, nextZoom, Math.round(255 * fade));
            if (frost > 0) c.drawColor(Color.argb(Math.round(frost * 110), 0, 0, 0));
        }

        private void layer(Canvas c, Bitmap sharp, Bitmap small, float z, int alpha) {
            if (frost < 1f) {
                paint.setAlpha(alpha);
                matrixFor(sharp, z, m);
                c.drawBitmap(sharp, m, paint);
            }
            if (frost > 0 && small != null) {
                paint.setAlpha(Math.round(alpha * frost));
                matrixFor(small, z, m);
                c.drawBitmap(small, m, paint);
            }
        }

        void changed() {
            invalidate();
            for (View g : glass) g.invalidate();
        }
    }

    /** Frosted glass: samples the wallpaper's blurred copy behind the host view. */
    static class GlassDrawable extends Drawable {
        final WallpaperView wall;
        final View host;
        final float radius, dp;
        final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix m = new Matrix();
        final RectF r = new RectF();
        final int[] loc = new int[2];
        Bitmap shaderFor, nextShaderFor;
        BitmapShader shader, nextShader;
        int tint = 0x1AFFFFFF, focusTint = 0xF5FFFFFF;
        boolean focused;
        int shadedH = -1;

        GlassDrawable(WallpaperView wall, View host, float radius, float dp) {
            this.wall = wall;
            this.host = host;
            this.radius = radius;
            this.dp = dp;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, 1.1f * dp));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            r.set(b);
            if (b.height() != shadedH) {
                shadedH = b.height();
                shine.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                        0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                        new int[]{0x99FFFFFF, 0x26FFFFFF, 0x40FFFFFF}, null, Shader.TileMode.CLAMP));
            }
            host.getLocationOnScreen(loc);
            if (wall.curBlur != null) {
                if (shaderFor != wall.curBlur) {
                    shaderFor = wall.curBlur;
                    shader = new BitmapShader(shaderFor, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                sample(c, shader, shaderFor, wall.zoom, 255);
            }
            if (wall.nextBlur != null && wall.fade > 0) {
                if (nextShaderFor != wall.nextBlur) {
                    nextShaderFor = wall.nextBlur;
                    nextShader = new BitmapShader(nextShaderFor, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                sample(c, nextShader, nextShaderFor, wall.nextZoom, Math.round(255 * wall.fade));
            }
            fill.setColor(focused ? focusTint : tint);
            c.drawRoundRect(r, radius, radius, fill);
            if (!focused) c.drawRoundRect(r, radius, radius, shine);
            r.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
            c.drawRoundRect(r, radius, radius, rim);
        }

        private void sample(Canvas c, BitmapShader s, Bitmap src, float zoom, int alpha) {
            wall.matrixFor(src, zoom, m);
            m.postTranslate(-loc[0], -loc[1]);
            s.setLocalMatrix(m);
            blurPaint.setShader(s);
            blurPaint.setAlpha(alpha);
            c.drawRoundRect(r, radius, radius, blurPaint);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Wi-Fi bars drawn in code: no bitmaps, recolours with the pill's focus tint. */
    static class NetGlyph extends Drawable {
        static final int WIFI = 0, NO_INTERNET = 1, ETHERNET = 2, OFFLINE = 3;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF arc = new RectF();
        int state = OFFLINE, level = 0;

        NetGlyph() { paint.setColor(Color.WHITE); paint.setStrokeCap(Paint.Cap.ROUND); }

        void set(int state, int level) {
            if (state == this.state && level == this.level) return;
            this.state = state;
            this.level = level;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            float u = getBounds().width() / 24f;
            c.save();
            c.translate(getBounds().left, getBounds().top);
            if (state == ETHERNET) {
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(9 * u, 3 * u, 15 * u, 8 * u, paint);
                c.drawRect(3 * u, 15 * u, 9 * u, 20 * u, paint);
                c.drawRect(15 * u, 15 * u, 21 * u, 20 * u, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f * u);
                c.drawLine(12 * u, 8 * u, 12 * u, 12 * u, paint);
                c.drawLine(6 * u, 12 * u, 18 * u, 12 * u, paint);
                c.drawLine(6 * u, 12 * u, 6 * u, 15 * u, paint);
                c.drawLine(18 * u, 12 * u, 18 * u, 15 * u, paint);
            } else {
                boolean off = state == OFFLINE;
                int lit = off ? -1 : level;           // 0 = dot only, 3 = all arcs
                float cx = 12 * u, cy = 19 * u;
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(lit >= 0 ? 255 : 80);
                c.drawCircle(cx, cy, 1.9f * u, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.3f * u);
                for (int i = 1; i <= 3; i++) {
                    float r = (1.9f + 4.3f * i) * u;
                    arc.set(cx - r, cy - r, cx + r, cy + r);
                    paint.setAlpha(i <= lit ? 255 : 80);
                    c.drawArc(arc, 225, 90, false, paint);
                }
                if (off) {
                    paint.setAlpha(255);
                    c.drawLine(4 * u, 4 * u, 20 * u, 20 * u, paint);
                } else if (state == NO_INTERNET) {
                    paint.setAlpha(255);
                    c.drawLine(20.5f * u, 9 * u, 20.5f * u, 15 * u, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(20.5f * u, 19 * u, 1.3f * u, paint);
                }
            }
            c.restore();
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------------- state

    final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private PackageManager pm;
    private float dp;

    private WallpaperView wall;
    private View header, topBar;
    private TextView greeting, dockName;
    private ScrollView scroller;
    private LinearLayout content;
    private ManualHScrollView dockScroll;
    private View dockPanel;

    private final List<App> dock = new ArrayList<>();
    private final List<App> others = new ArrayList<>();
    private String refocusKey;
    private boolean longFired;
    private boolean inGrid;
    private float lastFocusX = -1;
    private ValueAnimator dockAnim, parallaxAnim, frostAnim, wallAnim;

    private String[] walls = new String[0];
    private long wallShownAt;

    private final NetGlyph netGlyph = new NetGlyph();
    private View netPill;
    private NetReceiver netReceiver;
    private NetCallback netCallback;

    final Runnable rebuild = this::loadApps;
    private final BroadcastReceiver pkgReceiver = new PkgReceiver(this);
    private final Runnable minuteTick = this::onMinute;
    private final Runnable rotateWall = () -> nextWallpaper(true);

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pm = getPackageManager();
        dp = getResources().getDisplayMetrics().density;
        try {
            walls = getAssets().list("wall");
            Arrays.sort(walls);
        } catch (Exception ignored) { }
        setContentView(buildRoot());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
        showWallpaper(prefs.getInt("wallIdx", 0), false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        netReceiver = new NetReceiver(this);
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.RSSI_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(netReceiver, f);
        if (Build.VERSION.SDK_INT >= 24) {
            netCallback = new NetCallback(this);
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCallback);
        }
        updateNetwork();
        onMinute();
        // Coming back after a while gets a fresh photo, like a new day on Apple TV.
        if (wallShownAt != 0 && SystemClock.elapsedRealtime() - wallShownAt > WALL_NEW_ON_RETURN_MS) {
            nextWallpaper(true);
        } else {
            ui.removeCallbacks(rotateWall);
            ui.postDelayed(rotateWall, WALL_ROTATE_MS);
        }
    }

    @Override
    protected void onStop() {
        if (netReceiver != null) unregisterReceiver(netReceiver);
        netReceiver = null;
        if (netCallback != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        netCallback = null;
        ui.removeCallbacks(minuteTick);
        ui.removeCallbacks(rotateWall);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (hasWindowFocus()) goTop();
    }

    @Override
    public void onBackPressed() {
        goTop();
    }

    private void goTop() {
        LinearLayout dr = dockRow();
        if (dr != null && dr.getChildCount() > 0) dr.getChildAt(0).findViewWithTag("tile").requestFocus();
    }

    private void onMinute() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 21 ? "Good evening" : "Good night";
        greeting.setText(g.toUpperCase());
        ui.removeCallbacks(minuteTick);
        ui.postDelayed(minuteTick, 60_000);
    }

    // ------------------------------------------------------------- layout

    private int px(float v) { return Math.round(v * dp); }

    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        wall = new WallpaperView(this);
        root.addView(wall, match());

        // Soft vignettes keep the clock and dock legible on bright photos.
        View topScrim = new View(this);
        GradientDrawable tg = new GradientDrawable();
        tg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        tg.setGradientCenter(0f, 0f);
        tg.setGradientRadius(px(520));
        tg.setColors(new int[]{0x99000000, 0x40000000, 0x00000000});
        topScrim.setBackground(tg);
        root.addView(topScrim, match());
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x33000000, 0x00000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, px(260), Gravity.BOTTOM));

        header = buildHeader();
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2);
        hlp.leftMargin = px(EDGE + 4);
        hlp.topMargin = px(26);
        root.addView(header, hlp);

        scroller = new ManualScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (dockPanel != null) dockPanel.invalidate();
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(0, 0, 0, px(300));
        scroller.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroller, match());

        topBar = buildTopBar();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        tlp.topMargin = px(30);
        tlp.rightMargin = px(EDGE);
        root.addView(topBar, tlp);
        return root;
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);

        greeting = new TextView(this);
        greeting.setTextColor(0xD9FFFFFF);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        greeting.setLetterSpacing(0.24f);
        greeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        greeting.setShadowLayer(px(10), 0, px(1), 0x99000000);
        h.addView(greeting);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setLetterSpacing(-0.01f);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(px(18), 0, px(2), 0x80000000);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(2);
        h.addView(clock, clp);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setAllCaps(true);
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        date.setLetterSpacing(0.2f);
        date.setShadowLayer(px(10), 0, px(1), 0x99000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(4);
        h.addView(date, dlp);
        return h;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.addView(pill(getDrawable(R.drawable.ic_assistant), false, null, "Google Assistant", v -> launchSafe(
                new Intent("android.search.action.GLOBAL_SEARCH"), new Intent(Intent.ACTION_ASSIST))));
        bar.addView(pill(getDrawable(R.drawable.ic_input), true, "Inputs", "Inputs", v -> launchSafe(
                new Intent("com.android.tv.action.VIEW_INPUTS"), new Intent(Settings.ACTION_SETTINGS))));
        bar.addView(pill(getDrawable(R.drawable.ic_settings), true, "Settings", "Settings", v -> launchSafe(
                new Intent(Settings.ACTION_SETTINGS), null)));
        netPill = pill(netGlyph, true, null, "Network", v -> launchSafe(
                new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_SETTINGS)));
        bar.addView(netPill);

        // Keep left/right inside the bar, as tvOS does.
        View first = bar.getChildAt(0), last = bar.getChildAt(bar.getChildCount() - 1);
        first.setId(View.generateViewId());
        last.setId(View.generateViewId());
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
        return bar;
    }

    /** A frosted capsule (with label) or circle (icon only) in the top-right bar. */
    private View pill(Drawable icon, final boolean tint, String label, String desc, View.OnClickListener click) {
        final LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER);
        f.setFocusable(true);
        f.setContentDescription(desc);
        final GlassDrawable glass = new GlassDrawable(wall, f, px(18), dp);
        f.setBackground(glass);
        wall.glass.add(f);
        f.setOutlineProvider(new RoundOutline(px(18), 0.5f));
        f.setElevation(px(4));

        final ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        if (tint) iv.setColorFilter(Color.WHITE);
        f.addView(iv, new LinearLayout.LayoutParams(px(label == null ? 19 : 16), px(label == null ? 19 : 16)));

        final TextView tv;
        if (label != null) {
            tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(7);
            f.addView(tv, lp);
            f.setPadding(px(14), 0, px(16), 0);
        } else {
            tv = null;
        }

        f.setOnClickListener(click);
        f.setOnFocusChangeListener((v, has) -> {
            glass.focused = has;
            glass.invalidateSelf();
            int ink = has ? 0xFF1C1C1E : Color.WHITE;
            if (tint) iv.setColorFilter(ink);
            if (tv != null) tv.setTextColor(ink);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? px(10) : 0)
                    .setInterpolator(new OvershootInterpolator(1.8f)).setDuration(240).start();
            if (has) {
                scroller.smoothScrollTo(0, 0);
                setGridMode(false);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(label == null ? px(36) : -2, px(36));
        lp.leftMargin = px(10);
        f.setLayoutParams(lp);
        return f;
    }

    private void roundClip(View v, float radiusDp) {
        v.setOutlineProvider(new RoundOutline(radiusDp * dp));
        v.setClipToOutline(true);
    }

    // ------------------------------------------------------------- wallpaper

    private void nextWallpaper(boolean animate) {
        if (walls.length == 0) return;
        showWallpaper((prefs.getInt("wallIdx", 0) + 1) % walls.length, animate);
    }

    private void showWallpaper(final int idx, final boolean animate) {
        if (walls.length == 0) return;
        final int i = ((idx % walls.length) + walls.length) % walls.length;
        prefs.edit().putInt("wallIdx", i).apply();
        io.execute(() -> {
            Bitmap sharp = null, small = null;
            try (InputStream in = getAssets().open("wall/" + walls[i])) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                sharp = BitmapFactory.decodeStream(in, null, o);
                small = blurred(sharp);
            } catch (Exception ignored) { }
            if (sharp == null) return;
            final Bitmap s = sharp, sm = small;
            ui.post(() -> crossfadeTo(s, sm, animate));
        });
    }

    /** Slow "breathe in" cross-fade: the new photo fades in while settling from a deeper zoom. */
    private void crossfadeTo(final Bitmap sharp, final Bitmap small, boolean animate) {
        wallShownAt = SystemClock.elapsedRealtime();
        ui.removeCallbacks(rotateWall);
        ui.postDelayed(rotateWall, WALL_ROTATE_MS);
        if (wallAnim != null) wallAnim.end();
        wall.next = sharp;
        wall.nextBlur = small;
        wall.fade = 0f;
        wallAnim = ValueAnimator.ofFloat(0f, 1f);
        wallAnim.setDuration(animate ? 1600 : 700);
        wallAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        wallAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            wall.fade = t;
            wall.nextZoom = 1.07f + 0.05f * (1f - t);
            if (t >= 1f && wall.next == sharp) {
                Bitmap oldSharp = wall.cur, oldBlur = wall.curBlur;
                wall.cur = sharp;
                wall.curBlur = small;
                wall.zoom = 1.07f;
                wall.next = null;
                wall.nextBlur = null;
                wall.fade = 0f;
                if (oldSharp != null && oldSharp != sharp) oldSharp.recycle();
                if (oldBlur != null && oldBlur != small) oldBlur.recycle();
            }
            wall.changed();
        });
        wallAnim.start();
    }

    /** 96x54 box-blurred copy of the photo: enough for frosted glass, ~20 KB of memory. */
    private static Bitmap blurred(Bitmap src) {
        int w = 96, h = 54;
        Bitmap b = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) px = boxBlur(px, w, h, 2);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static int[] boxBlur(int[] in, int w, int h, int r) {
        int[] tmp = new int[in.length], out = new int[in.length];
        blur1d(in, tmp, w, h, r, true);
        blur1d(tmp, out, w, h, r, false);
        return out;
    }

    private static void blur1d(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int lines = horiz ? h : w, len = horiz ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int p = horiz ? in[l * w + j] : in[j * w + l];
                    rs += (p >> 16) & 0xFF; gs += (p >> 8) & 0xFF; bs += p & 0xFF; n++;
                }
                out[horiz ? l * w + i : i * w + l] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }

    private void parallaxTo(float tx, float ty) {
        if (parallaxAnim != null) parallaxAnim.cancel();
        final float sx = wall.px, sy = wall.py;
        parallaxAnim = ValueAnimator.ofFloat(0f, 1f);
        parallaxAnim.setDuration(900);
        parallaxAnim.setInterpolator(new DecelerateInterpolator(2f));
        parallaxAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            wall.px = sx + (tx - sx) * t;
            wall.py = sy + (ty - sy) * t;
            wall.changed();
        });
        parallaxAnim.start();
    }

    /** Grid mode frosts the wallpaper and hides the clock, as tvOS does when you scroll down. */
    private void setGridMode(boolean grid) {
        if (grid == inGrid) return;
        inGrid = grid;
        header.animate().alpha(grid ? 0f : 1f).translationY(grid ? -px(24) : 0).setDuration(280).start();
        if (frostAnim != null) frostAnim.cancel();
        frostAnim = ValueAnimator.ofFloat(wall.frost, grid ? 1f : 0f);
        frostAnim.setDuration(420);
        frostAnim.setInterpolator(new DecelerateInterpolator());
        frostAnim.addUpdateListener(a -> {
            wall.frost = (float) a.getAnimatedValue();
            wall.changed();
        });
        frostAnim.start();
    }

    // ------------------------------------------------------------- apps

    private void loadApps() {
        final String own = getPackageName();
        io.execute(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            final Map<String, App> all = new HashMap<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(own) || all.containsKey(pkg)) continue;
                App a = new App();
                a.key = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                a.banner = ri.activityInfo.loadBanner(pm);
                if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                if (a.banner == null) a.icon = ri.loadIcon(pm);
                all.put(pkg, a);
            }
            App s = new App();
            s.key = SETTINGS_KEY;
            s.label = "Settings";
            s.launch = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            s.icon = getDrawable(R.drawable.ic_settings);
            all.put(SETTINGS_KEY, s);
            ui.post(() -> applyApps(all));
        });
    }

    private void applyApps(Map<String, App> all) {
        Map<String, App> pool = new HashMap<>(all);
        dock.clear();
        others.clear();
        for (String k : readList("dock", DEFAULT_DOCK)) {
            App a = pool.remove(k);
            if (a != null) dock.add(a);
        }
        for (String k : readList("order", new String[0])) {
            App a = pool.remove(k);
            if (a != null) others.add(a);
        }
        List<App> rest = new ArrayList<>(pool.values());
        Collections.sort(rest, (x, y) -> {
            if (x.key.equals(SETTINGS_KEY)) return 1;
            if (y.key.equals(SETTINGS_KEY)) return -1;
            return x.label.compareToIgnoreCase(y.label);
        });
        others.addAll(rest);
        render();
    }

    private List<String> readList(String key, String[] def) {
        String v = prefs.getString(key, null);
        if (v == null) return Arrays.asList(def);
        if (v.isEmpty()) return new ArrayList<>();
        return Arrays.asList(v.split(","));
    }

    private void saveLists() {
        prefs.edit().putString("dock", joinKeys(dock)).putString("order", joinKeys(others)).apply();
    }

    private static String joinKeys(List<App> l) {
        StringBuilder sb = new StringBuilder();
        for (App a : l) { if (sb.length() > 0) sb.append(','); sb.append(a.key); }
        return sb.toString();
    }

    // ------------------------------------------------------------- render

    private LinearLayout dockRow() {
        return dockScroll == null ? null : (LinearLayout) dockScroll.getChildAt(0);
    }

    private void render() {
        View focused = getCurrentFocus();
        String keepKey = refocusKey;
        if (keepKey == null && focused != null && focused.getTag(R.id.app_key) != null) {
            keepKey = (String) focused.getTag(R.id.app_key);
        }
        refocusKey = null;
        if (dockPanel != null) wall.glass.remove(dockPanel);
        content.removeAllViews();

        int screenH = Math.round(getResources().getDisplayMetrics().heightPixels / dp);
        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(-1, px(screenH - BOTTOM - PANEL_H - NAME_H)));

        // Name of the focused dock app, gliding above the glass like tvOS.
        FrameLayout nameRow = new FrameLayout(this);
        dockName = new TextView(this);
        dockName.setTextColor(Color.WHITE);
        dockName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        dockName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dockName.setShadowLayer(px(14), 0, px(2), 0x99000000);
        dockName.setSingleLine();
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM);
        nlp.bottomMargin = px(8);
        nlp.leftMargin = px(SIDE);
        nameRow.addView(dockName, nlp);
        content.addView(nameRow, new LinearLayout.LayoutParams(-1, px(NAME_H)));

        // The frosted dock.
        FrameLayout panel = new FrameLayout(this);
        dockPanel = panel;
        panel.setBackground(new GlassDrawable(wall, panel, px(26), dp));
        wall.glass.add(panel);
        panel.setOutlineProvider(new RoundOutline(px(26), 0.55f));
        panel.setClipToOutline(true);
        panel.setElevation(px(16));
        dockScroll = new ManualHScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setClipChildren(false);
        dockScroll.setClipToPadding(false);
        dockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dockScroll.setPadding(px(PANEL_PAD), 0, px(PANEL_PAD), 0);
        dockScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> panel.invalidate());
        LinearLayout dr = new LinearLayout(this);
        dr.setClipChildren(false);
        dr.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < dock.size(); i++) dr.addView(cell(dock.get(i), true, i == 0));
        dockScroll.addView(dr, new FrameLayout.LayoutParams(-2, -1));
        panel.addView(dockScroll, match());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, px(PANEL_H));
        plp.leftMargin = px(EDGE);
        plp.rightMargin = px(EDGE);
        content.addView(panel, plp);

        TextView section = new TextView(this);
        section.setText("ALL APPS");
        section.setTextColor(0xB3FFFFFF);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        section.setLetterSpacing(0.24f);
        section.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = px(SIDE);
        slp.topMargin = px(BOTTOM + 30);
        slp.bottomMargin = px(16);
        content.addView(section, slp);

        LinearLayout row = null;
        for (int i = 0; i < others.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                // Padding would otherwise clip the scaled-up focused tile.
                row.setClipToPadding(false);
                row.setPadding(px(SIDE), 0, px(SIDE), 0);
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(cell(others.get(i), false, i % COLS == 0));
        }

        View keepCell = keepKey != null ? content.findViewWithTag("k:" + keepKey) : null;
        View target = keepCell != null ? keepCell.findViewWithTag("tile") : null;
        if (target == null && dr.getChildCount() > 0) target = dr.getChildAt(0).findViewWithTag("tile");
        final View t = target;
        if (t != null) ui.post(t::requestFocus);
    }

    private View cell(final App a, final boolean inDock, boolean first) {
        final LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(TILE_W), -2);
        if (!first) clp.leftMargin = px(GAP);
        cell.setLayoutParams(clp);
        // findViewWithTag needs a unique tag per app for refocusing after a rebuild.
        cell.setTag("k:" + a.key);

        final FrameLayout tile = new FrameLayout(this);
        tile.setTag("tile");
        tile.setTag(R.id.app_key, a.key);
        tile.setFocusable(true);
        tile.setContentDescription(a.label);
        tile.setCameraDistance(9000 * dp);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3A3D4C, 0xFF22242E});
        bg.setCornerRadius(12 * dp);
        tile.setBackground(bg);
        roundClip(tile, 12);

        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            tile.addView(iv, match());
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            inner.addView(iv, new LinearLayout.LayoutParams(px(36), px(36)));
            TextView tv = new TextView(this);
            tv.setText(a.label);
            tv.setTextColor(0xE6FFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setSingleLine();
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(px(8), px(6), px(8), 0);
            inner.addView(tv, new LinearLayout.LayoutParams(-2, -2));
            tile.addView(inner, match());
        }

        // Glass edge: faint rim and a soft highlight on the upper half.
        View rimView = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x05FFFFFF, 0x00FFFFFF});
        g.setCornerRadius(12 * dp);
        g.setStroke(Math.max(1, px(0.75f)), 0x40FFFFFF);
        rimView.setBackground(g);
        tile.addView(rimView, match());

        // Specular sheen that sweeps across on focus.
        final View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x4DFFFFFF, 0x00FFFFFF}));
        sheen.setRotation(18f);
        sheen.setAlpha(0f);
        tile.addView(sheen, new FrameLayout.LayoutParams(px(46), px(TILE_H * 2), Gravity.CENTER_VERTICAL));

        // White focus ring, as in tvOS 26.
        final View ring = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(12 * dp);
        rg.setStroke(px(2.5f), Color.WHITE);
        ring.setBackground(rg);
        ring.setAlpha(0f);
        tile.addView(ring, match());

        cell.addView(tile, new LinearLayout.LayoutParams(px(TILE_W), px(TILE_H)));

        final TextView label;
        if (!inDock) {
            label = new TextView(this);
            label.setText(a.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            label.setSingleLine();
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setAlpha(0f);
            label.setShadowLayer(px(4), 0, px(1), 0xCC000000);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
            llp.topMargin = px(12);
            llp.bottomMargin = px(16);
            cell.addView(label, llp);
        } else {
            label = null;
        }

        tile.setOnClickListener(v -> {
            // A quick press-in before launching makes the click feel physical.
            final float s = inDock ? 1.1f : 1.12f;
            v.animate().scaleX(s * 0.94f).scaleY(s * 0.94f).setDuration(90).withEndAction(() -> {
                launch(a);
                v.animate().scaleX(s).scaleY(s).setDuration(220).start();
            }).start();
        });
        tile.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                arrange(a, inDock);
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && (e.isLongPress() || e.getRepeatCount() > 0)) {
                if (!longFired) { longFired = true; arrange(a, inDock); }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && longFired) { longFired = false; return true; }
            return false;
        });
        tile.setOnFocusChangeListener((v, has) -> {
            if (has) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                float cx = loc[0] + v.getWidth() / 2f;
                // Swing in from the direction we came from, then settle on a spring.
                if (lastFocusX >= 0 && Math.abs(cx - lastFocusX) > 1) v.setRotationY(cx > lastFocusX ? -14f : 14f);
                lastFocusX = cx;
                float s = inDock ? 1.1f : 1.12f;
                v.animate().scaleX(s).scaleY(s).rotationY(0f).translationZ(px(18))
                        .setInterpolator(new OvershootInterpolator(1.3f)).setDuration(420).start();
                ring.animate().alpha(1f).setDuration(160).start();
                sheen.setTranslationX(-px(60));
                sheen.setAlpha(1f);
                sheen.animate().translationX(px(TILE_W + 20)).setDuration(700)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .withEndAction(() -> sheen.setAlpha(0f)).start();
                if (label != null) label.animate().alpha(1f).translationY(px(3)).setDuration(180).start();
                onTileFocused(a, inDock, cell);
            } else {
                v.animate().scaleX(1f).scaleY(1f).rotationY(0f).translationZ(0)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).setDuration(220).start();
                ring.animate().alpha(0f).setDuration(160).start();
                if (label != null) label.animate().alpha(0f).translationY(0).setDuration(160).start();
            }
        });
        return cell;
    }

    private void onTileFocused(App a, boolean inDock, View cell) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        if (inDock) {
            int index = ((ViewGroup) cell.getParent()).indexOfChild(cell);
            int scrollTarget = scrollDock(index);
            scroller.smoothScrollTo(0, 0);
            setGridMode(false);
            showDockName(a.label, px(EDGE + PANEL_PAD) + cell.getLeft() - scrollTarget);
            float cx = px(EDGE + PANEL_PAD) + cell.getLeft() - scrollTarget + px(TILE_W) / 2f;
            parallaxTo(-(cx / screenW - 0.5f) * px(34), 0);
        } else {
            View row = (View) cell.getParent();
            scroller.smoothScrollTo(0, Math.max(0, row.getTop() - px(96)));
            setGridMode(true);
            float cx = cell.getLeft() + px(TILE_W) / 2f;
            parallaxTo(-(cx / screenW - 0.5f) * px(34), -px(14));
        }
    }

    private void showDockName(String text, float x) {
        if (!text.contentEquals(dockName.getText())) {
            dockName.setAlpha(0f);
            dockName.setText(text);
            dockName.animate().alpha(1f).setDuration(260).start();
        }
        dockName.animate().translationX(x - px(SIDE)).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2.2f)).start();
    }

    /**
     * Scrolls the dock in whole columns so it stays aligned with the grid, keeping one
     * neighbour visible on each side of the focused app (except at the ends).
     * Returns the target scroll position.
     */
    private int scrollDock(int index) {
        int n = dock.size();
        int maxFirst = Math.max(0, n - COLS);
        int step = px(TILE_W + GAP);
        int from = dockScroll.getScrollX();
        int first = Math.round((dockAnim != null && dockAnim.isRunning()
                ? (int) dockAnim.getAnimatedValue() : from) / (float) step);
        if (dockAnim != null) dockAnim.cancel();
        first = Math.max(first, index - (COLS - 2));
        first = Math.min(first, index - 1);
        first = Math.max(0, Math.min(maxFirst, first));
        final int target = first * step;
        if (from == target) return target;
        dockAnim = ValueAnimator.ofInt(from, target);
        dockAnim.setDuration(380);
        dockAnim.setInterpolator(new DecelerateInterpolator(2.2f));
        dockAnim.addUpdateListener(an -> dockScroll.scrollTo((int) an.getAnimatedValue(), 0));
        dockAnim.start();
        return target;
    }

    // ------------------------------------------------------------- network

    void updateNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        String desc;
        if (ni == null || !ni.isConnected()) {
            netGlyph.set(NetGlyph.OFFLINE, 0);
            desc = "Not connected";
        } else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
            netGlyph.set(NetGlyph.ETHERNET, 0);
            desc = "Ethernet";
        } else {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int level = WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
            boolean online = true;
            if (Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                online = nc == null || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            netGlyph.set(online ? NetGlyph.WIFI : NetGlyph.NO_INTERNET, level);
            desc = online ? "Wi-Fi, " + level + " of 3 bars" : "Wi-Fi, no internet";
        }
        if (netPill != null) netPill.setContentDescription(desc);
    }

    // ------------------------------------------------------------- actions

    private void launch(App a) {
        launchSafe(a.launch, null);
    }

    private void launchSafe(Intent i, Intent fallback) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            if (fallback != null) {
                try { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
        }
    }

    private void arrange(final App a, final boolean inDock) {
        final List<App> list = inDock ? dock : others;
        final List<String> opts = new ArrayList<>();
        opts.add("Move Left");
        opts.add("Move Right");
        opts.add(inDock ? "Remove from Dock" : "Add to Dock");
        opts.add("Next Wallpaper");
        if (!a.key.equals(SETTINGS_KEY)) opts.add("App Info");
        new AlertDialog.Builder(this, R.style.GlassDialog)
                .setTitle(a.label)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    String o = opts.get(which);
                    int idx = list.indexOf(a);
                    switch (o) {
                        case "Move Left":
                            if (idx > 0) Collections.swap(list, idx, idx - 1);
                            break;
                        case "Move Right":
                            if (idx >= 0 && idx < list.size() - 1) Collections.swap(list, idx, idx + 1);
                            break;
                        case "Remove from Dock":
                            dock.remove(a);
                            others.add(0, a);
                            break;
                        case "Add to Dock":
                            others.remove(a);
                            dock.add(a);
                            break;
                        case "Next Wallpaper":
                            nextWallpaper(true);
                            return;
                        case "App Info":
                            launchSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + a.key)), null);
                            return;
                    }
                    saveLists();
                    refocusKey = a.key;
                    render();
                })
                .show();
    }
}
