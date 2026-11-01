# Editor Spike Report — Phase 0 (spec §5 gate)

_Library under test: **Compose Rich Editor 1.2.1** (`com.mohamedrejeb.richeditor:richeditor-compose`, Maven Central, latest stable verified 2026-11-01). Kotlin 2.4.21 / Compose BOM 2026.09.00._

## Why this gate exists

Tiptap has no native equivalent. Nota stores note bodies as HTML-canonical text (spec §3/§4), so the editor lives or dies on two questions: (1) can it *produce* Noma 1.x's formatting surface interactively, and (2) does HTML survive the round trip HTML → editor document → HTML without losing meaning? Question 2 is the hard requirement — it is what makes backup compatibility and the 1.x crossover honest.

## Method

- A throwaway spike screen in the app (Settings → “Editor spike (Phase 0)”) wiring one toolbar button per formatting verb to the library's public API, plus Load-fixture / Export-HTML actions.
- JVM unit tests with fixtures shaped like real Noma/Tiptap output (`SpikeFixtures`, in the app sources): strict assertions for core shapes (`HtmlRoundTripTest` — **all passing in CI**) and non-asserting probes that record exact outputs for risky shapes (`HtmlRoundTripProbeTest`, outputs quoted below from the CI artifact `unit-test-evidence`).

## API surface confirmed from the 1.2.x sources

- `rememberRichTextState()` / `RichTextEditor(state)` (material3). `RichTextState` is a plain class, constructible in JVM tests.
- Inline: `toggleSpanStyle(SpanStyle)` (bold/italic/underline/strike/**colour**/**highlight** via `color`/`background`), `toggleCodeSpan()` (inline code). Note: `toggleCode()` exists only as a `DeprecationLevel.ERROR` alias of `toggleCodeSpan()` — **code is span-level in this library; there is no code-block paragraph type**.
- Blocks: `setHeadingStyle(HeadingStyle.H1..H6)` — headings are a persisted paragraph-level identity with real `h1..h6` HTML tags, not visual fingerprinting; `toggleUnorderedList()` / `toggleOrderedList()` (+ list levels); `toggleParagraphStyle` (alignment etc.).
- Links: `addLink(text, url)`, `addLinkToSelection(url)`, `updateLink`, `removeLink`.
- Images: `RichSpanStyle.Image` inline spans + an `ImageLoader` abstraction and an optional Coil 3 integration module (`richeditor-compose-coil3`) — images are inline spans, not block islands.
- Backlink autocomplete: a generic **trigger system** (`registerTrigger(Trigger(char))`, `activeTriggerQuery`, `TriggerSuggestions`; token spans round-trip through HTML as `<span data-trigger=…>`). It is single-character: `[[` is two characters, so a `[` trigger plus query handling is an approximation, not a native `[[` trigger.
- **No table model exists in the library** (no table paragraph/span types in 1.2.x), and no blockquote or divider types were found in its model/API.

## Round-trip results (probe outputs, verbatim from CI)

| Shape | Result |
|---|---|
| Inline marks (strong/em/u/s/code) | ✅ **Lossless.** Output identical except tag renames `<strong>→<b>`, `<em>→<i>`. |
| Headings h1/h2/h3 | ✅ **Lossless**, byte-identical. |
| Bullet + ordered lists | ✅ **Lossless**, byte-identical. |
| Link + colour span + `<mark>` | ✅ **Preserved with normalisation**: href kept (exporter adds `target="_blank"`); `#c0392b` re-serialised as `rgba(192, 57, 43, 1.0)` (same colour); `<mark>` kept. |
| Bengali text | ✅ **Lossless** (full-note probe: heading + body intact). |
| `[[Backlinks]]` literal text | ✅ Passes through untouched. |
| Task lists (Tiptap `taskList`/`taskItem`/`data-checked`) | ❌ **Structure destroyed.** Output: `<p>Done item</p><p>Open item</p>` — list, item type and **checked state all lost**. |
| Blockquote | ❌ **Structure lost.** Output: plain `<p>A quoted line.</p>` (text kept). |
| Code block `<pre><code>` | ⚠️ **Block-ness lost.** Output: `<p><code>…</code></p>` (text + code span kept). |
| Table | ❌ **Structure destroyed.** Output: `<p>NameScorePhysics133Chemistry115</p>` — every cell concatenated with no separators. |
| Divider `<hr>` | ❌ **Dropped silently.** |
| Inline image `<img src alt>` | ⚠️ Tag and `src` kept; `alt` dropped; exporter emitted `width="0.0" height="0.0"` (no ImageLoader is registered in a JVM probe, so sizing is unresolved there — on-device behaviour with the Coil module is unverified). |

