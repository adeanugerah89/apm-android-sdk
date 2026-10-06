# State — adeanugerah89

> State kerja pribadimu. Satu file per orang. Cap ~100 baris. Selesai → rotasi ke archive/.

## Now

- **Objective:** sesi pengujian di emulator. **Blokir rilis terakhir (checklist no. 15) TUTUP**, dan
  dua cacat P0 ditemukan + diperbaiki + diverifikasi ulang di device (baris **A24**).
- **Active feature:** (tidak ada) — 2026-10-05: **10 temuan code-review diperbaiki** (CONSTITUTION 2026-10-05); cek ulang repo saudara; `docs/` disinkronkan ke apm-docs `90b283b`; **A42** baru (sinyal sesi bersih, menunggu keputusan produk). **A36:** key di `apm-ingest/readapi.py` sudah saya ubah ke `apm-android-sdk` (lokal, belum di-commit; verify.sh repo itu PASS). A38 ✅; A39 🟠 (checklist 21); A41 ✅.
- **Status:** A1–A22 ✅ kecuali **A19 🟠** · **A24 ✅** · **A30 ✅** · **A27 🟠** (JVM selesai, native + device tersisa) ·
  **A31 ✅** · **A35 ✅** · **A32 ✅** · **A33 ✅** · **A28 ✅** · **A29 🟠** · **A36 🟠** · **A40 🟠** · **A39 🟠** · ⬜ **A23, A26, A34, A37, A42** · **A38 ✅** · **A41 ✅** · ⬜ **A43** (tipe implementasi `public`). Checklist device: 8 pass, 3 separuh, 7 belum (no. 17, 18 baru).
- **Last verify:** `HARNESS_VERIFY: PASS (all)` · 533 unit test, 0 gagal (2026-10-06). Envelope sungguhan
  terakhir diterima dari device 2026-09-11 (`schema_version:1`, gzip).

Dua cacat P0 sesi 2026-09-11 (A24) dan detailnya: [archive/sessions/2026-09-11-device-testing.md](../archive/sessions/2026-09-11-device-testing.md).

## Next step

**Sesi 2026-09-30 (review vs apm-docs):** `docs/00,01,02` disalin dari `apm-docs` (sekarang identik;
belum di-commit). Dua gap SDK ditemukan dan dicatat sebagai baris baru, **belum dikerjakan**:

- **A30 ✅** (MOB-03) — selesai, menunggu review. Baris baru hasil verifikasi vs docs: **A29** (SEC-07 backup,
  butuh keputusan Anda: `noBackupFilesDir` vs perbaiki path + dokumentasi), **A31** (config `max_batch`/
  `upload_interval_s`/sampling tidak dipakai), **A32** (cold start `lifecycle` iOS vs `performance` Android,
  tanya backend), **A33** (`error_domain`/`error_code` konstan). Urutan usulan berikutnya: A27 → A31.
- **A27 🟠** — sisi JVM **selesai** (351 test, `PASS (all)`); event `crash` kini membawa `breadcrumbs` + `breadcrumbs_dropped:"0"`.
  Sisa: (1) **native tanpa breadcrumbs, sengaja** — butuh keputusan Anda (persist snapshot sebelum crash vs terima kosong);
  (2) **uji di device** (checklist no. 17) — tidak ada emulator terhubung hari ini.
- **A31 ✅** — `max_batch` dan `upload_interval_s` kini dipakai (device: no. 18). **A34 ⬜** sampling: butuh field sample-rate di kontrak.
- **Cross-check repo backend (2026-10-02)** — hasil lengkap di `FEATURES.md`: **A35 ✅** (ts_client crash native), **A31** diperbaiki
  (interval `Double`), **A28** terjawab (backend: `session_id` per envelope), **A32** terjawab (tak ada yang membaca; ikut iOS = `lifecycle`),
  baru **A36** (nama SDK `apmkit-android` vs `apm-android-sdk`, butuh keputusan) dan **A37** (SEC-14 butuh field kontrak).
  **A32 ✅** (cold start `lifecycle`), **A33 ✅** (error_domain/code asli, `tls_version`, `reused_connection`). Baru **A38** (iOS kirim `req_headers`
  tak berdokumen). **A28 ✅** (session per event; keputusan di CONSTITUTION 2026-10-02) — mengubah **format file antrean di disk**
  (`{"event","session_id"}`, file lama tetap terbaca) dan menambah stempel session di `ApmBootstrap`. Baru **A39**: `app.version`, `user_id`,
  OS version juga di-stempel saat upload (kasus `user_id` = pertanyaan privasi). Butuh keputusan Anda: **A29** (backup), **A36** (nama SDK),
  **A27 native**. (A38 dan A39 `user_id` sudah diputuskan.)
- **A29 🟠** — antrean + cache config pindah ke `noBackupFilesDir` (keputusan saya, rekomendasi sebelumnya; koreksi bila salah), dengan migrasi dari
  `filesDir`. Dokumen resmi Android dicek. **A36 🟠**: nama tetap `apm-android-sdk` (keputusan Anda); `SdkIdentity.NAME` jadi satu sumber.
  Yang tersisa ada di repo lain (read-only): `apm-ingest/readapi.py:488` perlu key `"apm-android-sdk"`. **A40 🟠**: state SDK (`install_id`, dll.) pindah ke `noBackupFilesDir/apm-state.json` (opsi b; koreksi bila salah), migrasi hanya pada update asli.
  Asumsi belum terbukti di device: install hasil restore punya `firstInstallTime` ≈ `lastUpdateTime` (checklist 20).
