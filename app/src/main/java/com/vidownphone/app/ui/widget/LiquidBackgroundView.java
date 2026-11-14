package com.vidownphone.app.ui.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * iOS / Apple Music style Ambient Liquid Mesh and Flowing Water Current background view.
 * Renders ultra-smooth, low-CPU organic radial fluid orbs and undulating liquid wave ribbons.
 *
 * Shaders and colors are precomputed (unit-radius radial gradients are scaled/translated via the
 * canvas, wave gradients are rebuilt only on size change) so onDraw does not allocate.
 * The animation stops while the view is not visible or detached.
 */
public class LiquidBackgroundView extends View {

    private static final int BASE_DARK = Color.parseColor("#07090E");
    private static final int BASE_LIGHT = Color.parseColor("#EEF2F8");

    // [mode][orb] where mode 0 = light, 1 = dark
    private static final int[][] ORB_CENTER = {
            {0x18BA2252, 0x1E3B82F6, 0x18BA2252, 0x2206B6D4},
            {0x66BA2252, 0x551E3A8A, 0x5EBA2252, 0x400F4C5C}
    };
    private static final int[][] ORB_MID = {
            {0x08BA2252, 0x0A3B82F6, 0x08BA2252, 0x0806B6D4},
            {0x2668102A, 0x22172554, 0x22450A1A, 0x18082026}
    };

    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath1 = new Path();
    private final Path wavePath2 = new Path();

    private final Shader[][] orbShaders = new Shader[2][4];
    private final Shader[] waveShaders1 = new Shader[2];
    private final Shader[] waveShaders2 = new Shader[2];
    private int shadersW = -1;
    private int shadersH = -1;

    private long startTimeMs;
    private boolean isPaused = false;
    private boolean isEnabledByPref = true;
    private boolean visibleAggregated = true;

    public LiquidBackgroundView(Context context) {
        super(context);
        init();
    }

