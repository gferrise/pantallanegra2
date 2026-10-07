package com.gabriel.pantallanegra;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Se abre con el doble toque del botón lateral (o tocando el ícono).
 * No dibuja nada: alterna la capa negra y se cierra.
 */
public class ToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Activá \"Mostrar sobre otras apps\" para Pantalla negra",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            Overlay.toggle(getApplicationContext());
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
