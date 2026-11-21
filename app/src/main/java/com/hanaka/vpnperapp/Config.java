package com.hanaka.vpnperapp;

/**
 * Daftar app yang BOLEH lewat VPN. App lain (termasuk game) tetap di jaringan normal.
 * Isi dengan package name, bukan nama app. Ubah lalu build ulang.
 */
public final class Config {
    private Config() {}

    public static final String[] ALLOWED = {
            "com.android.chrome",
            // "com.contoh.aplikasi",
    };

    /** Dipakai kalau tidak satu pun paket di ALLOWED berhasil ditambahkan,
     *  supaya VPN tidak jatuh ke mode "semua app" tanpa sengaja. */
    public static final String FALLBACK_PKG = "com.android.shell";
}
