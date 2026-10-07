package com.gabriel.pantallanegra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Servicio en primer plano del monitor.
 *
 * Cuándo envía:
 *  - cada 60 s ("intervalo");
 *  - inmediato al arrancar ("inicio"), al cambiar el estado térmico ("termico"),
 *    al conectar/desconectar el cargador ("carga") y con batería baja ("bateria_baja").
 * Entre dos envíos hay como mínimo 6 s (el sitio exige 5; el segundo extra cubre la
 * variación de la red). Los eventos que caen dentro de esa ventana se agrupan en un
 * solo envío, con el motivo de mayor prioridad. Después de cada envío el ciclo de 60 s
 * se reinicia. Un envío que falla se descarta: nunca se reintenta.
 *
 * Todo corre en un único hilo propio; el hilo principal queda libre para la capa negra.
 */
public class MonitorService extends Service {

    static final String ACCION_DETENER = "com.gabriel.pantallanegra.DETENER_MONITOR";

    private static final String CANAL = "monitor";
    private static final int NOTIF_ID = 1;
    private static final long INTERVALO_MS = 60_000L;
    private static final long ESPACIO_MIN_MS = 6_000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static volatile boolean activo;

    private HandlerThread hilo;
    private Handler h;
    private PowerManager pm;
    private Object listenerTermico; // PowerManager.OnThermalStatusChangedListener (API 29+)
    private ServidorLocal servidor;

    private boolean huboEnvio;
    private long ultimoEnvio;       // elapsedRealtime del último envío
    private String pendiente;       // motivo del próximo envío
    private int ultimoTermico = -1;

