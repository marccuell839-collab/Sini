"""Speech to text, behind a replaceable provider.

Two engines ship: faster-whisper running locally (offline, free, good at
Arabic) and Azure Speech (lower latency, needs a key). Both take a complete
utterance — the endpointer in ``vad`` decides when that is — rather than
streaming partial guesses that keep rewriting themselves on screen.
"""

from __future__ import annotations

import logging
import wave
from abc import ABC, abstractmethod
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Final

log = logging.getLogger(__name__)

SAMPLE_RATE: Final = 16_000


@dataclass(frozen=True)
class Transcript:
    text: str
    language: str | None = None
    #: 0..1 where the engine reports one. None when it does not — never a
    #: made-up number, so the UI can tell "unsure" from "not measured".
    confidence: float | None = None
    duration_ms: int = 0

    @property
    def is_empty(self) -> bool:
        return not self.text.strip()


class SttProvider(ABC):
    """A speech recogniser."""

    id: str = "stt"
    label: str = "محرّك تعرّف"

    @abstractmethod
    def is_available(self) -> bool:
        """True when this engine can run right now."""

    @abstractmethod
    def transcribe(self, pcm: bytes, sample_rate: int = SAMPLE_RATE) -> Transcript:
        """Transcribe one utterance of 16-bit mono PCM."""

    def warm_up(self) -> None:
        """Optional: pay the model-loading cost before the user speaks."""


def pcm_to_wav(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> bytes:
    """Wrap raw PCM in a WAV container, which most engines expect."""
    buffer = BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)  # 16-bit
        handle.setframerate(sample_rate)
        handle.writeframes(pcm)
    return buffer.getvalue()


