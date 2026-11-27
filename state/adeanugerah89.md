# State — adeanugerah89

> State kerja pribadimu. Satu file per orang. Cap ~100 baris. Selesai → rotasi ke archive/.

## Now

- **Objective:** sesi pengujian di emulator. **Blokir rilis terakhir (checklist no. 15) TUTUP**, dan
  dua cacat P0 ditemukan + diperbaiki + diverifikasi ulang di device (baris **A24**).
- **Active feature:** (tidak ada) — **A44 ✅** 2026-11-27 (tanpa perubahan kode produksi: backend sudah membaca `drop_reasons`; ditambah `SdkHealthWireTest` yang mengunci key di wire; 616 test, `PASS (all)`; cek ujung-ke-ujung device→backend BELUM dilakukan). Sisa yang bisa dikerjakan agen: tidak ada — A53 butuh keputusan Anda, sisanya menunggu pihak luar (A50, A42, A34, A37, A23). Sebelumnya: **A51 ✅** 2026-11-27 (`APM.reportNetworkRequest` untuk stack non-OkHttp; 612 test, `PASS (all)`; menunggu review; belum di device; dibangun tanpa jawaban produk atas instruksi pemilik). Berikutnya usulan: A53 (butuh keputusan), A44. Catatan lingkungan: Proxyman (proxy sistem :9090) membuat `NetworkEventAttrsTest` DNS gagal — jalankan verify dengan `JAVA_TOOL_OPTIONS=-Dhttp.nonProxyHosts=*`. Sebelumnya: **A52 ✅** 2026-11-27 (`event_id` crash native + termination deterministik; 599 test, `PASS (all)`; menunggu review; belum di device). Berikutnya usulan: A51, A53. Sebelumnya: **A54 ✅** 2026-11-27 (`setUser` abaikan string kosong/spasi; 590 test, `PASS (all)`; menunggu review; belum di device). Berikutnya usulan: A53 (butuh keputusan pemetaan tipe jaringan), A52/A51. Sebelumnya: **A39 ✅ · A29 ✅ · A40 ✅** 2026-11-12 (penutupan murni bukti device: checklist 19/20/21 ☑; 583 test, `PASS (all)`; menunggu review). Berikutnya usulan: rapikan docs usang, A52/A51, lalu keputusan eksternal (A50, A42). Sebelumnya: **A26 ✅** 2026-11-09 (upload kini bawa `Content-Length`; 583 test, `PASS (all)`; menunggu review; belum di device — tak perlu, murni framing HTTP). Sebelumnya: **A43 ✅** 2026-10-22 (`internal` + explicitApi; 581 test, `PASS (all)`; menunggu review; belum di device). Berikutnya usulan: A50 (butuh Kevin), A42 (butuh keputusan produk), uji device fisik. Sebelumnya: **A47 ✅** 2026-10-21 (hapus `files/apm-native-crash/`; 581 test, `PASS (all)`; menunggu review; belum di device). Berikutnya usulan: A43. Sebelumnya: **2026-10-21: sinkron ulang** (apm-docs `6c573ac`, apm-backend `ef19a10`, apm-ingest `e3dba7f`, apm-backoffice `bffb2a7` = remote, di-`pull --ff-only` atas pilihan Anda; APMKit lokal 0.0.7, remote 0.15). `docs/00,01,02` identik dengan apm-docs. 2026-10-14: **A50 baru** (kontrak upload symbol backend hanya Mach-O → A19 tidak akan diterima; 10-21: crash Android semua v1 + badge "Coarse grouping, upload the dSYM"), **A51**, **A52 baru** (event_id crash deterministik, opsional), A36/A44/A26/A42/A49 diberi catatan. Usulan berikutnya ada di jawaban sesi; menunggu pilihan Anda. 2026-10-12: `docs/00,01,02` disinkronkan ke apm-docs `258f681` (belum di-commit); **A49 ✅** — `Scrubber.normalizePath` kini mengikuti SEC-03b (`02` §6.1.1; 23 vektor jadi test; menunggu review). Keputusan: fallback email/JWT dipertahankan di langkah terakhir (CONSTITUTION 2026-10-12) — tanyakan ke pemilik iOS. `user_ref`, `hang_*`, Alamofire: tidak ada dampak ke Android. 2026-10-05: **10 temuan code-review diperbaiki** (CONSTITUTION 2026-10-05); cek ulang repo saudara; `docs/` disinkronkan ke apm-docs `90b283b`; **A42** baru (sinyal sesi bersih, menunggu keputusan produk). **A36:** key di `apm-ingest/readapi.py` sudah saya ubah ke `apm-android-sdk` (lokal, belum di-commit; verify.sh repo itu PASS). A38 ✅; A39 🟠 (checklist 21); A41 ✅.
- **Status:** A1–A22 ✅ kecuali **A19 🟠** · **A24 ✅** · **A30 ✅** · **A27 🟠** (JVM selesai + terbukti di device; native tanpa breadcrumbs menunggu keputusan) ·
  **A31 ✅** · **A35 ✅** · **A32 ✅** · **A33 ✅** · **A28 ✅** · **A29 ✅** · **A36 🟠** · **A40 ✅** · **A39 ✅** · ⬜ **A23, A34, A37, A42** · **A26 ✅** · **A38 ✅** · **A41 ✅** · ⬜ **A43** (tipe implementasi `public`) · **A49 ✅** (SEC-03b). Checklist device (21 item): **18 ☑**, 1 separuh (no. 1, device fisik), 2 belum (no. 2 rooted, no. 6 OOM kill).
