"""Local neural speaker similarity, never an authorization credential.

Only a normalized embedding is persisted, protected for the current Windows
user by DPAPI. Raw microphone samples exist in memory only.
"""
from __future__ import annotations

import json
import os
import sys
import threading
from pathlib import Path

import numpy as np

from .config import config_dir

MODEL_ID = "wespeaker_en_voxceleb_resnet34"
WARNING_AR = "الصوت وحده ليس مصادقة كافية للأوامر الخطرة. يمكن تقليده أو إعادة تشغيل تسجيل؛ يبقى التأكيد اليدوي مطلوباً."


def protect(data: bytes, decrypt: bool = False) -> bytes:
    if os.name != "nt":
        raise RuntimeError("حماية البصمة تتطلب Windows DPAPI")
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    # CRYPTPROTECT_UI_FORBIDDEN, current-user scope (no LOCAL_MACHINE flag).
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel.LocalFree(target.data)


def normalized(vector):
    v = np.asarray(vector, dtype=np.float32)
    if v.ndim != 1 or not np.isfinite(v).all() or np.linalg.norm(v) < 1e-8:
        raise ValueError("بصمة صوت غير صالحة")
    return v / np.linalg.norm(v)


class OwnerVoice:
    # Similarity threshold is experimental, not a calibrated probability.
    threshold = .65

    def __init__(self, path: Path | None = None):
        self.path = path or config_dir() / "owner-voice.dpapi"
        self._extractor = None
        self._lock = threading.RLock()

    @property
    def enrolled(self):
        return self.path.is_file()

    def embedding(self, pcm: bytes, sample_rate: int = 16000):
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
        if sample_rate != 16000 or len(audio) < 16000 * 1.5:
            raise ValueError("تحدث لمدة ثانيتين على الأقل للمطابقة")
        frames = audio[:len(audio) // 320 * 320].reshape(-1, 320)
        rms = np.sqrt(np.mean(frames ** 2, axis=1))
        if np.count_nonzero(rms > .008) < 50 or np.mean(np.abs(audio) > .99) > .03:
            raise ValueError("الصوت منخفض أو مشوّه؛ أعد التسجيل في مكان هادئ")
        with self._lock:
            if self._extractor is None:
                import sherpa_onnx
                base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[2]))
                model = base / "models" / "speaker.onnx"
                if not model.is_file():
                    raise RuntimeError("نموذج البصمة مفقود؛ أعد تنزيل حزمة LORA الكاملة")
                config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                    model=str(model), num_threads=2, provider="cpu")
                if not config.validate():
                    raise RuntimeError("تعذّر تحميل نموذج البصمة")
                self._extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
            stream = self._extractor.create_stream()
            stream.accept_waveform(sample_rate=sample_rate, waveform=audio)
            stream.input_finished()
            if not self._extractor.is_ready(stream):
                raise ValueError("التسجيل قصير جداً")
            return normalized(self._extractor.compute(stream))

    def enroll(self, samples: list[bytes]):
        if len(samples) != 3:
            raise ValueError("سجّل ثلاث عينات")
        vectors = [self.embedding(pcm) for pcm in samples]
        self.save_embeddings(vectors)

    def save_embeddings(self, vectors):
        if len(vectors) != 3:
            raise ValueError("سجّل ثلاث عينات")
        vectors = [normalized(v) for v in vectors]
        if min(float(np.dot(a, b)) for a in vectors for b in vectors) < .5:
            raise ValueError("العينات غير متناسقة؛ أعد التسجيل بنفس الصوت والميكروفون")
        vector = normalized(np.mean(vectors, axis=0))
        payload = json.dumps({"version": 1, "model": MODEL_ID, "vector": vector.tolist()}).encode()
        encrypted = protect(payload)
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temp = self.path.with_suffix(".tmp")
            temp.write_bytes(encrypted)
            temp.replace(self.path)

    def match(self, pcm: bytes, sample_rate=16000):
        with self._lock:
            payload = json.loads(protect(self.path.read_bytes(), decrypt=True))
        if payload.get("version") != 1 or payload.get("model") != MODEL_ID:
            raise ValueError("أعد تسجيل البصمة لهذا الإصدار")
        reference = normalized(payload["vector"])
        candidate = self.embedding(pcm, sample_rate)
        if reference.shape != candidate.shape:
            raise ValueError("أعد تسجيل البصمة")
        score = float(np.dot(reference, candidate))
        return score >= self.threshold, score

    def forget(self):
        with self._lock:
            self.path.unlink(missing_ok=True)