    public LiquidBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LiquidBackgroundView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        startTimeMs = SystemClock.uptimeMillis();
        wavePaint.setStyle(Paint.Style.FILL);
        for (int mode = 0; mode < 2; mode++) {
            for (int i = 0; i < 4; i++) {
                orbShaders[mode][i] = new RadialGradient(
                        0f, 0f, 1f,
                        new int[]{ORB_CENTER[mode][i], ORB_MID[mode][i], 0x00000000},
                        new float[]{0f, 0.58f, 1f},
                        Shader.TileMode.CLAMP
                );
            }
        }
    }

    public void setPaused(boolean paused) {
        if (this.isPaused != paused) {
            this.isPaused = paused;
            if (!paused && isEnabledByPref && visibleAggregated) {
                postInvalidateOnAnimation();
            }
        }
    }

    public void setLiquidEnabled(boolean enabled) {
        this.isEnabledByPref = enabled;
        invalidate();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        boolean wasVisible = visibleAggregated;
        visibleAggregated = isVisible;
        if (isVisible && !wasVisible && !isPaused && isEnabledByPref) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        visibleAggregated = false;
        super.onDetachedFromWindow();
    }

    private boolean isNightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildWaveShaders(w, h);
    }

    private void buildWaveShaders(int w, int h) {
        if (w <= 0 || h <= 0) return;
        shadersW = w;
        shadersH = h;
        float yBase1 = h * 0.42f;
        float amp1 = h * 0.07f;
        float yBase2 = h * 0.68f;
        float amp2 = h * 0.06f;
        waveShaders1[0] = new LinearGradient(0, yBase1 - amp1, 0, h * 0.85f, 0x12475569, 0x00000000, Shader.TileMode.CLAMP);
        waveShaders1[1] = new LinearGradient(0, yBase1 - amp1, 0, h * 0.85f, 0x26B91C1C, 0x00000000, Shader.TileMode.CLAMP);
        waveShaders2[0] = new LinearGradient(0, yBase2 - amp2, 0, h, 0x143B82F6, 0x00000000, Shader.TileMode.CLAMP);
        waveShaders2[1] = new LinearGradient(0, yBase2 - amp2, 0, h, 0x241E3A8A, 0x00000000, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (w != shadersW || h != shadersH) {
            buildWaveShaders(w, h);
        }

        boolean dark = isNightMode();
        int mode = dark ? 1 : 0;

        // 1. Deep Base Canvas
        canvas.drawColor(dark ? BASE_DARK : BASE_LIGHT);

        if (!isEnabledByPref) {
            return;
        }

        float t = (SystemClock.uptimeMillis() - startTimeMs) / 1000f;
        float minDim = Math.min(w, h);

        // 2. Four Slow-Drifting Liquid Plasma Orbs (Lissajous Fluid Trajectories)
        float x1 = w * (0.30f + 0.28f * (float) Math.sin(t * 0.42f));
        float y1 = h * (0.24f + 0.18f * (float) Math.cos(t * 0.31f));
        float r1 = minDim * (0.78f + 0.12f * (float) Math.sin(t * 0.55f));
        drawFluidOrb(canvas, x1, y1, r1, orbShaders[mode][0]);

        float x2 = w * (0.75f + 0.22f * (float) Math.cos(t * 0.36f + 1.2f));
        float y2 = h * (0.52f + 0.24f * (float) Math.sin(t * 0.29f + 0.8f));
        float r2 = minDim * (0.82f + 0.10f * (float) Math.cos(t * 0.48f));
        drawFluidOrb(canvas, x2, y2, r2, orbShaders[mode][1]);

        float x3 = w * (0.45f + 0.32f * (float) Math.sin(t * 0.33f + 2.5f));
        float y3 = h * (0.80f + 0.14f * (float) Math.cos(t * 0.40f + 1.9f));
        float r3 = minDim * (0.75f + 0.14f * (float) Math.sin(t * 0.44f + 1.0f));
        drawFluidOrb(canvas, x3, y3, r3, orbShaders[mode][2]);

        float x4 = w * (0.22f + 0.25f * (float) Math.cos(t * 0.27f + 3.1f));
        float y4 = h * (0.58f + 0.20f * (float) Math.sin(t * 0.38f + 2.2f));
        float r4 = minDim * (0.68f + 0.10f * (float) Math.cos(t * 0.52f));
        drawFluidOrb(canvas, x4, y4, r4, orbShaders[mode][3]);

        // 3. Undulating Water / Silk Wave Ribbons
        drawLiquidWaveRibbon(canvas, w, h, t, mode);

        // 4. Schedule next frame (~30fps cap) only while visible and attached
        if (!isPaused && visibleAggregated && isAttachedToWindow()) {
            postInvalidateDelayed(32L);
        }
    }

    private void drawFluidOrb(Canvas canvas, float cx, float cy, float radius, Shader unitShader) {
        float r = Math.max(1f, radius);
        orbPaint.setShader(unitShader);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        canvas.drawCircle(0f, 0f, 1f, orbPaint);
        canvas.restore();
    }

    private void drawLiquidWaveRibbon(Canvas canvas, int w, int h, float t, int mode) {
        // Wave 1: Flowing mid-screen liquid current
        float yBase1 = h * 0.42f;
        float amp1 = h * 0.07f;
        wavePath1.reset();
        wavePath1.moveTo(0, yBase1 + amp1 * (float) Math.sin(t * 0.7f));
        wavePath1.cubicTo(
                w * 0.33f, yBase1 - amp1 * 1.4f * (float) Math.cos(t * 0.55f),
                w * 0.66f, yBase1 + amp1 * 1.4f * (float) Math.sin(t * 0.65f + 1.1f),
                w, yBase1 - amp1 * (float) Math.cos(t * 0.6f)
        );
        wavePath1.lineTo(w, h);
        wavePath1.lineTo(0, h);
        wavePath1.close();
        wavePaint.setShader(waveShaders1[mode]);
        canvas.drawPath(wavePath1, wavePaint);

        // Wave 2: Counter-flowing lower liquid current
        float yBase2 = h * 0.68f;
        float amp2 = h * 0.06f;
        wavePath2.reset();
        wavePath2.moveTo(0, yBase2 - amp2 * (float) Math.cos(t * 0.6f));
        wavePath2.cubicTo(
                w * 0.38f, yBase2 + amp2 * 1.5f * (float) Math.sin(t * 0.5f + 0.7f),
                w * 0.72f, yBase2 - amp2 * 1.3f * (float) Math.cos(t * 0.62f),
                w, yBase2 + amp2 * (float) Math.sin(t * 0.54f)
        );
        wavePath2.lineTo(w, h);
        wavePath2.lineTo(0, h);
        wavePath2.close();
        wavePaint.setShader(waveShaders2[mode]);
        canvas.drawPath(wavePath2, wavePaint);
    }
}
