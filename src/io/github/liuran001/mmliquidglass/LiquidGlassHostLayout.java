package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

final class LiquidGlassHostLayout extends FrameLayout {

    static final Object GLASS_TAG = new Object();

    /** Light frost fallback for devices without RuntimeShader. */
    private static final float SAMPLE_SCALE_LEGACY = 0.4f;
    private static final int BLUR_RADIUS_LEGACY = 3;
    private static final float SATURATION_BOOST = 1.08f;

    private final ViewGroup mSampleRoot;
    private final float mDensity;
    private boolean mDarkMode;
    private int mCaptureCount;

    private final boolean mUseAgsl;

    /** Tuner for the vendored QmDeve renderer (API 33+). Null = legacy frost path. */
    interface GlassTuner {
        void onSize(int w, int h, float cornerRadius);
        void onTheme(boolean dark);
    }

    private GlassTuner mTuner;

    /**
     * Self-drawn drop shadow.
     *
     * <p>{@code setElevation} draws nothing here — whatever WeChat's view tree
     * does to this subtree, the platform shadow never appears even at absurd
     * values. Drawing it ourselves also keeps it matched to the capsule.
     *
     * <p>The host reserves {@link #mShadowPad} of padding on every side; children
     * (MATCH_PARENT) shrink into the inner box, so the opaque round-rect drawn
     * here is fully covered by the glass and only its shadow shows.
     */
    private int mShadowPad;
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mShadowClip = new Path();
    private float mShadowOffsetY;
    private boolean mShadowHidden;
    private int mShadowAlpha = 255;

    /** Slides/fades the shadow along with WeChat's bar. */
    void setShadowOffsetY(float ty, float alpha) {
        boolean hidden = ty == Float.MAX_VALUE;
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        if (mShadowHidden == hidden && mShadowOffsetY == ty && mShadowAlpha == a) {
            return;
        }
        mShadowHidden = hidden;
        mShadowOffsetY = hidden ? 0f : ty;
        mShadowAlpha = a;
        invalidate();
    }

    /** KernelSU: dropShadow(radius = 10.dp, alpha = dark ? 0.2f : 0.1f). */
    void setupShadow(float density, boolean night) {
        mShadowPad = Math.round(density * 14f);
        setPadding(mShadowPad, mShadowPad, mShadowPad, mShadowPad);
        mShadowPaint.setColor(0xFF000000);
        mShadowPaint.setShadowLayer(density * 10f, 0f, density * 2f,
                night ? 0x33000000 : 0x1A000000);
        // No setLayerType here: promoting the host to its own layer renders the
        // whole padded box as one texture, which shows up as a rectangular patch
        // clipping the list separators behind it. drawRoundRect's shadow layer is
        // hardware-accelerated on its own since API 28.
        invalidate();
    }

    int shadowPad() {
        return mShadowPad;
    }

