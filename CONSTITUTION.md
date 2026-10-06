# Constitution — APMKit Android SDK

> **Binding.** File ini memiliki semua aturan di proyek ini. `AGENTS.md` menjelaskan *cara bekerja*;
> file ini mendefinisikan *apa yang selalu benar*. Saat konflik, file ini menang. Saat file ini
> dan `docs/*.md` (PRD/spec) tidak sepakat, **spec menang** — amend file ini untuk mengikutinya,
> jangan diam-diam ikuti aturan lama.
> Tidak pernah diarsipkan, selalu dalam konteks. Mengubah aturan adalah amandemen disengaja — beri tanggal.

## Invariants — architecture

- **Pipeline order bersifat tetap dan tidak pernah dibalik:** Capture → Scrub → Disk → Sync.
  Scrubbing (redaksi PII) berjalan sebagai langkah **terakhir** sebelum setiap penulisan ke disk,
  termasuk untuk data yang dimasukkan lewat API manual (`logError`, `breadcrumb`). Tidak ada yang
  mencapai disk tanpa di-scrub.
- **Write-local-first:** setiap event ditulis ke disk secara durabel sebelum network call apa pun
  dicoba. Sync engine hanya membaca dari dan menghapus dari disk queue — tidak pernah menahan
  event di memori saja menunggu upload.
- **Event lokal hanya dihapus setelah response 2xx, DENGAN SATU PENGECUALIAN eksplisit: `400`**
  (payload malformed/schema tak dikenal — `01` §7). Response 2xx adalah jalur normal dan yang
  membuat at-least-once delivery berlaku; menghapus sebelum ACK di jalur itu kehilangan data di
  setiap koneksi terputus di tengah upload. `400` berbeda: backend bilang tegas payload itu **tidak
  akan pernah** diterima, jadi menyimpannya selamanya bukan melindungi data — itu bikin sync
  macet permanen. Semua kode lain (`401/403/413/429/5xx`/transport failure) **tidak** menghapus;
  data tetap di disk. (MOB-09, diklarifikasi 2026-09-07 — lihat Decisions)
- **Uploader menggunakan OkHttp client yang terpisah dan tidak terinstrumentasi** dari satu yang
  diekspos ke host app, dan ingest host dikecualikan dari capture. Menangkap traffic upload SDK
  sendiri menciptakan loop instrumentasi tak berujung. (MOB-10)
- **`user_id` ditransportkan mentah, tidak pernah di-hash di client.** Hashing ke `user_ref`
  adalah tugas backend (BE-21) — satu-satunya kewajiban SDK adalah nilai mentah menempati slot
  `user_id` di envelope dan tidak pernah bocor ke breadcrumb, log, atau field lain.

## Invariants — platform

- **Min SDK: Android API 24 (Android 7.0).** Periksa ketersediaan API sebelum memakai API yang
  lebih baru. Gunakan `@RequiresApi` dan conditional checks di mana diperlukan. (Diputuskan
  2026-09-03: build prompt mensyaratkan 24, bukan 23 seperti harness init pertama.)
- **Ini adalah Android library module `sdk` (folder `sdk/`, Gradle path `:sdk`), bukan app.**
  Build via `./gradlew :sdk:assembleRelease` dan `./gradlew :sdk:test`. Tidak ada APK yang
  di-ship; artefak adalah AAR untuk Maven internal. Module `app/` di repo ini adalah
  verification app (analog dengan feat-016 iOS) — bukan SDK-nya. Root project (repo/folder
  terluar) tetap bernama `apm-android-sdk` — lihat keputusan 2026-09-07 di bawah untuk alasan
  module folder direname dari `apm-android-sdk/` menjadi `sdk/`. **Ini terpisah dari wire value
  `sdk.name = "apm-android-sdk"`** di envelope JSON, yang tidak berubah.
- **Gunakan OkHttp `EventListener`, bukan hanya `Interceptor`, untuk network observability.**
  `EventListener` mengekspos `secureConnectStart`/`secureConnectEnd` dan `callFailed`, sehingga
  fase kegagalan (DNS/TCP/TLS/TTFB) bisa diketahui. `Interceptor` saja tidak cukup. (MOB-01, MOB-02)
- **Screen tracking bersifat otomatis via `ActivityLifecycleCallbacks`** — tidak perlu integrasi
  manual dari host app untuk screen breadcrumb, berbeda dengan iOS yang host-invoked. Output-nya
  (event breadcrumb dengan `category: navigation`) harus identik dengan iOS. (MOB-12)
- **Dependensi minimal by design:** Kotlin stdlib + coroutines; OkHttp; WorkManager; xCrash (Phase 2
  crash only). Tidak ada dependensi lain tanpa keputusan bertanggal baru di sini. (Diputuskan
  2026-09-03: xCrash menggantikan ACRA yang disebut versi harness init pertama — build prompt
  explicit.)
- **Tidak ada kode eksekusi dinamis.** Remote config (Fase 2) hanya boleh mengaktifkan/
  menonaktifkan flag yang telah didefinisikan; tidak pernah mengubah perilaku executable. (SEC-20)

## Prohibitions — code

- Tidak ada operasi I/O blocking di main thread — seluruh disk I/O dan network berjalan di
  background thread/coroutine.
- Tidak ada `!!` di Kotlin tanpa komentar yang membuktikan non-null di lokasi tersebut.
- **SDK tidak boleh crash atau throw ke host app — ini adalah aturan #1.** Setiap entry point
  publik dan setiap callback internal wajib defensif: catch, hitung via self-health counters
  (MOB-27), dan gagal secara senyap. Satu-satunya pengecualian yang disengaja adalah crash
  handler itu sendiri (Fase 2), yang by definition berjalan saat crash.
- Header capture menggunakan **allowlist** (`Content-Type`, `Content-Length`, `Accept`,
  `User-Agent`) — bukan blocklist. `Authorization`/`Cookie`/header kustom tidak pernah direkam.
  (SEC-02)
- Request/response body tidak pernah ditangkap; tidak ada opt-in untuk itu. (SEC-04)
- Jangan pakai API yang membutuhkan permission sensitif untuk sinyal integrity. IMEI/serial
  Android sudah dibatasi ketat sejak API 29 — cukup build props & file check. (MOB-29, MOB-30)
- Tidak ada secrets di `build.gradle` atau file properties yang di-commit.
- `logError` wajib mengisi `source_file` / `source_function` / `source_line` dari call site
  secara otomatis. Di Android gunakan `Thread.currentThread().stackTrace` atau anotasi
  `@CallerSensitive` pattern — bukan string literal hardcoded. (MOB-11b)

## Prohibitions — process

- **Never auto-commit.** Update file, laporkan apa yang berubah, biarkan user memutuskan.
- Jangan tandai feature ✅ tanpa evidence yang tercatat di `FEATURES.md`.
- Satu feature aktif sekaligus per orang (lihat `state/<name>.md`). Ide di luar scope jadi baris
  `FEATURES.md` baru, bukan drive-by edits.
- **Build order (F1→F10 di `FEATURES.md`) wajib diikuti, bukan saran.** Setiap feature adalah
  branch/PR sendiri: implementasi, tetap bisa dikompilasi, tambah unit test, berhenti untuk
  review. Jangan mulai feature berikutnya sebelum yang sekarang selesai di-review.

## Git

- Base branch untuk PR: `main`. Feature branch: `feature/<topic>/<detail>`.
- **Commit message diawali dengan feature ID:** `feat-001: <ringkasan>`.
  Ini memungkinkan `git log --grep="<id>"` mengkorroborasi kolom `By` di `FEATURES.md`.
- **State adalah satu file per orang:** `state/<git config user.name>.md`. Kamu hanya menulis
  file milikmu sendiri; tidak ada orang lain yang pernah menyentuhnya. Ini membuat merge,
  rebase, dan cherry-pick conflict-free by construction.
- **Visibilitas lintas orang ada di `FEATURES.md`, bukan di state file.** `FEATURES.md`
  merge normal dan menampilkan setiap feature yang sedang berjalan beserta pemilik `By`-nya.
  State file hanya menjawab "apa yang sedang *saya* kerjakan sekarang."
- Attribution (`By` columns) berasal dari `git config user.name` di mesin yang menjalankan
  sesi — bukan dari agen.

---

## Decisions

_Entri bertanggal. Tambahkan setiap kali pilihan yang bisa diperdebatkan diselesaikan —
sertakan alasannya, agar bisa dibuka kembali nanti tanpa mengulang analisis. Amend dengan
menambahkan entri baru bertanggal yang menggantikan yang lama; jangan edit riwayat secara diam-diam._

### 2026-09-02 · Android SDK repo scaffold dengan harness dari awal

Repo `apm-android-sdk` dibuat untuk paritas Android dari iOS SDK (`apm-ios-sdk`). PRD yang
berlaku adalah `docs/02-Mobile-SDK.md` (shared iOS & Android spec) dan `docs/01-Kontrak-Data-API.md`
(wire contract). Feature F1 adalah scaffold project Android (Gradle library module) karena belum
ada source code sama sekali. Build order F1→F10 mengikuti Fase 1 spec, ditetapkan di sini agar
agent tidak mulai dari feature yang dependensinya belum ada.

### 2026-09-03 · Koreksi minSdk 23→24 dan module name sdk→apm-android-sdk

Build prompt APMKit Android SDK (dikirim 2026-09-03) mensyaratkan `minSdk 24` secara eksplisit.
Harness init sebelumnya menulis `minSdk 23` — itu kesalahan di harness, bukan keputusan yang
disepakati. Dikoreksi ke 24. Konsekuensi: `ApplicationExitInfo` tersedia mulai API 30, bukan
semua device — API 24–29 tidak mendapat `termination` events (didokumentasikan di A9). Library
module bernama `apm-android-sdk` (bukan `sdk/`); module `app/` adalah verification app. Crash library
adalah xCrash (Phase 2), menggantikan ACRA yang disebutkan harness init pertama.

### 2026-09-07 · `sdk.name` wire value = `"apm-android-sdk"`

`FEATURES.md` baris A1 sempat menulis requirement `sdk.name="apmkit-android"` (mengikuti build
prompt asli dan konvensi iOS `"apmkit-ios"`), sementara kode yang sudah ditulis (`Envelope.kt`,
`EnvelopeSerializationTest.kt`) memakai `"apm-android-sdk"` — dua sumber saling bertentangan dan
ditandai sebagai blocker di state file. User mengonfirmasi: **tetap `"apm-android-sdk"`**
(mengikuti nama module, bukan konvensi `apmkit-<platform>` iOS). `FEATURES.md` A1 dikoreksi agar
konsisten dengan kode dan test yang sudah ada.

### 2026-09-07 · Module folder direname `apm-android-sdk/` → `sdk/`

Root repo dan library module sempat sama-sama bernama `apm-android-sdk`, menghasilkan struktur
`apm-android-sdk/apm-android-sdk/src/...` yang membingungkan (root project vs Gradle module tidak
bisa dibedakan sekilas). User meminta rename module folder jadi `sdk/` agar jelas. **Ini
menggantikan bagian penamaan module dari keputusan 2026-09-03** ("Library module bernama
`apm-android-sdk`, bukan `sdk/`") — bagian minSdk 24 dari keputusan itu tetap berlaku.

Yang berubah: folder `apm-android-sdk/` → `sdk/`; Gradle path `:apm-android-sdk` → `:sdk` di
`settings.gradle.kts` dan `verify.sh`. Root project name (`rootProject.name` di
`settings.gradle.kts`) **tetap** `apm-android-sdk` — itu nama repo, bukan nama module.

Yang **tidak** berubah: wire value `sdk.name = "apm-android-sdk"` di envelope JSON (keputusan
2026-09-07 di atas) — itu string yang dikirim ke backend, sepenuhnya independen dari nama folder
module Gradle. Kotlin package namespace (`edts.apm.android.sdk`) juga tidak berubah.

### 2026-09-07 · Klarifikasi MOB-09: `400` adalah pengecualian yang disengaja terhadap "hapus hanya setelah 2xx"

Menulis A5 (Sync Engine) menemukan pertentangan: invariant di atas menulis "hanya dihapus setelah
2xx" secara mutlak, sementara `01` §7 (kontrak response, sumber yang mengikat menurut aturan
"spec menang" di pembuka file ini) eksplisit menyuruh **membuang** batch pada `400`
("jangan retry selamanya"). Ini bukan pertentangan sungguhan — `400` berarti backend sudah
memastikan payload itu punya masalah skema yang tidak akan pernah lolos, jadi mempertahankannya
di disk selamanya bukan melindungi data, itu bikin queue macet permanen tanpa progres. Invariant
di atas dikoreksi menjadi eksplisit: hapus pada 2xx (jalur normal) ATAU 400 (batch memang tidak
bisa diselamatkan); kode lain (`401/403/413/429/5xx`, transport failure) tidak pernah menghapus.
Diimplementasikan di `sync/SyncAction.kt` (`DeleteBatch` vs `DropBatch`, dua path terpisah menuju
`EventQueue.delete()`) dan diuji di `SyncActionTest`/`SyncEngineTest`.

### 2026-09-07 · D2 resolved: stack-walk untuk `logError` source location — jalankan apa adanya

Pertanyaan (FEATURES.md D2): biaya stack walk, cara skip SDK frame, keamanan path di release
build. User: **tidak masalah, jalankan saja** — tidak perlu pembatasan tambahan.

