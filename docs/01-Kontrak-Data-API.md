# 01 — Kontrak Data & API

**PRD APM Kit** · Dokumen bersama Mobile · Backend · Frontend

> ## ⚠️ Dokumen ini adalah satu-satunya sumber kebenaran
>
> Dokumen tim **tidak boleh menyalin** isi di sini — cukup mereferensikan nomor bagiannya.
> Setiap perubahan wajib melalui review Mobile, Backend, **dan** Frontend, karena ketiganya bergantung pada kontrak ini.
>
> Perubahan yang tidak backward-compatible menaikkan `schema_version`.

---

## 1. Siapa Bergantung pada Apa

| Bagian | Mobile | Backend | Frontend |
|---|:---:|:---:|:---:|
| §2 Envelope | menulis | membaca | — |
| §3 Event | menulis | membaca | — |
| §4 Tipe & atribut | menulis | membaca | menampilkan |
| §5 Enum `failure_category` | menghasilkan | menyimpan | filter & grouping |
| §6 Fingerprinting | — | menghitung | menampilkan sebagai Issue |
| §7 Ingestion API | memanggil | menyediakan | — |
| §8 Symbol API | CI memanggil | menyediakan | status ditampilkan |
| §9 Config API | memanggil | menyediakan | admin mengatur |
| §10 Read API | — | menyediakan | memanggil |

---

## 2. Envelope

Satu request berisi satu envelope. Konteks statis diletakkan di envelope (tidak diulang per event) untuk menghemat bandwidth dan disk.

```json
{
  "schema_version": 1,
  "sdk":     { "name": "apmkit-ios", "version": "1.0.0",
               "health": { "written": 1420, "sent": 1398, "dropped": 22,
                           "drop_reasons": { "queue_full": 18, "rejected": 4 } } },
  "app":     { "id": "com.company.appname", "version": "3.2.1", "build": "1042" },
  "device":  { "os": "iOS", "os_version": "17.4", "model": "iPhone14,2",
               "locale": "id_ID", "timezone": "Asia/Jakarta" },
  "integrity": { "is_emulator": false, "is_rooted": false,
                 "is_dev_mode": false, "debugger_attached": false },
  "install_id": "8f14e45f-ea1a-4f2c-9d3b-7c2a1b0e5d44",
  "session_id": "b3d9c1a2-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "user_id":    "client-supplied-string-or-sdk-generated",
  "user_id_source": "host",
  "events": [ /* array of Event */ ]
}
```

| Field | Tipe | Keterangan |
|---|---|---|
| `schema_version` | int | Dinaikkan saat ada breaking change. Backend **wajib menolak** versi yang tidak dikenal dengan `400`. |
| `install_id` | UUID | Identitas instalasi, digenerate saat SDK pertama kali jalan. **Bukan identitas user.** Reset saat app di-uninstall. |
| `session_id` | UUID | Satu sesi = dari app foreground sampai background > 30 detik. |
| `user_id` | string \| null | Identifier yang di-set app host — **string bebas** (boleh nomor telepon, email, user ID internal, atau apa pun). Kalau app host tidak mengisinya, SDK meng-generate ID acak stabil per install. **Dikirim mentah lewat TLS**, lalu di-hash jadi `user_ref` di ingestion (BE-21) — nilai mentah **tidak pernah disimpan**. Lihat §2.1. |
| `user_id_source` | enum: `host` \| `generated` | Apakah `user_id` berasal dari `setUser` app host, atau digenerate SDK sebagai fallback. Lihat §2.2. |
| `integrity` | object | Sinyal environment device, di-snapshot sekali per sesi: `is_emulator`, `is_rooted`, `is_dev_mode`, `debugger_attached` (semua `bool`). Bersifat heuristik (MOB-29..31). Bukan PII. |
| `sdk.health` | object | Penghitung kesehatan SDK sendiri (MOB-27): `written`, `sent`, `dropped`, dan `drop_reasons`. Lihat §2.3. |

### 2.2 `user_id_source` — kenapa perlu dibedakan

Nilai `user_id` selalu terisi: bila app host tidak memanggil `setUser`, SDK memakai ID acak
yang stabil per install. Dari sisi backend keduanya terlihat identik — sama-sama string
yang di-hash menjadi `user_ref`.

Padahal artinya jauh berbeda. `user_ref` dari identifier app host **dapat dicocokkan**
dengan sistem utama aplikasi (itulah yang membuat "cari user via nomor HP" berfungsi);
`user_ref` dari ID generate **tidak bisa** — ia hanya konsisten di dalam APM.

Tanpa penanda ini, aplikasi yang lupa memanggil `setUser` terlihat sehat sepenuhnya:
User Lookup tetap mengembalikan data, hanya saja tidak pernah bisa dihubungkan ke user
sungguhan, dan tidak ada yang menyadarinya. Inilah salah satu peringatan integrasi di
`04` §3.8.

### 2.3 `sdk.health` — penghitung yang harus keluar dari perangkat

MOB-27 meminta SDK menghitung event yang ditulis, terkirim, dan terbuang. Penghitung yang
hanya hidup di perangkat tidak memenuhi tujuannya — justru saat SDK diam-diam membuang
data itulah tidak ada seorang pun yang bisa melihatnya.

Karena itu penghitung dikirim di envelope, bersifat kumulatif per install, dan dipakai
untuk peringatan integrasi (`04` §3.8): rasio `dropped` terhadap `written` yang naik
berarti antrean penuh atau payload ditolak — masalah yang perlu diketahui sebelum
menjelma jadi lubang data yang tak dijelaskan.

`drop_reasons` bersifat terbuka (mis. `queue_full`, `rejected`, `undecodable`); konsumen
harus menangani kunci yang belum dikenal tanpa rusak.

**Semantik `dropped` dan `drop_reasons` (jangan dicampur):**

- `dropped` = event yang **hilang**: SDK menulisnya atau menerimanya, lalu tidak akan pernah sampai ke
  backend (antrean penuh, payload ditolak, berkas tak terbaca). Hanya `dropped` yang dibagi `written` untuk
  rasio yang memicu peringatan di `04` §3.8.
- `drop_reasons` = rincian penyebab, dan **boleh memuat pembuangan yang disengaja** yang **tidak** dihitung ke
  `dropped` — tidak ada data yang hilang. Contoh: `watchdog_artifact` (APMKit ≥ 0.0.14: report Watchdog yang
  hanya bayangan dari sebuah crash dan sengaja dibuang, §4.3). Sebaliknya, `unparseable_report` (berkas report
  crash yang tidak bisa di-parse) memang kehilangan data, jadi dihitung di `dropped` **dan** di `drop_reasons`.
- Karena itu konsumen **tidak boleh** mengasumsikan `sum(drop_reasons) == dropped`, tidak boleh menampilkan
  jumlah `drop_reasons` sebagai "event terbuang", dan tidak boleh memakainya untuk peringatan `04` §3.8.
  Tampilkan rinciannya sebagai rincian, dengan `dropped` sebagai angka kehilangan.

### 2.1 Identifier: `user_id` (mentah, transit) → `user_ref` (tersimpan)

Model identifier sengaja dipisah dua, supaya app host bebas memakai identifier apa pun **tanpa** menaruh PII di storage APM:

- **`user_id`** — nilai mentah yang dikirim app (atau di-generate SDK). Boleh berupa nomor telepon/email/teks. Ada di envelope, **transit lewat TLS**, tidak pernah dipersistensi mentah.
- **`user_ref`** — hasil `HMAC-SHA256(server_key, user_id)` yang dihitung backend saat ingestion (BE-21). **Hanya nilai inilah yang disimpan** dan dipakai di seluruh downstream: Issue, sesi, Read API, dashboard. Opaque, tidak bisa dibalik tanpa `server_key` yang hanya ada di server.

**Format `user_ref` (spesifikasi, mengikat):**

- Digest **HMAC-SHA256 penuh** — 32 byte, di-encode sebagai **64 karakter hex huruf kecil** (`[0-9a-f]{64}`).
- **Tanpa prefix, tanpa truncation.** Tidak ada bagian digest yang dipotong dengan alasan apa pun — lihat alasan di bawah.
- Dua implementasi acuan yang HARUS konsisten dengan bentuk ini: `apm-backend/internal/userref.Compute` (produksi) dan `apm-backend/internal/contract.UserRefPattern` (`^[0-9a-f]{64}$`, dipakai untuk validasi di Read API dan di frontend dashboard).

Alasan truncation dilarang: sebuah implementasi acuan lama (pilot/`apm-ingest`) pernah memotong digest ke 12 hex karakter dengan prefix `usr_` (bentuk `usr_[0-9a-f]{12}`). Potongan 48-bit itu berkolisi ~0.2% pada populasi 1 juta user; satu kolisi berarti dua user tergabung jadi satu di User Lookup. Produksi **tidak pernah** memakai bentuk ini — ini murni warisan pilot, tidak valid sebagai `user_ref` produksi, dan ditolak secara eksplisit oleh validasi dashboard (`USER_REF_PATTERN`, `apm-backoffice/src/api/contract.ts`) maupun oleh Read API backend. Integrasi apa pun (termasuk SDK Android) yang membaca atau membandingkan `user_ref` HARUS mengasumsikan bentuk 64-hex di atas, dan HARUS memperlakukan bentuk `usr_<12hex>` sebagai tidak valid, bukan sebagai bentuk alternatif yang sah.

Konsekuensinya: input mentah yang sama (mis. nomor telepon yang sama) selalu menghasilkan `user_ref` yang sama → grouping & korelasi per user tetap jalan penuh, tapi storage tidak pernah memuat identitas langsung yang bisa dibaca. Trade-off jujur: nilai mentah **tetap melewati** endpoint ingestion sebelum di-hash-dan-dibuang — klaim yang benar adalah "tidak disimpan", bukan "tidak pernah dikirim".

