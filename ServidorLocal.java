package com.gabriel.pantallanegra;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Responde el mismo JSON en http://<IP del teléfono>:8765/estado, con CORS abierto.
 * Solo atiende direcciones de la red local y solo lecturas. Mientras nadie consulta,
 * el hilo queda bloqueado en accept(): no consume CPU.
 */
final class ServidorLocal implements Runnable {

    static final int PUERTO = 8765;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private volatile ServerSocket ss;

    ServidorLocal(Context ctx) { this.ctx = ctx; }

    void iniciar() {
        Thread t = new Thread(this, "servidor-" + PUERTO);
        t.setDaemon(true);
        t.start();
    }

    void detener() {
        ServerSocket s = ss;
        ss = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void run() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PUERTO));
            ss = s;
            while (!s.isClosed()) {
                Socket c;
                try {
                    c = s.accept();
                } catch (IOException e) {
                    break; // cerrado al detener el monitor
                }
                atender(c);
            }
        } catch (IOException e) {
            Config.log(ctx, "Puerto " + PUERTO + " no disponible: " + e.getMessage());
        }
    }

    private void atender(Socket socket) {
        try (Socket s = socket) {
            if (!esLocal(s.getInetAddress())) return;
            s.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            String pedido = in.readLine();
            if (pedido == null) return;
            String l;
            while ((l = in.readLine()) != null && !l.isEmpty()) { /* encabezados: se ignoran */ }

            String[] partes = pedido.split(" ");
            String metodo = partes[0];
            String ruta = partes.length > 1 ? partes[1] : "";
            int q = ruta.indexOf('?');
            if (q >= 0) ruta = ruta.substring(0, q);

            OutputStream out = s.getOutputStream();
            if ("OPTIONS".equals(metodo)) {
                responder(out, "204 No Content", null);
            } else if ("GET".equals(metodo) && ("/estado".equals(ruta) || "/estado/".equals(ruta))) {
                responder(out, "200 OK", Lectura.leer(ctx, Config.equipo(ctx), null).toString());
            } else {
                responder(out, "404 Not Found", "{\"error\":\"usar GET /estado\"}");
            }
        } catch (Exception ignored) {
            // conexión cortada o pedido mal formado
        }
    }

    private static boolean esLocal(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        // IPv6 de red local (fc00::/7)
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }

    private static void responder(OutputStream out, String estado, String cuerpo) throws IOException {
        byte[] b = cuerpo != null ? cuerpo.getBytes(UTF8) : new byte[0];
        String enc = "HTTP/1.1 " + estado + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(enc.getBytes(UTF8));
        out.write(b);
        out.flush();
    }
}
