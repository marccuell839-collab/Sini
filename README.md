# DEXTER • Appnova — Android Agent

A real Android Kotlin agent prototype with a code-rendered animated orb, OpenRouter free-model discovery, Deepgram optional TTS, native fallback TTS, speech input, action planning and confirmation-gated native tools.

## Agent behavior
The LLM produces structured JSON with an answer and suggested tools. A native validator enforces an allowlist, then the owner individually confirms tool execution. Supported tools: Android settings, browser URL, web search via browser, launch a visible installed app, share text, and clipboard. No unrestricted shell, accessibility abuse, hidden commands or arbitrary app input.

## Setup
Actions → Build Dexter Lab APK → Artifacts. Enter your own OpenRouter API key in Settings; select a currently-free model. Free models vary in availability, limits and reliability. Optionally enter Deepgram TTS key; Deepgram usage may cost money and chosen voices may not support Arabic. Keys are stored in Android app-private preferences (not encrypted at rest) and are never committed to source.

## Honest limitations
This is a functional *agent foundation*, not an all-powerful system assistant. AI may output invalid JSON; the UI will show the textual response without proposed actions. No PC integration, wake-word listening, background service, device-pairing, autonomous code execution, on-device scientific experiments or screen-reading yet. Actions need approval. Only the Android SpeechRecognizer handles STT; Deepgram is for TTS here. Device testing and performance profiling remain necessary.