---

## 3. Event

```json
{
  "event_id": "3f2b1c8a-...",
  "type": "network_failure",
  "ts_client": "2026-07-24T09:12:33.412Z",
  "seq": 1043,
  "attrs": { },
  "ctx": { "connectivity": "wifi", "screen": "CheckoutViewController",
           "app_state": "foreground", "low_power": false }
}
```

| Field | Keterangan |
|---|---|
| `event_id` | UUID v4. **Kunci deduplikasi di backend** — pengiriman bersifat *at-least-once*, event yang sama bisa datang dua kali. Untuk event `crash` dan `termination`, SDK iOS (APMKit ≥ 0.0.13) mengambilnya dari id laporan crash KSCrash (`report.id`), bukan UUID acak per kirim: bentuknya tetap UUID v4 (huruf besar, 36 karakter), tetapi **deterministik per laporan** — laporan yang sama yang diproses ulang atau terkirim dua kali membawa `event_id` yang sama. Backend tidak boleh mengandaikan `event_id` selalu baru per pengiriman. |
| `seq` | Counter monotonik per sesi. Dipakai backend untuk mengurutkan event ketika jam device tidak akurat. **Kualifikasi untuk `type: "crash"`** (APMKit 0.0.8+): SDK memulihkan `session_id` sebuah crash dari sesi yang benar-benar mengalaminya (lewat KSCrash user-info, ditulis sebelum proses mati), tapi `seq`-nya tetap berasal dari counter sesi PELAPOR (sesi baru yang membaca laporan crash tertunda saat proses berikutnya start) — objek `SessionManager` milik sesi yang crash sudah lenyap bersama prosesnya, sehingga counter aslinya tidak bisa dipulihkan. Akibatnya, untuk event `type: "crash"`, `seq` **tidak sebanding** dengan `seq` event lain yang berbagi `session_id` yang sama — jangan diasumsikan ikut deret monotonik sesi tersebut. Backend/dashboard yang mengurutkan event dalam satu sesi sebaiknya memakai `ts_client` untuk baris `crash`, bukan `seq`. |
| `ts_client` | Waktu dari device. **Tidak boleh dipercaya sebagai sumber kebenaran** — backend menyimpan `ts_server` terpisah (BE-05). |
| `ctx` | Konteks volatil yang bisa berubah dalam satu sesi. |

> **Status pengisian `ctx` (dicek terhadap kode APMKit 0.0.15, 2026-10-21) — contoh JSON di atas menunjukkan bentuk penuh yang dicadangkan, BUKAN yang dikirim hari ini.**
>
> | Field | Status | Keterangan |
> |---|---|---|
> | `app_state` | **diisi sebagian** | `foreground` \| `background`. Hanya di event `crash`, `termination`, dan (sejak 0.0.15) `MainThreadHang` — dari transition state KSCrash, aturan di §4.3. Tidak ada untuk crash saat launching/startup, dan **tidak pernah** di `network`, `network_failure`, `error`, `lifecycle`, maupun event cold start. |
> | `connectivity` | **belum diisi SDK (reserved)** | Perubahan koneksi hanya menjadi breadcrumb (`connectivity_lost`/`connectivity_restored`). |
> | `screen` | **belum diisi SDK (reserved)** | `recordScreen(_:)` hanya menjadi breadcrumb `navigation`. Kalau suatu saat diisi, nilainya tetap melewati Scrubber (SEC-05), seperti sekarang. |
> | `low_power` | **belum diisi SDK (reserved)** | — |
>
> "Reserved" berarti: nama dan bentuknya dicadangkan untuk SDK yang kelak mengisinya; **backend dan UI tidak boleh bergantung pada keberadaannya**, dan event tanpa field itu valid (`ctx: {}` adalah bentuk yang paling sering dikirim). Saat ini backend hanya meneruskan `ctx` apa adanya pada `sample_event` di detail issue; tidak ada query yang memfilter atau mengelompokkan berdasarkan field `ctx`. Pelacakan di sisi SDK: `FEATURES.md` APMKit, item 25.

---

## 4. Tipe Event & Atribut

### 4.1 `network` — request selesai

| Atribut | Tipe | Wajib |
|---|---|---|
| `host` | string | ✓ |
| `path` | string (sudah dinormalisasi & di-scrub, lihat SEC-03b) | ✓ |
| `method` | string | ✓ |
| `status_code` | int | ✓ |
| `duration_ms` | int | ✓ |
| `dns_ms`, `tcp_ms`, `tls_ms`, `ttfb_ms` | int | — |
| `req_bytes`, `res_bytes` | int | — |
| `protocol` | string (`http/1.1`, `h2`) | — |
| `tls_version` | string | — |
| `reused_connection` | bool | — |

### 4.2 `network_failure` — request gagal

| Atribut | Tipe | Wajib |
|---|---|---|
| `host`, `path`, `method` | string | ✓ |
| `failure_category` | enum (§5) | ✓ |
| `status_code` | int | ✓ jika `failure_category = http_error`, selain itu — |
| `error_domain`, `error_code` | string, int | ✓ untuk kegagalan transport, — untuk `http_error` |
| `underlying_domain`, `underlying_code` | string, int | — |
| `tls_phase_reached` | enum: `none` \| `started` \| `completed` | — |
| `duration_ms` | int | ✓ |

> **Perilaku untuk respons 4xx/5xx (`http_error`).** Respons dengan status ≥ 400 di level client bukan error transport — di iOS muncul sebagai `didCompleteWithError: nil` yang biasa (tak bisa dibedakan dari sukses tanpa SDK memeriksa status), di Android sebagai response biasa. SDK **memeriksa `status_code` di setiap request yang selesai**, lalu:
> 1. Selalu emit event **`network`** dengan `status_code` asli (§4.1) — request-nya memang selesai.
> 2. Jika `status_code ≥ 400`, **tambahan** emit event **`network_failure`** dengan `failure_category = http_error` dan `status_code` terisi (untuk fingerprinting §6).
>
> `http_error` adalah kategori tersendiri, **terpisah** dari kegagalan transport (`ssl_*`, `timeout`, `dns`, `connectivity`). Satu respons 500 = satu `network` + satu `network_failure(http_error)`; **jangan** juga dihitung sebagai kegagalan transport, supaya "network failure rate" per kategori di dashboard tidak tercampur.

### 4.3 `crash`

| Atribut | Keterangan |
|---|---|
| `crash_type` | `signal` \| `exception` \| `anr` \| `hang` |
| `name`, `reason` | Nama exception/signal dan alasannya |
| `is_fatal` | bool |
| `hang_resolution` | `recovered` \| `unresolved` — **hanya ada saat `crash_type: "hang"`** yang berasal dari KSCrash Watchdog monitor (`report.report.monitor_id == "Watchdog"`). `unresolved` artinya: hang ini tidak pernah terkonfirmasi pulih sebelum prosesnya mati — bukan "tidak diketahui". Lihat paragraf di bawah. **Hang live (sejak SDK 0.0.15):** event `name: "MainThreadHang"` (`is_fatal: false`) yang dikirim `HangDetector` di proses yang sama begitu hang berakhir juga membawa field ini, selalu `recovered` (event hanya dikirim setelah main thread pulih). Hang live yang dikirim SDK ≤ 0.0.14 **tidak** punya field ini (dan tidak punya `hang_*` lain). |
| `hang_duration_ms` | int — durasi hang, dari `error.hang`'s start/end timestamp KSCrash. Hanya ada bersama `hang_resolution`. Pada hang live: bilangan bulat yang sama dengan yang tertulis di `reason` ("main thread blocked for Nms"). |
| `hang_start_state`, `hang_end_state` | string — salah satu dari `startup`, `prewarm`, `launching`, `foregrounding`, `active`, `deactivating`, `background`, `terminating`, `exiting` (nilai transition-state mentah KSCrash, lowercase, bukan daftar yang kita definisikan sendiri). `start` = state saat watchdog **mendeteksi** hang (lihat paragraf di bawah — BUKAN jaminan saat main thread sungguh mulai macet); `end` = state pada tick sidecar terakhir sebelum hang pulih atau prosesnya mati. Hanya ada saat `crash_type: "hang"` dari Watchdog monitor **dan** sidecar KSCrash-nya membawa nilai ini — SDK/KSCrash lama (sebelum field ini ditambahkan) berarti field ini **tidak ada sama sekali**, bukan `"unknown"` dan bukan string kosong. **Hang live (sejak SDK 0.0.15):** `start` = state yang di-cache saat observer Watchdog menerima `started` untuk hang itu (notifikasi dikirim dari thread watchdog setelah laporan hang ditulis, jadi semantiknya sama dengan "saat watchdog mendeteksi" di paragraf bawah); `end` = state yang dibaca saat `ended`, **setelah main thread pulih**, jadi — tidak seperti jalur laporan Watchdog — `end` boleh berbeda dari `start` (mis. hang yang berakhir ketika app sudah masuk background). Kalau `started` tidak pernah diterima untuk hang itu, `hang_start_state` **tidak ada** (SDK tidak menebak dari state saat berakhir). Hang live **tidak** membawa `hang_start_perceptible`/`hang_end_perceptible`; konsumen menurunkannya dari state memakai aturan baris di bawah. |
| `hang_start_perceptible`, `hang_end_perceptible` | bool — `true` kalau state pasangannya (`hang_start_state`/`hang_end_state`) adalah state yang bisa dilihat pengguna (`startup`, `launching`, `foregrounding`, `active`, `deactivating`), `false` untuk `prewarm`/`background`/`terminating`/`exiting`. Dihitung SDK dari `ksapp_transitionStateIsUserPerceptible` milik KSCrash sendiri, bukan tabel yang kita tulis ulang. **Tidak ada** kalau state pasangannya tidak ada — sama aturannya. |
| `threads` | Array thread — bentuknya di §4.3.1 |
| `binary_images` | Daftar binary yang dimuat + UUID — **wajib** untuk symbolication; bentuknya di §4.3.2 |
| `exception_backtrace` | string — array JSON frame, **bentuk yang sama dengan `frames` di §4.3.1** (`index`, `object_name`, `object_addr`, `instruction_addr`, `is_app`, `symbol_name`/`file`/`line` = `null` sampai symbolication). Sumbernya `crash.last_exception_backtrace` KSCrash: backtrace tempat exception DILEMPAR, yang berbeda dari stack thread yang crash (di situ KSCrash menaruh stack handler). **Hanya ada** untuk crash yang punya backtrace itu — `NSException` dan exception C++; **tidak pernah** untuk trap Swift atau sinyal. Maksimum **64 frame**, urutan KSCrash (frame terdalam dulu), `index` = posisi dalam array. Memakai `binary_images` event yang sama untuk symbolication. Sejak SDK 0.0.15. |
| `crash_info_message` | string — pesan yang runtime Swift tulis ke section `__crash_info` sebelum trap (`fatalError`, `precondition`, force unwrap `nil`, index di luar jangkauan, dll.), dalam bentuk `<fileID>:<baris>: Fatal error: <pesan>` (contoh nyata: `VerificationApp/ContentView.swift:31: Fatal error: apm-test 507`; `Swift/ContiguousArrayBuffer.swift:675: Fatal error: Index out of range`). SDK hanya mengambilnya dari image **`libswiftCore.dylib`** (terbukti membawa pesan itu pada laporan nyata; image lain, mis. `libsystem_sim_platform` di Simulator yang menulis nama device + UDID, **tidak pernah** diteruskan), membuang newline penutup, memotongnya di **512 byte UTF-8** (di whitespace terakhir yang muat, kecuali itu membuang lebih dari separuh jatah — satu kata panjang tanpa spasi dipotong di karakter utuh terakhir yang muat; tidak pernah di tengah karakter), dan meneruskannya lewat Scrubber seperti string lain. **Teks bebas milik developer** — lihat catatan privasi di `02` §6.1. Tidak ada kalau tidak ada trap Swift (mis. exception Obj-C, sinyal murni) atau image itu tidak membawa pesan. Sejak SDK 0.0.15. |
| `app_state` | State saat crash — sebenarnya dikirim di `ctx.app_state`, bukan di `attrs`. Aturan pemetaan (sama untuk semua crash): transition-state KSCrash `foregrounding`/`active`/`deactivating` → `foreground`; `background` → `background`; `startup`/`prewarm`/`launching`/`terminating`/`exiting` atau tidak diketahui → **field tidak ada** (tidak ditebak). **Hang live (`MainThreadHang`, sejak SDK 0.0.15):** diisi dari state yang di-cache saat hang dimulai (lihat `hang_start_state`), dengan aturan pemetaan yang sama; event dari SDK ≤ 0.0.14 selalu `ctx: {}`. |
| `time_since_launch_ms` | int |

