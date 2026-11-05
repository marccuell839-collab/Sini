"""Consent flow regression tests use synthetic pixmaps, never a real screen."""
import pytest
pytest.importorskip('PySide6')
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import QApplication
from jarvis_win.core.config import Settings
from jarvis_win.ui.main_window import MainWindow
from jarvis_win.ui.screen_session import ScreenSession


@pytest.fixture
def session():
    app = QApplication.instance() or QApplication([])
    class Controller:
        settings = Settings(auto_speak=False)
    window = MainWindow(Controller.settings)
    obj = ScreenSession(window, Controller())
    yield obj
    window.close()


def test_capture_requires_fresh_source(session):
    with pytest.raises(RuntimeError):
        session.capture()
    session.source = ('window', 123)
    session.stop()
    assert session.source is None


def test_cancelled_preview_sends_nothing(session, monkeypatch):
    import jarvis_win.ui.screen_session as module
    monkeypatch.setattr(module, 'get_vision_key', lambda: 'test')
    monkeypatch.setattr(module, 'analyze', lambda *a: pytest.fail('uploaded'))
    session.source = ('window', 123)
    monkeypatch.setattr(session, 'capture', lambda: QPixmap(10, 10))
    monkeypatch.setattr(session, 'preview', lambda *a: False)
    session.send()
    assert not session.busy


def test_stop_invalidates_late_reply(session):
    session.source = ('window', 123)
    generation = session.generation
    session.stop()
    session._done(generation, 'old result')
    assert session.window.transcript.text() == ''


def test_close_revokes_source(session):
    session.window.cancel_requested.connect(session.stop)
    session.source = ('window', 123)
    session.window.close()
    assert session.source is None
