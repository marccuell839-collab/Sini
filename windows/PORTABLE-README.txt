LORA Windows x64 - portable build
Extract the COMPLETE ZIP, then run LORA/LORA.exe. Keep _internal next to the EXE.
Click the orb or press Space to start one voice turn. Esc cancels. F11 toggles
fullscreen. Settings is the gear in the corner; Ctrl+Q exits.
Add your Groq key in Settings. It is stored in Windows Credential Manager.
Groq AI and Groq Orpheus Noura require internet and your own service quota.
Deepgram transcribes each activated voice question over HTTPS using Arabic Nova-3.
Add a separate Deepgram key in Settings; it is stored in Windows Credential Manager.
No Whisper model download is required. Both Deepgram and Groq need internet
and available account quota. Owner enrollment audio remains local.
Enroll the owner from Settings: three six-second samples. Only a neural
embedding is stored, encrypted using current-user Windows DPAPI. Raw enrollment
samples are discarded. Delete/re-enroll from Settings. Existing enrollment
is kept if replacement fails. With enrollment enabled, unknown/short/invalid
speech is rejected BEFORE transcription. Without enrollment there is no gate.
Speaker matching is EXPERIMENTAL, not a calibrated identity guarantee. Replay,
cloning, noise, and microphone changes may fool or fail it. Voice alone is NOT
sufficient authentication for dangerous commands. File deletion ALWAYS requires
manual approval of the exact plan. Someone controlling your Windows account
can reset enrollment or decrypt its stored representation.
Microphone audio is captured only after activation or during explicit enrollment.
The microphone stops after a completed utterance so TTS cannot trigger commands.
The ZIP is unsigned; Windows may show a publisher warning. No API key is bundled.

End-of-speech silence defaults to about 350 ms (formerly 700). Change it in
Settings; shorter delays may split pauses. This does not change AI/network latency.