- **Last verify:** `HARNESS_VERIFY: PASS (all)` · 616 unit test, 0 gagal (2026-11-27, setelah A44; `ApmBootstrapMarkersTest` + `SyncSchedulerTest` gagal sekali di satu run penuh lalu lulus di 4 run berikutnya — flake belum dijelaskan) · `connectedDebugAndroidTest` 12/12 (AVD API 36) · run perangkat vs apm-ingest 2026-10-06: checklist 4, 8, 9, 12, 17, 18 ☑; 10 ☑ setelah A46 · **A46 ✅ · A48 ✅** (menunggu review); 19, 20, 21 ◨; 6 tak bisa dibuat di AVD. Envelope sungguhan
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
| `docs/00-Overview.md`, `docs/01-Kontrak-Data-API.md`, `docs/02-Mobile-SDK.md` | disalin dari apm-docs `258f681` (identik, `cmp` lolos) | tertinggal sejak 2026-10-05: SEC-03b + tabel vektor, `user_ref` 64-hex, ingest wajib chunked |
| `FEATURES.md` | baris A49 + baris fase "Spec drift (apm-docs 2026-10-12)" | `normalizePath` belum sesuai SEC-03b; belum dikerjakan |
| `Scrubber.kt` (`normalizePath`) | enam aturan SEC-03b berurutan + fallback PII di akhir | mengikuti `02` §6.1.1; sebelumnya hanya UUID / ≥5 digit / pola PII |
| `PathNormalizationVectorsTest.kt` (baru), `ScrubberTest.kt` | 23 vektor + edge; satu test lama yang bertentangan ditulis ulang | bukti A49; 6 negative control dijalankan |
| `CONSTITUTION.md`, `FEATURES.md` | entri keputusan A49; A49 ✅ + bukti | fallback PII dipertahankan (bisa dibalik) |
| `docs/01-Kontrak-Data-API.md`, `docs/02-Mobile-SDK.md` | disalin dari apm-docs `6c573ac` (`cmp` lolos, 00 identik; 01 terakhir berubah untuk basis `crash_info` iOS saja) | tertinggal: rumus `requests` network, `sdk_versions.installs/last_seen`, kontrak upload BE-10, adapter Alamofire (iOS saja) — tidak ada dampak kode ke Android |
| `FEATURES.md` | catatan cross-check 2026-10-14 (+ 10-21: A44, A50; baris A52) di A19/A23/A26/A36/A42/A44/A49; baris baru A50, A51; baris fase baru | hasil sinkron: kontrak symbol backend Mach-O-only, `is_fatal` filter + `drop_reasons` sudah di backend Go, PathNormalizer iOS tanpa fallback email |
| `SdkStorage.kt`, `ApmBootstrap.kt` | `deleteAbandonedNativeCrashDir` + satu panggilan setelah `migrateLegacy` | A47: sisa tombstone xCrash di `filesDir` ikut backup dan tak terbaca apa pun |
| `SdkStorageTest.kt` (+4), `ApmPublicApiTest.kt` (+1) | test hapus, idempoten, tak menyentuh no-backup root, graph `APM.start()` | bukti A47; 2 negative control dijalankan |
| `sdk/build.gradle.kts`, 60 file `sdk/src/main/**` | `internal` pada 91 deklarasi + `kotlin { explicitApi() }`; `public` eksplisit di 5 tipe | A43: compiler menegakkan daftar API yang didukung |
| `CallerSimulator.kt` (test), `RELEASING.md`, `CONSTITUTION.md`, `FEATURES.md` | helper test `internal`; teks RELEASING diperbarui; keputusan 2026-10-22; A43 ✅ + bukti | konsekuensi A43 + dokumentasi |
| `IngestUploader.kt`, `IngestUploaderTest.kt` (+2) | `gzip()` kompres sekali ke `Buffer` → body `ByteString` bertanda panjang; test: tanpa `Transfer-Encoding`, `Content-Length == bodySize`, panjang < JSON | A26: hilangkan chunked; 2 test gagal sebelum perubahan (negative control) |
| `CONSTITUTION.md`, `FEATURES.md` | entri keputusan A26 (2026-11-09); A26 ✅ + bukti | `413` lokal sengaja tidak dikerjakan |
| `MainActivity.kt` (app) | tombol `clearUser (item 21)` | checklist 21 butuh `clearUser()` di device; app uji belum punya tombolnya |
| `FEATURES.md` | A29/A39/A40 🟠→✅ + bukti; checklist 19/20/21 ◨→☑ | run device 2026-11-12 (backup→restore nyata, urutan setUser/clearUser); tidak ada kode SDK berubah |
| `CONSTITUTION.md` | entri 2026-11-12 (penutupan A29/A39/A40) | asumsi `firstInstallTime`≈`lastUpdateTime` kini teramati; catatan pembatas |
| `gradle.properties` | `org.gradle.java.home` → JBR 21 (`~/Library/Java/JavaVirtualMachines/jbr-21.0.11`) | JBR Android Studio kini JDK 25; Kotlin di Gradle 8.13 gagal parse `25.0.4`; `verify.sh build` PASS lagi (2026-11-27) |
| `docs/01-Kontrak-Data-API.md`, `docs/02-Mobile-SDK.md` | disalin dari apm-docs **HEAD `2d93cac`** (bukan worktree: apm-docs punya perubahan 01 yang belum di-commit; `cmp` 02 lolos) | tertinggal sejak `6c573ac`: MOB-12 aturan konektivitas, MOB-28 `clearUser`/atribusi saat capture/blank diabaikan, §4.3 definisi crash vs hang (backend saja) |
| `FEATURES.md` | baris A53 (MOB-12) + A54 (blank `setUser`); tabel Phase boundary diperbarui (A19→A50, A26, A27/A28); baris fase baru | docs usang + spec drift baru dari sinkron; belum dikerjakan |
| `INTEGRATION.md` §4, `README.md` Status | catatan: upload symbol belum bisa dipakai (A50); `0.1.0` sudah rilis | klaim lama "tinggal URL + token" tidak benar lagi sejak 2026-10-14 |
| `UserIdProvider.kt`, `ManualApi.kt`, `ApmBootstrap.kt`, `APM.kt` (KDoc) | `setUser` mengembalikan Boolean, abaikan blank; hook `debugLog` (hanya bila debug logging aktif, tanpa nilai) | A54: `02` MOB-28/SEC-06 |
| `UserIdProviderTest.kt` (+5), `ManualApiTest.kt` (+2), `INTEGRATION.md`, `FEATURES.md` | test + negative control (5 gagal tanpa perbaikan); A54 ✅ | bukti A54 |
| `ReportEventId.kt` (baru), `NativeCrashReporter.kt`, `TerminationDetector.kt` (`ExitInfo.timestamp`, `detect()`), `ApmBootstrap.kt` | `event_id` crash native + termination dari `(kind, package, pid, exit ts)`; JVM tetap acak | A52: crash/termination yang terkirim dua kali (marker hilang) di-dedup backend |
| `ReportEventIdTest` (4), `NativeCrashReporterTest` (+2), `TerminationDetectorTest` (+2), `BuildTerminationEventTest` (+1), `ApmPublicApiTest` (+1 assert), `CONSTITUTION.md`, `FEATURES.md` | test + negative control (2 gagal tanpa perbaikan); entri 2026-11-27; A52 ✅ | bukti A52 |
| `NetworkEventAttrs.kt` (baru), `ManualNetworkReporter.kt` (baru), `ApmEventListener.kt`, `FailureCategory.kt` (overload `Throwable`), `APM.kt` | `APM.reportNetworkRequest`; listener dipindah ke builder attrs bersama | A51: paritas `reportNetworkTask` iOS untuk stack non-OkHttp |
| `ManualNetworkReporterTest` (12, baru), `ApmPublicApiTest` (+1), `INTEGRATION.md`, `DATA-INVENTORY.md`, `CONSTITUTION.md`, `FEATURES.md` | test + negative control (7 gagal); doc: hanya OkHttpClient ber-listener yang tertangkap; A51 ✅ | bukti A51 |
| `SdkHealthWireTest.kt` (baru, 4), `FEATURES.md` | kunci wire `sdk.health` dikunci dari overflow antrean nyata sampai JSON; A44 ✅ tanpa kode produksi; negative control (rename `@SerialName`) 3 gagal | A44: permukaan baca sudah ada di apm-backend; celah yang tersisa di repo ini hanya kunci wire yang belum ada test-nya |
