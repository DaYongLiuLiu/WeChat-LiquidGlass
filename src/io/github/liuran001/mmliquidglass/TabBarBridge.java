package io.github.liuran001.mmliquidglass;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * Bridge to WeChat's own bottom tab bar.
 *
 * <p>WeChat obfuscates resource ids (AndResGuard turns them into {@code app:id/huj}),
 * so nothing here may look an id up by name. The UI class names survive
 * obfuscation, and so does the {@code t1} interface the tab bar implements:
 *
 * <pre>
 * class LauncherUIBottomTabView extends RelativeLayout implements t1
 * interface t1 { int getCurIdx(); void setTo(int); void setOnTabClickListener(s1); }
 * </pre>
 *
 * {@code setTo(int)} is what the app calls on every in-app page switch, which is
 * exactly the signal the droplet animation needs.
 */
final class TabBarBridge {

    static final String TAB_VIEW_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView";

    private static volatile boolean sHooked;

    private TabBarBridge() {
    }

    static void install(ClassLoader cl) {
        if (sHooked) {
            return;
        }
        try {
            Class<?> cls = cl.loadClass(TAB_VIEW_CLASS);
            Method setTo = cls.getMethod("setTo", int.class);
            WeChatLiquidGlassModule.hookAfter(setTo, chain -> {
                Object thiz = chain.getThisObject();
                Object arg0 = chain.getArg(0);
                if (thiz instanceof View && arg0 instanceof Integer) {
                    LiquidGlassInstaller.onTabChanged((View) thiz, (Integer) arg0);
                }
            });
            sHooked = true;
            WeChatLiquidGlassModule.log(android.util.Log.INFO,
                    "hooked " + TAB_VIEW_CLASS + ".setTo(int)");
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr(
                    "tab bar bridge unavailable (WeChat layout changed?)", t);
        }
    }

    /**
     * Matches by class name rather than {@code isInstance}. WeChat ships Tinker
     * hot-patching, so the loader that resolved our hook target is not
     * necessarily the loader the live view came from — an identity check silently
     * fails there, while the name always holds.
     */
    static boolean isTabView(View v) {
        return v != null && TAB_VIEW_CLASS.equals(v.getClass().getName());
    }

    /** Tabs a bottom bar can plausibly have. */
    private static final int MIN_TABS = 3;
    private static final int MAX_TABS = 5;
    /** A tab has to be tall enough to stack an icon over a label. */
    private static final float MIN_TAB_HEIGHT_DP = 32f;

    /**
     * Locates the tab bar, by class name first and by shape only as a fallback.
     *
     * <p>The name is what actually holds today, and it is exact. The structural
     * pass exists for the day WeChat renames the class: it is deliberately
     * strict rather than best-effort, because the two failure modes are not
     * comparable. Finding nothing leaves WeChat with its own bar and costs the
     * user a feature; latching onto the wrong row would reparent some unrelated
     * control into a floating pill and break the app.
     */
    static ViewGroup locateTabView(View root) {
        ViewGroup byName = findTabView(root);
        if (byName != null) {
            return byName;
        }
        ViewGroup row = findTabRowByShape(root);
        if (row == null) {
            return null;
        }
        ViewGroup host = tightestWrapper(row);
        WeChatLiquidGlassModule.log(android.util.Log.WARN,
                "tab bar class not found; matched by shape instead: "
                        + host.getClass().getName()
                        + " tabs=" + row.getChildCount());
        return host;
    }

    /**
     * Whether this group is laid out the way a bottom tab row is.
     *
     * <p>Every one of these has to hold. The geometry alone would still admit a
     * toolbar or a row of action buttons, so it is the last test that decides:
     * either the children carry their own index as a tag, or exactly one of them
     * is selected. Ordinary button rows do neither.
     */
    private static boolean looksLikeTabRow(View v) {
        if (!(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE
                || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        ViewGroup g = (ViewGroup) v;
        View first = null;
        int prevRight = Integer.MIN_VALUE;
        int tabs = 0;
        int selected = 0;
        boolean indexTagged = true;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (first == null) {
                first = c;
            } else if (Math.abs(c.getWidth() - first.getWidth()) > 2) {
                return false; // tabs share one width
            }
            if (c.getLeft() < prevRight) {
                return false; // side by side, in order, not overlapping
            }
            prevRight = c.getRight();
            Object tag = c.getTag();
            if (!(tag instanceof Integer) || (Integer) tag != i) {
                indexTagged = false;
            }
            if (c.isSelected()) {
                selected++;
            }
            tabs++;
        }
        if (first == null || tabs < MIN_TABS || tabs > MAX_TABS) {
            return false;
        }
        View root = v.getRootView();
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return false;
        }
        if (v.getWidth() < root.getWidth() * 0.6f) {
            return false; // a tab bar spans most of the screen
        }
        float density = v.getResources().getDisplayMetrics().density;
        if (first.getHeight() < MIN_TAB_HEIGHT_DP * density) {
            return false;
        }
        int[] loc = new int[2];
        int[] rootLoc = new int[2];
        v.getLocationOnScreen(loc);
        root.getLocationOnScreen(rootLoc);
        float fromBottom = (rootLoc[1] + root.getHeight()) - (loc[1] + v.getHeight());
        if (fromBottom > root.getHeight() * 0.25f) {
            return false; // and sits at the bottom of it
        }
        return indexTagged || selected == 1;
    }

