"""Security and error-path regression tests; no microphone/network needed."""
import os
from pathlib import Path

import numpy as np
import pytest

from jarvis_win.core.owner_voice import OwnerVoice, protect, normalized
from jarvis_win.core.listener import VoiceListener, ListenerCallbacks
from jarvis_win.core.stt import Transcript


def test_dpapi_roundtrip_and_corruption():
    if os.name != 'nt':
        pytest.skip('Windows DPAPI required')
    secret = b'{"voice": [0.2, 0.5]}'
    ciphertext = protect(secret)
    assert secret not in ciphertext
    assert protect(ciphertext, decrypt=True) == secret
    with pytest.raises(OSError):
        protect(ciphertext[:20], decrypt=True)


def test_enrollment_match_reject_and_forget(tmp_path, monkeypatch):
    if os.name != 'nt':
        pytest.skip('Windows DPAPI required')
    owner = OwnerVoice(tmp_path / 'voice.dpapi')
    monkeypatch.setattr(owner, 'embedding', lambda *args: normalized([1., 0., 0.]))
    owner.enroll([b'1', b'2', b'3'])
    assert owner.enrolled
    assert b'vector' not in owner.path.read_bytes()
    assert owner.match(b'voice')[0]
    monkeypatch.setattr(owner, 'embedding', lambda *args: normalized([0., 1., 0.]))
    assert not owner.match(b'stranger')[0]
    owner.forget()
    assert not owner.enrolled
    owner.forget()


def test_failed_reenrollment_keeps_previous(tmp_path, monkeypatch):
    owner = OwnerVoice(tmp_path / 'voice.dpapi')
    owner.path.write_bytes(b'previous encrypted record')
    monkeypatch.setattr(owner, 'embedding', lambda *args: normalized([1., 0.]))
    import jarvis_win.core.owner_voice as module
    def fail(*args):
        raise OSError('DPAPI unavailable')
    monkeypatch.setattr(module, 'protect', fail)
    with pytest.raises(OSError):
        owner.enroll([b'1'] * 3)
    assert owner.path.read_bytes() == b'previous encrypted record'


@pytest.mark.parametrize('pcm', [b'', bytes(16000 * 2 * 3), (np.ones(48000) * 32767).astype(np.int16).tobytes()])
def test_short_silent_clipped_audio_rejected(pcm):
    with pytest.raises(ValueError):
        OwnerVoice().embedding(pcm)


def test_invalid_vectors():
    for vector in ([0, 0], [float('nan'), 1], [[1, 2]]):
        with pytest.raises(ValueError):
            normalized(vector)


def test_inconsistent_samples_rejected(tmp_path):
    owner = OwnerVoice(tmp_path / 'voice.dpapi')
    with pytest.raises(ValueError):
        owner.save_embeddings([[1, 0], [0, 1], [1, 0]])
    assert not owner.enrolled


def test_identity_gate_precedes_transcription():
    class Stt:
        def transcribe(self, *args):
            pytest.fail('Unmatched audio must not reach STT')
    listener = VoiceListener(Stt(), ListenerCallbacks(allow_audio=lambda *args: False))
    listener._transcribe(b'audio')


def test_cancel_during_transcription_discards_result():
    seen = []
    class Stt:
        def transcribe(self, *args):
            listener._stop.set()
            return Transcript('delete files')
    listener = VoiceListener(Stt(), ListenerCallbacks(on_transcript=seen.append))
    listener._transcribe(b'audio')
    assert not seen


def test_real_bundled_model_loads():
    model = Path(__file__).resolve().parents[1] / 'models' / 'speaker.onnx'
    if not model.exists():
        pytest.skip('Download model using CI workflow first')
    t = np.arange(48000) / 16000
    pcm = (np.sin(2 * np.pi * 180 * t) * 8000).astype(np.int16).tobytes()
    result = OwnerVoice().embedding(pcm)
    assert len(result) > 32
    assert np.linalg.norm(result) == pytest.approx(1, abs=1e-5)
