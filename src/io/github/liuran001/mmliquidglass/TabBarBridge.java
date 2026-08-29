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

    /** Depth-first search for WeChat's tab bar under {@code root}. */
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
        return null;
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
