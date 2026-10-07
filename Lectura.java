package com.gabriel.pantallanegra;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Arma el JSON con los datos que Android ya tiene calculados.
 * Todos los valores se recortan al rango que acepta el sitio: un valor fuera
 * de rango haría rechazar el envío completo con 400.
 */
final class Lectura {

    private static float margenCache = Float.NaN;
    private static long margenCacheMs;

    private Lectura() {}

    /** motivo == null para la lectura local del puerto 8765. */
    static JSONObject leer(Context c, String equipo, String motivo) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("equipo", equipo);
        j.put("modelo", corta(Build.MODEL, 40));
        j.put("ts", System.currentTimeMillis() / 1000L);

        // Batería: broadcast "sticky", no registra nada.
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = 0, enchufe = 0, decimasC = 0, mv = 0;
        if (b != null) {
            int nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (nivel >= 0 && escala > 0) pct = Math.round(nivel * 100f / escala);
            enchufe = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            decimasC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        }
        if (mv > 0 && mv < 100) mv *= 1000; // algunos equipos informan voltios

        j.put("bateria_pct", rango(pct, 0, 100));
        j.put("cargando", enchufe != 0);
        j.put("fuente_carga", fuente(enchufe));
        j.put("bateria_temp_c", rango(decimasC, -200, 800) / 10.0);
        j.put("bateria_mv", rango(mv, 2000, 5500));

        // Estado y margen térmico.
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        int estado = 0;
        if (Build.VERSION.SDK_INT >= 29 && pm != null) estado = pm.getCurrentThermalStatus();
        j.put("estado_termico", rango(estado, 0, 6));
        j.put("margen_termico", margen(pm));

        // Wi-Fi: intensidad y velocidad de enlace (no requiere ubicación).
        int dbm = -127, mbps = 0;
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            @SuppressWarnings("deprecation")
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                dbm = wi.getRssi();
                mbps = wi.getLinkSpeed();
            }
        }
        j.put("wifi_dbm", rango(dbm, -127, 0));
        j.put("wifi_mbps", rango(mbps, 0, 10000));

        j.put("pantalla_negra", Overlay.visible());
        j.put("version_app", corta(version(c), 10));
        if (motivo != null) j.put("motivo", motivo);
        return j;
    }

    /**
     * Pronóstico térmico a 10 s. Android devuelve NaN si se consulta más de una vez
     * por segundo (puede pasar si coinciden el envío y la lectura local), así que se
     * reutiliza el último valor válido por 10 s.
     */
    private static synchronized Object margen(PowerManager pm) {
        if (Build.VERSION.SDK_INT < 30 || pm == null) return JSONObject.NULL;
        float h = pm.getThermalHeadroom(10);
        long ahora = SystemClock.elapsedRealtime();
        if (!Float.isNaN(h) && h >= 0f) {
            margenCache = h;
            margenCacheMs = ahora;
        } else if (!Float.isNaN(margenCache) && ahora - margenCacheMs < 10_000L) {
            h = margenCache;
        } else {
            return JSONObject.NULL;
        }
        return Math.round(Math.min(h, 2f) * 100f) / 100.0;
    }

    private static String fuente(int enchufe) {
        switch (enchufe) {
            case 0: return "ninguna";
            case BatteryManager.BATTERY_PLUGGED_USB: return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "inalambrica";
            default: return "ac"; // AC y base de carga
        }
    }

    static String version(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private static int rango(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String corta(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
