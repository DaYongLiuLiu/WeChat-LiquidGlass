package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * The selection droplet, reproducing KernelSU's:
 *
 * <pre>
 * drawBackdrop(
 *     backdrop = combinedBackdrop,          // page + a scaled copy of the tabs
 *     effects = { lens(refractionHeight = 10.dp * progress,
 *                      refractionAmount = 14.dp * progress,
 *                      depthEffect = true, chromaticAberration = 0.5f) },
 *     highlight = { pillHighlight.copy(alpha = progress) },
 *     onDrawSurface = { drawRect(black @ 0.1f, alpha = 1 - progress)
 *                       drawRect(black @ 0.03f * progress) })
 * .innerShadow { InnerShadow(radius = 8.dp * progress, black @ 0.15f, alpha = progress) }
 * </pre>
 *
 * <p>At rest ({@code progress == 0}) the lens vanishes and only the flat 10%
 * wash remains, so this is a plain tinted capsule until touched.
 *
 * <p>The backdrop is the page <em>plus</em> the tab row drawn again at
 * {@code 1 + 0.2 * progress} — KernelSU's {@code CombinedBackdrop} of the page
 * and the (invisible) 1.2×-scaled tab layer. That scaled copy is what makes the
 * icon under the droplet appear enlarged and bent while dragging, and it is why
 * the droplet sits above the real tabs rather than below them.
 */
final class DropletPanel extends View {

    /** KernelSU: refractionHeight = 10.dp * progress, on a 56dp-tall droplet. */
    private static final float REFRACTION_DP = 10f;
    /** KernelSU: refractionAmount = 14.dp * progress, on a 56dp-tall droplet. */
    private static final float AMOUNT_DP = 14f;
    /** KernelSU: chromaticAberration = 0.5f. */
    private static final float ABERRATION = 0.5f;
    /**
     * KernelSU: {@code LocalFloatingBottomBarTabScale = lerp(1f, 1.2f, progress)}.
     *
     * <p>Halved here. KernelSU's droplet is 56dp tall around a ~42dp icon-plus-
     * label stack; WeChat's bar is a third shorter (46dp of droplet) while its
     * tabs are just as big, so there is far less room to grow into and a full
     * 1.2× pushes the icon straight through the rim.
     */
    private static final float TAB_ZOOM = 0.1f;

