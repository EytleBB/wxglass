package dev.wxglass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.NinePatchDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.Locale;
import java.util.WeakHashMap;

/**
 * Watches the host window and dresses the message list when a chat page shows
 * up. Anchors come from the 8.0.79 tree:
 *
 * <pre>
 * ChattingUILayout
 *   ChattingContent > FrameLayout > ChattingImageBGView   (the wallpaper)
 *   ... > MMChattingListView > ScrollControlRecyclerView  (the messages)
 *         kh { ... LinearLayout > MMNeat7extView }        (a bubble)
 * </pre>
 */
final class GlassInstaller {

    private static final String TAG = GlassModule.TAG;
    private static final WeakHashMap<View, Boolean> sWatched = new WeakHashMap<>();
    private static int sStale;
    private int mLastTop = Integer.MIN_VALUE;

    private View mDecor;
    private View mList;
    private View mWallpaper;
    private ViewGroup mContainer;
    private GlassOverlay mOverlay;
    private View mOverlayWallpaper;
    private int mWrapped;
    private int mFrames;

    static void install(View decor) {
        if (decor == null) return;
        synchronized (sWatched) {
            if (sWatched.containsKey(decor)) return;
        }
        try {
            GlassInstaller installer = new GlassInstaller(decor);
            decor.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            try {
                                installer.tick();
                            } catch (Throwable t) {
                                Log.e(TAG, "tick", t);
                            }
                            return true;
                        }
                    });
            synchronized (sWatched) {
                sWatched.put(decor, Boolean.TRUE);
            }
            Log.i(TAG, "watcher attached " + decor.getClass().getSimpleName()
                    + " size=" + decor.getWidth() + "x" + decor.getHeight());
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private GlassInstaller(View decor) {
        mDecor = decor;
    }

    /** Entry point used by the watchdog thread: hop to the UI thread and scan. */
    static void scan(final View root) {
        if (root == null) return;
        root.post(() -> {
            try {
                GlassInstaller installer = sByDecor.get(root);
                if (installer == null) {
                    installer = new GlassInstaller(root);
                    sByDecor.put(root, installer);
                }
                installer.tick();
            } catch (Throwable t) {
                Log.e(TAG, "scan", t);
            }
        });
    }

    private static final WeakHashMap<View, GlassInstaller> sByDecor = new WeakHashMap<>();

    private void tick() {
        mFrames++;
        // Cheap check first: is a chat page on screen at all?
        View chat = Backdrop.findView(mDecor, "ChattingUILayout", 0);
        if (mFrames % 15 == 0) {
            Log.i(TAG, "scan frame=" + mFrames + " chat=" + (chat != null)
                    + " list=" + (mList != null) + " wrapped=" + mWrapped);
        }
        if (chat == null) {
            if (mList != null) {
                mList = null;
                Log.i(TAG, "left chat page");
            }
            return;
        }
        if (mWallpaper == null || !mWallpaper.isAttachedToWindow()) {
            mWallpaper = Backdrop.findView(chat, "ChattingImageBGView", 0);
            mContainer = mWallpaper != null && mWallpaper.getParent() instanceof ViewGroup
                    ? (ViewGroup) mWallpaper.getParent() : null;
        }
        if (mList == null || mList.getParent() == null) {
            mList = findList(chat);
            Log.i(TAG, "chat list = " + (mList == null ? "null" : mList.getClass().getName()));
        }
        if (!(mList instanceof ViewGroup)) return;
        ensureOverlay();

        ViewGroup list = (ViewGroup) mList;
        if (!mItemsDumped) {
            mItemsDumped = true;
            dumpStructure(list, 0, 6);
        }
        // While the list scrolls every capture is a frame behind; force a
        // re-record so the glass shows the pixels that just moved under it.
        int top = list.getChildCount() > 0 && list.getChildAt(0) != null
                ? list.getChildAt(0).getTop() : 0;
        boolean scrolling = mLastTop != Integer.MIN_VALUE && top != mLastTop;
        mLastTop = top;
        if (mOverlay != null) mOverlay.onFrame();
        for (int i = 0; i < list.getChildCount(); i++) {
            wrapItem(list.getChildAt(i), scrolling);
        }
        if (mFrames % 300 == 0) {
            Log.i(TAG, String.format(Locale.US, "frame=%d wrapped=%d items=%d",
                    mFrames, mWrapped, list.getChildCount()));
        }
    }

    private int mDiag;
    private boolean mItemsDumped;

    private void dumpStructure(View v, int depth, int maxDepth) {
        StringBuilder sb = new StringBuilder("  S ");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append(' ')
                .append(v.getWidth()).append('x').append(v.getHeight());
        Drawable bg = v.getBackground();
        if (bg != null) sb.append(" bg=").append(bg.getClass().getSimpleName())
                .append('(').append(bg.getIntrinsicWidth()).append('x')
                .append(bg.getIntrinsicHeight()).append(')');
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(" text=1");
        }
        Log.i(TAG, sb.toString());
        if (depth < maxDepth && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpStructure(g.getChildAt(i), depth + 1, maxDepth);
        }
    }

    private static int sampleColor(Drawable source, int fallback) {
        if (source == null) return fallback;
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            source.setBounds(0, 0, 1, 1);
            source.draw(c);
            int color = b.getPixel(0, 0);
            b.recycle();
            return (color >>> 24) > 40 ? color : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Outgoing messages sit on the right half of the screen. */
    static boolean isSelfBubble(View bubble) {
        int[] loc = new int[2];
        bubble.getLocationOnScreen(loc);
        int screen = bubble.getResources().getDisplayMetrics().widthPixels;
        return loc[0] + bubble.getWidth() / 2 > screen / 2;
    }

    static boolean systemNight() {
        int mode = android.content.res.Resources.getSystem().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static float mShiftOf(View view) {
        Object tag = view.getTag(0x77770003);
        return tag instanceof Float ? (Float) tag : 0f;
    }

    private static int mCoreOf(View view) {
        Object tag = view.getTag(0x77770001);
        return tag instanceof Integer ? (Integer) tag : 0xFF95EC69;
    }


    private void ensureOverlay() {
        if (mOverlay != null && mOverlayWallpaper != mWallpaper) {
            // Another conversation page: the old overlay belongs to a stale view.
            try {
                if (mOverlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
                }
            } catch (Throwable ignored) {
            }
            mOverlay = null;
        }
        if (mOverlay != null) return;
        if (mContainer == null || mWallpaper == null) {
            Log.i(TAG, "overlay pending container=" + mContainer + " wallpaper=" + mWallpaper);
            return;
        }
        try {
            GlassOverlay overlay = new GlassOverlay(mContainer.getContext(), mContainer, mWallpaper);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            mContainer.addView(overlay, lp);
            mOverlay = overlay;
            mOverlayWallpaper = mWallpaper;
            Log.i(TAG, "glass overlay added to " + mContainer.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "overlay add failed", t);
        }
    }

    private void wrapItem(View item, boolean scrolling) {
        View bubble = findBubble(item, null);
        if (mDiag < 8) {
            mDiag++;
            Log.i(TAG, "item " + item.getClass().getSimpleName() + " "
                    + item.getWidth() + "x" + item.getHeight() + " -> "
                    + (bubble == null ? "null" : bubble.getClass().getSimpleName()
                    + " " + bubble.getWidth() + "x" + bubble.getHeight()
                    + " bg=" + bubble.getBackground()));
        }
        if (bubble == null) return;
        GlassModule.ensureTextHook(bubble.getClass());
        Drawable bg = bubble.getBackground();
        if (bg instanceof ClearDrawable) {
            if (mOverlay != null) mOverlay.register(bubble, mCoreOf(bubble), mShiftOf(bubble));
            return;
        }
        if (!isBubbleBackground(bg)) return;
        if (mOverlay == null) return;   // without the overlay, leave the host alone
        int core = sampleColor(bg, 0xFF95EC69);
        bubble.setTag(0x77770001, core);
        android.graphics.Rect pad = new android.graphics.Rect();
        bg.getPadding(pad);
        boolean self = isSelfBubble(bubble);
        // The border must sit on the text box centre, nothing else: any extra
        // nudge in either direction is exactly what made it look off-centre.
        float shift = (pad.left - pad.right) * 0.5f;
        bubble.setBackground(new ClearDrawable());
        mOverlay.register(bubble, core, shift);
        mWrapped++;
        Log.i(TAG, "wrapped " + bubble.getClass().getName() + " "
                + bubble.getWidth() + "x" + bubble.getHeight()
                + " bg=" + bg.getClass().getSimpleName());
    }

    /** The bubble shape in 8.0.79 is a StateListDrawable wrapping 9-patches. */
    private static boolean isBubbleBackground(Drawable d) {
        if (d instanceof NinePatchDrawable) return true;
        if (d instanceof StateListDrawable) return d.getIntrinsicWidth() >= 24;
        if (d instanceof LayerDrawable) return d.getIntrinsicWidth() >= 24;
        return false;
    }

    private static View findBubble(View view, View best) {
        if (view.getVisibility() != View.VISIBLE) return best;
        if (view.getWidth() > 0 && view.getHeight() > 0
                && isBubbleBackground(view.getBackground())
                && view.getWidth() > dp(view, 16) && view.getHeight() > dp(view, 16)
                && isBubbleView(view)) {
            if (best == null || area(view) > area(best)) best = view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                best = findBubble(g.getChildAt(i), best);
            }
        }
        return best;
    }

    /**
     * The host draws text bubbles with MMNeat7extView, which does not extend
     * TextView, so the class name is the reliable signal.
     */
    private static boolean isBubbleView(View view) {
        String name = view.getClass().getName();
        if (name.contains("Neat")) return true;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text != null && text.length() > 0;
        }
        return false;
    }

    private static View findList(View chat) {
        View v = Backdrop.findView(chat, "ScrollControlRecyclerView", 0);
        if (v != null) return v;
        v = Backdrop.findView(chat, "RecyclerView", 0);
        if (v != null) return v;
        return Backdrop.findView(chat, "ListView", 0);
    }

    private static int area(View v) {
        return v.getWidth() * v.getHeight();
    }

    private static int dp(View v, int value) {
        return Math.round(value * v.getResources().getDisplayMetrics().density);
    }
}