> **`is_fatal=false` dikecualikan dari crash-free rate — prinsip yang sama dengan §4.7, diperluas.** Alasan `termination` bukan `crash` (§4.7: "Menghitungnya sebagai crash membuat crash-free rate buruk secara palsu") berlaku kata-demi-kata untuk `crash_type` apa pun di bawah `type: "crash"` yang `is_fatal`-nya `false` — misalnya `hang` yang pulih sendiri tanpa mematikan proses. Backend **wajib** mengecualikan baris `is_fatal=false` dari penghitung `crash_sessions`/`crash_users` (metrik crash-free), bukan hanya baris `type='termination'`. Baris `type='crash'` yang tidak punya `is_fatal` sama sekali (event lama, sebelum field ini ada) dihitung **sebagai fatal** — default yang menganggap tak-fatal akan diam-diam membuang crash sungguhan dari metrik, kegagalan yang jauh lebih buruk daripada memasukkan satu hang yang semestinya dikecualikan. Ditemukan 2026-10-01 dari data pilot nyata: KSCrash Watchdog yang tidak dijeda saat app di-background menghasilkan `crash_type: hang, is_fatal: false` persis pada siklus background/foreground, sebelum celah ini ditutup (lihat `FEATURES.md` APMKit, parked item, untuk perbaikan di sisi SDK yang masih tertunda).
>
> **`hang_resolution: "unresolved"` TIDAK berarti "tidak diketahui" — artinya hang ini tidak pernah terkonfirmasi pulih sebelum prosesnya mati.** KSCrash menandai laporan semacam ini `is_fatal: true` (lewat `WatchdogStitch.m`, di sisi SDK) karena ia tidak bisa membuktikan sebaliknya — proses itu sudah tidak ada untuk dimintai konfirmasi. Tapi "tidak terbukti pulih" bukan sinonim dari "crash sungguhan": kematian proses yang memicu kondisi ini bisa jadi crash nyata, atau bisa jadi semata app di-background lalu di-kill OS (jetsam/memory pressure) — kondisi yang sama sekali bukan crash yang dialami pengguna. **Apakah baris `hang_resolution: "unresolved"` tetap dihitung dalam `crash_free_sessions`/`crash_free_users`, atau dipisah ke metrik tersendiri, adalah keputusan produk yang BELUM diambil** — lihat `FEATURES.md` APMKit, item 18, untuk pertanyaan itu apa adanya. Item 18 adalah sumbernya; paragraf ini hanya merujuk ke nomor itu, bukan sebaliknya. **`hang_start_state`/`hang_start_perceptible` (di atas) adalah fakta yang keputusan itu kemungkinan besar akan dipakai** — lihat paragraf berikutnya untuk alasannya; `readapi.py` dan `overview.go` **belum membaca field ini** sama sekali hari ini (TAHAP 2 FEATURES.md item 18: SDK mengirim faktanya, server belum memutuskan apa-apa dari situ).
>
> **Arti "start": saat watchdog mendeteksi, bukan saat main thread sungguh macet.** KSCrash mencatat `hang_start_state`'s timestamp dari ping terjadwal terakhir (dekat dengan saat macetnya sungguh mulai), tapi transition-state-nya sendiri diambil pada TICK DETEKSI pertama (`watchdogTimerFired`, bisa tertunda hingga ambang 250ms, atau — pada skenario app disuspend lalu di-resume sebelum main thread sempat merespons ping — bisa jauh lebih lambat karena jam dinding terus berjalan selama suspend). Walau begitu, `hang_start_state` tetap field yang andal untuk "apakah pengguna pernah melihat app ini macet": `_transitionState` di KSCrash hanya berubah lewat notifikasi `UIApplication*` yang dikirim SYNCHRONOUS di main thread — kalau main thread benar-benar macet, notifikasi itu TIDAK BISA terkirim, jadi state-nya membeku di nilai terakhir yang sah sebelum macet. Hang yang dimulai `active` (pengguna melihatnya) tetap tercatat `active` walau app lalu dibackground paksa sambil masih macet; hang yang murni terjadi di background (termasuk artefak jam dari skenario suspend/resume di atas, karena notifikasi "masuk background" sudah terkirim sebelum suspend fisik terjadi) tercatat `background`. **Konsekuensinya: untuk satu hang yang sama, `hang_end_state` dalam PRAKTIK sama dengan `hang_start_state`** — karena state hanya bisa berubah lewat notifikasi main-thread, dan main thread yang sedang macet itulah yang seharusnya menjalankan notifikasi tersebut. **Ini TIDAK dijamin secara formal**: tidak ada pembuktian di sini yang menutup semua kasus, dan urutan antara notifikasi `WillEnterForeground` (dikirim main thread saat resume) dengan tick deteksi watchdog sendiri tidak disinkronkan oleh kode ini — ada celah (race), bukan kontradiksi atas mekanisme di atas, tapi juga bukan sesuatu yang dikendalikan SDK ini. `hang_end_state` tetap dikirim apa adanya (SDK tidak menyimpulkan ini, server yang nanti boleh), tapi jangan berharap ia membawa informasi baru di atas `hang_start_state`, dan jangan memperlakukan keduanya sebagai dijamin identik secara konstruksi.

#### 4.3.1 Bentuk `threads`

```json
"threads": [
  {
    "index": 0,
    "crashed": true,
    "name": "com.apple.main-thread",
    "frames": [
      {
        "index": 0,
        "object_name": "MerchantApp",
        "object_addr": "0x104a10000",
        "instruction_addr": "0x104a2c810",
        "is_app": true,
        "symbol_name": null,
        "file": null,
        "line": null
      }
    ]
  }
]
```

| Field frame | Keterangan |
|---|---|
| `index` | Urutan frame dalam thread (0 = terdalam) |
| `object_name` | Nama binary/modul pemilik alamat ini |
| `object_addr` | Alamat basis binary tersebut |
| `instruction_addr` | Alamat instruksi — inilah yang diterjemahkan symbolication |
| `is_app` | **Diisi SDK saat capture.** `true` bila frame berada di binary utama aplikasi (atau framework milik aplikasi), `false` untuk binary sistem. |
| `symbol_name`, `file`, `line` | `null` sampai symbolication berjalan; diisi backend (BE-11) |

