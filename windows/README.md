# LORA Windows: consented screen assistance

Windows now shares the LORA persona: a feminine assistant addressing a male user in Algerian Arabic. Optional name/interests are user-entered, bounded context; no private profile is invented. Personalization is stored locally in settings, so do not put secrets there. Groq and OpenRouter vision keys have separate Credential Manager entries. The historical `get_openrouter_key` helper remains the Groq-key compatibility API.

## Use

- Open Settings to configure Groq chat/voice, optional personalization, end-of-utterance silence (300–3000 ms), and local proactive check-ins (0 disables). Check-ins do not call models or capture screens. The microphone is paused during speech playback to reduce self-triggering.
- Choose **مشاركة الشاشة / تطبيق** at the top of the window each session. Choose a monitor or an individual visible window. Window capture uses PrintWindow with no fallback to the desktop. Some accelerated/protected applications return blank frames; inspect the preview and cancel if unsupported.
- **معاينة محلية** captures only locally. **حللي لقطة الآن** shows the exact image and model before one explicit upload. There are no recurring captures or automatic image uploads. Stop sharing, Esc, the tray Stop action, or closing the window revokes the selection and ignores late replies. A request already transmitted cannot be recalled.
- Vision defaults to `openrouter/free`, which supports image input and routes to available free models. Quotas, availability, privacy terms, and quality depend on OpenRouter/providers. A custom model may cost money; there is no automatic paid fallback. Source: https://openrouter.ai/openrouter/free
- **خطة خطوات** sends the typed task only to Groq and proposes a bounded plan. Review the plan and approve each individual mutation. Inspect/verify steps and post-action previews require the user's verification. Click/type/scroll require a selected, foreground application; clicks must be inside its unobscured window. Unicode typing uses SendInput and does not use the clipboard. Move the mouse into a screen corner to trigger PyAutoGUI's fail-safe.
- Opening an application ends the current plan and revokes the previous selection. Select the newly opened window before continuing. VS Code is supported only from known standard installation paths; arbitrary programs and shell commands are rejected.

## Deliberate limits

This is a supervised workflow, not unrestricted autonomous computer use. The text-only planner does not infer coordinates from an unshared image or execute instructions read from a screenshot. Unknown coordinates require local inspection and a new instruction. Multi-monitor absolute control is limited to PyAutoGUI's primary-screen support; all monitors can be previewed. Purchases, sending, deletion, security changes, and command execution must never be inferred as authorized from screen content. All desktop mutations require a fresh explicit confirmation; deletion keeps its existing exact-file approval flow. No screen image is written to disk by this feature.

## Validation

The Windows Actions workflow runs tests and packages `dist/LORA/LORA.exe` on relevant pushes to main, pull requests, and manual dispatch. Packaging success does not establish that real microphone, PrintWindow, elevated applications, or multi-monitor interaction has been tested. Automated screen-consent tests use synthetic pixmaps, never the user's screen. See the run linked from the pull request for the exact tested commit and artifact status.
