package dev.wxglass;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Fallback discovery + liveness proof. Hooks into framework lifecycle methods
 * turned out to be unreliable in this host, so a small daemon thread looks at
 * the process' own window list instead.
 */
final class Watchdog {

    private static volatile boolean sStarted;

    private Watchdog() {
    }

    static void start() {
        if (sStarted) return;
        sStarted = true;
        Thread thread = new Thread(() -> {
            int beat = 0;
            while (true) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    return;
                }
                beat++;
                ArrayList<View> views = roots();
                Log.i(GlassModule.TAG, "alive beat=" + beat + " roots=" + views.size());
                for (int i = 0; i < views.size(); i++) {
                    try {
                        View v = views.get(i);
                        GlassInstaller.scan(v);
                        GlassInstaller.install(v);
                    } catch (Throwable t) {
                        Log.e(GlassModule.TAG, "watchdog install", t);
                    }
                }
            }
        }, "WxGlassWatchdog");
        thread.setDaemon(true);
        thread.start();
        Log.i(GlassModule.TAG, "watchdog started");
    }

    private static ArrayList<View> roots() {
        ArrayList<View> out = new ArrayList<>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmg.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            Field views = wmg.getDeclaredField("mViews");
            views.setAccessible(true);
            Object value = views.get(instance);
            if (value instanceof Iterable) {
                for (Object o : (Iterable<?>) value) {
                    if (o instanceof View) out.add((View) o);
                }
            }
        } catch (Throwable t) {
            Log.e(GlassModule.TAG, "roots failed: " + t);
        }
        return out;
    }
}
