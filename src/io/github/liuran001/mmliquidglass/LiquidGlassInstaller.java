package io.github.liuran001.mmliquidglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * Moves WeChat's bottom tab bar into a floating liquid-glass pill.
 *
 * <p>Unlike the HeyBox original this is ported from, no layout surgery on the
 * content area is needed: WeChat already lays its {@code CustomViewPager} out at
 * full screen height with the tab bar floating on top, so the backdrop the glass
 * refracts is there from the start. We only reparent the bar and strip the solid
 * colour WeChat paints behind it.
 */
final class LiquidGlassInstaller {

    /**
     * Retry budget for the first layout pass after LauncherUI resumes. WeChat
     * builds its home screen asynchronously (FirstScreenFrameLayout, blink
     * preloading), so the bar can show up seconds after onResume. This polling
     * is only a safety net — the primary trigger is the {@code setTo(int)} hook.
     */
    private static final int MAX_ATTEMPTS = 20;
    private static final long RETRY_DELAY_MS = 250L;

    /** Scratch for on-screen positions; every use is on the UI thread. */
    private static final int[] sLoc = new int[2];
    private static boolean sKeepFailed;
    private static WeakReference<Activity> sActivityRef = new WeakReference<>(null);
    private static WeakReference<LiquidGlassHostLayout> sHostRef = new WeakReference<>(null);
    private static WeakReference<View> sDropletRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sTabRowRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sPagerRef = new WeakReference<>(null);
    private static int sLastIndex = -1;
    private static WeakReference<View> sTabViewRef = new WeakReference<>(null);
    private static WeakReference<View> sGlassRef = new WeakReference<>(null);
    private static DropletDragController sDrag;
    /** Droplet's resting Y inside the host, before WeChat's bar offset. */
    private static float sDropletBaseY;

    private LiquidGlassInstaller() {
    }

    /** How long the stock bar stays hidden before it is handed back. */
    private static final long REVEAL_TIMEOUT_MS = 4000L;