> **`is_app` sengaja diisi SDK, bukan diturunkan konsumen.** SDK tahu persis binary utama aplikasinya saat crash terjadi; Frontend tidak, dan menebaknya dari nama akan rapuh (nama modul bisa mirip, framework milik aplikasi tidak akan terdeteksi).
>
> **Konsekuensi penting: penyorotan frame aplikasi TIDAK menunggu symbolication.** Alamat sudah dapat dipetakan ke binary pemiliknya lewat `binary_images` sejak awal, jadi "frame ini milik kode kita" sudah diketahui meski nama fungsinya belum ada. Justru di crash yang belum tersimbolikasi inilah penandanya paling berguna — ia memisahkan "crash di kode kita" dari "crash di framework sistem". FE-06 dapat berjalan penuh sebelum BE-10/11 ada.

**Bentuk `exception_backtrace` (sejak SDK 0.0.15).** Bukan array thread, melainkan satu daftar frame — persis elemen `frames` di atas:

```json
"exception_backtrace": [
  { "index": 0, "object_name": "CoreFoundation", "object_addr": "0x180a00000", "instruction_addr": "0x180a3f2c4", "is_app": false, "symbol_name": null, "file": null, "line": null },
  { "index": 1, "object_name": "MerchantApp", "object_addr": "0x104a10000", "instruction_addr": "0x104a2c810", "is_app": true, "symbol_name": null, "file": null, "line": null }
]
```

Dikirim sebagai string JSON (satu atribut string, konvensi yang sama dengan `threads`), hanya bila ada, maksimum 64 frame. `is_app` dihitung SDK dengan aturan yang sama dengan `threads`. Backend memakai `binary_images` event yang sama untuk menyimbolkannya; untuk `crash_type: "exception"` ia adalah sumber frame yang lebih tepat daripada stack thread yang crash.

#### 4.3.2 Bentuk `binary_images`

```json
"binary_images": [
  { "name": "MerchantApp", "uuid": "A1B2C3D4-...", "base_addr": "0x104a10000",
    "size": 2457600, "arch": "arm64", "is_app": true }
]
```

`uuid` wajib — inilah yang mencocokkan binary dengan berkas symbol (dSYM/mapping) yang benar. Symbol dari build lain tidak akan cocok.

### 4.4 `error` — dilaporkan manual

| Atribut | Keterangan |
|---|---|
| `name`, `message` | Deskripsi error |
| `domain`, `code` | Untuk NSError/Exception |
| `handled` | Selalu `true` |
| `source_file` | Berkas asal pemanggilan, terisi otomatis. **iOS: `#fileID`, bukan `#file`.** Android: nama berkas dari stack frame pemanggil. |
| `source_function` | Nama fungsi pemanggil, terisi otomatis (`#function` di iOS) |
| `source_line` | Nomor baris pemanggil, terisi otomatis (`#line` di iOS). **Tampilan saja — tidak masuk fingerprint.** |
| `custom.*` | Key kustom dari developer. Maks 20 key, masing-masing ≤ 256 karakter. |

> **Wajib `#fileID`, bukan `#file`.** `#file` menghasilkan path lengkap dari mesin build — mis. `/Users/<nama>/dev/proyek/Sources/…` — yang membocorkan nama pengguna dan struktur direktori mesin build ke storage monitoring. Pola scrubbing SEC-05 mencari nomor telepon/email/JWT, **tidak** akan menangkap ini. `#fileID` menghasilkan bentuk pendek `Modul/Berkas.swift` yang aman.
>
> Ketiga atribut ini terisi oleh compiler di lokasi pemanggilan, tanpa biaya runtime dan tanpa developer perlu mengingat apa pun.

### 4.5 `breadcrumb`

| Atribut | Keterangan |
|---|---|
| `category` | `navigation` \| `user_action` \| `network` \| `lifecycle` \| `state` \| `log` |
| `message` | Deskripsi singkat |
| `level` | `debug` \| `info` \| `warning` \| `error` |

#### 4.5.1 Snapshot breadcrumb pada `crash` / `error`

Breadcrumb tidak dikirim sebagai event tersendiri ke disk. Ia hidup di ring buffer memori (100 terakhir) dan **ikut terlampir sebagai satu atribut `breadcrumbs`** ketika `crash` atau `error` terjadi. Nilainya adalah **string berisi JSON array** — konsumen perlu men-decode-nya sekali lagi:

```json
"breadcrumbs": "[{\"timestamp\":\"2026-09-01T08:30:57.309Z\",\"category\":\"network\",\"level\":\"info\",\"message\":\"connectivity_restored\"}]"
```

| Field entri | Keterangan |
|---|---|
| `timestamp` | ISO-8601 UTC. Frontend menampilkannya **relatif terhadap crash/error** (mis. `-4.2s`), bukan jam dinding |
| `category` | Sama dengan enum di atas |
| `level` | Sama dengan enum di atas |
| `message` | Teks dari developer atau dari sumber otomatis |

Urutan array kronologis: entri terakhir adalah yang paling dekat dengan kejadian.

> Alasan bentuknya string, bukan array bersarang: dengan menjadi satu atribut string biasa, snapshot ini otomatis melewati lapisan scrubbing yang sama dengan atribut lain (SEC-05) tanpa perlu jalur khusus. Pesan breadcrumb ditulis developer, jadi ia termasuk jalur kebocoran PII yang paling sering.

#### 4.5.2 Integritas payload breadcrumb

Karena `breadcrumbs` adalah string yang perlu di-decode sekali lagi (§4.5.1), ia punya
mode kegagalan yang tidak dimiliki atribut lain: **string-nya bisa sampai dalam keadaan
tidak bisa di-parse.** Kontrak ini mewajibkan mode itu ditangani secara eksplisit.

**Wajib: gagal parse ≠ breadcrumb kosong.** Keduanya adalah keadaan yang berbeda dan
tidak boleh diruntuhkan menjadi satu nilai. Sesi bersih memang tidak punya breadcrumb
secara desain (§10), sehingga "kosong" adalah keadaan sah dan sering terjadi — justru
karena itu, konsumen yang menampilkan payload rusak sebagai "kosong" akan menyembunyikan
kerusakan di balik keadaan yang wajar, dan kerusakan itu tidak akan pernah terlihat.

Setiap konsumen yang men-decode `breadcrumbs` **wajib**:

| Keadaan | Kewajiban |
|---|---|
| Atribut tidak ada, atau array kosong | Laporkan sebagai kosong — ini normal, bukan anomali |
| Decode berhasil | Laporkan sebagai utuh, sertakan isinya |
| Decode gagal | Laporkan sebagai **rusak**, dengan nilai yang berbeda dari "kosong". String mentahnya **dipertahankan**, tidak dibuang |

Melempar exception lalu mengembalikan `null`/`[]` tanpa penanda apa pun **melanggar
kontrak ini**, sekalipun tidak ada yang crash.

Bentuk pelaporannya di Read API ada di §10 (`breadcrumbs_status`).

**Batas ukuran & perilaku pemotongan — ditetapkan 2026-09-25.**

Ada DUA batas berbeda di sini, yang sempat tertukar dalam pengamatan lama (diarsipkan di
bawah) — sekarang dibedakan eksplisit:

- **Kapasitas ring buffer** (`02-Mobile-SDK.md` MOB-13): 100 entri breadcrumb terakhir yang
  disimpan di memori app. Ini soal berapa banyak breadcrumb yang app tahu, bukan berapa
  banyak yang dikirim.
- **Budget encoder** (APMKit `CrashBreadcrumbEncoder.maxEncodedBytes`,
  `Sources/APMKit/Crash/CrashBreadcrumbEncoder.swift:32`): 1000 byte — margin di bawah cap
  1024 byte milik KSCrash sendiri (`KSUSERINFO_MAX_STRING_LENGTH`,
  `KSCrashMonitor_UserInfo.c:41`) untuk string yang di-mirror ke crash report. Ini soal
  berapa banyak isi ring buffer yang MUAT dikirim tanpa memicu truncation upstream yang
  merusak JSON.

Sesi terverifikasi 2026-09-25 memuat 39 crumb di ring buffer (di bawah kapasitas 100 — ring
buffer tidak membuang apa pun di sesi ini) dan hanya 8 yang muat dalam budget 1000 byte
tersebut; 31 dibuang oleh budget encoder, bukan oleh kapasitas ring buffer. Pembuangan
dilakukan SDK sendiri, newest-first (crumb tertua dibuang lebih dulu) — bukan oleh KSCrash.

#### `breadcrumbs_dropped`

Field pendamping `breadcrumbs`, dikirim terpisah dari payload breadcrumb itu sendiri.