class DeepgramSttProvider(SttProvider):
    """Arabic Nova-3 transcription over HTTPS; no local ASR model download."""

    id = "deepgram"
    label = "Deepgram عربي (سحابي)"

    def __init__(self, api_key: str | None):
        self.api_key = api_key

    def is_available(self) -> bool:
        return bool(self.api_key and self.api_key.strip())

    def transcribe(self, pcm: bytes, sample_rate: int = SAMPLE_RATE) -> Transcript:
        import httpx
        import math

        if not self.is_available():
            raise SttError("أضف مفتاح Deepgram من إعدادات LORA للتعرّف على الكلام")
        if sample_rate != SAMPLE_RATE or len(pcm) % 2:
            raise SttError("صيغة الصوت غير صالحة: يلزم PCM 16-bit mono 16000 Hz")
        if not pcm:
            return Transcript(text="", language="ar")
        try:
            response = httpx.post(
                "https://api.deepgram.com/v1/listen",
                params={"model": "nova-3", "language": "ar", "smart_format": "true",
                        "punctuate": "true"},
                headers={"Authorization": f"Token {self.api_key.strip()}",
                         "Content-Type": "audio/wav"},
                content=pcm_to_wav(pcm, sample_rate),
                timeout=httpx.Timeout(25.0, connect=8.0),
            )
        except httpx.TimeoutException as exc:
            raise SttError("تأخر Deepgram؛ تحقق من الإنترنت وأعد المحاولة") from exc
        except httpx.HTTPError as exc:
            raise SttError("تعذّر الاتصال بـ Deepgram؛ تحقق من الإنترنت") from exc
        if response.status_code in (401, 403):
            raise SttError("رفض Deepgram المفتاح؛ راجعه في الإعدادات")
        if response.status_code == 402:
            raise SttError("رصيد Deepgram غير كافٍ؛ راجع حسابك")
        if response.status_code == 429:
            raise SttError("تجاوزت حصة Deepgram مؤقتاً؛ حاول بعد قليل")
        if response.status_code >= 400:
            raise SttError(f"خطأ من Deepgram (HTTP {response.status_code})")
        try:
            best = response.json()["results"]["channels"][0]["alternatives"][0]
            text = best["transcript"]
            if not isinstance(text, str):
                raise ValueError("Invalid transcript")
            confidence = best.get("confidence")
            if (isinstance(confidence, bool) or not isinstance(confidence, (int, float))
                    or not math.isfinite(confidence) or not 0 <= confidence <= 1):
                confidence = None
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise SttError("أرجع Deepgram نتيجة غير صالحة؛ أعد المحاولة") from exc
        return Transcript(text=text.strip(), language="ar", confidence=confidence,
                          duration_ms=len(pcm) * 1000 // (2 * sample_rate))


class WhisperSttProvider(SttProvider):
    """faster-whisper, running on this machine.

    The model is downloaded on first use, not bundled: shipping it would
    add hundreds of megabytes to the installer for something many users
    would replace with the cloud engine anyway.
    """

    id = "whisper"
    label = "ويسبر محلي (بلا إنترنت)"

    def __init__(self, model_size: str = "small", device: str = "auto", cache_dir: Path | None = None) -> None:
        self.model_size = model_size
        self.device = device
        self.cache_dir = cache_dir
        self._model = None

    def is_available(self) -> bool:
        try:
            import faster_whisper  # noqa: F401
        except ImportError:
            return False
        return True

    def warm_up(self) -> None:
        self._ensure_model()

    def _ensure_model(self):
        if self._model is not None:
            return self._model
        try:
            from faster_whisper import WhisperModel
        except ImportError as exc:
            raise SttError("حزمة faster-whisper غير مثبّتة") from exc

        # int8 keeps a CPU-only machine usable; CUDA is picked up when present.
        compute = "int8" if self.device in ("auto", "cpu") else "float16"
        try:
            self._model = WhisperModel(
                self.model_size,
                device=self.device,
                compute_type=compute,
                download_root=str(self.cache_dir) if self.cache_dir else None,
            )
        except Exception as exc:  # model download or load failure
            raise SttError(f"تعذّر تحميل نموذج ويسبر: {type(exc).__name__}") from exc
        return self._model

    def transcribe(self, pcm: bytes, sample_rate: int = SAMPLE_RATE) -> Transcript:
        import numpy as np

        model = self._ensure_model()
        # Whisper wants float32 in -1..1, not int16.
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        duration_ms = int(len(audio) / sample_rate * 1000)

        segments, info = model.transcribe(
            audio,
            language="ar",
            beam_size=1,  # greedy: much faster, and enough for short commands
            vad_filter=False,  # the endpointer already trimmed the utterance
            condition_on_previous_text=False,  # stops it inventing continuations
        )
        text = " ".join(segment.text.strip() for segment in segments).strip()

        return Transcript(
            text=text,
            language=getattr(info, "language", "ar"),
            confidence=None,  # whisper reports log-probs, not a calibrated score
            duration_ms=duration_ms,
        )


class AzureSttProvider(SttProvider):
    """Azure Speech recognition over REST, for when latency matters."""

    id = "azure"
    label = "أزور (سحابي، أسرع)"

    def __init__(self, api_key: str | None, region: str | None, language: str = "ar-DZ") -> None:
        self.api_key = api_key
        self.region = region
        self.language = language

    def is_available(self) -> bool:
        return bool(self.api_key and self.region)

    def transcribe(self, pcm: bytes, sample_rate: int = SAMPLE_RATE) -> Transcript:
        if not self.is_available():
            raise SttError("مفتاح أزور أو المنطقة غير مضبوطين")

        import httpx

        url = (
            f"https://{self.region}.stt.speech.microsoft.com"
            f"/speech/recognition/conversation/cognitiveservices/v1"
        )
        duration_ms = int(len(pcm) / 2 / sample_rate * 1000)

        try:
            response = httpx.post(
                url,
                params={"language": self.language, "format": "detailed"},
                headers={
                    "Ocp-Apim-Subscription-Key": self.api_key,
                    "Content-Type": f"audio/wav; codecs=audio/pcm; samplerate={sample_rate}",
                    "Accept": "application/json",
                },
                content=pcm_to_wav(pcm, sample_rate),
                timeout=15.0,
            )
        except httpx.HTTPError as exc:
            raise SttError(f"تعذّر الوصول إلى خدمة التعرّف: {type(exc).__name__}") from exc

        if response.status_code in (401, 403):
            raise SttError("خدمة التعرّف رفضت المفتاح")
        if response.status_code == 429:
            raise SttError("تجاوزت حصّة التعرّف، حاول بعد قليل")
        if response.status_code >= 400:
            raise SttError(f"خطأ من خدمة التعرّف (HTTP {response.status_code})")

        body = response.json()
        status = body.get("RecognitionStatus")
        if status == "NoMatch":
            return Transcript(text="", language=self.language, duration_ms=duration_ms)
        if status and status != "Success":
            raise SttError(f"لم يُتعرَّف على الكلام ({status})")

        best = (body.get("NBest") or [{}])[0]
        return Transcript(
            text=best.get("Display") or body.get("DisplayText", ""),
            language=self.language,
            confidence=best.get("Confidence"),
            duration_ms=duration_ms,
        )


class SttError(RuntimeError):
    """Recognition failed for a reason worth showing the user."""


def pick_provider(providers: list[SttProvider], preferred_id: str | None = None) -> SttProvider | None:
    """First available provider, preferring the one the user chose."""
    if preferred_id:
        for provider in providers:
            if provider.id == preferred_id and provider.is_available():
                return provider
    for provider in providers:
        if provider.is_available():
            return provider
    return None
