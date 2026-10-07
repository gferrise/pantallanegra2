package com.gabriel.pantallanegra;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/** Configuración del monitor (equipo, URL, token) y log interno de la app. */
final class Config {

    static final String URL_POR_DEFECTO =
            "https://www.cosasdeljardin.com/academia/wp-content/plugins/academia-equipos-monitor/monitor.php";

    /** Se usa como nombre de archivo en el sitio: solo letras, números, _ y -. */
    private static final Pattern EQUIPO = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final int MAX_LINEAS_LOG = 40;

    private Config() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("monitor", Context.MODE_PRIVATE);
    }

    static String equipo(Context c) { return p(c).getString("equipo", ""); }
    static String url(Context c)    { return p(c).getString("url", URL_POR_DEFECTO); }
    static String token(Context c)  { return p(c).getString("token", ""); }

    static void guardar(Context c, String equipo, String url, String token) {
        p(c).edit()
                .putString("equipo", equipo)
                .putString("url", url)
                .putString("token", token)
                .apply();
    }

    static boolean equipoValido(String s) {
        return s != null && EQUIPO.matcher(s).matches();
    }

    static boolean urlValida(String s) {
        if (s == null || !s.startsWith("https://")) return false;
        Uri u = Uri.parse(s);
        return u.getHost() != null && !u.getHost().isEmpty();
    }

    /** null si la configuración está completa; si no, qué falta. */
    static String problema(Context c) {
        if (!equipoValido(equipo(c))) return "Nombre de equipo inválido";
        if (!urlValida(url(c))) return "URL inválida (tiene que empezar con https://)";
        if (token(c).isEmpty()) return "Falta el token";
        return null;
    }

    // --- Último resultado (lo muestra la pantalla de configuración) ---

    static void ultimo(Context c, String texto) {
        p(c).edit().putString("ultimo", texto).apply();
    }

    static String ultimo(Context c) {
        return p(c).getString("ultimo", "Sin envíos todavía");
    }

    // --- Log interno: últimas 40 líneas, la más nueva arriba ---

    static synchronized void log(Context c, String linea) {
        Log.i("PantallaNegra", linea);
        String hora = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date());
        String[] previas = p(c).getString("log", "").split("\n");
        StringBuilder sb = new StringBuilder(hora).append("  ").append(linea);
        int n = 1;
        for (String l : previas) {
            if (l.isEmpty()) continue;
            if (n++ >= MAX_LINEAS_LOG) break;
            sb.append('\n').append(l);
        }
        p(c).edit().putString("log", sb.toString()).apply();
    }

    static String leerLog(Context c) { return p(c).getString("log", ""); }

    static void borrarLog(Context c) { p(c).edit().remove("log").apply(); }
}
