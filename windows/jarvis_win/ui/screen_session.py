"""Session-only screen consent, local previews, and confirmed desktop steps."""
import json
import threading
from PySide6.QtCore import QObject, Signal, QBuffer, QIODevice, Qt, QTimer
from PySide6.QtWidgets import (QApplication, QDialog, QVBoxLayout, QLabel, QPushButton,
                              QInputDialog, QMessageBox, QDialogButtonBox)
from jarvis_win.core.config import get_vision_key, get_openrouter_key
from jarvis_win.core.planner import propose
from jarvis_win.core.desktop import parse_plan, perform
from jarvis_win.core.screen import windows, capture_window, prepare_action
from jarvis_win.core.vision import analyze


class ScreenSession(QObject):
    completed = Signal(int, str)
    failed = Signal(int, str)
    proposed = Signal(int, str)

    def __init__(self, window, controller):
        super().__init__(window)
        self.window, self.controller = window, controller
        self.source = None
        self.generation = 0
        self.busy = False
        self.completed.connect(self._done)
        self.failed.connect(self._failed)
        self.proposed.connect(self._review_proposal)

    def _done(self, generation, text):
        if generation == self.generation and self.source is not None:
            self.busy = False
            self.window.show_text(text)
            if self.controller.settings.auto_speak:
                self.controller.speak(text)

    def _failed(self, generation, text):
        if generation == self.generation:
            self.busy = False
            self.window.show_error(text)

    def stop(self):
        self.generation += 1
        self.source = None
        self.busy = False
        self.window.share_status.setText("الشاشة غير مشاركة")

    def choose(self):
        self.stop()
        try:
            sources = [(('screen', i), f"شاشة كاملة: {s.name()}")
                       for i, s in enumerate(QApplication.screens())]
            sources += [(('window', hwnd), title) for hwnd, title in windows()
                        if hwnd != int(self.window.winId())]
            labels = [f"{i+1}. {label}" for i, (_, label) in enumerate(sources)]
            choice, ok = QInputDialog.getItem(self.window, "مشاركة الشاشة لهذه الجلسة",
                "اختر المصدر. اللقطات محلية حتى توافق على إرسال لقطة معينة.", labels, 0, False)
            if ok:
                self.source = sources[labels.index(choice)][0]
                self.window.share_status.setText("مشاركة محلية: " + choice + " — أوقفها وقت تحب")
        except Exception:
            self.window.show_error("تعذر اختيار الشاشة على هذا الجهاز")

    def capture(self):
        if self.source is None:
            raise RuntimeError("اختر الشاشة أو التطبيق أولاً")
        kind, ident = self.source
        if kind == 'window':
            return capture_window(ident)
        screens = QApplication.screens()
        if ident >= len(screens):
            self.stop()
            raise RuntimeError("الشاشة لم تعد متاحة")
        return screens[ident].grabWindow(0)

    def preview(self, pixmap, title, accept="موافقة"):
        dialog = QDialog(self.window)
        dialog.setWindowTitle(title)
        layout = QVBoxLayout(dialog)
        warning = QLabel("راجع الصورة: كلمات السر والرسائل والمفاتيح قد تظهر فيها. ألغِ إذا فيها بيانات حساسة.")
        warning.setWordWrap(True)
        layout.addWidget(warning)
        label = QLabel()
        label.setPixmap(pixmap.scaled(900, 600, Qt.AspectRatioMode.KeepAspectRatio))
        layout.addWidget(label)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel)
        yes = buttons.addButton(accept, QDialogButtonBox.ButtonRole.AcceptRole)
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setDefault(True)
        yes.clicked.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        return dialog.exec() == QDialog.DialogCode.Accepted

    def inspect(self):
        try:
            self.preview(self.capture(), "معاينة محلية — لا إرسال", "إغلاق المعاينة")
        except Exception as exc:
            self.window.show_error(str(exc))

    def send(self):
        if self.busy:
            return
        key = get_vision_key()
        if not key:
            self.window.show_error("أضف مفتاح OpenRouter للرؤية في الإعدادات")
            return
        try:
            pixmap = self.capture()
            generation = self.generation
            model = self.controller.settings.vision_model
            if not self.preview(pixmap, f"إرسال هذه اللقطة إلى OpenRouter — {model}", "إرسال هذه اللقطة فقط"):
                return
            if generation != self.generation or self.source is None:
                return
            buffer = QBuffer()
            buffer.open(QIODevice.OpenModeFlag.WriteOnly)
            pixmap.scaled(1600, 1200, Qt.AspectRatioMode.KeepAspectRatio).save(buffer, "JPEG", 75)
            jpeg = bytes(buffer.data())
            self.busy = True
            settings = self.controller.settings
            name, context = settings.user_name, settings.user_context
            def run():
                try:
                    text = analyze(key, jpeg, model, name, context)
                    self.completed.emit(generation, text)
                except Exception as exc:
                    self.failed.emit(generation, str(exc))
            threading.Thread(target=run, daemon=True, name="lora-vision").start()
        except Exception as exc:
            self.window.show_error(str(exc))

    def plan(self):
        if self.busy:
            return
        if self.source is None:
            self.window.show_error("اختر الشاشة أو التطبيق أولاً")
            return
        task, ok = QInputDialog.getText(self.window, "مهمة متعددة الخطوات", "واش حاب نديرو؟ يرسل النص فقط إلى Groq، بلا صور.")
        if not ok or not task.strip():
            return
        key = get_openrouter_key()
        generation = self.generation
        settings = self.controller.settings
        model, limit = settings.model, settings.max_tool_calls
        self.busy = True
        def run():
            try:
                self.proposed.emit(generation, propose(key, task, model, limit))
            except Exception as exc:
                self.failed.emit(generation, str(exc))
        threading.Thread(target=run, daemon=True, name="lora-plan").start()

    def _review_proposal(self, generation, raw):
        if generation != self.generation or self.source is None:
            return
        self.busy = False
        self.review_plan(raw)

    def review_plan(self, proposed):
        try:
            steps = parse_plan(proposed, self.controller.settings.max_tool_calls)
            names = {'inspect': 'شوفي التطبيق محلياً', 'verify': 'راجع النتيجة',
                     'click': 'انقري', 'type': 'اكتبي', 'scroll': 'مرري', 'open': 'افتحي التطبيق'}
            summary = "\n".join(f"{i+1}. {names[step.action]}: {json.dumps(step.args, ensure_ascii=False)}"
                                for i, step in enumerate(steps))
            if QMessageBox.question(self.window, "مراجعة الخطة المقترحة", summary,
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel,
                QMessageBox.StandardButton.Cancel) != QMessageBox.StandardButton.Yes:
                return
            generation = self.generation
            for i, step in enumerate(steps):
                if self.source is None or generation != self.generation:
                    raise RuntimeError("توقفت الخطة؛ مشاركة الشاشة انتهت")
                if step.action in {'inspect', 'verify'}:
                    if not self.preview(self.capture(), f"تحقق محلي {i+1}", "النتيجة صحيحة، تابع"):
                        break
                    continue
                # All mutations are sensitive, including clicks that could submit a form.
                detail = json.dumps(step.args, ensure_ascii=False)
                if QMessageBox.question(self.window, f"تأكيد خطوة {i+1}: {step.action}",
                    detail + "\nراجع الهدف. قد يرسل النقر رسالة أو يؤكد شراءً. هل تأذن بهذه الخطوة فقط؟",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel,
                    QMessageBox.StandardButton.Cancel) != QMessageBox.StandardButton.Yes:
                    break
                if generation != self.generation:
                    break
                self.controller.cancel()
                if step.action != 'open':
                    if self.source[0] != 'window':
                        raise RuntimeError("للتحكم اختر تطبيقاً واحداً؛ الشاشة الكاملة للمعاينة فقط")
                    hwnd = self.source[1]
                    prepare_action(hwnd, step)
                perform(step)
                if step.action == 'open':
                    self.window.show_text("طلبت فتح التطبيق. اختر نافذته ثم ابدأ خطة جديدة للتحقق والمتابعة.")
                    self.stop()
                    return
                # Human verifies the actual result; no automatic sensitive screenshot upload.
                if not self.preview(self.capture(), "تحقق من النتيجة قبل الخطوة التالية", "النتيجة صحيحة، تابع"):
                    break
            self.window.show_text("انتهت مراجعة الخطة. لم تُرسل صور الخطة إلى أي نموذج.")
        except Exception as exc:
            self.window.show_error(str(exc))
