# AGENTS.md

APMKit Android SDK — APM monitoring SDK untuk Android, paritas dengan iOS SDK.
Router untuk kerja agen. Fakta ada di docs/; file ini adalah peta, bukan manual.

## Session startup

1. Load skill `edts-harness` di awal setiap sesi.
2. Baca **state file milikmu sendiri** — resolve dengan:
   `echo "state/$(git config user.name | tr '[:upper:] ' '[:lower:]-').md"`
   Hanya file milikmu yang dibaca penuh: active feature, last verify result, blocker, next step.
   Jangan pernah menulis ke state file orang lain.
3. Baca `CONSTITUTION.md` — aturan permanen dan keputusan yang sudah diambil. Selalu dalam konteks.
4. Jalankan `./verify.sh build` untuk konfirmasi baseline bersih sebelum mulai edit.
5. Pilih **satu** feature siap dari `FEATURES.md` (semua `Depends on`-nya ✅).
   Set status ke 🔵 dan mulai.

## Project overview

- **Stack:** Android SDK (Kotlin), Gradle wrapper, Maven internal untuk distribusi
- **Structure:** Android library module (`sdk/`, Gradle path `:sdk`), verification app (`app/`).
- **Docs:** `CONSTITUTION.md` (aturan), `FEATURES.md` (scope), `docs/` (PRD lengkap).
- **PRD utama:** `docs/00-Overview.md` · `docs/02-Mobile-SDK.md` · `docs/01-Kontrak-Data-API.md`

## Verification

Jalankan sebelum klaim done. Semua check harus pass.

```bash
./verify.sh build    # Gradle assembleDebug
./verify.sh test     # unit tests (./gradlew test)
./verify.sh lint     # ./gradlew lint
./verify.sh all      # ketiga-tiganya
```

`verify.sh` mencetak baris akhir `HARNESS_VERIFY: PASS` / `FAIL` — baris itu adalah buktimu.
Hanya check yang benar-benar ada di proyek ini yang terdaftar. Jangan karang langkah lint/test/e2e.

> **Catatan bootstrap:** `gradlew` belum ada karena proyek belum di-scaffold. Jalankan
> `verify.sh build` setelah feat-001 (scaffold proyek) selesai. Sebelum itu, `verify.sh`
> akan melaporkan FAIL dengan pesan yang menjelaskan situasinya — itu benar.

## Definition of done

Feature dinyatakan `✅` hanya bila: kriteria `Done when`-nya terpenuhi, `verify.sh` pass,
bukti tercatat di sub-tabel `FEATURES.md`-nya, dan state file diperbarui dengan next step.

## Session handoff

- Jaga state file tetap aktual secara real time — ubah status begitu berubah.
- Setelah setiap edit, tambahkan ke tabel `Changes` (file · apa · kenapa).
- Sebelum mengakhiri sesi: jalankan verify, catat hasilnya, tinggalkan state file yang bisa dilanjutkan sendiri.
- Saat feature tutup, rotasi detailnya ke `archive/features/<id>.md` dan ganti sel Evidence
  dengan link. Sama untuk sesi dan epic yang selesai. Tulis file archive **dulu**, baru hapus
  detailnya — urutan sebaliknya kehilangan bukti jika terjadi gangguan.

---

## Rules

See `CONSTITUTION.md`. Rules dipegang di sana agar tidak duplikat. Di sini cukup pointer:

- Pipeline order: **Capture → Scrub → Disk → Sync** — tidak pernah dibalik.
- SDK tidak boleh menjadi penyebab crash aplikasi host — setiap entry point wajib defensif.
- Tidak ada operasi I/O blocking di main thread.
- Tidak ada `!!` di Kotlin tanpa komentar yang membuktikan non-null.
- One feature active at a time per person.
- Never auto-commit.
- Evidence before done.