    private final Runnable tick = () -> pedir("intervalo");
    private final Runnable diferido = this::enviarPendiente;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pedir(Intent.ACTION_BATTERY_LOW.equals(i.getAction()) ? "bateria_baja" : "carga");
        }
    };

    // ---------- Arranque y parada desde la app ----------

    static boolean activo() { return activo; }

    static void iniciar(Context c) {
        c.startForegroundService(new Intent(c, MonitorService.class));
    }

    static void detener(Context c) {
        c.stopService(new Intent(c, MonitorService.class));
    }

    // ---------- Ciclo de vida ----------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        crearCanal();
        Notification n = notificacion("Iniciando…");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException e) {
            // Android no permite pasar a primer plano (p. ej. reinicio en segundo plano).
            Config.log(this, "No se pudo iniciar el monitor: " + e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (h == null) arrancar();
        return START_STICKY;
    }

    private void arrancar() {
        activo = true;
        hilo = new HandlerThread("monitor");
        hilo.start();
        h = new Handler(hilo.getLooper());

        // Cargador y batería baja: broadcasts protegidos del sistema.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receptor, f, null, h, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receptor, f, null, h);
        }

        // Estado térmico. El listener avisa el valor actual al registrarse: se ignora.
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 29 && pm != null) {
            ultimoTermico = pm.getCurrentThermalStatus();
            PowerManager.OnThermalStatusChangedListener l = estado -> {
                if (estado != ultimoTermico) {
                    ultimoTermico = estado;
                    pedir("termico");
                }
            };
            pm.addThermalStatusListener(h::post, l);
            listenerTermico = l;
        }

        servidor = new ServidorLocal(getApplicationContext());
        servidor.iniciar();

        Config.log(this, "Monitor iniciado");
        h.post(() -> pedir("inicio"));
    }

    @Override
    public void onDestroy() {
        activo = false;
        if (h != null) h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receptor);
        } catch (IllegalArgumentException ignored) {
            // no estaba registrado
        }
        if (Build.VERSION.SDK_INT >= 29 && pm != null && listenerTermico != null) {
            pm.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) listenerTermico);
        }
        if (servidor != null) servidor.detener();
        if (hilo != null) hilo.quitSafely();
        h = null;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        Config.log(this, "Monitor detenido");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- Programación de envíos (siempre en el hilo "monitor") ----------

    private void pedir(String motivo) {
        if (!activo || h == null) return;
        if (prioridad(motivo) > prioridad(pendiente)) pendiente = motivo;
        h.removeCallbacks(diferido);
        long espera = huboEnvio
                ? ultimoEnvio + ESPACIO_MIN_MS - SystemClock.elapsedRealtime()
                : 0;
        if (espera > 0) h.postDelayed(diferido, espera);
        else enviarPendiente();
    }

    private void enviarPendiente() {
        if (!activo || pendiente == null) return;
        String motivo = pendiente;
        pendiente = null;
        h.removeCallbacks(tick);
        ultimoEnvio = SystemClock.elapsedRealtime();
        huboEnvio = true;

        enviar(motivo);

        // El ciclo de 60 s cuenta desde este envío.
        if (activo && h != null) {
            long resto = INTERVALO_MS - (SystemClock.elapsedRealtime() - ultimoEnvio);
            h.postDelayed(tick, Math.max(resto, ESPACIO_MIN_MS));
        }
    }

    private static int prioridad(String m) {
        if (m == null) return 0;
        switch (m) {
            case "termico": return 5;
            case "bateria_baja": return 4;
            case "carga": return 3;
            case "inicio": return 2;
            case "intervalo": return 1;
            default: return 0;
        }
    }

    // ---------- Envío ----------

    private void enviar(String motivo) {
        String problema = Config.problema(this);
        if (problema != null) {
            resultado(problema, problema, motivo);
            return;
        }

        String json;
        try {
            json = Lectura.leer(this, Config.equipo(this), motivo).toString();
        } catch (Exception e) {
            resultado("Error al leer datos", "Error al armar el JSON: " + e, motivo);
            return;
        }
        byte[] cuerpo = json.getBytes(UTF8);

        int codigo = -1;
        String detalle = "";
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(Config.url(this)).openConnection();
            con.setInstanceFollowRedirects(false); // una redirección convertiría el POST en GET
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setUseCaches(false);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(cuerpo.length);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("X-Monitor-Token", Config.token(this));
            con.setRequestProperty("User-Agent", "PantallaNegra-Monitor/" + Lectura.version(this));
            try (OutputStream os = con.getOutputStream()) {
                os.write(cuerpo);
            }
            codigo = con.getResponseCode();
            detalle = leerCorto(codigo >= 400 ? con.getErrorStream() : con.getInputStream());
            if (codigo >= 300 && codigo < 400) {
                String destino = con.getHeaderField("Location");
                if (destino != null) detalle = "→ " + destino;
            }
        } catch (HttpRetryException e) {
            // Android puede devolver así un 401 cuando el cuerpo ya se envió en streaming.
            codigo = e.responseCode();
            detalle = "";
            con.disconnect();
        } catch (IOException e) {
            detalle = e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
            if (con != null) con.disconnect();
        }

        String hora = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        if (codigo >= 200 && codigo < 300) {
            resultado("Último envío: OK " + hora + " (" + motivo + ")", null, motivo);
        } else if (codigo == 401) {
            resultado("Token inválido", "401 Token inválido " + detalle, motivo);
        } else if (codigo == 400) {
            resultado("Último envío: 400 (dato inválido)",
                    "400 Dato inválido " + detalle + " | enviado: " + json, motivo);
        } else if (codigo >= 300 && codigo < 400) {
            resultado("Último envío: " + codigo + " (redirige: revisar URL)",
                    codigo + " Redirección, envío perdido " + detalle, motivo);
        } else if (codigo == -1) {
            resultado("Último envío: sin respuesta", "Sin respuesta: " + detalle, motivo);
        } else {
            resultado("Último envío: " + codigo, codigo + " " + detalle, motivo);
        }
    }

    /** Muestra el resultado en la notificación; si hubo error lo registra en el log. */
    private void resultado(String textoNotif, String lineaLog, String motivo) {
        if (lineaLog != null) Config.log(this, "[" + motivo + "] " + lineaLog);
        Config.ultimo(this, textoNotif);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && activo) nm.notify(NOTIF_ID, notificacion(textoNotif));
    }

    private static String leerCorto(InputStream in) {
        if (in == null) return "";
        try (InputStream s = in) {
            byte[] buf = new byte[300];
            int n = 0, r;
            while (n < buf.length && (r = s.read(buf, n, buf.length - n)) > 0) n += r;
            while (s.read() != -1) { /* vaciar para reusar la conexión */ }
            return new String(buf, 0, n, UTF8).replace('\n', ' ').trim();
        } catch (IOException e) {
            return "";
        }
    }

    // ---------- Notificación fija ----------

    private void crearCanal() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Monitor", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notificacion(String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 0,
                new Intent(this, ConfigActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent parar = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction(ACCION_DETENER),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Monitor activo · " + Config.equipo(this))
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, "Detener", parar).build())
                .build();
    }
}
