package cl.owve.wifikeyboard;

import android.content.Context;
import java.security.SecureRandom;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

final class Config {
    static final int PORT = 47621;
    private Config() {}

    static String pin(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("owve_keyboard", Context.MODE_PRIVATE);
        String pin = prefs.getString("pin", "");
        if (pin.matches("[0-9]{6}")) return pin;
        int n = new SecureRandom().nextInt(1000000);
        pin = String.format(java.util.Locale.US, "%06d", n);
        prefs.edit().putString("pin", pin).apply();
        return pin;
    }

    static String localAddress() {
        String candidate = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> ips = ni.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress a = ips.nextElement();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (ni.getName().startsWith("wlan") || ni.getName().startsWith("wifi")) {
                        return a.getHostAddress();
                    }
                    if (candidate == null) candidate = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return candidate != null ? candidate : "Sin IPv4 local. Conecta Wi-Fi.";
    }
}
