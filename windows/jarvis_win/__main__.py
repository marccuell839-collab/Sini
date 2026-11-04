"""Entry point for the LORA desktop app."""

from __future__ import annotations

import logging
import sys

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QAction, QIcon, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import QApplication, QMenu, QMessageBox, QSystemTrayIcon, QDialog, QFormLayout, QLineEdit, QComboBox, QDialogButtonBox, QLabel, QVBoxLayout, QPushButton, QCheckBox

from jarvis_win.core.config import Settings, secrets_available, get_openrouter_key, set_openrouter_key
from jarvis_win.core.controller import Controller
from jarvis_win.core.files import DeletionPlan
from jarvis_win.core.state import AssistantState
from jarvis_win.ui.main_window import MainWindow
from jarvis_win.ui.theme import PALETTE
from jarvis_win.ui.enrollment import EnrollmentDialog
from jarvis_win.core.owner_voice import WARNING_AR

log = logging.getLogger("lora")


def _tray_icon() -> QIcon:
    """Draw a simple glyph so the build needs no binary asset."""
    pixmap = QPixmap(64, 64)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    painter.setBrush(Qt.GlobalColor.transparent)
    pen = painter.pen()
    pen.setColor(PALETTE.accent)
    pen.setWidth(6)
    painter.setPen(pen)
    painter.drawEllipse(10, 10, 44, 44)
    painter.drawLine(32, 22, 32, 42)
    painter.end()
    return QIcon(pixmap)


def _confirm_deletion(window: MainWindow, summary: str, plan: DeletionPlan) -> bool:
    """Show exactly what will go, and default to not deleting.

    Deletion is the one action with no undo, so the dialog lists every file
    rather than a count, and Cancel is the default button.
    """
    box = QMessageBox(window)
    box.setWindowTitle("تأكيد الحذف")
    box.setIcon(QMessageBox.Icon.Warning)
    box.setText(summary)
    box.setInformativeText(
        "نهائيًا — لا يمكن التراجع." if plan.permanent else "ستنتقل إلى سلّة المحذوفات ويمكن استرجاعها."
    )
    box.setDetailedText("\n".join(c.display for c in plan.candidates))

    delete_button = box.addButton("حذف", QMessageBox.ButtonRole.DestructiveRole)
    cancel_button = box.addButton("إلغاء", QMessageBox.ButtonRole.RejectRole)
    box.setDefaultButton(cancel_button)  # the safe option is the default
    box.setEscapeButton(cancel_button)
    box.exec()
    return box.clickedButton() is delete_button


