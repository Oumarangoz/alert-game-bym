# Multi-axial click/log audit

This report is intentionally evidence-driven and avoids pretending certainty where the current code already contains partial fixes.

## LOG-01 — In-memory log list is updated with a non-atomic read-modify-write pattern

- Severity: **high**
- Confidence: **high**
- Area: **logging**

`_lines.value = (_lines.value + line).takeLast(500)` can lose log entries when multiple threads call `AppLog.add()` at nearly the same time.

Evidence:
- `app/src/main/java/com/alertgamebym/AppLog.kt`:96 — `_lines.value = (_lines.value + line).takeLast(500)`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:61 — `background Default scope emits logs`
- `app/src/main/java/com/alertgamebym/accessibility/TapAccessibilityService.kt`:94 — `gesture callback also emits logs`

Why it matters: The UI log panel and fallback export path can silently miss lines. This does not always produce a 0-byte file, but it does produce inconsistent exports and can make diagnosis look random.

Fix directions:
- Protect `_lines` updates with a `Mutex` or replace list copies with a concurrent ring buffer.
- Keep file persistence as the source of truth and treat the UI list as a best-effort preview only.
- Add a monotonic sequence number per log line so missing lines become visible.

Suggested experiments:
- Fire 2000 parallel `AppLog.add()` calls in a test and compare expected vs actual line count.
- Temporarily prefix each line with an incrementing integer and inspect gaps.

## LOG-02 — Disk writer swallows write failures, which can hide the real reason behind empty or incomplete exports

- Severity: **high**
- Confidence: **medium**
- Area: **logging/export**

The background writer catches all exceptions around `appendText()` and ignores them.

Evidence:
- `app/src/main/java/com/alertgamebym/AppLog.kt`:82 — `catch (_: Throwable) { } around file.appendText`
- `app/src/main/java/com/alertgamebym/AppLog.kt`:80 — `file.appendText(text, Charsets.UTF_8)`

Why it matters: If filesystem writes fail, the queue still drains and the app behaves as if persistence worked. Later, export logic may find an empty disk file and you lose the real failure signal.

Fix directions:
- On write failure, enqueue a compact in-memory fault marker and expose a `LOG:DISK_WRITE_FAILED` counter in the UI.
- Persist the last write error string in memory and include it in export output.
- Hold a short retry budget before dropping the batch.

Suggested experiments:
- Temporarily point log output to a read-only path in a debug build and verify the UI shows the failure.
- Add a counter for failed file writes and surface it near the 'Log TXT Kaydet' button.

## LOG-03 — Export can still be incomplete under sustained load because `flushBlocking()` proceeds even if timeout expires

- Severity: **medium**
- Confidence: **medium**
- Area: **logging/export**

`snapshotText()` calls `flushBlocking()`, but ignores whether the flush actually succeeded before reading the file and queue snapshot.

Evidence:
- `app/src/main/java/com/alertgamebym/AppLog.kt`:121 — `flushBlocking(flushTimeoutMs)`
- `app/src/main/java/com/alertgamebym/AppLog.kt`:114 — `flush success condition`
- `app/src/main/java/com/alertgamebym/MainActivity.kt`:150 — `export uses snapshotText()`

Why it matters: If the queue is busy, part of the batch may already be dequeued but not yet written when export reads the file. That yields partial exports and makes rare empty exports more plausible under extreme churn.

Fix directions:
- Make `snapshotText()` return both `text` and `flushComplete` and log a warning when exporting a partial snapshot.
- Under export, temporarily pause new writer batches for a very short critical section.
- Consider exporting from a locked append-only file handle rather than combining disk+queue+UI fallbacks.

Suggested experiments:
- Create a stress test that adds logs continuously while exporting every second.
- Record `queue_size`, `writer_busy`, and `flush_complete` in the exported header.

## LOG-04 — Current code makes true 0-byte exports less likely, so remaining 0-byte cases probably need instrumentation rather than blind patching

- Severity: **medium**
- Confidence: **medium**
- Area: **logging/export**

The latest code falls back to the in-memory list when disk and queue snapshots are blank. That means recurring 0-byte files are likely caused by state reset, provider behavior, or a path outside the current happy path.

