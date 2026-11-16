# Handover (2026-11-16)

Branch: `ccr-b4eccc90-m97k4j`. No pull request has been opened.

## Last known-good builds
- **`7807bd0`**: Google Messages link complete. It passed 307 Kotlin tests, the Go tests, lint and the release build. The APK the user has is built from it.
- **`4dabec6`**: the sticker "Trace around it" cut-out tool, added on top of `7807bd0`. The released APK (308 tests passing) was built from 7807bd0 plus this commit, in a clean worktree.
- **Voice features** (dictation and read aloud, `ui/voice/`, `VoiceTest`): finished and tested (316 tests), committed in `4e0387d`.

## In progress at handover: WhatsApp as a second service

The user asked for:
- WhatsApp in the same app, with tabs at the top. They chose to leave Facebook Messenger out because of the ban risk.
- Typing and online status.
- Speech-to-text and read-aloud. These are done.

**Status:**
1. **Go bridge `gmbridge/wa`**: DONE.
   - It wraps whatsmeow and is bound into the same aar; 42 Go tests pass with `-race`.
   - Its exact API and events: `docs/handover/wa-go-report.md`.
   - The APK grows from about 39 MB to about 66 MB.
2. **Kotlin foundation**: DONE but unverified. It is the first step of workflow `wf_3f983f53-068` and includes:
   - `com.lightdecoder.chat` (ChatService, ChatBackend, ChatBackends);
   - an archive, media folder and notifications for each service;
   - stubs for WaManager, WaService and the `ui/wa` activities.
3. **Unfinished, stopped partway:**
   - the WhatsApp runtime (WaManager, GoWaBridge, WaService, outbox);
   - the chat UI service tabs;
   - the WhatsApp setup and status screens (pairing code and QR).

   Their partial edits are in the last commit. **The tree may not compile.**

The binding contracts are in:
- `docs/handover/multi-service-contract.md`, the Kotlin side, including "Additions";
- `docs/handover/wa-contract.md`, the Go side.

## How to continue
1. Run `ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileDebugKotlin --no-daemon` and see what is broken.
2. Finish, in order:
   1. the WaManager runtime, with FakeWaBridge tests;
   2. the WaSetupActivity and WaStatusActivity screens;
   3. the service tabs in ConversationsActivity, with ThreadActivity working through ChatBackend.
3. In the same Claude Code session, the workflow can be resumed:
   `Workflow({scriptPath: "/root/.claude/projects/-home-user/f6a1dca6-81a7-528c-a4e8-937b46189ca8/workflows/scripts/whatsapp-multi-service-wf_3f983f53-068.js", resumeFromRunId: "wf_3f983f53-068"})`.
   It reruns the build agents on the current tree, then integrates, reviews, fixes, runs a final check and writes the docs.
4. Verify:
   - `cd gmbridge && go test -race ./...`
   - `./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintDebug`
   - the skipped-symbol guard: `unzip -p app/libs/gmbridge-sources.jar '*.java' | grep '// skipped'` must print nothing.

   Never build with `-x :app:buildGoBridge`.
5. To ship a stable APK while WhatsApp is unfinished, build from a worktree at `4e0387d` (it includes voice) or at `4dabec6`.

## Hard product rules (both services)
1. Linking is never automated: the user confirms on their own phone.
2. A visible foreground notification with Disconnect is shown while connected, and Unpair is always available.
3. Everything stays local and is excluded from backup.
4. Logs never contain message text, names, numbers, JIDs, codes or keys.

## Known open points
- Nothing has been tested on a real phone, or against real Google or WhatsApp servers.
- Using WhatsApp and Google Messages through unofficial clients is against their terms. Accounts can be banned.
- Inline previews (Google's `mediaData`, WhatsApp's jpegThumbnail) are stand-ins only. The real media is downloaded; see `MediaLoader`.
