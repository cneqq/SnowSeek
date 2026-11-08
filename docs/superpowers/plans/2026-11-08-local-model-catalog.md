# Local Model Catalogue and Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a curated and importable local model library in SnowSeek for `.litertlm` and `.gguf` models on arm64 Android.

**Architecture:** Keep LiteRT-LM as the `.litertlm` engine and add an arm64 llama.cpp JNI engine for GGUF. A shared model registry/store and inference interface connect downloads, system-picker imports, device-fit descriptions, model selection, and the existing local chat.

**Tech Stack:** Kotlin, Jetpack Compose, Android Storage Access Framework, LiteRT-LM 0.17.1-compatible artifacts, llama.cpp C API, Android NDK, CMake, JNI, coroutines/Flow.

**Spec:** `docs/superpowers/specs/2026-11-08-local-model-catalog-design.md`

## Global Constraints

- App target is arm64-only; do not add other ABIs.
- Preserve minSdk 26 unless a concrete native compatibility failure is reported and separately approved.
- Keep local inference on device; do not send prompts or imported model contents to a server.
- Only curate pinned artifacts whose exact file, revision, license, template, size, and checksum status are visible.
- Store installed models in app-private storage and verify complete downloads before installation.
- Load one model engine at a time and start a fresh in-memory conversation when switching models.
- Catalogue device-fit is labeled measured, heuristic, or unknown; never infer performance from file size alone.
- Keep the interface compact and consistent with SnowSeek's existing black theme and typography.
- Bump app version from 0.4.0 / versionCode 22 to 0.5.0 / versionCode 23 when the feature is integrated.
- Do not add or run automated tests unless the user explicitly requests tests or verification.

## Review Focus

- Interrupted downloads/imports or insufficient storage must not mark a partial file as installed. (Task 2: manual interruption/storage acceptance scenarios.)
- A model with a malformed container, missing chat template, or unsupported architecture must fail with an actionable message, not crash the app. (Tasks 1 and 2: format/template rejection checks.)
- Switching/closing a model while it is generating must cancel native work and release the engine. (Tasks 1 and 4: cancellation/unload checks.)
- Android API 26 must not fail due to newer native symbols. (Task 1: compile and inspect minimum API linkage; physical/runtime check where available.)
- Device-fit copy must distinguish measured profiles from memory heuristics and must not describe file size as required RAM. (Task 3: inspect curated and imported states.)

---

