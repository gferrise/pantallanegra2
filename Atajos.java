package com.gabriel.pantallanegra;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import java.util.Arrays;

/**
 * Atajos al mantener presionado el ícono: "Monitor on/off" y "Configurar monitor".
 * Se publican desde el código (sin carpeta res); quedan disponibles después de
 * abrir la app una vez.
 */
final class Atajos {

    private Atajos() {}

    static void publicar(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || sm.getDynamicShortcuts().size() >= 2) return;

        ShortcutInfo monitor = new ShortcutInfo.Builder(c, "monitor")
                .setShortLabel("Monitor on/off")
                .setLongLabel("Monitor on/off")
                .setIntent(new Intent(Intent.ACTION_VIEW, null, c, MonitorToggleActivity.class))
                .setRank(0)
                .build();
        ShortcutInfo config = new ShortcutInfo.Builder(c, "config")
                .setShortLabel("Configurar monitor")
                .setLongLabel("Configurar monitor")
                .setIntent(new Intent(Intent.ACTION_VIEW, null, c, ConfigActivity.class))
                .setRank(1)
                .build();
        try {
            sm.setDynamicShortcuts(Arrays.asList(monitor, config));
        } catch (IllegalStateException ignored) {
            // límite de frecuencia del sistema: se reintenta en la próxima apertura
        }
    }
}
