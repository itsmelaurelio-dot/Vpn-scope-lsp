package com.hanaka.vpnperapp;

import android.net.VpnService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[VpnPerApp] ";
    private static final String BUILDER = "android.net.VpnService$Builder";
    private static final String MODULE_PKG = "com.hanaka.vpnperapp";

    /** true saat kode modul sendiri yang memanggil addAllowed/addDisallowedApplication. */
    private static final ThreadLocal<Boolean> INJECTING = new ThreadLocal<>();

    /** Salinan pengaturan yang dibaca dari UI modul (atau dari Config bila UI belum dipakai). */
    private static class Snapshot {
        boolean exclude;          // false = mode "hanya yang dicentang", true = mode "kecuali"
        List<String> list;
        String source;
        long at;
    }

    private static volatile Snapshot snap;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) return;

        if (MODULE_PKG.equals(lpparam.packageName)) {
            hookSelf(lpparam);
            return;
        }

        final ClassLoader cl = lpparam.classLoader;
        final String pkg = lpparam.packageName;

        try {
            // Deny list bawaan app VPN: dibuang di mode "hanya yang dicentang"
            // (allow list dan deny list tidak boleh dicampur), dipertahankan di mode "kecuali".
            XposedHelpers.findAndHookMethod(BUILDER, cl, "addDisallowedApplication",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(INJECTING.get())) return;
                            if (!snapshot().exclude) {
                                param.setResult(param.thisObject);
                            }
                        }
                    });

            // Allow list bawaan app VPN: selalu dibuang, hanya milik modul yang dipakai.
            XposedHelpers.findAndHookMethod(BUILDER, cl, "addAllowedApplication",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(INJECTING.get())) {
                                param.setResult(param.thisObject);
                            }
                        }
                    });

            // Sebelum TUN dibuat, suntikkan daftar kita.
            XposedHelpers.findAndHookMethod(BUILDER, cl, "establish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    inject((VpnService.Builder) param.thisObject, pkg);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    snap = null; // koneksi berikutnya baca pengaturan terbaru
                }
            });

            XposedBridge.log(TAG + "hook terpasang di " + pkg + " (proses " + lpparam.processName + ")");
        } catch (Throwable t) {
            if (!(t instanceof ClassNotFoundException)
                    && !(t instanceof NoSuchMethodError)) {
                XposedBridge.log(TAG + "gagal hook " + pkg + ": " + t);
            }
        }
    }

    /** Indikator untuk UI modul: MainActivity.isModuleActive() jadi true bila modul di-scope ke dirinya sendiri. */
    private void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(MODULE_PKG + ".MainActivity", lpparam.classLoader,
                    "isModuleActive", XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal pasang indikator: " + t);
        }
    }

    private static Snapshot snapshot() {
        Snapshot s = snap;
        if (s != null && System.currentTimeMillis() - s.at < 3000) return s;
        s = load();
        snap = s;
        return s;
    }

    private static Snapshot load() {
        Snapshot s = new Snapshot();
        s.at = System.currentTimeMillis();
        try {
            XSharedPreferences sp = new XSharedPreferences(MODULE_PKG, "config");
            if (sp.getFile().canRead()) {
                Set<String> set = sp.getStringSet("allowed", null);
                if (set != null) {
                    s.list = new ArrayList<>(set);
                    s.exclude = "exclude".equals(sp.getString("mode", "allow"));
                    s.source = "UI modul";
                    return s;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal baca pengaturan UI: " + t);
        }
        s.list = new ArrayList<>(Arrays.asList(Config.ALLOWED));
        s.exclude = false;
        s.source = "allowed.txt (bawaan)";
        return s;
    }

    private static void inject(VpnService.Builder b, String vpnPkg) {
        snap = null;
        Snapshot s = snapshot();
        INJECTING.set(Boolean.TRUE);
        try {
            if (s.exclude) {
                for (String p : s.list) addApp(b, p, false);
            } else {
                int ok = 0;
                for (String p : s.list) {
                    if (addApp(b, p, true)) ok++;
                }
                if (ok == 0) {
                    XposedBridge.log(TAG + "tidak ada paket valid, pakai fallback " + Config.FALLBACK_PKG);
                    addApp(b, Config.FALLBACK_PKG, true);
                }
            }
        } finally {
            INJECTING.remove();
        }
        XposedBridge.log(TAG + "VPN " + vpnPkg
                + " | sumber: " + s.source
                + " | mode: " + (s.exclude ? "KECUALI" : "HANYA YANG DICENTANG")
                + " | allowed di Builder: " + dump(b, "allowedApplications")
                + " | disallowed di Builder: " + dump(b, "disallowedApplications"));
    }

    private static boolean addApp(VpnService.Builder b, String p, boolean allow) {
        try {
            if (allow) {
                b.addAllowedApplication(p);
            } else {
                b.addDisallowedApplication(p);
            }
            return true;
        } catch (Throwable first) {
            // Fallback: isi langsung ke VpnConfig di dalam Builder (lewat refleksi).
            try {
                Object cfg = XposedHelpers.getObjectField(b, "mConfig");
                Field f = cfg.getClass().getDeclaredField(
                        allow ? "allowedApplications" : "disallowedApplications");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) f.get(cfg);
                if (list == null) {
                    list = new ArrayList<>();
                    f.set(cfg, list);
                }
                list.add(p);
                return true;
            } catch (Throwable second) {
                XposedBridge.log(TAG + "gagal menambah " + p + ": " + first + " | " + second);
                return false;
            }
        }
    }

    /** Baca isi daftar yang benar-benar ada di Builder, untuk verifikasi di log. */
    private static String dump(VpnService.Builder b, String field) {
        try {
            Object cfg = XposedHelpers.getObjectField(b, "mConfig");
            return String.valueOf(XposedHelpers.getObjectField(cfg, field));
        } catch (Throwable t) {
            return "(tidak terbaca)";
        }
    }
}