    private void drawPillShadow(Canvas canvas) {
        if (mShadowPad <= 0 || mShadowHidden || mShadowAlpha == 0) {
            return;
        }
        float l = mShadowPad;
        float t = mShadowPad;
        float r = getWidth() - mShadowPad;
        float b = getHeight() - mShadowPad;
        if (r <= l || b <= t) {
            return;
        }
        float radius = (b - t) * 0.5f;
        int save = canvas.save();
        canvas.translate(0f, mShadowOffsetY);
        // Punch the pill out of the shadow instead of relying on the glass to
        // cover it. The paint's fill is opaque black — it only ever existed to
        // cast the shadow — so a single frame where the glass lags behind (as
        // happens while WeChat slides the bar away) would otherwise flash a solid
        // black band across the bottom of the pill.
        if (mShadowClipW != getWidth() || mShadowClipH != getHeight()) {
            mShadowClip.reset();
            mShadowClip.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW);
            mShadowClipW = getWidth();
            mShadowClipH = getHeight();
        }
        canvas.clipOutPath(mShadowClip);
        int prev = mShadowPaint.getAlpha();
        mShadowPaint.setAlpha(mShadowAlpha);
        canvas.drawRoundRect(l, t, r, b, radius, radius, mShadowPaint);
        mShadowPaint.setAlpha(prev);
        canvas.restoreToCount(save);
    }

    /**
     * Optional gesture owner. It gets first refusal on every touch, but is
     * expected to claim only drags so WeChat's own tab views keep their taps.
     */
    interface DragHandler {
        boolean onIntercept(android.view.MotionEvent ev);
        boolean onTouch(android.view.MotionEvent ev);
    }

    private DragHandler mDragHandler;

    void setDragHandler(DragHandler handler) {
        mDragHandler = handler;
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        try {
            if (mDragHandler != null && mDragHandler.onIntercept(ev)) {
                return true;
            }
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("drag intercept failed", t);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        try {
            if (mDragHandler != null && mDragHandler.onTouch(ev)) {
                return true;
            }
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("drag touch failed", t);
        }
        return super.onTouchEvent(ev);
    }

    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();

    private float mCornerRadius;
    private Bitmap mRegionBuf;
    private boolean mCapturing;

    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private int mShadowClipW = -1;
    private int mShadowClipH = -1;

    LiquidGlassHostLayout(Context context, ViewGroup sampleRoot, ViewGroup bar) {
        super(context);
        mSampleRoot = sampleRoot;
        mDensity = context.getResources().getDisplayMetrics().density;
        // WeChat resolves day/night through standard values-night qualifiers,
        // so the activity uiMode is the authoritative source. The text probe
        // below only serves as a cross-check in the log.
        Boolean detected = detectDarkFromText(bar);
        mDarkMode = isSystemNight(context);
        mUseAgsl = Build.VERSION.SDK_INT >= 33;
        setTag(GLASS_TAG);
        setWillNotDraw(false);
        setupPaints();
        WeChatLiquidGlassModule.log(android.util.Log.INFO,
                "host created: sdk=" + Build.VERSION.SDK_INT
                        + " path=" + (mUseAgsl ? "agsl" : "legacy-frost")
                        + " dark=" + mDarkMode + " source="
                        + (detected != null ? "text-color" : "uiMode"));
    }

    /** Activates the vendored QmDeve renderer; disables internal frost drawing. */
    void setGlassTuner(GlassTuner tuner) {
        mTuner = tuner;
    }

    @SuppressWarnings("unused")
    private static boolean isSystemNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Follows the app's actual rendering: bright nav text means a dark bar.
     *
     * <p>WeChat nests each tab label three levels deep, and the same subtree also
     * holds the unread-count badge (white on red) which would poison a
     * first-match probe. Taking the most common colour across all labels sidesteps
     * that: three of the four tabs always carry the unselected colour, so the
     * badge and the single selected label can never win the vote.
     */
    static Boolean detectDarkFromText(ViewGroup bar) {
        if (bar == null) {
            return null;
        }
        try {
            java.util.HashMap<Integer, Integer> votes = new java.util.HashMap<>();
            collectTextColors(bar, votes);
            int best = 0;
            int bestCount = 0;
            for (java.util.Map.Entry<Integer, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            if (bestCount == 0) {
                return null;
            }
            float lum = (0.299f * Color.red(best)
                    + 0.587f * Color.green(best)
                    + 0.114f * Color.blue(best)) / 255f;
            return lum > 0.5f;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void collectTextColors(View v, java.util.Map<Integer, Integer> votes) {
        if (v.getVisibility() != VISIBLE) {
            return;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            // Badges carry a background drawable; plain labels do not.
            if (tv.getBackground() == null && tv.getText() != null
                    && tv.getText().length() > 0) {
                android.content.res.ColorStateList csl = tv.getTextColors();
                if (csl != null) {
                    int col = csl.getDefaultColor() | 0xFF000000;
                    Integer prev = votes.get(col);
                    votes.put(col, prev == null ? 1 : prev + 1);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTextColors(vg.getChildAt(i), votes);
            }
        }
    }

    private void setupPaints() {
        if (!mUseAgsl) {
            if (mDarkMode) {
                mTintPaint.setColor(0x33000000);
                mBorderPaint.setColor(0x1FFFFFFF);
                mBackdropPaint.setColor(0x40000000);
            } else {
                mTintPaint.setColor(0x4DFFFFFF);
                mBorderPaint.setColor(0x2EFFFFFF);
                mBackdropPaint.setColor(0x8CFFFFFF);
            }
            mBorderPaint.setStyle(Paint.Style.STROKE);
            mBorderPaint.setStrokeWidth(Math.max(mDensity * 0.8f, 0.75f));
            if (getWidth() > 0 && getHeight() > 0) {
                mGlossPaint.setShader(new LinearGradient(
                        0f, 0f, 0f, getHeight() * 0.45f,
                        mDarkMode ? 0x14FFFFFF : 0x30FFFFFF,
                        0x00FFFFFF, Shader.TileMode.CLAMP));
            }
        }
    }

    void attach() {
        detach();
        mPreDrawListener = () -> {
            if (!mCapturing && isAttachedToWindow()
                    && getVisibility() == VISIBLE
                    && getWidth() > 0 && getHeight() > 0) {
                capture();
            }
            return true;
        };
        mSampleRoot.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        invalidate();
        playRevealAnimation();
    }

    void detach() {
        if (mPreDrawListener != null) {
            mSampleRoot.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            mPreDrawListener = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        detach();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0f, 0f, w, h);
        mCornerRadius = Math.min(h * 0.46f, 30f * mDensity);
        if (mTuner != null) {
            // The tuner sizes the glass, which lives inside the shadow padding.
            mTuner.onSize(w - mShadowPad * 2, h - mShadowPad * 2, mCornerRadius);
            return;
        }
        if (!mUseAgsl) {
            mGlossPaint.setShader(new LinearGradient(
                    0f, 0f, 0f, h * 0.45f,
                    mDarkMode ? 0x1FFFFFFF : 0x40FFFFFF,
                    0x00FFFFFF, Shader.TileMode.CLAMP));
        }
    }

    private float sampleScale() {
        return mUseAgsl ? 1.0f : SAMPLE_SCALE_LEGACY;
    }

    private void capture() {
        try {
            mCapturing = true;
            maybeRefreshTheme();
            if (mTuner != null) {
                // External GPU renderer records content itself; no bitmaps needed.
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mSampleRoot.getWidth() <= 0) {
                return;
            }
            ensureRegionBuffer(w, h);

            Canvas c = new Canvas(mRegionBuf);
            float scale = sampleScale();
            int[] rootLoc = new int[2];
            int[] selfLoc = new int[2];
            mSampleRoot.getLocationOnScreen(rootLoc);
            getLocationOnScreen(selfLoc);
            float dx = selfLoc[0] - rootLoc[0];
            float dy = selfLoc[1] - rootLoc[1];

            c.save();
            c.clipRect(0f, 0f, w, h);
            c.scale(scale, scale);
            c.translate(-dx, -dy);
            int vis = getVisibility();
            setVisibility(INVISIBLE);
            try {
                mSampleRoot.draw(c);
            } finally {
                setVisibility(vis);
                c.restore();
            }

            applySaturationBoost(mRegionBuf);
            if (!mUseAgsl) {
                StackBlur.blur(mRegionBuf, BLUR_RADIUS_LEGACY);
            }
            invalidate();
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("capture failed", t);
        } finally {
            mCapturing = false;
        }
    }

    private void ensureRegionBuffer(int w, int h) {
        float scale = sampleScale();
        int bw = Math.max(Math.round(w * scale), 1);
        int bh = Math.max(Math.round(h * scale), 1);
        if (mRegionBuf == null
                || mRegionBuf.isRecycled()
                || mRegionBuf.getWidth() != bw
                || mRegionBuf.getHeight() != bh) {
            Bitmap old = mRegionBuf;
            mRegionBuf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        } else {
            mRegionBuf.eraseColor(android.graphics.Color.TRANSPARENT);
        }
    }

    /** Re-evaluates dark/light periodically so theme switches follow the app live.
     *  Uses the activity uiMode: WeChat resolves day/night via standard
     *  values-night qualifiers, so this mirrors the app's real theme. */
    private void maybeRefreshTheme() {
        mCaptureCount++;
        if (mCaptureCount % 20 != 1) {
            return;
        }
        boolean detected = isSystemNight(getContext());
        if (mCaptureCount == 1) {
            WeChatLiquidGlassModule.log(android.util.Log.INFO,
                    "theme probe first sample: dark=" + detected
                            + " current=" + mDarkMode);
        }
        if (detected != mDarkMode) {
            mDarkMode = detected;
            if (mTuner != null) {
                mTuner.onTheme(mDarkMode);
            }
            setupPaints();
            invalidate();
            WeChatLiquidGlassModule.log(android.util.Log.INFO,
                    "theme switched: dark=" + mDarkMode);
        }
    }

    private void applySaturationBoost(Bitmap bmp) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION_BOOST);
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(bmp).drawBitmap(bmp, 0f, 0f, p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawPillShadow(canvas);
        if (mTuner != null) {
            // Vendored renderer draws as child view index 0 beneath us.
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        drawLegacyFrost(canvas);
    }

    private void drawLegacyFrost(Canvas canvas) {
        float r = mCornerRadius;

        if (mRegionBuf != null && !mRegionBuf.isRecycled()) {
            BitmapShader shader = new BitmapShader(
                    mRegionBuf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(
                    getWidth() / (float) mRegionBuf.getWidth(),
                    getHeight() / (float) mRegionBuf.getHeight());
            shader.setLocalMatrix(m);
            mBackdropPaint.setShader(shader);
        } else {
            mBackdropPaint.setShader(null);
            mBackdropPaint.setColor(mDarkMode ? 0x50000000 : 0x8CFFFFFF);
        }
        canvas.drawRoundRect(mBounds, r, r, mBackdropPaint);

        canvas.drawRoundRect(mBounds, r, r, mTintPaint);
        canvas.drawRoundRect(mBounds, r, r, mGlossPaint);

        float half = mBorderPaint.getStrokeWidth() * 0.5f;
        RectF border = new RectF(half, half,
                getWidth() - half, getHeight() - half);
        canvas.drawRoundRect(border, r - half, r - half, mBorderPaint);
    }

    /* ---------------- liquid motion ---------------- */

    private void playRevealAnimation() {
        try {
            setPivotX(getWidth() * 0.5f);
            setPivotY(getHeight());
            setScaleY(0.86f);
            setAlpha(0f);
            animate().alpha(1f).scaleY(1f)
                    .setDuration(380L)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
        } catch (Throwable ignored) {
        }
    }

}