## Per-feature verdicts (interactive surface, 1.x parity)

- Bold / italic / underline / strike / inline code — **works** (`toggleSpanStyle`, `toggleCodeSpan`).
- Text colour + highlight — **works** (`SpanStyle.color` / `.background`).
- H1–H3 — **works** (`setHeadingStyle`, H1–H6 available).
- Bullet / numbered lists — **works**, incl. nesting levels.
- Links — **works** (add/update/remove APIs).
- Quotes — **blocked**: no quote type in the model; import flattens quotes to paragraphs.
- Code blocks — **partial**: code exists only as an inline span; `<pre>` blocks flatten on import.
- Dividers — **blocked**: no divider concept found.
- Tables — **blocked**: no table model at all; import mangles tables.
- Inline images — **partial**: image spans + Coil integration exist; alt text dropped on round trip; device-side sizing unverified.
- Charts as embedded blocks — **not supported** by the library; would have to be a Nota custom span via the library's `RichSpanStyle` registry/descriptor system (designed for app-defined span types — plausible, unproven).
- `[[` backlink autocomplete — **feasible with work**: single-char trigger system can drive suggestions from a `[` trigger; literal `[[…]]` text round-trips. Needs a Phase 1 prototype, not a Phase 0 blocker.
- HTML round trip overall — **partial**: lossless for the everyday core (paragraphs, headings, inline marks, lists, links, colours, highlights, Bengali); lossy-to-destructive for task lists, quotes, code blocks, tables, dividers.

## Recommendation — **CONDITIONAL GO** on Compose Rich Editor

Adopt Compose Rich Editor as the Phase 1 editor base. Do **not** fall back to a hand-rolled EditText/Spannable editor: the library covers the interactive 90% (marks, headings, lists, links, colours) with a maintained, Compose-native API, and its failures are concentrated in *import fidelity of block structures* — a layer Nota controls. A custom editor would have to rebuild everything this library already does well, and its HTML round trip would still be hand-written. The conditions are binding:

1. **Nota-side HTML guard layer.** Before any note HTML enters the editor, a guard intercepts the shapes this library destroys — tables, task lists, blockquotes, `<pre>`, `<hr>` — and converts them to/from protected representations (Nota custom block spans via the library's `RichSpanStyle` registry, or block islands rendered outside the text flow, which spec §5 already sanctions for tables/charts). **Until the guard exists, no code path may pass stored note HTML through `setHtml` → `toHtml` and save the result** — that round trip silently mangles tables and task lists. Phase 2's crossover milestone (restore a real 1.x backup) depends on this rule.
2. **HTML stays canonical in Room** (spec §3). The editor is a view over a guarded transformation, never the owner of the stored format.
3. **Phase 1 starts with the guard for task lists and tables** — the two shapes real Noma notes actually contain — before editor UI polish. If the registry route proves impractical for tables, tables fall back to block-island custom views (spec §5), not to a different editor.

What would change this verdict: discovering in Phase 1 that custom block spans cannot preserve task-list checked state or table cell structure through the registry. At that point the honest move is block islands for those shapes (still not a new editor), and this report's evidence already isolates exactly which shapes need them.

## Build-stack notes from Phase 0 (for future phases)

- AGP 9.5's new DSL rejects the classic `org.jetbrains.kotlin.android` plugin: the project sets `android.builtInKotlin=false` + `android.newDsl=false` in `gradle.properties`. Migration to AGP's built-in Kotlin is **required before AGP 10** (the flags are removed there).
- AGP 9's signing DSL rejects password property assignment in Kotlin DSL (script compilation error); release signing therefore uses AGP **injected-signing properties** on the CI Gradle command line (the same mechanism Noma's CI uses for its debug keystore), fed from repo secrets.
- Room runs on **KSP** (2.3.13): kapt cannot resolve Room 2.8's `@Fts5` (the annotation is absent from kapt's stub classpath) — and in fact **Room 2.8 removed `@Fts5` entirely** (verified in room-common 2.8.5: only `Fts3`/`Fts4` remain). The schema uses `@Fts4`; the MATCH query surface is unchanged.
- Current libraries require **compileSdk 37** (AAR metadata floor); `targetSdk` stays **36** per spec §1 — compile-time floor only, runtime behaviour opt-ins unchanged.
