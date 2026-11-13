package dev.wxglass;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One-shot view-tree dump used to locate the bubble views on a real device. */
final class TreeDump {

    private static final String TAG = GlassModule.TAG;
    private static int sRuns;
    private static final int MAX_LINES = 700;

    private TreeDump() {
    }

    static void schedule(View root) {
        // Three passes: the first frame after resume often still has the list
        // empty; the later ones also cover "open chat from a notification".
        for (int delay : new int[]{600, 1800}) {
            root.postDelayed(() -> {
                try {
                    dump(root);
                } catch (Throwable t) {
                    Log.e(TAG, "dump failed", t);
                }
            }, delay);
        }
    }

    private static void dump(View decor) {
        if (sRuns >= 14) return;
        sRuns++;
        Lines out = new Lines(MAX_LINES);
        out.add("==== window " + decor.getClass().getName() + " run=" + sRuns
                + " bg=" + bg(decor) + " size=" + decor.getWidth() + "x" + decor.getHeight());
        walk(decor, 0, out, 26);

        // Second pass over the lists: their items are what we actually need.
        List<View> lists = new ArrayList<>();
        collectLists(decor, lists);
        out.add("==== lists=" + lists.size());
        for (int i = 0; i < lists.size() && i < 4; i++) {
            View list = lists.get(i);
            dumpList(list, out);
        }
        out.flush();
    }

    private static void collectLists(View v, List<View> out) {
        String cls = v.getClass().getName();
        if (cls.contains("RecyclerView") || cls.contains("ListView")
                || cls.contains("CustomViewPager")) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectLists(g.getChildAt(i), out);
        }
    }

    private static void dumpList(View list, Lines out) {
        out.add("LIST " + list.getClass().getName() + " " + box(list)
                + " bg=" + bg(list) + " parent=" + (list.getParent() == null
                ? "null" : list.getParent().getClass().getName()));

        // Siblings drawn before the list are the wallpaper candidates.
        ViewParent p = list.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) p;
            int idx = parent.indexOfChild(list);
            out.add("  parent " + parent.getClass().getName() + " " + box(parent)
                    + " bg=" + bg(parent) + " children=" + parent.getChildCount()
                    + " listIndex=" + idx);
            for (int i = 0; i < parent.getChildCount() && i < 8; i++) {
                View sib = parent.getChildAt(i);
                out.add("   sib[" + i + "] " + sib.getClass().getName() + " " + box(sib)
                        + " vis=" + sib.getVisibility() + " bg=" + bg(sib));
            }
        }

        if (list instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) list;
            out.add("  adapter=" + adapterName(list) + " children=" + g.getChildCount());
            for (int i = 0; i < g.getChildCount() && i < 3; i++) {
                View item = g.getChildAt(i);
                out.add("  ITEM[" + i + "] " + item.getClass().getName() + " " + box(item)
                        + " bg=" + bg(item));
                walk(item, 2, out, 5);
            }
        }
    }

    private static String adapterName(View list) {
        try {
            Object adapter = list.getClass().getMethod("getAdapter").invoke(list);
            return adapter == null ? "null" : adapter.getClass().getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void walk(View v, int depth, Lines out, int maxDepth) {
        if (!out.hasRoom()) return;
        out.add(pad(depth) + describe(v));
        if (depth >= maxDepth || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, out, maxDepth);
    }

    private static String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getName());
        sb.append("{").append(box(v)).append(" vis=").append(v.getVisibility());
        if (v.getAlpha() < 1f) sb.append(" alpha=").append(v.getAlpha());
        if (v.getId() != View.NO_ID) sb.append(" #").append(idName(v));
        Drawable bg = v.getBackground();
        if (bg != null) sb.append(" bg=").append(bg(bg));
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            String txt = cs == null ? "" : cs.toString();
            if (txt.length() > 0) {
                if (txt.length() > 24) txt = txt.substring(0, 24) + "…";
                sb.append(" text=\"").append(txt.replace('\n', ' ')).append("\"");
            }
        } else if (v instanceof ImageView) {
            sb.append(" src=").append(((ImageView) v).getDrawable() == null
                    ? "null" : ((ImageView) v).getDrawable().getClass().getSimpleName());
        }
        if (v instanceof ViewGroup) sb.append(" n=").append(((ViewGroup) v).getChildCount());
        return sb.toString();
    }

    private static String bg(View v) {
        return bg(v.getBackground());
    }

    private static String bg(Drawable d) {
        if (d == null) return "null";
        String s = d.getClass().getSimpleName();
        if (d instanceof NinePatchDrawable) {
            s += "(" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")";
        } else if (d.getIntrinsicWidth() > 0) {
            s += "(" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")";
        }
        Drawable.ConstantState cs = d.getConstantState();
        if (cs != null) s += "@" + Integer.toHexString(System.identityHashCode(cs));
        return s;
    }

    private static String idName(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(v.getId());
        }
    }

    private static String box(View v) {
        return String.format(Locale.US, "(%d,%d)-(%d,%d) %dx%d",
                v.getLeft(), v.getTop(), v.getRight(), v.getBottom(), v.getWidth(), v.getHeight());
    }

    private static String pad(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    /** Collects lines and emits them one log entry each, so nothing is cut off. */
    private static final class Lines {
        private final int mMax;
        private final ArrayDeque<String> mPending = new ArrayDeque<>();

        Lines(int max) {
            mMax = max;
        }

        boolean hasRoom() {
            return mPending.size() < mMax;
        }

        void add(String line) {
            if (mPending.size() < mMax) mPending.add(line);
        }

        void flush() {
            String line;
            while ((line = mPending.poll()) != null) Log.i(TAG, line);
        }
    }
}