- **A28 ⬜ (tunggu jawaban backend)** — `session_id` di-stamp saat envelope dibangun, jadi crash native
  dan backlog offline tercatat di sesi pelapor. Tanya tim backend/`apm-ingest`: per envelope atau per event?
- Belum diperiksa: apakah CONSTITUTION/INTEGRATION perlu memuat koreksi cakupan MOB-01 (hanya
  `OkHttpClient` yang dipasangi `networkEventListener()`) — di `02` baru tertulis untuk iOS.

**Tidak ada blokir rilis tersisa.** Yang terbuka sebelumnya:

- **A26 ⬜** (baru, kecil) — upload memakai chunked tanpa `Content-Length`; sebagian gateway
  menolaknya dan backend tidak bisa menilai `413` (`01` §7). Perbaikannya: gzip sekali ke ByteArray
  lalu laporkan panjangnya — sekaligus berhenti mengompresi ulang tiap retry.
- **A19 🟠** — butuh `APM_SYMBOLS_URL` + `APM_CI_TOKEN` sebagai repo secret (dari Anda).
- **A23 ⬜** — kontrak upload symbol native, keputusan tim backend.
- Checklist device sisa: rooted device (2), OOM kill (6), API 24–29, dan device fisik untuk no. 1.

**Harness pengujian device sekarang bisa diulang:** `TESTING-ON-DEVICE.md` +
`tools/device-testing/local_ingest_server.py`. Emulator **wajib** berjaringan tervalidasi —
dua percobaan sebelumnya salah menyimpulkan "SyncWorker tidak jalan" padahal itu lingkungannya.

## Parked

- **PELAJARAN (sudah jadi aturan di CONSTITUTION 2026-09-07 A17):** komponen yang bergantung
  pada layanan sistem yang tidak dimiliki Robolectric (Keystore, dll) **wajib** punya
  instrumented test. A14 dan A16 sempat ✅ dengan 304 unit test hijau sementara SDK-nya
  sebenarnya tidak menangkap apa pun di device. Unit test hijau bukan bukti untuk komponen
  semacam itu.
- **Emulator `Medium_Phone_API_36.1` mungkin masih berjalan** — saya yang menyalakannya.
  Matikan dengan `adb emu kill` kalau tidak dipakai.
- **Dua keputusan menunggu pemilik proyek** (dari A12, detail di CONSTITUTION 2026-09-07 A12):
  1. **WorkManager memaksa 4 permission ke app host** — `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`,
     `FOREGROUND_SERVICE`, `REORDER_TASKS`. Bertegangan dengan `02` §5 "tanpa wakeup khusus", dan
     akan terlihat di store listing app host. Sudah dikunci test supaya tidak bertambah diam-diam;
     keputusan (terima & dokumentasikan vs tinjau ulang WorkManager) belum diambil.
  2. **`androidx.benchmark:benchmark-macro-junit4` + runner ber-emulator di CI** — tanpa itu
     4 baris budget sisanya tidak akan pernah bisa ditegakkan dan A12 tidak bisa jadi ✅.
- **MOB-22 (sampling per tipe event)** — P1, di luar scope A10. `RemoteConfig.sampling` sudah
  di-decode tapi belum ada yang membacanya. Jadikan baris `FEATURES.md` sendiri.
- **KDoc `DiskEventQueue` salah** soal mtime di-set sebelum rename — kodenya tidak.
  `DiskEventQueueTest` menutupinya dengan `Thread.sleep(5)`. Flake yang menunggu terjadi.
- **`okhttp-tls`** belum diputuskan sebagai dependency test; tanpa itu penolakan pin salah (A15)
  tidak akan pernah terbukti di CI.

## Blockers

- A9b butuh sampel crash native asli untuk validasi parsing tombstone xCrash — tidak tersedia di
  lingkungan ini (tidak ada device/emulator dengan NDK crash trigger). Sudah didiskusikan &
  disetujui user untuk ditunda. Juga perlu approval sebelum menambah dependency
  `com.iqiyi.xcrash:xcrash-android-lib` ke `gradle/libs.versions.toml`.
- Manual checklist device-only bertambah 2 baris dari A10 (no. 10 cold start frame sungguhan,
  no. 11 kill switch end-to-end) — tidak memblokir apa pun, tapi belum bisa dicentang tanpa device.
- Checklist no. 4 (TLS fail-closed) sudah terbukti di unit test A11 (`TlsFailClosedTest`, dengan
  handshake TLS yang benar-benar gagal), tapi tetap ☐ sampai dijalankan terhadap ingest host asli.

## Changes

Sesi sebelumnya dirotasi ke [2026-09-11-device-testing.md](../archive/sessions/2026-09-11-device-testing.md) dan
[2026-10-02-review-vs-apm-docs.md](../archive/sessions/2026-10-02-review-vs-apm-docs.md) (A27/A30/A31).

| File | Change | Why |
|------|--------|-----|
| `SdkStorage.kt`, `UserIdProvider.kt`, `app/build.gradle.kts`, `.gitignore`, `ApmBootstrap.kt` (KDoc) | 5 temuan review ke-2 (CONSTITUTION 2026-10-06) | restore ≠ update untuk queue; handoff identitas di dalam lock; CA debug digenerate; `.kotlin/` di-ignore |