    private static final String LENS_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + LiquidGlassPanel.SDF_SOURCE
            + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) { return content.eval(coord); }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize,"
            + "            gradRadius) + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    float dispersionIntensity = chromaticAberration"
            + "            * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5; color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5; color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    /**
     * KernelSU: {@code innerShadow { InnerShadow(radius = 8.dp * progress,
     * black @ 0.15f, alpha = progress) }}.
     *
     * <p>An inner shadow falls off smoothly from the rim inwards. Faking it with
     * a plain 8dp-wide stroke gives it a hard inner edge, and that edge lands
     * just inside the 10dp refraction band — which is exactly the seam between
     * the undistorted middle and the bent rim that made the droplet look
     * layered. Reusing the same rounded-rect SDF the lens runs on gives the real
     * gradient.
     */
    private static final String INNER_SHADOW_SHADER = ""
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float blur;\n"
            + "uniform float alpha;\n"
            + LiquidGlassPanel.SDF_SOURCE
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float sd = sdRoundedRect(coord - halfSize, halfSize, radius);\n"
            + "    float t = 1.0 - smoothstep(0.0, blur, -sd);\n"
            + "    half a = half(alpha * t * t);\n"
            + "    return half4(0.0, 0.0, 0.0, a);\n"
            + "}\n";

    private final WeakReference<ViewGroup> mPagerRef;
    private final WeakReference<ViewGroup> mTabRowRef;
    private final float mDensity;
    private final int mPad;
    private final boolean mNight;

    private final RenderNode mNode = new RenderNode("wxDroplet");
    private RuntimeShader mLens;
    private RuntimeShader mInnerShader;

    private final Paint mWash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final Paint mPillSurface = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Reused across draws rather than allocated per frame.
    private final int[] mTmp = new int[2];
    private final int[] mSelf = new int[2];
    private final int[] mSrc = new int[2];
    private final android.graphics.Rect mVisible = new android.graphics.Rect();
    private WeakReference<View> mPillRef = new WeakReference<>(null);

    /** The glass pill, redrawn into the droplet's backdrop as KernelSU does. */
    void setPill(View pill) {
        mPillRef = new WeakReference<>(pill);
    }

    private static final android.graphics.PorterDuffXfermode SRC_ATOP =
            new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SRC_ATOP);
    private final Paint mAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mAccentCache;

    /**
     * WeChat's selected-tab colour, read off whichever tab is currently selected
     * so it follows the app's own theme rather than being hard-coded.
     */
    private int accentColour(ViewGroup tabRow) {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (!tab.isSelected()) {
                continue;
            }
            int c = firstLabelColour(tab, 0);
            // Reject near-white / near-black: before the selection settles this
            // reads an *unselected* label, and caching that turned the whole
            // tinted copy white. Never cache — the theme can flip at runtime.
            if (c != 0 && !isNeutral(c)) {
                mAccentCache = c;
                return c;
            }
        }
        return mAccentCache != 0 ? mAccentCache : 0xFF07C160; // WeChat green
    }

    private static boolean isNeutral(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min < 24;
    }

    private int firstLabelColour(View v, int depth) {
        if (depth > 4 || v.getVisibility() != VISIBLE) {
            return 0;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            if (tv.getBackground() == null && tv.getText() != null
                    && tv.getText().length() > 0) {
                return tv.getCurrentTextColor() | 0xFF000000;
            }
            return 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                int c = firstLabelColour(g.getChildAt(i), depth + 1);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    /**
     * Draws one tab with KernelSU's {@code LocalContentColor} applied.
     *
     * <p>The tint has to be a single layer over the tab's own {@code draw()}.
     * Walking down to leaf views and tinting those instead looks equivalent but
     * silently drops anything a <em>container</em> paints for itself — no group
     * ever gets drawn, only its children — and that is exactly where WeChat
     * keeps the unread bubble, which is why it vanished from the droplet.
     *
     * <p>Whatever carries its own colour is then repainted on top, untinted, so
     * the bubble stays red instead of going green with the rest of the tab.
     */
    private void drawTab(Canvas c, View tab, int accent) {
        if (tab.getVisibility() != VISIBLE
                || tab.getWidth() <= 0 || tab.getHeight() <= 0) {
            return;
        }
        int w = tab.getWidth();
        int h = tab.getHeight();
        // The bubble hangs off the icon's top-right and can reach past the tab's
        // own bounds, so the layer is grown rather than clipped to them.
        float pad = h * 0.5f;
        int l = c.saveLayer(-pad, -pad, w + pad, h + pad, null);
        tab.draw(c);
        mAccent.setColor(accent);
        mAccent.setXfermode(SRC_ATOP);
        c.drawRect(-pad, -pad, w + pad, h + pad, mAccent);
        mAccent.setXfermode(null);
        c.restoreToCount(l);
        // Repaint the badges untinted. Drawing the badge view itself renders
        // nothing — WeChat keeps the TextView for layout and paints the bubble
        // from its parent — so the tab is redrawn as a whole, clipped to the
        // badge's own capsule. Whoever actually paints those pixels, the
        // untinted copy is what survives inside the clip.
        if (tab instanceof ViewGroup) {
            mBadgeCount = 0;
            collectBadges((ViewGroup) tab, 0f, 0f, 0);
            if (mBadgeCount > 0) {
                int s = c.save();
                mBadgeClip.reset();
                for (int i = 0; i < mBadgeCount; i++) {
                    android.graphics.RectF r = mBadges.get(i);
                    float rr = r.height() * 0.5f;
                    mBadgeClip.addRoundRect(r, rr, rr, Path.Direction.CW);
                }
                c.clipPath(mBadgeClip);
                tab.draw(c);
                c.restoreToCount(s);
            }
        }
    }

    /**
     * True for views that own their colour and must survive the accent tint.
     *
     * <p>KernelSU's content colour reaches the icon and the label; everything
     * else in the tab — the unread bubble, the little dot — is styled in its own
     * right and would read as a green blob if it were tinted along with them.
     */
    private static boolean ownsItsColour(View v) {
        String cls = v.getClass().getName();
        if (cls.endsWith("TabIconView")) {
            return false;
        }
        return !(v instanceof android.widget.TextView && v.getBackground() == null);
    }

    /** Badge bounds, kept as a pool: this runs on every frame of a drag. */
    private final java.util.ArrayList<android.graphics.RectF> mBadges =
            new java.util.ArrayList<>(4);
    private int mBadgeCount;
    private final Path mBadgeClip = new Path();

    private void addBadge(float l, float t, float r, float b) {
        if (mBadgeCount == mBadges.size()) {
            mBadges.add(new android.graphics.RectF());
        }
        mBadges.get(mBadgeCount++).set(l, t, r, b);
    }

    /** Collects the bounds of everything in the tab that owns its colour. */
    private void collectBadges(ViewGroup parent, float ox, float oy, int depth) {
        if (depth > 4) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != VISIBLE
                    || child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue;
            }
            float cx = ox + child.getLeft();
            float cy = oy + child.getTop();
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                collectBadges((ViewGroup) child, cx, cy, depth + 1);
            } else if (ownsItsColour(child)
                    && (child.getBackground() != null
                        || child instanceof android.widget.ImageView)) {
                addBadge(cx, cy, cx + child.getWidth(), cy + child.getHeight());
            }
        }
    }

    /**
     * Half-height of a tab's content stack, measured from the tab's centre.
     *
     * <p>WeChat wraps the icon and the label in one container, so its bounds
     * are what has to clear the lens.
     */
    private static float contentHalfHeight(ViewGroup tabRow) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0f;
        }
        View tab = tabRow.getChildAt(0);
        if (tab == null || tab.getHeight() <= 0) {
            return 0f;
        }
        float centre = tab.getHeight() * 0.5f;
        if (!(tab instanceof ViewGroup) || ((ViewGroup) tab).getChildCount() == 0) {
            return centre;
        }
        View content = ((ViewGroup) tab).getChildAt(0);
        return Math.max(centre - content.getTop(),
                content.getTop() + content.getHeight() - centre);
    }

    private float mProgress;
    private boolean mSupported;

    DropletPanel(Context ctx, ViewGroup pager, ViewGroup tabRow,
                 float density, boolean night) {
        super(ctx);
        mPagerRef = new WeakReference<>(pager);
        mTabRowRef = new WeakReference<>(tabRow);
        mDensity = density;
        mNight = night;
        mPad = Math.round(AMOUNT_DP * density) + Math.round(density * 4f);

        mSupported = Build.VERSION.SDK_INT >= 33;
        if (mSupported) {
            try {
                mLens = new RuntimeShader(LENS_SHADER);
                mInnerShader = new RuntimeShader(INNER_SHADOW_SHADER);
            } catch (Throwable t) {
                mSupported = false;
                WeChatLiquidGlassModule.logErr("droplet shader rejected", t);
            }
        }
        mPillSurface.setColor(night ? 0xE62C2C2E : 0xE6F2F2F7);
        mWash.setColor(night ? 0x1AFFFFFF : 0x1A000000);
        mPressTint.setColor(0x08000000);
        mHighlight.setStyle(Paint.Style.STROKE);
        mHighlight.setStrokeWidth(density);
        mHighlight.setColor(0x1FFFFFFF);
        mInnerShadow.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    /** Press progress, 0..1, driven by the drag controller's spring. */
    void setProgress(float p) {
        if (mProgress != p) {
            mProgress = p;
            invalidate();
        }
    }

    /**
     * Re-captures the backdrop. translationX moves the view on the render thread
     * without redrawing it, so without this the refracted content freezes at
     * whatever was underneath when the press began.
     */
    void refresh() {
        if (mProgress > 0.01f) {
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mClip.reset();
        float r = h * 0.5f;
        mClip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = h * 0.5f;
        float p = mProgress;

        if (mSupported && p > 0.01f && canvas.isHardwareAccelerated()) {
            try {
                drawLens(canvas, w, h, radius, p);
            } catch (Throwable t) {
                mSupported = false;
                WeChatLiquidGlassModule.logErr("droplet lens failed", t);
            }
        }

        // KernelSU: the flat wash fades out exactly as the lens fades in.
        int washAlpha = Math.round(0x1A * (1f - p));
        if (washAlpha > 0) {
            mWash.setAlpha(washAlpha);
            canvas.drawRoundRect(0, 0, w, h, radius, radius, mWash);
        }
        if (p > 0f) {
            mPressTint.setAlpha(Math.round(0x08 * p));
            canvas.drawRoundRect(0, 0, w, h, radius, radius, mPressTint);

            mHighlight.setAlpha(Math.round(0x1F * p));
            float half = mHighlight.getStrokeWidth() * 0.5f;
            canvas.drawRoundRect(half, half, w - half, h - half,
                    radius - half, radius - half, mHighlight);

            // InnerShadow(radius = 8dp * progress, black @ 0.15, alpha = progress)
            float inner = 8f * mDensity * p;
            if (inner > 0.5f && mInnerShader != null
                    && canvas.isHardwareAccelerated()) {
                mInnerShader.setFloatUniform("size", w, h);
                mInnerShader.setFloatUniform("radius", radius);
                mInnerShader.setFloatUniform("blur", inner);
                mInnerShader.setFloatUniform("alpha", 0.15f * p);
                mInnerShadow.setShader(mInnerShader);
                mInnerShadow.setAlpha(255);
                canvas.drawRoundRect(0, 0, w, h, radius, radius, mInnerShadow);
            }
        }
    }

    /**
     * Builds the shader's input layer: page content, the pill surface, and the
     * scaled, tinted copy of the tabs.
     */
    private void paintBackdrop(Canvas c, int nw, int nh, int[] self, float p) {
        ViewGroup pager = mPagerRef.get();
        ViewGroup tabRow = mTabRowRef.get();
        if (pager == null) {
            return;
        }
        int[] src = mSrc;
        c.drawColor(mNight ? 0xFF111111 : 0xFFF7F7F7);
        // Undo the view's own scale before sampling. While held the droplet is
        // blown up to 78/56, and that scale stretches whatever this node
        // contains — so the backdrop came out magnified on top of the 1.2×
        // tab copy. KernelSU's backdrop is sampled in fixed screen space:
        // growing the droplet shows *more* of the background, it does not
        // enlarge it. Pre-scaling by 1/s reproduces that.
        float viewScale = ViewGeom.cumulativeScale(this);
        if (Math.abs(viewScale - 1f) > 0.001f) {
            c.scale(1f / viewScale, 1f / viewScale, nw * 0.5f, nh * 0.5f);
        }
        android.graphics.Rect visible = mVisible;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page.getVisibility() != VISIBLE
                    || !page.getGlobalVisibleRect(visible) || visible.isEmpty()) {
                continue;
            }
            page.getLocationOnScreen(src);
            int save = c.save();
            c.translate(mPad - (self[0] - src[0]), mPad - (self[1] - src[1]));
            c.clipRect(self[0] - src[0] - mPad, self[1] - src[1] - mPad,
                    self[0] - src[0] - mPad + nw, self[1] - src[1] - mPad + nh);
            page.draw(c);
            c.restoreToCount(save);
        }
        // The scaled tab layer: KernelSU's tabsBackdrop, drawn at
        // lerp(1, 1.2, progress) so the icon under the droplet reads as
        // enlarged once the lens bends it.
        View pill = mPillRef.get();
        if (tabRow != null && ViewGeom.unscaledScreenPos(tabRow, src)) {
            int save = c.save();
            c.translate(mPad - (self[0] - src[0]), mPad - (self[1] - src[1]));
            // KernelSU's tabsBackdrop is a full glass pill carrying the tabs,
            // not a bare icon layer (its onDrawSurface paints containerColor).
            // Without it the page shows through wherever the tabs are
            // transparent, and the droplet magnifies chat text.
            if (pill != null && ViewGeom.unscaledScreenPos(pill, mTmp)) {
                int ps = c.save();
                c.translate(mTmp[0] - src[0], mTmp[1] - src[1]);
                float pr = pill.getHeight() * 0.5f;
                c.drawRoundRect(0, 0, pill.getWidth(), pill.getHeight(),
                        pr, pr, mPillSurface);
                c.restoreToCount(ps);
            }
            // Each tab scales about its OWN centre, exactly as KernelSU does
            // (graphicsLayer on each tab Column). Scaling the whole row about
            // one point instead shoves distant tabs outward and blows the
            // nearby one up — that is what looked so overdone.
            float scale = 1f + TAB_ZOOM * p;
            int accent = accentColour(tabRow);
            for (int i = 0; i < tabRow.getChildCount(); i++) {
                View tab = tabRow.getChildAt(i);
                if (tab.getVisibility() != VISIBLE) {
                    continue;
                }
                int ts = c.save();
                c.scale(scale, scale,
                        tab.getLeft() + tab.getWidth() * 0.5f,
                        tab.getTop() + tab.getHeight() * 0.5f);
                c.translate(tab.getLeft(), tab.getTop());
                // KernelSU renders this whole layer with LocalContentColor =
                // accentColor, so whichever tab the droplet passes over shows
                // in the selected colour immediately, without waiting for the
                // selection to actually change on release.
                drawTab(c, tab, accent);
                c.restoreToCount(ts);
            }
            c.restoreToCount(save);
        }
    }

    private void drawLens(Canvas canvas, int w, int h, float radius, float p) {
        ViewGroup pager = mPagerRef.get();
        ViewGroup tabRow = mTabRowRef.get();
        if (pager == null) {
            return;
        }

        int nw = w + mPad * 2;
        int nh = h + mPad * 2;
        mNode.setPosition(0, 0, nw, nh);

        // getLocationOnScreen() reports the *scaled* position — the droplet is
        // blown up to 78/56 while held — but this canvas is in unscaled local
        // coordinates. Derive the unscaled screen position from an ancestor that
        // never scales, otherwise every sample lands somewhere else entirely.
        int[] self = mSelf;
        if (!ViewGeom.unscaledScreenPos(this, self)) {
            return;
        }

        RecordingCanvas rc = mNode.beginRecording(nw, nh);
        try {
            paintBackdrop(rc, nw, nh, self, p);
        } finally {
            mNode.endRecording();
        }

        mLens.setFloatUniform("size", w, h);
        mLens.setFloatUniform("offset", -mPad, -mPad);
        mLens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        // KernelSU's 10dp band sits just outside its tab content. WeChat's is
        // the same size inside a shorter droplet, so a fixed 10dp swallows the
        // top of the icon while the label — whose glyphs sit well inside their
        // box — stays clear, and the droplet reads as lopsided. Take the band
        // from the room the content actually leaves instead, never more than
        // KernelSU asks for.
        float band = REFRACTION_DP * mDensity;
        float half = contentHalfHeight(tabRow);
        if (half > 0f) {
            float scale = ViewGeom.cumulativeScale(this);
            band = Math.max(0f,
                    Math.min(band, h * 0.5f - half * (1f + TAB_ZOOM * p) / scale));
        }
        mLens.setFloatUniform("refractionHeight", band * p);
        mLens.setFloatUniform("refractionAmount", -band * (AMOUNT_DP / REFRACTION_DP) * p);
        // depthEffect adds normalize(centeredCoord) to the gradient, which swings
        // hard near the middle of a droplet this flat and draws a visible ring at
        // the refraction boundary. KernelSU's droplet is far taller, so it never
        // shows there.
        mLens.setFloatUniform("depthEffect", 0f);
        mLens.setFloatUniform("chromaticAberration", ABERRATION);
        mNode.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(mLens, "content"));

        canvas.save();
        canvas.clipPath(mClip);
        canvas.translate(-mPad, -mPad);
        canvas.drawRenderNode(mNode);
        canvas.restore();
    }
}
