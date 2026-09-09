# State — adeanugerah89

> State kerja pribadimu. Satu file per orang. Cap ~100 baris. Selesai → rotasi ke archive/.

## Now

- **Objective:** MOB-26 ✅ (baris A22). `debugLogging` sudah tidak dead config lagi.
- **Active feature:** (tidak ada)
- **Status:** **22 dari 24 baris ✅.** Tersisa **A13 🟠** dan **A19 🟠** — dua-duanya nol
  pekerjaan engineering. **Tidak ada lagi yang bisa saya kerjakan tanpa input dari luar.**
- **Last verify:** `HARNESS_VERIFY: PASS (all)` — build+test+lint+budget+publish · 337 unit test ·
  9/9 device test · `PERF_BUDGET used=23%`.

## Next step

**Semua pekerjaan engineering selesai.** Yang tersisa butuh Anda:

1. **A13** — URL + kredensial Maven internal → satu perintah, lihat `RELEASING.md`.
2. **A19** — URL + CI token symbol (masukkan sebagai repo secret `APM_SYMBOLS_URL` /
   `APM_CI_TOKEN`; step CI-nya sudah ada), **dan keputusan tim backend** soal kontrak upload
   symbol **native** — `01` §8 hanya menyebut `mapping.txt`, sementara sejak A9b crash native
   punya build id dan bisa disimbolikasi.

**Blokir rilis yang masih berdiri, dan ini yang paling penting:**
manual checklist **no. 15 — jalur upload belum pernah berjalan di device**. WorkManager melaporkan
`Constraints not met` di kedua emulator uji, jadi `SyncWorker` tidak pernah dieksekusi di luar unit
test. Rantai peek → envelope → HTTP → hapus-setelah-2xx belum pernah dijalankan sungguhan.

Rekam jejak proyek ini jelas soal itu: **empat cacat hanya terlihat setelah dijalankan di device**
(enkripsi gagal total, ANR tidak terdeteksi, `consumer-rules.pro` kosong, regresi cold start), dan
semuanya lolos dari ratusan unit test hijau. Jalur upload adalah bagian besar terakhir yang belum
pernah disentuh device. Butuh device/emulator berjaringan tervalidasi, atau ingest host sungguhan.

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

Sesi ini sudah dirotasi ke
[archive/sessions/2026-09-07-a13-a9b.md](../archive/sessions/2026-09-07-a13-a9b.md).

| File | Change | Why |
|------|--------|-----|
| `sdk/…/queue/LoggingEventQueue.kt` | [NEW] | Log per event; diletakkan DI DALAM scrubber — logcat bukan kanal privat |
| `sdk/…/internal/DebugMode.kt` | [NEW] | "Off di release" ditegakkan `FLAG_DEBUGGABLE` app host, bukan oleh config |
| `sdk/…/internal/ApmBootstrap.kt` | [EDIT] pasang logger di dalam scrubber | Urutan komposisi = syarat keamanan |
| 2 file test baru (12 test) | [NEW] | Termasuk assert bahwa nomor telepon/email tidak pernah muncul di log |
| `INTEGRATION.md`, `DATA-INVENTORY.md` | [EDIT] | Klaim "debugLogging tidak berfungsi" dikoreksi |
| `DATA-INVENTORY.md` | [NEW] 247 baris | SEC-21 + SEC-22; diturunkan dari kode, konstanta diverifikasi ulang |
| `INTEGRATION.md`, `README.md` | [EDIT] tautan + Data Safety | Tim adopter diarahkan ke dokumen yang benar |
| `FEATURES.md` | [EDIT] +A21 → ✅ | SEC-21/SEC-22 tidak pernah punya baris |
| `CONSTITUTION.md` | [APPEND] SEC-21/SEC-22 | Aturan: inventaris data diturunkan dari kode, batasnya dinyatakan |
| `INTEGRATION.md` | [NEW] 199 baris | 3 langkah manual, 2 pengungkapan di muka, keterbatasan apa adanya |
| `README.md` | [NEW] | Belum ada sama sekali sebelumnya |
| `FEATURES.md` | [EDIT] +A20 (MOB-25) → ✅ | MOB-25 tidak pernah punya baris sendiri |
| `CONSTITUTION.md` | [APPEND] MOB-25 | Koreksi klaim `02` §3.4 soal "satu-satunya langkah" |
| `sdk/consumer-rules.pro` | [FIX P0] dari kosong → rules lengkap | Tanpa ini SDK diam total di setiap release build adopter |
| `app/build.gradle.kts` | [EDIT] R8 ON permanen + signing debug | Library yang tak pernah dibangun di bawah R8 tak pernah diuji seperti cara dipakai |
| `gradle/scripts/symbol-upload.gradle.kts` | [NEW] | `POST /v1/symbols` multipart; gagal = build gagal (`01` §8) |
| `.github/workflows/ci.yml` | [EDIT] +step symbol upload | Gate rilis di CI |
| `RELEASING.md` | [EDIT] +bagian Symbols | Termasuk: mapping.txt yang penting milik app adopter, bukan repo ini |
| `sdk/…/tombstones/real-device-api34-genuine-fault.txt` | [NEW] fixture ke-4 | Dari device fisik user — inilah yang menghabiskan Opsi A |
| `sdk/…/TombstoneParserTest.kt` | [EDIT] +4 test (total 23) | Frame device fisik, dan bahwa keempat capture menunjukkan kegagalan dumper yang sama |
| `sdk/…/internal/ApmBootstrap.kt` | [FIX] `Log.w` saat start gagal total | SDK monitoring yang gagal start tanpa jejak tidak bisa didiagnosis |
| `app/…/MainActivity.kt` | [FIX] status dibaca ulang 1,5 s setelah resume | A18 bikin start asinkron → status basi `started:false` sempat menyesatkan diagnosis |
| `CONSTITUTION.md` | [APPEND] A9b Opsi A | Tabel bukti 4 capture + 2 cacat yang ketahuan karena uji di device fisik |
| `FEATURES.md` | [EDIT] A9b diperbarui | Opsi tinggal B atau C |
