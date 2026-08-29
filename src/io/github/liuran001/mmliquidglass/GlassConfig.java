package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one tunable the bar exposes, read from WeChat's own SharedPreferences.
 *
 * <p>Nothing in the module writes this file — there is no settings UI — but it
 * lets the float height be changed without a rebuild, which is the only value
 * that is really a matter of taste.
 */
final class GlassConfig {

    private static final String PREFS = "wx_liquid_glass_cfg";

    /** Distance between the bottom of the glass pill and the screen edge, dp. */
    static volatile int barOffsetDp = 12;

    private GlassConfig() {
    }

    static void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
        } catch (Throwable t) {
            WeChatLiquidGlassModule.logErr("config load failed", t);
        }
    }
}
