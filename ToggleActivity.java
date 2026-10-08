package com.gabriel.pantallanegra;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Se abre tocando el ícono. No dibuja nada: activa el modo pantalla negra
 * (monitor + recuadro flotante) y se cierra.
 *  - Doble toque en el recuadro: capa negra.
 *  - Mantener el recuadro 5 s: apaga monitor y recuadro.
 */
public class ToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Atajos.publicar(this);

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Activá \"Mostrar sobre otras apps\" para Pantalla negra",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else if (MonitorService.activo()) {
            Burbuja.mostrar(this); // por si no estaba visible
            Toast.makeText(this, "Ya está activo. Doble toque en el recuadro = pantalla negra",
                    Toast.LENGTH_SHORT).show();
        } else {
            MonitorService.iniciar(this);
            String problema = Config.problema(this);
            Toast.makeText(this, problema == null
                            ? "Monitor activo. Doble toque en el recuadro = pantalla negra"
                            : "Recuadro activo, pero el monitor no envía: " + problema,
                    Toast.LENGTH_LONG).show();
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