    /**
     * Keeps WeChat's own bar invisible until the pill takes it over.
     *
     * <p>Installing cannot happen before the first layout, and WeChat has drawn
     * its own bar by then — which reads as a flash of the original on every cold
     * start. Hooking the bar's constructor would be the natural place to catch
     * it, but Tinker means the class our loader resolves is not the one the live
     * view comes from, so the hook never fires. Matching on the class name from
     * a pre-draw listener sidesteps that entirely, and pre-draw is the last
     * point before anything reaches the screen.
     *
     * <p>The timeout hands the bar back if the pill never arrives: a permanently
     * invisible tab bar would be far worse than a flash.
     */
    private static void hideStockBarUntilInstalled(View decor) {
        decor.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private final long deadline =
                            android.os.SystemClock.uptimeMillis() + REVEAL_TIMEOUT_MS;
                    private View bar;

                    @Override
                    public boolean onPreDraw() {
                        if (bar == null || bar.getParent() == null) {
                            bar = TabBarBridge.findTabView(decor);
                        }
                        boolean installed = bar != null
                                && bar.getParent() instanceof LiquidGlassHostLayout;
                        boolean expired =
                                android.os.SystemClock.uptimeMillis() > deadline;
                        if (installed || expired) {
                            if (bar != null && !installed) {
                                bar.setAlpha(1f);
                                WeChatLiquidGlassModule.log(android.util.Log.WARN,
                                        "pill never took over, stock bar restored");
                            }
                            decor.getViewTreeObserver().removeOnPreDrawListener(this);
                        } else if (bar != null && bar.getAlpha() != 0f) {
                            bar.setAlpha(0f);
                        }
                        return true;
                    }
                });
    }

    static void scheduleInstall(Activity activity) {
        // Kept for extendUnderNavBar: WeChat's views hand out a context that does
        // not wrap the Activity, so the window is not reachable from them.
        sActivityRef = new WeakReference<>(activity);
        View decor = activity.getWindow().getDecorView();
        if (sHostRef.get() == null) {
            hideStockBarUntilInstalled(decor);
        }
        decor.post(() -> tryInstall(activity, decor, 0));
    }

    private static void tryInstall(Activity activity, View decor, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // Only skip if the pill is live in *this* window. WeChat's process
            // outlives a swipe-away from recents, so these statics still point at
            // the destroyed Activity's host — treating that as "already installed"
            // left the relaunched LauncherUI with its stock bar.
            LiquidGlassHostLayout live = sHostRef.get();
            if (live != null && live.isAttachedToWindow()
                    && live.getRootView() == decor.getRootView()) {
                // Nothing to install, but coming back from a chat or any other
                // screen rebuilds the window state: the navigation inset is
                // applied again and the dead strip at the bottom re-opens.
                reassertBottom(activity);
                return;
            }
            if (live != null) {
                resetState();
            }
            ViewGroup tabView = TabBarBridge.locateTabView(decor);
            if (tabView == null) {
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(
                            () -> tryInstall(activity, decor, attempt + 1),
                            RETRY_DELAY_MS);
                } else {
                    WeChatLiquidGlassModule.log(android.util.Log.WARN,
                            "tab bar not found after " + MAX_ATTEMPTS
                                    + " attempts, giving up; tree="
                                    + TabBarBridge.describeTree(decor));
                }
                return;
            }
            install(tabView);
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("install failed", t);
        }
    }

    /** Drops references to a previous Activity's views so a relaunch reinstalls. */
    private static void resetState() {
        sHostRef = new WeakReference<>(null);
        sTabViewRef = new WeakReference<>(null);
        sGlassRef = new WeakReference<>(null);
        sDropletRef = new WeakReference<>(null);
        sTabRowRef = new WeakReference<>(null);
        sPagerRef = new WeakReference<>(null);
        sDrag = null;
        sLastIndex = -1;
        sDropletBaseY = 0f;
        WeChatLiquidGlassModule.log(android.util.Log.INFO,
                "stale host from a previous Activity dropped, reinstalling");
    }

    private static void install(ViewGroup tabView) {
        ViewGroup parent = tabView.getParent() instanceof ViewGroup
                ? (ViewGroup) tabView.getParent() : null;
        if (parent == null) {
            return;
        }
        if (parent instanceof LiquidGlassHostLayout) {
            return; // already installed
        }

        ViewGroup backdrop = findBackdrop(parent, tabView);
        if (backdrop == null) {
            WeChatLiquidGlassModule.log(android.util.Log.WARN,
                    "no backdrop sibling found, glass would refract nothing");
            return;
        }

        Context ctx = tabView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int bottomOffset = Math.round(density * GlassConfig.barOffsetDp);

        stripSolidBackgrounds(tabView);
        boolean underNav = extendUnderNavBar(ctx);

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(ctx, backdrop, tabView);

        int index = parent.indexOfChild(tabView);
        int barHeight = tabView.getHeight();
        parent.removeView(tabView);

        // KernelSU's floating bar is width(IntrinsicSize.Min) — it hugs its tabs
        // and floats centred, rather than stretching edge to edge. The width has
        // to be resolved up front and pinned: leaving the host WRAP_CONTENT makes
        // the MATCH_PARENT glass layer measure to zero and vanish.
        // The shadow is drawn inside the host's own padding — setElevation never
        // renders anything in this view tree — so the host is inflated by that
        // much and pulled back down by the same amount.
        host.setupShadow(density, isNight(ctx));
        int shadowPad = host.shadowPad();

        int barWidth = hugContentWidth(TabBarBridge.findTabRow(tabView), density);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                barWidth > 0 ? barWidth + shadowPad * 2
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        // The navigation inset only counts once the window has been grown to
        // cover it — before that the pill's parent already stops above the
        // gesture bar, and adding it again floats the pill far too high.
        // Anchored on the parent, not the bar: the bar has already been detached
        // by this point and a detached view reports no insets at all, which
        // silently dropped the whole correction and let the bar sink onto the
        // gesture pill.
        hostLp.bottomMargin = bottomOffset - shadowPad
                + (underNav ? navInset(parent) : 0);
        parent.addView(host, index, hostLp);

        host.addView(tabView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight > 0 ? barHeight : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL));
        // Safe to show again: the glass goes in below it in this same pass, so
        // the two appear together.
        tabView.setAlpha(1f);

        sHostRef = new WeakReference<>(host);
        sTabViewRef = new WeakReference<>(tabView);
        sTabRowRef = new WeakReference<>(TabBarBridge.findTabRow(tabView));
        sPagerRef = new WeakReference<>(backdrop);
        sLastIndex = -1;

        // Open up clipping all the way to the content root: the droplet grows
        // past the pill while dragging, and any ancestor still clipping its
        // children would shear that overflow off.
        unclipAncestors(parent);
        attachRenderer(ctx, host, backdrop, density);
        installSelectionWatcher(host);
        watchBottomInset(host, backdrop);

        host.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean done;

                    @Override
                    public void onGlobalLayout() {
                        if (done) {
                            return;
                        }
                        done = true;
                        host.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        host.attach();
                        syncDropletSize(TabBarBridge.currentIndex(tabView));
                        extendPagesToBottom(backdrop);
                        // The window grows a beat after the flag is set, and the
                        // pages have to be re-stretched into the room that frees.
                        host.postDelayed(() -> extendPagesToBottom(backdrop), 500L);
                        WeChatLiquidGlassModule.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    private static final int EXTEND_TAG_KEY = 0x7F5A0001;

    /**
     * Lets every page's content run to the bottom of the screen, so the list
     * keeps rendering behind and below the floating pill.
     *
     * <p>WeChat sizes each page to stop where the docked bar used to start. Once
     * the bar floats, that band would otherwise show bare page background. The
     * content is stretched to the full height and the scrolling views get bottom
     * padding, so rows scroll through underneath the pill and the last one can
     * still clear it.
     *
     * <p>WeChat re-applies its own LayoutParams on later layout passes (that is
     * why the Contacts page kept snapping back), so each container also gets a
     * layout listener that re-stretches it whenever that happens.
     */
    private static void extendPagesToBottom(ViewGroup pager) {
        if (pager == null) {
            return;
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page instanceof ViewGroup) {
                extendOnePage((ViewGroup) page);
            }
        }
    }

    private static void extendOnePage(ViewGroup pg) {
        int pageHeight = pg.getHeight();
        if (pageHeight <= 0) {
            return;
        }
        // Some pages (Contacts) reserve the bar's height as padding on the page
        // itself rather than as a child margin, which caps the content at 2434
        // no matter what its LayoutParams say. Drop it and let the scrollers
        // carry the inset instead.
        if (pg.getPaddingBottom() > 0) {
            pg.setClipToPadding(false);
            pg.setPadding(pg.getPaddingLeft(), pg.getPaddingTop(),
                    pg.getPaddingRight(), 0);
        }
        for (int i = 0; i < pg.getChildCount(); i++) {
            View c = pg.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE
                    || c.getHeight() < pageHeight / 2) {
                continue;
            }
            stretchToBottom(c, pageHeight);
            if (Boolean.TRUE.equals(c.getTag(EXTEND_TAG_KEY))) {
                continue;
            }
            c.setTag(EXTEND_TAG_KEY, Boolean.TRUE);
            c.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
                ViewGroup parent = v.getParent() instanceof ViewGroup
                        ? (ViewGroup) v.getParent() : null;
                if (parent != null) {
                    stretchToBottom(v, parent.getHeight());
                }
            });
        }
        padScrollersBottom(pg, bottomReserve(pg), 0);
    }

    /** Breathing room between the last row and the pill once it is scrolled clear. */
    private static final float LAST_ROW_GAP_DP = 8f;

    /**
     * Room the floating bar takes up at the bottom of the screen.
     *
     * <p>Measured off the pill rather than assembled from the constants that
     * position it, so it stays right whatever the bar height, the float offset
     * or the navigation inset turn out to be.
     */
    private static int bottomReserve(View anchor) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host == null || host.getHeight() <= 0) {
            return 0;
        }
        host.getLocationOnScreen(sLoc);
        // Undo the offset followBarOffset applies while the bar slides away.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return 0;
        }
        root.getLocationOnScreen(sLoc);
        float density = anchor.getResources().getDisplayMetrics().density;
        float reserve = (sLoc[1] + root.getHeight()) - pillTop
                + LAST_ROW_GAP_DP * density;
        return reserve > 0f ? Math.round(reserve) : 0;
    }

    private static void stretchToBottom(View c, int pageHeight) {
        if (pageHeight <= 0) {
            return;
        }
        int gap = pageHeight - c.getBottom();
        if (gap <= 8) {
            return; // already reaches the bottom
        }
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        boolean changed = false;
        if (mlp.bottomMargin != 0) {
            mlp.bottomMargin = 0;
            changed = true;
        }
        if (mlp.height >= 0) {
            mlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            changed = true;
        }
        if (changed) {
            c.setLayoutParams(mlp);
        }
    }

    /**
     * Gives every scrolling view in the subtree room to scroll its last row clear
     * of the floating pill, with {@code clipToPadding=false} so rows still render
     * through the padded band — i.e. behind and below the pill.
     */
    private static void padScrollersBottom(ViewGroup root, int pad, int depth) {
        if (depth > 6) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (isScroller(c)) {
                ViewGroup sv = (ViewGroup) c;
                // clipToPadding is re-asserted even when the amount already
                // matches: WeChat turns it back on, and with it on the padded
                // band goes blank instead of showing rows through the glass.
                if (sv.getClipToPadding()) {
                    sv.setClipToPadding(false);
                }
                if (sv.getPaddingBottom() != pad) {
                    sv.setPadding(sv.getPaddingLeft(), sv.getPaddingTop(),
                            sv.getPaddingRight(), pad);
                }
            } else if (c instanceof ViewGroup) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            }
        }
    }

    /**
     * Recognises scrolling containers without compile-time access to androidx,
     * walking the superclass chain so WeChat's own subclasses match too.
     */
    private static boolean isScroller(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        if (v instanceof android.widget.ScrollView
                || v instanceof android.widget.AbsListView) {
            return true;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if ("androidx.recyclerview.widget.RecyclerView".equals(n)
                    || "androidx.core.widget.NestedScrollView".equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Lets the droplet's overflow escape every ancestor up to the content view. */
    private static void unclipAncestors(ViewGroup from) {
        ViewGroup v = from;
        for (int i = 0; i < 12 && v != null; i++) {
            v.setClipChildren(false);
            v.setClipToPadding(false);
            if (v.getId() == android.R.id.content) {
                return;
            }
            android.view.ViewParent p = v.getParent();
            v = p instanceof ViewGroup ? (ViewGroup) p : null;
        }
    }

    /**
     * Replaces WeChat's equal-weight tab columns with fixed, content-sized ones.
     *
     * <p>WeChat gives each tab {@code LinearLayout.LayoutParams(0, h, weight=1)},
     * which only makes sense when the bar spans the screen. For a floating pill
     * the width has to come from the content instead — mirroring KernelSU, where
     * the row is {@code IntrinsicSize.Min} and every tab shares the widest
     * column's width.
     */
    private static int hugContentWidth(ViewGroup tabRow, float density) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0;
        }
        int count = tabRow.getChildCount();
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int widest = 0;
        for (int i = 0; i < count; i++) {
            View tab = tabRow.getChildAt(i);
            tab.measure(unspecified, unspecified);
            widest = Math.max(widest, tab.getMeasuredWidth());
        }
        if (widest <= 0) {
            return 0;
        }
        // WeChat's labels are narrower than KernelSU's, so hugging them exactly
        // gives a cramped 46%-wide bar. Matching KernelSU's proportions means
        // giving each column the same generous breathing room it uses (~32dp per
        // side), then capping so the pill still clears the screen edges.
        int pad = Math.round(density * 4f);
        int tabWidth = widest + Math.round(density * 32f);
        int screen = tabRow.getResources().getDisplayMetrics().widthPixels;
        int maxTotal = screen - Math.round(density * 24f);
        if (tabWidth * count + pad * 2 > maxTotal) {
            tabWidth = (maxTotal - pad * 2) / count;
        }
        for (int i = 0; i < count; i++) {
            View tab = tabRow.getChildAt(i);
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            lp.width = tabWidth;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) lp).weight = 0f;
            }
            tab.setLayoutParams(lp);
        }
        // Horizontal inset only: the bar's height is WeChat's own and adding
        // vertical padding pushes the tabs out through the bottom of the pill.
        tabRow.setPadding(pad, 0, pad, 0);
        int total = tabWidth * count + pad * 2;
        WeChatLiquidGlassModule.log(android.util.Log.INFO,
                "tab row hugged: content=" + widest + " tabWidth=" + tabWidth
                        + " total=" + total + " screen=" + screen);
        return total;
    }

    /**
     * Keeps the glass and shadow travelling with WeChat's own bar.
     *
     * <p>WeChat slides the bar out of the way with {@code translationY} — that is
     * how it gets out of the way for the mini-program panel — and fades it with
     * alpha. Since only the bar itself was reparented into the host, those
     * properties would otherwise move the tabs while the glass and its shadow
     * stayed put. Mirroring them onto our own layers keeps the pill whole, and
     * reading rather than overwriting them leaves WeChat's animator alone.
     */
    /**
     * How much further than WeChat the bar has to travel to leave the screen,
     * as a fraction of WeChat's own travel. Zero once the pill already clears.
     */
    private static float hideShortfall(LiquidGlassHostLayout host, View tabView) {
        float travel = tabView.getHeight();
        if (travel <= 0f) {
            return 0f;
        }
        host.getLocationOnScreen(sLoc);
        // Undo the offset this very method applied, so the reference stays put.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        root.getLocationOnScreen(sLoc);
        float need = sLoc[1] + root.getHeight() - pillTop;
        return Math.max(0f, need / travel - 1f);
    }

    private static void followBarOffset(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return;
        }
        float ty = tabView.getTranslationY();
        float alpha = tabView.getAlpha();
        boolean gone = tabView.getVisibility() != View.VISIBLE;

        // WeChat slides its bar down by exactly its own height, which was enough
        // to clear the bottom of the screen when the bar sat flush against it.
        // The pill floats above that, so the same travel leaves a slice of it
        // still showing when WeChat gives up and hides the bar outright — it
        // vanishes mid-slide. The shortfall is added to the host rather than to
        // the glass: the tab icons are carried by WeChat's own translation, and
        // moving the glass alone would slide it out from under them.
        // Only worth computing while the bar is actually moving.
        host.setTranslationY(ty == 0f ? 0f : hideShortfall(host, tabView) * ty);

        View glass = sGlassRef.get();
        if (glass != null && glass.getTranslationY() != ty) {
            glass.setTranslationY(ty);
        }
        View droplet = sDropletRef.get();
        if (droplet != null) {
            droplet.setTranslationY(sDropletBaseY + ty);
            droplet.setAlpha(gone ? 0f : alpha);
        }
        if (glass != null) {
            glass.setAlpha(gone ? 0f : alpha);
        }
        host.setShadowOffsetY(gone ? Float.MAX_VALUE : ty, alpha);
    }

    /**
     * Tracks the selected tab and the visible pager page once per frame.
     *
     * <p>Both have to be polled rather than hooked: {@code setTo(int)} does not
     * fire on tab taps, and the pager scrolls between fixed child offsets instead
     * of restacking them, so nothing notifies us when the page under the glass
     * changes.
     */
    /**
     * Holds the navigation bar transparent.
     *
     * <p>WeChat's chat screen opens inside LauncherUI and paints the navigation
     * bar opaque on its way in, then leaves it that way — which puts a solid
     * strip back over the content the pill floats above. Nothing tells us when
     * that happens (no Activity change, no relayout), so it is simply checked
     * whenever the bar draws; the read is a field access and the write only
     * happens when WeChat has actually changed it.
     */
    private static WeakReference<View> sNavBgRef = new WeakReference<>(null);
    private static int sNavBgId = -1;

    /** The DecorView's navigation-bar backdrop, looked up by its framework id. */
    private static View navBarBackground(View decor) {
        View v = sNavBgRef.get();
        if (v != null && v.getParent() != null) {
            return v;
        }
        if (sNavBgId == -1) {
            sNavBgId = decor.getResources().getIdentifier(
                    "navigationBarBackground", "id", "android");
        }
        if (sNavBgId == 0) {
            return null;
        }
        v = decor.findViewById(sNavBgId);
        sNavBgRef = new WeakReference<>(v);
        return v;
    }

    /**
     * Keeps the navigation bar from covering the content.
     *
     * <p>WeChat's chat screen opens inside LauncherUI — no Activity change, no
     * window focus change — and paints the navigation bar opaque on its way in,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: the system bar drawn over content that already reaches the
     * edge, not a layout gap.
     *
     * <p>Setting the colour back is no use here. On HyperOS the value reads
     * straight back as WeChat's, so the write never lands. The bar's backdrop is
     * an ordinary View inside the DecorView though, and hiding that is entirely
     * ours to do — and it holds, because the DecorView has to run a draw pass to
     * show it again, and this runs first in every one of them.
     */
    private static void keepNavBarClear() {
        Activity a = sActivityRef.get();
        if (a == null) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        View navBg = navBarBackground(decor);
        if (navBg != null && navBg.getVisibility() != View.GONE) {
            navBg.setVisibility(View.GONE);
        }
        // Checked separately: losing this flag shrinks the window back above the
        // gesture bar, which is a real gap rather than something drawn over.
        int vis = decor.getSystemUiVisibility();
        if ((vis & View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
            decor.setSystemUiVisibility(
                    vis | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private static void installSelectionWatcher(LiquidGlassHostLayout host) {
        host.getViewTreeObserver().addOnPreDrawListener(() -> {
            // First, and on its own: anything below must not be able to stop the
            // navigation bar being held clear.
            try {
                keepNavBarClear();
            } catch (Throwable t) {
                // Once only: this sits on a per-frame path.
                if (!sKeepFailed) {
                    sKeepFailed = true;
                    WeChatLiquidGlassModule.logErr("keepNavBarClear failed", t);
                }
            }
            try {
                followBarOffset(host);
                ViewGroup tabRow = sTabRowRef.get();
                int sel = TabBarBridge.selectedIndex(tabRow);
                if (sel >= 0 && sel != sLastIndex) {
                    boolean first = sLastIndex < 0;
                    sLastIndex = sel;
                    syncDropletSize(sel);
                    if (sDrag != null) {
                        // KernelSU animates programmatic switches the same way as
                        // drags: press, travel, release.
                        sDrag.animateToIndex(sel, first);
                    }
                    ViewGroup pgr = sPagerRef.get();
                    if (pgr != null) {
                        pgr.post(() -> extendPagesToBottom(pgr));
                    }
                }
            } catch (Throwable ignored) {
            }
            return true;
        });
    }

    /**
     * The sibling the glass refracts. WeChat puts the pager and the tab bar under
     * the same parent, so the backdrop is whichever sibling is not the bar itself
     * and actually covers the screen.
     */
    private static ViewGroup findBackdrop(ViewGroup parent, View tabView) {
        ViewGroup best = null;
        int bestArea = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || !(c instanceof ViewGroup)
                    || c.getVisibility() != View.VISIBLE) {
                continue;
            }
            int area = c.getWidth() * c.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = (ViewGroup) c;
            }
        }
        return best;
    }

    /**
     * WeChat paints the bar's opaque colour on the inner LinearLayout
     * ({@code E.setBackgroundColor(...)}). Only flat colour fills are removed —
     * anything else (badge shapes, ripples) is left alone.
     */
    private static void stripSolidBackgrounds(View v) {
        // The bar and its row are drawn by the glass now, so clear their
        // backgrounds outright rather than only flat fills: WeChat paints the
        // bar's hairline top divider through a non-ColorDrawable background, and
        // leaving it in draws a stray line across the top of the pill that stops
        // short at the row's padding edge.
        if (v.getBackground() != null) {
            v.setBackground(null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            // Down to the tab columns themselves: each carries a top hairline,
            // and the four of them line up into a divider spanning the row's
            // content box (214..1006 px) right across the pill's top edge.
            // Their children keep theirs (badges, icon states).
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c.getBackground() != null) {
                    c.setBackground(null);
                }
                if (c instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup) c;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View tab = row.getChildAt(j);
                        if (tab.getBackground() != null) {
                            tab.setBackground(null);
                        }
                    }
                }
            }
        }
    }

    private static final int EDGE_TAG_KEY = 0x7F5A0002;

    /**
     * Lets the pages draw under the gesture bar.
     *
     * <p>WeChat's window stops at the navigation bar, so once the tab bar is
     * lifted off the bottom there is a dead strip below it that nothing ever
     * paints. Asking for the hide-navigation layout grows the window to the
     * whole screen; the navigation inset is then stripped on its way down the
     * tree, because otherwise WeChat's own containers just pad the same gap
     * straight back in.
     *
     * @return whether the window was actually grown
     */
    private static boolean extendUnderNavBar(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        try {
            Activity activity = activityOf(ctx);
            if (activity == null) {
                activity = sActivityRef.get();
            }
            if (activity == null) {
                WeChatLiquidGlassModule.log(android.util.Log.WARN,
                        "no Activity for the window, bottom strip stays blank");
                return false;
            }
            android.view.Window w = activity.getWindow();
            View decor = w.getDecorView();
            boolean first = !Boolean.TRUE.equals(decor.getTag(EDGE_TAG_KEY));

            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
            android.view.WindowInsetsController ctrl = w.getInsetsController();
            if (ctrl != null) {
                // The gesture pill now sits on WeChat's own content rather than
                // on a system background, so it has to contrast with that.
                int light = android.view.WindowInsetsController
                        .APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(isNight(ctx) ? 0 : light, light);
            }
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (first) {
                decor.setTag(EDGE_TAG_KEY, Boolean.TRUE);
                decor.setOnApplyWindowInsetsListener((v, insets) -> {
                    WindowInsets stripped = new WindowInsets.Builder(insets)
                            .setInsets(WindowInsets.Type.navigationBars(),
                                    android.graphics.Insets.NONE)
                            .build();
                    return v.onApplyWindowInsets(stripped);
                });
                WeChatLiquidGlassModule.log(android.util.Log.INFO,
                        "window extended under the navigation bar, inset="
                                + navInset(decor));
            }
            decor.requestApplyInsets();
            return true;
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("could not extend under the nav bar", t);
            return false;
        }
    }

    /**
     * Keeps the bottom strip filled without guessing at timings.
     *
     * <p>Coming back from a chat re-runs WeChat's own window setup, and it does
     * so on its own schedule — a delayed one-shot after resume sometimes lands
     * before WeChat has finished handing the navigation inset back. Watching the
     * pager's layout and the window's focus catches it whenever it happens.
     */
    private static void watchBottomInset(LiquidGlassHostLayout host, ViewGroup backdrop) {
        backdrop.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            extendPagesToBottom(backdrop);
            // Catches the transition frame itself rather than waiting for the
            // bar's next draw, so the strip never flashes on the way back.
            keepNavBarClear();
        });
        host.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus) {
                return;
            }
            Activity a = sActivityRef.get();
            if (a != null) {
                reassertBottom(a);
            }
        });
    }

    /**
     * Re-applies the edge-to-edge window state and re-stretches the pages.
     *
     * <p>Runs on every resume of an already-installed window. WeChat restores
     * its own system-ui flags when a secondary screen closes, which hands the
     * navigation inset straight back to its containers.
     */
    private static void reassertBottom(Activity activity) {
        if (!extendUnderNavBar(activity)) {
            return;
        }
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        pager.post(() -> extendPagesToBottom(pager));
        // The insets land a frame or two after the window is re-shown, and the
        // pages can only be stretched once the room is actually there.
        pager.postDelayed(() -> extendPagesToBottom(pager), 400L);
    }



    /**
     * Stops WeChat repainting the navigation bar over the content.
     *
     * <p>The chat screen opens inside LauncherUI — no Activity change, no window
     * focus change — and on its way in it paints the navigation bar opaque,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: not a layout gap, the system bar itself drawn over content
     * that already reaches the edge.
     *
     * <p>Correcting it after the fact is not enough. There is nothing to react
     * to, and a per-frame check only runs while something is being drawn — once
     * the list settles, no frames, no correction. So the call itself is
     * swallowed for this one window instead.
     */

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static int navInset(View anchor) {
        try {
            WindowInsets insets = anchor.getRootWindowInsets();
            if (insets == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            return insets.getSystemWindowInsetBottom();
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ---------------- renderer ---------------- */

    private static boolean isNight(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The flat wash the droplet shows at rest.
     *
     * <p>KernelSU: {@code drawRect(black @ 0.1f)} on light, white on dark —
     * no lens until the droplet is actually pressed.
     */
    static android.graphics.drawable.Drawable restingDropletDrawable(boolean night) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(9999f);
        d.setColor(night ? 0x1AFFFFFF : 0x1A000000);
        return d;
    }

    private static void attachRenderer(Context ctx, LiquidGlassHostLayout host,
                                       ViewGroup backdrop, float density) {
        if (Build.VERSION.SDK_INT < 33) {
            WeChatLiquidGlassModule.log(android.util.Log.INFO,
                    "SDK < 33, staying on the legacy frost path");
            return;
        }
        try {
            boolean night = isNight(ctx);

            // The glass surface is a direct port of KernelSU's effect stack,
            // see LiquidGlassPanel.
            final LiquidGlassPanel glass =
                    new LiquidGlassPanel(ctx, backdrop, density, night);
            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // The droplet goes on top of the tabs, not under them: it refracts a
            // separately drawn, enlarged copy of the tab row, and that refracted
            // copy is what should be visible inside it — same as KernelSU.
            final DropletPanel droplet = new DropletPanel(
                    ctx, backdrop, sTabRowRef.get(), density, night);
            droplet.setVisibility(View.INVISIBLE);
            host.addView(droplet, new FrameLayout.LayoutParams(0, 0,
                    android.view.Gravity.TOP | android.view.Gravity.START));
            droplet.setPill(glass);
            sDropletRef = new WeakReference<>(droplet);
            // The droplet scales past the pill's bounds while held (78/56). Both
            // flags matter: FrameLayout defaults clipToPadding to true, and the
            // host carries 14dp of shadow padding — that alone was shearing off
            // exactly the overflow we wanted to show.
            host.setClipChildren(false);
            host.setClipToPadding(false);
            sGlassRef = new WeakReference<>(glass);

            host.setGlassTuner(new LiquidGlassHostLayout.GlassTuner() {
                @Override
                public void onSize(int w, int h, float cornerRadius) {
                    glass.invalidate();
                }

                @Override
                public void onTheme(boolean dark) {
                    glass.setTheme(dark);
                    droplet.setBackground(restingDropletDrawable(dark));
                }
            });

            ViewGroup tabRow = sTabRowRef.get();
            if (tabRow != null) {
                sDrag = new DropletDragController(droplet, tabRow, density, night);
                sDrag.setPill(glass);
                sDrag.setHost(host);
                host.setDragHandler(sDrag);
            }

            // The backdrop is re-captured on each draw, so the glass follows the
            // page behind it.
            host.getViewTreeObserver().addOnPreDrawListener(() -> {
                glass.invalidate();
                return true;
            });

            WeChatLiquidGlassModule.log(android.util.Log.INFO,
                    "renderer=KernelSU-style lens (saturation+blur+SDF refraction)"
                            + " supported=" + glass.isSupported()
                            + " drag=" + (tabRow != null));
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("glass renderer unavailable", t);
        }
    }


    /* ---------------- droplet ---------------- */

    /**
     * Called from the {@code setTo(int)} hook on every in-app page switch.
     *
     * <p>This doubles as the install trigger: WeChat calls setTo once during
     * startup to select the initial tab, and by then the bar is guaranteed to
     * exist — which the decor-view polling cannot guarantee.
     */
    static void onTabChanged(View tabView, int index) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && !host.isAttachedToWindow()) {
            resetState();
            host = null;
        }
        if (host == null || host.getParent() == null || tabView.getParent() != host) {
            // Not ours (yet). Either the first call of this process, or a fresh
            // LauncherUI instance after the old one was destroyed.
            if (tabView instanceof ViewGroup && tabView.getParent() != null) {
                tabView.post(() -> {
                    try {
                        install((ViewGroup) tabView);
                        syncDropletSize(index);
                    } catch (Throwable t) {
                        WeChatLiquidGlassModule.logErr("install from setTo failed", t);
                    }
                });
            }
            return;
        }
        host.post(() -> {
            syncDropletSize(index);
            if (sDrag != null) {
                sDrag.animateToIndex(index, false);
            }
        });
    }

    /**
     * Sizes and vertically places the droplet for the given tab. Horizontal
     * position and all motion belong to {@link DropletDragController}'s springs.
     */
    private static void syncDropletSize(int index) {
        try {
            View droplet = sDropletRef.get();
            ViewGroup tabRow = sTabRowRef.get();
            LiquidGlassHostLayout host = sHostRef.get();
            if (droplet == null || tabRow == null || host == null || index < 0) {
                return;
            }
            View tab = TabBarBridge.tabAt(tabRow, index);
            if (tab == null || tab.getWidth() == 0) {
                return;
            }
            float density = host.getResources().getDisplayMetrics().density;
            // KernelSU sizes the droplet to the full tab column: width = tabWidth,
            // height = bar height - 2 * 4dp padding.
            int inset = Math.round(density * 4f);
            int w = tab.getWidth();
            int h = tab.getHeight() - inset * 2;
            if (w <= 0 || h <= 0) {
                return;
            }
            ViewGroup.LayoutParams lp = droplet.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                droplet.setLayoutParams(lp);
            }
            sDropletBaseY = tab.getTop() + tabRow.getTop() + inset;
            droplet.setTranslationY(sDropletBaseY);
            droplet.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("droplet sizing failed", t);
        }
    }
}
