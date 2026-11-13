package dev.wxglass;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;

/** Logs every sizable view that carries a drawable background, with its parents. */
final class Probe {

    private static int sRuns;

    private Probe() {
    }

    static void run(View decor) {
        if (sRuns >= 6) return;
        sRuns++;
        StringBuilder header = new StringBuilder("PROBE run=").append(sRuns)
                .append(" decor=").append(decor.getWidth()).append("x").append(decor.getHeight());
        Log.i(GlassModule.TAG, header.toString());
        int[] budget = {6000};
        walk(decor, budget, 0);
        View chat = find(decor, "ChattingUILayout", 0);
        if (chat != null) {
            Log.i(GlassModule.TAG, "CHAT SUBTREE " + chat.getClass().getName()
                    + " " + chat.getWidth() + "x" + chat.getHeight());
            subtree(chat, 0, 8);
        }
    }

    private static void walk(View v, int[] budget, int depth) {
        if (budget[0]-- <= 0) return;
        Drawable bg = v.getBackground();
        if (bg != null && v.getWidth() > 0) {
            int iw = bg.getIntrinsicWidth();
            int ih = bg.getIntrinsicHeight();
            String cls = v.getClass().getName();
            boolean interesting = iw >= 24 || cls.contains("Bubble") || cls.contains("Chat");
            if (interesting) {
                Log.i(GlassModule.TAG, String.format(Locale.US,
                        "  BG %s %dx%d bg=%s(%dx%d) parent=%s",
                        cls, v.getWidth(), v.getHeight(),
                        bg.getClass().getSimpleName(), iw, ih, chain(v)));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), budget, depth + 1);
        }
    }

    private static View find(View v, String needle, int depth) {
        if (depth > 40) return null;
        if (v.getClass().getName().contains(needle)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = find(g.getChildAt(i), needle, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void subtree(View v, int depth, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility());
        Drawable bg = v.getBackground();
        if (bg != null) {
            sb.append(" bg=").append(bg.getClass().getSimpleName())
                    .append('(').append(bg.getIntrinsicWidth()).append('x')
                    .append(bg.getIntrinsicHeight()).append(')');
        }
        Log.i(GlassModule.TAG, "  T " + sb);
        if (depth < maxDepth && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) subtree(g.getChildAt(i), depth + 1, maxDepth);
        }
    }

    private static String chain(View v) {
        StringBuilder sb = new StringBuilder();
        android.view.ViewParent p = v.getParent();
        int hops = 0;
        while (p instanceof View && hops < 4) {
            View pv = (View) p;
            sb.append(pv.getClass().getSimpleName()).append('<');
            p = pv.getParent();
            hops++;
        }
        return sb.toString();
    }
}
