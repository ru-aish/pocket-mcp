# In-app agent: architecture and future requirements

Status: design for the next version. The published 0.1.0 APK remains the validated MCP tool server; these agent features are not implemented yet.

## Product requirements

- A chat message starts a task, executes available tools and reports the observed outcome.
- Support both API credentials and a supported Codex/ChatGPT account connection.
- Integrate an existing external memory system through a replaceable adapter or MCP, without embedding its database/server in the APK by default.
- Support background child agents, asynchronous results, cancellation, progress and recovery after process death.
- Connect to other MCP servers while continuing to expose the phone's own MCP server.
- Add Deepgram voice with streaming transcription, speech output, interruption and steering of ongoing work.
- Preserve direct Android APIs, app-provided actions, semantic accessibility and screen gestures in that preference order.
- Preserve standalone phone operation, small storage use, GitHub-only APK compilation and local Fedora emulator validation.

## Components

```text
Chat UI / Deepgram voice
            |
      Conversation controller
            |
      Agent runtime adapter -------- Model/account adapters
            |                        API credentials / supported ChatGPT plan flow
            |
     Durable task coordinator ------ Existing memory provider
            |                        Recall / write / correct / forget
            |
     Shared tool dispatcher -------- Remote MCP client connections
            |
     Current Android phone tools
            |
     Existing authenticated MCP server
```

The on-device agent should call the shared tool dispatcher directly. A local conversation should not need an ngrok connection to control its own phone. External clients retain the public MCP endpoint.

The MCP server and agent must share one tool registry, schema validation, operation ledger and serialized phone-action queue. Creating another independent PhoneTools instance and executor would let two callers race the same screen.

## Agent runtime decision

Treat the harness as replaceable. Define its inputs/outputs in terms of messages, tool calls/results, cancellation, streaming events and durable checkpoints. Keep Android UI, credentials, memory and phone access outside the harness.

Pi Agent Core is a relevant reusable loop: it provides tool execution, events, steering and lifecycle hooks. It is a TypeScript package, not an Android library. Before committing to embedding it, prove its runtime/provider dependencies, background execution and cancellation on Android. Browser proxy support alone does not establish reliable Android background operation. Do not package a Linux environment merely to run it.

A native Android loop remains the smaller integration if Pi's runtime bridge adds more work than it saves. Neither choice eliminates the need for a durable task coordinator, scoped memory and shared phone control.

Distinguish **ChatGPT-plan authentication** from **the Codex harness**. A native app using a supported ChatGPT OAuth token with Responses still owns its agent loop. Running the actual Codex app-server is a separate runtime adapter and requires a proven Android build/runtime or an explicitly selected remote host. Do not make an always-on PC a hidden dependency.

## Model and account connections

- API mode: user-selected provider/model and encrypted credentials; streaming, tool calls and image support are provider capabilities.
- ChatGPT mode: use supported registration/authorization, stable installation identity, token refresh and account/plan eligibility checks. Do not reuse another app's client identity or copy Codex's token files.
- Show the active account/provider and retain their separate usage/error states. Switching to a billable API connection must be an explicit user choice.
- Obtain model availability from the selected connection; do not assume a model catalog proves entitlement.
- Keep provider-specific serialization in adapters, including reasoning/tool-call items and tool-result image handling.

Current ChatGPT-plan preview rules require streaming Responses with store=false and client-supplied context. They do not support hosted MCP or native computer use on that route. Local phone functions and screenshot inputs can instead be bridged through supported function tools, subject to model/account support.

## Memory integration

Define a MemoryProvider boundary for recall, write, correction and deletion. Select the existing provider after evaluating the user's preferred system; do not declare a winner or build a competing memory engine now.

- Recall relevant information before planning; keep the retrieved context bounded.
- Separate working conversation state, task records and durable personal memory.
- Record source, time and scope; support corrections and forgetting.
- Persist outcomes supported by evidence. A requested SMS or uncertain UI action is not a completed task memory.
- Scope child-agent access and writes to their task. Return candidate memories with provenance rather than allowing every child to overwrite shared user facts.
- Leave large memory indexes and history outside the APK unless the user chooses a local engine.

## Background and asynchronous agents

Durable task records need parent/task IDs, state, checkpoints, deadlines/budgets, cancellation and completion events. Suggested states: queued, running, waiting_for_input, waiting_for_device, completed, failed, interrupted and cancelled. Recover records after process death before dispatching more work.

Independent retrieval/reasoning children may run concurrently. Phone mutations must share the device queue. A multi-step screen workflow also needs a task lease: per-call serialization alone permits another agent to navigate between an observation and its next action. Permit urgent user cancellation and release expired/crashed leases safely.

Unfinished mutations retain their existing operation ID and uncertain state. Cancellation stops future steps and speech; it cannot undo a message already submitted. Reconcile the ledger and owning app/provider before repeating an uncertain action.

Use Android foreground-service/background-work mechanisms according to the task lifecycle. Do not promise uninterrupted execution after force-stop, reboot or OEM restrictions. No additional APK, Linux process or model installation is required merely to represent a child agent.

## MCP client support

- Keep inbound phone MCP and outbound MCP client connections as separate modules.
- Namespace tool names by connection, preserve input schemas and error semantics, and resolve credentials per server.
- Support capability discovery, reconnects, bounded output and cancellation.
- Keep screenshots and other image results available to vision-capable models rather than flattening everything into text.
- Treat tool content as data; it must not change the user's authority or the dispatcher policy.

## Deepgram voice

Preferred first architecture:

```text
Android microphone -> Deepgram streaming STT -> same conversation/task controller
Android speaker    <- Deepgram streaming TTS <- speakable agent response events
```

The voice layer handles microphone permission/lifecycle, audio focus and playback, echo reduction, interim transcripts, turn boundaries, streaming synthesis and interruptions. Partial transcript revisions may prepare context, but must not trigger phone mutations before a committed user turn.

When the user interrupts, stop queued speech promptly, identify what work is already committed and steer/cancel future steps through the same task controller. Keep text chat and voice on the same task and memory state. Use actual action outcomes in spoken updates.

Deepgram's managed Voice Agent pipeline is another adapter option, but must delegate to the same task controller rather than create competing memory, subagent and tool state. Do not assume its LLM protocol supports every API or ChatGPT-plan connection.

Gemini-like live camera/screen conversation is an additional vision feature. Speech recognition and synthesis alone do not provide that capability. Add explicit, bounded screen/camera inputs through the model adapter when supported.

## Implementation order

1. Prove the harness/runtime boundary on Android; implement shared dispatch and chat using one complete model connection, then the second account mode.
2. Add durable task state, stop/steer, result verification and bounded conversation context.
3. Add remote MCP client connections and the memory-provider adapter.
4. Add child-agent orchestration, asynchronous notifications and device-task leases.
5. Add Deepgram streaming voice on that same controller; then optional live vision.

For each release, compile APKs only in Actions. Extend the local Fedora fixture for deterministic agent scenarios; distinguish simulated provider tests from live model/account/voice validation.

## Primary references

- [Pi Agent Core](https://github.com/earendil-works/pi/tree/main/packages/agent)
- [OpenAI agent runtime choices](https://developers.openai.com/api/docs/guides/agents)
- [ChatGPT-plan usage](https://developers.openai.com/siwc/token-sharing-open-source)
- [ChatGPT-plan preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Codex app-server connection](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server)
- [Deepgram streaming transcription](https://developers.deepgram.com/reference/speech-to-text/listen-streaming)
- [Deepgram streaming speech synthesis](https://developers.deepgram.com/reference/text-to-speech/speak-streaming)
