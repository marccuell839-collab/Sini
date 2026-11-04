"""Headless checks that the window builds and its state wiring holds.

Runs under the Qt offscreen platform, so it works in CI with no display.
"""

import os

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

pytest.importorskip("PySide6")

from PySide6.QtWidgets import QApplication  # noqa: E402

from jarvis_win.core.config import Settings  # noqa: E402
from jarvis_win.core.state import AssistantState  # noqa: E402
from jarvis_win.ui.main_window import MainWindow  # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    app = QApplication.instance() or QApplication([])
    yield app


@pytest.fixture
def window(qapp):
    w = MainWindow(Settings())
    yield w
    w.close()
    w.deleteLater()


def test_window_builds(window):
    assert window.windowTitle() == "LORA"
    assert window.machine.state is AssistantState.IDLE


def test_arabic_layout_is_right_to_left(qapp):
    from PySide6.QtCore import Qt

    w = MainWindow(Settings(language="ar"))
    assert w.layoutDirection() == Qt.LayoutDirection.RightToLeft
    w.deleteLater()


def test_status_text_follows_state(window):
    window.set_state(AssistantState.LISTENING)
    assert window.status_label.text() == "أستمع إليك..."
    window.set_state(AssistantState.EXECUTING)
    assert window.status_label.text() == "أنفّذ الأمر..."


def test_cancel_enabled_only_while_busy(window):
    assert window.cancel_btn.isEnabled() is False
    window.set_state(AssistantState.THINKING)
    assert window.cancel_btn.isEnabled() is True
    window.set_state(AssistantState.SUCCESS)
    assert window.cancel_btn.isEnabled() is False


def test_cancel_emits_and_returns_to_idle(window):
    window.set_state(AssistantState.EXECUTING)
    seen = []
    window.cancel_requested.connect(lambda: seen.append(True))
    window._on_cancel()
    assert seen == [True]
    assert window.machine.state is AssistantState.IDLE


def test_disconnect_shows_disconnected(window):
    window.set_connected(False)
    assert window.machine.state is AssistantState.DISCONNECTED
    assert window.status_label.text() == "غير متصل"
    window.set_connected(True)
    assert window.machine.state is AssistantState.IDLE


def test_local_command_goes_straight_to_executing(window):
    emitted = []
    window.utterance_submitted.connect(emitted.append)
    window.input.setText("افتح يوتيوب")
    window._on_submit()
    assert emitted == ["افتح يوتيوب"]
    # Routed locally, so no model call: straight to EXECUTING.
    assert window.machine.state is AssistantState.EXECUTING


def test_open_question_goes_to_thinking(window):
    window.input.setText("لماذا السماء زرقاء")
    window._on_submit()
    assert window.machine.state is AssistantState.THINKING


def test_visualizer_accepts_amplitude(window):
    window.push_amplitude(0.7)
    window.visualizer._tick()
    assert window.visualizer._energy > 0

def test_main_surface_has_only_settings_button(window, qapp):
    from PySide6.QtWidgets import QPushButton, QLineEdit
    window.resize(1280, 720)
    window.show()
    qapp.processEvents()
    assert [b for b in window.findChildren(QPushButton) if b.isVisible()] == [window.settings_btn]
    assert not any(e.isVisible() for e in window.findChildren(QLineEdit))
    center = window.visualizer.geometry().center()
    assert abs(center.x() - window.width() / 2) <= 1
    assert abs(center.y() - window.height() / 2) <= 1


def test_orb_toggle_and_escape_stop(window):
    seen = []
    window.mic_toggled.connect(seen.append)
    window.toggle_listening()
    assert seen == [True]
    window._on_cancel()
    assert not window._mic_on
    assert window.machine.state is AssistantState.IDLE

def test_controller_identity_failure_closes_mic_and_fails_closed(monkeypatch, qapp):
    from jarvis_win.core import controller as module
    monkeypatch.setattr(module, 'get_azure_key', lambda: None)
    monkeypatch.setattr(module, 'get_openrouter_key', lambda: None)
    control = module.Controller(Settings())
    class Owner:
        enrolled = True
        def match(self, *args):
            raise OSError('corrupt DPAPI file')
    class Listener:
        def stop(self):
            self.stopped = True
    listener = Listener()
    control._listener = listener
    control.owner_voice = Owner()
    assert not control._verify_audio(b'voice', 16000)
    assert listener.stopped
    assert control._listener is None
    assert control._state is AssistantState.ERROR
