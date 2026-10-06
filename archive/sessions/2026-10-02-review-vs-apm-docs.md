# Sesi 2026-09-30 → 2026-10-02 — review vs apm-docs, A27/A30/A31

Verifikasi SDK terhadap apm-docs, sinkronisasi `docs/`, dan tiga feature: **A30** (MOB-03, ✅), **A27** (breadcrumbs di crash JVM, 🟠 — native + device tersisa), **A31** (`max_batch`/`upload_interval_s`, ✅). Baris baru A29, A32, A33, A34 dicatat di `FEATURES.md`. Hasil akhir: `HARNESS_VERIFY: PASS (all)`, 362 unit test.

## Changes

| File | Change | Why |
|------|--------|-----|
| `docs/00-Overview.md`, `docs/01-Kontrak-Data-API.md`, `docs/02-Mobile-SDK.md` | Disalin dari `apm-docs` (HEAD `5897de0`) | `docs/` tertinggal dari sumber kanonis; spec menang (CONSTITUTION) |
| `FEATURES.md` | Baris baru A27, A28 + baris Phase boundary "Spec drift" | Dua gap SDK vs kontrak; di luar scope, jadi baris baru bukan perbaikan langsung |
| `state/adeanugerah89.md` | Next step + Changes diperbarui | Handoff sesi review |
| `sdk/.../network/FailureCategory.kt` | Mapper mengenali `SSLPeerUnverifiedException` berpesan `Certificate pinning failure!` → `ssl_pinning_rejected`; parameter `isPinningRejection` dihapus | A30 / MOB-03: flag lama tidak pernah di-set siapa pun |
| `sdk/.../network/ApmEventListener.kt` | `Timing.isPinningRejection` dan pemakaiannya dihapus | Kode mati yang mengklaim mekanisme yang tidak ada |
| `sdk/src/test/.../FailureCategoryMapperTest.kt` | Test "pinning takes priority" (memakai flag mati) diganti 2 test: pesan OkHttp, hostname mismatch tetap `ssl_certificate` | Test lama tidak membuktikan kegagalan nyata |
| `sdk/src/test/.../PinningRejectionTest.kt` (baru) + `test/resources/tls/localhost-test.p12` | 3 test handshake TLS nyata (pin salah, pin benar, cert tidak tepercaya) + fixture keystore uji | Bukti ujung-ke-ujung; kunci fixture sekali pakai, bukan rahasia |
| `FEATURES.md` | A29–A33 ditambah; A30 ✅ + evidence; catatan A3 dikoreksi | Hasil verifikasi vs docs |
| `sdk/.../breadcrumb/BreadcrumbRingBuffer.kt` | + `putBreadcrumbSnapshot()` (`breadcrumbs` + `breadcrumbs_dropped:"0"`) | A27: satu definisi untuk `error` dan `crash` |
| `sdk/.../crash/JvmCrashHandler.kt`, `internal/ApmBootstrap.kt` | `breadcrumbsProvider` baru, dipasang ke `breadcrumbs::snapshot`; snapshot yang gagal tidak menggagalkan laporan crash | A27 / MOB-13 |
| `sdk/.../manual/ManualApi.kt` | Memakai helper bersama | `error` kini juga menulis `breadcrumbs_dropped` |
| `sdk/.../crash/NativeCrashReporter.kt` | Hanya komentar: kenapa native sengaja tanpa breadcrumbs | Cegah "perbaikan" yang menyesatkan |
| `JvmCrashHandlerTest` +5, `ManualApiTest` +1, `ApmPublicApiTest` +1 | Termasuk crash lewat graph `APM.start()` dan scrubbing nyata | Bukti ujung-ke-ujung + kontrol negatif |
| `config/RemoteConfigProvider.kt` | `hasServerValue` + `uploadIntervalMs(hostDefault)` | A31: server menang setelah bicara; sebelumnya nilai host |
| `sync/SyncEngine.kt` | `maxBatchProvider` (hanya menurunkan, clamp 1..200) + `currentBatchSize` | A31 / `01` §7 maks 200 per request |
| `sync/SyncWorker.kt`, `internal/ApmRuntime.kt`, `internal/ApmBootstrap.kt`, `ApmConfig.kt` | Interval jadi `() -> Long` hidup; dibaca SETELAH refresh config; wiring + komentar lama dikoreksi | A31: nilai server berlaku satu siklus terlambat sebelumnya |
| `RemoteConfigProviderTest` +4, `SyncEngineTest` +5, `SyncWorkerRefreshOrderTest` (baru), `ApmPublicApiTest` +1 | Termasuk graph `APM.start()` dengan cache config | Dua kontrol negatif dijalankan |
| `sdk/.../config/RemoteConfig.kt`, `RemoteConfigProvider.kt` | `upload_interval_s` `Long` → `Double`; ms = `(s*1000).toLong()` | Cross-check backend: kolom `DOUBLE PRECISION`; `45.5` menggagalkan decode dan membuang seluruh config termasuk kill switch |
| `RemoteConfigFetcherTest` +1, `RemoteConfigProviderTest` +1, literal Long→Double di 5 test | Payload berbentuk keluaran Go | Bukti decode |
| `sdk/.../crash/TombstoneTimestamp.kt` (baru), `internal/Clock.kt` (+`iso8601(millis)`), `NativeCrashReporter.kt` | Konversi timestamp tombstone → ISO-8601 UTC; cadangan `ApplicationExitInfo.timestamp` | A35: nilai asli `2026-09-09 11:14:38.641294490+0700` bukan RFC3339, backend menyimpan `ts_client` NULL |
| `TombstoneTimestampTest` (7), `NativeCrashReporterTest` (2, baru) | Termasuk fixture tombstone asli lewat shadow `ApplicationExitInfo` | `NativeCrashReporter` sebelumnya tanpa test sama sekali |
| `FEATURES.md` | A35 ✅; A28/A32/A31/A26/A34 diperbarui dengan jawaban backend; baris baru A36, A37 | Hasil cross-check `apm-backend`, `apm-ingest`, `apm-backoffice` |
| `sdk/.../stability/ColdStartTracker.kt` | Tipe event cold start `performance` → `lifecycle` (+ KDoc alasan) | A32: ikut iOS dan korpus parity backend; tak ada konsumen yang membedakan |
| `ColdStartTrackerTest` (rename, +1 test wire) | Memeriksa JSON hasil serialisasi, bukan hanya enum | Assertion lama hanya melihat enum |
| `DATA-INVENTORY.md` | Bagian `lifecycle`/`performance`/`termination` → hanya `cold_start` yang dikirim | Klaim `foreground`/`background` sebagai event tidak benar; dokumen harus diturunkan dari kode |
| `sdk/.../network/TransportError.kt` (baru), `ApmEventListener.kt` | `error_domain` = nama kelas exception, `error_code` = errno asli atau 0, `underlying_*` dari cause; `tls_version` (dibaca di `connectionAcquired`) + `reused_connection` di event `network` | A33: sebelumnya konstanta `java.io.IOException` / `-1` |
| `TransportErrorTest` (9), `NetworkEventAttrsTest` (7), `LocalTlsServer` (helper), `PinningRejectionTest` (refaktor ke helper) | Handshake TLS, DNS, koneksi ditolak, dan pooling asli | Bukti + 2 kontrol negatif |
| `DATA-INVENTORY.md` | Field network baru; `breadcrumbs_dropped` dan breadcrumb di crash JVM | Dokumen harus diturunkan dari kode |
| `FEATURES.md` | A33 ✅; baris baru A38 | `req_headers` iOS tak berdokumen |
| `core/Event.kt`, `queue/EventCodec.kt` | `Event.sessionId` (`@Transient`); `JsonEventCodec` menulis `{"event","session_id"}`, file lama tetap terbaca | A28: backend mengisi `session_id` dari envelope; event harus ingat sesinya sendiri, tanpa mengubah kawat |
| `queue/SessionStampingEventQueue.kt` (baru), `core/SessionManager.kt` (+`onSessionStarted`) | Stempel saat capture di thread pemanggil; sesi baru diumumkan agar bisa disimpan | A28 |
| `sync/SyncEngine.kt` | Satu envelope per run berurutan dengan sesi sama | A28 |
| `crash/NativeCrashReporter.kt`, `internal/ApmBootstrap.kt` | `crashedSessionId` untuk record exit terbaru; termination ikut sesi sebelumnya; session id disimpan di prefs | A28 |
| `SessionAttributionTest` (8), `EventCodecTest` +4, `SessionManagerTest` +3, `SessionStampingEventQueueTest` (3), `NativeCrashReporterTest` +3, `ApmPublicApiTest` +4 | Termasuk 2 tes ujung-ke-ujung disk+stempel+engine dan 4 kontrol negatif | Bukti |
| `CONSTITUTION.md`, `DATA-INVENTORY.md`, `FEATURES.md` | Keputusan bertanggal A28; session_id tersimpan di prefs; A28 ✅, baris baru A39 | Keputusan mengikat + dokumen legal-facing |
| `sdk/.../internal/SdkStorage.kt` (baru), `ApmBootstrap.kt`, `DiskEventQueue.kt` (KDoc) | Queue + cache config di `noBackupFilesDir`; `migrateLegacy` dari `filesDir` sebelum dibuka | A29 / SEC-07: aturan backup lama salah path, tak dirujuk, dan bergantung pada host |
| `res/xml/apm_backup_rules.xml` | Jadi referensi opsional untuk file prefs | Aturan lama tak mengenai apa pun |
| `SdkStorageTest` (8), `ApmPublicApiTest` +1 (+ path di `ApmBootstrapTest`, `ResourceBudgetTest`, `MainActivity` verification app) | Termasuk update dari build lama lewat graph + 2 kontrol negatif | Bukti |
| `DATA-INVENTORY.md`, `INTEGRATION.md`, `TESTING-ON-DEVICE.md`, `CONSTITUTION.md`, `FEATURES.md` | Klaim backup dikoreksi (dan dipersempit ke yang tertulis di dokumentasi Android); bagian *Backups*; keputusan A29; A29 🟠, baris A40, checklist 19; **perbaikan baris A32 yang yatim akibat bug sisip saya di A28** | Dokumen legal-facing harus akurat |
| `sdk/.../core/SdkIdentity.kt` (baru), `ApmBootstrap.kt`, `Envelope.kt` (komentar), `SdkIdentityTest` (2) | `sdk.name` dan header `X-APM-Sdk` dari satu konstanta; nilainya dikunci test | A36: keputusan tetap `apm-android-sdk`; sebelumnya dua literal terpisah |
| `CONSTITUTION.md`, `FEATURES.md` | Keputusan A36; baris A36 🟠 dengan daftar edit di repo lain | Tabel `LATEST_SDK_VERSIONS` di `apm-ingest` belum mengenal nama ini |
| `sdk/.../internal/NoBackupPrefs.kt` (baru), `SdkStorage.openState`, `ApmBootstrap.kt`, `InstallIdProvider.kt` (KDoc) | State SDK di `noBackupFilesDir/apm-state.json` (implementasi `SharedPreferences`); migrasi dari `shared_prefs` hanya pada update asli, file lama selalu dikosongkan | A40: `install_id` ikut backup/restore dan bisa ada di dua device |
| `sdk/src/main/res/xml/apm_backup_rules.xml` | Dihapus | Tidak ada lagi yang perlu dikecualikan host |
| `NoBackupPrefsTest` (12), `SdkStorageTest` +7, `ApmPublicApiTest` +3 (+3 test A28 menyemai lokasi baru) | 3 kontrol negatif | Bukti |
| `INTEGRATION.md` (*Backups* ditulis ulang), `DATA-INVENTORY.md`, `CONSTITUTION.md`, `FEATURES.md` | Klaim `install_id` reset saat reinstall benar lagi; keputusan A40; A40 🟠, checklist 20 | Dokumen legal-facing |
| `core/Event.kt` (+`appVersion/appBuild/osVersion`, `originKey`, `AppStamp`), `queue/EventCodec.kt`, `queue/SessionStampingEventQueue.kt`, `sync/SyncEngine.kt`, `core/DeviceContextBuilder.kt` | Versi app/build/OS dicatat saat capture, dipersist di disk, tidak di kawat; envelope dipecah per run asal | A39: crash native setelah update tercatat di versi baru |
| `crash/NativeCrashReporter.kt`, `internal/ApmBootstrap.kt` | `crashedApp`; versi berjalan dipersist tiap start dan dibaca sebelum ditimpa | A39 |
| `EventCodecTest` +3, `SessionStampingEventQueueTest` +5, `SessionAttributionTest` +6, `NativeCrashReporterTest` +2, `ApmPublicApiTest` +2 | 5 kontrol negatif | Bukti |
| `CONSTITUTION.md`, `DATA-INVENTORY.md`, `FEATURES.md` | Keputusan A39 (+ alasan `user_id` dikecualikan); A39 🟠 dengan opsi (a)/(b)/(c) | Keputusan privasi milik pemilik |
| `core/UserIdProvider.kt` (ditulis ulang), `core/Event.kt` (+`identityEpoch`), `queue/EventCodec.kt`, `queue/SessionStampingEventQueue.kt`, `sync/SyncEngine.kt` (+`identityFor`, `contextFor`) | Epoch identitas dicatat saat capture; `user_id` dipilih per run saat upload; identitas host disimpan satu kali (Keystore) | A39 `user_id` + A41 |
| `APM.kt`, `manual/ManualApi.kt` | `APM.clearUser()` (API publik baru) | Tidak ada cara menyatakan logout |
| `internal/ApmBootstrap.kt`, `crash/NativeCrashReporter.kt`, `internal/NoBackupPrefs.kt` | Satu cipher untuk queue dan identitas; `crashedIdentityEpoch` parameter wajib; `awaitPendingWrites` | Wiring; flakiness lintas-test ditemukan kontrol negatif |
| `UserIdentityEpochTest` (17), `SessionAttributionTest` +6, `ApmPublicApiTest` +5, `EventCodecTest` +1, `SessionStampingEventQueueTest` +1, `NativeCrashReporterTest` +1, `IdentityKeystoreInstrumentedTest` (3, belum dijalankan) | 3× suite bersih = 496 | Bukti |
| `INTEGRATION.md`, `DATA-INVENTORY.md`, `CONSTITUTION.md`, `FEATURES.md` | `setUser` bertahan restart + `clearUser()`; user_id kini tersimpan di device (satu nilai); keputusan; A39 🟠, A41 ✅, checklist 21 | Dokumen legal-facing |
| `NetworkEventAttrsTest` +2 | Rahasia (Authorization, Cookie, query, header kustom) tak pernah muncul di event, sukses maupun gagal; 2 kontrol negatif | A38: Android tak menangkap header/query; dikunci |
| `DATA-INVENTORY.md`, `CONSTITUTION.md`, `FEATURES.md` | Klaim "wire identik" dikoreksi dengan daftar perbedaan; keputusan A38; A38 ✅ | iOS mengirim `req_headers`/`res_headers`/nama query yang tak dibaca siapa pun |
| `core/UserIdProvider.kt` (ditulis ulang), `config/RemoteConfigFetcher.kt`, `config/RemoteConfigProvider.kt`, `internal/SdkStorage.kt`, `internal/NoBackupPrefs.kt`, `internal/ApmBootstrap.kt`, `queue/EventCodec.kt`, `.github/workflows/ci.yml`, `RELEASING.md` | 10 perbaikan temuan code-review: epoch acak + tak dikenal=anonim, enkripsi fail-closed, persistensi off-thread, fallback pin dibatasi, migrasi idempoten, tulis state diserialkan, marker setelah start sukses, codec sekali-parse, token CI per-langkah, definisi API yang didukung | Review kode 2026-10-05 |
| `UserIdentityEpochTest` (24), `RemoteConfigFetcherTest` +5, `RemoteConfigProviderTest` +3, `SdkStorageTest` +4, `NoBackupPrefsTest` +4, `EventCodecTest` +3, `ApmBootstrapMarkersTest` (4, baru) | Kontrol negatif tiap perbaikan; kontrol 6 baru gagal 4/4 setelah test diperkuat | 528 test, 2× bersih |
