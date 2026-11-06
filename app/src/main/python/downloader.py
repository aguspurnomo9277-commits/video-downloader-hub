import os
import json
from urllib.parse import urlparse
from yt_dlp import YoutubeDL

def save_progress(path, data):
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(temp, path)

def download(url, out_dir, progress_path, mode="Hub"):
    parsed = urlparse(url.strip())
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError("Tautan video tidak valid.")

    os.makedirs(out_dir, exist_ok=True)

    def hook(data):
        status = data.get("status", "downloading")
        downloaded = data.get("downloaded_bytes", 0)
        total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
        percent = round(downloaded * 100 / total, 1) if total else 0

        save_progress(progress_path, {
            "status": "Mengunduh..." if status == "downloading" else "Menyiapkan file...",
            "percent": percent,
            "speed": data.get("speed") or 0,
            "eta": data.get("eta")
        })

    options = {
        # Utamakan satu file yang sudah berisi video dan audio.
        # Tidak membutuhkan proses penggabungan FFmpeg.
        "format": "best",
        "outtmpl": os.path.join(out_dir, "%(title).100B [%(id)s].%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "retries": 3,
        "socket_timeout": 30,
        "progress_hooks": [hook],
    }

    save_progress(progress_path, {
        "status": "Menghubungi sumber video...",
        "percent": 0
    })

    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        filename = ydl.prepare_filename(info)

        if not os.path.isfile(filename):
            requested = info.get("requested_downloads") or []
            if requested:
                filename = requested[0].get("filepath") or requested[0].get("_filename")

        if not filename or not os.path.isfile(filename):
            raise RuntimeError("File hasil unduhan tidak ditemukan.")

    save_progress(progress_path, {
        "status": "Unduhan selesai",
        "percent": 100
    })
    return filename
