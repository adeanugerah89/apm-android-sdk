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

### 2.1 Identifier: `user_id` (mentah, transit) → `user_ref` (tersimpan)

Model identifier sengaja dipisah dua, supaya app host bebas memakai identifier apa pun **tanpa** menaruh PII di storage APM:

- **`user_id`** — nilai mentah yang dikirim app (atau di-generate SDK). Boleh berupa nomor telepon/email/teks. Ada di envelope, **transit lewat TLS**, tidak pernah dipersistensi mentah.
- **`user_ref`** — hasil `HMAC-SHA256(server_key, user_id)` yang dihitung backend saat ingestion (BE-21). **Hanya nilai inilah yang disimpan** dan dipakai di seluruh downstream: Issue, sesi, Read API, dashboard. Opaque, tidak bisa dibalik tanpa `server_key` yang hanya ada di server.

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
| `event_id` | UUID v4. **Kunci deduplikasi di backend** — pengiriman bersifat *at-least-once*, event yang sama bisa datang dua kali. |
| `seq` | Counter monotonik per sesi. Dipakai backend untuk mengurutkan event ketika jam device tidak akurat. **Kualifikasi untuk `type: "crash"`** (APMKit 0.0.8+): SDK memulihkan `session_id` sebuah crash dari sesi yang benar-benar mengalaminya (lewat KSCrash user-info, ditulis sebelum proses mati), tapi `seq`-nya tetap berasal dari counter sesi PELAPOR (sesi baru yang membaca laporan crash tertunda saat proses berikutnya start) — objek `SessionManager` milik sesi yang crash sudah lenyap bersama prosesnya, sehingga counter aslinya tidak bisa dipulihkan. Akibatnya, untuk event `type: "crash"`, `seq` **tidak sebanding** dengan `seq` event lain yang berbagi `session_id` yang sama — jangan diasumsikan ikut deret monotonik sesi tersebut. Backend/dashboard yang mengurutkan event dalam satu sesi sebaiknya memakai `ts_client` untuk baris `crash`, bukan `seq`. |
| `ts_client` | Waktu dari device. **Tidak boleh dipercaya sebagai sumber kebenaran** — backend menyimpan `ts_server` terpisah (BE-05). |
| `ctx` | Konteks volatil yang bisa berubah dalam satu sesi. |

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
| `threads` | Array thread — bentuknya di §4.3.1 |
| `binary_images` | Daftar binary yang dimuat + UUID — **wajib** untuk symbolication; bentuknya di §4.3.2 |
| `app_state` | State saat crash |
| `time_since_launch_ms` | int |

> **`is_fatal=false` dikecualikan dari crash-free rate — prinsip yang sama dengan §4.7, diperluas.** Alasan `termination` bukan `crash` (§4.7: "Menghitungnya sebagai crash membuat crash-free rate buruk secara palsu") berlaku kata-demi-kata untuk `crash_type` apa pun di bawah `type: "crash"` yang `is_fatal`-nya `false` — misalnya `hang` yang pulih sendiri tanpa mematikan proses. Backend **wajib** mengecualikan baris `is_fatal=false` dari penghitung `crash_sessions`/`crash_users` (metrik crash-free), bukan hanya baris `type='termination'`. Baris `type='crash'` yang tidak punya `is_fatal` sama sekali (event lama, sebelum field ini ada) dihitung **sebagai fatal** — default yang menganggap tak-fatal akan diam-diam membuang crash sungguhan dari metrik, kegagalan yang jauh lebih buruk daripada memasukkan satu hang yang semestinya dikecualikan. Ditemukan 2026-10-01 dari data pilot nyata: KSCrash Watchdog yang tidak dijeda saat app di-background menghasilkan `crash_type: hang, is_fatal: false` persis pada siklus background/foreground, sebelum celah ini ditutup (lihat `FEATURES.md` APMKit, parked item, untuk perbaikan di sisi SDK yang masih tertunda).

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
| `crash` | Hash dari (tipe exception + N frame teratas non-sistem yang sudah dinormalisasi) |
| `network_failure` | Hash dari (host + `failure_category` + `status_code`) |
| `error` | Hash dari (domain + code + message yang dinormalisasi + `source_file` + `source_function`). **`source_line` sengaja TIDAK ikut** — lihat catatan di bawah. |
| `termination` | Hash dari (`termination_reason` + versi app) |

> **Kenapa `source_line` dikecualikan.** Menambah satu baris di atas lokasi pemanggilan akan menggeser nomor barisnya. Jika `source_line` ikut fingerprint, issue lama tampak "hilang" dan muncul issue baru yang identik — padahal bug-nya sama dan tidak ada yang diperbaiki. `source_file` + `source_function` cukup stabil terhadap penyuntingan biasa, sekaligus memisahkan dua error berbeda yang kebetulan punya domain, code, dan pesan serupa. `source_line` tetap dikirim dan ditampilkan untuk melompat ke kode.

> **Aturan ini milik Backend, tapi Frontend bergantung padanya.** Mengubah aturan mengubah pengelompokan data historis — perubahan wajib dikomunikasikan dan diberi versi.
>
> Kualitas fingerprint sangat bergantung pada normalisasi path (SEC-03b) yang dilakukan Mobile. Path yang tidak dinormalisasi menghasilkan ribuan Issue unik untuk satu masalah yang sama.

---

## 7. `POST /v1/ingest`

**Headers:** `X-APM-Key` (kunci per app) · `X-APM-Sdk` (nama & versi) · `Content-Type: application/json` · `Content-Encoding: gzip`

**Body:** Envelope (§2). Maksimum **1 MB terkompresi**, maksimum **200 event** per request.

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

**Idempotensi:** backend melakukan dedup berdasarkan `event_id` dengan window minimal **7 hari**.

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
