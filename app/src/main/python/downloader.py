import os
import json
from urllib.parse import urlparse
from yt_dlp import YoutubeDL


def save_progress(path, data):
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(temp, path)


class DiagnosticLogger:
    def __init__(self):
        self.messages = []

    def debug(self, message):
        pass

    def warning(self, message):
        self.messages.append("PERINGATAN: " + str(message))

    def error(self, message):
        self.messages.append("ERROR: " + str(message))


def download(url, out_dir, progress_path, mode="Hub"):
    parsed = urlparse(url.strip())

    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError("Tautan video tidak valid.")

    os.makedirs(out_dir, exist_ok=True)

    logger = DiagnosticLogger()

    def hook(data):
        status = data.get("status", "downloading")
        downloaded = data.get("downloaded_bytes", 0)
        total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
        percent = round(downloaded * 100 / total, 1) if total else 0

        save_progress(progress_path, {
            "status": "Mengunduh..." if status == "downloading"
                      else "Menyiapkan file...",
            "percent": percent
        })

    options = {
        "format": "best",
        "outtmpl": os.path.join(
            out_dir, "%(title).100B [%(id)s].%(ext)s"
        ),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "logger": logger,
        "retries": 3,
        "socket_timeout": 30,
        "progress_hooks": [hook],
    }

    save_progress(progress_path, {
        "status": "Memeriksa sumber video...",
        "percent": 0
    })

    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
            filename = ydl.prepare_filename(info)

            if not os.path.isfile(filename):
                requested = info.get("requested_downloads") or []
                if requested:
                    filename = (
                        requested[0].get("filepath")
                        or requested[0].get("_filename")
                    )

            if not filename or not os.path.isfile(filename):
                raise RuntimeError("File hasil unduhan tidak ditemukan.")

    except Exception as exc:
        details = "\n".join(logger.messages[-8:])
        message = str(exc)

        if details:
            message += "\n\nDiagnostik:\n" + details

        raise RuntimeError(message) from exc

    save_progress(progress_path, {
        "status": "Unduhan selesai",
        "percent": 100
    })

    return filename
