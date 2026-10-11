package com.experimento.launcher.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Servicio de red mejorado.
 * Separa los argumentos de red en dos grupos:
 *  - getLanArgs()        → para mejorar LAN (multijugador local)
 *  - getServerConnectArgs() → para mejorar conexión a servidores externos (Aternos, etc.)
 *
 * IMPORTANTE: -Djava.net.preferIPv4Stack=true se eliminó del default porque
 * deshabilita IPv6 completamente y puede romper conexiones a servidores externos.
 */
public final class LanFixService {

    private LanFixService() {}

    /**
     * Detecta la IP local más adecuada para red local (LAN).
     * Prioriza interfaces físicas activas sobre virtuales y VPN.
     * Retorna "127.0.0.1" como fallback seguro.
     */
    public static String getLocalIpAddress() {
        String bestIp = null;
        int bestScore = -1;

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "127.0.0.1";

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;

                // Puntuar la interfaz: más puntaje = más preferida
                int score = 0;
                String name = iface.getName().toLowerCase();
                String displayName = iface.getDisplayName().toLowerCase();

                // Preferir interfaces físicas conocidas
                if (name.startsWith("eth") || name.startsWith("en")) score += 30;
                else if (name.startsWith("wlan") || name.startsWith("wi-fi") || displayName.contains("wi-fi")
                        || displayName.contains("wireless")) score += 20;
                else if (name.startsWith("lo") || name.startsWith("loopback")) score -= 100;

                // Penalizar interfaces virtuales/VPN/Docker
                if (iface.isVirtual()) score -= 50;
                if (name.contains("veth") || name.contains("docker") || name.contains("vmnet")
                        || name.contains("vbox") || name.contains("tun") || name.contains("tap")
                        || displayName.contains("virtual") || displayName.contains("vmware")) {
                    score -= 40;
                }

                // Bonus si tiene hardware address (interfaz física real)
                byte[] mac = iface.getHardwareAddress();
                if (mac != null && mac.length > 0) score += 10;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        if (score > bestScore) {
                            bestScore = score;
                            bestIp = addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return bestIp != null ? bestIp : "127.0.0.1";
    }

    /**
     * Argumentos JVM para mejorar la detección LAN (multijugador en red local).
     * Estos son SEGUROS y no rompen la conexión a servidores externos.
     * - Prefiere IPv4 en la resolución de nombres (NO deshabilita IPv6)
     * - Aumenta el TTL de multicast para mejor alcance en la red local
     */
    public static List<String> getLanArgs() {
        return List.of(
            // Timeout de conexión reducido para LAN (detecta servidores caídos más rápido)
            "-Dsun.net.client.defaultConnectTimeout=5000",
            "-Dsun.net.client.defaultReadTimeout=15000"
        );
    }

    /**
     * Argumentos JVM para mejorar la conexión a servidores externos (Aternos, etc.).
     * NO incluye preferIPv4Stack para preservar compatibilidad IPv6.
     */
    public static List<String> getServerConnectArgs() {
        return List.of(
            // No usar proxy del sistema (evita interferencia de proxies corporativos)
            "-Djava.net.useSystemProxies=false",
            // Resolver DNS más rápido (TTL de caché DNS)
            "-Dnetworkaddress.cache.ttl=30"
        );
    }

    /**
     * @deprecated Usar getLanArgs() + getServerConnectArgs() por separado.
     * Mantenido por compatibilidad con código existente.
     */
    @Deprecated
    public static List<String> getNetworkFixArgs() {
        List<String> args = new ArrayList<>();
        args.addAll(getLanArgs());
        args.addAll(getServerConnectArgs());
        return args;
    }
}

