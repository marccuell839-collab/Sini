# JARVIS Windows — identity, theme and task queue

## Implemented in the Windows source
- Default assistant label **JARVIS**; editable in Settings. The visible window title, top identity, live dashboard and Groq system prompt use the chosen label.
- Blue/electric theme or classic rose theme, persisted in the user's per-profile settings JSON.
- Live dashboard shows current state, selected Groq model and number of queued steps.
- List of up to 20 tasks. Enter an instruction, click **＋ إضافة مهمة**, then **▶ تنفيذ المهام**. Each step starts after the prior command returns completion; the queue stops on an error instead of proceeding blind.
- Expanded local app opener: VS Code, PowerShell, Word, Excel, plus previous apps. Opening applications does not execute shell commands from generated model text.
- Assistant speaks about itself in feminine form and addresses the male owner in Algerian Arabic.

## Limitations and safety
- Multi-task currently means **sequential known actions or chat turns**, not independently planning and validating an arbitrary GUI workflow.
- Autonomous mouse/keyboard interaction, screen vision, arbitrary terminal actions and answering messages are **not implemented** in this Windows upgrade. They need a separate explicit-consent computer-use subsystem and verification after each action.
- Do not claim success of packaging or Windows runtime until CI builds and an actual Windows session are tested.
- User credentials stay in Windows Credential Manager; no API key is embedded.
- Sensitive deletion remains subject to the existing confirmation workflow.

## Usage
Open Settings to choose your name and theme. Add each task individually using the text field and **＋ إضافة مهمة**. Review tasks before pressing Execute. Use Stop/Escape if you need to interrupt.
