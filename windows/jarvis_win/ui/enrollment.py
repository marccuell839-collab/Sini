"""Explicit, cancellable enrollment; microphone audio is never saved."""
import threading

from PySide6.QtCore import Signal, Slot
from PySide6.QtWidgets import QDialog, QVBoxLayout, QLabel, QPushButton

from jarvis_win.core.owner_voice import OwnerVoice, WARNING_AR


class EnrollmentDialog(QDialog):
    progress = Signal(str)
    finished_recording = Signal(object, str)

    def __init__(self, owner: OwnerVoice, parent=None):
        super().__init__(parent)
        self.owner = owner
        self.samples = []
        self._busy = False
        self._cancel = threading.Event()
        self.setWindowTitle("تسجيل صوت المالك")
        self.setMinimumWidth(480)
        layout = QVBoxLayout(self)
        warning = QLabel(WARNING_AR)
        warning.setWordWrap(True)
        layout.addWidget(warning)
        self.label = QLabel("سجّل 3 عينات، كل واحدة 6 ثوانٍ. تحدث بجمل مختلفة في مكان هادئ.")
        self.label.setWordWrap(True)
        layout.addWidget(self.label)
        self.record = QPushButton("تسجيل العينة 1 / 3")
        self.record.clicked.connect(self._record)
        layout.addWidget(self.record)
        self.cancel_button = QPushButton("إلغاء")
        self.cancel_button.clicked.connect(self.reject)
        layout.addWidget(self.cancel_button)
        self.progress.connect(self.label.setText)
        self.finished_recording.connect(self._done)

    @Slot()
    def _record(self):
        if self._busy:
            return
        self._busy = True
        self.record.setEnabled(False)
        self._cancel.clear()

        def run():
            try:
                import sounddevice as sd
                blocks = []
                with sd.RawInputStream(samplerate=16000, channels=1, dtype="int16", blocksize=1600) as stream:
                    for i in range(60):
                        if self._cancel.is_set():
                            self.finished_recording.emit(None, "أُلغي التسجيل")
                            return
                        if i % 10 == 0:
                            self.progress.emit(f"تحدث الآن… {6 - i // 10}")
                        data, overflow = stream.read(1600)
                        if overflow:
                            raise ValueError("انقطع التسجيل؛ أعد المحاولة")
                        blocks.append(bytes(data))
                pcm = b"".join(blocks)
                vector = self.owner.embedding(pcm)
                self.finished_recording.emit(vector, "")
            except Exception as exc:
                self.finished_recording.emit(None, f"تعذّر التسجيل: {exc}")
        threading.Thread(target=run, daemon=True, name="lora-enroll").start()

    @Slot(object, str)
    def _done(self, pcm, error):
        self._busy = False
        self.record.setEnabled(True)
        if self._cancel.is_set():
            self.samples.clear()
            super().reject()
            return
        if error:
            self.label.setText(error)
            return
        self.samples.append(pcm)
        if len(self.samples) == 3:
            # Only embeddings remain; DPAPI save is brief and atomic.
            try:
                self.owner.save_embeddings(self.samples)
            except Exception as exc:
                self.samples.clear()
                self.label.setText(f"تعذّر حفظ البصمة: {exc}")
                self.record.setText("إعادة التسجيل 1 / 3")
                return
            self.samples.clear()
            self.accept()
        else:
            self.label.setText("تم قبول العينة. غيّر الجملة ثم سجّل العينة التالية.")
            self.record.setText(f"تسجيل العينة {len(self.samples) + 1} / 3")

    def reject(self):
        self._cancel.set()
        if self._busy:
            self.label.setText("جارٍ إلغاء التسجيل…")
            return
        self.samples.clear()
        super().reject()

    def closeEvent(self, event):
        if self._busy:
            self.reject()
            event.ignore()
        else:
            self.samples.clear()
            super().closeEvent(event)