| Aspek | Kontrak |
|---|---|
| Tipe & asal | Ditulis SDK sebagai **string** (KVS milik KSCrash hanya menerima string), di-coerce ke integer satu kali di titik baca (`apm-ingest/readapi.py`'s `_parse_breadcrumbs_dropped`). Konsumen lain wajib melakukan coercion yang sama di satu titik, bukan di tiap pemanggil. |
| Makna | Jumlah crumb yang dibuang oleh **budget encoder** di atas sebelum upload — BUKAN oleh kapasitas ring buffer. Ring buffer men-drop crumb tertua secara diam-diam jauh sebelum crash terjadi (perilaku normalnya, di luar hitungan ini); field ini hanya menghitung apa yang dibuang SDK saat mem-budget sisa crumb untuk crash report. |
| Tiga keadaan | (1) **Angka, termasuk 0** — 0 ditulis eksplisit setiap kali tidak ada yang dibuang, tidak pernah diomit. (2) **null karena field tidak ada** — SDK dari sebelum perbaikan buffer crash (APMKit `cfc5aedd`) tidak pernah mengirim field ini. (3) **null karena tidak bisa di-parse** — nilai hadir tapi bukan integer valid, diduga kerusakan pipeline. **(2) dan (3) berasal dari sebab berbeda dan tidak boleh diruntuhkan menjadi satu "null".** |
| Hubungan dengan §4.5.1 | §4.5.1 menetapkan crumb yang TERSIMPAN tetap urut kronologis, tua ke baru. Yang DIBUANG untuk mencapai itu adalah crumb **tertua** — kebalikan dari intuisi "yang terbaru pasti dibuang duluan". Ditulis eksplisit karena kontra-intuitif dan pernah salah dinyatakan.

**Pengamatan lama, diarsipkan sebagai konteks historis (bukan lagi TBD):**

- Pada data pilot lama, `breadcrumbs` pernah teramati sampai dalam keadaan terpotong di
  tengah array sehingga tidak bisa di-parse — inilah bug yang bagian ini sekarang perbaiki.
- Titik potong yang teramati saat itu (~1024 / 567 / 456 karakter) berasal dari database
  yang sudah dihapus dan tidak bisa diperiksa ulang. Rantai sebab di atas menjelaskan
  MENGAPA truncation terjadi dan mengapa ia merusak JSON, tapi angka 567/456 itu sendiri
  tidak rekonsiliasi dengan cap 1024 byte KSCrash di bawah mekanisme manapun yang bisa
  ditelusuri — lihat koreksi 2026-09-25 di `APMKit/FEATURES.md` untuk detailnya. Dicatat
  sebagai pertanyaan terbuka, bukan fakta yang sudah selesai.

### 4.6 `lifecycle` / `performance`

| Atribut | Keterangan |
|---|---|
| `state` | `cold_start` \| `warm_start` \| `foreground` \| `background` \| `terminate` |
| `duration_ms` | Untuk cold start: waktu sampai frame pertama |

> `state: terminate` berarti aplikasi ditutup secara normal dan teramati **saat itu juga**. Proses yang mati mendadak tanpa sempat melapor adalah hal berbeda — lihat §4.7.

> **Celah ditemukan 2026-10-02: kontrak mendefinisikan lima `state`, SDK hanya pernah mengirim satu — dan bahkan itu pun opsional.** `warm_start`, `foreground`, `background`, `terminate` tidak punya kode pengirim sama sekali di APMKit (digrep di seluruh `Sources/APMKit/`, nihil) — hanya `cold_start` yang diimplementasikan (`ColdStartTracker.swift:51-55`), dan itu pun **host-invoked by design, not automatic** (`ColdStartTracker.swift:9`): SDK hanya menyediakan `recordFirstFrame(sink:sessionManager:)`, aplikasi host yang harus memanggilnya dari titik "first frame" miliknya sendiri. Pilot `edts-klikidm-ios` tidak pernah memanggilnya (digrep, nihil). Akibatnya **tidak ada satu pun event `type: "lifecycle"` yang pernah tercatat di database pilot** — dikonfirmasi langsung: `SELECT type, COUNT(*) FROM events GROUP BY type` hanya mengembalikan `crash`/`error`, tidak pernah `lifecycle`. Empat nilai `state` ini kelas MOB-01 (kontrak menjanjikan nilai yang sah, kode tidak pernah memenuhinya untuk keempatnya) — lihat §10's catatan `crash_free_sessions` untuk konsekuensinya pada metrik headline, dan `apm-docs/00-Overview.md` §11 untuk keputusan cakupan produk yang masih terbuka soal ini.
>
> **Ditutup sebagian, APMKit 0.0.9 (revisi):** `foreground` sekarang dikirim otomatis oleh SDK sendiri (bukan host-invoked) — khusus untuk menutup celah penyebut `crash_free_sessions`/`crash_free_users` di §10 — tapi **BUKAN saat `APM.start()`/app launch**. `didFinishLaunchingWithOptions` (tempat `start()` dipanggil) juga berjalan untuk proses yang diluncurkan murni di background (silent push `content-available`, background fetch); mengirim di titik itu akan menghitung sesi yang tidak pernah benar-benar dipakai ke penyebut, membuat crash-free rate terlihat terlalu bagus — kebalikan dari celah yang paragraf di atas gambarkan, tapi tetap sebuah kebohongan. Titik kirimnya sekarang: **saat sesi itu PERTAMA KALI benar-benar teramati di foreground** (`UIApplication.didBecomeActiveNotification`, bukan `willEnterForeground` — yang terakhir tidak pernah terpicu pada cold launch langsung ke foreground) atau saat `SessionManager` merotasi ke sesi baru (rotasi itu sendiri ADALAH kembali ke foreground), mana pun yang lebih dulu untuk `session_id` tersebut — dijaga satu-per-`session_id`, jadi tidak ada duplikasi dari urutan panggilan. **Konsekuensi yang disengaja: proses yang diluncurkan di background dan tidak pernah dibuka pengguna tidak mengirim apa pun, dan tidak pernah masuk ke penyebut manapun di §10** — ini bukan efek samping, ini seluruh alasan titik kirimnya dipindah. **Sesi pertama sebuah app (begitu benar-benar di foreground) mengirim `state: "foreground"`, BUKAN `state: "cold_start"`** — `cold_start` tetap host-invoked dan opsional seperti sebelumnya (lihat paragraf di atas), jadi pembaca tidak boleh menyimpulkan `cold_start` selalu terkirim untuk launch pertama. `warm_start`, `background`, `terminate` masih belum punya kode pengirim sama sekali.

### 4.7 `termination` — proses mati tanpa crash

Ditemukan **retrospektif saat peluncuran berikutnya**: proses sebelumnya mati tanpa sempat menulis penanda penutupan normal, dan bukan karena crash. Sifatnya seperti crash report (dilaporkan belakangan), bukan seperti lifecycle (dilaporkan langsung).

| Atribut | Tipe | Wajib |
|---|---|---|
| `termination_reason` | enum: `memory_limit` \| `memory_pressure` \| `cpu` \| `thermal` \| `low_battery` | ✓ |
| `time_since_launch_ms` | int | — |

**Aturan emit:** SDK **hanya** mengirim event ini bila penyebabnya termasuk enum di atas — yakni kondisi sumber daya kritis yang benar-benar teramati sebelum proses mati. Terminasi yang penyebabnya tidak diketahui OS (`unexplained`) **tidak dikirim sama sekali**.

> **Kenapa `unexplained` dibuang.** Kategori itu didominasi perilaku normal: user menggeser app dari app switcher, developer menekan Stop di Xcode, atau rebuild. Sistem operasi tidak memberi informasi lebih setelah SIGKILL, sehingga terminasi semacam ini tidak bisa dibedakan dari penutupan biasa dan tidak punya nilai diagnostik. Mengirimnya hanya menghasilkan derau bervolume tinggi.
>
> **Kenapa bukan `crash`.** Proses yang dihentikan dari luar tidak punya stack trace, tidak disebabkan kode aplikasi, dan tidak bisa dicegah olehnya. Menghitungnya sebagai crash membuat crash-free rate buruk secara palsu — selama pengembangan hampir setiap sesi berakhir demikian. Temuan ini muncul dari run nyata pertama pada app pilot. Prinsip yang sama (jangan hitung non-app-fault sebagai crash) sejak 2026-10-01 berlaku juga **di dalam** `type: "crash"` sendiri, untuk `is_fatal=false` — lihat catatan di §4.3.
>
> **Catatan paritas.** Android menghasilkan bentuk data yang setara lewat `ApplicationExitInfo` (`REASON_LOW_MEMORY` dan sejenisnya), jadi tipe event ini berlaku untuk kedua platform. Pemetaan nilai Android ke enum di atas ditetapkan saat port Android dikerjakan.

**Fingerprint (§6):** hash dari (`termination_reason` + versi app).

---

## 5. Enum `failure_category`

Nilai **identik di iOS & Android**. Dipakai untuk grouping dan filter di dashboard.

| Nilai | Arti |
|---|---|
| `ssl_certificate` | Sertifikat server tidak valid, kedaluwarsa, atau root tidak dikenal |
| `ssl_pinning_rejected` | Validasi pinning kustom aplikasi menolak sertifikat |
| `tls_handshake` | Handshake gagal di luar dua kasus di atas |
| `timeout` | Request melewati batas waktu |
| `dns` | Resolusi nama gagal |
| `connectivity` | Tidak ada koneksi atau koneksi terputus di tengah |
| `cancelled` | Dibatalkan aplikasi secara sengaja |
| `http_error` | Response diterima dengan status 4xx/5xx (aturan emit di §4.2) |
| `unknown` | Tidak terpetakan |

> **Catatan implementasi iOS:** validasi pinning kustom yang menolak koneksi umumnya muncul sebagai `NSURLErrorCancelled`, bukan sebagai error SSL. SDK harus membedakannya dari pembatalan biasa dengan menandai request yang gagal di tahap trust evaluation, lalu memetakannya ke `ssl_pinning_rejected`. Tanpa ini, kegagalan pinning tidak akan terlihat sebagai masalah keamanan di dashboard.

---

## 6. Fingerprinting (Grouping)

Backend mengelompokkan event menjadi **Issue**. Website menampilkan Issue, bukan event mentah — tanpa ini dashboard menjadi ribuan baris duplikat.

| Tipe | Aturan fingerprint |
|---|---|
| `crash` | **v2** (BE-11 tahap B): hash dari (`app_id` + nama exception/signal + `trap_kind` bila ada + kunci kanonik frame aplikasi teratas); bila frame tidak bisa diberi nama, dari pesan `crash_info_message` yang dinormalisasi (sejak SDK 0.0.15) — lihat bawah. **v1** (tetap berlaku untuk crash yang belum punya signature v2): hash dari (nama exception + `reason` yang dinormalisasi). |
| `network_failure` | Hash dari (host + `failure_category` + `status_code`) |
| `error` | Hash dari (domain + code + message yang dinormalisasi + `source_file` + `source_function`). **`source_line` sengaja TIDAK ikut** — lihat catatan di bawah. |
| `termination` | Hash dari (`termination_reason` + versi app) |

> **Kenapa `source_line` dikecualikan.** Menambah satu baris di atas lokasi pemanggilan akan menggeser nomor barisnya. Jika `source_line` ikut fingerprint, issue lama tampak "hilang" dan muncul issue baru yang identik — padahal bug-nya sama dan tidak ada yang diperbaiki. `source_file` + `source_function` cukup stabil terhadap penyuntingan biasa, sekaligus memisahkan dua error berbeda yang kebetulan punya domain, code, dan pesan serupa. `source_line` tetap dikirim dan ditampilkan untuk melompat ke kode.

### 6.1 Crash: pengelompokan v2 (BE-11 tahap B)

Crash yang symbolication-nya sudah menghasilkan frame aplikasi punya baris `event_fingerprint` versi 2 (signature = hash dari `app_id`, nama exception/signal, `trap_kind`, dan kunci kanonik frame aplikasi teratas; kunci kanonik membuang nomor closure, sehingga `closure #2 in X.f()` dan `closure #3 in X.f()` satu kunci). Crash yang tidak bisa diberi frame yang benar **dan** tidak membawa pesan trap yang bisa dipakai (tanpa thread, hanya frame tanpa nama, image tanpa dSYM) **tetap v1** — tidak ada tebakan.

| | v1 | v2 |
|---|---|---|
| `id` | 12 hex (`3821336ebbd7`) | `v2_` + 16 hex (`v2_3fa9c1d200b47e11`); tidak mungkin bertabrakan dengan v1 |
| `title` | `<nama> · <reason>` | `<label> in <frame teratas>`; label = `trap_kind` bila ada (huruf pertama kapital: `Index out of range in …`), selain itu nama exception/signal apa adanya |
| `fingerprint_version` | `1` | `2` |
| `fingerprint_basis` | `name+reason` (+ `fingerprint_note`, `id_stable: false`) | `frame` atau `crash_info` (`id_stable: true`, tanpa catatan) |

**Sumber frame (sejak SDK 0.0.15).** Untuk `crash_type = exception` (`NSException`, C++), frame diambil dari `attrs.exception_backtrace` — stack tempat exception DILEMPAR — bukan dari thread yang crash (di situ KSCrash hanya menaruh stack handler Crashlytics/reporter, sehingga semua `NSGenericException` dulu jatuh ke satu kunci yang sama). Backtrace itu di-decode dan di-symbolicate persis seperti thread (`binary_images` event yang sama); **semua** frame-nya alamat kembali, termasuk frame 0, sehingga pencariannya `alamat − 1`. Aturan jalan (walk) sama dengan thread: frame sistem/reporter dilewati, frame app tanpa nama menghentikan walk, dSYM utama yang belum ada menunda (`NotReady`). Bila backtrace tidak menghasilkan nama, thread yang crash dipakai seperti biasa. Event tanpa field itu (SDK ≤ 0.0.14, trap Swift, sinyal) tidak berubah sama sekali — signature-nya tetap yang lama, dan satu `NSException` baru berbagi grup dengan yang lama bila frame app tempat ia dilempar sama.

**`crash_info_message` sebagai cadangan (basis `crash_info`).** Pesan `<Module>/<File>.swift:<baris>: Fatal error: <pesan>` (§4.3) dipakai **hanya** bila walk frame berhenti karena frame app yang tidak bisa diberi nama (closure/thunk tanpa nama, alasan `unnamed-app-frame`) atau karena tidak ada frame app sama sekali (`no-app-frame`). Alasan berhenti lain (titik masuk, image app tanpa UUID atau tanpa dSYM, dSYM utama yang belum datang) **tidak** memakainya. Signature yang sudah dihasilkan walk frame **tidak pernah berubah** karena pesan ini; pesan tidak pernah menggantikan frame.

- Parser: `<path>:<baris>: ` lalu jenis (`Fatal error`, `Precondition failed`, `Assertion failed`, selain itu `other`) lalu pesan (boleh kosong). File = **basename** (`CartViewController.swift`; modul dan direktori adalah detail build). Baris disimpan sebagai metadata dan **tidak ikut signature** (alasan sama dengan `source_line` di atas). String yang tidak berbentuk itu dianggap "tidak dipahami" → tetap v1.
- Normalisasi pesan, berurutan: teks dalam kutip (`"…"`, “…”, `` `…` ``, `'…'`) → `"<str>"`; UUID → `<uuid>`; `0x…` → `0x#`; setiap deret angka → `#`; whitespace dilipat jadi satu spasi; dipotong **120 karakter**. Huruf besar/kecil dipertahankan.
- Signature = `sha256("v2|<app_id>|<nama exception/signal>|ci|<file>|<jenis>|<pesan ternormalisasi>")`. Pesan yang kosong setelah normalisasi → tetap v1 (tidak ada yang bisa dikelompokkan). Trap stdlib di build Release tidak membawa `crash_info_message`; event itu tetap memakai `trap_kind` seperti sebelumnya.
- `event_fingerprint` menyimpan `basis = 'crash_info'` dan `crash_info = {file, line, kind, message}` (pesan ternormalisasi); baris berbasis frame menyimpan keduanya `NULL`.
- Read API: `fingerprint_basis: "crash_info"`; `title` = `<jenis>: <pesan> in <File>.swift` (mis. `Fatal error: apm-test in CartViewController.swift`; jenis `other` tidak ditulis; pesan kosong → `Fatal error in <File>.swift`); detail membawa `crash_info: {file, line, kind, message}` dari event contoh (baris = milik event itu). Backoffice menjelaskannya di kartu "Grouping basis".
- **Privasi.** `crash_info_message` adalah teks bebas dari developer dan bisa memuat email, nomor kartu, atau token. Ia tidak pernah muncul di log backend mana pun (worker, ingest, Read API, rencana `fingerprintbackfill`); yang tersimpan di baris hanya bentuk ternormalisasinya. Dijaga test yang menangkap output log.

Saklar: Read API membaca **satu** setting proses, `APM_READ_GROUPING_V2` (default **mati**), dan setting itu berlaku sama untuk semua endpoint (daftar issue, detail, PATCH status, overview, user detail, integration). Mati = perilaku persis sebelum tahap B; `fingerprint_version` selalu `1`.

Event dengan `crash_type = hang` **tidak pernah** mendapat signature v2 (benang yang dibawanya hanyalah posisi main thread saat watchdog menembak); hang tetap v1.

**Tidak ada regroup diam-diam.** Saat saklar menyala:
- issue v1 yang kehilangan sebagian event: daftar membawa `regrouped_events` (jumlah event yang pindah), detail membawa `regrouped_into: [{id,title,events}]`;
- issue v2: detail membawa `merged_from: [{id,events}]`, `top_frame` (kunci kanonik), `trap_kind`;
- id v1 yang tidak punya event lagi menjawab **`404 {"error":"issue_moved","moved_to":[{id,title,events}]}`** (bukan `not_found`; hanya untuk app yang sama; hitungan mengikuti `days` dan `real_users_only`). `PATCH` ke id itu menjawab sama.

**Status per app.** `issue_status` berkunci `(app_id, issue_id)`; PATCH hanya menulis untuk app di path. Issue v2 yang belum pernah di-PATCH mewarisi status dari issue v1 asal event-nya **hanya bila semuanya sepakat** (`status_source: "carried"`); bila berbeda, statusnya `new` dengan `status_source: "conflict"`. Baris lama tanpa app (`app_id = ''`) dijawab sebagai fallback sampai `cmd/issuestatusscope` menyalinnya ke tiap app.

**Regresi (v1 dan v2 sama).** Issue `resolved` yang menerima event dengan `ts_server` lebih baru dari waktu resolve ditampilkan `status: "new"`, `regressed: true`, `resolved_at`. ("Resolve in version X" belum ada; backlog.)

Semua field baru bersifat aditif dan **hanya muncul bila berlaku** (kecuali `fingerprint_version`), jadi respons v1 tidak berubah satu byte pun untuk konsumen lama. Desain dan ukuran biaya query: [design/BE-11/tahap-B-plan.md](design/BE-11/tahap-B-plan.md).

> **Aturan ini milik Backend, tapi Frontend bergantung padanya.** Mengubah aturan mengubah pengelompokan data historis — perubahan wajib dikomunikasikan dan diberi versi.
>
> Kualitas fingerprint sangat bergantung pada normalisasi path (SEC-03b) yang dilakukan Mobile. Path yang tidak dinormalisasi menghasilkan ribuan Issue unik untuk satu masalah yang sama.

---

## 7. `POST /v1/ingest`

**Headers:** `X-APM-Key` (kunci per app) · `X-APM-Sdk` (nama & versi) · `Content-Type: application/json` · `Content-Encoding: gzip`

**Body:** Envelope (§2). Maksimum **1 MB terkompresi**, maksimum **200 event** per request.

> **Endpoint ini WAJIB menerima body tanpa `Content-Length` (`Transfer-Encoding: chunked`), bukan hanya body dengan `Content-Length` yang sudah dihitung.** OkHttp (SDK Android) men-stream body gzip tanpa menyatakan ukurannya lebih dulu, jadi setiap request dari device Android nyata datang chunked, tanpa `Content-Length` sama sekali — ini bukan kasus tepi, melainkan bentuk permintaan yang normal untuk platform tertentu. Kontrak ini sebelumnya diam soal ini, dan diamnya itu yang membuat dua implementasi menebak berbeda: `apm-backend` (Go, `net/http`) menangani `Transfer-Encoding: chunked` secara transparan tanpa perubahan apa pun (stdlib-nya men-dechunk sebelum handler melihat body), sedangkan `apm-ingest` sebelumnya hanya membaca `Content-Length` byte — untuk request chunked itu selalu 0, sehingga body yang terbaca kosong dan berakhir `400 malformed json`. Implementasi mana pun yang melayani endpoint ini wajib menangani kedua bentuk transfer tersebut, bukan hanya salah satunya.

### Kontrak response — wajib disepakati Mobile & Backend

| Kode | Arti | Aksi SDK |
|---|---|---|
| `202` | Diterima & diantrikan | Hapus batch dari disk |
| `400` | Payload malformed / schema tidak dikenal | **Buang batch** — jangan retry selamanya. Catat sebagai metrik internal. |
| `401` / `403` | Kunci tidak valid | Nonaktifkan pengiriman 24 jam, jangan hammer server |
| `413` | Payload terlalu besar | Pecah batch jadi setengah, retry |
| `429` | Rate limited | Backoff sesuai header `Retry-After` |
| `5xx` | Error server | Exponential backoff (30s → 60s → … → maks 30 menit), data tetap di disk |

> Kontrak ini sumber bug klasik kalau tidak disepakati di awal: SDK yang me-retry `400` selamanya akan menghabiskan baterai user dan membanjiri server dengan payload yang memang tidak akan pernah diterima.

**Idempotensi:** backend melakukan dedup berdasarkan `event_id` dengan window minimal **7 hari**. Dedup ini yang menyerap duplikat crash/termination: SDK iOS ≥ 0.0.13 memakai id laporan crash sebagai `event_id` (§4.3, §4.7), jadi laporan yang sama selalu ter-dedup, dan backend hanya boleh memvalidasi `event_id` sebagai string tidak kosong (jangan menolak berdasarkan huruf besar/kecil atau versi UUID).

---

## 8. `POST /v1/symbols`

Upload dari CI, **bukan dari device**. Autentikasi memakai token CI terpisah, bukan kunci app.

**Multipart:** `app_id`, `version`, `build`, `platform`, dan file — dSYM zip (iOS) atau `mapping.txt` (Android).

> **Gate rilis:** pipeline CI harus **gagal** jika upload symbol gagal. Tanpa symbol, crash report tidak terbaca dan datanya praktis hilang — dan baru ketahuan berminggu-minggu kemudian saat ada yang mencoba membaca crash.

---

## 9. `GET /v1/config`

SDK mengambil konfigurasi remote saat startup, dengan cache lokal dan fallback ke default bila gagal.

```json
{ "enabled": true,
  "sampling": { "network": 1.0, "breadcrumb": 1.0 },
  "max_batch": 200,
  "upload_interval_s": 30,
  "disabled_features": [] }
```

`enabled: false` berfungsi sebagai **kill switch** — memungkinkan menonaktifkan SDK di seluruh app tanpa rilis baru. Wajib ada sebelum rollout ke tim lain.

---

## 10. Read API (untuk Website)

Autentikasi **terpisah** dari ingestion — sesi user / SSO, bukan `X-APM-Key`.

> **Celah ditemukan 2026-10-02: penyebut `crash_free_sessions`/`crash_free_users` tidak pernah didefinisikan di kontrak ini.** `03-Backend.md` BE-16 dan `04-Frontend-Website.md` FE-01 menamai metriknya ("crash-free rate", "crash-free session rate") tapi tidak satu pun baris menyatakan populasi penyebutnya — bukan "seluruh sesi," bukan pula "sesi yang punya event lain." Implementasi (`apm-ingest/readapi.py`) diam-diam memilih `COUNT(DISTINCT session_id) FROM events` tanpa filter type — yaitu, penyebutnya adalah **sesi yang muncul di tabel `events` sama sekali**, bukan "seluruh sesi yang pernah berjalan." Karena §4.6 `lifecycle`'s empat `state` (`warm_start`/`foreground`/`background`/`terminate`) tidak pernah diimplementasikan dan `cold_start` hampir tidak pernah dipanggil (lihat catatan di §4.6), **sesi yang berjalan bersih (tidak ada crash, error, atau network request) tidak meninggalkan baris apa pun** — ia tidak pernah masuk hitungan sebagai sesi sama sekali, bukan dihitung sebagai "sesi sehat." Dikonfirmasi terhadap database pilot nyata: lima siklus pemakaian bersih berturut-turut (buka, pakai, tutup, tanpa crash/error disengaja) menghasilkan 0 baris; dari seluruh siklus pengujian, hanya sesi yang kebetulan memicu error/hang yang pernah muncul di `events`. Akibatnya `crash_free_sessions`/`crash_free_users` secara struktural **tidak bisa pernah mendekati nilai sehat** untuk app yang berperilaku normal — bukan karena crash-nya banyak, tapi karena sesi sehat tidak terlihat sama sekali oleh penyebutnya sendiri. Ini celah desain yang belum pernah diputuskan (bukan pelanggaran kontrak — tidak ada baris yang dilanggar, karena tidak ada baris yang pernah mendefinisikan ini), kelas yang sama dengan celah `is_fatal` yang ditutup 2026-10-01. Keputusan cakupan produk yang masih terbuka: `apm-docs/00-Overview.md` §11.

| Endpoint | Fungsi |
|---|---|
| `GET /v1/apps` | Daftar app yang boleh diakses user |
| `GET /v1/apps/{id}/overview` | Metrik ringkas + tren |
| `GET /v1/apps/{id}/issues` | Daftar issue (filter, sort, paginasi) |
| `GET /v1/issues/{id}` | Detail issue + stack tersimbolikasi + sample event |
| `GET /v1/issues/{id}/breadcrumbs` | Timeline breadcrumb untuk sample event |
| `GET /v1/apps/{id}/network` | Agregasi network per host & kategori. Dengan `&host=…&failure_category=…` mengembalikan blok `drilldown` — lihat catatan di bawah. |
| `POST /v1/apps/{id}/users/resolve` | Menerima identifier mentah (mis. nomor telepon/email), meng-hash-nya dengan `server_key`, mengembalikan `user_ref`. **Input tidak disimpan.** Ini yang membuat "cari user via nomor HP" tetap bisa tanpa menyimpan nomornya. |
| `GET /v1/apps/{id}/users/{user_ref}` | Ringkasan user + timeline sesi (layar User Lookup). Tiap sesi membawa `timeline` (dari event tersimpan) dan `breadcrumbs` — lihat catatan di bawah. |
| `GET /v1/apps/{id}/integration` | Data peringatan integrasi (`04` §3.8): `user_id_source`, `sdk_health`, versi SDK terpasang vs terbaru, dan status symbolication. Setiap blok membawa `available` — bila `false`, kondisi itu **belum bisa dinyatakan** dan Frontend tidak boleh menampilkannya sebagai "aman". |
| `PATCH /v1/issues/{id}` | Ubah status (triaged / resolved / ignored) |
| `GET/POST /v1/apps/{id}/alerts` | Konfigurasi alert |

> **Filter device-integrity berlaku lintas endpoint.** Endpoint `overview`, `issues`, dan `network` menerima parameter filter `is_emulator`, `is_rooted`, `is_dev_mode` — termasuk opsi "kecualikan sesi non-real (emulator/debug)" untuk metrik headline (FE-22).

> **`sdk_versions` per entri — field tambahan (feat-016 follow-up).** Tiap entri array `sdk_versions` pada `GET /v1/apps/{id}/integration` membawa, selain `sdk_name`/`version`/`sessions`/`latest_known`/`is_outdated`:
>
> | Field | Arti |
> |---|---|
> | `installs` | `COUNT(DISTINCT install_id)` dalam jendela — ukuran adopsi yang lebih jujur daripada `sessions`: satu install yang membuka app berkali-kali menghasilkan banyak sesi tapi tetap satu install. Selalu hadir (bukan `null`), karena setiap entri berasal dari baris `GROUP BY` dengan ≥1 event. |
> | `last_seen` | ISO 8601, `MAX(ts_server)` untuk pasangan `(sdk_name, version)` itu dalam jendela. Memungkinkan Frontend membedakan versi lama yang **masih aktif** dari versi lama yang cuma sisa data beberapa hari lalu (`04` §3.8) — lihat catatan ambang 24 jam di sana. |
>
> `installs` dan `last_seen` dihitung identik di `readapi.py` (pilot) dan `integration.go` (Go) — dibuktikan oleh `TestIntegrationParityAgainstRealPilot`.

> **Breadcrumb pada timeline sesi — apa yang ada dan apa yang tidak.** Breadcrumb hidup di ring buffer memori perangkat dan **hanya ikut terkirim ketika ada `crash` atau `error`** (§4.5.1). Konsekuensinya:
>
> | Hasil sesi | `breadcrumbs` | `breadcrumbs_status` | `timeline` |
> |---|---|---|---|
> | `crashed` / `errors` | Terisi — jejak lengkap sebelum kejadian | `ok` | Terisi |
> | `clean` | **Kosong** — tidak pernah dikirim | `ok` | Terisi |
> | payload gagal di-parse | **Kosong** — isinya tidak terbaca | `corrupt` | Terisi |
>
> Sesi bersih tidak punya breadcrumb **secara desain**, bukan karena datanya hilang. Mengirimkannya untuk setiap sesi akan melipatgandakan volume unggahan tanpa manfaat diagnostik — tidak ada yang perlu didiagnosa pada sesi yang berjalan normal.
>
> Karena itu setiap sesi juga membawa `timeline`, disusun dari event yang benar-benar tersimpan (request network, crash, error, termination, lifecycle). Untuk sesi bersih, inilah satu-satunya rekaman aktivitas yang tersedia — dan ia data nyata, bukan pengganti. Frontend memakai `breadcrumbs_available` untuk memilih tampilan, dan **tidak menampilkan hitungan breadcrumb pada sesi bersih**.
>
> **`breadcrumbs_status` — kenapa `breadcrumbs_available` saja tidak cukup.** `breadcrumbs_available` adalah boolean, sedangkan keadaan yang nyata ada tiga: terisi, kosong secara desain, dan **rusak** (§4.5.2). Payload yang gagal di-parse menghasilkan `breadcrumbs_available: false` — tidak bisa dibedakan dari sesi bersih yang memang tidak pernah mengirim breadcrumb. Karena "kosong" adalah keadaan yang sah dan sering, kerusakan jadi tidak terlihat sama sekali.
>
> Karena itu setiap respons yang memuat `breadcrumbs` **wajib** juga memuat `breadcrumbs_status` dengan nilai `ok` atau `corrupt`. Ini berlaku untuk `GET /v1/issues/{id}`, `GET /v1/issues/{id}/breadcrumbs`, dan tiap entri sesi pada `GET /v1/apps/{id}/users/{user_ref}`.
>
> `breadcrumbs_available` **tetap ada dan tidak berubah artinya** — penambahan ini bersifat aditif, konsumen lama tidak perlu diubah. Konsumen baru memakai `breadcrumbs_status` untuk membedakan rusak dari kosong.
>
> Ini penerapan langsung dari prinsip `04-Frontend-Website.md` §3.8: *jangan tampilkan "semua aman" untuk kondisi yang belum bisa dijawab.* `breadcrumbs_available: false` pada payload rusak adalah persis pelanggaran itu — tampak seperti sesi sehat tanpa breadcrumb, padahal datanya ada dan hilang terbaca.

> **Definisi `requests`, `failures`, `failure_rate`, dan persentil pada endpoint network** (per host, per route breakdown, DAN `network_failure_rate` overview — koreksi feat-010/feat-012, 2026-10-13; rumus `requests` DISEMPURNAKAN 2026-10-13 dengan komponen `GREATEST`, lihat di bawah). §4.2 di atas menetapkan: satu respons ≥ 400 **selalu** menghasilkan DUA baris event untuk SATU request — satu `network` (status asli) **ditambah** satu `network_failure(failure_category=http_error)`. Siapa pun yang mengimplementasikan endpoint ini (termasuk, secara eksplisit, pembaca Android SDK di masa depan) harus tahu persis bagaimana pasangan itu dihitung, supaya tidak menghitung satu request sebagai dua — dan supaya sebuah http_error yang KEHILANGAN pasangannya (lihat di bawah) tidak menghilang dari hitungan:
>
> ```
> requests = COUNT(network)
>          + COUNT(network_failure WHERE failure_category ≠ http_error)
>          + GREATEST(0, COUNT(network_failure WHERE failure_category = http_error)
>                        - COUNT(network WHERE status_code ≥ 400))
> ```
>
> | Metrik | Rumus | Kenapa |
> |---|---|---|
> | `requests` | Rumus `GREATEST` di atas, dihitung PER KELOMPOK (per host, per route, per app untuk overview) | Suku pertama+kedua: `network` dihitung apa adanya; `network_failure` non-`http_error` (`timeout`, `dns`, `ssl_*`, dst.) tidak punya pasangan `network` sama sekali, jadi tetap dihitung, sekali. Suku `GREATEST`: `http_error` idealnya TIDAK ditambahkan (request-nya sudah terhitung lewat baris `network` pasangannya, §4.2) — TAPI itu hanya benar selama pasangannya memang ada. `GREATEST(0, he - paired)` mengkreditkan PERSIS SELISIH `http_error` yang tidak terpasangkan dengan `network`-nya sendiri, sehingga tiap `http_error` yatim tetap terhitung sebagai satu request miliknya — bukan hilang dari penyebut, dan bukan pula membuat `failures` lebih besar daripada `requests`. |
> | `failures` | `COUNT(network_failure)` — tidak berubah | `http_error` TETAP dihitung sebagai kegagalan di sini; yang dikoreksi hanya penghitungannya di `requests`, bukan di `failures`. |
> | `failure_rate` | `100 × failures / requests`, atau **`null`** bila `requests = 0` | Konvensi Go yang sudah ada untuk pembagi nol (null, bukan 0 — "tidak ada data" bukan "0%"). Dengan rumus `GREATEST` di atas, `requests = 0` HANYA mungkin kalau kelompok itu benar-benar tidak punya event sama sekali (baik `network` maupun `network_failure`) — kelompok seperti itu tidak akan pernah muncul sebagai baris pada endpoint ini (GROUP BY tidak menghasilkan baris untuk kelompok kosong), jadi di level host/route ini secara praktis tidak teramati; `network_failure_rate` tingkat overview TETAP bisa 0 denominator sungguhan (app/window benar-benar kosong) dan sudah lama memakai null di sana. |
> | `p50` / `p95` / `p99` | Hanya dari durasi baris **`network`** | Baris `network_failure(http_error)` adalah KOPI dari durasi pasangan `network`-nya (request yang sama) — memasukkannya menghitung durasi itu dua kali. Baris kegagalan transport tidak pernah punya respons yang selesai sungguhan, jadi durasinya (kalau ada) tidak pernah mewakili latensi nyata dan tidak boleh ikut memengaruhi persentil — termasuk saat durasinya sangat panjang (mis. timeout), yang kalau diikutkan bisa menggeser `p99` jauh dari kenyataan. Komponen `GREATEST` TIDAK memengaruhi baris ini: persentil tetap hanya dari `network`, titik. |
>
> **Kenapa komponen `GREATEST` perlu ada sama sekali.** Versi pertama koreksi ini (per keputusan sebelumnya) langsung MENGECUALIKAN setiap `http_error` dari `requests`, dengan asumsi request-nya selalu sudah terhitung lewat pasangan `network`-nya. Asumsi itu TERBUKTI salah untuk kasus:
> - **SDK versi lama** yang belum mengimplementasikan pasangan ganda §4.2 (sebelum MOB-02b di Android, atau sebelum commit pertama APMKit untuk iOS) — mengirim `network_failure(http_error)` saja, tanpa `network` pendampingnya.
> - **Platform lain, atau bug di satu platform** yang menyimpang dari pasangan ganda yang didokumentasikan, tanpa diketahui dulu oleh server.
> - **Event yang hilang** di jalur transport/ingest (jaringan device putus persis setelah satu dari dua event terkirim) — tidak ada yang menjamin pasangan sampai berdua atau tidak sama sekali.
>
> Tanpa `GREATEST`, kasus-kasus itu membuat `requests` dihitung TERLALU RENDAH, dan pada kasus ekstrem membuat `failures > requests` — contoh nyata: 1 respons sukses berpasangan + 2 `http_error` tanpa pasangan dulu terbaca `requests=1, failures=2` → *failure rate* 200%, sebuah angka yang mustahil secara definisi. `GREATEST(0, he - paired)` memperbaiki ini tanpa mengubah perilaku untuk data yang pasangannya lengkap: kalau `he ≤ paired` (kasus normal, SDK yang patuh §4.2), suku itu bernilai 0 dan hasilnya identik dengan rumus pengecualian murni di atas.
>
> Sebelum koreksi pertama, `requests` menghitung setiap baris `network` + `network_failure` tanpa pembedaan — sebuah route yang 100% gagal dengan status 404 terbaca sebagai *fail rate* 50%, bukan 100%, karena setiap kegagalan ikut menggandakan pembilang request.

> **Blok `drilldown` pada endpoint network.** Diaktifkan dengan `&host=…` (opsional `&failure_category=…`) dan berisi, selain deret waktu per menit:
>
> | Field | Kegunaan |
> |---|---|
> | `failures`, `users_affected` | Skala dampak |
> | `started`, `last_seen`, `peak` | Kapan mulai dan puncaknya — untuk anotasi pada grafik |
> | `app_versions`, `platforms`, `os_versions` | Sebaran; masing-masing dengan `count` dan `pct` |
> | `affected_version_count` / `active_version_count` | Berapa versi terdampak dari berapa versi yang aktif mengirim ke host itu |
> | `all_active_versions_affected` | `true` bila **seluruh** versi aktif terdampak |
>
> Field terakhir itu yang mengubah callout "likely cause" (FE-11) dari dugaan menjadi kesimpulan berbukti: kegagalan yang melonjak di **semua versi aplikasi sekaligus** tidak mungkin berasal dari rilis baru — polanya menunjuk ke perubahan di sisi server (rotasi sertifikat terhadap klien yang mem-pin). Sebaliknya, kegagalan yang hanya muncul di satu versi menunjuk ke regresi di versi itu. Tanpa pembanding ini, Frontend hanya bisa menyatakan dugaan.

---

## 11. Aturan Perubahan Kontrak

| Jenis perubahan | Contoh | Perlakuan |
|---|---|---|
| **Aditif** | Menambah atribut opsional baru | Tidak menaikkan `schema_version`. Backend abaikan field yang tidak dikenal; Frontend tangani ketiadaannya. |
| **Breaking** | Menghapus/mengganti nama field, mengubah tipe, mengubah arti enum | Naikkan `schema_version`. Backend wajib mendukung versi lama selama masih ada app terpasang yang mengirimkannya. |
| **Enum bertambah nilai** | `failure_category` dapat nilai baru | Aditif, tapi Frontend wajib punya fallback tampilan untuk nilai yang belum dikenal. |

> **Konsekuensi khas mobile:** app versi lama akan tetap terpasang di device user berbulan-bulan setelah rilis baru. Backend tidak bisa berasumsi semua client sudah upgrade — dukungan multi-versi bersifat permanen, bukan sementara.
