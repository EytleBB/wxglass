package dev.wxglass;

import android.view.View;
import android.view.ViewGroup;

/** Small helpers shared by the installer and the overlay. */
final class Backdrop {

    private Backdrop() {
    }

    static boolean isNight(View view) {
        int mode = view.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static View findView(View v, String needle, int depth) {
        if (depth > 40) return null;
        if (v.getClass().getName().contains(needle) && v.getWidth() > 0) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findView(g.getChildAt(i), needle, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
