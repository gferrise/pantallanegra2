package com.gabriel.pantallanegra;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Recuadro flotante del modo "pantalla negra". Existe mientras el monitor está activo.
 *  - Arrastrar: lo mueve por la pantalla (la posición se recuerda).
 *  - Doble toque: activa la capa negra. El recuadro se quita mientras la capa está
 *    visible y vuelve cuando se la saca (doble toque sobre lo negro).
 *  - Mantener 5 s: apaga el monitor y el recuadro (fin del modo). Mientras se mantiene,
 *    el recuadro se achica como indicador; soltar antes de los 5 s cancela.
 * Todo corre en el hilo principal.
 */
final class Burbuja {

    private static final long MANTENER_MS = 5_000L;
    private static final long DOBLE_TOQUE_MS = 400L;
    private static final int LADO_DP = 48;

    private static View view;
    private static WindowManager.LayoutParams lp;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable finPendiente;

    private Burbuja() {}

    /** Muestra el recuadro (idempotente). No hace nada si la capa negra está visible. */
    static void mostrar(Context c) {
        final Context ctx = c.getApplicationContext();
        if (view != null || Overlay.visible() || !Settings.canDrawOverlays(ctx)) return;

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        int lado = dp(ctx, LADO_DP);

        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0xB0202020);
        fondo.setCornerRadius(dp(ctx, 12));
        fondo.setStroke(dp(ctx, 2), 0x80FFFFFF);

        View v = new View(ctx);
        v.setBackground(fondo);
        v.setOnTouchListener(new Toque(ctx, wm));

        SharedPreferences p = prefs(ctx);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(
                lado, lado,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = limitar(p.getInt("x", dm.widthPixels - lado - dp(ctx, 8)), dm.widthPixels - lado);
        lp.y = limitar(p.getInt("y", dm.heightPixels / 3), dm.heightPixels - lado);

        wm.addView(v, lp);
        view = v;
    }

    /** Quita el recuadro (idempotente). */
    static void quitar(Context c) {
        cancelarFin();
        if (view == null) return;
        WindowManager wm = (WindowManager) c.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.removeViewImmediate(view);
        } catch (IllegalArgumentException ignored) {
            // ya no estaba adjunta
        }
        view = null;
    }

    private static void cancelarFin() {
        if (finPendiente != null) {
            H.removeCallbacks(finPendiente);
            finPendiente = null;
        }
    }

    /** Arrastre, doble toque y "mantener 5 s", sin GestureDetector para no mezclarlos. */
    private static final class Toque implements View.OnTouchListener {

        private final Context ctx;
        private final WindowManager wm;
        private final int slop;

        private float x0, y0;
        private int lpX0, lpY0;
        private boolean arrastrando;
        private long ultimoToque;

        Toque(Context ctx, WindowManager wm) {
            this.ctx = ctx;
            this.wm = wm;
            this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (v != view) return true; // ya se quitó
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    lpX0 = lp.x;
                    lpY0 = lp.y;
                    arrastrando = false;
                    empezarMantener(v);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!arrastrando && Math.hypot(dx, dy) > slop) {
                        arrastrando = true;
                        cortarMantener(v);
                        ultimoToque = 0;
                    }
                    if (arrastrando) {
                        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                        lp.x = limitar(lpX0 + Math.round(dx), dm.widthPixels - lp.width);
                        lp.y = limitar(lpY0 + Math.round(dy), dm.heightPixels - lp.height);
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (IllegalArgumentException ignored) { }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    cortarMantener(v);
                    if (arrastrando) {
                        prefs(ctx).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    } else {
                        long ahora = SystemClock.uptimeMillis();
                        if (ahora - ultimoToque <= DOBLE_TOQUE_MS) {
                            ultimoToque = 0;
                            Overlay.show(ctx); // quita el recuadro
                        } else {
                            ultimoToque = ahora;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    cortarMantener(v);
                    return true;
            }
            return true;
        }

        private void empezarMantener(View v) {
            cancelarFin();
            v.animate().scaleX(0.5f).scaleY(0.5f).setDuration(MANTENER_MS).start();
            finPendiente = () -> {
                finPendiente = null;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                quitar(ctx);
                MonitorService.detener(ctx);
                Toast.makeText(ctx, "Pantalla negra y monitor desactivados",
                        Toast.LENGTH_SHORT).show();
            };
            H.postDelayed(finPendiente, MANTENER_MS);
        }

        private void cortarMantener(View v) {
            cancelarFin();
            v.animate().cancel();
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
    }

    // ---------- Helpers ----------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("burbuja", Context.MODE_PRIVATE);
    }

    private static int limitar(int v, int max) {
        return Math.max(0, Math.min(Math.max(max, 0), v));
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
