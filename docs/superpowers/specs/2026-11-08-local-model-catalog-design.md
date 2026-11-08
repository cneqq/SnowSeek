# SnowSeek local model catalogue and import

## Intent

Let a user choose a built-in local language model or import a compatible model file, download it from a curated source, and use it in the existing on-device chat. The first version supports `.litertlm` and `.gguf` on the app's current arm64 Android target.

## Goals

- Keep the existing Qwen3 0.6B LiteRT-LM chat working while moving from one hard-coded model to a model manager.
- Offer a small, curated catalogue with model name, file size, supported tasks/languages, source and license, and a phone-specific workload/compatibility estimate.
- Download listed files in-app with progress, cancellation, resume, free-space checks, pinned revisions, and SHA-256 verification whenever the source provides a known digest.
- Import `.litertlm` and `.gguf` from Android's system file picker; copy each file to SnowSeek-managed private storage and validate its container before offering it to the chat.
- Let the user select an installed model, load its engine only when needed, switch models, and remove models with their storage usage shown.
- Keep local prompts and generated replies on the device.

## Non-goals

- Arbitrary model conversion, quantization, fine-tuning, or compatibility with every GGUF/LiteRT-LM artifact.
- Web search, cloud inference, or sending prompts to catalogue providers.
- NPU/GPU acceleration for every model/device in the first implementation; CPU inference is the common baseline.
- Claiming that all imported models are safe, suitable, or performant based only on their filename or size.

## Approaches

1. **LiteRT-LM only.** Reuse the current runtime and its `.litertlm` models. This is the smallest change but does not meet the agreed GGUF requirement.
2. **LiteRT-LM plus llama.cpp via its upstream Android binding.** This follows the official Android sample's metadata reader and inference facade. The current upstream Android sample declares minSdk 33, while SnowSeek currently declares minSdk 26, so adopting the sample directly risks dropping Android 8–12 support.
3. **LiteRT-LM plus a lean llama.cpp JNI build.** Keep the existing Kotlin LiteRT-LM adapter and build only the arm64 llama.cpp runtime for this app. This requires maintaining a narrow JNI bridge but gives SnowSeek control over packaged components and allows a direct compatibility check against its minSdk 26.

**Chosen:** approach 3. The user approved an in-app arm64 NDK/JNI engine for GGUF. The APK will grow because the second native runtime is bundled. GGUF models themselves remain optional downloads/imports. Build and runtime compatibility at API 26 must be verified; if the native dependency cannot support API 26, do not silently raise minSdk.

## Product shape

The existing “Локальная модель” destination becomes the local model manager and chat entry point. It shows a curated catalogue and installed models, with a clear separate action to import a file. A catalogue card includes:

- model name and format;
- download size and approximate runtime memory need;
- task and language labels from the model publisher/model card;
- workload/fit for this device, with a visible distinction between a measured profile and a RAM-based estimate;
- source/publisher and license;
- a download, continue, select, or delete action appropriate to the state.

After choosing an installed model, the user enters the local chat for that model. The current Qwen3 0.6B chat remains available as an existing installed-model experience. Each chat supports multi-turn messages, the current concise-assistant system instruction, and streamed output. Local transcripts remain in memory only, consistent with the current local-model screen; they are not synced or persisted to disk. Choosing a different model starts a fresh conversation and unloads the previous engine; no transcript is replayed into another model. The UI should remain compact and use SnowSeek's established black theme and typography, with clear loading, download, import, low-storage, unsupported-format, and inference-error states.

## Catalogue and trust

- Represent catalogue items as app-owned metadata, not arbitrary remote instructions: stable ID, display name, format, exact repository/file/revision or direct HTTPS URL, exact download size, optional SHA-256, base model, publisher, license URL, task/language summary, supported chat template, runtime minimum, and curated memory estimate/profile.
- Pin downloadable files to immutable revisions. Verify HTTPS, exact size, and SHA-256 when known before atomic install. A source without a known digest must still be pinned and size-checked and must be labelled as not checksum-verified.
- Start with the existing Qwen3 0.6B LiteRT-LM artifact and at least one GGUF artifact from `ggml-org` (Qwen3 0.6B Q4_0 is an initial candidate). Before release, verify exact file revision, license, file size, SHA-256, model template compatibility, and a successful on-device response for every seed entry. Add a larger choice only if its peak-memory estimate is appropriate for at least one phone tier and its runtime compatibility is verified.
- `ggml-org` is the llama.cpp team's quantized GGUF publisher, while the base model can have a different author and license. Show both provenance and license accurately; do not describe a conversion as the base-model author's official artifact.
- Keep locally imported items marked “Импортирована”. Read GGUF metadata where possible. A `.litertlm` import receives structural checks and an engine-load check. Imported-item quality/performance estimates are rough unless SnowSeek has a device-specific measurement.

