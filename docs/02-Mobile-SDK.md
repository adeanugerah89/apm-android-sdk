# 02 — Mobile SDK (iOS & Android)

**PRD APM Kit** · Untuk tim Mobile

> Konteks proyek: `00-Overview.md`
> Schema event & kontrak API: `01-Kontrak-Data-API.md` — **jangan disalin ke sini, referensikan saja**

---

## 1. Yang Dibangun Tim Ini

SDK yang di-embed ke aplikasi iOS & Android, bertugas menangkap event, menyimpannya secara lokal, dan mengirimkannya ke backend saat kondisi memungkinkan.

```
┌──────────────────────────────────────────┐
│ Capture Layer                            │
│ network · crash · error manual · crumbs  │
├──────────────────────────────────────────┤
│ Scrubber            ← redaksi PII        │
├──────────────────────────────────────────┤
│ Disk Queue          ← atomic write       │
├──────────────────────────────────────────┤
│ Sync Engine         ← batch + backoff    │
└──────────────────────────────────────────┘
```

**Aturan urutan yang tidak boleh dibalik:** Capture → Scrub → **Disk** → Sync. Penulisan ke disk terjadi sebelum operasi network apa pun. Ini inti dari prinsip *write local first*.

---

## 2. Ketergantungan Lintas Tim

| Yang tim ini **butuhkan** | Dari | Kapan |
|---|---|---|
| Endpoint ingestion aktif (walau minimal) | Backend | M1 |
| Kunci app per aplikasi | Backend | M1 |
| Endpoint `GET /v1/config` untuk kill switch & sampling | Backend | M2 |
| Endpoint upload symbol + integrasi CI | Backend | M3 |

| Yang tim lain **butuhkan** dari tim ini | Untuk | Kapan |
|---|---|---|
| Payload sesuai `01-Kontrak-Data-API.md` §2–4 | Backend | M1 |
| Path yang sudah dinormalisasi (SEC-03b) | Backend — kualitas fingerprinting | M1 |
| `binary_images` + UUID pada crash | Backend — symbolication | M3 |
| `failure_category` konsisten iOS & Android | Frontend — filter & grouping | M2 |
| Flag `integrity` per sesi (emulator/root/dev-mode) | Frontend — filter & exclude sesi non-real | M3 |
| Dokumen data inventory (SEC-21) | Semua tim adopter — pengisian privacy label | M5 |

---

## 3. Requirement Fungsional

> Berlaku untuk kedua platform kecuali ditandai khusus. **Perilaku yang terlihat dari luar — schema, kategorisasi, kontrak response — wajib identik di iOS & Android.**

### 3.1 Network Observability — Fase 1

**Cakupan (koreksi 2026-09-29):** seluruh requirement di seksi ini berlaku **hanya** untuk request yang melewati `URLSession` hasil `APM.instrumentedSession(...)`. Request lewat `URLSession.shared` atau session lain milik aplikasi host **tidak tertangkap**, dan tidak ada jalur cadangan. "Tiap request" di MOB-02b dan requirement lain di bawah dibaca dengan cakupan ini sebagai konteksnya — bukan seluruh trafik aplikasi.

| ID | Requirement | Prio |
|---|---|---|
| MOB-01 | Menangkap metrik HTTP request: URL, method, status, durasi, breakdown per fase (DNS/TCP/TLS/TTFB) | P0 |
| MOB-02 | Menangkap kegagalan network dan memetakannya ke `failure_category` (`01` §5) | P0 |
| MOB-02b | Periksa `status_code` tiap request yang selesai: selalu emit event `network`; jika status ≥ 400, tambahan emit `network_failure` dengan `failure_category = http_error` + `status_code` (aturan lengkap `01` §4.2). `http_error` tidak dihitung sebagai kegagalan transport. | P0 |
| MOB-03 | Membedakan penolakan pinning kustom dari pembatalan request biasa | P0 |
| MOB-10 | Traffic SDK sendiri dikecualikan dari instrumentasi (mencegah loop tak berujung) | P0 |

> **Kenapa penangkapan network di iOS bersifat per-session, bukan menyeluruh (MOB-01).** Fakta teknis: `URLSessionTaskDelegate` — mekanisme yang dipakai SDK ini — secara teknis hanya dapat menempel pada `URLSession` yang dibuat sendiri lewat inisialisasi eksplisit. Menangkap seluruh trafik aplikasi menuntut `URLProtocol` kustom atau method swizzling, yang keduanya merupakan interception global. **Pertanyaan terbuka:** tidak ada catatan di repo yang menjelaskan apakah mekanisme per-session ini dipilih justru untuk menghindari interception global — sejalan dengan penolakan swizzling di MOB-12 — atau memang tidak pernah diputuskan secara eksplisit sebagai perbandingan trade-off.
>
> Konsekuensinya paralel dengan MOB-12: cakupan data network bergantung pada disiplin aplikasi host memakai `APM.instrumentedSession()` untuk request-nya, sama seperti cakupan crumb navigasi MOB-12 bergantung pada host memanggil `recordScreen(_:)`. Dua keputusan ini menghasilkan trade-off yang sama. **Request yang tidak muncul di Network Explorer belum tentu bug SDK** — bisa jadi request itu memang tidak pernah lewat session ber-instrumen.

**Catatan implementasi:**
- **iOS:** `URLSessionTaskMetrics` memberi breakdown per fase, termasuk `secureConnectionStartDate`/`EndDate`. Handshake yang dimulai tapi tak pernah selesai = gagal di fase TLS.
- **Android:** pakai `EventListener` OkHttp, bukan hanya `Interceptor`. `EventListener` mengekspos `secureConnectStart`/`secureConnectEnd` dan `callFailed`, sehingga fase kegagalan bisa diketahui.

### 3.1b Integrasi networking library pihak ketiga (Alamofire, dan sejenisnya) — C8 Tahap A

`APM.instrumentedSession(...)` (§3.1) mengasumsikan aplikasi host membuat `URLSession`-nya sendiri secara langsung. Sebuah networking library yang membuat `URLSession`/delegate-nya sendiri secara internal — Alamofire adalah contoh nyata di app pilot — tidak bisa dipasangi `instrumentedSession` sama sekali (`00-Overview.md` §11 mencatat gap ini).

**Keputusan (C8 Tahap A, CONSTITUTION.md 2026-10-12, APMKit): APMKit TIDAK bergantung pada Alamofire dalam bentuk apa pun** — bukan sebagai dependency inti, bukan juga sebagai produk SPM/subspec CocoaPods terpisah di package yang sama. APMKit sebagai gantinya membuka API publik, `APM.reportNetworkTask(_:metrics:error:failureHint:sink:sessionManager:ingestEndpoint:additionalExcludedHosts:)`, yang menerima `URLSessionTask`/`URLSessionTaskMetrics?`/`Error?` apa pun — tanpa peduli library mana yang menghasilkannya. Adapter Alamofire (atau library lain) **tinggal di aplikasi host**, bukan di APMKit.

Pemetaan event yang dihasilkan **identik** dengan `instrumentedSession`'s `NetworkCaptureDelegate` — keduanya memanggil implementasi yang sama (`NetworkEventBuilder`), termasuk aturan "tiap respons ≥400 menghasilkan event `network` DAN `network_failure(http_error)`" (§3.1 MOB-02b) dan klasifikasi penolakan pinning (MOB-03). Kalau sebuah task pernah ditangani lewat sesi hasil `instrumentedSession`, panggilan manual untuk task yang SAMA menjadi no-op — mencegah emisi ganda bila kedua jalur pernah terpasang bersamaan secara tidak sengaja.

