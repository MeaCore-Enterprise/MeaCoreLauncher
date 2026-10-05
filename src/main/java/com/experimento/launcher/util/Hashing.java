package com.experimento.launcher.util;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {}

    public static String sha1Hex(InputStream in) throws Exception {
        try (in) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        }
    }

    /** SHA-256 en hexadecimal minúsculas. */
    public static String sha256Hex(InputStream in) throws Exception {
        try (in) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        }
    }
}
