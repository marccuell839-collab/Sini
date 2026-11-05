"""Windows window enumeration and isolated PrintWindow capture. No uploads."""
import ctypes
from ctypes import wintypes


def api():
    if not hasattr(ctypes, "windll"):
        raise RuntimeError("Windows فقط")
    user, gdi = ctypes.windll.user32, ctypes.windll.gdi32
    user.GetDC.restype = wintypes.HDC
    gdi.CreateCompatibleDC.restype = wintypes.HDC
    gdi.CreateCompatibleBitmap.restype = wintypes.HBITMAP
    gdi.SelectObject.restype = wintypes.HANDLE
    # Explicit handle widths on 64-bit Windows.
    user.IsWindow.argtypes = [wintypes.HWND]
    user.IsIconic.argtypes = [wintypes.HWND]
    user.IsWindowVisible.argtypes = [wintypes.HWND]
    user.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    user.GetDC.argtypes = [wintypes.HWND]
    user.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
    user.PrintWindow.argtypes = [wintypes.HWND, wintypes.HDC, wintypes.UINT]
    gdi.CreateCompatibleDC.argtypes = [wintypes.HDC]
    gdi.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
    gdi.SelectObject.argtypes = [wintypes.HDC, wintypes.HANDLE]
    gdi.DeleteObject.argtypes = [wintypes.HANDLE]
    gdi.DeleteDC.argtypes = [wintypes.HDC]
    return user, gdi


def windows():
    user, _ = api()
    result = []
    callback = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(hwnd, _):
        if user.IsWindowVisible(hwnd):
            title = ctypes.create_unicode_buffer(512)
            user.GetWindowTextW(hwnd, title, 512)
            if title.value:
                result.append((int(hwnd), title.value))
        return True
    user.EnumWindows(callback(visit), 0)
    return result


def capture_window(hwnd):
    from PySide6.QtGui import QImage, QPixmap
    user, gdi = api()
    if not user.IsWindow(hwnd) or user.IsIconic(hwnd):
        raise RuntimeError("النافذة مغلقة أو مصغرة؛ أعد اختيارها")
    rect = wintypes.RECT()
    user.GetWindowRect(hwnd, ctypes.byref(rect))
    w, h = rect.right - rect.left, rect.bottom - rect.top
    if not (0 < w <= 16384 and 0 < h <= 16384) or w*h > 40000000:
        raise RuntimeError("حجم النافذة غير صالح")
    dc = user.GetDC(hwnd)
    mem = gdi.CreateCompatibleDC(dc)
    bitmap = gdi.CreateCompatibleBitmap(dc, w, h)
    old = gdi.SelectObject(mem, bitmap)
    class Header(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("width", wintypes.LONG),
                    ("height", wintypes.LONG), ("planes", wintypes.WORD),
                    ("bits", wintypes.WORD), ("compression", wintypes.DWORD),
                    ("image_size", wintypes.DWORD), ("x", wintypes.LONG),
                    ("y", wintypes.LONG), ("used", wintypes.DWORD), ("important", wintypes.DWORD)]
    try:
        if not user.PrintWindow(hwnd, mem, 2):
            raise RuntimeError("هذا التطبيق لا يسمح بالالتقاط؛ لا يوجد رجوع لالتقاط سطح المكتب")
        gdi.SelectObject(mem, old)
        header = Header(ctypes.sizeof(Header), w, -h, 1, 32, 0, 0, 0, 0, 0, 0)
        buf = ctypes.create_string_buffer(w*h*4)
        gdi.GetDIBits.argtypes = [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT, ctypes.c_void_p, ctypes.c_void_p, wintypes.UINT]
        if gdi.GetDIBits(dc, bitmap, 0, h, buf, ctypes.byref(header), 0) != h:
            raise RuntimeError("تعذر قراءة اللقطة")
        return QPixmap.fromImage(QImage(buf.raw, w, h, w*4, QImage.Format.Format_RGB32).copy())
    finally:
        gdi.SelectObject(mem, old)
        gdi.DeleteObject(bitmap)
        gdi.DeleteDC(mem)
        user.ReleaseDC(hwnd, dc)


def focus(hwnd):
    user, _ = api()
    user.GetForegroundWindow.restype = wintypes.HWND
    user.SetForegroundWindow.argtypes = [wintypes.HWND]
    if not user.IsWindow(hwnd) or user.IsIconic(hwnd):
        raise RuntimeError("النافذة غير متاحة")
    user.SetForegroundWindow(hwnd)
    if user.GetForegroundWindow() != hwnd:
        raise RuntimeError("تعذر تفعيل النافذة المحددة")


def type_unicode(text):
    # INPUT includes a union as large as MOUSEINPUT, including 64-bit padding.
    class Mouse(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("data", wintypes.DWORD), ("flags", wintypes.DWORD), ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]
    class Key(ctypes.Structure):
        _fields_ = [("vk", wintypes.WORD), ("scan", wintypes.WORD), ("flags", wintypes.DWORD), ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]
    class Union(ctypes.Union):
        _fields_ = [("mouse", Mouse), ("key", Key)]
    class Input(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("value", Union)]
    user, _ = api()
    for unit in memoryview(text.encode("utf-16-le")).cast("H"):
        for flags in (4, 6):
            entry = Input(1, Union(key=Key(0, unit, flags, 0, 0)))
            if user.SendInput(1, ctypes.byref(entry), ctypes.sizeof(Input)) != 1:
                raise RuntimeError("Windows رفض الكتابة")


def prepare_action(hwnd, step):
    """Restore and recheck target after the confirmation dialog lost focus."""
    focus(hwnd)
    user, _ = api()
    rect = wintypes.RECT()
    if not user.GetWindowRect(hwnd, ctypes.byref(rect)):
        raise RuntimeError("تعذر تحديد حدود التطبيق")
    if step.action == 'click':
        x, y = step.args['x'], step.args['y']
        if not (rect.left <= x < rect.right and rect.top <= y < rect.bottom):
            raise RuntimeError("النقطة خارج التطبيق المحدد")
        user.WindowFromPoint.argtypes = [wintypes.POINT]
        user.WindowFromPoint.restype = wintypes.HWND
        user.GetAncestor.argtypes = [wintypes.HWND, wintypes.UINT]
        user.GetAncestor.restype = wintypes.HWND
        hit = user.WindowFromPoint(wintypes.POINT(x, y))
        if user.GetAncestor(hit, 2) != hwnd:
            raise RuntimeError("النقطة محجوبة بنافذة أخرى")
    elif step.action == 'scroll':
        import pyautogui
        pyautogui.moveTo((rect.left + rect.right)//2, (rect.top + rect.bottom)//2)