Alasan aman tanpa syarat lebih: `Thread.currentThread().stackTrace` di Android/JVM tidak
menghasilkan path absolut sama sekali — `StackTraceElement.getFileName()` selalu nama file
polos (mis. `"LoginActivity.kt"`), beda dari `#file` di Swift yang membocorkan path mesin build.
Jadi risiko yang membuat iOS mewajibkan `#fileID` (SEC — bocor username/direktori) **tidak ada**
di Android by construction; tidak perlu mekanisme setara `#fileID` secara eksplisit. Biaya
runtime: satu stack walk per `logError()` call (bukan hot path — dipanggil manual oleh developer
saat error, bukan per-event otomatis) — diterima tanpa budget check tambahan.

Diimplementasikan di `internal/CallSiteLocator.kt` — skip frame `edts.apm.android.sdk.*` dan
frame VM (`java.lang.Thread`, `dalvik.system`), fail-safe ke `CallSite.UNKNOWN` bila stack walk
gagal atau tidak ada caller eksternal yang teridentifikasi (CONSTITUTION rule #1).

### 2026-09-07 · D3 resolved: `is_emulator` cukup untuk filter noise `is_rooted` di AVD

Pertanyaan (FEATURES.md D3): AVD punya `su` + `test-keys` sehingga `is_rooted=true` di emulator
technically correct tapi noise di dashboard. User: **tidak masalah untuk kebutuhan filter** —
artinya `is_emulator` (MOB-29, sudah ada di schema `01` §2 sebagai field terpisah) itu sendiri
sudah cukup sebagai mekanisme filter; SDK **tidak** perlu menekan/menyesuaikan sinyal `is_rooted`
di emulator. Kedua flag tetap independen dan apa adanya — backend/dashboard yang menyaring
`is_rooted=true AND is_emulator=true` sebagai noise, bukan SDK yang berbohong soal root state.

Diimplementasikan di `integrity/EmulatorDetector.kt`, `integrity/RootDetector.kt`,
`integrity/DevModeDetector.kt`, dikomposisi di `integrity/DeviceIntegrityCollector.kt`.

### 2026-09-07 · D1 resolved: mapping `ApplicationExitInfo` → `termination_reason`, dan scope A9

D1: hanya `ApplicationExitInfo.REASON_LOW_MEMORY` (API 30+) yang punya padanan jelas ke enum
`termination_reason` (`01` §4.7). Tidak ada `REASON_*` Android untuk `cpu`/`thermal`/
`low_battery` secara langsung. User menyetujui proposal: `REASON_LOW_MEMORY` → `memory_pressure`;
`REASON_*` lain yang tidak cocok **tidak di-emit sama sekali** — mengikuti aturan yang sudah ada
di spec untuk membuang kategori `unexplained` (tidak ada nilai diagnostik, hanya derau).

Scope A9 tambahan yang disepakati: A9 sesi ini fokus ke jalur **Java/JVM crash**
(`Thread.UncaughtExceptionHandler`) — bukan native/xCrash. Dua alasan:
1. Wire schema `threads[].frames[]` (`01` §4.3.1) berbasis alamat memori native
   (`object_addr`/`instruction_addr`, `uuid` untuk symbolication) — cocok untuk crash native
   (xCrash) tapi tidak untuk frame Java (`StackTraceElement` tidak punya alamat memori sama
   sekali). Disepakati: untuk frame asal Java, `object_addr`/`instruction_addr` di-omit (null),
   `symbol_name`/`file`/`line` diisi **langsung saat capture** (bukan menunggu backend
   symbolication seperti alur native) karena Java tidak butuh symbolication terpisah.
   `binary_images` kosong untuk crash Java — tidak ada yang perlu disimbolkan.
2. Parsing native crash report xCrash (format tombstone) butuh sampel crash asli untuk
   divalidasi — tidak tersedia di lingkungan ini (tidak ada device/NDK crash nyata). Menebak
   formatnya berisiko menghasilkan data salah yang terlihat valid. xCrash native parsing
   dicatat sebagai baris terpisah di `FEATURES.md`, bukan ditebak sekarang.

### 2026-09-07 · A10: ambang ANR 5 detik, `is_fatal=false`, dan semantik kill switch

Tiga pilihan yang bisa diperdebatkan diselesaikan saat menulis A10. Dicatat di sini karena
ketiganya mengikat feature berikutnya (A11, A16), bukan cuma A10.

**1. Ambang ANR = 5000 ms, bukan 2000 ms.** `02` §3.5 MOB-18 menulis "Deteksi ANR (Android) /
hang main thread > 2 detik (iOS)". Angka 2 detik itu mengkualifikasi *hang detector iOS*, yang
tidak punya padanan ambang di level OS sehingga harus memilih angka sendiri. Android punya:
5 detik adalah ambang input-dispatch ANR milik platform. Memakai angka platform berarti setiap
ANR yang SDK laporkan adalah ANR yang OS juga akan sebut ANR — tidak ada kategori "ANR menurut
APM saja" yang harus dijelaskan ke tim aplikasi. Nilainya tetap konstruktor parameter
(`AnrWatchdog.thresholdMs`), jadi bisa diturunkan tanpa mengubah kode.

**2. ANR yang terdeteksi watchdog `is_fatal = false`.** Watchdog mengamati main thread yang
terblokir, bukan proses yang mati; aplikasi sangat sering pulih. Menandainya fatal akan mengisi
penyebut crash-free-session rate dengan sesi yang tidak pernah benar-benar berakhir. Konsekuensi:
ANR yang benar-benar berujung pada proses dibunuh akan muncul dua kali di backend — sekali sebagai
`crash_type: anr, is_fatal: false` dari watchdog, dan (bila OS melaporkannya) sekali lagi lewat
jalur `ApplicationExitInfo` A9. Itu disengaja: keduanya membawa informasi berbeda — yang pertama
membawa stack trace saat blokir, yang kedua membawa fakta bahwa prosesnya mati.

**3. Kill switch (`enabled: false`) menghentikan capture dan upload, tapi tidak menghapus apa pun.**
`GatedEventQueue` menolak event baru di ujung capture (sebelum scrub, jadi tidak ada biaya
serialisasi yang terbuang), dan `SyncEngine` mengembalikan `KillSwitched` tanpa memanggil uploader.
Event yang sudah ada di disk **tetap di disk**: itu data yang ditangkap saat capture masih sah,
dan invariant "hapus hanya setelah 2xx/400" tidak mengenal pengecualian "operator mematikan SDK".
Kalau switch dinyalakan lagi, backlog itu terkirim.

Turunan dari #3: event yang ditolak gate **tidak** dihitung sebagai `dropped` di `sdk.health`.
`dropped` dibaca sebagai peringatan integrasi (`04` §3.8) — "SDK kehilangan data yang ingin
disimpannya". Operator yang sengaja mematikan capture bukan kegagalan, dan menghitungnya akan
membuat setiap app yang di-kill terlihat rusak di dashboard.

Turunan lain: `RemoteConfigProvider` **fail-open** (default `enabled = true`) saat tidak ada cache
dan fetch gagal, tapi **tidak pernah** mengembalikan SDK yang sudah di-kill ke keadaan hidup karena
refresh gagal — nilai lama dipertahankan. Cache dibaca lebih dulu, sebelum fetch, supaya kill
switch bertahan melewati peluncuran tanpa jaringan. Kill switch yang lupa dirinya sendiri begitu
device offline bukan kill switch.

### 2026-09-07 · A11: TLS floor MODERN_TLS, dan SDK tidak memaksa network security config host

**1. `ConnectionSpec` = MODERN_TLS dengan `tlsVersions(TLS_1_3, TLS_1_2)`, bukan RESTRICTED_TLS.**
SEC-10 meminta "TLS 1.2+". `ConnectionSpec.RESTRICTED_TLS` memenuhi itu tapi sekaligus mempersempit
daftar cipher suite ke subset modern — lebih ketat dari yang diminta, dan itu persis mekanisme
mematikan-diri-sendiri yang jadi alasan SEC-11 diturunkan ke P2: ingest host yang cipher-nya belum
di-upgrade akan menghentikan telemetri seluruh app terpasang tanpa ada yang tahu. Dipilih MODERN_TLS
sebagai basis dengan versi TLS dipatok eksplisit ke 1.3/1.2. Tidak ada `ConnectionSpec.CLEARTEXT`
di daftar — ketiadaan itulah penegakannya: OkHttp menolak panggilan `http://` dengan
`UnknownServiceException`, bukan mencoba lalu gagal.

**2. SDK TIDAK menyetel `usesCleartextTraffic="false"` di manifest-nya sendiri.** Manifest merger
akan mendorong setelan itu ke seluruh app host dan mematikan setiap endpoint cleartext yang sah
milik tim aplikasi. Library monitoring yang memaksa kebijakan platform-wide akan dicabut dari
aplikasi, bukan membuat cleartext-nya diperbaiki. Yang di-ship: `res/xml/apm_network_security_config.xml`
sebagai **referensi** yang host app bisa tunjuk sendiri lewat `android:networkSecurityConfig`,
plus penjelasannya di dokumen integrasi (MOB-25). Koneksi milik SDK sendiri sudah TLS-1.2-floor
dan tidak bisa cleartext by construction — itu bagian SEC-10 yang benar-benar dalam kendali SDK.

**3. SEC-12 ditegakkan di dua lapis, sengaja redundan.** Lapis pertama: `TlsPolicy.isSecureUrl`
dicek di `IngestUploader.upload()` dan `RemoteConfigFetcher.fetch()` setiap panggilan, jadi URL
cleartext ditolak sebelum payload diserialisasi. Lapis kedua: connection spec client itu sendiri.
Lapis pertama ada supaya SEC-12 tetap berlaku kalau suatu saat composition root (A16) merakit
komponen ini dengan client yang tidak dibangun lewat `ApmHttpClientFactory`. Keduanya diuji
terpisah di `TlsFailClosedTest` — satu test sengaja membuka gate URL agar lapis client yang
terbukti, bukan lapis pertama yang menutupinya.

Konsekuensi yang diterima: kedua kelas itu punya parameter seam `isSecureUrl` yang defaultnya
`TlsPolicy::isSecureUrl`. Seam itu ada **hanya** supaya `IngestUploaderTest`/`RemoteConfigFetcherTest`
bisa menguji jalur HTTP sungguhan lewat MockWebServer (yang cleartext). Jangan pakai seam itu di
kode produksi.

### 2026-09-07 · A14: enkripsi ada di codec, kunci hilang berarti plaintext, dan `peek` sekarang membuang file rusak

**1. Enkripsi at-rest hidup di `EventCodec`, bukan sebagai decorator `EventQueue`.** KDoc
`EventQueue` sebelumnya menjanjikan A14 akan datang sebagai decorator yang membungkus interface
itu secara transparan — persis pola `ScrubbingEventQueue`/`GatedEventQueue`. Janji itu **salah**
dan sudah dikoreksi di kode: interface `EventQueue` berbicara dalam objek `Event`, sementara
enkripsi at-rest harus bekerja pada byte yang benar-benar mendarat di disk. Decorator di atas
interface itu tidak pernah melihat byte-nya. Karena itu enkripsi diletakkan di tempat serialisasi
sudah tinggal: `EventCodec` (`JsonEventCodec` / `EncryptedEventCodec`), yang disuntikkan ke
`DiskEventQueue`. Mekanika file — atomic rename, urutan FIFO, eviction kapasitas — identik apa pun
formatnya, jadi tetap ditulis sekali saja.

**2. Kunci tidak tersedia → antrean jalan terus dalam plaintext, bukan berhenti menangkap.**
`KeystoreKeyProvider` mengembalikan `null` pada setiap kegagalan, dan `null` berarti fallback ke
`JsonEventCodec`. Alasannya berlapis: SEC-08 adalah **P1**, sementara kehilangan seluruh telemetri
adalah kegagalan produk; data yang ditulis sudah melewati Scrubber (SEC-01/05b) sehingga bukan PII
mentah; dan file antrean sudah berada di internal storage yang dikecualikan dari auto-backup
(SEC-07) — enkripsi adalah lapis tambahan di atas itu, bukan satu-satunya pertahanan. SDK juga
tidak boleh jadi penyebab app mati (aturan #1), jadi melempar exception bukan opsi.

**3. Kunci Keystore sengaja TIDAK memakai `setUserAuthenticationRequired(true)`.** Antrean ditulis
dari background thread dan dikuras oleh WorkManager, keduanya rutin berjalan saat device terkunci.
Kunci yang menuntut unlock terkini akan menghentikan telemetri diam-diam justru untuk sesi yang
paling perlu dilihat.

**4. Format file berversi, dan file plaintext lama tetap dibaca.** Layout: `[0x01][IV 12 byte][ciphertext‖tag]`.
JSON polos selalu diawali `{` (0x7B) sehingga tidak pernah bisa tertukar dengan version byte —
`EncryptedEventCodec` membedakan keduanya dengan pasti, bukan menebak. Konsekuensi yang disengaja:
build ber-enkripsi **membaca** file plaintext yang ditulis build sebelumnya. Itu bukan pelemahan,
itu jalur upgrade — menolaknya berarti membuang satu antrean penuh telemetri asli di setiap update.
Tulisan selalu terenkripsi. Perubahan format berikutnya memakai 0x02, bukan migrasi.

**5. `peek()` sekarang MENGHAPUS file yang tidak bisa didekode; sebelumnya hanya melewatinya.**
Ini perubahan perilaku terhadap A2 dan disengaja. Melewati file rusak masih bisa ditolerir saat
semua file adalah JSON polos dan file tak terbaca berarti satu penulisan terpotong yang langka.
Enkripsi menciptakan cara agar **banyak file rusak sekaligus** — kunci Keystore yang diregenerasi
atau di-invalidasi membuat seluruh backlog tidak bisa didekripsi. Karena file yang dilewati tetap
menduduki kepala FIFO, `peek(maxCount)` akan terus mengembalikan kosong dan antrean macet permanen
di belakang data yang tidak akan pernah bisa dibaca build mana pun. Membuangnya menghabiskan byte
yang memang sudah hilang dan membuat antrean bergerak lagi; kehilangannya terlihat di
`drop_reasons.undecodable` (§2.3, MOB-27), bukan senyap. `peek` juga kini berjalan melewati file
buruk sampai batch-nya penuh, bukan membaca tepat `maxCount` file lalu mengembalikan sisanya.

**6. Pengecualian SEC-09 (crash report ditulis tanpa enkripsi) TIDAK berlaku untuk jalur crash
Android saat ini.** Pengecualian itu ada karena signal handler native tidak boleh mengalokasi
memori. Jalur crash JVM Android (A9) berjalan di konteks JVM biasa — sudah dinyatakan eksplisit di
KDoc `JvmCrashHandler` — jadi crash event melewati pipeline normal dan ikut terenkripsi seperti
event lain. Pengecualian SEC-09 baru berlaku saat A9b (xCrash native) mendarat.

**Batas pengujian yang jujur:** Robolectric tidak punya provider `AndroidKeyStore` sama sekali
(`KeyStoreException: AndroidKeyStore not found`), jadi `KeystoreKeyProvider` hanya bisa diuji pada
cabang fallback-nya. Pembuatan kunci dan ketahanannya melintasi relaunch tetap ☐ di manual
checklist device-only (no. 9). Cipher, codec, dan queue diuji penuh dengan kunci AES biasa.

### 2026-09-07 · A15: pin cadangan wajib secara tipe, kill switch per-call, dan `Call.Factory` menggantikan `OkHttpClient`

**1. "Pin cadangan wajib" ditegakkan oleh signature, bukan validasi.**
`CertificatePinningConfig.of(hostname, primary, backup, vararg additional)` menerima primary dan
backup sebagai **dua parameter wajib terpisah**. Konsekuensinya "pinning dengan satu pin" tidak
bisa diekspresikan sama sekali — tidak ada `require()` yang bisa lupa dipanggil, tidak ada jalur
konfigurasi yang melewatinya. Ini yang dimaksud `02` §6.3 "wajib menyertakan pin cadangan": rotasi
sertifikat server tidak boleh bisa menelantarkan seluruh app terpasang sekaligus. Dua pin yang
identik juga ditolak — itu cadangan hanya pada namanya.

`of()` mengembalikan `null` (bukan throw) untuk hostname kosong atau pin yang formatnya salah.
Host app yang membangunnya, dan SDK tidak boleh mematikan host (aturan #1); `null` turun ke default
— tanpa pinning, TLS floor A11 tetap berlaku. Bidang lama `ApmConfig.pinnedCertificateSha256` /
`backupPinnedCertificateSha256` (dua `List<String>` longgar, bisa diisi satu tanpa yang lain)
**dihapus** dan diganti `pinning: CertificatePinningConfig? = null`.

**2. Kill switch pinning bekerja dengan memilih antar dua client, dibaca per panggilan.**
`OkHttpClient` immutable dan `CertificatePinner`-nya ditetapkan saat build, jadi kill switch tidak
bisa bekerja dengan memutasi client. `ApmHttpClientFactory.createPinnedPair()` membangun keduanya
sekali dari satu base lewat `newBuilder()` — connection pool dan dispatcher-nya **sama**, jadi
berpindah cuma satu percabangan, bukan menegakkan HTTP stack kedua. `PinnedCallFactory` membaca
flag setiap `newCall`, sehingga refresh remote config berlaku di upload berikutnya. Itulah inti
requirement-nya: memulihkan pin yang tidak lagi cocok tidak boleh menuntut rilis app baru, karena
menunggu user update persis sama dengan blackout berminggu-minggu yang `02` §6.3 gambarkan.
Nama fitur untuk `disabled_features` adalah `pinning` (`RemoteConfig.Feature.PINNING`).

**3. Mematikan pinning TIDAK melonggarkan TLS.** Fallback-nya adalah client **unpinned yang tetap
membawa TLS floor A11** dan tetap tidak bisa cleartext. Kill switch melonggarkan pinning, bukan
keamanan transport — SEC-12 berlaku di kedua jalur, dan itu diuji eksplisit.

**4. `IngestUploader` dan `RemoteConfigFetcher` sekarang menerima `okhttp3.Call.Factory`, bukan
`OkHttpClient`.** Keduanya hanya memanggil `newCall()`, dan `OkHttpClient` sendiri mengimplementasi
`Call.Factory`, jadi ini perubahan tipe yang tidak menyentuh satu pun call site yang sudah ada —
tapi memungkinkan `PinnedCallFactory` disisipkan tanpa kelas-kelas itu tahu apa-apa soal pinning.
Kalau nanti butuh sesuatu dari `OkHttpClient` selain `newCall`, ubah kembali dengan sadar; jangan
diam-diam meng-cast.

**Batas pengujian yang jujur:** yang teruji adalah *konfigurasi* pinning — pin mana yang terpasang
di client mana, dan client mana yang dipakai saat switch hidup/mati. Bahwa OkHttp benar-benar
**menolak** sertifikat yang pin-nya salah adalah perilaku OkHttp, dan membuktikannya butuh TLS
server dengan sertifikat terkontrol (`okhttp-tls`/`HeldCertificate`) — dependency baru yang belum
diputuskan. Sampai itu ada, checklist manual no. 5 (reject pada pin salah, pass pada benar) adalah
satu-satunya bukti untuk bagian itu, dan tetap ☐.

### 2026-09-07 · A16: WorkManager tanpa `Configuration.Provider`, `ProcessLifecycleOwner` tanpa dependency baru, dan satu sumber kebenaran untuk "sudah start"

**1. `SyncWorker` dibangun oleh default factory WorkManager, bukan `SyncWorkerFactory`.**
Cara A5 menyuntik `SyncEngine` lewat `WorkerFactory` kustom menuntut **app host** mengimplementasi
`Configuration.Provider` di kelas `Application`-nya. Itu langkah integrasi wajib untuk setiap tim
adopter, bentrok dengan app yang sudah punya factory sendiri, dan bertentangan dengan MOB-25
(integrasi < 30 menit) serta klaim `02` §3.4 bahwa screen tracking adalah satu-satunya langkah yang
tidak nol-effort. Diganti: `SyncWorker` punya konstruktor `(Context, WorkerParameters)` asli lewat
`@JvmOverloads`, dan mengambil dependensinya dari `ApmRuntime` — object proses-wide yang diisi
`APM.start()`. Bentuk singleton bukan pilihan bebas; ia konsekuensi WorkManager yang meng-instansiasi
worker secara reflektif dan tidak bisa diberi argumen konstruktor. Parameter tetap injectable
supaya test tidak pernah menyentuh graph global. `SyncWorkerFactory` dipertahankan **khusus untuk
test**, tidak dipakai di produksi.

**2. Foreground/background diturunkan dari `ActivityLifecycleCallbacks`, bukan `ProcessLifecycleOwner`.**
`ProcessLifecycleOwner` ada di `androidx.lifecycle:lifecycle-process`, dependency yang belum ada di
`sdk/build.gradle.kts` dan tidak bisa ditambah tanpa keputusan bertanggal (CONSTITUTION: "dependensi
minimal by design"). `VolatileContextProvider` sudah menghitung activity yang started untuk mengisi
`app_state`, jadi transisi 0→1 dan 1→0 dari **counter yang sama** dipakai untuk menggerakkan
`SessionManager` dan `LifecycleBreadcrumbSource`. Satu counter berarti `app_state` di event dan
batas sesi tidak mungkin berselisih. Menghitung (bukan boolean on resume/pause) juga yang menjaga
`app_state` benar saat rotasi layar — rotasi men-stop activity lama **setelah** memulai yang baru.

**3. `APM.isStarted` bersumber hanya dari `ApmRuntime`.** Versi pertama menyimpan flag
`initialized` sendiri di `APM` — dua sumber kebenaran untuk satu fakta, dan keduanya bisa
berselisih: assembly yang gagal tapi terlanjur menandai SDK "initialized" membuat setiap panggilan
berikutnya jadi no-op permanen tanpa cara mengetahui sebabnya. Ketahuan dari test yang gagal, bukan
dari review. Sekarang `ApmRuntime.isStarted` satu-satunya penentu; start yang gagal boleh diulang,
start yang berhasil adalah no-op.

**4. `ApmConfig.configUrl` default diturunkan dari `ingestUrl`.** `/v1/ingest` → `/v1/config` pada
host yang sama — deployment normal. Bisa di-set eksplisit kalau kedua endpoint terpisah; jangan
membengkokkan `ingestUrl` supaya tebakannya cocok.

**5. `APM.networkEventListener()` adalah langkah manual, karena memang harus.** SDK tidak memiliki
`OkHttpClient` milik app host dan tidak bisa memasang dirinya ke sana (MOB-01/02). Ini langkah
integrasi kedua di samping screen tracking, dan harus ditulis menonjol di dokumen integrasi
(MOB-25), bukan sebagai catatan kaki. Listener yang dikembalikan sudah mengecualikan host ingest,
jadi MOB-10 tetap berlaku bahkan bila host mengarahkannya ke client yang juga bicara ke ingest.

**6. `integrity` di-snapshot sekali per sesi, bukan per event** (`01` §2.1). Probe-nya menyentuh
build props dan filesystem; menjalankannya ulang tiap event adalah biaya murni untuk nilai yang
tidak bisa berubah selama proses hidup.

**Batas pengujian yang jujur:** `ApmBootstrapTest`/`ApmPublicApiTest` berjalan di Robolectric, yang
**tidak punya `AndroidKeyStore`** — jadi setiap test A16 menjalankan jalur fallback plaintext
(A14), bukan jalur terenkripsi. Assembly versi terenkripsi hanya terbukti di device. Sama untuk
penjadwalan WorkManager: yang teruji adalah bahwa `start()` merakit graph dan menjadwalkan, bukan
bahwa worker benar-benar berjalan di device dengan constraint jaringan asli.

### 2026-09-07 · A12: budget performa hanya menegakkan yang benar-benar bisa diukur tanpa device

`02` §5 punya 8 baris budget. Empat bisa diukur jujur di runner CI tanpa device dan **ditegakkan**;
empat sisanya tidak, dan **tidak dibuat seolah-olah ditegakkan**.

| Baris `02` §5 | Status | Cara |
|---|---|---|
| Ukuran app ≤ 1,5 MB | ✅ ditegakkan | Gradle task `:sdk:checkPerformanceBudget` (AAR release, saat ini 20% budget) |
| Disk ≤ 20 MB | ✅ ditegakkan | `PerformanceBudgetTest` — konstanta **dan** eviction sungguhan |
| Retensi offline ≥ 7 hari | ✅ ditegakkan | Tidak ada expiry berbasis umur sama sekali; kapasitas satu-satunya batas |
| Tanpa wakeup khusus | ✅ ditegakkan | Manifest SDK + guard source-level + constraint WorkManager |
| Overhead cold start ≤ 30 ms p95 | ❌ **tidak tercakup** | Butuh macrobenchmark di device |
| CPU ≤ 2% | ❌ **tidak tercakup** | Butuh device di bawah beban |
| Memori ≤ 8 MB resident | ❌ **tidak tercakup** | Butuh device |
| Main thread nol blocking I/O | ❌ **tidak tercakup** | Butuh StrictMode di device |

Karena itu A12 ditutup **🟠, bukan ✅** — "Dilanggar = tidak boleh rilis" belum berlaku untuk
setengah daftarnya. Menandainya ✅ akan berarti tim mengira budget performa sudah dijaga CI
padahal empat baterisnya tidak pernah diukur sama sekali. Menutup sisanya butuh
`androidx.benchmark:benchmark-macro-junit4` + runner ber-emulator di CI: dua keputusan
(dependency dan biaya CI) yang belum diambil.

**Temuan sepanjang A12 — dua hal yang ketahuan karena test gagal, bukan karena review:**

**1. `DiskEventQueue` bisa melampaui plafon byte-nya sebesar satu event.** `enforceCapacity()`
meng-evict sampai isi **yang sudah ada** muat, lalu tetap menulis event baru — sehingga hasil
akhirnya bisa `maxBytes - 1 + ukuran_event`. Di 20 MB itu kecil, tapi `02` §5 menyatakan plafon,
dan plafon yang rutin dilewati sejumlah yang bisa diprediksi bukan plafon yang bisa dipakai
menghitung storage device. Diperbaiki: payload di-encode lebih dulu, ukurannya ikut diperhitungkan
saat eviction (`count + 1 > maxEvents || bytes + incomingBytes > maxBytes`).

**2. SDK memaksa 4 permission tambahan ke setiap app host, lewat WorkManager:** `WAKE_LOCK`,
`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `REORDER_TASKS`. SDK tidak pernah memintanya dan
tidak pernah memakainya — semuanya datang dari manifest `androidx.work`. Ini bertegangan dengan
`02` §5 "tanpa background wakeup khusus": `RECEIVE_BOOT_COMPLETED` justru mekanisme semacam itu
(WorkManager memakainya untuk memulihkan job setelah reboot), dan permission itu akan **terlihat di
store listing app host**. Belum diputuskan apa-apa: `PerformanceBudgetTest` mengunci himpunan itu
supaya tidak bisa bertambah diam-diam, dan keputusannya (terima & dokumentasikan, atau tinjau ulang
WorkManager) diserahkan ke pemilik proyek.

CI dibuat di `.github/workflows/ci.yml` dan **memanggil `verify.sh`**, bukan perintah Gradle
sendiri. Dua definisi "hijau" adalah cara paling cepat membuat bukti harness tidak berarti.

### 2026-09-07 · A17: bug enkripsi fatal yang hanya terlihat di device, dan batas sensitivitas watchdog ANR

Verification app dijalankan di emulator (`Medium_Phone_API_36.1`, API 36). Dua cacat nyata
ditemukan dalam menit-menit pertama — keduanya tidak mungkin terlihat dari 304 unit test yang
semuanya hijau.

**1. [KRITIS] `AesGcmQueueCipher.encrypt()` selalu gagal dengan kunci Keystore asli — SDK tidak
menangkap apa pun di device mana pun.**

Kunci Keystore dibuat dengan `setRandomizedEncryptionRequired(true)` (memang benar demikian).
Flag itu **melarang pemanggil menyuplai IV sendiri**: `init(ENCRYPT_MODE, key, GCMParameterSpec(...))`
melempar `InvalidAlgorithmParameterException: Caller-provided IV not permitted`. `encrypt()`
menangkapnya dan mengembalikan `null` — persis seperti yang dirancang untuk fail-safe — sehingga
`DiskEventQueue.enqueue()` menolak **setiap** event dan antrean selamanya kosong. Diam total,
tanpa crash, tanpa log.

Seluruh test suite JVM tetap hijau karena Robolectric tidak punya `AndroidKeyStore` sama sekali,
jadi semua test A14/A16 menjalankan jalur fallback plaintext. A14 dan A16 sempat ditandai ✅
berdasarkan bukti yang secara struktural tidak bisa menyentuh cacat ini.

Perbaikan: IV dibangkitkan oleh `Cipher` (`init(ENCRYPT_MODE, key)` lalu baca `cipher.iv`), bukan
oleh `SecureRandom` milik kita. Itu bukan kompromi keamanan melainkan penguatan — pada kunci
hardware-backed, IV dibangkitkan di dalam secure element.

**Aturan yang lahir dari ini:** setiap komponen yang bergantung pada layanan sistem yang tidak
dimiliki Robolectric (Keystore, dan sejenisnya) **wajib punya instrumented test**. Unit test hijau
bukan bukti untuk komponen semacam itu. Instrumented test ada di `sdk/src/androidTest/`, dijalankan
dengan `./gradlew :sdk:connectedDebugAndroidTest`, dan butuh device/emulator — karena itu **tidak**
masuk `verify.sh` (harness melarang mendaftarkan check yang tidak selalu bisa jalan).

**2. `AnrWatchdog` bisa melewatkan blokir yang lebih pendek dari 2x ambangnya.**

Watchdog melakukan post-sentinel → tidur selama `thresholdMs` → menilai. Karena periode polling
sama dengan ambangnya, blokir yang mulai dan berakhir di dalam satu siklus tidak pernah terlihat.
Terbukti di device: blokir 7 detik dengan ambang 5 detik **tidak** terdeteksi, berulang; blokir 12
detik terdeteksi. Jadi ambang efektifnya ~2x nilai `thresholdMs`, bukan `thresholdMs`.

Belum diperbaiki — itu perubahan desain pada A10, bukan pekerjaan A17. Perbaikan yang benar adalah
memisahkan periode polling dari ambang: post lebih sering (mis. tiap 1 detik) dan ukur berapa lama
sejak respons terakhir. Dicatat sebagai baris terbuka di `FEATURES.md`.

### 2026-09-07 · A10b: periode polling watchdog ANR dipisahkan dari ambangnya

`AnrWatchdog` versi pertama tidur selama `thresholdMs` penuh di antara pemeriksaan. Konsekuensinya
periode sampling **sama dengan** hal yang diukur, sehingga blokir yang mulai dan berakhir di dalam
satu siklus tidak pernah terlihat: ambang efektifnya 2x nilai yang dikonfigurasi. Terbukti di
emulator API 36 — blokir 7 detik dengan ambang 5 detik berulang kali TIDAK terdeteksi, blokir 12
detik terdeteksi.

Sekarang watchdog mem-posting sentinel setiap `pollIntervalMs` (default **1 detik**); sentinel
mencatat kapan ia berjalan, dan blokir dinyatakan ANR bila stempel terbaru sudah lebih tua dari
`thresholdMs`. Keterlambatan deteksi terburuk menjadi `threshold + satu poll` — dalam 20% dari
ambang, bukan 100%.

Tidak ada pencocokan sentinel-ke-poll tertentu. Beberapa sentinel bisa menumpuk di belakang main
thread yang terblokir lalu berjalan sekaligus saat ia lega; semuanya menimpa stempel yang sama
dengan nilai yang makin besar, jadi yang terbaru menang dan tumpukan itu tidak berbahaya.

Biayanya satu `Handler.post` sepele per detik di daemon thread ber-`MIN_PRIORITY`. Diverifikasi
di device: kasus 7 detik yang dulu gagal sekarang menghasilkan event.

**Pelajaran yang lebih umum:** sampling tidak boleh seperiode dengan hal yang disampel. Cacat ini
lolos review dan lolos unit test karena test-nya memanggil `tick()` sekali dan memaksa "responded =
false" — bentuk yang tidak pernah bisa memperlihatkan masalah windowing. Test-nya sekarang
mensimulasikan waktu (`FakeClock` yang hanya maju saat watchdog tidur), sehingga blokir 7 detik vs
12 detik adalah pernyataan yang bisa diuji, bukan kebetulan wall-clock.

### 2026-09-07 · A12 (lanjutan): 8 baris budget `02` §5 semuanya terukur; satu MERAH

Atas arahan user, empat baris budget yang tadinya "tidak tercakup" ditutup — **tanpa** menambah
`androidx.benchmark` maupun runner ber-emulator di CI. Semuanya diukur lewat instrumented test di
device (`sdk/src/androidTest/`), dijalankan dengan `./gradlew :sdk:connectedDebugAndroidTest`.

| Baris `02` §5 | Hasil di AVD API 36 | Budget |
|---|---|---|
| Ukuran app | 314 KB (20%) | ≤ 1,5 MB ✅ |
| Disk | konstanta + eviction diuji | ≤ 20 MB ✅ |
| Retensi offline | tidak ada expiry umur | ≥ 7 hari ✅ |
| Tanpa wakeup khusus | manifest + guard source | ✅ |
| **Cold start** | p95 **17 ms**, median 3 ms | ≤ 30 ms ✅ |
| **Memori** | **0,27 MB** | ≤ 8 MB ✅ |
| **CPU (idle)** | delta **0,6%** (baseline 0,2% → 0,8%) | ≤ 2% ✅ |
| **Main thread nol blocking I/O** | **13 pelanggaran** | **❌ MERAH** |

**Tiga cacat metodologi di test saya sendiri, ketahuan sebelum angkanya dipercaya:**

1. **Detektor yang tidak mendeteksi apa pun.** Versi pertama harness StrictMode mengembalikan
   "nol pelanggaran" — bukan karena bersih, tapi karena policy-nya dipulihkan di dalam runnable
   yang diukur, sehingga pelanggaran yang masih antre di looper terbuang. Ketahuan karena ada
   **negative control** (`theDetectorActuallyDetects`, memakai `internal object BudgetProbe` yang
   sengaja menulis file di main thread). Kontrol itu wajib ada: hijau dari detektor rusak dan
   hijau dari kode bersih tidak bisa dibedakan tanpanya.
2. **Test yang lulus tanpa menjalankan kodenya.** Test main-thread pertama tidak pernah memanggil
   `APM.start()`, jadi `APM.logError()` adalah no-op terdokumentasi dan test lulus tanpa menyentuh
   satu baris pun kode yang diklaimnya diukur.
3. **Angka yang mengukur hal lain.** "CPU 2,1%" adalah CPU **seluruh proses** termasuk harness
   instrumentasi — tidak mengatakan apa-apa tentang SDK. Diperbaiki jadi delta terhadap baseline
   (SDK mati vs hidup): 0,6%. Percentile cold start juga salah indeks (mengambil maksimum, bukan
   p95) dan mengukur start berulang di satu proses, yang memakai ulang kelas dan kunci Keystore.

**Yang masih merah, dan kenapa itu P0:** `APM.logError()` melakukan enkripsi + listing direktori +
tulis file **secara sinkron di thread pemanggil**. App adopter memanggilnya dari click listener dan
blok `catch` di kode UI — artinya SDK memblokir main thread mereka setiap kali. `APM.start()` juga
melakukan 7 pembacaan disk di `Application.onCreate`. Keduanya melanggar `02` §5 dan larangan di
file ini. Perbaikannya adalah perubahan pada jalur capture, bukan pada budget — dicatat sebagai
**A18**, dengan trade-off durabilitas yang harus diputuskan user (lihat baris itu).

`02` §5 berbunyi "Dilanggar = tidak boleh rilis". Baris ini dilanggar, jadi **A13 (distribusi)
tidak boleh ditutup sebelum A18 selesai.**

### 2026-09-07 · A18: capture dipindah dari thread pemanggil; write-local-first diperjelas

Budget main-thread `02` §5 yang merah di A12 sekarang hijau. Dua perubahan, dan satu invariant di
file ini perlu dibaca lebih presisi karenanya.

**1. `AsyncEventQueue` — penulisan dipindah dari thread pemanggil.** Satu worker thread FIFO
(daemon, `MIN_PRIORITY`) menguras antrean, jadi urutan terjaga dan `seq` tetap bermakna. Buffer
dibatasi 512 event; luapan dibuang dan dihitung sebagai `queue_full` di `sdk.health` (§2.3), karena
buffer tak terbatas mengubah ledakan capture jadi heap tak terbatas — persis kegagalan yang budget
memori 8 MB ada untuk mencegah.

**2. `APM.start()` memindahkan perakitan ke background thread bila dipanggil dari main thread.**
Dipanggil dari thread lain, ia berjalan inline — tidak ada yang perlu dilindungi. Biayanya jendela
beberapa milidetik di `Application.onCreate` saat SDK belum hidup dan semua API no-op, termasuk
crash handler. Ditukar dengan tidak memblokir main thread di setiap peluncuran. `APM.awaitStarted()`
disediakan untuk host yang benar-benar butuh SDK hidup sebelum lanjut.

**Klarifikasi invariant "write-local-first".** Bunyi aslinya: "setiap event ditulis ke disk secara
durabel sebelum network call apa pun dicoba." Itu **tetap berlaku utuh** — yang dijamin adalah
tidak ada yang diupload sebelum ditulis, dan `SyncEngine` tetap hanya membaca dari disk. Yang
**tidak** dijamin lagi adalah bahwa event sudah di disk saat `enqueue()` kembali. Konsekuensinya
nyata: event non-fatal yang ditangkap beberapa mikrodetik sebelum hard crash bisa hilang.

Karena itu jalur crash dan termination **menulis lewat queue sinkron**, bukan async. Event yang ada
justru untuk menggambarkan proses yang sedang mati tidak boleh diantre di belakang worker thread
yang tidak akan pernah dijadwalkan lagi. Pembagiannya di `ApmBootstrap`: `syncQueue` untuk
`JvmCrashHandler` + `TerminationDetector` + `SyncEngine`; `queue` (async) untuk manual API,
breadcrumb, network capture, cold start, dan ANR watchdog.

**Cacat yang ketahuan saat menulis ini, dua-duanya lolos kompilasi:**
- `awaitDrain()` versi pertama menunggu sampai antrean executor kosong. Antrean kosong berarti task
  **diambil**, bukan **selesai** — jadi penantiannya kembali sebelum penulisan terjadi, dan test
  membaca antrean kosong. Diganti dengan barrier: submit satu task, tunggu ia berjalan; executor
  single-thread FIFO menjamin semua sebelumnya sudah selesai.
- `awaitStarted()` versi pertama memakai `CountDownLatch` sekali-pakai di `object APM`. Latch itu
  one-shot seumur proses, jadi start kedua setelah teardown melihatnya sudah terbuka dan melapor
  sukses sebelum perakitan berjalan. Diganti polling ke `ApmRuntime.isStarted`.

Hasil terukur sesudahnya (AVD API 36): main-thread I/O **0 pelanggaran**, cold start p95 **15 ms**,
memori **0,69 MB**, CPU idle delta **0,2%**. Seluruh 8 baris `02` §5 hijau.

### 2026-09-07 · A9b: xCrash terpasang dan jalur next-launch terbukti, tapi `binary_images` tidak bisa diisi

Blokir A9b sejak D1 adalah "butuh sampel crash native asli untuk validasi parsing". Sampel itu
sekarang ada: SIGSEGV dikirim ke verification app yang sudah memasang xCrash, di emulator arm64
API 33 **dan** API 36. Dua tombstone asli disimpan sebagai fixture di
`sdk/src/test/resources/tombstones/`, dan `TombstoneParser` ditulis terhadap **file yang
benar-benar dihasilkan device**, bukan terhadap dokumentasi format.

**Yang terbukti bekerja (end-to-end di device):** signal handler terpasang → tombstone ditulis saat
crash → relaunch → tombstone dikonsumsi (0 file tersisa) → event `crash` masuk antrean (1 event).
Itu MOB-16 + SEC-09 untuk jalur native: tidak ada parsing, enkripsi, atau enqueue di dalam signal
handler; semuanya terjadi di proses yang sehat berikutnya.

**Yang TIDAK bisa dipenuhi, dan ini bukan soal implementasi:**

`01` §4.3.2 menandai `uuid` sebagai **wajib** — itulah yang mencocokkan crash dengan berkas symbol
yang benar. xCrash bisa menulis memory map dan ELF hash (`setNativeDumpMap` / `setNativeDumpElfHash`,
keduanya sudah diaktifkan), tetapi kedua section itu ditulis oleh **proses anak dumper**, dan dumper
itu **gagal di setiap capture**: `child terminated normally with non-zero exit status(102)`.
Reproducible di API 33 dan API 36, dengan dan tanpa opsi map/elf-hash.

Akibatnya tombstone yang didapat selalu versi fallback dari signal handler:
- **tidak ada memory map, tidak ada build id** → `binary_images` kosong, symbolication mustahil
- `instruction_addr` hanya offset relatif terhadap objek, tanpa base address untuk melabuhkannya
- backtrace hanya **4 frame**, semuanya library sistem; tidak ada frame aplikasi
- tidak ada section thread lain

`binary_images` sengaja dikembalikan **kosong**, bukan diisi sebagian atau ditebak. Laporan yang
tampak bisa disimbolikasi padahal tidak akan membuat backend dan Frontend membangun di atas data
yang tidak ada — persis kegagalan yang keputusan D1 hindari.

**Karena itu A9b ditutup 🟠, bukan ✅.** MOB-17 mensyaratkan `threads` **dan** `binary_images`;
separuhnya tidak bisa dipenuhi dengan xCrash sebagaimana ia berperilaku hari ini.

**Batas pengujian yang jujur:** semua capture memakai SIGSEGV **yang diinjeksi** (`kill -11`,
`SI_USER`) di **emulator arm64**. Fault asli di dalam proses (dereference null di kode native) pada
**device fisik** belum pernah diuji, dan bisa saja dumper-nya berhasil di sana. Itu eksperimen
termurah berikutnya sebelum menyimpulkan xCrash tidak layak.

**Keputusan yang menunggu pemilik proyek** (tiga opsi, tidak saya ambil sendiri):
1. Uji fault native asli di device fisik dulu — murah, dan bisa mengubah seluruh kesimpulan.
2. Ganti xCrash (rilis terakhir 2021) dengan solusi NDK yang terpelihara.
3. Terima crash native tanpa symbolication untuk v1, dan amandemen `01` §4.3.2 agar `uuid`
   opsional untuk platform yang tidak bisa menyediakannya.

**Catatan budget:** AAR naik 314 KB → 340 KB (22% dari 1,5 MB). Tapi native `.so` xCrash ada di APK
app (~190 KB per ABI), dan baris budget `02` §5 berbunyi "penambahan ukuran **app**". Task
`checkPerformanceBudget` hanya mengukur AAR — keterbatasan yang sudah dicatat di A12 dan kini
menjadi lebih material.

### 2026-09-07 · A13: koordinat rilis, semver, dan publikasi yang bisa diverifikasi tanpa kredensial

**Koordinat:** `edts.apm.android:apm-android-sdk`. Group, artifact dan version hidup di satu tempat,
`gradle.properties`. POM, `BuildConfig.LIBRARY_VERSION` dan header `X-APM-Sdk` semuanya membaca dari
sana — artefak yang dirilis tidak bisa melaporkan versi yang berbeda dari versi ia dipublikasikan.
Sebelumnya `0.1.0` di-hardcode di `buildConfigField`, terpisah dari koordinat mana pun.

**Semver (MOB-24) — yang menentukan adalah definisi "breaking".** Dicatat di `RELEASING.md`, dan
satu barisnya perlu ditegaskan di sini karena mudah terlewat: **wire contract adalah bagian dari
public API.** SDK bicara dengan backend yang dirilis terpisah, jadi field yang di-rename SDK sama
breaking-nya dengan method yang dihapus — dan lebih buruk, karena gagalnya di ingestion, bukan saat
kompilasi. `schema_version` (`01` §2) adalah titik koordinasinya: naik hanya pada perubahan wire
yang breaking. Menaikkan `minSdk` juga MAJOR.

**Kredensial tidak pernah masuk repo.** Repository `internal` hanya didaftarkan bila URL disuplai
lewat `-PapmMavenUrl` atau `APM_MAVEN_URL`. Konsekuensinya seluruh jalur rilis tetap bisa dijalankan
di mesin tanpa kredensial apa pun — dan itu bukan efek samping, itu tujuannya.

**`verifyPublication` — rilis adalah satu-satunya operasi tanpa undo.** Artefak yang sudah
di-resolve tim adopter tidak bisa diam-diam diganti. Karena itu publikasi dibangun ke
`build/local-repo` (repository sungguhan di disk, bukan `publishToMavenLocal` yang menyembunyikan
layout di `~/.m2`) lalu divalidasi: AAR punya `classes.jar`, `AndroidManifest.xml` dan resource
`apm_network_security_config.xml`; sources jar tidak kosong; POM memakai versi terkunci — **range
ditolak** (SEC-19: "versinya dikunci"); dan manifest SHA-256 cocok dengan byte di sebelahnya.

Pengecekan itu diuji dengan negative control: menghapus sources jar dan menambah satu byte ke AAR,
keduanya benar-benar menggagalkan task. Pelajaran dari A12 diterapkan — pengecekan yang belum
pernah terlihat gagal tidak boleh dipercaya.

`./verify.sh publish` menjalankannya, masuk ke `all`, dan berjalan di CI setiap perubahan. Jadi POM
rusak atau artefak hilang ketahuan saat itu juga, bukan saat rilis.

**Yang TIDAK bisa saya selesaikan, dan alasannya bukan teknis:** URL dan kredensial Maven internal.
Itu milik organisasi. Tanpa keduanya artefak tidak pernah benar-benar diunggah ke mana pun, jadi
A13 ditutup **🟠**, bukan ✅ — MOB-23 berbunyi "distribusi via Maven internal", dan distribusi ke
repository yang belum ada bukan distribusi.

**Blokir rilis yang masih berdiri** (tercatat juga di `RELEASING.md`): manual checklist no. 15 —
**jalur upload belum pernah berjalan di device**. WorkManager melaporkan `Constraints not met` di
kedua emulator uji, jadi `SyncWorker` tidak pernah dieksekusi di luar unit test. Mem-publish SDK
yang jalur upload-nya belum pernah jalan berarti tim adopter yang menemukan masalahnya.

### 2026-09-07 · A9b Langkah 0: hipotesis "sinyal injeksi" TERBANTAH, tapi nilai crash native jauh lebih tinggi dari dugaan

Eksperimen: pemicu JNI di verification app yang men-dereference null → SIGSEGV asli
(`code 1 SEGV_MAPERR, fault addr 0x0`), bukan `kill -11` (`SI_USER`) yang dipakai semua capture
sebelumnya. Dijalankan di AVD API 33.

**Hipotesisnya salah.** Dumper xCrash **tetap** gagal `exit status(102)`, sama persis seperti pada
sinyal injeksi. Jadi kegagalan dumper bukan artefak cara crash dipicu — ia gagal apa pun
penyebabnya. Konsekuensi yang tetap berlaku: tidak ada memory map, tidak ada build id,
`binary_images` tetap kosong, symbolication penuh tetap mustahil.

**Tetapi kesimpulan saya sebelumnya terlalu pesimistis, dan itu perlu dikoreksi.** Saya menyimpulkan
"backtrace hanya 4 frame, semuanya library sistem, tidak ada frame aplikasi" dari dua tombstone yang
**dua-duanya** berasal dari sinyal injeksi. Sinyal injeksi menginterupsi thread yang sedang parkir di
looper — jadi 4 frame sistem itu menggambarkan cara crash-nya dipicu, bukan kemampuan xCrash.

Fault asli menghasilkan gambaran yang jauh berbeda:

| | Sinyal injeksi (`kill -11`) | Fault asli (`SEGV_MAPERR`) |
|---|---|---|
| Jumlah frame | 4 | **22** |
| Frame aplikasi | tidak ada | **#00 dan #01** |
| Nama simbol | tidak ada | `Java_..._crashNow+32` |
| `fault addr` | `--------` | `0x0` |

Artinya **`is_app` per frame (MOB-17, dan syarat FE-06) berfungsi penuh tanpa symbolication** —
persis seperti yang `01` §4.3.1 harapkan: "penyorotan frame aplikasi TIDAK menunggu symbolication".
Path frame sudah cukup untuk memisahkan "crash di kode kita" dari "crash di framework sistem".
Simbol yang diekspor (fungsi JNI) bahkan sampai apa adanya.

**Yang masih hilang, dan hanya itu:** `binary_images[].uuid`, yang dibutuhkan untuk mensimbolikasi
fungsi **non-ekspor** di library rilis yang sudah di-strip. Untuk crash di kode native aplikasi
sendiri, laporan hari ini sudah menunjukkan library dan offset yang benar.

**Pelajaran metodologi:** dua sampel yang dikumpulkan dengan cara yang sama bukan dua sampel. Saya
menarik kesimpulan tentang kemampuan sebuah library dari dua capture yang berbagi satu cacat
metode yang sama, dan menuliskannya sebagai temuan. Fixture ketiga
(`real-api33-genuine-fault.txt`) sekarang menjadi yang paling representatif dari ketiganya.

### 2026-09-07 · A9b Opsi A: dumper xCrash TETAP gagal di device fisik — opsi A habis

User menjalankan verification app di device fisik (Realme RMX3630, **API 34**, arm64) dan
menghubungkannya lewat USB. Fault native asli dipicu di sana (JNI null dereference →
`signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0`).

**Hasil: dumper tetap gagal `child terminated normally with non-zero exit status(102)`.**
Tidak ada memory map, tidak ada build id. Sama persis seperti di emulator.

Bukti sekarang mencakup empat capture yang saling melengkapi:

| Fixture | Jenis fault | Platform | Frame | dumper gagal |
|---|---|---|---|---|
| `real-api33-sigsegv` | injeksi (`SI_USER`) | emulator | 4 | ya |
| `real-api36-sigsegv` | injeksi (`SI_USER`) | emulator | 4 | ya |
| `real-api33-genuine-fault` | asli (`SEGV_MAPERR`) | emulator | 22 | ya |
| `real-device-api34-genuine-fault` | asli (`SEGV_MAPERR`) | **fisik** | **31** | **ya** |

Tiga API level (33/34/36), dua jenis fault, emulator dan hardware asli, dengan dan tanpa
`setNativeDumpMap`/`setNativeDumpElfHash`. **Opsi A (uji device fisik) sudah habis** — kegagalan
dumper bukan artefak emulator dan bukan artefak cara crash dipicu. `binary_images` tidak bisa
diperoleh dari xCrash 3.1.0 titik.

**Yang tetap berfungsi, dan di hardware asli malah lebih baik:** 31 frame, frame aplikasi di #00/#01,
nama simbol yang diekspor terbaca. `is_app` dan penyorotan frame aplikasi (FE-06) berjalan penuh
tanpa symbolication. End-to-end terbukti di device: tombstone → relaunch → dikonsumsi → event masuk
antrean.

**Keputusan tersisa menyempit jadi dua: B (ganti library) atau C (terima untuk v1).**

---

**Dua cacat lain yang ketahuan justru karena menguji di device fisik:**

1. **`ApmBootstrap` menelan kegagalan startup tanpa jejak apa pun.** Saat SDK terlihat tidak
   start di device, tidak ada satu baris pun di logcat untuk mendiagnosisnya — untuk SDK monitoring
   itu ironi yang mahal. Ditambahkan satu `Log.w` pada kegagalan start total. Itu bukan "logging"
   (MOB-26 masih terpisah); itu pembeda antara masalah yang bisa didiagnosis dan yang tidak.
2. **Verification app menampilkan status basi.** A18 membuat `APM.start()` asinkron, tapi
   `statusLine()` hanya dihitung saat `onResume` — jadi cetakan pertamanya hampir selalu
   `started: false`, dan itu **membuat saya salah menduga ada kegagalan startup** dan menghabiskan
   waktu mengejarnya. Status kini dibaca ulang 1,5 detik setelah resume. Pelajaran: nilai asinkron
   yang ditampilkan sekali saat render adalah jebakan diagnosis, bukan sekadar UI yang kurang rapi.

### 2026-09-09 · A9b opsi B: xCrash DIHAPUS, diganti tombstone milik Android sendiri

User memilih opsi B. Hasilnya lebih baik dari "ganti satu library dengan library lain":
**xCrash dihapus dan tidak ada dependency pengganti sama sekali.**

Sumber crash native sekarang `ApplicationExitInfo.getTraceInputStream()` untuk
`REASON_CRASH_NATIVE` — tombstone protobuf milik AOSP, dibaca di peluncuran berikutnya. SDK tidak
memasang signal handler apa pun lagi: Android sudah menulis tombstone-nya, kita hanya membacanya.
Itu versi yang lebih kuat dari yang diminta SEC-09 — tidak ada satu baris pun kode kita yang
berjalan saat proses sedang mati.

**Perbandingan pada crash yang sama (null dereference, AVD API 33):**

| | xCrash 3.1.0 | Tombstone sistem |
|---|---|---|
| `binary_images[].uuid` | **tidak pernah** (dumper gagal) | **15 objek, build id lengkap** |
| `object_addr` | tidak ada | ada (`pc - rel_pc`) |
| Frame thread yang crash | 31 | **47** |
| Nama simbol | hanya yang diekspor | dari tombstone |
| Dependency | 1 (+4 native `.so`) | **0** |
| Jangkauan | API 24+ (terdegradasi) | **API 30+** |

**Harga yang dibayar, dan ini nyata:** `ApplicationExitInfo` baru ada di **API 30+**, sementara
`minSdk` 24. Di API 24–29 **tidak ada penangkapan crash native sama sekali**. Sebelumnya xCrash
"menangkap" di sana, tapi yang dihasilkannya tidak pernah bisa disimbolikasi — jadi yang hilang
adalah laporan yang tidak berguna, bukan laporan yang berguna. Dicatat sebagai keterbatasan, bukan
disembunyikan.

**Field number proto diturunkan dari byte tombstone asli**, bukan dari salinan `Tombstone.proto` —
disiplin yang sama seperti parser teks, dan alasannya sama. `ProtoReader` ditulis tangan (~90 baris)
supaya tidak perlu runtime protobuf + plugin codegen; field tak dikenal dilewati, jadi Android baru
yang menambah field tidak merusak parsing.

**Empat bug yang ketahuan karena diuji terhadap byte sungguhan, bukan terhadap ekspektasi:**
1. **Probe saya sendiri merusak datanya.** `traceInputStream.bufferedReader().readText()` mendekode
   protobuf sebagai UTF-8 — file 321 KB yang "terparsir" sampai byte 265. Harus dibaca sebagai byte.
2. **proto3 menghilangkan nilai default.** `fault_address = 0` — kasus paling umum, null dereference —
   tidak ada di byte sama sekali; ada boolean terpisah `has_fault_address`. Tanpa membacanya,
   "crash di 0x0" tidak bisa dibedakan dari "alamat tidak tercatat".
3. **Library di dalam APK tidak punya entri mapping atas namanya sendiri.** Ia dipetakan sebagai
   `…/base.apk`, jadi `binary_images` yang dibangun dari `memory_mappings` kehilangan nama library
   dan melewatkan justru library yang crash. Sekarang dibangun dari **frame**, yang membawa nama
   dan build id — dan hasilnya hanya objek yang benar-benar ada di stack (15, bukan 2102).
4. **`base.apk!libfoo.so` tidak punya `/` setelah `!`.** `substringAfterLast('/')` menghasilkan
   `base.apk!libfoo.so` sebagai nama objek untuk hampir semua library native yang tidak dikompresi.

**Regresi performa yang ditangkap budget A12, bukan oleh review:** menjalankan pemindaian tombstone
di dalam `ApmBootstrap` membuat cold start **15 ms → 50 ms** dan CPU idle **0,2% → 3%**, dua-duanya
melanggar `02` §5. Setiap tombstone ~250 KB dengan ~2100 memory mapping. Diperbaiki: pemindaian
dipindah ke thread sendiri **setelah** graph dipublikasikan. Crash dari proses sebelumnya tidak
pernah cukup mendesak untuk duduk di jalur startup.

Ukuran event juga dibatasi (24 thread, 96 frame untuk thread yang crash, 12 untuk lainnya): tanpa
batas, satu laporan jadi **~90 KB**, dan 200 event per batch (`01` §7) akan langsung kena `413`.
Dengan batas itu ~56 KB.

### 2026-09-09 · A19: upload symbol sebagai gate, dan consumer R8 rules yang selama ini kosong

**`01` §8 adalah gate, bukan langkah.** `:app:uploadSymbols` menggagalkan build pada respons
non-2xx, pada kegagalan transport, dan saat endpoint/token tidak ada. Ia **menolak jadi no-op** —
task yang diam-diam tidak melakukan apa-apa persis cara sebuah rilis lolos tanpa symbol, dan
`01` §8 sudah menyatakan akibatnya: tanpa symbol crash report tidak terbaca, dan baru ketahuan
berminggu-minggu kemudian.

Diverifikasi terhadap server HTTP sungguhan, bukan mock: multipart `POST /v1/symbols` dengan
`app_id`, `version`, `build`, `platform=android`, file `mapping.txt` 6,1 MB, `Authorization:
Bearer <CI token>`. Tiga jalur kegagalan diuji sebagai kontrol negatif (500, connection refused,
token hilang) — ketiganya menggagalkan build.

Token CI **terpisah dari app key** (`01` §8). App key adalah identifier yang tertanam di binary dan
bisa diekstrak siapa pun (SEC-13); memakainya untuk mengautentikasi rilis akan menyerahkan hak
unggah symbol ke siapa saja yang mengunduh APK.

**Yang harus dikatakan terang-terangan di dokumen integrasi:** SDK ini di-shrink sebagai bagian dari
build **app adopter**, jadi `mapping.txt` yang bisa membaca crash di lapangan adalah milik app
mereka, bukan milik repo ini. Script `gradle/scripts/symbol-upload.gradle.kts` dipasang di
verification app sebagai implementasi rujukan; tim adopter memasangnya di modul app mereka sendiri.
Tim yang melewatkannya akan menerima crash report yang tidak bisa mereka baca.

**Symbol native belum tercakup.** `01` §8 hanya menyebut `mapping.txt` untuk Android. Sejak A9b,
crash native membawa build id dan **bisa** disimbolikasi — tapi itu butuh `.so` tanpa strip
diunggah dan dicocokkan lewat build id, dan kontrak endpoint-nya belum menggambarkan itu. Perlu
keputusan tim backend.

---

**Temuan terpisah, dan ini yang paling berbahaya dari seluruh A19: `sdk/consumer-rules.pro` kosong.**

Rules itu ikut di dalam AAR dan diterapkan ke setiap build R8 app adopter. Kosong berarti:

1. **`SyncWorker` di-rename atau dihapus.** WorkManager meng-instansiasinya secara reflektif lewat
   nama kelas — upload berhenti total, dan tidak ada yang melaporkan error; antrean hanya penuh.
2. **Serializer `kotlinx.serialization` hilang.** Kelas `$$serializer` dan companion object tidak
   punya call site yang R8 bisa lihat, jadi setiap event gagal diserialisasi dan SDK tidak menulis
   apa pun ke disk.

Dua-duanya **hanya muncul di release build app adopter**, bukan di debug, bukan di test kita. SDK
akan terkompilasi, terpasang, lalu diam. Sekarang rules-nya ditulis, dan dibuktikan dengan
menyalakan R8 di verification app: `mapping.txt` mengonfirmasi `APM`, `ApmConfig` dan `SyncWorker`
tidak di-rename, 14 entri `$$serializer` bertahan, dan aplikasi R8 di device benar-benar menulis
8 event terenkripsi.

Pelajaran: **library yang tidak pernah dibangun ulang di bawah R8 tidak pernah diuji seperti cara
ia akan dipakai.** `isMinifyEnabled = true` di verification app sekarang permanen justru karena itu.

### 2026-09-09 · MOB-25: integrasi butuh TIGA langkah manual, bukan satu

Dokumen integrasi (`INTEGRATION.md`) dan `README.md` dibuat. Sample app-nya sudah ada sejak A17,
jadi MOB-25 tinggal separuh dokumen — tapi menulisnya memaksa satu koreksi terhadap klaim yang
sudah lama berdiri di `02` §3.4.

**`02` §3.4 menyebut screen tracking sebagai "satu-satunya langkah yang tidak nol-effort". Itu
tidak akurat.** Integrasi sebenarnya butuh **tiga** langkah manual:

1. `APM.start()` di `Application.onCreate()`.
2. Memasang `APM.networkEventListener()` ke `OkHttpClient` milik app host — SDK tidak memiliki
   client itu dan tidak bisa memasang dirinya sendiri (MOB-01/02). Tanpa langkah ini tidak ada
   telemetri network sama sekali.
3. Memasang script upload symbol di modul app mereka (A19) — `mapping.txt` yang bisa membaca crash
   di lapangan milik app adopter, bukan milik repo ini.

Screen tracking justru **nol-effort** di Android (otomatis lewat `ActivityLifecycleCallbacks`),
kebalikan dari yang tertulis. Klaim di `02` §3.4 berasal dari asumsi iOS. Dokumen integrasi menulis
ketiganya menonjol di bagian atas, bukan sebagai catatan kaki.

**Dua hal diungkap di muka, sebelum tim adopter berkomitmen**, karena keduanya berdampak di luar
"menambah satu dependency":

- **SDK menambahkan empat permission ke app mereka** — `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`,
  `FOREGROUND_SERVICE`, `REORDER_TASKS` — semuanya dari WorkManager, dan **akan terlihat di Play
  Store listing mereka**. Menyembunyikannya sampai review keamanan adalah cara tercepat kehilangan
  kepercayaan tim adopter.
- **Privacy policy mereka kemungkinan perlu diperbarui** (SEC-22), dan dokumen data inventory
  (SEC-21) belum ada — jadi dokumen integrasi menyuruh mereka memintanya, bukan berpura-pura sudah
  tersedia.

Keterbatasan hari ini juga ditulis apa adanya, bukan dihaluskan: crash native butuh API 30+, crash
native belum bisa disimbolikasi (kontrak upload `.so` belum ada), dan `debugLogging` masih tidak
melakukan apa-apa (MOB-26).

Setiap klaim di dokumen diperiksa terhadap kode: minSdk, versi, daftar permission, dan status
`debugLogging`. Potongan kode langkah 2 dan 3 identik dengan yang benar-benar dipakai verification
app — dokumen yang potongannya tidak pernah dikompilasi adalah dokumen yang salah dalam beberapa
bulan.

### 2026-09-09 · SEC-21/SEC-22: data inventory diturunkan dari kode, bukan dari spec

`DATA-INVENTORY.md` mendaftarkan setiap field yang benar-benar dikumpulkan SDK, dan memetakannya ke
form Data Safety Play Console (SEC-22). Dua aturan yang dipakai menulisnya, dan keduanya penting:

**1. Diturunkan dari kode, bukan dari `01`.** Setiap field di dokumen itu ditulis oleh baris tertentu
di `sdk/src/main/kotlin`; field yang tidak diproduksi kode tidak didaftarkan, dan sebaliknya.
Dokumen inventaris data yang menyalin spec akan mendeskripsikan sistem yang diinginkan, bukan sistem
yang di-ship — dan yang dipakai tim legal untuk mengisi form regulator adalah yang di-ship.
Angka-angkanya (100 breadcrumb, 5 000 event, 20 MB) diverifikasi ulang terhadap konstanta di kode.

**2. Batasnya dinyatakan, bukan dihaluskan.** Scrubbing berbasis pola, jadi tidak bisa diklaim
lengkap. Stack trace dan pesan error yang ditulis developer bisa membawa data pribadi secara tidak
sengaja. `device.timezone` adalah sinyal lokasi kasar. Ketiganya ditulis sebagai "open questions"
di dokumen, bukan disembunyikan — reviewer privasi yang menemukannya sendiri akan meragukan seluruh
dokumen.

**Keputusan yang bukan milik SDK, dan harus dikatakan begitu:** `APM.setUser()` adalah **satu-satunya**
tempat data pribadi bisa masuk, dan SDK tidak memeriksa maupun membatasi nilainya. Kalau tim adopter
mengoper nomor telepon, mereka wajib mendeklarasikannya di Data Safety. Dokumen memberi tiga pilihan
eksplisit (id internal / data pribadi + deklarasi / tidak memanggil `setUser` sama sekali) dan
merekomendasikan yang pertama, alih-alih berpura-pura pilihan itu tidak ada.

Klaim yang bertahan diperiksa juga: `user_id` mentah **melewati** endpoint ingestion sebelum di-hash.
Klaim yang benar adalah "tidak disimpan", bukan "tidak pernah dikirim" — konsisten dengan `01` §2.1.

### 2026-09-09 · MOB-26: debug logging ditegakkan oleh build, dan diletakkan di dalam scrubber

`ApmConfig.debugLogging` sudah ada sejak A1 tapi tidak pernah dibaca satu baris pun — dead config
selama seluruh proyek. Sekarang berfungsi, dengan dua keputusan yang keduanya soal keamanan.

**1. Logger duduk DI DALAM `ScrubbingEventQueue`, bukan di luarnya.**
Komposisinya `GatedEventQueue(ScrubbingEventQueue(LoggingEventQueue(disk)))`, jadi yang tercetak
sudah diredaksi. Logcat bukan kanal privat — developer mana pun dengan `adb` bisa membacanya, dan di
Android lama app lain juga bisa. Memasang logger di luar scrubber akan mencetak persis data pribadi
yang SEC-05 ada untuk menghapusnya, ke satu-satunya tempat yang tidak pernah diaudit siapa pun.
Urutan ini di-assert oleh test (`composed inside the scrubber, the log never shows unredacted
personal data`), bukan sekadar ditulis di komentar.

**2. "Non-aktif di build release" ditegakkan oleh build, bukan oleh config.**
`DebugMode.isEnabled` menuntut **dua** syarat: flag config **dan** app host `FLAG_DEBUGGABLE`. Flag
saja tidak cukup — tim yang meninggalkan `debugLogging = true` di objek config lalu merilisnya akan
menyiarkan setiap event ke logcat produksi. Requirement-nya berbunyi "non-aktif di build release",
jadi build itu sendiri yang harus menegakkannya.

`FLAG_DEBUGGABLE` dibaca dari **app host**, bukan dari `BuildConfig.DEBUG` milik SDK. SDK di-ship
sebagai AAR release ke dalam app yang debuggable sepanjang waktu; build type SDK tidak mengatakan
apa pun tentang app yang sedang dijalankan developer.

Diverifikasi di device dengan config yang sama persis: build debug mencetak satu baris per event;
build release **nol baris**, sementara SDK tetap menangkap 7 event. Itu bentuk pengujian yang
diminta requirement — bukan "flag-nya kami hormati", tapi "build release mengabaikannya".

### 2026-09-09 · A13 selesai: rilis 0.1.0 ke GitHub Packages, dan pelajaran soal penyimpanan kredensial

`edts.apm.android:apm-android-sdk:0.1.0` **live di GitHub Packages**
(`https://maven.pkg.github.com/adeanugerah89/apm-android-sdk`) dan terbukti bisa ditarik proyek lain
(362 155 byte AAR). Rantai penuh terverifikasi: gladi bersih SNAPSHOT → tarik sebagai konsumen →
`verify.sh all` hijau → rilis 0.1.0 → tarik lagi sebagai konsumen.

Percobaan publish ulang 0.1.0 mengembalikan **409 Conflict**. Itu perilaku yang benar dan layak
dicatat sebagai properti yang kita andalkan: registry menolak menimpa versi rilis. Sejalan dengan
alasan `verifyPublication` ada — rilis tidak bisa dibatalkan, jadi kesalahan harus tertangkap
sebelum unggahan, bukan sesudah.

**Catatan untuk tim adopter:** registry Maven GitHub menuntut autentikasi untuk **membaca**, tidak
seperti Maven Central. Setiap developer dan setiap job CI butuh PAT `read:packages`. Ini friksi
nyata dibanding Nexus/Artifactory internal; GitHub Packages dipilih sebagai langkah pertama untuk
membuktikan rantai rilis bekerja, bukan sebagai rumah permanen. Pindah nanti hanya mengubah nilai
`apmMavenUrl`.

---

**Insiden kredensial, dan aturan yang lahir darinya.**

Token publish sempat berada di `gradle.properties` **milik proyek** — file yang ikut ter-commit, di
repo yang **publik**. Diperiksa: commit `be1f378` yang sudah ter-push **tidak** memuatnya, jadi tidak
ada kebocoran. Nilainya tetap direkomendasikan dirotasi karena sempat tercetak polos di output
terminal.

Dua kesalahan, dan keduanya milik proses ini, bukan milik user:

1. **Saya mencetak nilainya.** Saat memeriksa lokasi token saya memakai `grep` yang menampilkan
   baris utuh, padahal pemeriksaan pertama sudah benar — hanya melaporkan keberadaan dan panjang.
   Aturannya: **kredensial diperiksa keberadaannya, tidak pernah isinya.**
2. **Saya memindahkannya tanpa memastikan itu bertahan.** Token dipindah ke `~/.gradle/`, lalu user
   memasangnya lagi di tempat lain — tiga kali, di tiga lokasi berbeda. Itu bukan kecerobohan user;
   itu tanda bahwa lokasi yang saya minta tidak jelas atau tidak nyaman.

Karena itu build sekarang membaca setelan publish dengan urutan: `-P` → **`local.properties`** →
environment variable. `local.properties` didukung karena itu konvensi Android, sudah ter-ignore git
di repo ini (diverifikasi), dan itulah tempat yang secara alami dipilih orang. Menolak lokasi yang
akan dipakai orang tidak membuat rahasia lebih aman — ia hanya memindahkan rahasia ke tempat yang
tidak diperiksa siapa pun.

Untuk token rilis, urutan preferensi tetap: **macOS Keychain** (init script tersedia di
`~/.gradle/init.d/apm-credentials.gradle.kts`) → **CI secrets** → `~/.gradle/gradle.properties` →
`local.properties`. Yang tidak pernah boleh: `gradle.properties` proyek, inline di perintah, atau
`export` di shell profile.

### 2026-09-09 · A19: gate symbol di CI tidak pernah berjalan — `env` step tidak terlihat oleh `if` step

Bug di wiring CI-nya sendiri, bukan di task-nya. Step symbol upload ditulis begini:

```yaml
- name: Symbol upload (release gate)
  if: github.event_name == 'push' && env.APM_SYMBOLS_URL != ''
  env:
    APM_SYMBOLS_URL: ${{ secrets.APM_SYMBOLS_URL }}
```

GitHub Actions mengevaluasi `if:` **sebelum** blok `env:` milik step itu diterapkan, jadi
`env.APM_SYMBOLS_URL` selalu kosong dan kondisinya selalu `false`. **Step-nya tidak akan pernah
berjalan**, bahkan setelah secret dipasang.

Untuk sebuah release gate itu bug terburuk yang mungkin: terlihat terkonfigurasi, menegakkan nol.
Persis kegagalan yang `01` §8 peringatkan — "tanpa symbol, crash report tidak terbaca, dan baru
ketahuan berminggu-minggu kemudian" — hanya saja penyebabnya di pipeline, bukan di upload.

Diperbaiki dengan memindahkan `env` ke **level job**, yang memang terlihat oleh `if:` step.
Ditambahkan juga step peringatan eksplisit yang menyala saat secret belum ada: gate yang diam-diam
dilewati tidak bisa dibedakan dari gate yang bekerja, dan tim yang melihat CI hijau akan
menyimpulkan symbol sudah terunggah.

**Pelajaran yang lebih umum, dan ini yang ketiga kalinya di proyek ini:** mekanisme kondisional yang
belum pernah terlihat *berjalan* tidak boleh dianggap bekerja. Sama seperti detektor StrictMode yang
tidak mendeteksi apa pun (A12) dan `verifyPublication` yang belum pernah gagal (A13) — keduanya
diberi kontrol negatif. Kondisi CI ini tidak bisa diberi kontrol negatif tanpa menjalankan CI-nya,
jadi gantinya: cabang "tidak terkonfigurasi" sekarang mengeluarkan output yang terlihat.

### 2026-09-11 · Remote control tidak pernah sampai ke device — dua cacat P0

Sesi pengujian di emulator dengan server ingest HTTPS lokal dan sertifikat terkontrol. Blokir rilis
terakhir (manual checklist no. 15) tertutup, dan dalam prosesnya dua cacat P0 muncul. Keduanya
lolos dari 337 unit test hijau, dan keduanya membuat fitur yang sudah ditandai ✅ tidak berfungsi.

**1. `RemoteConfigProvider.refresh()` tidak pernah dipanggil di kode produksi.**

Composition root hanya memasang `loadFromCache()`. Tidak ada satu pun pemanggil `refresh()`, jadi
SDK **tidak pernah menghubungi `/v1/config` sama sekali**. MOB-20 ("mengambil remote config saat
startup") tidak terpenuhi, dan MOB-21 mustahil: kill switch tidak bisa tiba kalau config tidak
pernah diambil. A10 ditandai ✅ dengan unit test yang menguji `refresh()` **secara terisolasi** —
test yang tidak pernah menanyakan apakah ada yang memanggilnya.

Sekarang di-refresh saat startup (thread latar, setelah graph terbit) **dan** sebelum tiap siklus
sync. Yang kedua penting: tanpa itu, app yang menyala berjam-jam tidak akan pernah melihat kill
switch — ia jadi setelan saat peluncuran, bukan kendali jarak jauh.

**2. Kill switch pinning tidak bisa dijangkau, secara konstruksi.**

`RemoteConfigFetcher` memakai `Call.Factory` yang sama dengan uploader. Saat pin berhenti cocok,
fetch config ikut ditolak — jadi `disabled_features: ["pinning"]` yang akan memulihkannya tidak
pernah tiba. Pemulihan menuntut rilis app baru: **persis blackout berminggu-minggu yang `02` §6.3
gambarkan, dan yang menjadi alasan proyek ini ada.**

Fetch config yang gagal sekarang dicoba ulang sekali lewat client **tanpa pin**. Client itu tetap
menegakkan TLS floor dan tetap tidak bisa cleartext (SEC-12); yang dilepas hanya pin-nya.
Paparannya sempit dan disengaja: penyerang dengan sertifikat dari CA yang sudah dipercaya device
bisa menyajikan config yang mematikan pinning — tapi penyerang yang sama toh bisa menyadap endpoint
ingest yang tidak ter-pin. Menukar itu dengan kill switch yang benar-benar bekerja adalah sisi
tawar yang lebih baik.

---

**Pola yang berulang, dan sekarang jadi aturan.** Ini kali keempat cacat hanya terlihat di device
(enkripsi Keystore, sensitivitas ANR, `consumer-rules.pro` kosong, dan sekarang remote control).
Benang merahnya bukan "Robolectric lemah" — melainkan **unit test menguji komponen, bukan apakah
komponen itu tersambung**. `refresh()` punya enam test dan nol pemanggil.

Aturannya: **sebuah feature belum ✅ kalau jalur yang dipakai pengguna belum pernah dijalankan
ujung-ke-ujung.** Untuk SDK ini artinya checklist device di `FEATURES.md`, bukan hanya
`verify.sh all`. Caranya ada di `TESTING-ON-DEVICE.md`, termasuk harness server ingest lokal di
`tools/device-testing/` supaya bisa diulang siapa pun.

**Catatan teknis dari sesi ini yang layak diingat:**

- **Emulator tanpa jaringan tervalidasi membatalkan semua WorkManager job** (`Constraints not met`).
  Dua percobaan sebelumnya menyimpulkan "SyncWorker tidak jalan" padahal itu lingkungannya. Periksa
  `dumpsys connectivity | grep IS_VALIDATED` sebelum menyalahkan SDK.
- **Upload memakai chunked transfer encoding.** `IngestUploader.gzip()` tidak meng-override
  `contentLength()`, jadi OkHttp mengirim tanpa `Content-Length`. Valid HTTP/1.1, tapi sebagian
  gateway dan WAF menolaknya, dan backend tidak bisa tahu ukuran di muka — relevan untuk `413` di
  `01` §7. Dicatat sebagai A26, tidak diubah dalam sesi ini.
- **Checklist 5 (pinning menolak pin salah) ternyata bisa diuji tanpa `okhttp-tls`** — cukup server
  HTTPS lokal dengan sertifikat yang kita pegang. Anggapan sebelumnya bahwa itu butuh dependency
  baru salah.

### 2026-10-02 · A28: a session belongs to the event, not to the envelope

`apm-backend` (`worker.go:352`) writes the **envelope's** `session_id` onto every event it contains, and
an envelope used to be stamped with the session current at *upload* time. Anything captured in one
session and uploaded in a later one — an offline backlog, a native crash or termination reported at the
next launch — was filed under the wrong session. Found by cross-checking the backend, not by any test.

**1. The session is fixed at capture and kept off the wire.** `Event.sessionId` is `@Transient`: it
travels in memory through the queue layers and is persisted by `JsonEventCodec` as
`{"event": {...}, "session_id": "..."}`, but is never serialized as part of the wire event — `01` §3
defines no per-event `session_id`, and adding one would be a contract change. Files written before this
are a bare event and are still read (their session is simply unknown); both shapes start with `{`, so the
encrypted-versus-plain detection is untouched. `SessionStampingEventQueue` sits outermost and stamps on
the caller's thread, before the async hop that could outlive a session; an event that already has a
session is never restamped.

**2. One envelope per session run.** `SyncEngine` uploads only the leading run of events that share the
first event's session and leaves the rest on disk for the next call (`Sent` already asks the caller to
continue immediately). The common single-session batch is still one request. A backlog spanning N
session changes costs N requests, which the per-install limiter (burst 25, 1 per 30 s) absorbs.
Contiguous runs, not grouping by session: events are in capture order, and reordering them would defeat
FIFO and `seq`.

**3. A crash or termination from the previous process is filed under the session that process was in.**
The current session id is written to `SharedPreferences` every time a session starts (`apply()`, off the
main thread's critical path), and `ApmBootstrap` reads it **before** creating its own. It is applied to
the termination event and to the **newest** native-crash exit record only — an older unreported crash
belongs to a session nobody remembers, and stays unrecorded (so it falls back to the current session)
rather than being given a wrong one. Consequence accepted: `apply()` is asynchronous, so a kill in the
instant after a session change can leave the session before it. That is still closer than the new
session, which is what was recorded before.

**Not fixed, and said so:** the same defect exists for every other envelope-level field — `app.version`
and `app.build`, `user_id` / `user_id_source`, and device/OS version. A native crash reported after an
app update is filed under the **new** version; a backlog captured while user X was signed in is uploaded
under whoever is signed in at send time. Recorded as A39, because the user-identity half is a privacy
question and needs a decision first.

`seq` for these events is still the reporter's counter (`01` §3's `type: crash` qualification) — the
crashed process's counter does not survive, so that part cannot be recovered and is documented, not fixed.

### 2026-10-02 · A29: the queue lives in `noBackupFilesDir`, because no host can be relied on to exclude it

SEC-07 requires the Android queue to be excluded from auto-backup. The first design kept it in `filesDir`
and shipped `apm_backup_rules.xml` for the host to merge. That never worked: the file excluded
`apm_queue/` while the directory was `apm-queue/` (no match), the SDK manifest did not reference it,
`INTEGRATION.md` never told adopters to merge it, and `DATA-INVENTORY.md` claimed the exclusion anyway. A
host with the default `allowBackup="true"` and no rules — the verification app — had its whole queue
eligible for cloud backup. Found by cross-reading the data inventory against the code.

**1. The queue and the cached remote config are stored under `noBackupFilesDir`** (`SdkStorage`). Android's
documentation: files there are "always excluded even if you try to include them" — a guarantee that holds
for every host without any action from it. The alternative (fix the path, document the merge) was rejected
because it depends on every adopter, which is exactly what failed.

**2. The SDK still does not set `allowBackup`, `fullBackupContent` or `dataExtractionRules`.** Same
reasoning as the cleartext decision (2026-09-07, A11): the manifest merger would push the setting onto the
host's whole app and override a policy that is theirs. Moving the files makes it unnecessary.

**3. A previous build's files are migrated, not abandoned.** `SdkStorage.migrateLegacy` runs in
`ApmBootstrap` before the cache and queue are opened. Stranding a queue would be silent loss of real,
unsent telemetry on every app update. Rename where the filesystem allows (it keeps the modification time
the queue orders itself by), copy-then-delete otherwise; never overwrite, never throw.

**4. A claim about the platform must be checked against the platform's own documentation before it goes
into a legal-facing document.** My first draft said the directory is also excluded from device-to-device
transfer; the official page only says Auto Backup. `DATA-INVENTORY.md` is what a team copies into a
regulator's form, so it now says exactly what the source says.

**Not decided here, recorded as A40:** the SDK's SharedPreferences file is still backup-eligible, so
`install_id` can survive a reinstall (contradicting `01` §2) and be cloned onto a second device.

### 2026-10-02 · A36: `sdk.name` stays `apm-android-sdk`; the backend adapts

Cross-checking `apm-ingest` found `LATEST_SDK_VERSIONS` keyed by `apmkit-ios` / `apmkit-android`, so the
backend's outdated-SDK lookup misses an SDK that sends `apm-android-sdk`. The owner decided: **Android
keeps `apm-android-sdk`.** This confirms the 2026-09-07 decision (which was made for a different reason —
resolving a conflict inside this repo) and answers the new question: the SDK does not change, the backend
table does. Reopening the name would also have meant a wire change for an SDK already published as 0.1.0.

The value is now a single constant, `SdkIdentity.NAME`, read by both `sdk.name` and the `X-APM-Sdk`
header, and pinned by a test. The needed backend edit is recorded in `FEATURES.md` A36; those repos are
read-only from here, so the SDK side is finished and the row stays 🟠 until the table is updated.

### 2026-10-02 · A40: the SDK's own state is kept out of backup too, because `install_id` is promised to reset

A29 moved the queue out of backup but left the SDK's SharedPreferences (`install_id`, generated user id,
latest session id, two crash-dedup markers) in `shared_prefs/`, which Auto Backup includes by default and
which cannot be relocated. `01` §2 says `install_id` resets with the install; with `allowBackup="true"` it was
instead restored after a reinstall and copied onto a second device, so one `install_id` could be live on two
devices — sharing the backend's per-install rate-limit bucket (burst 25) and overwriting each other's
cumulative `sdk.health` row. The three options were: document it, move the state, or ask every host to merge
backup rules. The last is the one that already failed in A29, so the state moved.

**1. The state lives in `noBackupFilesDir/apm-state.json`** behind `NoBackupPrefs`, a `SharedPreferences`
implementation over an atomic JSON file. Implementing the platform interface kept every consumer unchanged.
No host-supplied value is stored — `setUser` is memory-only — so the file holds nothing personal.

**2. Older values migrate only on a genuine update.** On a fresh install, whatever is in `shared_prefs/` came
from a backup restore, and importing it would recreate the clone. A genuine update is `lastUpdateTime` more
than 60 s after `firstInstallTime`; when that is unknown, nothing is imported. The failure modes of the
heuristic are both on the safe side (a restored id carried over = status quo; an update resetting the id once
= a new `sdk.health` row), and neither loses telemetry. The old file is always emptied so the cloud copy stops
holding an `install_id`.

**3. Rejected: fixing it with a rules file for the host.** A29 showed a host-merged file does not get merged.

**Unverified, recorded as a checklist item:** that an install created by a backup restore has
`firstInstallTime` ≈ `lastUpdateTime`. The behaviour is documented nowhere I found; the design degrades safely
if it is wrong.

### 2026-10-02 · A39: app version and OS are fixed at capture like the session; `user_id` is not, pending a privacy decision

The A28 defect was general: the backend applies one envelope's `app`, `device` and `session_id` to every event in
it, and an envelope is built at upload time. After A28 fixed `session_id`, the same applied to `app.version`/
`app.build` and `device.os_version`: a native crash reported after an app update was filed under the *new*
version, so "is this crash fixed in the new build" read wrongly, and the backend groups issues by version.

**1. Same mechanism as A28.** `Event.appVersion/appBuild/osVersion` are `@Transient` (never on the wire),
persisted by the codec, stamped at capture by `SessionStampingEventQueue` (each fact filled on its own, never
overwriting one an event already carries), and `SyncEngine` sends one envelope per run of equal
`Event.originKey` (session, version, build, OS). Recovered crashes and terminations use the values persisted by
the previous start. A normal single-run batch is still one request.

**2. `user_id` / `user_id_source` is deliberately excluded.** Recording it per event means persisting the raw
host-supplied value — a phone number or email — on disk for the first time. Today it is memory-only, and the
queue is plaintext whenever the Keystore is unavailable. That would cut against *Capture → Scrub → Disk*,
SEC-06, and what `DATA-INVENTORY.md` tells adopters for their Data Safety form. It is an owner decision, not an
implementation detail; the options and a recommendation are in `FEATURES.md` A39. Until it is made, `user_id`
remains the identity at upload time, and that limit is stated in `DATA-INVENTORY.md`.

**3. Not stamped, and why:** `device.model`, `locale`, `timezone` rarely change and do not drive grouping;
`integrity` is a per-session snapshot whose volatile members (`debugger_attached`, `is_dev_mode`) are low value
across processes. Revisit only if a consumer starts relying on them.

### 2026-10-02 · A39/A41 (decision): `user_id` is attributed by identity epoch, and the current host id is stored once

This **supersedes point 2 of the earlier 2026-10-02 A39 entry**, which left `user_id` out pending a decision. Having
read the iOS SDK (it persists the raw host id in `UserDefaults`, and builds every envelope field at upload), the
owner approved the recommendation.

**1. Events record an *identity epoch*, never the `user_id`.** A counter that rises each time the identity
*changes* (`setUser` with a different value, or `clearUser()`). `Event.identityEpoch` is `@Transient` and
persisted by the codec like the session; at upload `SyncEngine` turns it back into an identity through
`UserIdProvider.identityFor`. **The rule: an event goes out under the user signed in when it was captured, or as
anonymous — never under a user who was not.** An era the process cannot identify (an older one after a restart)
is sent as the generated id with `user_id_source: generated`.

**2. The current host id is stored once, in the no-backup state** — encrypted with the queue's Keystore cipher
(`e1:`), or as a marked plaintext (`p1:`) when there is none, the same trade-off as the queue (2026-09-07, A14).
This is the first time a host-supplied value is written to disk, and it is a deliberate, bounded exception to
"the raw `user_id` lives only in the envelope slot" (SEC-06): one value, never per event, never in a breadcrumb,
log or other field, never in a backup, and `DATA-INVENTORY.md` / `INTEGRATION.md` now say so. It also fixes
A41 (`setUser` is no longer lost on restart) and is the baseline the next `setUser` is compared against, so a
restart is not an identity change.

**3. `APM.clearUser()` is a public API addition.** There was no way to say "signed out", and without it activity
after logout would keep being attributed to the last user — the error this removes. Hosts are asked to call it.

**4. Rejected: recording the raw `user_id` on every event** (wide PII footprint on disk), and **leaving it
as is** (a wrong-person attribution error, which is worse than an anonymous one).

**5. Where a test cannot prove a link, the compiler must.** The native reporter runs on a background thread after
the graph is published, so a host `setUser` can race it; no deterministic test distinguishes a forgotten
`crashedIdentityEpoch` from a correct one. It is therefore a required parameter, with no default. (The
termination's copy was removed instead: at that point current equals previous by construction.)

### 2026-10-02 · A38: Android records no headers and no query string; iOS's `req_headers` is a spec gap, not a requirement

Comparing with APMKit found `req_headers`/`res_headers` on iOS network events and the query's parameter names
inside `path`. They are deliberate (an owner-directed amendment, 2026-08-24, so SEC-02/03 would have live data to
enforce) but `01` defines no such fields, and **nothing reads them** — not `apm-backend`, `apm-ingest`,
`apm-backoffice` or apm-docs.

**Decision (recommended, awaiting the owner's confirmation): Android does not capture request/response headers or
the query string at all.** SEC-02 and SEC-03 forbid *recording* `Authorization`, `Cookie`, custom headers and
query values; recording nothing meets them in the strongest way and keeps the egress surface minimal. The
allowlisted headers and the query names would leave the device for no consumer, and `Content-Length` duplicates
`req_bytes`/`res_bytes`. This is enforced by a test that drives real requests carrying secrets and asserts none
reaches the serialised event.

**Revisit only if the spec formalizes the fields** (`01` §4.1/§4.2: names, which events, the allowlist, whether
query names belong in `path`). Until then the two platforms differ in what they send, and
`DATA-INVENTORY.md` says so instead of claiming the wire output is identical.

### 2026-10-05 · Code-review fixes: what changed in earlier decisions

A review of the uncommitted work found ten defects. Most were bugs; four amend a decision recorded above.

**1. Identity epochs are random, and an unknown epoch is anonymous (amends 2026-10-02 A39/A41).** The earlier rule
"an epoch newer than the current one is the current identity" violated the rule it was written to protect: a
switch whose asynchronous state write never landed (process killed) left events stamped with an epoch the next run
did not know, and they were uploaded as the previous user. And a counter can repeat after a lost write, handing a
later user an earlier backlog. Epochs are now random, `identityFor` returns the generated id for any epoch it
does not know, and nothing is ever attributed to the "current" user by default.

**2. Stored identity fails closed (amends the same entry).** "Encrypted with the queue's cipher, plaintext
(`p1:`) when there is none" had a hole: a cipher that existed but failed once stored the raw value readable, a
weaker rule than the queue's (an event that cannot be encrypted is not written). Plaintext is now used only when there
is no cipher at all; a cipher failure stores nothing. Encryption and the write also run on a background thread,
never on the caller's (the Keystore is a blocking binder call; `setUser` is called from the main thread).

**3. The unpinned config fallback is narrowed (amends 2026-09-11, A24 point 2).** It retried on any failure and
its stated reason — "the same attacker could intercept an unpinned ingest endpoint anyway" — is false when pinning is
on. Now: it runs only after a genuine pin rejection; the config it returns is honoured only for "switch pinning
off", in memory, never cached (a restart retries the pinned path). **Residual, stated plainly:** an attacker with a
certificate from a trusted CA can still force a pin rejection and switch pinning off for the remainder of that process.
Whether to keep the fallback at all is an owner decision; until then the exposure is bounded, not removed.

**4. State migration and writes are idempotent and serialised (amends 2026-10-02 A40).** The migration never
overwrites an existing key, empties the legacy file synchronously and writes its marker last and durably; state
writes are serialised across threads and instances; and the "previous process" markers are written only after a start
has fully succeeded, so a retried start cannot mistake its own markers for the real previous process.

**5. "Supported API" is defined (RELEASING.md).** Exactly what `consumer-rules.pro` keeps. Everything else is
implementation and is not covered by semver. Making the types `internal` is tracked as A43.

**6. The CI release token is step-scoped.** Moving it to job level (to make an `if:` work) exposed it to every step,
including third-party actions. The condition now reads a non-secret job-level flag; the token is in the one step
that uploads symbols.

### 2026-10-06 · Second review pass: a restore is not an update, for the queue as well

**1. Old queue files migrate only on a genuine update (extends 2026-10-02 A29/A40).** `SdkStorage.migrateLegacy`
moved the old `filesDir/apm-queue` and config cache unconditionally, while the key-value state refused the same
data on a fresh install. Those old files were backup-eligible, so on a reinstall or restore they are a *backup's*
copy of some earlier queue; moving them in uploaded them under the new `install_id`. Now one rule covers both:
when the install times positively say "installed just now" the old files are **deleted** (the cloud-eligible
copy must not linger); when the platform will not say they are **moved**, because dropping real telemetry
over a failed lookup is the worse mistake. The package manager is not asked at all once nothing legacy remains.
*Decision taken (the recommended option) — correct me if you would rather send restored events.*

**2. The identity write is handed off under the lock; the remaining window is documented (extends 2026-10-05 §1).**
Two concurrent `setUser`/`clearUser` calls could enqueue their writes in the opposite order and leave the older
identity on disk. The hand-off now happens inside the lock (`execute` only enqueues). What stays: the stored identity
trails memory by the writer's latency, so a process killed inside that window recovers its crash under the previous
identity. Closing it needs a blocking Keystore call on the caller's thread; it is recorded in `UserIdProvider`'s
KDoc instead of being paid for.

**3. The debug test certificate is generated, not committed.** `app/src/debug/res/raw/apm_local_test_ca.pem` was a
30-day certificate that expired within days and left a trust anchor in the repository. `generateLocalTestCa`
(`app/build.gradle.kts`, debug variants only) copies `tools/device-testing/server.crt` if present, otherwise makes a
throwaway certificate and discards its key. `server.key`, `server.crt` and `received.json` are git-ignored.