Evidence:
- `app/src/main/java/com/alertgamebym/AppLog.kt`:139 — `fallback to in-memory lines`
- `app/src/main/java/com/alertgamebym/MainActivity.kt`:155 — `export success is logged after stream write`

Why it matters: This is where overconfidence is dangerous: the current code already contains a fallback. If the user still gets 0-byte files, the next step should be evidence collection, not guessing.

Fix directions:
- Write an export header with `line_count`, `disk_bytes`, `queue_size`, and `flush_complete` for every saved file.
- Store the exact export byte count in a log line after saving.
- Verify whether the target document provider is returning an empty writable stream on the specific device/app picker.

Suggested experiments:
- After export, immediately reopen the same URI and log the actual byte count read back.
- Test saving to Downloads, Drive, and another provider to see if the bug is provider-specific.

## TAP-01 — Anti-spam is global, not phase-aware, even though the API shape suggests phase-aware logic

- Severity: **high**
- Confidence: **high**
- Area: **clicking/anti-spam**

`shouldClickPhase()` accepts `phaseKey` but only compares the last point/time. A valid second tap in another phase can be blocked if it lands near the same coordinate within 1200 ms.

Evidence:
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:1070 — `phaseKey is accepted`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:1071 — `decision ignores phaseKey`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:1080 — `phaseKey also ignored on mark`

Why it matters: If STATE1 and STATE2 or repeated actions happen near the same on-screen coordinate, the app can skip a legitimate tap and then appear inconsistent or 'stuck'.

Fix directions:
- Track anti-spam per phase key, or per `(phaseKey, roundedX, roundedY)` tuple.
- Include the phase key in the dedupe state and log the exact reason for rejection.
- Reduce the time window or make it configurable in debug builds.

Suggested experiments:
- Log `phaseKey`, `lastPhaseKey`, and the point delta when anti-spam blocks a tap.
- Temporarily disable anti-spam and compare behavior on the failing screen.

## TAP-02 — The blue bubble stays visible during scan/tap cycles and can contaminate screenshots or cover targets

- Severity: **high**
- Confidence: **high**
- Area: **clicking/visibility**

Transient overlays are hidden, but the main bubble is intentionally kept visible and touchable during capture and tap flows.

Evidence:
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:187 — `method keeps bubble`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:192 — `bubble remains visible/touchable`

Why it matters: If the bubble overlaps the ROI or item region, the screenshot contains app-owned pixels instead of game pixels. This can break matching and OCR in a way that looks random to the user.

Fix directions:
- Add a 'hide bubble during scan/tap' option and default it on for automation.
- At minimum, auto-move the bubble away from both the icon ROI and item ROI before a scan.
- Log the bubble bounds together with the ROI bounds to detect overlap.

Suggested experiments:
- Move the bubble far from the ROI and compare match rate.
- Capture and save the exact screenshot used for matching on a failing run.

## TAP-03 — Template matching search is fragile for device scaling and animation variance

- Severity: **high**
- Confidence: **high**
- Area: **matching**

Matching only tries 3 scales (0.90, 1.00, 1.10) with a fixed step of 3 pixels. Animated UI, DPI changes, and slight zoom differences can be enough to miss.

Evidence:
- `app/src/main/java/com/alertgamebym/image/ImageTemplateScanner.kt`:75 — `only three scales`
- `app/src/main/java/com/alertgamebym/image/ImageTemplateScanner.kt`:107 — `fixed step size`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:273 — `user-visible symptom is repeated 'not found'`

Why it matters: This directly matches the symptom where test taps succeed but auto mode cannot find the red/brown icon in ROI.

Fix directions:
- Widen the scale set (for example 0.80..1.20 with coarse-to-fine refinement).
- Allow per-reference confidence thresholds and save the best score history.
- Persist a debug crop of the ROI plus the reference thumbnail whenever a match fails.

Suggested experiments:
- Dump the ROI bitmap and top-5 scores for 20 failing runs.
- Test a second matching method, such as edge map or normalized cross-correlation, on the same frames.

## TAP-04 — Each scan creates a new VirtualDisplay and ImageReader, which increases latency and stale-frame risk