    /** Lowest group on screen that passes {@link #looksLikeTabRow}. */
    private static ViewGroup findTabRowByShape(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (root instanceof LiquidGlassHostLayout) {
            return null; // our own bar, already installed
        }
        ViewGroup best = looksLikeTabRow(root) ? (ViewGroup) root : null;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup found = findTabRowByShape(g.getChildAt(i));
                if (found != null && (best == null || lowerOnScreen(found, best))) {
                    best = found;
                }
            }
        }
        return best;
    }

    private static boolean lowerOnScreen(View a, View b) {
        int[] la = new int[2];
        int[] lb = new int[2];
        a.getLocationOnScreen(la);
        b.getLocationOnScreen(lb);
        return la[1] + a.getHeight() > lb[1] + b.getHeight();
    }

    /**
     * The smallest container that wraps the row, which is what gets reparented.
     *
     * <p>Stops as soon as an ancestor is taller than the row by any real margin:
     * past that it is a page, not the bar.
     */
    private static ViewGroup tightestWrapper(ViewGroup row) {
        ViewGroup best = row;
        android.view.ViewParent p = row.getParent();
        while (p instanceof ViewGroup && !(p instanceof LiquidGlassHostLayout)) {
            ViewGroup g = (ViewGroup) p;
            if (g.getHeight() > row.getHeight() * 1.6f) {
                break;
            }
            best = g;
            p = g.getParent();
        }
        return best;
    }

    /** Depth-first search for WeChat's tab bar under {@code root}, by class name. */
    static ViewGroup findTabView(View root) {
        if (isTabView(root)) {
            return root instanceof ViewGroup ? (ViewGroup) root : null;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            ViewGroup found = findTabView(vg.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The horizontal row holding the four tabs. WeChat builds it in code as a
     * plain LinearLayout child of the tab view, so it carries no id at all.
     */
    static ViewGroup findTabRow(ViewGroup tabView) {
        if (tabView == null) {
            return null;
        }
        for (int i = 0; i < tabView.getChildCount(); i++) {
            View c = tabView.getChildAt(i);
            if (c instanceof LinearLayout
                    && ((LinearLayout) c).getOrientation() == LinearLayout.HORIZONTAL
                    && ((ViewGroup) c).getChildCount() >= 2) {
                return (ViewGroup) c;
            }
        }
        // Shape-matched bars can have no wrapper at all, in which case the view
        // handed in is already the row.
        return looksLikeTabRow(tabView) ? tabView : null;
    }

    /**
     * Bounds of the tab at {@code index} relative to the tab row. Each tab root
     * carries its index as an Integer tag (WeChat: {@code d.setTag(valueOf(i))}),
     * which is more reliable than positional order.
     */
    static View tabAt(ViewGroup tabRow, int index) {
        if (tabRow == null) {
            return null;
        }
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View c = tabRow.getChildAt(i);
            Object tag = c.getTag();
            if (tag instanceof Integer && (Integer) tag == index) {
                return c;
            }
        }
        // Tag missing (layout changed): fall back to child order.
        return index >= 0 && index < tabRow.getChildCount()
                ? tabRow.getChildAt(index) : null;
    }

    /** Lists the WeChat-owned view classes under {@code root}, for miss diagnosis. */
    static String describeTree(View root) {
        StringBuilder sb = new StringBuilder();
        collectNames(root, sb, 0);
        return sb.length() == 0 ? "(no com.tencent.mm views)" : sb.toString();
    }

    private static void collectNames(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 30 || sb.length() > 2000) {
            return;
        }
        String n = v.getClass().getName();
        if (n.startsWith("com.tencent.mm.ui.") || n.contains("TabView")) {
            sb.append(depth).append(':').append(n).append(' ');
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectNames(vg.getChildAt(i), sb, depth + 1);
            }
        }
    }

    /**
     * Index of the visually selected tab, read straight off the view state.
     *
     * <p>WeChat's {@code setTo(int)} turns out not to fire on ordinary tab taps,
     * so the selection has to be observed rather than intercepted. Every tab root
     * gets {@code setSelected(true/false)} on each switch, which is both reliable
     * and free to poll.
     */
    static int selectedIndex(ViewGroup tabRow) {
        if (tabRow == null) {
            return -1;
        }
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View c = tabRow.getChildAt(i);
            if (c.isSelected()) {
                Object tag = c.getTag();
                return tag instanceof Integer ? (Integer) tag : i;
            }
        }
        return -1;
    }

    static int currentIndex(View tabView) {
        try {
            Method m = tabView.getClass().getMethod("getCurIdx");
            Object v = m.invoke(tabView);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