**Snippet final — adapter `EventMonitor` Alamofire (C8 Tahap B; thread-safety dan pemetaan `.explicitlyCancelled` diperbaiki Tahap B.2; queue dipisah dari main thread Tahap B.3).** Kode di bawah ini **sudah diverifikasi kompilasinya** terhadap app pilot yang sesungguhnya (`edts-klikidm-ios`, branch `testing/apmkit`, APMKit dipasang via local SPM package override, `xcodebuild -scheme EDTSCode-dev build` → `BUILD SUCCEEDED`, 4 target app: `EDTSCode`/`EDTSCode-dev`/`EDTSCode-staging`/`EDTSCode-to`). Ini bukan referensi bentuk — ini file yang berjalan di `EDTSCode/Cores/Utils/Alamofire/APMNetworkEventMonitor.swift`, dipasang ke satu-satunya `Session` Alamofire aplikasi (`AFSession.swift`'s `eventMonitors:`):

**Penting — `queue` HARUS di-override, jangan dibiarkan default.** `EventMonitor`'s `queue` bernilai `DispatchQueue.main` secara default kalau conformer tidak men-override-nya (properti protokol di Alamofire, dengan implementasi default lewat protocol extension yang mengembalikan `.main`) — artinya TANPA override, seluruh kerja scrub-dan-report adapter ini untuk SETIAP request yang selesai akan berjalan di main thread, bersaing dengan kerja UI pada satu-satunya thread yang tidak boleh diblokir aplikasi. Adapter ini men-override `queue` dengan `DispatchQueue` serial milik sendiri (label jelas, `qos: .utility`), memindahkan seluruh callback-nya keluar dari main thread.

**Konsekuensi: `APMInstance` HARUS diambil lewat holder yang thread-safe, bukan `UIApplication`.** Karena callback sekarang genuinely berjalan di queue adapter sendiri (bukan main thread), `UIApplication.shared.delegate` (main-thread only) tidak aman dipakai di sini. Holder `NSLock`-guarded (`APMInstanceHolder`, lihat `EDTSCode/Cores/Utils/Alamofire/APMInstanceHolder.swift`) menyelesaikan ini: `AppDelegate` memanggil `set(_:)` TEPAT SATU KALI, sinkron, langsung setelah `APM.start()` kembali (masih di main thread); adapter memanggil `get()` dari queue-nya sendiri. Race-free karena setiap baca/tulis lewat lock yang sama — callback yang datang sebelum `set(_:)` dipanggil hanya melihat `nil` dan no-op, tidak pernah melihat state yang baru separuh terbentuk, karena `set(_:)` hanya dipanggil dengan nilai `APM.start()` yang sudah sepenuhnya kembali.

**`pendingMetrics` tidak lagi perlu lock sendiri.** `CompositeEventMonitor` Alamofire mengirim setiap callback `EventMonitor` lewat `monitor.queue.async { ... }` — karena `queue` di atas serial (bukan `.concurrent`), paling banyak satu callback utk monitor INI yang berjalan sekaligus, berurutan sesuai urutan Alamofire memanggilnya. `didGatherMetrics` untuk suatu task selalu selesai total sebelum `requestDidFinish` untuk task yang SAMA dimulai — jadi `NSLock` yang dulu melindungi `pendingMetrics` sudah tidak diperlukan.

```swift
import Alamofire
import APMKit
import Foundation

/// Hidup di aplikasi host, BUKAN di APMKit — lihat keputusan di atas.
final class APMNetworkEventMonitor: EventMonitor {
    // Default EventMonitor.queue adalah DispatchQueue.main — tanpa override ini, kerja
    // scrub-dan-report untuk SETIAP request yang selesai akan berjalan di main thread,
    // bersaing dengan UI. Queue serial khusus memindahkannya keluar dari main thread.
    let queue = DispatchQueue(label: "com.edts.klikidm.apmNetworkEventMonitor", qos: .utility)

    // Sekarang genuinely di luar main thread (lihat `queue` di atas) — APMInstance TIDAK
    // boleh diambil lewat UIApplication.shared.delegate (main-thread only). APMInstanceHolder
    // diisi sekali di main thread tepat setelah APM.start(), dan dibaca di sini lewat lock
    // yang sama (lihat APMInstanceHolder.swift untuk alasan ini race-free).
    private func resolveAPMInstance() -> APMInstance? {
        APMInstanceHolder.shared.get()
    }

    // Disentuh HANYA dari `queue` di atas: CompositeEventMonitor Alamofire mengirim tiap
    // callback EventMonitor lewat `monitor.queue.async { ... }`, dan `queue` serial (bukan
    // .concurrent) — paling banyak satu callback untuk monitor ini berjalan sekaligus,
    // berurutan, jadi didGatherMetrics untuk satu task selalu selesai sebelum
    // requestDidFinish untuk task yang sama dimulai. Tidak perlu lock.
    private var pendingMetrics: [Int: URLSessionTaskMetrics] = [:]

    func request(_ request: Request, didGatherMetrics metrics: URLSessionTaskMetrics) {
        if let task = request.task {
            pendingMetrics[task.taskIdentifier] = metrics
        }
    }

    func requestDidFinish(_ request: Request) {
        guard let task = request.task, let apm = resolveAPMInstance() else { return }
        let metrics = pendingMetrics.removeValue(forKey: task.taskIdentifier)

        guard let afError = request.error?.asAFError else {
            apm.reportNetworkTask(task, metrics: metrics, error: request.error)
            return
        }

        switch afError {
        // MOB-03: ServerTrustManager yang menolak pinning kustom menghasilkan
        // AFError.serverTrustEvaluationFailed, bukan domain NSURLErrorDomain biasa — SATU-
        // SATUNYA kasus yang perlu petunjuk eksplisit (NetworkFailureHint).
        case .serverTrustEvaluationFailed:
            apm.reportNetworkTask(task, metrics: metrics, error: afError, failureHint: .certificatePinningRejected)

        // .validate() Alamofire menolak respons — transport sudah selesai (task.response
        // adalah HTTPURLResponse asli). Teruskan nil supaya status code asli di task yang
        // menentukan, bukan error wrapper ini (lihat §3.1 MOB-02b). App pilot tidak memakai
        // .validate() di manapun (terverifikasi lewat grep) — kasus ini tidak bisa terjadi
        // di app itu hari ini, tapi tetap ditulis untuk kelengkapan kontrak.
        case .responseValidationFailed:
            apm.reportNetworkTask(task, metrics: metrics, error: nil)

        // Perluasan dari alasan yang sama seperti .responseValidationFailed, bukan instruksi
        // literal: kegagalan deserialisasi body HANYA terjadi setelah HTTPURLResponse asli
        // sudah diterima — tetap bukan kegagalan transport.
        case .responseSerializationFailed:
            apm.reportNetworkTask(task, metrics: metrics, error: nil)

        // AFError.underlyingError mengembalikan retryError (kegagalan retrier itu sendiri)
        // untuk kasus ini, BUKAN originalError (kegagalan jaringan yang memicu retry).
        // Teruskan originalError secara eksplisit — memakai underlyingError di sini akan
        // salah melaporkan bug retrier sebagai kegagalan jaringan.
        case let .requestRetryFailed(_, originalError):
            apm.reportNetworkTask(task, metrics: metrics, error: originalError)

        // Masing-masing kasus ini membungkus Error asli yang bermakna (dikonfirmasi dari
        // AFError.underlyingError) — teruskan itu supaya error_domain/error_code (01 §4.2)
        // tetap bermakna, bukan error wrapper Alamofire.
        case .sessionTaskFailed, .createUploadableFailed, .createURLRequestFailed,
             .downloadedFileMoveFailed, .requestAdaptationFailed, .multipartEncodingFailed,
             .parameterEncodingFailed, .parameterEncoderFailed, .sessionInvalidated:
            apm.reportNetworkTask(task, metrics: metrics, error: afError.underlyingError ?? afError)

        // Pembatalan Alamofire sendiri tidak pernah menyentuh jaringan — AFError.underlyingError
        // nil di sini, tapi BERBEDA dari tiga kasus di bawah: ada padanan NSURLErrorDomain
        // yang nyata untuk "cancelled" — task URLSession native yang dibatalkan gagal dengan
        // error yang persis ini. Meneruskannya (bukan AFError mentah, yang domain bridge-nya
        // "Alamofire.AFError") membuat FailureCategoryMapper mengklasifikasikannya sebagai
        // .cancelled, bukan jatuh ke .unknown — konsisten dengan bagaimana pembatalan akan
        // dilaporkan kalau Session ini tidak punya lapisan Alamofire sama sekali. (Tahap B.2:
        // app pilot dikonfirmasi lewat grep TIDAK PERNAH memanggil .cancel() pada request
        // Alamofire di manapun — kategori `cancelled` baru relevan kalau itu berubah nanti.
        // Baik readapi.py maupun apm-backend menghitung SEMUA baris network_failure ke
        // network_failure_rate tanpa filter failure_category — cancelled ikut terhitung.)
        case .explicitlyCancelled:
            apm.reportNetworkTask(task, metrics: metrics, error: URLError(.cancelled))

        // Tidak ada transport yang pernah terjadi, dan AFError.underlyingError bernilai nil
        // untuk ketiga kasus ini — teruskan AFError itu sendiri supaya FailureCategoryMapper
        // tetap menerima sesuatu untuk diklasifikasi (otomatis jatuh ke .unknown untuk error
        // di luar NSURLErrorDomain, tidak pernah crash).
        case .sessionDeinitialized, .invalidURL, .urlRequestValidationFailed:
            apm.reportNetworkTask(task, metrics: metrics, error: afError)
        }
    }
}

// Pemasangan, di tempat Session Alamofire aplikasi dibuat (app pilot: AFSession.swift):
// eventMonitors: [AFLogger(), APMNetworkEventMonitor()]
```

**`APMInstanceHolder`** — holder thread-safe yang dirujuk `resolveAPMInstance()` di atas:

```swift
import APMKit
import Foundation

final class APMInstanceHolder {
    static let shared = APMInstanceHolder()

    private let lock = NSLock()
    private var instance: APMInstance?

    private init() {}

    func set(_ instance: APMInstance) {
        lock.lock(); defer { lock.unlock() }
        self.instance = instance
    }

    func get() -> APMInstance? {
        lock.lock(); defer { lock.unlock() }
        return instance
    }
}

// Di AppDelegate, TEPAT SATU KALI, langsung setelah APM.start() kembali:
// apm = APM.start(configuration: ...)
// APMInstanceHolder.shared.set(apm)
```

**Batasan build Simulator yang tidak terkait APMKit.** Build Simulator app pilot di Apple
Silicon gagal link secara native-arm64 karena pod `BPLive` (`1.64.0`) hanya menyertakan slice
arm64 untuk **device** iOS, bukan untuk **Simulator** iOS, di dalam `.a` fat binary yang sama
(`lipo -info` tidak membedakan ini — hanya pesan linker `ld: building for 'iOS-simulator', but
linking in object file ... built for 'iOS'` yang menunjukkannya). Ini pra-eksisting dan tidak
berhubungan dengan APMKit maupun adapter ini — workaround build-nya: paksa arsitektur Simulator
ke `x86_64` (`ARCHS=x86_64 EXCLUDED_ARCHS=arm64`, berjalan lewat Rosetta), atau jalankan di Mac
Intel, sampai `BPLive` menyertakan slice arm64-simulator.

**Tidak ditangani di Tahap A/B, dicatat sebagai parked item** — lihat `FEATURES.md` APMKit butir 20(C)/20(D): breakdown per-path/per-method belum terbaca sisi backend manapun (bukan masalah SDK — data sudah tertulis), dan `PathNormalizer` belum mengenali slug/token non-numerik (ITEM 20(C) SUDAH DITUTUP — lihat Decisions 2026-10-12).

### 3.2 Penyimpanan Lokal — Fase 1

| ID | Requirement | Prio |
|---|---|---|
| MOB-04 | Menulis setiap event ke disk secara **atomic** sebelum operasi network apa pun | P0 |
| MOB-05 | Antrean bertahan melewati proses mati, force quit, dan device restart | P0 |
| MOB-06 | Batas antrean: maks 20 MB **atau** 5.000 event; eviction FIFO saat penuh | P0 |
| MOB-14 | Scrubbing dijalankan sebelum penulisan ke disk — detail lengkap di §6.1 | P0 |

### 3.3 Pengiriman — Fase 1

| ID | Requirement | Prio |
|---|---|---|
| MOB-07 | Batch dengan retry & exponential backoff sesuai kontrak response (`01` §7) | P0 |
| MOB-08 | Trigger upload: timer periodik, transisi ke background, dan pemulihan koneksi | P0 |
| MOB-09 | Event lokal **hanya dihapus setelah response sukses** dari server | P0 |

> Poin MOB-09 adalah yang membuat jaminan *at-least-once* berlaku. Menghapus sebelum ACK berarti kehilangan data setiap kali koneksi putus di tengah upload.

### 3.4 API untuk Developer — Fase 1

| ID | Requirement | Prio |
|---|---|---|
| MOB-11 | API publik: `logError(error, context)` dan `breadcrumb(message, category)` | P0 |
| MOB-11b | `logError` mengisi `source_file` / `source_function` / `source_line` secara otomatis dari lokasi pemanggilan (default parameter `#fileID` / `#function` / `#line` di iOS). **Wajib `#fileID`, bukan `#file`** — `#file` membocorkan path mesin build beserta nama pengguna. Developer tidak perlu mengisi apa pun. | P0 |
| MOB-12 | Breadcrumb otomatis untuk **lifecycle app** dan **perubahan konektivitas** (benar-benar otomatis, tanpa aksi app host). Konektivitas dicatat hanya saat keadaan benar-benar berubah (`connectivity_lost`, `connectivity_restored`, `connectivity_changed_to_<wifi|cellular|wired|other>`); laporan pertama saat start bukan perubahan (online saat launch tidak menulis apa-apa), perubahan beruntun dalam 5 detik digabung, dan breadcrumb konektivitas paling banyak separuh dari ring buffer maupun separuh dari jatah laporan crash (0.0.16). Untuk **perpindahan screen**: di iOS bersifat *host-invoked* — SDK menyediakan primitive `recordScreen(_:)` plus helper opt-in (base class UIViewController & view modifier SwiftUI), **tanpa method swizzling**. Di Android perpindahan screen bisa otomatis via `ActivityLifecycleCallbacks`. | P0 |
| MOB-13 | Ring buffer breadcrumb: 100 entri terakhir, dilampirkan ke setiap crash/error | P0 |
| MOB-26 | Mode debug: log lokal berisi event yang tertangkap, non-aktif di build release | P1 |
| MOB-28 | API `setUser(id)` untuk app host menetapkan `user_id` (string bebas). Jika tidak di-set, generate ID acak stabil per install sebagai fallback. `user_id` dikirim mentah di envelope; hashing dilakukan backend (BE-21). **Sertakan `user_id_source`** (`host` \| `generated`, `01` §2.2) — tanpa itu aplikasi yang lupa memanggil `setUser` tidak dapat dibedakan dari yang memanggilnya. **`clearUser()`** (0.0.16) menghapus id eksplisit dan kembali ke ID acak per install; event sesudahnya tidak membawa user lama. `setUser` dengan string kosong atau hanya whitespace **diabaikan** (log debug di build debug). User dicatat **saat event terjadi**, bukan saat upload — lihat blok di bawah. | P0 |

> **Atribusi user (MOB-28, 0.0.16) — dicatat saat event terjadi.** Sebelum 0.0.16 SDK menempelkan user ke envelope *saat upload*, sehingga event yang ditangkap sebelum `setUser(B)` ikut terkirim atas nama B (atau sebaliknya: event sebelum login ikut tercatat atas nama user yang login sesudahnya). Sekarang:
>
> - Setiap event membawa user yang berlaku **saat ia ditangkap**, disimpan di file antrean (`QueuedEvent.user`: `id` + `source`). Itu **bukan** atribut event dan **tidak** dikirim per event: kontrak wire tetap `user_id`/`user_id_source` di level envelope (`01` §2). Saat upload SDK mengelompokkan event per **(session_id, user)**; satu sesi dengan user A lalu B menjadi dua envelope.
> - **Crash:** user diambil dari sesi yang crash, bukan dari launch berikutnya. SDK menyimpan `{id, source}` di user-info KSCrash bersama data sesi (satu key `user_identity`, selalu diperbarui oleh `setUser`/`clearUser`). Bila tidak ada (laporan dari SDK lama, sidecar rusak, atau id terlalu panjang untuk muat 1024 byte), crash dikirim **tanpa `user_id`** — tidak pernah dengan user launch ini. Backend menyimpannya sebagai `user_ref` null.
> - **Antrean lama:** file antrean yang ditulis SDK ≤ 0.0.15 tidak punya catatan user. Event itu tetap terbaca dan memakai perilaku lama (user saat upload). Itu terjadi sekali, saat pembaruan SDK, untuk event yang belum sempat terkirim.
> - Biaya ruang: +40 byte (id host), +74 byte (id generate), +10 byte (tanpa user) per file antrean, terukur pada record 276 byte. Satu envelope tambahan muncul hanya bila user benar-benar berganti dalam satu antrean — hitungannya masuk anggaran rate seperti envelope biasa.
>
> **Panduan integrasi untuk app host:**
>
> 1. Panggil `APM.setUser(id:)` **setelah login berhasil** dan **saat sesi dipulihkan** (app dibuka kembali dengan token tersimpan). Tanpa pemanggilan kedua, launch yang memulihkan sesi tercatat dengan ID generate sampai user login lagi.
> 2. Panggil `APM.clearUser()` **saat logout**. Tanpa itu, event user berikutnya yang memakai app yang sama (atau event sebelum login berikutnya) masih tercatat atas nama user lama.
> 3. **Jangan mengirim nilai bawaan** seperti `"0"`, `"-1"`, `"null"`, atau `"guest"` sebagai penanda "belum login". Itu bukan user: semua tamu menjadi satu `user_ref` yang sama, dan User Lookup menampilkannya sebagai satu orang. Sebelum login, **jangan panggil `setUser`**; setelah logout, panggil `clearUser()`. SDK hanya menolak string kosong/whitespace; nilai "0" lolos validasi (SEC-06) dan menjadi tanggung jawab app host.
>
> **Kenapa screen tracking di iOS tidak "otomatis" (MOB-12).** Satu-satunya cara membuatnya benar-benar otomatis di iOS adalah *method swizzling* `UIViewController.viewDidAppear`. Itu ditolak: teknik ini mengganti implementasi method sistem saat runtime, berisiko crash/undefined behavior, dan **bentrok dengan SDK lain yang melakukan hal sama** (Firebase Analytics sudah men-swizzle method ini di aplikasi kita). Karena SDK ini dipasang di aplikasi tim lain, kegagalan seperti itu akan dilimpahkan ke kita dan mematikan adopsi (G4) — melanggar batasan mutlak "SDK tidak boleh menjadi penyebab crash aplikasi host".
>
> Konsekuensinya: integrasi screen tracking adalah **satu-satunya langkah yang tidak nol-effort**, jadi harus ditulis menonjol di dokumen integrasi (MOB-25), bukan sebagai catatan kaki. Nama screen berasal dari developer → tetap wajib melewati Scrubber (SEC-05).
>
> **Catatan paritas:** perbedaan iOS vs Android di sini disengaja. Android punya `ActivityLifecycleCallbacks` yang resmi dan aman, jadi di sana screen tracking memang otomatis. Yang wajib identik adalah *output*-nya (event breadcrumb dengan `category: navigation`), bukan cara memicunya. **Konsekuensinya:** cakupan crumb navigasi di iOS bergantung pada disiplin aplikasi host memanggil `recordScreen(_:)` — crumb navigasi yang hilang di suatu sesi iOS belum tentu bug SDK.

### 3.5 Crash Reporting — Fase 2

| ID | Requirement | Prio |
|---|---|---|
| MOB-15 | Menangkap uncaught exception dan sinyal native | P0 |
| MOB-15b | Proses yang mati tanpa crash **tidak boleh** dilaporkan sebagai `crash`. Emit sebagai event `termination` (`01` §4.7) **hanya** bila penyebabnya termasuk enum sumber daya kritis; terminasi `unexplained` dibuang. Library crash (KSCrash) menyuntikkan laporan sintetis berlabel SIGKILL untuk kasus ini — label itu harus diabaikan, bukan diteruskan. | P0 |
| MOB-16 | Crash report ditulis ke disk saat crash, dikirim pada peluncuran berikutnya | P0 |
| MOB-17 | Menyertakan `threads` dan `binary_images` sesuai bentuk yang didokumentasikan di `01` §4.3.1/§4.3.2, termasuk **`is_app` per frame** — SDK yang menentukannya saat capture, karena hanya SDK yang tahu binary utama aplikasinya. Tanpa ini, penyorotan frame milik aplikasi (FE-06) tidak bisa dibangun, dan symbolication tidak punya `uuid` untuk mencocokkan berkas symbol. | P0 |
| MOB-18 | Deteksi ANR (Android) / hang main thread > 2 detik (iOS) | P1 |
| MOB-19 | Metrik cold start hingga frame pertama | P1 |

> **Hang main thread iOS (MOB-18) — dua jalur, dua bentuk event (`01` §4.3).** (1) Hang yang **pulih sendiri** dikirim *live* oleh `HangDetector` (membungkus observer Watchdog KSCrash; ambang 2,0 detik) sebagai `crash` dengan `crash_type: hang`, `name: MainThreadHang`, `is_fatal: false`. Sejak 0.0.15 event ini membawa `hang_resolution: recovered`, `hang_duration_ms`, `hang_start_state`, `hang_end_state` dan `ctx.app_state`. State dibaca dari `KSCrashAppStateTracker` (properti atomik — **tidak pernah** dari main thread yang sedang macet) dan di-cache saat observer menerima `started`; `hang_end_state` dibaca saat `ended`, ketika main thread sudah bebas. State yang tidak diketahui berarti field **tidak ada** — SDK tidak menebak (hang live dari SDK ≤ 0.0.14 selalu `ctx: {}` tanpa `hang_*`). (2) Hang yang **tidak terbukti pulih** sebelum proses mati ditemukan pada peluncuran berikutnya dari laporan Watchdog (`hang_resolution: unresolved`, `is_fatal: true`), dengan field yang sama ditambah `hang_*_perceptible`.
>
> **Atribut crash tambahan sejak 0.0.15:** `exception_backtrace` (backtrace tempat `NSException`/exception C++ dilempar, maks 64 frame, bentuk `frames`) dan `crash_info_message` (pesan trap runtime Swift, maks 512 byte, hanya dari `libswiftCore.dylib`). Bentuk dan batasnya ada di `01` §4.3/§4.3.1; catatan privasi `crash_info_message` ada di §6.1 di bawah.

> **Rekomendasi:** bungkus library crash reporting yang sudah matang daripada menulis signal handler sendiri. Menulis kode yang harus berjalan dengan benar saat aplikasi sedang sekarat adalah bagian tersulit dari seluruh proyek ini, dan kesalahannya berdampak langsung ke aplikasi tim lain. Keputusan final: `00-Overview.md` §11 no. 4.

### 3.6 Kontrol Jarak Jauh — Fase 2

| ID | Requirement | Prio |
|---|---|---|
| MOB-20 | Mengambil remote config saat startup, dengan cache & fallback default | P0 |
| MOB-21 | Mematuhi kill switch (`enabled: false`) | P0 |
| MOB-22 | Sampling per tipe event, dikontrol remote config | P1 (naik ke P0 di Fase 3 bila proyeksi volume melebihi kapasitas) |
| MOB-27 | SDK menghitung kesehatan dirinya sendiri (event tertulis vs terkirim vs terbuang, beserta alasan pembuangan) **dan mengirimkannya di envelope sebagai `sdk.health`** (`01` §2.3). Penghitung yang hanya hidup di perangkat tidak memenuhi tujuan requirement ini — justru saat SDK diam-diam membuang data, tidak ada yang bisa melihatnya. | P1 |

### 3.7 Distribusi & Dukungan — Fase 1–3

| ID | Requirement | Prio |
|---|---|---|
| MOB-23 | Distribusi via SPM & CocoaPods (iOS), Maven internal (Android) | P0 |
| MOB-24 | Semver + dokumen kompatibilitas; breaking change hanya di major version | P0 |
| MOB-25 | Sample app + dokumen integrasi — target: integrasi selesai < 30 menit | P0 |

### 3.8 Sinyal Integritas Device — Fase 1

Snapshot sekali per sesi saat startup, dilampirkan ke `integrity` di envelope (`01` §2). Karena envelope sudah membawa app version, device model, dan OS version, tiap flag otomatis punya konteks "versi berapa, device & OS apa" — tidak perlu field tambahan.

| ID | Requirement | Prio |
|---|---|---|
| MOB-29 | Deteksi **emulator/simulator** → `integrity.is_emulator`. iOS: `TARGET_OS_SIMULATOR` + cek model/env var. Android: heuristik build props (`FINGERPRINT`, `HARDWARE`=goldfish/ranchu, `MODEL`), file QEMU (`/dev/qemu_pipe`), sensor absen. | P1 |
| MOB-30 | Deteksi **jailbreak/root** → `integrity.is_rooted`. iOS: cek file Cydia/Sileo, kemampuan tulis di luar sandbox, symlink mencurigakan. Android: cek binary `su`, paket Magisk/SuperSU, `test-keys` di build tags, partisi system writable. | P1 |
| MOB-31 | Deteksi **developer mode** → `integrity.is_dev_mode` **dan** `integrity.debugger_attached` sebagai **dua boolean independen** (bukan digabung dengan OR — schema `01` §2 memang menyediakan field terpisah). `debugger_attached`: debugger sedang menempel saat itu — iOS via `sysctl` (`P_TRACED`), Android via `Debug.isDebuggerConnected()`. `is_dev_mode`: environment non-produksi — iOS via build non-App-Store (`embedded.mobileprovision`, TestFlight `sandboxReceipt`), Android via `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` + `ADB_ENABLED`. | P1 |

> **Kenapa dipisah (MOB-31).** Dua sinyal ini artinya berbeda dan berguna dibedakan saat menyaring sesi non-real: build TestFlight yang dipakai QA bisa berjalan **tanpa** debugger, sementara developer yang sedang nge-debug adalah kasus lain lagi. Menggabungkannya jadi satu OR akan membuang informasi itu dan membuat field `debugger_attached` di schema jadi mubazir.

**Batas keandalan & aturan (wajib dibaca sebelum implementasi):**
- Ketiganya **heuristik dan bisa diakali** — cukup untuk *observability* (menyaring/menandai sesi), **bukan** security gate. iOS simulator ~pasti terdeteksi; Android emulator & root bisa disembunyikan (Magisk DenyList dll.).
- **Jangan pakai API yang butuh permission sensitif.** IMEI/serial di Android sudah dibatasi ketat sejak Android 10 — cukup build props & file check yang tidak butuh permission.
- Flag ini **properti environment device, bukan PII** → aman dengan postur "no direct identifiers". Jangan pernah diikat ke identitas asli.
- **Upgrade path (opsional, bukan v1):** kalau nanti butuh sinyal yang sulit dipalsu (untuk security gate), pakai **Play Integrity API** (Android; SafetyNet Attestation sudah dimatikan Jan 2025) dan **App Attest / DeviceCheck** (iOS) — keduanya hardware-backed, verifikasi di server. Tercatat sebagai open decision di `00-Overview.md` §11.

---

## 4. Batasan Mutlak

> **SDK tidak boleh menjadi penyebab crash aplikasi host.**
>
> Seluruh entry point publik dan callback internal wajib defensif. Kegagalan internal SDK ditangani secara senyap dan dilaporkan lewat MOB-27 — **tidak pernah dilempar ke aplikasi host**.
>
> Ini bukan sekadar kualitas. SDK ini akan dipasang di aplikasi tim lain; satu crash yang berasal darinya akan menghentikan adopsi secara permanen, dan reputasi itu jauh lebih mahal dipulihkan daripada dijaga.

Konsekuensi praktis:
- Setiap method publik membungkus isinya dengan penanganan error menyeluruh
- Kegagalan I/O disk tidak boleh melempar exception ke pemanggil
- Parsing response server tidak boleh mengasumsikan bentuk payload
- Rollout selalu bertahap: app sendiri dulu sebagai canary, baru ditawarkan ke tim lain

---

## 5. Budget Performa

Dilanggar = tidak boleh rilis. **Diukur otomatis di CI**, bukan diperiksa manual.

| Aspek | Batas |
|---|---|
| Penambahan ukuran app | ≤ 1,5 MB per platform (terkompresi) |
| Overhead cold start | ≤ 30 ms (p95) |
| Main thread | **Nol** operasi I/O blocking; seluruh pekerjaan di background queue/thread |
| CPU | ≤ 2% rata-rata saat penggunaan normal |
| Memori | ≤ 8 MB resident |
| Disk | ≤ 20 MB |
| Baterai | Tanpa background wakeup khusus untuk upload; menumpang siklus hidup app |
| Retensi offline | Minimal 7 hari data tersimpan saat tidak ada koneksi |

---

## 6. Keamanan Sisi Client

### 6.1 Scrubbing — mencegah PII masuk pipeline

Aplikasi yang dimonitor mengalirkan nomor telepon dan data user lain. **Ancaman utamanya bukan pengumpulan yang disengaja, melainkan kebocoran tak sengaja:**

| Jalur kebocoran | Contoh |
|---|---|
| Path URL | `/api/v2/user/628123456789/profile` |
| Query parameter | `?msisdn=0812xxxx&otp=...` |
| Pesan error | `"Gagal mengirim OTP ke 0812xxxxxxx"` |
| Breadcrumb / atribut manual | `logError(e, ["phone": user.phone])` |
| Nama screen | `OTPVerification-0812xxxxxxx` |

| ID | Requirement | Prio |
|---|---|---|
| SEC-01 | **Scrubbing dilakukan sebelum penulisan ke disk.** Membersihkan di server tidak cukup — begitu data tertulis di disk device, permukaan kebocoran sudah terbentuk. | P0 |
| SEC-02 | Header HTTP memakai **allowlist**, bukan blocklist. Default hanya `Content-Type`, `Content-Length`, `Accept`, `User-Agent`. `Authorization`, `Cookie`, dan header kustom tidak pernah tercatat. | P0 |
| SEC-03 | Query parameter di-redaksi; hanya nama parameter disimpan, nilainya diganti `[redacted]`. **Koreksi 2026-10-12 (C8 Tahap A — kondisi sebenarnya di kode, bukan aspirasi):** SEMUA nilai query diredaksi tanpa kecuali hari ini. `QueryParameterScrubber.scrub(queryString:valueAllowlist:)` punya parameter allowlist, tapi tidak ada satu pun pemanggil yang mengisinya (selalu `[]`) — belum ada jalur dari config per app (`RemoteConfig`/`RemoteConfigStore`) ke scrubber ini. Konfigurasi per app untuk parameter ini **belum dibangun**. | P0 |
| SEC-03b | **Normalisasi path URL:** segmen diganti `{id}` kalau dia UUID, mengandung digit apa pun, atau berbentuk token buram (panjang ≥24, tanpa digit, bukan kata kebab-case huruf kecil). Pengecualian: segmen versi (`v2`, `v2.1`) dan segmen kata kebab-case huruf kecil (`category-by-order-status`, berapa pun panjangnya). **Diperkuat 2026-10-12 (C8 item C, disesuaikan sekali lagi hari ini):** ambang lama ("UUID atau run ≥5 digit") tidak menangkap id pendek (alamat/order/activity/district, sering <5 digit) maupun slug non-numerik (`PRC-XP-0042`, `STR-JKT-01`) yang nyata-nyata dipakai di app pilot; penyesuaian kedua menambahkan pengecualian kebab-case supaya slug nama-aksi yang panjang (`category-by-order-status`, persis 24 karakter) tidak ikut diredaksi — lihat tabel vektor di bawah. Aturan lengkap dan tabel vektor di bawah ini adalah **spesifikasi wajib untuk kedua platform** — SDK Android harus lulus vektor yang identik, bukan menerjemahkan ulang dari deskripsi prosa. | P0 |
| SEC-04 | Request & response body **tidak pernah** ditangkap. Tidak ada opsi mengaktifkannya di v1. | P0 |
| SEC-05 | Redaksi pola diterapkan ke **seluruh string** yang masuk pipeline — termasuk pesan error, `reason` crash, pesan breadcrumb, dan atribut kustom. Pola minimal: nomor telepon Indonesia (`08xx`, `+62xx`), email, string mirip JWT, deret angka ≥ 10 digit. | P0 |
| SEC-05b | Redaksi berjalan sebagai **lapisan terakhir** sebelum penulisan ke disk, sehingga berlaku juga untuk data yang dikirim developer lewat API manual. Asumsinya developer akan lupa — SDK tidak boleh bergantung pada kedisiplinan pemanggilnya. | P0 |
| SEC-06 | **`user_id` bebas diisi app host** — SDK tidak memvalidasi atau menolak isinya (boleh nomor telepon, email, atau teks apa pun) — satu-satunya pengecualian: string kosong atau hanya whitespace diabaikan (0.0.16), karena itu bukan identitas. Hashing menjadi `user_ref` dilakukan **backend** di ingestion (BE-21), bukan SDK. Kewajiban SDK: `user_id` mentah hanya menempati slot `user_id` di envelope — **tidak boleh** bocor ke breadcrumb, log debug, atau field lain. | P0 |

> **Batas SEC-05 yang harus dibaca apa adanya — teks bebas di crash (0.0.15).** `PatternRedactor` hanya mengenali **empat pola**: string mirip JWT, email, nomor HP Indonesia (`08xx`/`+62xx`), dan deret angka ≥ 10 digit. Ia **tidak** menangkap nama orang, alamat, ID pendek (< 10 digit), token yang bukan JWT, atau kalimat bebas. `crash_info_message` adalah teks yang developer aplikasi host tulis sendiri (`fatalError("…\(user.name)…")`), jadi apa pun yang ia interpolasikan lolos kalau tidak cocok salah satu pola itu. Yang SDK lakukan: hanya meneruskan pesan dari `libswiftCore.dylib` (bukan image lain), memotongnya di 512 byte pada batas whitespace **sebelum** Scrubber (potongan di tengah token bisa meninggalkan potongan email yang tidak lagi cocok pola), lalu melewatkannya di Scrubber seperti string lain (SEC-05/05b — tanpa jalur khusus yang lolos darinya). Yang SDK **tidak bisa** jamin: pesan yang berisi data pribadi non-pola. **Aplikasi host tidak boleh menaruh data pribadi di pesan `fatalError`/`precondition`.** Paparan yang sama sudah ada sejak awal pada `reason` `NSException`; `crash_info_message` tidak menambah jenis paparan baru, hanya satu string lagi yang sifatnya sama.
>
> **Perubahan dari desain awal (Opsi B):** versi sebelumnya menolak `user_id` yang menyerupai PII. Sekarang app host boleh mengisi apa pun, karena backend meng-hash-nya menjadi `user_ref` yang opaque sebelum disimpan (`01` §2.1 & BE-21). Postur "tidak menyimpan PII" tetap terjaga — yang berubah hanya *di mana* penyamaran terjadi: di server, bukan di client. Alasan memilih ini: app host bebas memakai identifier apa pun (nomor telepon dari sistem utama, dsb.) tanpa membebani mereka aturan validasi, sementara storage tetap bersih dari identitas langsung.

#### 6.1.1 SEC-03b — vektor normalisasi path (spesifikasi wajib, kedua platform)

**Aturan, dalam urutan evaluasi per segmen** (setelah path dipecah per `/`; setiap aturan hanya
jalan kalau aturan di atasnya tidak cocok):
1. Segmen cocok UUID (`^[0-9a-fA-F]{8}-...{12}$`) → `{id}`.
2. Segmen cocok pola versi `^v\d+(\.\d+)*$`, case-insensitive (`v2`, `v2.1`, `V3`) → **tidak diubah**. Dicek sebelum aturan digit supaya "v2" tidak kena aturan 3.
3. Segmen mengandung **minimal satu digit** (di mana pun posisinya) → `{id}`. Ambang lama ≥5-digit-berturutan dihapus.
4. Segmen cocok `^[a-z]+(-[a-z]+)*$` — kata/frasa huruf kecil yang disambung tanda hubung tunggal, bentuk nama route yang ditulis manusia (`category-by-order-status`, `get-product-count`) → **tidak diubah, berapa pun panjangnya**. Pola ini juga cocok untuk satu kata huruf kecil tanpa tanda hubung sama sekali (grup `(-[a-z]+)*` boleh nol kali) — lihat catatan di bawah untuk konsekuensinya.
5. Segmen tanpa digit sama sekali, panjang ≥24, hanya berisi `[A-Za-z0-9_-]` → `{id}`. Segmen yang sampai ke aturan ini sudah gagal di aturan 4, jadi dia pasti mengandung huruf besar, garis bawah, atau keduanya.
6. Selain itu → tidak diubah.

Query string **tidak disentuh** oleh aturan ini — nilainya sudah diredaksi penuh oleh aturan SEC-03 yang terpisah, terlepas dari isinya.

> **Riwayat aturan 4 (kebab-case) — kenapa diubah dua kali.** Versi pertama mengeluarkan tanda hubung sepenuhnya dari aturan token (aturan 5), supaya `category-by-order-status` (CSAPIEnum.swift, persis 24 karakter) tidak ikut diredaksi. Itu memperbaiki kasus itu, tapi sebagai efek samping membuat token base64url yang kebetulan tanpa digit dan mengandung `-` (encoding yang valid dan umum) ikut lolos tidak diredaksi — salah ke arah yang lebih berbahaya (identitas lolos, bukan kata biasa yang ikut diredaksi). Versi final ini mengembalikan tanda hubung ke aturan token (5), dan sebagai gantinya menambah pengecualian eksplisit (aturan 4) untuk bentuk kata kebab-case huruf-kecil-saja. **Konsekuensi yang disengaja:** karena aturan 4 juga cocok untuk satu kata tanpa tanda hubung, segmen sepanjang apa pun yang murni huruf kecil tanpa tanda hubung/garis-bawah/huruf besar (mis. `aaaabbbbccccddddeeeeffff`, 24 karakter) tetap dianggap "kata", **tidak diubah** — bukan `{id}`. Token buram asli (base64url, hash) nyaris mustahil murni huruf-kecil-ASCII pada panjang segitu; ini trade-off yang disengaja dan didokumentasikan, bukan oversight — lihat baris #18 di tabel vektor.

**Tabel vektor** (sumber kebenaran untuk implementasi dan test kedua platform; input selalu hanya bagian path, tanpa host):

| # | Kasus | Input | Output |
|---|---|---|---|
| 1 | Address id, Int kecil (gerbang privasi Tahap B) | `/customer/api/mobile/v2/address/food/482` | `/customer/api/mobile/v2/address/food/{id}` |
| 2 | District id | `/coverage-area/api/kelurahan-by-district/3173` | `/coverage-area/api/kelurahan-by-district/{id}` |
| 3 | Activity id (realtime-online) | `/home/xpress/api/mobile/livestream/get-realtime-online/77` | `/home/xpress/api/mobile/livestream/get-realtime-online/{id}` |
| 4 | Activity id (product-count) | `/home/xpress/api/mobile/livestream/get-product-count/9123` | `/home/xpress/api/mobile/livestream/get-product-count/{id}` |
| 5 | Activity id (detail-livestream) | `/home/view/general/api/mobile/livestream/get-detail-livestream/5541` | `/home/view/general/api/mobile/livestream/get-detail-livestream/{id}` |
| 6 | Product page code (slug alfanumerik) | `/home/xpress/api/mobile/product-page/PRC-XP-0042` | `/home/xpress/api/mobile/product-page/{id}` |
| 7 | Order id, Int kecil | `/order-xpress-view/api/mobile/orders/8821` | `/order-xpress-view/api/mobile/orders/{id}` |
| 8 | Store code (slug alfanumerik) | `/catalog-food-flagship/api/mobile/stores/slot/STR-JKT-01` | `/catalog-food-flagship/api/mobile/stores/slot/{id}` |
| 9 | Product page code (domain food) | `/home/food/api/mobile/product-page/FD-0099` | `/home/food/api/mobile/product-page/{id}` |
| 10 | Transaction code (alfanumerik + digit) | `/order-xpress-view/api/mobile/orders/payment-info/TRX20250914XP01` | `/order-xpress-view/api/mobile/orders/payment-info/{id}` |
| 11 | Order id, Int besar (kasus lama, sanity) | `/order-xpress-view/api/mobile/orders/88212345` | `/order-xpress-view/api/mobile/orders/{id}` |
| 12 | UUID (kasus lama, sanity) | `/customer/api/mobile/orders/3F2B1C8A-5E6F-4A7B-8C9D-0E1F2A3B4C5D` | `/customer/api/mobile/orders/{id}` |
| 13 | Tidak ada segmen id sama sekali | `/complain/api/mobile/food/category-by-order-status` | **tidak berubah** |
| 14 | Kata sebelum id, tanpa id-nya | `/home/xpress/api/mobile/livestream/get-product-count` | **tidak berubah** |
| 15 | Segmen versi sendirian | `/customer/api/mobile/v2/address/food` | **tidak berubah** |
| 16 | Segmen versi dengan minor | `/api/v2.1/status` | **tidak berubah** |
| 17 | Segmen versi huruf besar | `/api/V3/status` | **tidak berubah** |
| 18 | Huruf kecil murni, 24 karakter, tanpa tanda hubung — kasus nol-tanda-hubung aturan 4, dianggap kata bukan token | `/share/link/aaaabbbbccccddddeeeeffff` | **tidak berubah** |
| 19a | Campuran besar-kecil, tanpa tanda hubung/garis-bawah/digit, 23 karakter (tepat di bawah ambang token) | `/share/link/aAaAbBbBcCcCdDdDeEeEfFf` | **tidak berubah** |
| 19b | Campuran besar-kecil, tanpa tanda hubung/garis-bawah/digit, 24 karakter (tepat di ambang token) | `/share/link/aAaAbBbBcCcCdDdDeEeEfFfF` | `/share/link/{id}` |
| 20 | Frasa kebab-case huruf kecil, 40 karakter — tidak berubah berapa pun panjangnya | `/routes/get-customer-order-history-detail-report` | **tidak berubah** |
| 21 | Token buram, campuran besar-kecil + tanda hubung, ≥24 karakter, tanpa digit | `/share/link/shareTokenAbcDefGhiJkl-mnoPqr` | `/share/link/{id}` |
| 22 | Token buram, campuran besar-kecil + garis-bawah, ≥24 karakter, tanpa digit | `/share/link/shareTokenAbcDefGhiJkl_mnoPqr` | `/share/link/{id}` |
| 23 | Query string tidak tersentuh aturan ini (diredaksi oleh SEC-03 secara terpisah) | `/customer/api/mobile/profile?token=abcdef123456` | `/customer/api/mobile/profile?token=[redacted]` |

### 6.2 Data at rest di device

| ID | Requirement | Prio |
|---|---|---|
| SEC-07 | **iOS:** file antrean memakai `FileProtectionType.completeUntilFirstUserAuthentication`, ditandai dikecualikan dari backup. **Android:** internal storage, dikecualikan dari auto-backup. | P0 |
| SEC-08 | Enkripsi antrean at-rest: AES-GCM, kunci di Keychain / Android Keystore | P1 |
| SEC-09 | **Pengecualian eksplisit untuk crash report** — lihat kotak di bawah | P0 |

> ### Trade-off yang disengaja: crash report tidak dienkripsi saat ditulis
>
> Penulisan crash report terjadi di dalam signal handler. Di konteks itu, alokasi memori tidak *async-signal-safe*, sehingga menjalankan kriptografi berisiko membuat proses hang atau crash kedua kali — kehilangan data yang justru sedang coba diselamatkan.
>
> **Solusinya:** crash report ditulis mentah dengan payload seminimal mungkin (tanpa PII sama sekali), lalu dienkripsi saat peluncuran aplikasi berikutnya.
>
> Trade-off ini **didokumentasikan sejak awal** agar tidak muncul sebagai temuan tak terduga saat security review.

### 6.3 Komunikasi & kredensial

| ID | Requirement | Prio |
|---|---|---|
| SEC-10 | TLS 1.2+ untuk seluruh komunikasi SDK ke endpoint ingestion; cleartext dinonaktifkan di level platform (ATS di iOS, network security config di Android) | P0 |
| SEC-11 | Certificate pinning pada endpoint ingestion — **opsional, tidak aktif secara default** (lihat keputusan di bawah). Bila dinyalakan per-app, **wajib** menyertakan pin cadangan dan kill switch via remote config. | P2 |
| SEC-12 | Jika validasi TLS gagal karena sebab apa pun: **fail closed** — data tetap di disk, tidak pernah fallback ke koneksi tanpa proteksi. Berlaku terlepas dari SEC-11 aktif atau tidak. | P0 |

> ### Keputusan: pinning tidak diaktifkan secara default (SEC-11 → P2)
>
> **Konteks.** Ada dua koneksi berbeda yang sering tertukar:
>
> | Koneksi | Client-nya | Yang melakukan pinning |
> |---|---|---|
> | App host → API bisnis | App host | App host. SDK hanya **mengamati** hasilnya (`ssl_pinning_rejected`, MOB-03) |
> | **SDK → endpoint ingestion APM** | **SDK** | SDK — inilah lingkup SEC-11 |
>
> **Alasan diturunkan.** Biaya operasionalnya tidak sebanding dengan ancaman yang ditutup. Rotasi sertifikat server ingestion (rutin, biasanya tahunan) akan menghentikan pengiriman telemetri di **seluruh app terpasang sekaligus**, dan pemulihannya menuntut rilis app baru plus menunggu user memperbarui — berminggu-minggu tanpa visibilitas. Ancaman yang ditutup pinning hanya berlaku bila penyerang sudah mampu menanam CA palsu di device user; pada titik itu perusahaan menghadapi masalah yang jauh lebih besar daripada penyadapan data telemetri.
>
> Nilai data yang ditransmisikan juga rendah secara relatif: `user_id` mentah (langsung di-hash di ingestion, BE-21), path yang sudah dinormalisasi, dan breadcrumb yang sudah di-scrub. Request/response body tidak pernah ditangkap sama sekali (SEC-04).
>
> **Catatan penting.** Proyek ini lahir dari insiden kegagalan pinning yang membuat tim kehilangan visibilitas. Membangun sistem monitoring yang bisa mematikan dirinya sendiri dengan mekanisme yang persis sama adalah risiko yang tidak masuk akal untuk diambil secara default.
>
> **Jalur naik.** Bila suatu tim adopter membutuhkan jaminan lebih kuat (atau muncul tuntutan compliance), pinning dapat dinyalakan sebagai **opsi konfigurasi per-app** — dengan syarat mutlak pin cadangan + kill switch. Itu menjadi keputusan sadar per aplikasi, bukan default yang diam-diam berisiko.
| SEC-13 | Kunci app diperlakukan sebagai **identifier, bukan credential** — kunci yang tertanam di binary dapat diekstrak siapa pun. Konsekuensinya kunci bersifat write-only. | P0 |
| SEC-14 | Kunci dapat dirotasi lewat remote config tanpa rilis app baru | P1 |

### 6.4 Integritas SDK

| ID | Requirement | Prio |
|---|---|---|
| SEC-19 | Rilis dari repository artifact internal, checksum dipublikasikan, dependensi pihak ketiga minimal dan versinya dikunci | P0 |
| SEC-20 | Tidak ada eksekusi kode dinamis; remote config hanya boleh mengubah flag yang telah didefinisikan, tidak pernah perilaku eksekusi | P0 |
| SEC-21 | Dokumen **data inventory** resmi: daftar lengkap data yang dikumpulkan SDK | P0 |
| SEC-22 | **Konsekuensi rilis untuk semua app adopter:** setiap app yang meng-embed SDK wajib memperbarui privacy label di App Store Connect dan formulir Data Safety di Play Console. Tanpa ini, rilis berisiko ditolak. Dokumen SEC-21 menjadi rujukan pengisiannya. | P0 |

---

## 7. Definition of Done per Fase

**Fase 1 (M1) — iOS**
- [ ] Seluruh MOB P0 Fase 1 terpenuhi
- [ ] Scrubbing P0 (SEC-01 s/d SEC-05b) terpasang dan diuji dengan payload berisi nomor telepon
- [ ] `setUser(id)` + fallback generate berjalan (MOB-28); diverifikasi bersama Backend bahwa `user_id` mentah **tidak** tersimpan setelah di-hash jadi `user_ref` (BE-21)
- [ ] Flag `integrity` (emulator/root/dev-mode) terisi benar di device uji nyata (MOB-29..31)
- [ ] Budget performa §5 lolos di CI
- [ ] Data dari 1 app pilot masuk ke backend selama ≥ 2 minggu tanpa regresi crash-free rate
- [ ] Skenario diuji: SSL gagal, offline penuh, force quit saat upload, disk penuh

**Fase 2 (M2–M3)**
- [ ] Android mengirim payload yang identik dengan iOS untuk kasus uji yang sama
- [ ] Crash tersimbolikasi otomatis di kedua platform
- [ ] Kill switch terbukti berfungsi di production

**Fase 3 (M5)**
- [ ] Sample app + dokumen integrasi selesai; diuji dengan satu tim adopter nyata
- [ ] Data inventory (SEC-21) diserahkan ke semua tim adopter
