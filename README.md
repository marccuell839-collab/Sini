# DEXTER LAB — Appnova Research Agent (Android)
Native Android research assistant based on Sini, rebuilt as an independent experimental laboratory. Arabic RTL interface.

## Features
- Four-stage evidence-aware research loop: analysis, hypotheses, experiment plan, critical review
- OpenRouter model identifier configurable (default `openrouter/free`; availability and usage limits vary)
- Optional Deepgram Aura TTS via your own API key; Android TTS fallback
- Android speech recognition for microphone input (not Deepgram STT)
- Report sharing and local last-report persistence
- Secrets entered on the device, never included in the GitHub repository
- Build APK using GitHub Actions > Build Android APK

## Limitations
Not an autonomous code execution or science validation system. No shell execution, external device control, physical experimentation, or verification of novelty. Model may make mistakes. Deepgram pricing, credits and voice/language support depend on the user's account and chosen model. Voice is opt-in and Deepgram English models may not read Arabic correctly.
