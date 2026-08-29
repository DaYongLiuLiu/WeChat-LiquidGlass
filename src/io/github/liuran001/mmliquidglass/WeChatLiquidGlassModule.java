package io.github.liuran001.mmliquidglass;

import android.app.Activity;
import android.app.Instrumentation;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;

public class WeChatLiquidGlassModule extends XposedModule {

    static final String TAG = "WeChatLiquidGlass";

    static final String TARGET_PKG = "com.tencent.mm";
    /** WeChat's home activity; hosts the bottom tab bar we replace. */
    private static final String LAUNCHER_ACTIVITY = "com.tencent.mm.ui.LauncherUI";

    private static volatile int sResumeHits;
    private static volatile WeChatLiquidGlassModule sSelf;

    public WeChatLiquidGlassModule() {
        super();
        sSelf = this;
    }

    /** Hooks an executable, running fn AFTER the original and ignoring its result. */
    static void hookAfter(java.lang.reflect.Executable ex, AfterCallback fn) {
        WeChatLiquidGlassModule self = sSelf;
        if (self == null) {
            throw new IllegalStateException("module instance not attached yet");
        }
        self.hook(ex)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        fn.after(chain);
                    } catch (Throwable t) {
                        logErr("after-hook failed", t);
                    }
                    return result;
                });
    }

    interface AfterCallback {
        void after(XposedInterface.Chain chain) throws Throwable;
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String proc = param.getProcessName();
        log(android.util.Log.INFO, "onModuleLoaded process=" + proc
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
        // WeChat is heavily multi-process (:push, :tools, :appbrandX, ...).
        // LauncherUI lives in the main process only; everything else detaches.
        if (!TARGET_PKG.equals(proc)) {
            log(android.util.Log.INFO, "not the main process, detach");
            detach();
            return;
        }
        try {
            Method callOnResume = Instrumentation.class.getMethod(
                    "callActivityOnResume", Activity.class);
            hook(callOnResume)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof Activity) {
                                Activity activity = (Activity) arg0;
                                String name = arg0.getClass().getName();
                                if (LAUNCHER_ACTIVITY.equals(name)) {
                                    GlassConfig.load(activity);
                                    sResumeHits++;
                                    if (sResumeHits <= 3 || sResumeHits % 20 == 0) {
                                        log(android.util.Log.INFO,
                                                "LauncherUI onResume #" + sResumeHits);
                                    }
                                    LiquidGlassInstaller.scheduleInstall(activity);
                                }
                            }
                        } catch (Throwable t) {
                            logErr("resume hook error", t);
                        }
                        return result;
                    });
            log(android.util.Log.INFO, "hooked Instrumentation.callActivityOnResume");
        } catch (Throwable t) {
            logErr("install resume hook failed", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        log(android.util.Log.INFO, "target package loaded, classLoader="
                + param.getDefaultClassLoader());
        // The tab bar bridge needs WeChat's own classes, so it can only be
        // wired once the app class loader exists.
        TabBarBridge.install(param.getDefaultClassLoader());
    }

    static void log(int prio, String msg) {
        android.util.Log.println(prio, TAG, msg);
    }

    static void logErr(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
    }
}
