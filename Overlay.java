package com.gabriel.pantallanegra;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Capa negra a pantalla completa por encima de cualquier app.
 * - Negro puro: en AMOLED esos píxeles quedan apagados.
 * - Brillo forzado al mínimo mientras está visible.
 * - Mantiene la pantalla encendida para que NDI no se pause por bloqueo.
 * - Absorbe los toques (evita tocar NDI sin querer).
 * - Doble toque sobre la capa = quitarla (alternativa al botón lateral).
 * Sin servicio ni procesos en segundo plano: cero consumo cuando está apagada.
 */
final class Overlay {

    private static View view;

    private Overlay() {}

    static void toggle(Context ctx) {
        if (view != null) hide(ctx); else show(ctx);
    }

    static void show(final Context ctx) {
        if (view != null) return;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        View v = new View(ctx);
        v.setBackgroundColor(Color.BLACK);

        final GestureDetector gd = new GestureDetector(ctx,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        hide(ctx);
                        return true;
                    }
                });
        v.setOnTouchListener((view1, event) -> gd.onTouchEvent(event));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.screenBrightness = 0f; // brillo mínimo mientras la capa está visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        wm.addView(v, lp);
        view = v;
    }

    static void hide(Context ctx) {
        if (view == null) return;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.removeViewImmediate(view);
        } catch (IllegalArgumentException ignored) {
            // ya no estaba adjunta
        }
        view = null;
    }
}
