package com.hanaka.vpnperapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final String PREF_NAME = "config";
    static final String KEY_ALLOWED = "allowed";
    static final String KEY_MODE = "mode";
    static final String MODE_ALLOW = "allow";
    static final String MODE_EXCLUDE = "exclude";

    private static final int REQ_BACKUP = 101;
    private static final int REQ_RESTORE = 102;
    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_CLEAR_ALL = 2;
    private static final int MENU_SYSTEM = 3;
    private static final int MENU_BACKUP = 4;
    private static final int MENU_RESTORE = 5;

    private static final Pattern PKG_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    /** Di-hook oleh MainHook (bila modul di-scope ke dirinya sendiri) supaya mengembalikan true. */
    public static boolean isModuleActive() {
        return false;
    }

    private static class Item {
        String pkg;
        String label;
        Drawable icon;
    }

    private SharedPreferences prefs;
    private final Set<String> selected = new HashSet<>();
    private String mode = MODE_ALLOW;
    private boolean showSystem = false;
    private String query = "";

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private ListAdapter adapter;
    private TextView status;
    private RadioGroup radioGroup;
    private RadioButton rbAllow;
    private RadioButton rbExclude;

    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            // LSPosed/Vector mengalihkan ini ke folder yang bisa dibaca hook.
            return getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void save() {
        prefs.edit()
                .putStringSet(KEY_ALLOWED, new HashSet<>(selected))
                .putString(KEY_MODE, mode)
                .apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.parseColor("#9E9E9E"));
        status.setPadding(dp(16), dp(12), dp(16), dp(4));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        prefs = openPrefs();
        if (prefs == null) {
            status.setTextColor(Color.parseColor("#FF8A80"));
            status.setText("Penyimpanan bersama: TIDAK AKTIF \u2717\n"
                    + "Framework belum mengaktifkan penyimpanan bersama untuk modul ini.\n"
                    + "Pastikan modul VPN Per-App sudah AKTIF di Vector/LSPosed, lalu buka ulang app ini.\n"
                    + "Sementara itu hook memakai daftar dari allowed.txt.");
            setContentView(root);
            return;
        }

        Set<String> saved = prefs.getStringSet(KEY_ALLOWED, null);
        if (saved != null) {
            selected.addAll(saved);
            mode = MODE_EXCLUDE.equals(prefs.getString(KEY_MODE, MODE_ALLOW))
                    ? MODE_EXCLUDE : MODE_ALLOW;
        } else {
            // Belum pernah disimpan: hook memakai daftar bawaan, jadi tampilkan itu.
            Collections.addAll(selected, Config.ALLOWED);
        }

        // Pilihan mode
        radioGroup = new RadioGroup(this);
        radioGroup.setOrientation(RadioGroup.VERTICAL);
        radioGroup.setPadding(dp(16), dp(4), dp(16), dp(4));
        rbAllow = new RadioButton(this);
        rbAllow.setId(View.generateViewId());
        rbAllow.setText("Hanya app yang dicentang yang lewat VPN");
        rbExclude = new RadioButton(this);
        rbExclude.setId(View.generateViewId());
        rbExclude.setText("Semua app lewat VPN, kecuali yang dicentang");
        radioGroup.addView(rbAllow);
        radioGroup.addView(rbExclude);
        radioGroup.check(MODE_EXCLUDE.equals(mode) ? rbExclude.getId() : rbAllow.getId());
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            mode = checkedId == rbExclude.getId() ? MODE_EXCLUDE : MODE_ALLOW;
            save();
            updateStatus();
        });
        root.addView(radioGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(this);
        search.setHint("Cari app...");
        search.setSingleLine(true);
        search.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        adapter = new ListAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.setOnItemClickListener((parent, view, position, id) -> {
            Item it = shown.get(position);
            if (selected.contains(it.pkg)) {
                selected.remove(it.pkg);
            } else {
                selected.add(it.pkg);
            }
            save();
            adapter.notifyDataSetChanged();
            updateStatus();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        setContentView(root);
        updateStatus();
        loadApps();
    }

    // ---------- Menu (pilih semua, hapus semua, app sistem, backup, restore) ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SELECT_ALL, 0, "Pilih semua yang tampil");
        menu.add(0, MENU_CLEAR_ALL, 1, "Hapus semua yang tampil");
        menu.add(0, MENU_SYSTEM, 2, "Tampilkan app sistem")
                .setCheckable(true).setChecked(showSystem);
        menu.add(0, MENU_BACKUP, 3, "Backup daftar ke file");
        menu.add(0, MENU_RESTORE, 4, "Restore daftar dari file");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (prefs == null) return true;
        switch (item.getItemId()) {
            case MENU_SELECT_ALL:
                for (Item it : shown) selected.add(it.pkg);
                save();
                applyFilter();
                updateStatus();
                return true;
            case MENU_CLEAR_ALL:
                for (Item it : shown) selected.remove(it.pkg);
                save();
                applyFilter();
                updateStatus();
                return true;
            case MENU_SYSTEM:
                showSystem = !showSystem;
                item.setChecked(showSystem);
                loadApps();
                return true;
            case MENU_BACKUP: {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, "vpnperapp-backup.txt");
                startActivityForResult(i, REQ_BACKUP);
                return true;
            }
            case MENU_RESTORE: {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_RESTORE);
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_BACKUP) {
            backupTo(uri);
        } else if (requestCode == REQ_RESTORE) {
            restoreFrom(uri);
        }
    }

    private void backupTo(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("tidak bisa membuka file");
            StringBuilder sb = new StringBuilder();
            sb.append("# VPN Per-App backup\n");
            sb.append("mode=").append(mode).append('\n');
            List<String> sorted = new ArrayList<>(selected);
            Collections.sort(sorted);
            for (String p : sorted) sb.append(p).append('\n');
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Backup tersimpan (" + selected.size() + " app)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Backup gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreFrom(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("tidak bisa membuka file");
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            Set<String> newSel = new HashSet<>();
            String newMode = MODE_ALLOW;
            boolean sawMode = false;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("mode=")) {
                    sawMode = true;
                    newMode = MODE_EXCLUDE.equals(line.substring(5).trim())
                            ? MODE_EXCLUDE : MODE_ALLOW;
                    continue;
                }
                if (PKG_PATTERN.matcher(line).matches()) newSel.add(line);
            }
            if (!sawMode) {
                Toast.makeText(this, "File bukan backup VPN Per-App", Toast.LENGTH_LONG).show();
                return;
            }
            selected.clear();
            selected.addAll(newSel);
            mode = newMode;
            save();
            radioGroup.check(MODE_EXCLUDE.equals(mode) ? rbExclude.getId() : rbAllow.getId());
            updateStatus();
            loadApps();
            Toast.makeText(this, "Restore berhasil (" + newSel.size() + " app)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Restore gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Status, daftar app, filter ----------

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Penyimpanan bersama: aktif \u2713\n");
        sb.append(isModuleActive()
                ? "Modul di framework: aktif \u2713\n"
                : "Modul di framework: tidak terdeteksi (indikator opsional, cek log Vector)\n");
        sb.append("Dipilih: ").append(selected.size())
                .append(" app. Perubahan berlaku saat VPN disambung ulang.");
        if (MODE_EXCLUDE.equals(mode)) {
            sb.append("\nMode ini membuat hampir semua app, termasuk game, lewat VPN.");
        }
        status.setText(sb.toString());
    }

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        final boolean includeSystem = showSystem;
        final Set<String> sel = new HashSet<>(selected);
        new Thread(() -> {
            List<Item> tmp = new ArrayList<>();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (ai.packageName.equals(getPackageName())) continue;
                boolean hasLauncher = pm.getLaunchIntentForPackage(ai.packageName) != null;
                if (!includeSystem && !hasLauncher && !sel.contains(ai.packageName)) continue;
                Item it = new Item();
                it.pkg = ai.packageName;
                it.label = String.valueOf(pm.getApplicationLabel(ai));
                it.icon = pm.getApplicationIcon(ai);
                tmp.add(it);
            }
            runOnUiThread(() -> {
                all.clear();
                all.addAll(tmp);
                applyFilter();
            });
        }).start();
    }

    /** Menyaring berdasarkan kata kunci dan mengurutkan: yang dicentang di atas, lalu abjad. */
    private void applyFilter() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Item it : all) {
            if (q.isEmpty()
                    || it.label.toLowerCase(Locale.ROOT).contains(q)
                    || it.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(it);
            }
        }
        Collections.sort(shown, (a, b) -> {
            boolean sa = selected.contains(a.pkg);
            boolean sb = selected.contains(b.pkg);
            if (sa != sb) return sa ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        adapter.notifyDataSetChanged();
    }

    private class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView name;
            TextView pkgView;
            CheckBox cb;

            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));

                icon = new ImageView(MainActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = dp(14);
                row.addView(texts, tlp);

                name = new TextView(MainActivity.this);
                name.setTextSize(16);
                texts.addView(name);

                pkgView = new TextView(MainActivity.this);
                pkgView.setTextSize(12);
                pkgView.setTextColor(Color.parseColor("#9E9E9E"));
                texts.addView(pkgView);

                cb = new CheckBox(MainActivity.this);
                cb.setClickable(false);
                cb.setFocusable(false);
                row.addView(cb);

                row.setTag(new View[]{icon, name, pkgView, cb});
            } else {
                row = (LinearLayout) convertView;
                View[] v = (View[]) row.getTag();
                icon = (ImageView) v[0];
                name = (TextView) v[1];
                pkgView = (TextView) v[2];
                cb = (CheckBox) v[3];
            }

            Item it = shown.get(position);
            icon.setImageDrawable(it.icon);
            name.setText(it.label);
            pkgView.setText(it.pkg);
            cb.setChecked(selected.contains(it.pkg));
            return row;
        }
    }
}