- Severity: **medium**
- Confidence: **high**
- Area: **capture/performance**

Both template matching and OCR create a fresh screen capture pipeline every time they run.

Evidence:
- `app/src/main/java/com/alertgamebym/image/ImageTemplateScanner.kt`:170 — `new ImageReader for every template scan`
- `app/src/main/java/com/alertgamebym/ocr/OcrDebugScanner.kt`:58 — `new ImageReader for every OCR scan`
- `app/src/main/java/com/alertgamebym/image/ImageTemplateScanner.kt`:183 — `retries wait for frames`

Why it matters: When the target moves fast, the app may tap based on an older frame. To the user this looks like 'it found it but still missed'.

Fix directions:
- Keep a reusable capture session alive while automation is running.
- Time-stamp each captured frame and log capture age at tap time.
- Add a max-age check: do not tap using a frame older than a threshold.

Suggested experiments:
- Log capture duration and frame age for every successful/failed match.
- Compare match success with a persistent VirtualDisplay implementation.

## TAP-05 — OCR item selection uses nearest-to-center instead of best interaction target

- Severity: **medium**
- Confidence: **high**
- Area: **item-ocr**

When several OCR lines contain the query, the code picks the line nearest to the ROI center, not the most likely clickable control.

Evidence:
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:1019 — `nearest-to-center winner`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:1004 — `substring matching can match multiple lines`

Why it matters: On crowded screens, the chosen OCR line may not be the intended item or may point to text instead of the tappable hotspot.

Fix directions:
- Rank candidates by text similarity, line size, and distance to a learned item anchor rather than center alone.
- Let the user choose between 'nearest center', 'topmost', and 'highest confidence' policies in debug mode.
- Store bounding boxes and tap relative to a configurable offset within the matched box.

Suggested experiments:
- Log all candidate lines and their scores for failing OCR taps.
- Try a policy that picks the lowest candidate in the ROI if the game list grows downward.

## TAP-06 — Gesture timeout handling is better now, but the app still lacks a full action-level acknowledgement model

- Severity: **medium**
- Confidence: **medium**
- Area: **gesture-lifecycle**

`performTapAt()` treats `completed` as success, but the higher-level flow has no visual post-condition check that the intended UI actually changed.

Evidence:
- `app/src/main/java/com/alertgamebym/accessibility/TapAccessibilityService.kt`:34 — `gesture timeout exists`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:866 — `flow trusts gesture completion`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:262 — `phase advances after tap success`

Why it matters: A completed injected gesture is not the same as a confirmed in-game action. Without a post-condition, the state machine can drift away from the real UI.

Fix directions:
- After a tap, verify a small post-condition: icon disappeared, item appeared, or OCR text changed.
- Distinguish `gesture_success` from `action_confirmed` in logs.
- Retry or roll back phase when the expected visual change does not happen within a short window.

Suggested experiments:
- For STATE1 and STATE2, sample the ROI again 150–300 ms after tap and check whether confidence changed as expected.
- Add a debug mode that saves before/after ROI pairs for each tap.

## OPS-01 — The app needs stronger evidence capture before more patching

- Severity: **medium**
- Confidence: **high**
- Area: **observability**

The code now has several protections already. The fastest path to the next real fix is to collect more structured evidence rather than continue blind patching.

Evidence:
- `app/src/main/java/com/alertgamebym/MainActivity.kt`:487 — `log UI exists`
- `app/src/main/java/com/alertgamebym/BubbleOverlayService.kt`:246 — `runtime config is already logged`

Why it matters: This project has already gone through multiple rounds of patching. At this stage, the missing piece is usually not another guess but a better per-run trace.

Fix directions:
- Add a per-run session id, exported header, counters, and small image artifacts for failing scans.
- When export succeeds, include byte count and provider authority in the saved file.
- Add a one-button 'diagnostic bundle' export containing log, config, ROI screenshot, and reference thumbnails.

Suggested experiments:
- Implement a debug build option that writes a zipped diagnostic bundle after every failed cycle.
- Compare three sessions on the same screen with only one variable changed each time: bubble position, confidence threshold, and ROI size.
