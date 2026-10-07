package com.gabriel.pantallanegra;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Pantalla mínima: equipo, URL, token, iniciar/detener y log. Sin layouts XML. */
public class ConfigActivity extends Activity {

    private EditText equipo, url, token;
    private TextView estado, log;
    private Button botonMonitor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Atajos.publicar(this);

        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        col.addView(titulo("Monitor · Pantalla negra " + Lectura.version(this)));

        int textoPlano = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        equipo = campo(col, "Nombre del equipo (letras, números, _ y -; máx. 20)",
                Config.equipo(this), textoPlano);
        url = campo(col, "URL del endpoint", Config.url(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        token = campo(col, "Token", Config.token(this), textoPlano);

        boton(col, "Guardar", v -> {
            if (guardar()) Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show();
        });
        botonMonitor = boton(col, "", v -> alternarMonitor());

        estado = new TextView(this);
        estado.setPadding(0, dp(12), 0, dp(12));
        col.addView(estado);

        col.addView(titulo("Log"));
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        log.setTextIsSelectable(true);
        col.addView(log);
        boton(col, "Borrar log", v -> {
            Config.borrarLog(this);
            refrescar();
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refrescar();
    }

    private boolean guardar() {
        String e = equipo.getText().toString().trim();
        String u = url.getText().toString().trim();
        String t = token.getText().toString().trim();

        if (!Config.equipoValido(e)) {
            equipo.setError("1 a 20 caracteres: A-Z, a-z, 0-9, _ o -. Sin espacios.");
            equipo.requestFocus();
            return false;
        }
        if (!Config.urlValida(u)) {
            url.setError("Tiene que empezar con https://");
            url.requestFocus();
            return false;
        }
        if (t.isEmpty()) {
            token.setError("Pegá el token que genera el plugin");
            token.requestFocus();
            return false;
        }
        String host = Uri.parse(u).getHost();
        if (host != null && host.equals("cosasdeljardin.com")) {
            Toast.makeText(this, "Ojo: sin www el sitio redirige y el envío se pierde",
                    Toast.LENGTH_LONG).show();
        }
        Config.guardar(this, e, u, t);
        return true;
    }

    private void alternarMonitor() {
        if (MonitorService.activo()) {
            MonitorService.detener(this);
        } else {
            if (!guardar()) return;
            MonitorService.iniciar(this);
        }
        botonMonitor.postDelayed(this::refrescar, 400);
    }

    private void refrescar() {
        boolean activo = MonitorService.activo();
        botonMonitor.setText(activo ? "Detener monitor" : "Iniciar monitor");
        estado.setText("Monitor " + (activo ? "activo" : "detenido") + "\n" + Config.ultimo(this));
        String l = Config.leerLog(this);
        log.setText(l.isEmpty() ? "(vacío)" : l);
    }

    // ---------- Helpers de UI ----------

    private TextView titulo(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private EditText campo(LinearLayout col, String etiqueta, String valor, int tipo) {
        TextView l = new TextView(this);
        l.setText(etiqueta);
        l.setPadding(0, dp(8), 0, 0);
        col.addView(l);
        EditText e = new EditText(this);
        e.setInputType(tipo);
        e.setSingleLine(true);
        e.setText(valor);
        col.addView(e);
        return e;
    }

    private Button boton(LinearLayout col, String texto, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        col.addView(b);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
