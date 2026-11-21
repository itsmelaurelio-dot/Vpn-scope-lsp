# VPN Per-App

Modul **LSPosed / Vector** supaya app VPN yang **tidak punya split tunneling** tetap bisa dipakai per aplikasi: kamu yang memilih app mana saja yang lewat VPN, sisanya (termasuk game) tetap di jaringan normal.

> 🇬🇧 **In short:** an Xposed (LSPosed/Vector) module that forces any VpnService-based VPN app to route only the apps you pick (or everything except the apps you pick). Requires root + Zygisk + LSPosed/Vector. English notes at the bottom.

## Fitur

- **Dua mode**
  - *Hanya yang dicentang lewat VPN*: IP dan lokasi berubah hanya untuk app pilihanmu.
  - *Semua lewat VPN, kecuali yang dicentang*: cocok kalau cuma beberapa game yang mau dikecualikan.
- **UI pemilih app**: cari app, pilih semua / hapus semua, tampilkan app sistem, daftar terpilih naik ke atas.
- **Backup & restore** daftar ke file `.txt`.
- **Tanpa edit kode**: pilihan dibaca ulang setiap VPN tersambung, jadi tidak perlu restart app.
- Log verifikasi di LSPosed/Vector (cari `[VpnPerApp]`) yang menampilkan isi daftar yang benar-benar dipakai.

## Syarat

- Android 8.0+ dengan **root** (Magisk / KernelSU) dan **Zygisk**
- **LSPosed** atau **Vector** (framework Xposed) yang sudah terpasang dan jalan
- App VPN yang memakai `VpnService` Android (hampir semua app VPN biasa)

## Instalasi

1. Download APK terbaru dari halaman [**Releases**](../../releases).
2. Install APK-nya.
3. Buka LSPosed/Vector, tab **Modul**, aktifkan **VPN Per-App**.
4. Di scope modul, centang **app VPN-mu**. Hanya itu, jangan centang app lain.
   (Opsional: centang juga **VPN Per-App** supaya indikator status di app menyala.)
5. Force stop app VPN-mu, lalu buka lagi.

## Cara pakai

1. Buka app **VPN Per-App**, pilih mode, lalu centang app-app yang diinginkan.
2. Sambungkan (atau sambungkan ulang) VPN.
3. Cek IP dari app yang kamu pilih dan dari app lain untuk memastikan.
4. Kalau ragu, buka log LSPosed/Vector dan cari baris `[VpnPerApp]`. Di situ terlihat sumber daftar, mode, dan isi allow/deny list yang benar-benar masuk ke VPN.

## Cara kerja

Android hanya mengizinkan VPN memakai **allow list** *atau* **deny list** per app, tidak keduanya sekaligus. Modul ini meng-hook `VpnService.Builder` di dalam app VPN-mu: daftar bawaan app VPN dibuang, lalu daftar pilihanmu disuntikkan sebelum `establish()` dipanggil. App VPN-nya sendiri tidak dimodifikasi.

## Batasan dan catatan jujur

- **Baru diuji dengan satu app VPN** di Vector. Beberapa app VPN membuat tunnel lewat kode native (bukan lewat `VpnService.Builder` Java), dan itu **tidak akan kena** hook ini.
- Pilihan dari UI dibagikan ke hook lewat fitur *New XSharedPreferences* milik LSPosed. Fitur itu **sudah deprecated dan dijadwalkan dihapus** di LSPosed, dan dukungan di Vector belum pasti. Kalau gagal, app menampilkan pesan merah dan hook otomatis memakai daftar bawaan dari `allowed.txt` (lihat bagian build).
- APK resmi di Releases ditandatangani dengan kunci pribadi yang disimpan di GitHub Secrets. Kalau kamu fork repo ini dan build sendiri, hasilnya memakai kunci debug bawaan repo dan akan ditandatangani ulang, jadi tidak bisa dipasang sebagai update dari APK resmi
- Dibuat sebagai proyek pribadi. **Tanpa garansi.** Memakai Xposed/root ada risikonya, jadi pakai dengan hati-hati.

## Build sendiri

**Lewat GitHub Actions (tanpa PC):** fork repo ini, lalu jalankan workflow **Build APK** di tab Actions. APK muncul di bagian *Artifacts*. Edit `allowed.txt` (satu package name per baris) untuk menentukan daftar bawaan yang dipakai kalau UI belum dipakai atau penyimpanan bersama gagal.

**Lewat Android Studio:** buka proyek, pastikan repositori `https://api.xposed.info/` terdaftar untuk dependensi `de.robv.android.xposed:api:82` (`compileOnly`), lalu `assembleDebug`.

## Contribute

Issue dan pull request dipersilakan. Kalau melapor masalah, sertakan: versi Android, framework (LSPosed/Vector + versi), app VPN yang dipakai, dan potongan log yang berisi `[VpnPerApp]`.

---

## English notes

- Hooks `VpnService.Builder` inside the VPN app (scope it to **that app only**), drops the app's own allow/deny lists, then injects yours before `establish()`.
- Two modes: *allow only the ticked apps* or *everything except the ticked apps*.
- Settings are shared to the hook through LSPosed's deprecated *New XSharedPreferences*; if unavailable the hook falls back to `allowed.txt` baked in at build time.
- Tested with a single VPN app on Vector so far. VPN apps that build their tunnel natively won't be affected.
- Releases are debug-signed. No warranty.
