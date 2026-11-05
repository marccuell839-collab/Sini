"""Groq chat completions for LORA desktop (separate from speech synthesis)."""
from __future__ import annotations

import httpx
from .persona import system_prompt


def answer(api_key: str, message: str, model: str = "openai/gpt-oss-20b",
           history: list[dict] | None = None, timeout: float = 45.0, name: str = "", context: str = "") -> str:
    """Get a short Arabic answer. No tool execution or arbitrary remote commands."""
    if not api_key.strip():
        raise ValueError("أدخل مفتاح Groq في إعدادات لورا")
    messages = [{
        "role": "system",
        "content": system_prompt(name, context)
    }]
    messages.extend((history or [])[-8:])
    messages.append({"role": "user", "content": message})
    try:
        response = httpx.post(
            "https://api.groq.com/openai/v1/chat/completions",
            headers={"Authorization": f"Bearer {api_key}"},
            json={
                "model": model,
                "messages": messages,
                "temperature": 0.6,
                "max_completion_tokens": 350,
            },
            timeout=timeout,
        )
        if response.status_code == 429:
            raise RuntimeError("بلغت حصة Groq مؤقتاً؛ انتظر إعادة التعيين")
        if response.status_code in (401, 403):
            raise RuntimeError("Groq رفض المفتاح؛ راجعه في الإعدادات")
        response.raise_for_status()
        text = response.json()["choices"][0]["message"]["content"]
        if not isinstance(text, str) or not text.strip():
            raise RuntimeError("النموذج أرجع جواباً فارغاً")
        return text.strip()
    except httpx.HTTPError as exc:
        raise RuntimeError(f"تعذّر الاتصال بـGroq ({type(exc).__name__})") from exc
