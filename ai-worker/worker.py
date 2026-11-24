import json
import logging
import math
import os
import subprocess
import sys
import wave
from functools import cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Lock

MAX_BYTES = 25 * 1024 * 1024
MAX_PAGES = 20
MAX_MEDIA_SECONDS = 600
MAX_SEGMENTS = 1920
MAX_TEXT_LENGTH = 120000
SLOT = Lock()


class IncompleteExtraction(Exception):
    pass


@cache
def ocr_model():
    os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
    from paddleocr import PaddleOCR

    local_models = {
        name: folder for name, folder in {
            "text_detection_model_dir": os.environ.get("RECORDON_OCR_DET_MODEL_DIR"),
            "text_recognition_model_dir": os.environ.get("RECORDON_OCR_REC_MODEL_DIR"),
        }.items() if folder
    }
    return PaddleOCR(
        device="cpu",
        text_detection_model_name="PP-OCRv5_mobile_det",
        text_recognition_model_name="korean_PP-OCRv5_mobile_rec",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        enable_mkldnn=False,
        cpu_threads=4,
        **local_models,
    )


@cache
def whisper_model():
    packages = os.environ.get("RECORDON_WHISPER_PACKAGES")
    if packages and packages not in sys.path:
        sys.path.append(packages)
    from faster_whisper import WhisperModel

    local_model = os.environ.get("RECORDON_WHISPER_MODEL_DIR")
    if local_model and not Path(local_model).is_dir():
        raise FileNotFoundError("Whisper model directory is missing")
    return WhisperModel(local_model or "small", device="cpu", compute_type="int8", cpu_threads=4,
                        local_files_only=bool(local_model))


def ocr_segments(path, page):
    results = []
    for output in ocr_model().predict(str(path)):
        for text, confidence, box in zip(output["rec_texts"], output["rec_scores"], output["rec_boxes"], strict=True):
            if confidence < 0.65:
                raise IncompleteExtraction()
            if text.strip():
                results.append({"text": text.strip(), "page": page, "box": [int(value) for value in box]})
    if not results:
        raise IncompleteExtraction()
    return results


def extract_image(path):
    from PIL import Image

    with Image.open(path) as image:
        if image.format not in {"PNG", "JPEG"} or image.width * image.height > 25_000_000:
            raise IncompleteExtraction()
        normalized = path.with_name("image.png")
        image.convert("RGB").save(normalized)
    return ocr_segments(normalized, 1)


def extract_pdf(path):
    import pypdfium2 as pdfium
    import pypdfium2.raw as pdfium_raw

    results = []
    with pdfium.PdfDocument(str(path)) as document:
        if not 1 <= len(document) <= MAX_PAGES:
            raise IncompleteExtraction()
        for index in range(len(document)):
            page = document[index]
            try:
                text_page = page.get_textpage()
                try:
                    text = text_page.get_text_bounded().strip()
                finally:
                    text_page.close()
                has_images = next(page.get_objects(filter=[pdfium_raw.FPDF_PAGEOBJ_IMAGE]), None) is not None
                if text:
                    results.append({"text": text, "page": index + 1})
                if not text or has_images:
                    scale = min(2, 2400 / max(page.get_size()))
                    bitmap = page.render(scale=scale)
                    try:
                        rendered = path.with_name("page.png")
                        bitmap.to_pil().save(rendered)
                        results.extend(ocr_segments(rendered, index + 1))
                    finally:
                        bitmap.close()
                check_size(results)
            finally:
                page.close()
    return results


def extract_media(path):
    import numpy

    audio = path.with_name("speech.wav")
    subprocess.run([
        os.environ.get("RECORDON_FFMPEG_PATH", "ffmpeg"),
        "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe",
        "-format_whitelist", "wav,mp3,mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,flac",
        "-i", str(path), "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
        "-t", str(MAX_MEDIA_SECONDS + 1), str(audio),
    ], check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with wave.open(str(audio)) as samples:
        frame_count = samples.getnframes()
        if not 0 < frame_count <= MAX_MEDIA_SECONDS * samples.getframerate():
            raise IncompleteExtraction()
        waveform = numpy.frombuffer(samples.readframes(frame_count), dtype="<i2").astype(numpy.float32) / 32768
    segments, _ = whisper_model().transcribe(waveform, task="transcribe", vad_filter=True)
    results = []
    for segment in segments:
        if not segment.text.strip():
            continue
        if not math.isfinite(segment.avg_logprob) or segment.avg_logprob < -1.0:
            raise IncompleteExtraction()
        results.append({"text": segment.text.strip(), "startMillis": round(segment.start * 1000),
                        "endMillis": round(segment.end * 1000)})
        check_size(results)
    if not results:
        raise IncompleteExtraction()
    return results


def check_size(segments):
    if len(segments) > MAX_SEGMENTS or sum(len(segment["text"]) for segment in segments) > MAX_TEXT_LENGTH:
        raise IncompleteExtraction()


EXTRACTORS = {"/extract/pdf": extract_pdf, "/extract/image": extract_image,
              "/extract/audio": extract_media, "/extract/video": extract_media}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(200 if self.path == "/healthz" else 404, {"up": self.path == "/healthz"})

    def do_POST(self):
        extract = EXTRACTORS.get(self.path)
        if extract is None:
            self.reply(404, {})
            return
        size = self.headers.get("Content-Length", "")
        if not size.isdecimal() or len(size) > 9 or not 0 < int(size) <= MAX_BYTES:
            self.reply(413, {})
            return
        if self.headers.get_content_type() != "application/octet-stream":
            self.reply(415, {})
            return
        if not SLOT.acquire(blocking=False):
            self.reply(429, {})
            return
        try:
            self.connection.settimeout(30)
            with TemporaryDirectory(prefix="recordon-") as directory:
                path = Path(directory) / "attachment"
                with path.open("wb") as destination:
                    remaining = int(size)
                    while remaining:
                        chunk = self.rfile.read(min(remaining, 1024 * 1024))
                        if not chunk:
                            raise IncompleteExtraction()
                        destination.write(chunk)
                        remaining -= len(chunk)
                segments = extract(path)
                check_size(segments)
        except IncompleteExtraction:
            self.reply(200, {"complete": False, "segments": []})
        except Exception as error:
            logging.warning("Extraction failed (%s)", type(error).__name__)
            self.reply(422, {"complete": False, "segments": []})
        else:
            self.reply(200, {"complete": bool(segments), "segments": segments})
        finally:
            SLOT.release()


if __name__ == "__main__":
    host = os.environ.get("RECORDON_EXTRACTOR_HOST", "127.0.0.1")
    port = int(os.environ.get("RECORDON_EXTRACTOR_PORT", "8181"))
    ThreadingHTTPServer((host, port), Handler).serve_forever()
