# Session 2026-09-11 · Pengujian di emulator — blokir rilis terakhir tutup

- **By:** adeanugerah89
- **Verify at close:** `HARNESS_VERIFY: PASS (all)` — 340 unit test; envelope sungguhan diterima
  dari device
- **Hasil:** checklist device 2→8 pass; **A24 ✅** (dua cacat P0); **A26 ⬜** dibuka

## Changes

Sesi ini sudah dirotasi ke
[archive/sessions/2026-09-07-a13-a9b.md](../archive/sessions/2026-09-07-a13-a9b.md).

| File | Change | Why |
|------|--------|-----|
| `sdk/…/internal/ApmBootstrap.kt` | [FIX P0] panggil `remoteConfig.refresh()` di thread latar | Sebelumnya tidak pernah dipanggil → MOB-20 tidak terpenuhi |
| `sdk/…/sync/SyncWorker.kt` | [FIX P0] refresh config tiap siklus sync | Tanpa ini kill switch cuma setelan saat peluncuran |
| `sdk/…/config/RemoteConfigFetcher.kt` | [FIX P0] +`fallbackClient` tanpa pin | Kill switch pinning tidak bisa dijangkau saat pin rusak |
| `sdk/…/config/RemoteConfigFetcherTest.kt` | [NEW] 3 test | Fallback dipakai saat gagal, tidak dipakai saat sukses |
| `app/src/debug/` (manifest, network config, CA uji) | [NEW] | Debug-only; agar jalur upload bisa diuji tanpa melanggar SEC-12 |
| `TESTING-ON-DEVICE.md`, `tools/device-testing/` | [NEW] | Harness agar checklist device bisa diulang |
| `FEATURES.md` | [EDIT] +A24 ✅, +A26 ⬜; 5 item checklist device jadi ☑ | Hasil sesi ini |
| `.github/workflows/ci.yml` | [FIX] `env` ke level job + step peringatan | `if:` step tidak melihat `env:` step → gate tidak akan pernah jalan |
| `FEATURES.md` | [EDIT] baris diurutkan A1…A22; tabel fase diperbarui | Urutan acak setelah banyak penyisipan; fase masih menyebut A11–A17 |
| `CONSTITUTION.md` | [APPEND] A19 CI bug | Pelajaran ketiga: mekanisme yang belum pernah terlihat jalan jangan dianggap bekerja |
| **RILIS** `apm-android-sdk:0.1.0` | [PUBLISHED] ke GitHub Packages | Terverifikasi bisa ditarik konsumen |
| `sdk/build.gradle.kts` | [EDIT] `publishSetting()` baca `local.properties`; `verifyPublication` dukung SNAPSHOT | Nama file SNAPSHOT diberi timestamp Maven |
| `~/.gradle/init.d/apm-credentials.gradle.kts` | [NEW, di luar repo] | Baca token dari macOS Keychain |
| `gradle.properties` | [FIX] nilai kredensial dikosongkan | Sempat memuat token di repo publik |
| `RELEASING.md`, `INTEGRATION.md` | [EDIT] +GitHub Packages | Sisi publisher & konsumen, termasuk friksi read-auth |
| `CONSTITUTION.md` | [APPEND] A13 + insiden kredensial | Aturan: periksa keberadaan rahasia, jangan isinya |
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

## Kenapa sesi ini penting

Blokir rilis "jalur upload belum pernah berjalan di device" berdiri sejak A16. Dua percobaan
sebelumnya gagal dan disimpulkan sebagai keterbatasan emulator. Ternyata benar — emulatornya tidak
punya jaringan tervalidasi — tapi begitu jalurnya benar-benar dijalankan, **dua cacat P0 langsung
muncul**, dan keduanya membuat fitur ber-✅ tidak berfungsi di lapangan.

Yang paling perlu diingat: `RemoteConfigProvider.refresh()` punya enam unit test dan **nol
pemanggil**. Unit test menguji komponen; ia tidak menanyakan apakah komponen itu tersambung.