## Device fit and workload

- Read Android device memory class and available physical memory through Android APIs, and use the current ABI/runtime requirements.
- Curated catalogue estimates may use measured device profiles where available. Otherwise compare the model's documented/observed peak memory requirement to available memory and present a conservative heuristic such as “легкая / средняя / высокая нагрузка” plus a short explanation.
- Separate file/download size from peak inference memory. Never imply that equal-sized model files have equal memory use.
- For imported files without validated profile data, show “оценка примерная” or “нет данных”; do not invent a device benchmark.
- Warn/block before loading if the device is clearly below the minimum memory requirement. Use one model engine at a time; unload it before switching.

## Runtime and storage architecture

- Introduce a common model metadata/store layer and a small inference adapter interface that supports load, stream response chunks, cancel, and close.
- Keep LiteRT-LM as the `.litertlm` adapter. Add llama.cpp through a pinned source revision, arm64-only NDK/CMake build, and minimal JNI bridge for GGUF metadata, context setup, token streaming, cancellation, and teardown.
- Require catalogue entries to be conversational/instruct models with a chat template compatible with the chosen runtime. For imported GGUF, read and use its embedded chat template where possible; fail with a clear unsupported-model explanation if there is no usable conversation template.
- Begin with CPU inference and a conservative configurable context size. Preserve existing app minSdk 26 unless a verified native requirement makes that impossible; test API 26 compatibility before accepting the integration.
- Use SnowSeek private files storage for installed models and temporary `.part` downloads. Use Android's Storage Access Framework picker for user-selected files, then copy to app-managed storage and release the source URI permission. Never load directly from an unstable picker URI.
- Check remaining disk space before download/import and before copying. Keep imports/downloads atomic so an interrupted copy cannot appear as a usable model. Deletion removes the app-managed file only after unloading its engine.
- Keep the catalogue separate from installed-model state. Persist model ID, source, file name, format, size, checksum/import validation, and selected model. Do not persist chat prompts as part of model management.

## Constraints and risks

- The current app is arm64-only, minSdk 26, and uses LiteRT-LM 0.17.1. Model entries must match the runtime versions actually integrated; newer LiteRT-LM exports can require newer runtimes or hit template/runtime bugs.
- llama.cpp's official Android sample currently declares minSdk 33, so the chosen custom NDK build needs a deliberate API 26 compatibility check. The official NDK command documents an Android API 28 target; this does not by itself prove API 26 compatibility.
- Bundling llama.cpp will increase installed APK size. This is a trade-off for direct GGUF support; measure the final arm64 APK instead of estimating it in advance.
- Model runtime memory varies by context length, backend, tokenizer/template, quantization, and device. Catalog ratings are guidance, not guarantees.
- Hugging Face source availability, rate limits, and model licenses differ. Provide a source link and an attribution/license view for each catalogue item.

## Builder decisions

- Choose a pinned llama.cpp revision and native build options that keep the JNI surface small and the arm64 binary size measured and reported.
- Decide whether API 26 can be retained after compiling and exercising the native library; if not, stop and report the exact incompatibility before changing minSdk.
- Select initial catalogue entries only after runtime, template, license, size, hash, memory, and real-device compatibility checks. Prefer a small GGUF that shares the existing Qwen family for a straightforward comparison.
- Design the screen from the goals above and existing SnowSeek visual language; keep model source/license and uncertainty visible without crowding the primary download/select action.

## Acceptance checks

- Existing installed Qwen3 0.6B LiteRT-LM still loads and responds in local chat.
- At least one pinned GGUF catalogue model downloads, verifies, loads through llama.cpp JNI, streams a response, cancels, unloads, and can be deleted.
- Both formats can be imported through the system picker, copied safely, recognized, and selected; invalid or unsupported artifacts show actionable errors.
- Download resume, cancellation, storage exhaustion, checksum/size mismatch, and model-switch unloading do not leave a partial file marked installed or crash the UI.
- The current minSdk 26 declaration is retained unless a concrete compatibility failure is surfaced and separately approved.
- Device-fit labels distinguish measured from heuristic estimates and show an unknown/rough state for unprofiled imported models.
- App package-size increase and minimum Android compatibility are recorded from the resulting arm64 build.

## Builder-check decisions

- **Conversation ownership:** a local chat is tied to the selected model, supports multi-turn context within that engine session, and is not written to persistent storage. Switching models starts an empty session and unloads the prior engine. This avoids silently mixing context across models and avoids retaining memory-heavy engines.
- **Chat capabilities:** preserve the current local chat's multi-turn conversation, concise system instruction, and streamed chunks. GGUF catalogue entries must have a tested chat template; imported GGUF must expose a usable template for chat or show an actionable incompatibility instead of sending raw unformatted text.
