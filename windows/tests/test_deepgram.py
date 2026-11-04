"""Deepgram contract and failure tests: no real key, microphone or network."""
import json
import pytest
import httpx
from jarvis_win.core.stt import DeepgramSttProvider, SttError
from jarvis_win.core.config import Settings


def body(text='افتح الحاسبة', confidence=.93):
    return {'results': {'channels': [{'alternatives': [{'transcript': text, 'confidence': confidence}]}]}}


def test_arabic_wav_request_and_transcript(monkeypatch):
    def post(url, **kwargs):
        assert url == 'https://api.deepgram.com/v1/listen'
        assert kwargs['headers'] == {'Authorization': 'Token test-key', 'Content-Type': 'audio/wav'}
        assert kwargs['params']['model'] == 'nova-3'
        assert kwargs['params']['language'] == 'ar'
        assert kwargs['content'][:4] == b'RIFF'
        assert kwargs['content'][8:12] == b'WAVE'
        assert kwargs['timeout'].connect == 8
        return httpx.Response(200, json=body())
    monkeypatch.setattr(httpx, 'post', post)
    result = DeepgramSttProvider(' test-key ').transcribe(bytes(32000))
    assert result.text == 'افتح الحاسبة'
    assert result.language == 'ar'
    assert result.confidence == .93
    assert result.duration_ms == 1000


@pytest.mark.parametrize('key', [None, '', ' '])
def test_missing_key_fails_before_upload(key, monkeypatch):
    monkeypatch.setattr(httpx, 'post', lambda *a, **k: pytest.fail('Must not upload without a key'))
    provider = DeepgramSttProvider(key)
    assert not provider.is_available()
    with pytest.raises(SttError, match='Deepgram'):
        provider.transcribe(bytes(32000))


@pytest.mark.parametrize('status,message', [(401,'المفتاح'),(403,'المفتاح'),(402,'رصيد'),(429,'حصة'),(400,'400'),(503,'503')])
def test_service_errors_are_actionable_and_never_expose_key(monkeypatch,status,message):
    key='sensitive-test-key'
    monkeypatch.setattr(httpx,'post',lambda *a,**k:httpx.Response(status,text=key))
    with pytest.raises(SttError,match=message) as error:
        DeepgramSttProvider(key).transcribe(bytes(32000))
    assert key not in str(error.value)


@pytest.mark.parametrize('error',[httpx.ConnectError('offline'),httpx.ReadTimeout('late')])
def test_network_errors_are_readable(monkeypatch,error):
    def post(*a,**k): raise error
    monkeypatch.setattr(httpx,'post',post)
    with pytest.raises(SttError,match='Deepgram'):
        DeepgramSttProvider('key').transcribe(bytes(32000))


@pytest.mark.parametrize('payload',[{}, {'results':{'channels':[]}}, body(None)])
def test_malformed_success_is_rejected(monkeypatch,payload):
    monkeypatch.setattr(httpx,'post',lambda *a,**k:httpx.Response(200,json=payload))
    with pytest.raises(SttError,match='نتيجة غير صالحة'):
        DeepgramSttProvider('key').transcribe(bytes(32000))


def test_no_speech_is_empty_not_an_error(monkeypatch):
    monkeypatch.setattr(httpx,'post',lambda *a,**k:httpx.Response(200,json=body('')))
    assert DeepgramSttProvider('key').transcribe(bytes(32000)).is_empty


def test_invalid_confidence_is_not_invented(monkeypatch):
    monkeypatch.setattr(httpx,'post',lambda *a,**k:httpx.Response(200,json=body('مرحبا','unknown')))
    assert DeepgramSttProvider('key').transcribe(bytes(32000)).confidence is None


def test_empty_audio_does_not_upload(monkeypatch):
    monkeypatch.setattr(httpx,'post',lambda *a,**k:pytest.fail('Empty audio must not upload'))
    assert DeepgramSttProvider('key').transcribe(b'').is_empty


def test_invalid_pcm_rejected_before_upload(monkeypatch):
    monkeypatch.setattr(httpx,'post',lambda *a,**k:pytest.fail('Invalid audio must not upload'))
    with pytest.raises(SttError):
        DeepgramSttProvider('key').transcribe(b'x')
    with pytest.raises(SttError):
        DeepgramSttProvider('key').transcribe(bytes(16000),8000)


def test_default_uses_deepgram_without_whisper():
    assert Settings().stt_provider == 'deepgram'


def test_deepgram_secret_uses_credential_store_only(monkeypatch,tmp_path):
    from jarvis_win.core import config
    saved={}
    monkeypatch.setattr(config,'_set',lambda key,value:saved.setdefault(key,value) is not None)
    monkeypatch.setattr(config,'_get',lambda key:saved.get(key))
    monkeypatch.setattr(Settings,'path',classmethod(lambda cls:tmp_path/'settings.json'))
    assert config.set_deepgram_key('private-test-key')
    assert config.get_deepgram_key() == 'private-test-key'
    Settings().save()
    assert 'private-test-key' not in Settings.path().read_text()
