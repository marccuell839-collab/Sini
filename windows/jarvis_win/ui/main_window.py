"""Main assistant window."""

from __future__ import annotations

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QFont, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QComboBox,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
    QApplication,
)

from jarvis_win.core.config import Settings
from jarvis_win.core.intent import route
from jarvis_win.core.state import AssistantState, StateMachine
from jarvis_win.ui.theme import PALETTE, STATE_COLOR, stylesheet
from jarvis_win.ui.orb import JarvisOrb


class MainWindow(QWidget):
    """The assistant surface: identity, state, visualiser, input, controls.

    This window owns presentation only. It emits intent and lets the
    controller decide what to run, so no UI code performs actions.
    """

    #: Emitted when the user submits text or finishes dictating.
    utterance_submitted = Signal(str)
    #: Emitted when the user asks to stop whatever is running.
    cancel_requested = Signal()
    #: Emitted when the user toggles the microphone.
    mic_toggled = Signal(bool)
    settings_requested = Signal()
    #: Emitted when the user picks a different voice.
    voice_changed = Signal(str)

    def __init__(self, settings: Settings, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.settings = settings
        self.machine = StateMachine()
        self.machine.subscribe(self._on_state_changed)
        self._mic_on = False

        self.setWindowTitle("LORA")
        self.setMinimumSize(640, 480)
        self.setStyleSheet(stylesheet())
        if settings.language == "ar":
            self.setLayoutDirection(Qt.LayoutDirection.RightToLeft)

        self._build()
        self._apply_state(AssistantState.IDLE)

        QShortcut(QKeySequence("Esc"), self, activated=self._on_cancel)

    # -------------------------------------------------------------- layout

    def _build(self) -> None:
        # A single surface; controls live in Settings or on the orb itself.
        self.settings_btn = QPushButton("⚙", self)
        self.settings_btn.setAccessibleName("الإعدادات")
        self.settings_btn.setToolTip("الإعدادات · F11 ملء الشاشة · Esc إيقاف")
        self.settings_btn.clicked.connect(self.settings_requested.emit)
        self.settings_btn.setFixedSize(48, 48)
        self.visualizer = JarvisOrb(self)
        self.visualizer.set_reduced_motion(self.settings.motion_intensity != "full")
        self.visualizer.setCursor(Qt.CursorShape.PointingHandCursor)
        self.visualizer.setToolTip("اضغط للاستماع أو الإيقاف · Space")
        self.visualizer.mouseReleaseEvent = lambda event: self.toggle_listening()
        self.status_label = QLabel(self)
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.transcript = QLabel(self)
        self.transcript.setWordWrap(True)
        self.transcript.setAlignment(Qt.AlignmentFlag.AlignCenter)
        # Compatibility entry points for integrations, never placed on the surface.
        self.input = QLineEdit(self)
        self.input.hide()
        self.mic_btn = QPushButton(self)
        self.mic_btn.setCheckable(True)
        self.mic_btn.hide()
        self.cancel_btn = QPushButton(self)
        self.cancel_btn.hide()
        self.connection_dot = QLabel(self)
        self.connection_dot.hide()
        self.voice_picker = QComboBox(self)
        self.voice_picker.hide()
        self.voice_picker.currentIndexChanged.connect(self._on_voice_changed)
        QShortcut(QKeySequence("Space"), self, activated=self.toggle_listening)
        QShortcut(QKeySequence("F11"), self, activated=self.toggle_fullscreen)
        QShortcut(QKeySequence("Ctrl+Q"), self, activated=QApplication.instance().quit)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        side = max(260, min(int(self.height() * .70), int(self.width() * .72), 820))
        x, y = (self.width() - side) // 2, (self.height() - side) // 2
        self.visualizer.setGeometry(x, y, side, side)
        self.settings_btn.move(self.width() - 72, 24)
        self.status_label.setGeometry(24, y + side - 12, self.width() - 48, 36)
        self.transcript.setGeometry(self.width() // 6, y + side + 28,
                                    self.width() * 2 // 3, max(36, self.height() - y - side - 40))

    def toggle_fullscreen(self) -> None:
        self.showNormal() if self.isFullScreen() else self.showFullScreen()

    def toggle_listening(self) -> None:
        if self.machine.busy or self._mic_on:
            self._on_cancel()
        else:
            self._on_mic(True)

    # --------------------------------------------------------------- state

    def _on_state_changed(self, previous: AssistantState, current: AssistantState) -> None:
        del previous
        self._apply_state(current)

    def _apply_state(self, state: AssistantState) -> None:
        self.status_label.setText(self.machine.status_ar + (" · اضغط الدائرة للتحدث" if state is AssistantState.IDLE else ""))
        if state is AssistantState.IDLE:
            self._mic_on = False
        self.visualizer.set_state(state)
        self.cancel_btn.setEnabled(self.machine.busy)
        colour = STATE_COLOR[state]
        self.status_label.setStyleSheet(f"color: {colour};")

        connected = state is not AssistantState.DISCONNECTED
        self.connection_dot.setStyleSheet(
            f"color: {PALETTE.success if connected else PALETTE.error}; font-size: 16px;"
        )

        # Success and error are momentary; drift back to idle on their own.
        if state in {AssistantState.SUCCESS, AssistantState.ERROR}:
            QTimer.singleShot(2200, self._settle)

    def _settle(self) -> None:
        if self.machine.state in {AssistantState.SUCCESS, AssistantState.ERROR}:
            self.machine.go(AssistantState.IDLE)

    def set_state(self, state: AssistantState) -> None:
        """Entry point for the controller."""
        self.machine.go(state)

    def set_connected(self, connected: bool) -> None:
        if not connected:
            self.machine.go(AssistantState.DISCONNECTED)
        elif self.machine.state is AssistantState.DISCONNECTED:
            self.machine.go(AssistantState.IDLE)

    def show_text(self, text: str) -> None:
        self.transcript.setStyleSheet("")
        self.transcript.setText(text)

    def show_error(self, message: str) -> None:
        self.transcript.setStyleSheet(f"color: {PALETTE.error};")
        self.transcript.setText(message)

    def populate_voices(self, voices) -> None:
        """Fill the picker with the voices this machine can actually use."""
        self.voice_picker.blockSignals(True)
        self.voice_picker.clear()
        if not voices:
            self.voice_picker.addItem("لا توجد أصوات متاحة", None)
            self.voice_picker.setEnabled(False)
        else:
            self.voice_picker.setEnabled(True)
            for voice in voices:
                self.voice_picker.addItem(voice.display, voice.id)
            index = self.voice_picker.findData(self.settings.voice)
            if index >= 0:
                self.voice_picker.setCurrentIndex(index)
        self.voice_picker.blockSignals(False)

    def _on_voice_changed(self, index: int) -> None:
        voice_id = self.voice_picker.itemData(index)
        if not voice_id:
            return
        self.settings.voice = voice_id
        self.settings.save()
        self.voice_changed.emit(voice_id)

    def append_text(self, chunk: str) -> None:
        """Append streamed model output without reflowing the whole label."""
        self.transcript.setText(self.transcript.text() + chunk)

    def push_amplitude(self, level: float) -> None:
        self.visualizer.push_amplitude(level)

    # ------------------------------------------------------------- actions

    def _on_submit(self) -> None:
        text = self.input.text().strip()
        if not text:
            return
        self.input.clear()
        self.transcript.setText("")
        # Show the local routing decision immediately; the controller still
        # owns execution and confirmation.
        intent = route(text)
        self.machine.go(AssistantState.EXECUTING if intent else AssistantState.THINKING)
        self.utterance_submitted.emit(text)

    def _on_mic(self, checked: bool) -> None:
        self._mic_on = checked
        self.mic_btn.setText("⏹ إيقاف الاستماع" if checked else "🎙 تحدّث")
        if checked and self.machine.can_go(AssistantState.LISTENING):
            self.machine.go(AssistantState.LISTENING)
        elif not checked and self.machine.state is AssistantState.LISTENING:
            self.machine.go(AssistantState.IDLE)
        self.mic_toggled.emit(checked)

    def _on_cancel(self) -> None:
        if not self.machine.busy and not self._mic_on:
            return
        self._mic_on = False
        self.cancel_requested.emit()
        self.mic_btn.setChecked(False)
        self.mic_btn.setText("🎙 تحدّث")
        self.machine.go(AssistantState.IDLE)
