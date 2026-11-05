"""Entry point for the LORA desktop app."""

from __future__ import annotations

import logging
import sys

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QAction, QIcon, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import QApplication, QMenu, QMessageBox, QSystemTrayIcon, QDialog, QFormLayout, QLineEdit, QComboBox, QDialogButtonBox, QLabel, QVBoxLayout, QSpinBox

from jarvis_win.core.config import Settings, secrets_available, get_openrouter_key, set_openrouter_key, set_vision_key
from jarvis_win.core.controller import Controller
from jarvis_win.core.files import DeletionPlan
from jarvis_win.core.state import AssistantState
from jarvis_win.ui.main_window import MainWindow
from jarvis_win.ui.theme import PALETTE

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
    vision_key = QLineEdit()
    vision_key.setEchoMode(QLineEdit.EchoMode.Password)
    vision_key.setPlaceholderText("اتركه فارغاً للإبقاء على المفتاح")
    form.addRow("مفتاح OpenRouter للرؤية فقط", vision_key)
    vision_model = QLineEdit(settings.vision_model)
    form.addRow("نموذج الرؤية (قد يكون مدفوعاً)", vision_model)
    name = QLineEdit(settings.user_name)
    form.addRow("اسمك (اختياري)", name)
    context = QLineEdit(settings.user_context)
    form.addRow("اهتماماتك، بلا أسرار (اختياري)", context)
    endpoint = QSpinBox()
    endpoint.setRange(300, 3000)
    endpoint.setValue(settings.endpoint_silence_ms)
    form.addRow("الصمت قبل الرد، ملي ثانية", endpoint)
    proactive = QSpinBox()
    proactive.setRange(0, 120)
    proactive.setValue(settings.proactive_minutes)
    form.addRow("تذكير محلي كل كم دقيقة (0 إيقاف)", proactive)
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
    if vision_key.text().strip() and not set_vision_key(vision_key.text().strip()):
        QMessageBox.warning(window, "LORA", "تعذر حفظ مفتاح الرؤية بأمان")
        return
    settings.vision_model = vision_model.text().strip() or "openrouter/free"
    settings.user_name = name.text().strip()[:80]
    settings.user_context = context.text().strip()[:2000]
    settings.endpoint_silence_ms = endpoint.value()
    settings.proactive_minutes = proactive.value()
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
    app.setQuitOnLastWindowClosed(False)  # closing the window keeps the tray

    settings = Settings.load()
    window = MainWindow(settings)
    controller = Controller(settings)
    from jarvis_win.ui.screen_session import ScreenSession
    screen_session = ScreenSession(window, controller)
    window.share_requested.connect(screen_session.choose)
    window.unshare_requested.connect(screen_session.stop)
    window.inspect_requested.connect(screen_session.inspect)
    window.vision_requested.connect(screen_session.send)
    controller.screen_requested.connect(screen_session.send)
    window.plan_requested.connect(screen_session.plan)
    window.cancel_requested.connect(screen_session.stop)
    # Opt-in local check-in: no model call, microphone start, or screen upload.
    proactive_timer = QTimer(window)
    proactive_timer.setInterval(60_000)
    elapsed = [0]
    def check_in():
        if settings.proactive_minutes <= 0:
            elapsed[0] = 0
            return
        if controller._state is not AssistantState.IDLE:
            elapsed[0] = 0
            return
        elapsed[0] += 1
        if elapsed[0] >= settings.proactive_minutes:
            elapsed[0] = 0
            text = "راني هنا خويا، تحب نعاونك في حاجة؟"
            window.show_text(text)
            if settings.auto_speak:
                controller.speak(text)
    proactive_timer.timeout.connect(check_in)
    proactive_timer.start()

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
    show_action.triggered.connect(lambda: (window.showNormal(), window.raise_(), window.activateWindow()))
    menu.addAction(show_action)

    listen_action = QAction("استماع", menu)
    listen_action.triggered.connect(lambda: controller.set_listening(True))
    menu.addAction(listen_action)

    stop_action = QAction("إيقاف", menu)
    stop_action.triggered.connect(controller.cancel)
    stop_action.triggered.connect(screen_session.stop)
    menu.addAction(stop_action)

    menu.addSeparator()
    quit_action = QAction("إغلاق", menu)
    quit_action.triggered.connect(app.quit)
    menu.addAction(quit_action)

    tray.setContextMenu(menu)
    tray.activated.connect(
        lambda reason: window.showNormal() if reason == QSystemTrayIcon.ActivationReason.Trigger else None
    )
    tray.show()

    # Window-scoped: a true system-wide hotkey needs an OS hook and is
    # tracked as unimplemented rather than faked here.
    QShortcut(
        QKeySequence(settings.push_to_talk_hotkey),
        window,
        activated=lambda: controller.set_listening(True),
    )

    app.aboutToQuit.connect(screen_session.stop)
    app.aboutToQuit.connect(controller.shutdown)
    window.set_state(AssistantState.IDLE)
    window.show()
    if not get_openrouter_key():
        QTimer.singleShot(400, lambda: _show_settings(window, settings, controller))
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