### Task 1: Add a slim llama.cpp JNI runtime for GGUF

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/llama_jni.cpp`
- Create: `app/src/main/java/dev/snowseek/app/local/LlamaCppEngine.kt`
- Create: `app/src/main/java/dev/snowseek/app/local/LiteRtInferenceEngine.kt`
- Create: `app/src/main/java/dev/snowseek/app/local/LocalInferenceEngine.kt`
- Create: `app/src/main/java/dev/snowseek/app/local/LocalModelFormat.kt`
- Modify: `app/build.gradle.kts`
- Modify: `licenses/` or `THIRD_PARTY_NOTICES.md` for the pinned llama.cpp license and attribution

**Interfaces:**
- Produces: `LocalInferenceEngine.load(modelFile: File, format: LocalModelFormat, contextTokens: Int)`, `stream(messages: List<LocalChatMessage>): Flow<String>`, `cancel()`, and `close()`; `LlamaCppEngine` implements it for GGUF and the LiteRT adapter implements it for LiteRT-LM. Each `LocalChatMessage` contains `role: String` and `content: String`; supported roles are `system`, `user`, and `assistant`.
- JNI surface: `nativeLoad(path: String, contextTokens: Int): Long`, `nativeGenerate(handle: Long, roles: Array<String>, contents: Array<String>, tokenSink: TokenSink)`, `nativeCancel(handle: Long)`, and `nativeClose(handle: Long)`.
- Keep the JNI implementation on an immutable llama.cpp revision; compile CPU-only arm64 and disable unsupported backends and tools not used by this app.

- [ ] **Step 1: Pin source and build configuration** — Record the exact upstream llama.cpp revision, license notice, arm64 CMake switches, and Android API target. Use `ANDROID_PLATFORM=android-26` initially and fail this task if compilation requires raising SnowSeek's minSdk; report evidence instead of silently changing it.
- [ ] **Step 2: Add the native bridge** — Implement model open/metadata validation, bounded context allocation, synchronous token callbacks, cancellation, and idempotent close. Return native errors as Kotlin exceptions with readable messages.
- [ ] **Step 3: Add `LlamaCppEngine` and `LocalInferenceEngine`** — Load the JNI library lazily, serialize native calls on an IO dispatcher, format the complete multi-turn message list through the GGUF model's chat template on each turn, bridge token callbacks into `Flow<String>`, and ensure coroutine cancellation invokes `nativeCancel` before teardown.
- [ ] **Step 4: Keep the existing LiteRT-LM adapter** — Add `LiteRtInferenceEngine` around the current Engine/Conversation lifecycle; preserve the existing system instruction and streaming behavior for `.litertlm`. Integrate it into the view model when the model repository replaces the single-model state in Task 4.
- [ ] **Step 5: Compile the arm64 app** — Run `gradlew.bat :app:compileDebugKotlin :app:externalNativeBuildDebug` and inspect the linked native symbols/API requirements. Do not run test tasks. Expected: Kotlin and CMake compile for arm64 with minSdk 26 unchanged.
- [ ] **Step 6: Exercise native lifecycle on an available API 26+ arm64 device** — Load and unload a small compatible GGUF, stream one response, cancel generation, and confirm the app remains responsive. If no device is connected, record this as not run rather than claiming runtime verification.

### Task 2: Generalize model catalogue, downloader, import, and storage

**Files:**
- Create: `app/src/main/java/dev/snowseek/app/local/ModelCatalog.kt`
- Create: `app/src/main/java/dev/snowseek/app/local/InstalledModel.kt`
- Create: `app/src/main/java/dev/snowseek/app/local/LocalModelRepository.kt`
- Create: `app/src/main/java/dev/snowseek/app/local/ModelFileInspector.kt`
- Modify: `app/src/main/java/dev/snowseek/app/ui/LocalModelViewModel.kt`

**Interfaces:**
- `ModelCatalogEntry(id, name, format, sizeBytes, source, revision, sha256?, publisher, licenseUrl, tasks, languages, templateId, runtimeMinVersion, memoryProfile)`.
- `InstalledModel(id, displayName, format, file, fileSizeBytes, sourceKind, validationState, memoryProfile)`.
- `LocalModelRepository.catalog(): List<ModelCatalogEntry>`, `installed(): Flow<List<InstalledModel>>`, `download(id, onProgress)`, `import(uri)`, `select(id)`, and `delete(id)`; selected entries are converted to the Task 1 `modelFile`, `format`, and `contextTokens` adapter arguments.
- `ModelFileInspector.inspect(file): ModelInspection` validates extension/container, detects GGUF metadata/template and supported architecture, and exposes unknown profile rather than fabricating capability data.

- [ ] **Step 1: Define catalogue entries** — Carry forward the exact existing Qwen3 0.6B LiteRT-LM artifact. Add a pinned `ggml-org/Qwen3-0.6B-GGUF` Q4_0 candidate only after verifying immutable revision, exact bytes/hash, Apache-2.0 attribution, chat template, and successful Task 1 inference. Do not add newer LiteRT-LM files that need a runtime upgrade unless that upgrade is part of the same verified integration.
- [ ] **Step 2: Generalize download/install** — Replace `FirstLocalModel` constants and single-file logic with repository metadata. Preserve retry, resume, cancellation, free-space checks, exact-size checks, SHA-256 where known, and atomic `.part` install.
- [ ] **Step 3: Implement SAF import** — Accept `.litertlm` and `.gguf` through a system document picker. Copy to an app-private temporary file with free-space checks; inspect and validate before atomic install; reject wrong/truncated/malformed files with a clear error.
- [ ] **Step 4: Persist installed model metadata** — Store model IDs, managed file names, format, sizes, source/license information, validation status, and selected model. Keep prompts and local chat transcript out of this persistent repository.
- [ ] **Step 5: Implement selection/deletion lifecycle** — Before switching or deleting, cancel generation and close the active engine. A model is listed as installed only after successful validation and atomic move.
- [ ] **Step 6: Inspect failure behavior manually** — Use one interrupted transfer/import, one insufficient-storage condition where safely reproducible, one invalid extension/container, one hash mismatch, and one unsupported GGUF template. Confirm no partial artifact is marked usable and each error gives a recovery action. Do not create or run automated tests.

### Task 3: Add phone-specific fit estimates and model-management UI

**Files:**
- Create: `app/src/main/java/dev/snowseek/app/local/DeviceModelFit.kt`
- Modify: `app/src/main/java/dev/snowseek/app/ui/LocalModelScreen.kt`
- Modify: `app/src/main/java/dev/snowseek/app/ui/SnowSeekApp.kt`

**Interfaces:**
- `DeviceModelFit.evaluate(entry: ModelCatalogEntry, device: DeviceProfile): FitResult` returns `Measured`, `Heuristic`, or `Unknown`, a workload label, and explanatory text.
- `DeviceProfile` includes memory class/total memory, ABI, and only those device benchmark keys actually available; `FitResult` does not equate download size to RAM need.
- `LocalModelScreen` displays catalogue and installed entries; callbacks connect import, download/resume/cancel, select/chat, and delete.

- [ ] **Step 1: Build `DeviceProfile` and fit evaluation** — Prefer curated device measurements; otherwise compare a conservative model-runtime memory estimate to Android memory class and label the result as heuristic. Show “нет данных” for imported models without a defensible profile.
- [ ] **Step 2: Replace single-model landing UI** — Show compact catalogue/installed sections and a visible import action. Each card exposes name, format, file size, workload/fit and its evidence type, task/language, source/license, and current action.
- [ ] **Step 3: Add transfer and import states** — Show determinate download progress, resume/cancel state, verification, low-storage, unsupported-model, and actionable error content. Keep keyboard/window behavior unaffected.
- [ ] **Step 4: Connect local model navigation** — Selecting an installed model opens a fresh local chat for that model; retain the existing local model settings link and correct its summary to the selected/default model and count of installed items.
- [ ] **Step 5: Review UI states on phone and emulator layouts** — Inspect compact and large font-scale layouts, empty catalogue/install states, long model names/licenses, download progress/errors, import rejection, and selected-model state. Do not run automated tests.

### Task 4: Integrate model-aware local chat, version, and release notes

**Files:**
- Modify: `app/src/main/java/dev/snowseek/app/ui/LocalModelViewModel.kt`
- Modify: `app/src/main/java/dev/snowseek/app/ui/LocalModelScreen.kt`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`
- Modify: `THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Local chat receives the selected `InstalledModel`, asks the repository for the matching adapter, streams response chunks, and owns one engine/conversation at a time.
- Switching models starts a fresh in-memory transcript, cancels active generation, closes the prior adapter, and initializes the new selection only when the chat is entered.

- [ ] **Step 1: Wire selected models to chat** — Use the model runtime selected by format. Preserve multi-turn context, the current concise-assistant system instruction for formats that accept it, streaming output, send cancellation, and model-specific template handling.
- [ ] **Step 2: Verify switching and lifecycle manually** — Switch between `.litertlm` and `.gguf`, generate a response in each, cancel a response, leave the chat, and remove the active model. Confirm the previous runtime is closed and the next chat starts empty.
- [ ] **Step 3: Update version and documentation** — Bump app to versionName `0.5.0` and versionCode `23`; update the README local-model description and include download/import/model provenance behavior; retain third-party licensing information.
- [ ] **Step 4: Build arm64 release artifact** — Run `gradlew.bat :app:assembleRelease`; record output APK path and measured APK size compared with current 0.4.0 arm64 artifact if available. Do not run test tasks.
- [ ] **Step 5: Final device acceptance** — On arm64 Android, verify existing Qwen `.litertlm`, one catalog GGUF, one imported file per format, stream/cancel/unload, and model fit copy. Record minimum tested Android API and any unavailable device scenarios.

## Plan self-review

Device-only acceptance steps remain unchecked: no arm64 phone or emulator is attached in this session. Automated tests were not run, as specified.

- **Coverage:** catalog/download/import/integrity/storage are Task 2; dual engines and JNI lifecycle Task 1; device-specific estimates and UI Task 3; selected chat, version, documentation, and arm64 release build Task 4.
- **Interfaces:** `LocalModelFormat` and the runtime adapter signature are defined in Task 1; Task 2 repository metadata is mapped to those adapter arguments; UI fit result provenance is explicit.
- **Risk cases:** interruptions, insufficient storage, malformed/imported files, templates, cancellation, low API compatibility, and estimate uncertainty are assigned above.
- **Scope:** no persistent local transcript, arbitrary runtime plugins, cloud inference, model conversion, or automated tests are included.
