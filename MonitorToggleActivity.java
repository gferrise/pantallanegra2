package com.gabriel.pantallanegra;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Atajo "Monitor on/off": prende o apaga el monitor y se cierra. */
public class MonitorToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean faltaPermiso = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;

        if (MonitorService.activo()) {
            MonitorService.detener(this);
            Toast.makeText(this, "Monitor detenido", Toast.LENGTH_SHORT).show();
        } else if (Config.problema(this) != null || faltaPermiso) {
            Toast.makeText(this, "Completá la configuración del monitor", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ConfigActivity.class));
        } else {
            MonitorService.iniciar(this);
            Toast.makeText(this, "Monitor activo", Toast.LENGTH_SHORT).show();
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
