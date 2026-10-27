package cl.owve.wifikeyboard;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Un unico puerto TCP para teclado IME y mouse Accesibilidad.
 * El servidor puede seguir disponible aunque ningun campo de texto tenga foco,
 * pues el servicio de accesibilidad esta habilitado permanentemente.
 */
final class SessionServer {
    private static boolean started;
    private SessionServer() { }
    static synchronized void ensureStarted(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> run(app),"OWVE-WiFi-Server");
        worker.setDaemon(true);
        worker.start();
    }
    private static void run(Context app) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Config.PORT));
            while (true) {
                Socket client = ss.accept();
                try { serve(app, client); }
                catch (IOException ignored) { }
                finally {
                    RemoteMouseAccessibilityService.stopIfAvailable();
                    try { client.close(); } catch (IOException ignored) { }
                }
            }
        } catch (IOException ignored) {
            synchronized (SessionServer.class) { started = false; }
        }
    }
    private static String boundedLine(BufferedReader reader, int limit) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char)c);
            if (sb.length() > limit) throw new IOException("Linea demasiado extensa");
        }
        return null;
    }
    private static void serve(Context app, Socket client) throws IOException {
        client.setSoTimeout(8000);
        client.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(
                client.getInputStream(),StandardCharsets.UTF_8));
        String auth = boundedLine(in,100);
        if (!("AUTH " + Config.pin(app)).equals(auth)) {
            client.getOutputStream().write("NO\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        client.getOutputStream().write("OK\n".getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        client.setSoTimeout(0);
        RemoteKeyboardService.connected(true);
        try {
            String cmd;
            while ((cmd = boundedLine(in,1024)) != null) {
                if ((cmd.startsWith("T ") || cmd.startsWith("K ")) && cmd.length() > 2) {
                    RemoteKeyboardService.deliver(cmd);
                } else if (cmd.startsWith("M ") && cmd.length() <= 55) {
                    RemoteMouseAccessibilityService.accept(cmd.substring(2));
                    RemoteKeyboardService.mouseActivity();
                }
            }
        } finally {
            RemoteKeyboardService.connected(false);
        }
    }
}
