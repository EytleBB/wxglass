package dev.wxglass;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Entry point: binds the window/decor hooks and, lazily, the text-paint hook
 * used to recolour outgoing bubble text in dark mode.
 */
public class GlassModule extends XposedModule {

    static final String TAG = "WxGlass";
    static final String TARGET = "com.tencent.mm";

    private static GlassModule sInstance;
    private static final Set<String> sLogged = new HashSet<>();
    private static final Map<Class<?>, List<Field>> sPaintFields = new HashMap<>();
    private static final Map<Class<?>, List<Field>> sIntFields = new HashMap<>();
    private static int sPaintLog;
    private static int sTextCalls;
    private static boolean sTextHooked;
    static final ThreadLocal<Boolean> sInBubbleDraw = new ThreadLocal<>();
    private static final ThreadLocal<Paint> sBubblePaint = new ThreadLocal<>();

    public GlassModule() {
        sInstance = this;
    }

    static void logErr(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Log.i(TAG, "loaded process=" + param.getProcessName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        sInstance = this;
        Log.i(TAG, "package loaded " + param.getPackageName() + " first=" + param.isFirstPackage());

        try {
            Class<?> viewRootImpl = Class.forName("android.view.ViewRootImpl");
            Method setView = viewRootImpl.getDeclaredMethod("setView", View.class,
                    WindowManager.LayoutParams.class, View.class);
            hook(setView).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof View) {
                        View decor = (View) arg;
                        String name = decor.getClass().getName();
                        synchronized (sLogged) {
                            if (sLogged.add("V:" + name)) Log.i(TAG, "window " + name);
                        }
                        GlassInstaller.install(decor);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "setView hook", t);
                }
                return result;
            });
            Log.i(TAG, "hooked ViewRootImpl.setView");
        } catch (Throwable t) {
            Log.e(TAG, "hook setView failed", t);
        }

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        GlassInstaller.install(((Activity) self).getWindow().getDecorView());
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "resume hook", t);
                }
                return result;
            });
        } catch (Throwable t) {
            Log.e(TAG, "hook onResume failed", t);
        }

        try {
            Method appCreate = Instrumentation.class.getMethod(
                    "callApplicationOnCreate", Application.class);
            hook(appCreate).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof Application) {
                        ((Application) arg).registerActivityLifecycleCallbacks(
                                new Application.ActivityLifecycleCallbacks() {
                                    @Override public void onActivityCreated(Activity a, Bundle b) { }
                                    @Override public void onActivityStarted(Activity a) { }
                                    @Override public void onActivityResumed(Activity a) {
                                        GlassInstaller.install(a.getWindow().getDecorView());
                                    }
                                    @Override public void onActivityPaused(Activity a) { }
                                    @Override public void onActivityStopped(Activity a) { }
                                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                                    @Override public void onActivityDestroyed(Activity a) { }
                                });
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "app create hook", t);
                }
                return result;
            });
        } catch (Throwable t) {
            Log.e(TAG, "hook app create failed", t);
        }

        hookTextDraws();

        Log.i(TAG, "hooks installed");
    }

    private static Paint textPaintOf(View v) {
        for (Field f : paintFieldsOf(v.getClass())) {
            try {
                Object o = f.get(v);
                if (o instanceof android.text.TextPaint) return (Paint) o;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void hookTextDraws() {
        if (true) return;
        try {
            Method draw = android.text.Layout.class.getMethod(
                    "draw", android.graphics.Canvas.class);
            Method getPaint = android.text.Layout.class.getMethod("getPaint");
            hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!Boolean.TRUE.equals(sInBubbleDraw.get())) return chain.proceed();
                Object selfObj = chain.getThisObject();
                Paint paint = null;
                try {
                    Object p = getPaint.invoke(selfObj);
                    if (p instanceof Paint) paint = (Paint) p;
                } catch (Throwable ignored) {
                }
                if (paint == null) return chain.proceed();
                int old = paint.getColor();
                paint.setColor(0xFFFFFFFF);
                if (sPaintLog < 3) { sPaintLog++; Log.i(TAG, "layout draw whiten"); }
                try {
                    return chain.proceed();
                } finally {
                    paint.setColor(old);
                }
            });
            Log.i(TAG, "hooked Layout.draw");
        } catch (Throwable t) {
            Log.e(TAG, "hook Layout.draw failed", t);
        }

        try {
            Class<?> rc = Class.forName("android.graphics.RecordingCanvas");
            int n = 0;
            for (Method m : rc.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"drawText".equals(mn) && !"drawTextRun".equals(mn)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 4 || ps[ps.length - 1] != Paint.class) continue;
                try {
                    hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!Boolean.TRUE.equals(sInBubbleDraw.get())) return chain.proceed();
                        Object last = chain.getArg(chain.getArgs().size() - 1);
                        if (!(last instanceof Paint)) return chain.proceed();
                        Paint paint = (Paint) last;
                        int old = paint.getColor();
                        paint.setColor(0xFFFFFFFF);
                        if (sPaintLog < 3) { sPaintLog++; Log.i(TAG, "recording drawText whiten"); }
                        try {
                            return chain.proceed();
                        } finally {
                            paint.setColor(old);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "hooked RecordingCanvas drawText=" + n);
        } catch (Throwable t) {
            Log.e(TAG, "hook RecordingCanvas failed", t);
        }

        try {
            Method tvDraw = android.widget.TextView.class.getDeclaredMethod(
                    "onDraw", android.graphics.Canvas.class);
            hook(tvDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object selfObj = chain.getThisObject();
                if (!(selfObj instanceof View)) return chain.proceed();
                View bubble = bubbleAncestor((View) selfObj);
                if (bubble == null || !GlassInstaller.systemNight()
                        || !GlassInstaller.isSelfBubble(bubble)) {
                    return chain.proceed();
                }
                sInBubbleDraw.set(Boolean.TRUE);
                try {
                    return chain.proceed();
                } finally {
                    sInBubbleDraw.remove();
                }
            });
            Log.i(TAG, "hooked TextView.onDraw");
        } catch (Throwable t) {
            Log.e(TAG, "hook TextView.onDraw failed", t);
        }
    }

    private static View bubbleAncestor(View v) {
        android.view.ViewParent p = v.getParent();
        for (int i = 0; i < 8 && p != null; i++) {
            if (!(p instanceof View)) return null;
            View pv = (View) p;
            if (pv.getClass().getName().contains("Neat")) return pv;
            p = pv.getParent();
        }
        return null;
    }

    private void hookDrawTextsUnused() {
        int hooked = 0;
        for (Method m : android.graphics.Canvas.class.getDeclaredMethods()) {
            String mn = m.getName();
            if (!"drawText".equals(mn) && !"drawTextRun".equals(mn)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 4 || p[p.length - 1] != android.graphics.Paint.class) continue;
            try {
                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (!Boolean.TRUE.equals(sInBubbleDraw.get())) return chain.proceed();
                    if (sTextCalls < 3) { sTextCalls++; Log.i(TAG, "drawText flagged " + m.getName()); }
                    Object last = chain.getArg(chain.getArgs().size() - 1);
                    if (!(last instanceof Paint)) return chain.proceed();
                    Paint paint = (Paint) last;
                    int old = paint.getColor();
                    paint.setColor(0xFFFFFFFF);
                    try {
                        return chain.proceed();
                    } finally {
                        paint.setColor(old);
                    }
                });
                hooked++;
                if (sPaintLog < 1) {
                    sPaintLog++;
                    Log.i(TAG, "drawText hook " + m);
                }
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, "drawText hooks=" + hooked);
    }

    /**
     * Hooks onDraw once the host class is actually loaded (it is not at package
     * load time). While drawing an outgoing bubble in dark mode the text paints
     * are switched to white, then restored.
     */
    static void ensureTextHook(Class<?> bubbleClass) {

        if (sTextHooked || bubbleClass == null) {
            Log.i(TAG, "text hook skip hooked=" + sTextHooked + " cls=" + bubbleClass);
            return;
        }
        GlassModule self = sInstance;
        if (self == null) {
            Log.e(TAG, "text hook: module instance missing");
            return;
        }
        synchronized (GlassModule.class) {
            if (sTextHooked) return;
            Class<?> target = bubbleClass;
            Method onDraw = null;
            while (target != null && target != View.class && onDraw == null) {
                try {
                    onDraw = target.getDeclaredMethod("onDraw", android.graphics.Canvas.class);
                } catch (NoSuchMethodException e) {
                    target = target.getSuperclass();
                }
            }
            if (onDraw == null) {
                Log.e(TAG, "text hook: onDraw not found on " + bubbleClass.getName());
                return;
            }
            try {
                self.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object selfObj = chain.getThisObject();
                    if (!(selfObj instanceof View)) return chain.proceed();
                    View v = (View) selfObj;
                    if (!GlassInstaller.systemNight() || !GlassInstaller.isSelfBubble(v)) {
                        return chain.proceed();
                    }
                    int cur = readTextColor(v);
                    List<Field> fields = intFieldsOf(v.getClass());
                    List<Field> hit = new ArrayList<>();
                    List<Integer> oldVals = new ArrayList<>();
                    for (Field f : fields) {
                        try {
                            int val = f.getInt(v);
                            if (val != cur) continue;
                            hit.add(f);
                            oldVals.add(val);
                            f.setInt(v, 0xFFFFFFFF);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (sPaintLog < 3) {
                        sPaintLog++;
                        Log.i(TAG, "onDraw recolour cur=" + Integer.toHexString(cur)
                                + " fields=" + fields.size() + " hit=" + hit.size());
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        for (int i = 0; i < hit.size(); i++) {
                            try {
                                hit.get(i).setInt(v, oldVals.get(i));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                sTextHooked = true;
                Log.i(TAG, "hooked onDraw on " + target.getName());
            } catch (Throwable t) {
                Log.e(TAG, "hook onDraw failed", t);
            }
        }
    }

    private static int readTextColor(View v) {
        try {
            Method m = v.getClass().getMethod("getCurrentTextColor");
            Object r = m.invoke(v);
            return r instanceof Integer ? (Integer) r : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static synchronized List<Field> intFieldsOf(Class<?> cls) {
        List<Field> cached = sIntFields.get(cls);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {
                }
            }
        }
        sIntFields.put(cls, out);
        return out;
    }

    private static synchronized List<Field> paintFieldsOf(Class<?> cls) {
        List<Field> cached = sPaintFields.get(cls);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Paint.class.isAssignableFrom(f.getType())) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {
                }
            }
        }
        sPaintFields.put(cls, out);
        return out;
    }
}
