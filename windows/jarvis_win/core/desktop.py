"""Bounded desktop plans: model output is a proposal, never authorization."""
from __future__ import annotations
import json
import math
from dataclasses import dataclass


@dataclass(frozen=True)
class Step:
    action: str
    args: dict


def parse_plan(raw: str, limit: int = 8) -> tuple[Step, ...]:
    data = json.loads(raw)
    if not isinstance(data, list) or not 1 <= len(data) <= min(20, max(1, limit)):
        raise ValueError("الخطة لازم تحتوي خطوات محدودة")
    shapes = {"inspect": set(), "verify": set(), "click": {"x", "y"},
              "type": {"text"}, "scroll": {"amount"}, "open": {"app"}}
    steps = []
    for item in data:
        if not isinstance(item, dict) or set(item) != {"action", "args"}:
            raise ValueError("خطوة غير صالحة")
        action, args = item["action"], item["args"]
        if not isinstance(action, str) or action not in shapes or not isinstance(args, dict) or set(args) != shapes[action]:
            raise ValueError("أداة أو وسائط غير مسموحة")
        for key in ("x", "y", "amount"):
            if key in args and (type(args[key]) is not int or not math.isfinite(args[key])):
                raise ValueError("الإحداثيات لازم تكون أعداد صحيحة")
        if action == "click" and not (0 <= args["x"] <= 16384 and 0 <= args["y"] <= 16384):
            raise ValueError("إحداثيات خارج الحدود")
        if action == "scroll" and not -10 <= args["amount"] <= 10:
            raise ValueError("تمرير خارج الحدود")
        if action == "type" and (not isinstance(args["text"], str) or len(args["text"]) > 2000 or any(ord(c) < 32 for c in args["text"])):
            raise ValueError("النص طويل أو يحتوي أزرار تحكم")
        if action == "open" and args["app"] not in {"notepad", "calculator", "explorer", "settings", "browser", "paint", "vscode"}:
            raise ValueError("تطبيق غير مسموح في الخطة")
        steps.append(Step(action, dict(args)))
    return tuple(steps)


def perform(step: Step) -> None:
    """Caller must obtain a fresh confirmation and restore the selected target."""
    if step.action in {"inspect", "verify"}:
        return
    if step.action == "open":
        from .tools import _open_application
        result = _open_application(step.args)
        if not result.ok:
            raise RuntimeError(result.summary)
        return
    import pyautogui
    pyautogui.FAILSAFE = True
    pyautogui.PAUSE = 0.15
    if step.action == "click":
        x, y = step.args["x"], step.args["y"]
        if not pyautogui.onScreen(x, y):
            raise ValueError("النقطة خارج الشاشة الرئيسية")
        pyautogui.click(x, y)
    elif step.action == "scroll":
        pyautogui.scroll(step.args["amount"])
    elif step.action == "type":
        # Unicode typing via Windows SendInput, never clipboard or shell.
        from .screen import type_unicode
        type_unicode(step.args["text"])
