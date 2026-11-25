package com.example.racingcar;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class GameView extends View {

    private static final int LANES = 4;
    private static final float BASE_SPEED = 400f;    // px/s baslangic hizi
    private static final float MAX_SPEED  = 1250f;   // px/s maksimum hiz
    private static final float SPEED_GAIN = 22f;     // her saniye hiz artisi
    private static final float DASH_PERIOD = 140f;
    private static final float GRASS_PERIOD = 90f;

    private static final int[] RIVAL_COLORS = {
            0xFFE53935, 0xFF1E88E5, 0xFFFDD835,
            0xFF8E24AA, 0xFF00ACC1, 0xFFFB8C00
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();

    private float viewW, viewH;
    private float roadLeft, roadRight, roadWidth, laneWidth;
    private float carW, carH;

    private float playerX, playerY, targetX;
    private float speed = BASE_SPEED;

    private float dashOffset, grassOffset;

    private final List<Rival> rivals = new ArrayList<>();
    private float spawnTimer;

    private float meters;
    private int best;
    private boolean gameOver;
    private boolean running;
    private long lastNanos;

    private static class Rival {
        float x, top, v;
        int color;
    }

    public GameView(Context ctx) {
        super(ctx);
        paint.setStyle(Paint.Style.FILL);
        setBackgroundColor(0xFF2E7D32);
        setKeepScreenOn(true);
        setFocusable(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);

        SharedPreferences sp = ctx.getSharedPreferences("racer", Context.MODE_PRIVATE);
        best = sp.getInt("best", 0);

        resetGame();
    }

    private void resetGame() {
        rivals.clear();
        speed = BASE_SPEED;
        dashOffset = 0f;
        grassOffset = 0f;
        spawnTimer = 0.8f;
        meters = 0f;
        gameOver = false;
        lastNanos = 0L;
        if (laneWidth > 0f) {
            playerX = laneCenter(1);
            targetX = playerX;
        }
    }

    private float laneCenter(int lane) {
        return roadLeft + laneWidth * (lane + 0.5f);
    }

    private float clampToRoad(float x) {
        float min = roadLeft + carW / 2f;
        float max = roadRight - carW / 2f;
        if (x < min) return min;
        if (x > max) return max;
        return x;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;

        roadWidth = w * 0.86f;
        roadLeft  = (w - roadWidth) / 2f;
        roadRight = roadLeft + roadWidth;
        laneWidth = roadWidth / LANES;

        carW = laneWidth * 0.62f;
        carH = carW * 1.85f;
        playerY = h * 0.68f;

        if (playerX == 0f) {
            playerX = laneCenter(1);
        }
        playerX = clampToRoad(playerX);
        targetX = playerX;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastNanos = 0L;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        lastNanos = 0L;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = (lastNanos == 0L) ? 0f : (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.05f) dt = 0.05f;

        if (!gameOver) {
            update(dt);
        }

        drawRoad(c);
        for (Rival r : rivals) {
            drawCar(c, r.x, r.top, r.color);
        }
        drawCar(c, playerX, playerY, 0xFF29B6F6);
        drawHud(c);
        if (gameOver) {
            drawGameOver(c);
        }

        if (running && !gameOver) {
            postInvalidateOnAnimation();
        }
    }

    private void update(float dt) {
        speed = Math.min(MAX_SPEED, speed + SPEED_GAIN * dt);
        float scroll = speed * dt;

        dashOffset  = (dashOffset + scroll) % DASH_PERIOD;
        grassOffset = (grassOffset + scroll * 0.6f) % GRASS_PERIOD;
        meters += scroll * 0.02f;

        // yumusak serit gecisi
        float k = 1f - (float) Math.exp(-14f * dt);
        playerX += (targetX - playerX) * k;

        spawnTimer -= dt;
        if (spawnTimer <= 0f) {
            spawnRival();
            spawnTimer = Math.max(0.42f, 1.10f - speed / 2600f);
        }

        Iterator<Rival> it = rivals.iterator();
        while (it.hasNext()) {
            Rival r = it.next();
            r.top += scroll * r.v;
            if (r.top > viewH + carH) {
                it.remove();
                continue;
            }
            if (hitPlayer(r)) {
                endGame();
                return;
            }
        }
    }

    private void spawnRival() {
        int lane = rnd.nextInt(LANES);
        for (int attempt = 0; attempt < 8; attempt++) {
            float tryX = laneCenter(lane);
            boolean free = true;
            for (Rival r : rivals) {
                if (Math.abs(r.x - tryX) < carW * 0.8f && r.top < carH * 1.6f) {
                    free = false;
                    break;
                }
            }
            if (free) break;
            lane = rnd.nextInt(LANES);
        }

        Rival r = new Rival();
        r.x = laneCenter(lane);
        r.top = -carH - rnd.nextFloat() * carH;
        r.v = 0.55f + rnd.nextFloat() * 0.5f;
        r.color = RIVAL_COLORS[rnd.nextInt(RIVAL_COLORS.length)];
        rivals.add(r);
    }

    private boolean hitPlayer(Rival r) {
        float padX = carW * 0.14f;
        float padY = carH * 0.10f;

        RectF a = new RectF(r.x - carW / 2f + padX, r.top + padY,
                            r.x + carW / 2f - padX, r.top + carH - padY);
        RectF b = new RectF(playerX - carW / 2f + padX, playerY + padY,
                            playerX + carW / 2f - padX, playerY + carH - padY);
        return RectF.intersects(a, b);
    }

    private void endGame() {
        gameOver = true;
        int score = (int) meters;
        if (score > best) {
            best = score;
            getContext().getSharedPreferences("racer", Context.MODE_PRIVATE)
                    .edit().putInt("best", best).apply();
        }
        invalidate();
    }

    // ---------------- cizim ----------------

    private void drawRoad(Canvas c) {
        paint.setColor(0xFF2E7D32);
        c.drawRect(0, 0, viewW, viewH, paint);

        paint.setColor(0xFF388E3C);
        for (float y = -grassOffset; y < viewH; y += GRASS_PERIOD) {
            c.drawRect(0, y, roadLeft, y + GRASS_PERIOD * 0.5f, paint);
            c.drawRect(roadRight, y, viewW, y + GRASS_PERIOD * 0.5f, paint);
        }

        paint.setColor(0xFF37474F);
        c.drawRect(roadLeft, 0, roadRight, viewH, paint);

        paint.setColor(0xFFFFFFFF);
        c.drawRect(roadLeft, 0, roadLeft + 6f, viewH, paint);
        c.drawRect(roadRight - 6f, 0, roadRight, viewH, paint);

        paint.setColor(0xCCFFFFFF);
        for (int i = 1; i < LANES; i++) {
            float x = roadLeft + laneWidth * i - 3f;
            for (float y = -DASH_PERIOD + dashOffset; y < viewH; y += DASH_PERIOD) {
                c.drawRect(x, y, x + 6f, y + DASH_PERIOD * 0.5f, paint);
            }
        }
    }

    private void drawCar(Canvas c, float cx, float top, int color) {
        float w = carW, h = carH;
        float left = cx - w / 2f;
        float right = cx + w / 2f;
        float radius = w * 0.26f;

        // golge
        paint.setColor(0x40000000);
        c.drawRoundRect(new RectF(left + 3f, top + 8f, right + 3f, top + h + 8f),
                radius, radius, paint);

        // tekerlekler
        paint.setColor(0xFF151515);
        float ww = w * 0.20f, wh = h * 0.16f;
        float[] wy = {top + h * 0.10f, top + h * 0.70f};
        for (float y : wy) {
            c.drawRoundRect(new RectF(left - ww * 0.35f, y, left + ww * 0.65f, y + wh), 4f, 4f, paint);
            c.drawRoundRect(new RectF(right - ww * 0.65f, y, right + ww * 0.35f, y + wh), 4f, 4f, paint);
        }

        // kasa
        paint.setColor(color);
        c.drawRoundRect(new RectF(left, top, right, top + h), radius, radius, paint);

        // on cam / arka cam
        paint.setColor(0xCC12222E);
        c.drawRoundRect(new RectF(cx - w * 0.33f, top + h * 0.17f, cx + w * 0.33f, top + h * 0.36f),
                w * 0.10f, w * 0.10f, paint);
        c.drawRoundRect(new RectF(cx - w * 0.30f, top + h * 0.60f, cx + w * 0.30f, top + h * 0.76f),
                w * 0.10f, w * 0.10f, paint);

        // serit
        paint.setColor(0x66FFFFFF);
        c.drawRect(cx - w * 0.05f, top + h * 0.38f, cx + w * 0.05f, top + h * 0.58f, paint);

        // farlar
        paint.setColor(0xFFFFF176);
        c.drawRect(left + w * 0.12f, top + h * 0.02f, left + w * 0.30f, top + h * 0.07f, paint);
        c.drawRect(right - w * 0.30f, top + h * 0.02f, right - w * 0.12f, top + h * 0.07f, paint);

        // stop lambalari
        paint.setColor(0xFFFF5252);
        c.drawRect(left + w * 0.12f, top + h * 0.93f, left + w * 0.30f, top + h * 0.98f, paint);
        c.drawRect(right - w * 0.30f, top + h * 0.93f, right - w * 0.12f, top + h * 0.98f, paint);
    }

    private void drawHud(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(viewW * 0.055f);
        c.drawText("Mesafe: " + (int) meters + " m", viewW * 0.04f, viewH * 0.06f, textPaint);

        textPaint.setTextSize(viewW * 0.040f);
        c.drawText("Rekor: " + best + " m", viewW * 0.04f, viewH * 0.105f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText((int) (speed / 8f) + " km/h", viewW * 0.96f, viewH * 0.06f, textPaint);
    }

    private void drawGameOver(Canvas c) {
        paint.setColor(0xB3000000);
        c.drawRect(0, 0, viewW, viewH, paint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFFFF5252);
        textPaint.setTextSize(viewW * 0.11f);
        c.drawText("ÇARPTIN!", viewW / 2f, viewH * 0.38f, textPaint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(viewW * 0.075f);
        c.drawText((int) meters + " metre", viewW / 2f, viewH * 0.48f, textPaint);

        textPaint.setTextSize(viewW * 0.050f);
        c.drawText("Rekor: " + best + " m", viewW / 2f, viewH * 0.55f, textPaint);

        textPaint.setColor(0xFFFFF176);
        c.drawText("Yeniden oynamak için dokun", viewW / 2f, viewH * 0.66f, textPaint);
    }

    // ---------------- dokunma ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (gameOver) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                resetGame();
                if (laneWidth > 0f) {
                    playerX = laneCenter(1);
                    targetX = playerX;
                }
                lastNanos = 0L;
                invalidate();
            }
            return true;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (laneWidth <= 0f) return true;
                int lane = (int) ((e.getX() - roadLeft) / laneWidth);
                if (lane < 0) lane = 0;
                if (lane >= LANES) lane = LANES - 1;
                targetX = laneCenter(lane);
                break;
            }
            default:
                break;
        }
        return true;
    }
}
