package dev.wxglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.RuntimeShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.Log;
import android.graphics.BitmapShader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.Build;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Draws only the bubble outline: the background is fully transparent, the
 * rounded border is all that is left.
 */
final class GlassOverlay extends View {

    private static final String TAG = GlassModule.TAG;

    private final ArrayList<Entry> mBubbles = new ArrayList<>();

    private static final class Entry {
        final WeakReference<View> view;
        final float shift;

        Entry(View v, float shift) {
            this.view = new WeakReference<>(v);
            this.shift = shift;
        }
    }
    /** Set false to go back to the border-only look. */
    private static final boolean ENABLE_FILL = true;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RuntimeShader mLens;
    private Bitmap mWallpaperBmp;
    private boolean mWallpaperHasDetail;
    private long mLastCapture;
    private float mWpScale = 0.5f;
    private int mWpX;
    private int mWpY;

    private static final String LENS_SRC = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float strength;\n"
            + "uniform float dispersion;\n"
            + "float sdRound(float2 p, float2 hs, float r) {\n"
            + "    float2 q = abs(p) - (hs - float2(r));\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "float2 gradRound(float2 p, float2 hs, float r) {\n"
            + "    float2 q = abs(p) - (hs - float2(r));\n"
            + "    float2 m = max(q, 0.0);\n"
            + "    float l = length(m);\n"
            + "    if (l > 1e-4) return sign(p) * (m / l);\n"
            + "    float gx = step(q.y, q.x);\n"
            + "    return sign(p) * float2(gx, 1.0 - gx);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = size * 0.5;\n"
            + "    float2 p = coord - hs;\n"
            + "    float sd = sdRound(p, hs, radius);\n"
            + "    float d = clamp(-sd / max(band, 1.0), 0.0, 1.0);\n"
            + "    float amp = 1.0 - d;\n"
            + "    amp = amp * amp;\n"
            + "    float2 g = gradRound(p, hs, radius);\n"
            + "    float2 off = -g * amp * strength;\n"
            + "    half4 c;\n"
            + "    c.r = content.eval(coord + off * (1.0 + dispersion)).r;\n"
            + "    c.g = content.eval(coord + off).g;\n"
            + "    c.b = content.eval(coord + off * (1.0 - dispersion)).b;\n"
            + "    c.a = 1.0;\n"
            + "    return c;\n"
            + "}\n";
    private final Paint mGrain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Bitmap sGrain;
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final int[] mSelf = new int[2];
    private final int[] mBubble = new int[2];
    private final float mDensity;
    private final View mWallpaper;
    private final ViewGroup mContainer;
    private int mSignature;

    GlassOverlay(Context context, ViewGroup container, View wallpaper) {
        super(context);
        mWallpaper = wallpaper;
        mContainer = container;
        mDensity = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(1.1f * mDensity);
        mStroke.setShader(whiteStroke());
        mLens = Build.VERSION.SDK_INT >= 33 ? new RuntimeShader(LENS_SRC) : null;

        mHairline.setStyle(Paint.Style.STROKE);
        mHairline.setStrokeWidth(1f);
        mHairline.setColor(0x22000000);
    }

    void register(View bubble, int core, float shift) {
        for (Entry e : mBubbles) {
            View v = e.view.get();
            if (v == bubble) return;
        }
        mBubbles.add(new Entry(bubble, shift));
        // A new chat brings new bubble views; drop the cached wallpaper so the
        // lens cannot keep drawing the previous conversation's background.
        long now = System.nanoTime();
        if (now - mLastCapture > 400_000_000L) mWallpaperBmp = null;
        invalidate();
    }

    /** Called once per frame; redraws only when a bubble moved. */
    void onFrame() {
        int sig = 0;
        for (Entry e : mBubbles) {
            View v = e.view.get();
            if (v == null) continue;
            v.getLocationOnScreen(mBubble);
            sig = sig * 31 + mBubble[0] * 7 + mBubble[1] * 13 + v.getWidth() * 3 + v.getHeight();
        }
        if (sig != mSignature) {
            mSignature = sig;
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    private static Shader whiteStroke() {
        return new LinearGradient(0, 0, 0, 1,
                new int[]{0x99FFFFFF, 0x55FFFFFF, 0x33FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    private static Shader darkStroke() {
        return new LinearGradient(0, 0, 0, 1,
                new int[]{0x55141414, 0x33141414, 0x22141414},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A flat colour (the default background) has nothing to refract. */
    private static boolean hasDetail(Bitmap b) {
        int stepX = Math.max(1, b.getWidth() / 48);
        int stepY = Math.max(1, b.getHeight() / 64);
        double sum = 0, sum2 = 0;
        int n = 0;
        for (int y = 0; y < b.getHeight(); y += stepY) {
            for (int x = 0; x < b.getWidth(); x += stepX) {
                int c = b.getPixel(x, y);
                double l = 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF)
                        + 0.114 * (c & 0xFF);
                sum += l;
                sum2 += l * l;
                n++;
            }
        }
        if (n < 8) return false;
        double mean = sum / n;
        double var = sum2 / n - mean * mean;
        return Math.sqrt(Math.max(0, var)) > 8.0;
    }

    private void drawRefraction(Canvas canvas, View bubble,
                                float x, float y, float w, float h, float radius) {
        Bitmap bmp = wallpaperBitmap();
        if (bmp == null || !mWallpaperHasDetail) return;
        int[] wp = new int[2];
        mWallpaper.getLocationOnScreen(wp);
        bubble.getLocationOnScreen(mBubble);

        Matrix m = new Matrix();
        m.setScale(1f / mWpScale, 1f / mWpScale);
        m.postTranslate(wp[0] - mBubble[0], wp[1] - mBubble[1]);
        BitmapShader content = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        content.setLocalMatrix(m);

        mLens.setInputShader("content", content);
        mLens.setFloatUniform("size", w, h);
        mLens.setFloatUniform("radius", radius);
        mLens.setFloatUniform("band", 14f * mDensity);
        mLens.setFloatUniform("strength", 10f * mDensity);
        mLens.setFloatUniform("dispersion", 0.035f);
        mLensPaint.setShader(mLens);

        canvas.save();
        canvas.translate(x, y);
        canvas.clipPath(roundPath(w, h, radius));
        canvas.drawRect(0, 0, w, h, mLensPaint);
        canvas.restore();
    }

    private final Path mLensPath = new Path();

    private Path roundPath(float w, float h, float radius) {
        mLensPath.reset();
        mLensPath.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        return mLensPath;
    }

    private Bitmap wallpaperBitmap() {
        if (mWallpaperBmp != null && !mWallpaperBmp.isRecycled()) return mWallpaperBmp;
        View wv = mWallpaper;
        if (wv == null || wv.getWidth() <= 0) return null;
        try {
            int tw = Math.max(1, Math.round(wv.getWidth() * mWpScale));
            int th = Math.max(1, Math.round(wv.getHeight() * mWpScale));
            Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(mWpScale, mWpScale);
            wv.draw(c);
            mWallpaperBmp = b;
            mLastCapture = System.nanoTime();
            mWallpaperHasDetail = hasDetail(b);
            Log.i(TAG, "wallpaper captured detail=" + mWallpaperHasDetail);
            return b;
        } catch (Throwable t) {
            Log.e(TAG, "wallpaper bitmap failed", t);
            return null;
        }
    }

    private static Bitmap grain() {
        if (sGrain != null) return sGrain;
        int n = 96;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        int[] px = new int[n * n];
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < px.length; i++) {
            int v = 100 + r.nextInt(155);
            int a = 18 + r.nextInt(34);
            px[i] = (a << 24) | (v << 16) | (v << 8) | v;
        }
        b.setPixels(px, 0, n, 0, 0, n, n);
        sGrain = b;
        return b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        getLocationOnScreen(mSelf);
        for (Entry e : mBubbles) {
            View bubble = e.view.get();
            if (bubble == null || !bubble.isAttachedToWindow()
                    || bubble.getVisibility() != VISIBLE || bubble.getWidth() <= 0) {
                continue;
            }
            bubble.getLocationOnScreen(mBubble);
            float x = mBubble[0] - mSelf[0] + e.shift;
            float y = mBubble[1] - mSelf[1];
            float w = bubble.getWidth();
            float h = bubble.getHeight();
            float radius = Math.min(h * 0.46f, 26f * mDensity);

            mPath.reset();
            mPath.addRoundRect(x, y, x + w, y + h, radius, radius, Path.Direction.CW);

            if (mLens != null) {
                drawRefraction(canvas, bubble, x, y, w, h, radius);
            }

            if (ENABLE_FILL) {
                boolean night = Backdrop.isNight(bubble);
                mFill.setShader(null);
                mFill.setColor(night ? 0x5E1A1A1C : 0x5EFFFFFF);
                mStroke.setShader(night ? whiteStroke() : darkStroke());
                canvas.drawPath(mPath, mFill);

                // frosted grain, tiled so it never depends on what is behind
                mGrain.setShader(new BitmapShader(grain(),
                        Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
                mGrain.setAlpha(night ? 0x0C : 0x0A);
                canvas.drawPath(mPath, mGrain);
            }

            canvas.drawPath(mPath, mStroke);

            mHairline.setColor(mStroke.getShader() != null ? 0x22000000 : 0x22FFFFFF);
            canvas.drawPath(mPath, mHairline);
        }
    }
}