def _show_settings(window: MainWindow, settings: Settings, controller: Controller) -> None:
    """Configure Groq securely through Windows Credential Manager."""
    controller.cancel()
    dialog = QDialog(window)
    dialog.setWindowTitle("إعدادات LORA")
    dialog.setMinimumWidth(440)
    layout = QVBoxLayout(dialog)
    layout.addWidget(QLabel("Groq للذكاء وصوت لورا. المفتاح محفوظ في Windows Credential Manager."))
    form = QFormLayout()
    key_input = QLineEdit()
    key_input.setEchoMode(QLineEdit.EchoMode.Password)
    key_input.setPlaceholderText("أدخل مفتاح Groq الجديد (اتركه فارغاً للإبقاء على الحالي)")
    form.addRow("مفتاح Groq", key_input)
    current_label = QLabel("مفتاح محفوظ ✓" if get_openrouter_key() else "لا يوجد مفتاح")
    form.addRow("الحالة", current_label)
    model_input = QLineEdit(settings.model)
    form.addRow("نموذج الذكاء", model_input)
    voice = QComboBox()
    for ident, label in [
        ("noura", "Noura — نورا"), ("aisha", "Aisha — عائشة"),
        ("lulwa", "Lulwa — لولوة"), ("abdullah", "Abdullah"),
        ("fahad", "Fahad"), ("sultan", "Sultan")
    ]:
        voice.addItem(label, ident)
    voice.setCurrentIndex(max(0, voice.findData(settings.voice)))
    form.addRow("صوت Groq", voice)
    layout.addLayout(form)
    warning = QLabel(WARNING_AR)
    warning.setWordWrap(True)
    layout.addWidget(warning)
    identity = QLabel("بصمة محفوظة محلياً ومشفرة" if controller.owner_voice.enrolled else "لم تُسجّل بصمة المالك")
    layout.addWidget(identity)
    enroll = QPushButton("تسجيل / استبدال بصمة المالك")
    def record_owner():
        if EnrollmentDialog(controller.owner_voice, dialog).exec() == QDialog.DialogCode.Accepted:
            identity.setText("تم حفظ البصمة مشفرة محلياً؛ المطابقة تجريبية وليست إثبات هوية")
    enroll.clicked.connect(record_owner)
    layout.addWidget(enroll)
    forget = QPushButton("حذف بصمة المالك")
    def forget_owner():
        try:
            controller.owner_voice.forget()
            identity.setText("حُذفت البصمة؛ المطابقة معطلة")
        except OSError:
            identity.setText("تعذّر حذف البصمة")
    forget.clicked.connect(forget_owner)
    layout.addWidget(forget)
    reduced = QCheckBox("تقليل الحركة")
    reduced.setChecked(settings.motion_intensity != "full")
    layout.addWidget(reduced)
    fullscreen = QPushButton("تبديل ملء الشاشة · F11")
    fullscreen.clicked.connect(window.toggle_fullscreen)
    layout.addWidget(fullscreen)
    quit_button = QPushButton("إغلاق LORA")
    quit_button.clicked.connect(QApplication.instance().quit)
    layout.addWidget(quit_button)
    buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save |
                               QDialogButtonBox.StandardButton.Cancel)
    buttons.accepted.connect(dialog.accept)
    buttons.rejected.connect(dialog.reject)
    layout.addWidget(buttons)
    if dialog.exec() != QDialog.DialogCode.Accepted:
        return
    if key_input.text().strip() and not set_openrouter_key(key_input.text().strip()):
        QMessageBox.warning(window, "LORA", "تعذر حفظ المفتاح بشكل آمن في Windows Credential Manager")
        return
    settings.motion_intensity = "reduced" if reduced.isChecked() else "full"
    window.visualizer.set_reduced_motion(reduced.isChecked())
    settings.voice = voice.currentData()
    settings.model = model_input.text().strip() or "openai/gpt-oss-20b"
    settings.tts_provider = "groq"
    settings.save()
    controller._tts_providers[0].key = get_openrouter_key()
    window.populate_voices(controller.available_voices())
    window.show_text("تم حفظ إعدادات LORA. جرّب الآن سؤالاً أو تحدّث معها بصوتك.")


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    app = QApplication(argv if argv is not None else sys.argv)
    app.setApplicationName("LORA")
    if "--smoke-test" in app.arguments():
        import numpy as np
        import faster_whisper
        import sounddevice
        from jarvis_win.core.owner_voice import OwnerVoice, protect
        assert protect(protect(b"lora-dpapi-smoke"), decrypt=True) == b"lora-dpapi-smoke"
        probe = OwnerVoice()
        t = np.arange(48000) / 16000
        pcm = (np.sin(2 * np.pi * 180 * t) * 8000).astype(np.int16).tobytes()
        assert np.isfinite(probe.embedding(pcm)).all()
        test_window = MainWindow(Settings())
        test_window.resize(1280, 720)
        test_window.show()
        for state in (AssistantState.LISTENING, AssistantState.UNDERSTANDING, AssistantState.THINKING, AssistantState.SPEAKING):
            test_window.set_state(state)
            app.processEvents()
            assert not test_window.grab().isNull()
        test_window.close()
        return 0
    app.setQuitOnLastWindowClosed(False)  # closing the window keeps the tray

    settings = Settings.load()
    window = MainWindow(settings)
    controller = Controller(settings)

    # --- controller to window --------------------------------------------
    controller.state_changed.connect(window.set_state)
    controller.level_changed.connect(window.push_amplitude)
    controller.text_shown.connect(window.show_text)
    controller.error_shown.connect(window.show_error)

    def on_approval(summary: str, plan: DeletionPlan) -> None:
        if _confirm_deletion(window, summary, plan):
            controller.approve_deletion(plan)
        else:
            controller.reject_deletion()

    controller.approval_requested.connect(on_approval)

    # --- window to controller --------------------------------------------
    window.utterance_submitted.connect(controller.handle_utterance)
    window.mic_toggled.connect(controller.set_listening)
    window.cancel_requested.connect(controller.cancel)
    window.settings_requested.connect(lambda: _show_settings(window, settings, controller))

    if not secrets_available():
        window.show_error(
            "لا يوجد مخزن بيانات اعتماد في هذا النظام، "
            "فلن تُحفَظ المفاتيح بين الجلسات."
        )

    recogniser = controller.stt_available()
    if recogniser is None:
        window.show_error(
            "لا يوجد محرّك تعرّف على الكلام. "
            "ثبّت faster-whisper: pip install -r requirements-stt.txt"
        )

    window.populate_voices(controller.available_voices())

    # --- tray --------------------------------------------------------------
    tray = QSystemTrayIcon(_tray_icon(), app)
    tray.setToolTip("LORA")
    menu = QMenu()

    show_action = QAction("إظهار النافذة", menu)
    show_action.triggered.connect(lambda: (window.showFullScreen(), window.raise_(), window.activateWindow()))
    menu.addAction(show_action)

    listen_action = QAction("استماع", menu)
    listen_action.triggered.connect(lambda: controller.set_listening(True))
    menu.addAction(listen_action)

    stop_action = QAction("إيقاف", menu)
    stop_action.triggered.connect(controller.cancel)
    menu.addAction(stop_action)

    menu.addSeparator()
    quit_action = QAction("إغلاق", menu)
    quit_action.triggered.connect(app.quit)
    menu.addAction(quit_action)

    tray.setContextMenu(menu)
    tray.activated.connect(
        lambda reason: window.showFullScreen() if reason == QSystemTrayIcon.ActivationReason.Trigger else None
    )
    tray.show()

    # Window-scoped: a true system-wide hotkey needs an OS hook and is
    # tracked as unimplemented rather than faked here.
    QShortcut(
        QKeySequence(settings.push_to_talk_hotkey),
        window,
        activated=lambda: controller.set_listening(True),
    )

    app.aboutToQuit.connect(controller.shutdown)
    window.set_state(AssistantState.IDLE)
    window.showFullScreen()
    if not get_openrouter_key():
        QTimer.singleShot(400, lambda: _show_settings(window, settings, controller))
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
