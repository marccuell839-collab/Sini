# SINI JARVIS (Android)
Arabic-friendly voice assistant Android proof-of-concept: tap-to-listen SpeechRecognizer → OpenRouter chat completions → Android TextToSpeech with adaptive rate/pitch.

## Setup
Build via **Actions → Android APK** or Android Studio. Install generated debug APK.
In the app enter your OpenRouter API key and model identifier (e.g. a currently available `*:free` model); the key stays in private application preferences and is not committed to GitHub. Choose a suitable Android-installed TTS voice/language in system settings.

## Notes
This first version uses Android TTS, **not** unofficial Edge TTS. Edge-like neural voice requires an approved speech service or independently operated backend; pitch/rate changes alone do not produce real laughing/whispering. The microphone starts only when tapped. No background listening or remote PC control. Avoid sharing API keys or publishing them in screenshots.
