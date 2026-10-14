# Workout Tracker v1

Android app for Mahesh's 5-day top-to-bottom gym split. Package: `com.mahesh.workouttracker`

## Install the APK
File: `WorkoutTracker-v1.apk` (also in the goal files folder)
1. Copy the APK to your Android phone (Drive, USB, or download from the link).
2. Tap it and allow "Install unknown apps" for your browser/files app if asked.
3. Open **Workout Tracker**. The 5 preloaded workouts appear on first launch.

This is a debug build signed with the debug key — fine for personal side-loading, not for Play Store.

## How it works
- **Week runs Sunday-Saturday.**
- **Week counter:** Home shows "Week N" plus the date range, e.g. `Sun Oct 5 - Sat Oct 11`.
  It is a *training* week counter, not a pure calendar counter. Seeded at Week 1 on first launch.
  On each launch the app steps through every elapsed Sun-Sat week: Week N goes up by 1 **only if that week had at least 1 completed workout**, otherwise it stays the same. The displayed week start then advances to the current Sunday.
- **Home:** Suggested workout for today's weekday is highlighted. Each routine card shows Done (checked/struck), In Progress, or Pending for this week, with Start / Resume / Do Again. Resume banner appears if a session is unfinished.
- **Session:** Tap Start to create today's snapshot (template edits later don't rewrite it). Reorder exercises with ^/v for *today only*, or use "Save Order as Default" to update the template. Each exercise has set rows: weight, reps, BW toggle, Done check. Checking Done starts a 60-second rest timer (Skip/Cancel). Add/Remove sets as needed. Everything autosaves to local SQLite — leave and resume exactly where you stopped. Finish Workout marks it completed (unchecked sets are kept).
- **Routines:** Manage workouts grouped by weekday (Sun-Sat + Unassigned). Add/edit a workout: name, muscle focus, assigned day, YouTube URL, notes, and its exercises (name, type Warmup/Main/Stretch, equipment Kettlebell/Dumbbell/Bodyweight/Machine/None, default sets, target reps, default weight, variation, YouTube URL) with reorder/edit/delete.
- **History:** All sessions by date with week number; tap to view/edit.
- **Settings:** lb/kg unit toggle (applies to labels and CSV), Export CSV, Restore preloaded routines (keeps history), version.

## Preloaded workouts (Mon-Fri)
Mon Shoulders & Traps, Tue Chest & Triceps, Wed Back & Biceps, Thu Legs (Quad focus), Fri Legs (Hamstring/Glute) + Core. Each has a warm-up row, 6-7 main rows mixing kettlebell/dumbbell/bodyweight/machine where possible, and a stretching row. Sun/Sat are rest (unassigned).

## CSV export
Use Home or Settings → Export CSV, then pick where to save (Storage Access Framework). One row per set, header:
`date,week_number,week_start,workout,exercise,exercise_order,exercise_type,equipment,set_number,weight,weight_unit,reps,completed,workout_youtube_url,exercise_youtube_url`
Dates are ISO (`yyyy-MM-dd`), commas/quotes are escaped, bodyweight sets export weight as `BW`. All sessions (completed and in-progress) are included.

## Build (offline, this environment)
Uses the tic-tac-toe pattern: file:// local Maven repo in `~/workspace/.tooling/localrepo`, AppCompat 1.7.0 only, framework SQLite (no Room).
Important: launch Gradle with IPv4 preference or the daemon socket is intercepted in this VM:
```
export JAVA_HOME=~/workspace/.tooling/jdk17
export GRADLE_USER_HOME=~/workspace/.tooling/gradle-home
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true -DsocksProxyHost= -Dhttp.proxyHost= -Dhttps.proxyHost="
~/workspace/.tooling/gradle-8.9/bin/gradle assembleDebug --offline
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

## V2 (2026-10-07) - Beginner-first card flow

Flow: Home card -> Workout Detail (preview, nothing starts) -> [Start/Resume Workout] -> Sections (Warm Up / Exercise / Cool Down) -> Card mode per section (or View as list). History opens the session list directly.

New in v2:
- Body map per workout + per exercise (Canvas, front/back)
- YouTube links are per exercise (_Watch Form Video_); workout-level video removed from UI & CSV. CSV header: `date,week_number,week_start,workout,exercise,exercise_order,exercise_type,equipment,set_number,weight,weight_unit,reps,completed,exercise_youtube_url`. Skipped exercises export their sets with `completed=false` (no separate status column).
- Equipment badges (KB/DB/BW/MC), expanded 3-4 item Warm-Up and Cool-Down per day, targetMuscles on every seeded exercise
- Last performed line with effort suggestion (Too Easy -> try +5 lb/+2 reps, Just Right -> same weight, Too Hard -> lighter)
- Card swipe flow: left=Skip, right=Done-all-sets, up=+Set Done logging dialog, with matching on-screen buttons + Undo. Status: session_exercises.status (pending/done/skipped)

Beginner Mode (default ON, Settings toggle): every seeded exercise shows Do it (sets x reps in words), Check your posture (3-5 plain-language self-checks), What you'll feel (target muscle), Watch Form Video, form cues, Start light hint on weighted moves. Easier/Swap on Main cards swaps in a beginner alternative. Tap logging: steppers + preset chips (weights, reps 8/10/12/15, rest 30/60/90s) - no typing required to log. No accounts, no points/levels (gamification deferred).

Migration: DB v1->v2 only ALTER TABLE ADD COLUMN (exercises.targetMuscles/cues/postureCheck, session_exercises.targetMuscles/status/cues/effort/postureCheck). One-time v2SeedApplied replaces the 5 preloaded routines' exercise lists (weekdays kept); custom routines untouched; history preserved. Installs over v1.

Build: `JAVA_HOME=~/workspace/.tooling/jdk17 GRADLE_USER_HOME=~/workspace/.tooling/gradle-home GRADLE_OPTS="-Djava.net.preferIPv4Stack=true -DsocksProxyHost= -Dhttp.proxyHost= -Dhttps.proxyHost=" ~/workspace/.tooling/gradle-8.9/bin/gradle assembleDebug --offline`

## V2.1 (2026-10-07) - UI + navigation overhaul (versionCode 3, versionName 2.1)

Presentation/navigation only — no DB or feature changes; everything in the V2 section works as before.

**Theme: clean LIGHT theme** (chosen over dark: a beginner reading posture checks in bright gym light gets better contrast from near-black text on white than white-on-navy).
Palette (tokens in `Theme` in Ui.kt): background `#F3F5F9`, surface `#FFFFFF`, surfaceVariant `#EEF2F7`, stroke `#D8E0EA`, shadow `#C9D6E4`, primary `#2563EB` (dark `#1D4ED8`), textPrimary `#111827`, textSecondary `#5B6B7F`, textTertiary `#8494A7`, success `#15803D` on `#DCFCE7`, warn `#92400E` on `#FEF3C7`, pending `#475569` on `#E2E8F0`; sections: Warm Up amber `#D97706`, Exercise blue `#2563EB`, Cool Down teal `#0D9488`. AppCompat theme is now Light.NoActionBar with matching window background.

**Real cards:** `cardLayout()` now builds a LayerDrawable — soft shadow layer (`#C9D6E4`, offset 3dp down, visible as a 4dp band under the card) + white surface with 20dp corners and 1dp stroke (2dp colored stroke when an accent is passed) — plus view elevation and a RippleDrawable press state on tappable cards. 18dp padding, 14dp gaps. Buttons: 56dp primary with ripple, outlined secondary, 44dp tonal small/chip buttons; typography helpers `screenTitle / overline / cardTitle / bodyText / caption` (sans-serif-medium titles).

**Navigation:** persistent custom bottom nav bar (Home 🏠 / Routines 📋 / History 🕓 / Settings ⚙️, accent + top indicator on the selected tab) on Main, Routines, History, Settings via `tabScaffold()` in Ui.kt; tabs relaunch with REORDER_TO_FRONT. The stacked Home "Menu" button block and Home's duplicate Export CSV are gone (Export lives in Settings). Secondary screens (Detail, Sections, Card, Session list, Routine/Exercise edit, Summary) get a consistent top app bar (back arrow + title) and no bottom bar.

**Screens:** Home = gradient hero card (Week N, dates, X-of-5 + progress, muscles map below), featured Suggested Today card, attention-style Resume card, tappable This Week cards with chevron. Detail = Start/Resume card at top, hero card, section preview cards with colored left accent bar. Sections = three large tappable color-accented section cards with progress bars. Card mode = deck peek strips behind the main card, colored top band by section, set dots, 52dp weighted Skip / + Set Done / Done buttons (swipe tint kept). Session list = cards grouped under colored section headers, set rows as tinted tiles with styled inputs and steppers. Routines = day-grouped tappable cards, Add Workout up top. History = tappable session cards. Settings = grouped cards (Units, Beginner Mode, Training Week, Your Data, About; shows Version 2.1). Summary = gradient celebration hero + stat tiles, still factual (no points/badges).

## V2.2 (2026-10-07) - swipe-card feel (versionCode 4, versionName 2.2)

Card-mode interaction fix only (CardSessionActivity + one helper in Ui.kt); no DB, feature, or flow changes.

- **One moving card:** the whole card — LayerDrawable shadow/border (`deckCardDrawable` in Ui.kt, 20dp corners, 8dp elevation), colored top band, body map, posture content — is the dragged view, over two static peek cards (scaled 0.96 / 0.92, offset down) in a deck container. The card rotates subtly with horizontal drag (proportional to dx, max ±8°, pivot near bottom centre).
- **Strict commit rule (a light turn never commits):** horizontal commit needs |dx| ≥ max(36% of card width, 130dp); up (Log Set) needs |dy| ≥ max(30% of card height, 110dp); the dominant axis must also be ≥ 1.3× the other axis. Distance only — a fast flick below threshold snaps back, velocity never commits.
- **Faded direction labels:** stamped labels on the card — ✓ DONE (green, top-right), ✕ SKIP (red, top-left), ↑ LOG SET (blue, top-center) — start invisible and fade in with drag progress (alpha = |drag| / threshold, full at threshold, slight scale-up, one haptic tick on crossing). The card border and a translucent face tint strengthen toward the direction color with the same progress.
- **Animations (framework animators only):** snap-back 220ms with OvershootInterpolator(0.8) (gentle spring); commit exit 260ms with AccelerateInterpolator(1.1), flying off to ±(card width + 80dp) and fading out, with the action applied only after the exit finishes; next-card entry 220ms DecelerateInterpolator from scale 0.96 / +18dp / alpha 0; the peek card swells toward full size as the top card leaves. The Skip / + Set Done / Done buttons trigger the exact same animations as the swipes.
- **Up-swipe treatment:** the card does not exit (the log dialog is the focus) — it lifts ~48dp, scales to 0.98, fades to 0.85 over 180ms, the Log Set dialog opens, and the card settles back over 200ms.
- **Tap safety:** dragging only starts after movement exceeds touch slop, and the card requests disallow-intercept while dragging, so taps on in-card buttons (Watch Form Video) and small nudges are never treated as swipes.


## V2.3 (2026-10-07) - approved mockup + workout timer + exercise library (versionCode 5, versionName 2.3)

Everything in the V2/V2.1/V2.2 sections keeps working (WeekManager counter, Beginner Mode, posture checks, Easier/Swap, effort faces, last-done, CSV export, 60-second rest timer, seed, and the v2.2 strict swipe thresholds/animations — the card drag code is unchanged).

**Approved mockup changes (Mahesh approved the web mockup on 2026-10-07):**
- **Mist palette (Mahesh color change):** background `#EAF0F5`, card surface `#F7F9FB` (soft off-white — never pure `#FFFFFF` cards), surfaceVariant `#EDF2F7`, stroke `#D6DEE8`, shadow `#C3D0DE`, textPrimary `#132033`, textSecondary `#5A6B80`, primary `#2563EB`; hero gradient stays primary→primaryDark. Bottom nav and the log-set bottom sheet use the same `#F7F9FB` surface. Drag-direction tints/gradients were retinted to the mist surface while keeping the done/skip/log colors recognizable.
- **Simplified exercise card (card mode):** essentials only up front — colored section band, big name, "Do 3 sets of 10" in words, big set-progress dots, equipment badge, small body map, Last done line, and the Skip / Log Set / Done action row. Educational content (Watch Form Video, "What you'll feel", posture self-checks, form cues, "Start light") lives behind a **Show me how** toggle: expanded by default for the FIRST card of a section, collapsed for the rest, remembered per exercise while in the section. The Session list view applies the same Show me how per exercise in Beginner Mode (first exercise expanded by default); with Beginner Mode OFF it shows only the video button, as before.
- **Home = daily loop:** Week progress hero (Week N, Sun–Sat dates, "x of 5", progress bar, muscles trained this week) -> TODAY hero (workout name/focus, body map, status pill, ONE primary Start/Resume/View button, "4 Warm Up • 6 Exercise • 3 Cool Down" breakdown) -> slim This Week cards. The duplicate Resume banner only appears when the in-progress workout differs from today's.
- **Log Set bottom sheet:** logging a set now opens a bottom-anchored dialog (rounded top corners, drag handle, `#F7F9FB` surface) with big −/+ steppers for sets count / weight / reps, weight chips (BW,10,20,30,40,50), reps chips (8/10/12/15), rest chips 30/60/90s, and the optional type-in fallback. Same data written, same effort prompt after completion, same rest countdown after save.
- **Vector icons instead of emoji UI chrome:** `res/drawable` vector drawables (ic_home, ic_list, ic_history, ic_settings, ic_play, ic_check, ic_close, ic_arrow_up, ic_timer, ic_chevron, ic_back) used in the bottom nav, buttons, section headers, back bars and the timer chip. ✓/✕/●/○/☐ glyphs remain as typography. 20dp card padding, 16–20dp corners, generous gaps throughout.
- **Detail/Sections/Summary alignment:** start card + hero with main-exercise vs total-activity counts ("6 exercises (14 total incl. warm-up & cool-down)"), section previews list warm-up/cool-down items by name, body-map thumbnails on section and routine cards.

**Workout timer (NEW, Mahesh request):**
- **DB v3** — upgrade is ALTER-only, no tables dropped: `sessions ADD COLUMN startedAt INTEGER`, `elapsedSec INTEGER DEFAULT 0`, `endedAt INTEGER`. Pre-v3 sessions keep null/0 and simply show no duration.
- Sessions stamp `startedAt=now, elapsedSec=0` on creation (Start Workout). `WorkoutTimer` (WorkoutTimer.kt) keeps a resume timestamp in SharedPreferences (`timer_resume_<sessionId>`, epoch ms) plus a paused flag (`timer_paused_<sessionId>`): live elapsed = banked `elapsedSec` + (now − resume)/1000 while running.
- Timer chip in the top card of Workout Sections, Card mode, and Session list (ic_timer + clock, mm:ss or h:mm:ss, 1s Handler tick). Tap toggles pause/resume — pause folds live seconds into `elapsedSec` and persists; resume records a fresh timestamp, so leaving/reopening the app continues correctly. Elapsed is also folded on every screen's `onPause`, and on Finish Workout (folds, stamps `endedAt`, clears the timer keys). If the app is killed mid-workout, the banked seconds survive.
- Displays: Summary gains a "Time" stat (e.g. "48 min" / "1 hr 5 min"); History cards append "• 48 min" when timed; Home's today/resume cards show elapsed so far.
- **CSV:** one new trailing column `workout_duration_sec` (session `elapsedSec`, blank for pre-v3 sessions). New header: `date,week_number,week_start,workout,exercise,exercise_order,exercise_type,equipment,set_number,weight,weight_unit,reps,completed,exercise_youtube_url,workout_duration_sec`.

**Exercise library (NEW, Mahesh request):** 33 additional beginner-safe exercises — 3 per muscle group across Shoulders, Traps, Chest, Triceps, Back, Biceps, Quads, Hamstrings, Glutes, Calves, Core — defined as static data in `Library.kt` (no new DB tables), each with mixed equipment, default 3 sets, sensible reps (Farmer's Carry 40 sec, Mountain Climbers 30 sec, Russian Twist 20 (10 ea)), explicit plain-language posture checks and "Do it" cues, and `targetMuscles` for the body map. New `LibraryActivity` (Routines → "Exercise Library") groups them per muscle with a body-map thumbnail; each card has **Watch Form** and **Add to Routine** (dialog picks a routine; appends as a Main exercise at the end). Routine Edit also has **Pick from Library** to insert one directly. Daily routines are unchanged — the library is only a pool to add/swap from. All library video links are honest YouTube *search* URLs (`https://www.youtube.com/results?search_query=<name>+form`); no video IDs were invented.

Build: same offline invocation; APK `WorkoutTracker-v2.3.apk` (versionCode 5, versionName 2.3) installs over v2.2 with history preserved.

## V2.4 (2026-10-07) - preview cards without starting (versionCode 6, versionName 2.4)

Mahesh request: "Without starting the workout also I should be able to click the card and see it but not log exercise unless I've clicked start workout. Basically see the cards without doing the workout."

- **New `PreviewCardsActivity`** (registered in AndroidManifest; new `ic_eye` vector): opens with a prominent "Preview — Start Workout to log" banner so the mode is unmistakable. Inputs: `routineId`, optional `sectionType` ("Warmup"/"Main"/"Stretch"; absent = all exercises in warm-up → main → cool-down order).
- **Strictly read-only (the core invariant):** the deck is built purely from template exercises via `db.getExercises(routineId)` — never session exercises. The activity never calls `db.createSession`, any insert/update/delete, any `WorkoutTimer` start function, or any SharedPreferences timer key while browsing. Its only database reads are `getRoutine`, `getExercises`, `sessionsInWeek` / `inProgressSessions` (for the Start button label), and `lastPerformedForExercise` for the Last done line. The single write path in the whole screen is the user tapping Start Workout (see below).
- **Same card, browse navigation:** mist palette deck with the whole-card drag feel (rotation, peek cards, entry/snap-back animations) but browse semantics — swipe **left = next card**, swipe **right = previous card**, with faded "NEXT ›" / "‹ PREV" direction labels (never DONE/SKIP). Same strict commit rule as card mode: |dx| ≥ max(36% of card width, 130dp), dominant axis ≥ 1.3× the other, distance only — a light nudge snaps back and never changes the card. Previous/Next buttons mirror the swipes; swipe/button at the deck ends simply settles back. Cards show section band, big name, "Do N sets of X", **hollow neutral plan dots** (not progress), equipment badge, body map from `targetMuscles`, Last done line, and the same **Show me how** toggle as session cards (posture checks, cues, Watch Form Video; first card expanded by default). Progress header: section name + "Card 3 of 13". No Skip/Log Set/Done buttons, no effort prompt, no rest timer, no workout-timer chip.
- **Start from preview:** sticky primary button under the deck (label mirrors Workout Detail: Start Workout / Resume Workout / Start Again). Tapping it runs the exact existing start flow — `WeekManager.reconcile`, reuse this week's in-progress session for the routine if one exists, else `db.createSession(...)` — then opens `WorkoutSectionsActivity` and finishes the preview.
- **Entry points:** Workout Detail's Start card gains a secondary **Preview Cards** button (eye icon), visible in all states (not started / in progress / done); additionally each section card on the detail page (both the progress and the not-started variants) is tappable and opens the preview filtered to that section. Routines still open detail as before; no Home changes.
- **Edge cases:** a routine with 0 exercises shows "No exercises yet — add some in Routines." Back behaves normally; rotation simply re-renders like the other activities.

No DB changes (still v3); installs over v2.3 with history preserved. Build: same offline invocation; APK `WorkoutTracker-v2.4.apk` (versionCode 6, versionName 2.4).

## V2.5 (2026-10-07) - every screen fits one page, no scrolling (versionCode 7, versionName 2.5)

Mahesh request: "it would be great if each page on the app comes in the entire page and no vertical scrolling is required."

**Approach:** presentation-only rework. `grep -rn ScrollView app/src/main/java` returns **zero** — every activity's root is a fixed-height container (`fitRoot()` / the non-scrolling `tabScaffold()`), and fit is achieved by (a) compact adaptive density and (b) pagination/tabs for inherently long content. Text floors kept: body ≥12sp, secondary ≥11sp, primary actions ≥48dp tall. Shared helpers in Ui.kt: `fitRoot()`, `pagerBar(page, pageCount, onPrev, onNext)` (‹ Prev • 2/5 • Next ›), `isCompactScreen()` (screen height <700dp → card padding 14dp vs 18dp, tighter gaps) and `screenHeightDp()` for page-size math.

**Per-screen fit map:**
- **Home:** compact week hero (muscles-trained folded into it) → Today hero → optional one-row resume strip → This Week as flexible ~52dp rows (weight=1 region), bottom nav always visible.
- **Workout Detail:** top bar, Start/Preview card, compact hero, then exactly three compact section rows (tap → per-section preview; exercise names live in the preview cards, counts/progress on the rows). Progress state shows the same three rows with progress bars + an Open Sections bar.
- **Workout Sections:** compact header (body map + timer chip + beginner note inline), three section cards sharing the remaining height, fixed View-as-list / Finish bar.
- **Card mode + Preview:** fixed root; the deck fills the flexible middle (weight=1) and the action rows are pinned below. The expanded Show-me-how block is compacted to live *inside* the card: 76dp body map, posture bullets capped at 4, cues at 3 (2 on compact screens), tighter line spacing, 48dp video button. If an exercise has more bullets/cues than the cap, the remainder is shown as a single "…" line — the one deliberate truncation (how-to tail on very content-heavy cards).
- **Session list:** exercises are paginated by estimated height (roughly 1-3 per page depending on set count, Show-me-how state, and screen height; expanded cards take a whole page). Set rows are compact 48dp chip rows (weight/reps steppers, BW, Done), section header + pager bar, and a fixed bottom action bar (Save Order / Finish / Save & Exit / Discard).
- **Routines:** 4 compact cards per page + pager; Library/Add buttons pinned on top.
- **Routine Edit:** compact two-half form (name/focus, day/notes) + paginated exercise rows (5/page on tall screens, fewer on short ones) + fixed Add/Pick and Delete/Back bars. Form text is kept across re-renders so paging never loses typing.
- **Exercise Edit:** one fixed two-half form — all 10 fields + 2 spinners + Save visible with the keyboard closed. (When the keyboard opens it may overlay the lower fields; that is OS behavior, not app scrolling.)
- **Library:** one muscle group per page — wrapping group chips on top, compact group body-map header, the group's 3 exercises sharing the remaining height.
- **History:** 5 compact session rows per page + pager (full set detail remains in the session view/CSV).
- **Settings:** four compact grouped cards (training-week rule folded into About) — fits with the bottom nav visible.
- **Summary:** compact celebration hero, body map + stat rows (exercises/sets/skipped/time), per-section lines, buttons pinned at the bottom.

**Truncation flags:** only the how-to caps above (posture ≤4 bullets, cues ≤3, surplus shown as "…") plus normal single-line ellipsizing of long names/labels in the compact rows. No features, data, DB, timer, preview, library, or swipe-threshold behavior changed.

Build: same offline invocation; APK `WorkoutTracker-v2.5.apk` (versionCode 7, versionName 2.5) installs over v2.4 with history preserved. Not device-tested.


## V2.5.1 (2026-10-07) - hotfix: Home showed only "0 / 5" (versionCode 8, versionName 2.5.1)

Mahesh report on v2.5: "Home page shows 0/5 nothing else. Seems like a bug" — only the week hero rendered; Today and This Week were invisible.

**Root cause (confirmed in code):** v2.5 made pages fixed non-scrolling LinearLayouts whose children used WRAP_CONTENT heights. On a real phone (font scale, status/nav bars), hero + Today card + labels summed past the viewport; LinearLayout laid them out anyway and clipped the overflow, leaving the weight=1 week region at zero height and everything below the hero off-screen. Fixed-height stacks only work with explicit budgets — v2.5 relied on wrap heights happening to fit.

**Fix — deterministic height budgets:** every region on every screen now has an explicit dp height or is the single weight=1 flex region, so the stack cannot exceed the viewport by construction. `topBar` is a fixed 56dp. No ScrollView returned (grep stays 0). Text floors: body >= 12sp, secondary >= 11sp, actions >= 44dp.

**Home budget** (available = screen height - ~72dp bottom nav - root padding):
| Region | ~640dp viewport (available ~546dp) | ~800dp viewport (available ~706dp) |
|---|---|---|
| Hero | 104dp (very short) | 118dp compact / 128dp |
| "Today" label | 20dp | 20dp |
| Today card | 132dp (56dp map, 44dp Start inside) | 150dp |
| Resume strip (only when shown) | 48dp | 48dp |
| "This Week" label | 24dp | 24dp |
| Week rows region | ~218-266dp (5 rows share equally, >= 44dp each when available >= 500dp) | ~330-380dp |

Trained-muscles line folded into the hero (no separate row); Today status moved inline (no separate pill row); safety rule shrinks hero/Today to their short-screen floors before the week region may drop.

**Per-screen audit (fixed heights + the one flex region):**
- Workout Detail: top 56 + Start card 100 + hero 116 + label 24 + section region (weight 1, 3 equal rows) + Open Sections 48.
- Workout Sections: top 56 + header 112 + label 24 + section region (weight 1) + finish bar 48.
- Settings: title 40 + Unit 116/124 + Beginner 116/124 + Your Data 84/88 + About 104/112 (bottom nav below).
- Summary: top 56 + hero 84 + stats 148 + by-section 92 + filler (weight 1) + note + buttons 48.
- Session list: top 56 + header 76 + exercise region (weight 1) + action rows 44 + 44. See Routines/Session notes below.
- Routines: title 40 + Library/Add row 56 + routines region (weight 1). History unchanged in structure: title 40 + list region (weight 1, 5/page) + pager 48.
- Routine Edit: top 56 + header 64 + two 58dp form rows + Save 48 + label 24 + exercise region (weight 1, paged rows) + pager + two 48dp action bars. Exercise Edit: top 56 + name 54 + 4 field rows x 54 + hint 18 + buttons 48 (fields are 14dp label + 40dp input; documented heights sum to ~390dp + padding, so Save is visible on a 600dp viewport with the keyboard closed).
- Library: top 56 + group chips 126 (3 x 42) + group header 76 + 3 cards sharing the region (weight 1) + footer 20.
- Card mode / Preview: top 56 + header card 96 (card) / banner 64 + progress 56 (preview) + deck (weight 1) + fixed 48dp action rows (+ 20dp Next line). Deck absorbs all slack; actions are pinned and visible on a 640dp viewport by construction.

**Mahesh refinements folded in (override v2.5 pagination on two screens):**
- **Routines: NO pagination.** All routines (currently 5) on one fixed page; cards share the region equally (height 0, weight 1), name/day/count on compact lines. If the count grows past ~8 the cards simply get shorter — accepted. The "Exercise Library (33 extra moves)" button became a compact 56dp card row: "Exercise Library" (15sp, one line) with "33 extra moves" as a small caption — it no longer clips at 360dp wide.
- **Doing-exercise page (Session list): ONE PAGE, no pagination.** All ~13 exercises visible at once; each row is an equal weight share (single line: status dot, name, set chips, reorder +/-, how-to toggle). Tap a set chip to log/edit that set (weight/reps/BW/Done with steppers, same writes as before; Done starts the 60s rest timer). Header (with timer chip) and the bottom action bar (Save Order / Finish / Save & Exit / Discard, two compact 44dp rows) are fixed. "Show me how" is an accordion: the expanded row takes a 3.2x weight share while others compress; posture <= 4, cues <= 3, surplus as "…".

**Button overflow audit (app-wide):** all primary/secondary/small buttons are now single-line with ellipsis + autosize down to 13sp (11sp small) on API 26+. Specific labels shortened: "Delete This Workout" -> "Delete Workout" (Routine Edit), card-mode "Swap -> <long name>" -> "Swap ->" (the dialog still names the alternative), Routines "Add New Workout" -> "Add Workout" beside the new Library card. Settings buttons verified at 360dp: "Turn Beginner Mode OFF/ON", "Restore Preloaded", "Switch to kg/lb", "Export CSV" fit on one line.

No DB or feature changes (still DB v3); installs over v2.5 with history preserved. Not device-tested.


## V2.6 (2026-10-07) - UI cleanup from on-device feedback (versionCode 9, versionName 2.6)

Mahesh's v2.5.1 feedback, item by item:

1. **"Hide how-to" button removed; posture always visible.** The Show me how / Hide how-to toggle is gone from CardSessionActivity, PreviewCardsActivity, and the SessionActivity list. Cards now render a compact posture block by default: Watch Form Video (44dp), "What you'll feel", "Check your posture:" header with up to 4 bullets, up to 2 cue lines, and the Start-light hint — no collapsing. Body map shrunk to 52x64dp and line spacing tightened so it fits the fixed-height card. In the Session list, rows no longer have a chevron toggle BUTTON: tapping the row expands/collapses it (accordion, passive chevron glyph), posture visible in the expanded row.
2. **Preview is pure browse.** PreviewCardsActivity no longer has a Start Workout button (or a bottom Back button); the banner reads "Preview — browsing only". Start lives only in Workout Detail and the Resume flows. Preview remains strictly read-only.
3. **Corner back control.** `topBar()` keeps a single 40dp circular corner button (ic_back, surface bg + stroke, top-left, title beside it). Full-width/bottom "Back" buttons were removed from Preview, RoutineEdit, and ExerciseEdit — exit is the corner control everywhere.
4. **Cute segmented Home progress.** The giant "0 / 5 done" hero text is replaced by 5 rounded segments (12dp, one per routine, green `#4ADE80` when done, white 35% alpha when not) plus a small "0 of 5 this week" caption (13sp). Week N and the date range stay; hero budget unchanged (104/118/128dp).
5. **Today card has no Start button.** The Home Today card is now purely tappable -> Workout Detail, with a status line and a subtle "Tap to open ›" affordance. Card height drops to 108dp (very short) / 120dp; the freed height goes to the This Week region.
6. **Workout Detail: one clean action pair.** The separate top Start card is deleted. Inside the body-parts hero card (172dp): body-map row, status pill row ("Done / In Progress / Pending" pill + short state line), then two equal halves at 48dp with an 8dp gap — primary "Start Workout / Resume Workout / Start Again" (ic_play) and secondary "Preview" (ic_eye). Per-section rows below unchanged.

Invariants kept: no ScrollView (grep = 0), deterministic per-screen height budgets, DB v3 unchanged, timer/preview/library/swipe behavior intact apart from the items above. Build: same offline invocation; APK `WorkoutTracker-v2.6.apk` (versionCode 9, versionName 2.6) installs over v2.5.1 with history preserved. Not device-tested.

## V2.6.1 (2026-10-07) - screenshot cleanup + copy audit (versionCode 10, versionName 2.6.1)

Mahesh installed v2.6 and sent screenshots; each flagged item plus a full copy audit ("for each tab there are some redundant or useless text — revisit and clean it up").

**Screenshot fixes:**
- **Workout Detail hero count line:** was "Tuesday • 7 exercises (4 Warm Up • 7 Exercise • 3 Cool Down) • Quads, Calves, Chest, Shoulders, Triceps". Now exactly "Tuesday • 7 exercises • 14 total with warm-up & cool-down" (main count = "exercises", total separate, per the approved mockup).
- **Wrong muscles on hero/Home/Routines:** `DbHelper.routineMuscles()` now aggregates MAIN exercises only. Warm-up/cool-down moves (bike, incline walk, stretches) no longer leak Quads/Calves/etc. into body maps or "what you'll feel" labels anywhere that helper feeds (Detail hero, Home Today + week rows, Routines rows).
- **Clipped status pill (Detail hero):** root cause was the pill measured taller than its 26dp row (text 11sp + 5dp padding + the pill's own 6/2dp margins) inside the fixed 172dp hero. Fix: `statusPill()` no longer carries built-in margins and uses 4dp vertical padding app-wide; the Detail status row is an explicit 30dp with the pill given its own 24dp height and margins stripped; hero grew 172→176dp (taken from the flexible section region). Audited the other statusPill sites (Home week rows, History rows) — both sit in CENTER_VERTICAL flex rows and now measure ~24dp.
- **Oversized Start/Preview buttons:** `makeButton`/`makeSecondaryButton` set minHeight 56/52, which silently overrode the buttons' 48dp layout height and inflated them (huge play/eye in the screenshot). New `Ui.tightButton()` strips min-height/width and reapplies a 12dp-radius background; compound icons are set with explicit bounds (`Ui.boundedIcon`: 20dp primary, 18dp small) instead of intrinsic vector size. Detail action pair: 48dp, 15sp, 20dp icons, equal halves.
- **Preview headers merged:** the "Preview — browsing only / Nothing is recorded here…" banner card AND the separate "Warm Up • Card 1 of 4" card with an empty bar are now ONE slim header (48dp: circular corner back + "Preview — Warm Up" + right-aligned "1 / 4") with a 6dp progress bar under it (56dp total). Fill is position-based, (index+1)/total — card 1 of 4 shows 25%. `CardSessionActivity` uses the same rule (max of handled count and position), so its bar is never empty on card 1 either.
- **Preview card body:** top padding 14dp so the name never touches the band (checked in card mode too: 14dp padding + 8dp band gap, name max 2 lines); header row = 46x56dp body map + 18sp name + equipment badge; "Do it:" directly under with set dots INLINE on the same row, only when sets > 1; the redundant "Planned sets — preview only" line is deleted; "First time — no previous record. Start light." no longer shows in preview (still in session mode); posture block normalized to ~8dp gaps (the old look stacked makeText's default margins into dead whitespace).
- **Previous/Next buttons:** 48dp tall, 15sp, min-height stripped — they were rendering at ~56dp+ with margins, crowding half the bottom.

**Copy audit (per screen — removed/shortened):**
- Home: "Tap to open ›" (Today card) -> chevron icon; verbose "● IN PROGRESS — name / Week N • … • tap to resume" banner -> "In Progress" pill + name + elapsed. (There is no app-name overline on Home; Week + dates + segments + caption only.)
- Workout Detail: "Your progress / What you'll do — tap a section to preview its cards" header removed; "Not started yet / Pick up where you left off / Done this week — go again anytime" line removed (pill only); section rows "… activities • tap to preview ›" -> "… activities", "… done/skipped • tap to preview ›" -> "… done/skipped".
- Sections: "Beginner Mode ON" suffix removed (duplicates Settings toggle); "Today's sections" header removed; section caption "x of y handled • z done • w skipped • tap to open" -> "z done • w skipped".
- Card mode: "Swipe: Skip • Done • Log Set (or use buttons below)" removed; completion screen text shortened to counts. ("Next up:" kept.)
- Preview: banner sentence removed with the banner; in-card swipe hint removed; nav buttons shrunk as above.
- Session list: "Sets: … (tap a number chip to log)" -> "Sets: …".
- Routines: "… • tap to edit" removed from routine rows.
- Routines' Library entry unchanged (already "Exercise Library / 33 extra moves").
- Library: topBar subtitle "33 extra moves • one muscle group per page" -> "33 moves"; group header "Targets: … • 3 moves • add any to a routine" -> "Targets: …"; "Group x of 11" footer removed.
- History: unchanged (empty state "No sessions yet." kept).
- Routine Edit: "Form videos are per exercise below." removed.
- Settings: Units caption -> "Applies to labels and CSV."; Beginner caption -> "Posture checks and form cues on every exercise."; About -> 2 lines (Version 2.6.1 + week-advance rule).
- Summary: "— nice work showing up and getting it done." subtitle and "Every workout counts — see you next session." filler removed (stats only).

Kept deliberately: exercise names, sets/reps, posture bullets, cues, last-done, status, counts, week/dates, timer, button labels, empty-state one-liners. Beginner posture content untouched by the audit.

Invariants kept: no ScrollView (grep = 0), "Planned sets — preview only" grep = 0, height budgets hold, DB v3 unchanged. APK `WorkoutTracker-v2.6.1.apk` (versionCode 10) installs over v2.6. Not device-tested.

## V2.7 (2026-10-07) - party popper at the end of the workout (versionCode 11, versionName 2.7)

Mahesh request: "When all the exercise of a body part is done. Do a party popper to celebrate end of exercise and back to home page." — i.e. when the day's body-part workout is finished, celebrate, then land back on Home.

- **Trigger sites (fresh completion only):** both existing finish paths pass `celebrate=true` to SummaryActivity — `WorkoutSectionsActivity.doFinish()` (including the Finish-early confirm; it is still the end of that workout) and SessionActivity's Finish dialog. Finishing is confirmation that the session is completed, so the celebration is session-level; there is deliberately NO confetti for finishing a single section inside card mode. Opening a summary any other way (e.g. reviewing from History) does not pass the extra and never celebrates. Re-opening a session and re-finishing fires it again.
- **Celebration:** a new `ConfettiView` (custom View, framework animators only, no new dependencies) overlays the summary: ~90 particles — small rounded rects + circles in primary blue/primary-dark/teal/amber/green/pink — burst from the top-center, fall with gravity, terminal fall speed, sway and rotation with a fake-3D squash, fading out over ~2.8s. The overlay is non-clickable, so buttons beneath keep working. A big 🎉 (48sp-ish) above the hero pops with a scale bounce (0.6 → 1.1 → 1.0, ~420ms, OvershootInterpolator) and the hero reads "🎉 Workout complete!". One short haptic fires with the pop (VibrationEffect 80ms on API 26+, legacy guarded fallback, wrapped so a missing vibrator can never crash). The summary content itself is unchanged — routine stats (exercises/sets/skipped/time), trained-muscles map and by-section lines, copy-audit tone, no extra filler.
- **Back to Home:** the primary button becomes "Back to Home" → MainActivity with CLEAR_TOP | SINGLE_TOP. After the confetti ends, the summary AUTO-returns to Home at ~4.5s total — but only if Mahesh hasn't interacted: any touch/button press (`onUserInteraction`) or leaving the screen (`onPause`) cancels the pending auto-navigation, so he is never yanked away mid-read and never trapped either.
- **Fit:** Summary root is now FrameLayout { existing fixed column, ConfettiView }. The hero grows 84→110dp only when celebrating (popper line), and the flexible filler absorbs it — the one-page/no-scroll invariant holds (grep ScrollView = 0).

Build: same offline invocation; APK `WorkoutTracker-v2.7.apk` (versionCode 11, versionName 2.7) installs over v2.6.1 with history preserved. Not device-tested — the confetti animation and vibrator cannot be verified in this environment.

## V2.8 (2026-10-07) - Sign in with Google + Drive backup/restore + new app icon (versionCode 12, versionName 2.8)

Mahesh request: "can you update the code to support login via gmail and once integrated ensure that data is backed up in google drive and restored from there", plus "could you also select a new custom icon for the app".

**Sign-in design (no new Gradle dependencies — framework classes only):**
- OAuth2 Authorization Code flow with PKCE (S256; verifier/challenge via SecureRandom + SHA-256 + Base64URL). Scopes: `openid email profile https://www.googleapis.com/auth/drive.appdata`.
- The auth page is launched with a plain ACTION_VIEW intent (androidx.browser/Custom Tabs is not available offline); Google redirects to `com.mahesh.workouttracker:/oauth2redirect`, captured by `OauthCallbackActivity` (singleTask, exported, scheme/host intent-filter in the manifest). Code exchange and refresh happen at oauth2.googleapis.com/token; sign-out revokes best-effort.
- **Default Client ID is baked in** (`GoogleAuth.DEFAULT_CLIENT_ID` = `746141152814-7uc1ov26tuflo5mapc010t4al6134ptf.apps.googleusercontent.com`), so Sign in works out of the box with zero pasting. It is Mahesh's Android OAuth client from his own Google Cloud project, registered for **package `com.mahesh.workouttracker`** and **SHA-1 `7B:82:1B:6B:13:FD:A8:B2:2E:15:7A:EA:0B:84:BC:EE:66:DB:08:CC`**. A Client ID is a public installed-app identifier (no secret), so it is safe in source and in this public repo. **Changing the signing key (new debug/release keystore) requires registering a new Android OAuth client for the new SHA-1 and updating the default or the Settings override.** Settings keeps a "Google Client ID" field as an override: a non-empty saved pref wins over the default, and the field is prefilled with the effective value.
- Tokens (access + refresh) are AES/GCM-encrypted with a key held in the Android Keystore (alias `wt_google_key`); ciphertext lives in a dedicated `google_auth` SharedPreferences file. Tokens are never logged and never appear in source or repo. Only the display identity (email/name from the id_token) and bookkeeping (last-backup time) are stored plain.

**Backup/restore (Drive REST v3 over HttpURLConnection):**
- Backup file: `workout-tracker-backup.json` in the app-private **appDataFolder** (invisible in the user's Drive UI, scoped by `drive.appdata`). Existing file is PATCH-updated; first backup creates it via multipart POST.
- Payload: `{ format: 1, appVersion, exportedAt (epoch ms), tables: { routines, exercises, sessions, session_exercises, session_sets } }` — every row, every column, IDs preserved — plus a prefs subset (unit, beginner mode, WeekManager `weekNumber`/`weekStart`). Seeded exercise content (posture checks, cues) lives in the exercises table, so the snapshot covers it.
- Restore semantics: **full replace, transactional**. The file is validated (`format == 1`), then in ONE SQLite transaction the 5 tables are deleted and re-inserted with original IDs, and the prefs subset is restored. Any error rolls back — local data is left untouched.

**Triggers:**
- Settings gains a "Google Backup" section: signed-out shows "Sign in with Google" (Client ID field prefilled — no setup needed); signed-in shows name/email, "Last backup: <date or Never>", and **Backup now** / **Restore from Drive** (with a replace-confirmation dialog) / **Sign out**. Backup/restore run on a background thread with a progress dialog.
- Auto-backup: after a session is marked completed at either finish site (`WorkoutSectionsActivity.doFinish`, SessionActivity Finish dialog) and the user is signed in, a silent background backup fires (failures ignored, never blocks the UI).
- Right after a successful sign-in: if a Drive backup exists and the phone has 0 sessions, the app offers the restore once.
- Manifest adds the INTERNET permission (the app previously had none).

**New app icon:** bold white kettlebell silhouette (arc handle with a visible grip window over a round ball) centered on the theme's deep primary-blue gradient (#2563EB→#1D4ED8), with a small green (#22C55E) check badge tucked at the ball's lower-right. No text; art sits inside the adaptive-icon safe zone. Implemented as `ic_launcher_background.xml` (gradient) + `ic_launcher_foreground.xml` (vector art) + adaptive icons in `mipmap-anydpi-v26`, with PIL-rendered density PNGs (mdpi 48 → xxxhdpi 192) as the pre-API-26 fallback. A 512×512 preview lives at `workout-tracker-icon-v2.8.png` next to the APKs in the goal files folder. Settings About now reads Version 2.8.

Invariants kept: no ScrollView (grep = 0), DB still v3 schema, no-scroll height budgets hold (Settings re-budgeted to fit the new card), timer/preview/library/confetti untouched, no new dependencies. Build: same offline invocation; APK `WorkoutTracker-v2.8.apk` (versionCode 12, versionName 2.8) installs over v2.7 with history preserved. **UNTESTED end-to-end**: no live Google sign-in or Drive round-trip has been run from this environment (compile + code-path review only) — the first real test is signing in on-device.

> **Superseded in V2.9:** the sign-in design above (browser Authorization Code + PKCE with a custom-scheme redirect) is rejected by Google on Android — see the V2.9 section. v2.8's Drive backup payload/restore design is unchanged and carried over.

## V2.8.1 (2026-10-07) - new launcher icon: woman doing a dumbbell lunge (versionCode 13, versionName 2.8.1)

Icon-only change on top of v2.8; Mahesh rejected the kettlebell ("something that shows a women character in yogapants doing workout"). No code, DB, feature, or behavior changes — Google sign-in / Drive backup, timer, preview, library, confetti all untouched (grep ScrollView still 0).

- **Design:** flat fitness-app illustration of a woman mid-workout — dumbbell lunge, dark ponytail, coral sports top, dark navy yoga pants (leggings), coral shoes — large and centered on the app's blue (#2563EB→#1D4ED8 family; the art sits on its matching flat blue). No text. The v2.8 green check badge is dropped; the character is the whole icon. A 512×512 preview lives at `workout-tracker-icon-v2.8.1.png` next to the APKs in the goal files folder. Legibility checked at 48px: the figure still reads as a person lunging with dumbbells.
- **Route chosen — generated PNG art, not hand-built vector:** organic character shapes (ponytail, lunge silhouette, dumbbells) cannot be drawn recognizably with primitives the way the kettlebell could. The art was generated as a flat illustration with the environment's image tooling, then post-processed: the generator's baked-in white rounded corners were filled back to the art's own blue and the edge rim shaved, yielding a clean full-bleed square. The kettlebell assets (`drawable/ic_launcher_foreground.xml` vector + green badge) are replaced.
- **Assets:** the foreground is now a **PNG** (`drawable-nodpi/ic_launcher_foreground.png`, 864×864 — the same art with the figure at ~78% so she stays inside the adaptive-icon safe zone on every launcher mask), referenced unchanged by the adaptive `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`; the gradient `drawable/ic_launcher_background.xml` remains the background layer. Density PNGs (mdpi 48 → xxxhdpi 192, `ic_launcher.png` + `ic_launcher_round.png`) serve pre-API-26. Manifest unchanged (same @mipmap references).
- **GitHub note:** as in v2.8, the connector's file tools are text-only, so the binary icon PNGs (density icons + foreground) are **not** in the repo — the text files (build.gradle, README, Settings version string if counted) are pushed, and the icon art is reproducible from the 512 preview + the description above. The canonical 512 art ships with the APKs in the goal files folder.

Build: same offline invocation; APK `WorkoutTracker-v2.8.1.apk` (versionCode 13, versionName 2.8.1) installs over v2.8 with history preserved. Not device-tested.

## V2.9 (2026-10-12) - Google sign-in fixed: Identity Services SDK (versionCode 14, versionName 2.9)

Mahesh report: sign-in in v2.8/v2.8.1 fails on his phone with an invalid-auth error.

**Root cause (honest):** v2.8's design was wrong for Android. It ran a browser OAuth2 Authorization Code + PKCE flow against an *Android* OAuth client and expected Google to redirect back through the custom URI scheme `com.mahesh.workouttracker:/oauth2redirect`. Google's current OAuth documentation states custom URI schemes are not supported for Android/Chrome apps and directs Android apps to the Google Identity Services SDK instead. No console toggle or alternate scheme fixes that design — v2.9 replaces it.

**What changed — sign-in only; Drive backup/restore is untouched:**
- **Dependency:** `com.google.android.gms:play-services-auth:21.6.0` (Google Identity Services). It was added to `~/workspace/.tooling/extra-roots.txt` and resolved with `~/workspace/.tooling/resolve.py` into the offline file repo (full closure: play-services-auth 21.6.0, play-services-base 18.5.0, play-services-basement 18.9.0, play-services-tasks 18.2.0, auth-api-phone/auth-base/fido, coroutines 1.8.0, etc.). One artifact the resolver's POM walk missed — `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0` — was fetched with the existing `fetch-missing.py` helper after Gradle named it. No google-services plugin and no `google-services.json`: the Authorization API is client-side.
- **Flow:** `Identity.getAuthorizationClient(...)` → `authorize()` with an `AuthorizationRequest` for scopes `drive.appdata`, `userinfo.email`, `userinfo.profile`, `openid`. Client-side access only — `requestOfflineAccess` is deliberately NOT called (that would need a web client ID + a backend this app doesn't have). If the result `hasResolution()`, Settings launches the pending intent via `startIntentSenderForResult` (request code 2002) and parses the reply with `getAuthorizationResultFromIntent`. Failures surface the ApiException status code + message; a DEVELOPER_ERROR additionally spells out that the Cloud Android client's package/SHA-1 doesn't match this app.
- **Token model change:** no refresh token exists anymore. The SDK returns short-lived access tokens (~1h); the newest is cached with the same AES/GCM + Keystore scheme as before (alias `wt_google_key`, `google_auth` prefs) and treated as expired after 50 minutes. `GoogleAuth.getAccessToken` (blocking, background threads only, `Tasks.await` 30s) returns the cached token or silently re-authorizes through the SDK; if Google requires interaction it returns null and Drive operations report "Google session expired — open Settings and sign in again". Manual Backup/Restore in Settings detect that case and run the interactive flow first, then continue the pending action automatically. Auto-backup after a finished workout stays silent/best-effort and simply skips when re-auth would need interaction.
- **Identity:** from `AuthorizationResult.toGoogleSignInAccount()`; if email/name come back blank, Settings fetches `https://openidconnect.googleapis.com/v1/userinfo` with the fresh token on a background thread. Signed-in state is a `gis_signed_in` flag + identity — never a stored token. **Upgrading from v2.8 requires one fresh sign-in**: leftover v2.8 tokens/Client ID/pkce values in the `google_auth` prefs are scrubbed on sight and never count as signed-in.
- **Sign out:** SDK `clearToken` for the cached access token + `revokeAccess` (account rebuilt from the stored email, same scopes), best-effort on a background thread, then all local auth state is wiped. Workout data untouched.
- **Deleted:** `OauthCallbackActivity` and its manifest intent-filter, the PKCE/verifier/state helpers, the token-endpoint exchange/refresh/revoke code, and the Settings "Google Client ID" field + Save button. The signed-out Settings card is now just an explanation + "Sign in with Google"; Settings height budgets were re-audited for the smaller card (bottom nav still always visible, no ScrollView). Post-sign-in behavior is unchanged: last-backup time is picked up from Drive, and a backup + zero local sessions offers the one-time restore.

**Cloud side — what stays true:** the EXISTING Android OAuth client in Mahesh's Google Cloud project (package `com.mahesh.workouttracker` + SHA-1 `7B:82:1B:6B:13:FD:A8:B2:2E:15:7A:EA:0B:84:BC:EE:66:DB:08:CC`) is what Play services uses to identify this app — it stays as-is, and its client ID string is no longer entered or stored anywhere in the app. The Drive API must be enabled in that project, and while the OAuth consent screen is in Testing, Mahesh's Gmail must be listed as a test user. Changing the signing key still requires registering a new Android OAuth client for the new SHA-1.

**Failure wording to expect if Play services still rejects the app identity:** tapping Sign in shows a toast like `Google sign-in failed (code 10): 10: Developer console is not set up correctly.` followed by ` — Google doesn't recognize this app: the Cloud Android client's package name and signing-certificate SHA-1 must match this app exactly.` (code 10 = DEVELOPER_ERROR; the middle text is Google's own message and may vary slightly by Play services version). Quote that toast back verbatim when reporting a failure — it names the exact mismatch.

Invariants kept: DB still v3, Drive payload/find/backup/restore and DbHelper import/export unchanged, WeekManager/timer/confetti/preview/library/icon (v2.8.1) untouched, grep ScrollView = 0, dead-flow greps (`oauth2redirect`, `o/oauth2/v2/auth`, `DEFAULT_CLIENT_ID`) = 0 in app/src. Build: same offline invocation. **UNTESTED end-to-end**: no device or Google account is available in this environment — sign-in is compile + API-contract verified only; the first real test is Mahesh's on-device sign-in.

## V2.9.1 (2026-10-12) - sign-in result handling fix (versionCode 15, versionName 2.9.1)

Mahesh's on-device v2.9 test reached Google's account picker, but after he selected an account the app reported "Google sign-in cancelled". Two defects were found:

**1. The app's signing key had silently changed (the likely proximate cause).** Verifying the delivered APKs' certificates showed the debug signing SHA-1 rotated across builds — v1 `0528…`, v2/v2.1 `8f27…`, v2.2–v2.4 `7c22…`, v2.5–v2.7 `7b82…`, v2.8/v2.8.1 `bb0c…`, v2.9 `0a1a…` — because the default debug keystore lives in `/root/.android` on ephemeral build-machine storage and was regenerated whenever the machine was replaced. The SHA-1 Mahesh registered in Google Cloud was `7B:82:1B:6B:13:FD:A8:B2:2E:15:7A:EA:0B:84:BC:EE:66:DB:08:CC` (the v2.5–v2.7 key). Play services identifies an app by package + signing SHA-1; with no registered Android OAuth client matching the installed build's key, authorization fails right after account selection and is surfaced as a cancellation. **Fix:** the current keystore (SHA-1 `0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE` — the key v2.9 was already built with, so v2.9.1 installs over it with data intact) is copied to the persisted workspace at `~/workspace/.tooling/signing/debug.keystore` (outside this repo; it is a private key) and `app/build.gradle` pins `signingConfigs.debug` to it, so the key can never rotate again. **Mahesh must add this 0A:1A… SHA-1 as an Android OAuth client in the same Google Cloud project** (package `com.mahesh.workouttracker`); the 7B:82… client alone no longer matches the app.

**2. v2.9's result handling hid the real error.** Settings launched Google's resolution PendingIntent with the deprecated `startIntentSenderForResult()` and then parsed the returned Intent **only if** `resultCode == RESULT_OK`. Any non-OK Activity result — including one carrying an Intent with a usable AuthorizationResult or the real ApiException — was discarded and replaced by the blanket "cancelled" toast. The app therefore could not distinguish a deliberate cancellation from a Google-side failure after account selection.

**What changed — sign-in plumbing + signing-key pinning; Drive backup/restore is untouched:**
- **Signing key pinned:** `signingConfigs.debug` in `app/build.gradle` now points at the persisted workspace keystore (see defect 1 above); the APK certificate SHA-1 is verified after every build from now on.
- **Current Activity Result API:** the resolution PendingIntent is now launched through `registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult())` with an `IntentSenderRequest`, matching Google's current Android authorization sample.
- **Parse first:** the launcher callback passes every non-null returned Intent to `getAuthorizationResultFromIntent()` before considering the Activity result code. A successful parse continues through the existing granted-result path. An ApiException is classified by its actual status: common cancellation (16) or Google Sign-In cancelled (12501) shows a diagnostic dialog with that exact code; every other code uses the detailed failure dialog, including the package/SHA-1 explanation for DEVELOPER_ERROR (10). Only a null Intent is treated as a bare cancellation, and even then the Activity result code is included. No token or Intent-extra value is displayed or logged.
- **Activity context for interactive auth:** interactive `authorize()` and result parsing now use an AuthorizationClient created from the Settings Activity. The application-context client remains in use for silent/background token work.
- **Pending action survives recreation:** the action waiting on interactive authorization ("signin", "backup", "restore", or "restore-run") is persisted in a small Settings flow prefs file instead of living only in an Activity field, so recreating Settings while Google's screen is open cannot lose the follow-up Backup/Restore action.
- **SDK update:** `play-services-auth` is updated from 21.6.0 to Google's current 22.0.0 and resolved into the same offline local repository.
- **Scopes unchanged:** Drive appData + userinfo email/profile + `openid` remain the request. Google's current Android authorization guide explicitly uses the userinfo/profile/openid scopes when the app needs the selected account's identity from the userinfo endpoint, and this app already has that fallback; there was no evidence that `openid` caused the cancellation, so it was not removed on speculation.

Invariants kept: DB still v3, Drive JSON payload/find/backup/restore and transactional restore unchanged, v2.8.1 icon retained, no Client ID in source, grep ScrollView = 0. **Still untested end-to-end in this environment:** the next real test is Mahesh installing v2.9.1, signing in from Settings, and tapping Backup now. If Google still returns an error, the new dialog's exact status code — not another bare "cancelled" — is the evidence needed for the next step.

## V3 (2026-10-13) - body-part workouts, JSON data, photos, Rest Ring (versionCode 16, versionName 3.0)

The fixed 5-day routine assignment is retired. New flow: Home shows a
**Week N scoreboard of body parts** -> **Start Workout** -> pick today's body
parts -> the app assembles **one flat workout list** -> in-workout each
exercise offers **Skip / Do later / Start**.

### Design: model in code, data in JSON

- **Model** — `BodyParts.kt`: the 11 body parts (shoulders, traps, chest,
  triceps, back, biceps, quads, hamstrings, glutes, calves, core), each with
  id slug, display name and a `repeatable` flag (only **core** is repeatable:
  it shows "N× this week" and never locks).
- **Data** — one JSON per body part in `app/src/main/assets/data/<part-id>.json`:

```json
{
  "bodyPart": "biceps",
  "warmups": [],
  "exercises": [
    {
      "id": "db-bicep-curl",
      "name": "DB Bicep Curl",
      "equipment": "Dumbbell",
      "sets": 3,
      "targetReps": "10",
      "targetMuscles": "biceps",
      "precautions": "Elbows fixed at your sides - they do not move forward;Upper arms completely still - only forearms curl;...",
      "cues": "Elbows fixed at your sides;Curl up without swinging;Lower slowly all the way",
      "image": "biceps",
      "url": "https://www.youtube.com/watch?v=5EQ1Nu1ODgE",
      "primary": true,
      "photo": "exercise-images/db-bicep-curl.jpg"
    }
  ],
  "stretches": []
}
```

`precautions`/`cues` are ";" separated and preserved verbatim from the v2
data. `primary` marks the old routine exercises (pre-checked when assembling);
library extras are `primary: false`. `easier` carries the existing
easier-variation name where one exists. Content migration: **94 entries**
(67 exercises = 34 routine + 33 library, 14 warm-ups, 13 stretches) — the
same 94 before and after; nothing was dropped.

On first v3 launch `JsonSeed` seeds the DB from the JSONs; afterwards the DB
is the working copy, so in-app add/edit keeps working, scoped by body part
(Body Parts tab -> part -> Add/Edit). Settings -> Restore Preloaded re-seeds
the canonical rows from the JSONs (user-added rows and history untouched).

### Flow details

- **Home**: Week hero (segment bar over the 10 non-repeatable parts) + all 11
  parts with last-trained date and this-week state. A part not trained in 7+
  days (or never) gets an amber **Due** tag; Done-this-week wins; Core keeps
  its count. Resume of an in-progress session is unchanged.
- **Picker**: multi-select parts (Due/Done state shown), "Build workout".
- **Assembled list**: warm-up card = union of the parts' warm-ups deduped by
  id; then ONE flat exercise list (no body-part sections), each row tagged
  with its body part, default order grouped by equipment — **Machine ->
  Dumbbell -> Kettlebell -> Bodyweight** (JSON order inside a cluster).
  Primary exercises pre-checked; rows can be unchecked and reordered (up/down
  buttons). Per-row last performance ("Last: 12×10 · 12×12 · 15×10"). Start
  creates the session with only warm-ups + checked exercises + stretches, in
  the displayed order.
- **In-workout**: card flow keeps its swipes and gains visible **Skip /
  Later** buttons; the list view's expanded row offers Skip / Do later /
  Start (log set). "Do later" moves the exercise to the end of today's
  remaining list. Last performance is prominent at the TOP of the card and in
  the expanded list row: "Last time (Tue Oct 7) · 12×10 · 12×12 · 15×10", and
  the set logger shows each set's own last-time value as a hint.
- **Repeat last time** (set logger): one tap fills all sets from the last
  performance and marks them logged; rows stay editable.
- **Beat last time** (Summary, fresh completions only): per exercise, total
  volume = sum over done sets of weight × reps; exercises with no weighted
  done sets on either day compare total reps. Shows "Beat last time on N of
  M exercises" plus up to 3 deltas.

### Rest Ring (replaces the old single fixed 60s rest timer)

The old behavior was one fixed 60-second countdown dialog. V3 replaces it
everywhere with a code-drawn **Rest Ring**: a circular ring that drains as
seconds pass, big second count in the center, ring turns warm amber in the
final 10 seconds, green "GO" pop + haptic at zero, "+15s" and "Skip rest"
buttons. Under the ring: the next set's last-time target ("Next · Set 3 —
last time 15×10"), or the next exercise's name when resting between
exercises, plus a rotating form cue from the current exercise's cues
("While you rest — ...", rotating every ~5s).

Durations are user-set defaults: **30 seconds between sets, 60 seconds
between exercises**, auto-started when a set is logged (more sets to go ->
set rest; exercise finished/skipped -> exercise rest). Both are adjustable
in Settings ("Rest Timer" card, ±5s / ±15s steppers), persist in prefs, and
are included in the Drive backup prefs subset.

### Exercise photos

Source: **yuhonas/free-exercise-db** (public domain / **Unlicense**),
downloaded 2026-10-13. Each photo is the dataset entry's first image,
resized to max width 640px, JPEG q80, bundled under
`app/src/main/assets/exercise-images/<our-id>.jpg` and referenced from the
JSON entry's `photo` field. Matching was variant-tolerant (same movement,
grip/equipment variants OK) but never forced across movements; unmatched
exercises fall back to the BodyMap visual. Coverage: **64 of 67 exercises**
(unmatched: Pike Push-up, Wall Sit, Fire Hydrant) plus 17 warm-ups/stretches
= **81 photos**; 20 images spot-viewed, 1 dropped and 2 re-matched after
checking. Full mapping: `research-exercise-images.md`.
**Note: the .jpg binaries live in the APK/local assets only and are NOT
pushed to GitHub** (the connector is text-only and corrupts binaries).

### DB v4

- `exercises` += `bodyPart TEXT`, `sourceId TEXT` (ALTERs; existing rows
  backfilled by name -> JSON id, duplicates from the multi-day seed deduped,
  all history preserved).
- New table `session_parts(sessionId, bodyPart)` — which parts a session
  trained; pre-v3 sessions backfilled from their exercises. New sessions
  store `routineName` = "Back + Biceps" style label and `focus` =
  comma-joined part ids.
- Drive backup payload adds `session_parts`; import tolerates old backups
  without it. CSV export gains a trailing `body_part` column.

Invariants kept: no ScrollView anywhere (grep = 0), one-page screens, Mist
palette, Beginner posture content untouched, launcher icon unchanged, no
gamification. Google sign-in / Drive auth code untouched (that thread is
parked; only the backup table list + prefs subset changed).

## V3.1 (versionCode 17) — on-device feedback round

DB stays v4 (no schema change). All 8 items are from Mahesh's on-device
v3 test:

1. **No feedback ask** — the post-exercise effort prompt ("How was that?")
   is gone entirely, as is the effort readout line on the cards. Finishing
   or logging never interrupts to ask how it felt. DB columns/data left
   as-is; effort suggestions simply stop being fed.
2. **Photo rendering** — the exercise photo is now the BACKGROUND of the
   card header (card mode) and of the exercise row (Library detail):
   a `CENTER_CROP` ImageView filling a rounded-corner frame — never
   stretched (`fitXY` grep = 0) — with the exercise name + last-time line
   overlaid at the bottom on a dark gradient scrim. No photo -> the body
   map gets the same header space.
3. **Card buttons** — no Skip and no Done buttons anywhere in card mode
   (both are swipe-only). The only buttons are **Log set** (primary) and
   **Later** (secondary) in one slim row under the card. Swipes keep the
   strict commit rules (dominant axis >= 1.3x, displacement thresholds):
   left = Skip, right = Done, up = Log set, down = Do later (new), with
   faded direction labels while dragging (incl. a new down LATER label)
   and one hint line: "Swipe <- Skip · -> Done · v Later". In the list
   session view the per-exercise button row is gone too: tap = Start/open
   (set logger), long-press opens a Start / Do later / Skip menu.
4. **Custom log values** — the set logger's weight and reps chip rows end
   with a **Custom** chip that opens a numeric editor (decimal for
   weight, integer for reps) prefilled with the current value; the
   displayed weight/reps value itself is also tappable to open the same
   editor. Fixed chips unchanged.
5. **Google sign-in removed from Settings** — the whole Google Backup
   section (account card, Sign in with Google, Backup now, Restore from
   Drive, Sign out) is deleted from the Settings UI. `GoogleAuth.kt` /
   `DriveBackup.kt` remain in the codebase but are unreachable from any
   UI; the post-workout auto-backup call sites stay silent no-ops when
   never signed in (verified by code read of `backupSilently`).
6. **Tab rename** — the bottom-nav tab formerly "Body Parts" is now
   **Library** (label only; content unchanged).
7. **Settings switches fixed** — the Units and Beginner Mode cards were
   shorter than their content (~102dp in 98dp cards) and clipped their
   buttons. Both cards are now 138dp with an exact 56dp switch button and
   full padding. Recomputed budget (compact): title 32 + Units 138 +
   Beginner 138 + Rest 72 + Data 116 + ~16 margins = ~512dp, under the
   ~546dp floor; still zero scrolling (ScrollView grep = 0) and every
   row fully visible (the removed Google card freed the space).
8. **Picker compatibility highlighting** — `BodyParts` gains a symmetric
   `compatibleWith` matrix (e.g. Chest<->{Triceps, Shoulders}, Back<->
   {Biceps, Traps}, Quads<->{Hamstrings, Glutes, Calves}, Core<->all).
   In the Start Workout picker: nothing selected -> all cards normal;
   with >=1 selected, unselected parts compatible with EVERY selected
   part get a primary border + "Pairs well" pill, and other unselected
   cards dim to alpha 0.55 (still fully selectable). Recomputed on every
   selection change.

Plus, from the same review — **animated exercise images**: every bundled
photo's dataset entry ships a second frame (the other movement position);
all 81 were downloaded, resized (max width 640px, JPEG q80) and bundled
as `assets/exercise-images/<id>-2.jpg`. Card-mode headers and Library
detail rows crossfade-loop the two frames (~800ms hold, ~350ms fade, two
stacked CENTER_CROP ImageViews, no animation library), stopping when the
screen isn't visible. Coverage: **81 of 81** photos animated; see
`research-exercise-images.md`. The -2.jpg binaries, like the originals,
are NOT pushed to GitHub (text-only connector).

APK: 11,166,828 bytes (+3,337,491 vs v3.0's 7,829,337 — the 81 second
frames). Signing cert unchanged: SHA-1
0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE.

## V3.2 (versionCode 18) — picker states, full-frame photo, polish pass

DB stays v4 (no schema change). From Mahesh's on-device v3.1 test; both
visual changes were approved as mockups first.

1. **Picker five-state system** (PickPartsActivity) — no legend row; the
   card treatments are the code, and they layer instead of competing:
   - **Selected**: solid primary fill (#2563EB), white name, last-line in
     #BFDBFE, white circle with blue ✓ on the card's top-right corner,
     "Selected" pill (white bg, blue text). Overrides everything.
   - **Suggested** (unselected, compatible with EVERY selected part):
     pale violet fill #F5F0FF with a 1.5dp DASHED violet border (#7C3AED,
     GradientDrawable dash stroke) and an "✦ Pairs well" pill (#EDE9FE /
     #6D28D9). Never a check mark.
   - **Done this week**: pale green fill #F0FDF4, green border #86EFAC,
     "✓ Done this week" pill (#DCFCE7 / #15803D); Core shows
     "✓ Done · N×" with its week count.
   - **Not paired** (selection non-empty, doesn't pair with all
     selected): whole card at alpha 0.45 + "Not paired" pill; still
     fully tappable.
   - **Plain** (nothing selected): default card, v3 Due/Pending pills.
   Layering: the Done pill renders whenever the part is done and not
   selected — on suggested cards (beside a small "✦" pill) and on dimmed
   not-paired cards; the suggested outline applies whenever paired; the
   dim applies whenever not paired. Recomputed on every selection
   change. Home/Library grids keep their existing Done/Pending/Due
   presentation. New helpers in Ui.kt: `stateCardDrawable` (layered
   shadow card with custom fill + solid/dashed stroke) and `pillView`
   (one uniform 10.5sp / 8x3dp small pill, now used by the picker, Home,
   Library and the build list's part tag).

2. **Full-frame photo** (CardSessionActivity, all three section types —
   one shared header builder). The photo band's height is now the card
   content width × 2/3, aspect-matched to the bundled 3:2 photos, so the
   ENTIRE frame is visible — CENTER_CROP in an aspect-matched box crops
   nothing and stretches nothing (fitXY grep stays 0). Both PhotoLooper
   frames keep identical containers. Layout per the approved mock: body
   part + equipment tags top-left over the photo; the bottom gradient
   scrim carries the exercise name + last-time line; the posture check
   moves OUT of the photo area into its own flat surface card (mist fill
   + stroke, 16dp corners) directly below the essentials row; then the
   Log set / Later row (now exactly 48dp) and the swipe hint line. The
   equipment badge that sat under the header is gone (it's a photo tag
   now); Do-it + Sets + progress dots form one compact essentials row.
   No-photo exercises get the same 3:2 band with a larger body map.
   Posture caps inside the card are height-aware (compact screens: 2
   bullets / 1 cue; else 3 / 1) so the fixed-height card does not clip
   at the ~546dp compact floor — the v2.5-accepted how-to cap; the full
   content remains in the list view. Library part-detail rows keep their
   v3.1 background treatment (per the brief). Card-screen budget re-run
   at the compact floor: topBar 56 + progress card 96 + deck (flex) +
   buttons 48 + hint 20 + next-up 20; the deck flex absorbs the taller
   photo band on short screens while name/last-time/tags/buttons/hint
   are always fully visible.

3. **Page-by-page visual polish pass** — every screen audited against:
   12dp screen padding, 8/12dp rhythm, 20dp card radius + Theme stroke,
   height budgets at the ~546dp floor, titles 18sp bold, body ≥12sp,
   secondary #5A6B80 / meta #8494A7, touch targets ≥48dp, uniform pills,
   scrims only under text on photos. Fixes: topBar titles 19→18sp;
   build-list checkbox 36→40dp and reorder buttons 34→40dp, row padding
   symmetric, meta 11→12sp; Sections footer buttons now tightButton'd to
   exactly 48dp (factory minHeight was inflating them) and the
   all-handled green matches the 12dp button radius; list view set
   chips 26×22→30×26dp, row reorder/set buttons 22→30×28dp (still under
   48 — the deliberate one-page-list density trade-off from v2.5.1),
   action bars 44→48dp, set-editor steppers 36→40dp; Summary "By
   section" lines 14sp→13sp with stripped margins (content 88dp ≤ the
   92dp card — the third line could clip before); Library detail meta
   11→12sp with normalized margins, Add button 46→48dp; Settings rest
   steppers 30×32→36×36 (Rest card 72→78dp), Data buttons 44→48dp
   (card 116→120dp; Settings compact total ≈522dp ≤ 546); Exercise
   Library group chips 40→42dp in 44dp rows; Rest Ring stroke width is
   now density-correct 10dp (was a raw 26px) and its dialog buttons
   take the app palette; Preview header title 17→18sp and the empty
   state no longer references the retired "Routines" name; the log-set
   sheet drops its duplicated subtitle line. Home, History list and
   Workout Detail reviewed — already consistent with the checklist
   (their pills now come from the shared `pillView`).

APK: 11,169,088 bytes. Signing cert unchanged: SHA-1
0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE.

## V3.3 (versionCode 19) — interaction fixes from the approved mockup

DB stays v4 (no schema change). Four items, all from Mahesh's on-device
v3.2 test:

1. **Skip never rests.** Card mode's skip used to flow through the same
   advance as Done, so skipping an exercise still popped the 60s
   between-exercises Rest Ring. Skip now advances via a dedicated
   `advanceAfterSkip()` (index walk + re-render only — the rest code is
   unreachable from it). The list view's long-press Skip was audited and
   was already rest-free (it only writes the status and re-renders).
   Rest triggers are unchanged for real work: the 30s ring after logging
   a set with more sets to go, and the 60s ring after completing an
   exercise via Done (or by logging its last set).
2. **Done confirmation — Main exercises only.** Swiping Done on a Main
   exercise with logged sets < planned sets no longer completes
   silently: the card snaps back and a Mist-styled dialog asks "Mark
   done?" — "You've logged X of Y sets for <name>", one line per set
   (logged sets show their weight×reps in success green, the rest read
   "not logged"), **Keep logging** (dismiss, nothing changes) and
   **Mark done** (the normal completion, advance/rest included). All
   sets logged (or none planned) -> Done completes instantly, no
   dialog. Zero logged sets still asks. **Warm-up and Cool-down/Stretch
   items are exempt** (the user's explicit scoping): their Done always
   completes instantly. The gate lives at the right-swipe commit, the
   only card path that can complete an exercise with sets missing —
   logging the final set in the logger completes directly, as before.
3. **Later button removed.** The card button row is now a single
   full-width "+ Log set" primary. Do later survives as the down-swipe
   (and the list view's long-press menu is untouched). The
   "Swipe <- Skip · -> Done · v Later" hint line under the row is gone
   (item 4 replaces it).
4. **On-card swipe cues.** Three small pills sit ON the photo band —
   the same band body-map exercises use, so Warmup, Main and Stretch
   cards all get them: left-center "← ✕ Skip" (dark translucent),
   right-center "Done ✓ →" (solid #15803D), bottom-center "↓ Later"
   (dark translucent). At rest they're at 90% opacity; while dragging,
   the cue matching the dominant direction goes fully opaque and scales
   to ~1.15x as displacement approaches the commit threshold while the
   other two fade back, and everything resets on release. The pills are
   click-through (the card's drag handling never sees them), and the
   name + last-time block is raised 32dp (photo scrim deepened to
   112dp) so the bottom cue never overlaps it. The existing drag
   direction stamps stay. Preview cards deliberately show NO cues —
   browsing promises no gestures.

APK: 11,171,584 bytes. Signing cert unchanged: SHA-1
0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE.

## V3.4 (versionCode 20) — Health Connect sync

Wish-list item 1, un-parked 2026-10-13. Finished workouts are written to
**Health Connect** on the device. No Google sign-in, OAuth, or Cloud
project is involved — Health Connect permissions are granted on-device,
the data stays on the phone, and apps the user allows (e.g. Google Fit's
own Health Connect sync) can read it from there. Direct Google Fit API
integration is not possible (those APIs closed to new apps in May 2024).

**Opt-in flow.** Settings gains a Health Connect card — toggle "Turn sync
ON/OFF" backed by the `hc_sync` pref, default OFF — with a status line
that reflects reality ("Not available on this device" / "Tap to grant
permission" / "On — workouts sync after you finish them" / "Off"), and a
"Sync past workouts" backfill button. Turning the toggle on without the
permission launches the Health Connect permission request for
ExerciseSessionRecord write; denial drops the toggle back to OFF with a
toast. Availability is checked via `HealthConnectClient.getSdkStatus`
(SDK_UNAVAILABLE disables the toggle; PROVIDER_UPDATE_REQUIRED shows an
update hint). Home also gets a slim "Sync to Health Connect" row: it
toasts if Health Connect is unavailable, asks before overriding an OFF
toggle ("Health Connect sync is off." -> Turn on / Not now), requests
permission if missing, and otherwise upserts all completed workouts and
toasts the count. On a fresh completion, the Summary screen attempts the
sync for that session and shows a subtle "Synced to Health Connect ✓"
line on success only; any failure is silent everywhere — sync can never
block or break a workout finish.

**What's written** (`HealthSync.kt`). One `ExerciseSessionRecord` per
completed session: real start time + elapsed duration (falling back to
the session date + duration), system zone offset, strength-training
type, title = the session's body-part label from `session_parts`
("Back + Biceps"; falls back to the routine name), notes = "Week N ·
Workout Tracker" plus each exercise's heaviest logged set. One
`ExerciseSegment` per exercise marked done with at least one logged set,
in session order: segment type mapped best-effort from the exercise
name (squat, deadlift, bench press, curls, rows, pull-up, lat pulldown,
leg press/curl/extension, plank, stretching, ...; generic weightlifting
otherwise — every constant verified present in the resolved AAR),
repetitions = total reps across the exercise's logged sets.
`clientRecordId = "wt-session-<sessionId>"` with manual-entry metadata
makes re-syncs upsert instead of duplicating.

**Two honest approximations.**
1. *Segment times*: per-exercise timestamps aren't tracked, so the
   session window is divided evenly across the segments in order.
2. *Segment weight*: `ExerciseSegment` in this API has **no weight
   field** (verified with javap), so per-exercise weight can't be a
   structured value; the heaviest logged set per exercise rides in the
   session notes instead (bodyweight sets contribute reps only, as
   specified).

**Dependency + minSdk notes.** The client is
`androidx.health.connect:connect-client`, but pinned to **1.1.0-alpha08**
rather than the 1.1.0 stable: the stable's AAR metadata requires
compileSdk 36 and AGP 8.9.1+, which this project's pinned toolchain
(compileSdk 34 / AGP 8.5.2) cannot satisfy; alpha08 is the newest
release built against compileSdk 34, and its full API surface used here
was verified against the AAR with javap (Kotlin metadata 1.8 — fine for
the 1.9.24 compiler). The library manifest declares **minSdk 26**, and
the manifest merger refuses lower, so the app's minSdk moves **24 -> 26**
(no override hack): Android 7.0/7.1 devices can no longer install the
app; Android 8.0+ is unaffected. targetSdk stays 34, DB stays v4.
`hc_sync` also joins the Drive backup prefs subset for consistency.

Housekeeping to fit the new Settings card in the one-page no-scroll
budget: Units + Beginner Mode now share one Preferences card (same text
and toggles, 48dp buttons). Compact Settings budget: title 32 +
Preferences 218 + Rest 68 + Health Connect 96 + Data 114 + margins ~
536dp <= ~546dp available. Home budget likewise re-run (~537dp) with
slightly tighter grid cells/hero. Verified: no ScrollView anywhere,
photos unchanged, signing cert unchanged
(0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE).

APK: 12,887,479 bytes (+1,715,895 vs v3.3 — the Health Connect client,
its protobuf runtime, and coroutines).

## V3.5 (versionCode 21) — config-driven Health Connect exercise types

V3.4 picked each Health Connect segment's type at runtime by guessing
from the exercise NAME (a keyword map). It worked — a dumbbell press
synced as a dumbbell press — but Mahesh asked for it to be exact:
pre-research the correct type for every exercise, store it in the
config, and sync from the stored value.

**Where the mapping lives.** Every entry in
`assets/data/<part>.json` (94 entries: exercises + warm-ups + stretches
across the 11 parts) now carries an **`hcType`** field — the NAME of a
Health Connect `EXERCISE_SEGMENT_TYPE_*` constant (e.g.
`EXERCISE_SEGMENT_TYPE_BENCH_PRESS`). The only edit made to the JSON
content is adding that field; every other field is byte-for-byte
unchanged (verified programmatically against the pre-change files).
The full research — the verified constant list, the per-exercise
mapping table with rationales, and the coverage numbers — lives in
**`research-health-connect-types.md`**.

**Ground truth.** The constant list (68 values, 0–67) was enumerated
with `javap -constants` on `ExerciseSegment` from the resolved
connect-client **1.1.0-alpha08** AAR — not from documentation. The API
has notable gaps (no push-up, dip, fly, shrug, calf-raise, or generic
row constant; the only row is `DUMBBELL_ROW`; presses map to
`BENCH_PRESS`), and those gaps were NOT papered over with
wrong-movement types.

**Coverage: 47 specific / 47 generic of 94.** Specific = a constant
naming the exercise's movement (ARM_CURL ×5, BENCH_PRESS ×3, SQUAT ×6,
DEADLIFT ×3, LEG_CURL ×3, HIP_THRUST ×3, LAT_PULL_DOWN, LEG_PRESS, …).
Generic = the most honest category where no movement constant exists:
WEIGHTLIFTING ×27 (rows, calf raises, push-ups, flies, dips, shrugs,
face pull, …), STRETCHING ×14 (every stretch + Cat-Cow),
OTHER_WORKOUT ×6 (scap pulls, dead bug, fire hydrant, leg swings, arm
circles, KB halo). The full generic list is named in the research doc.

**How sync resolves a type now** (`HealthSync.configSegmentType`):
session exercise → its origin `exercises` row → `sourceId` →
`JsonSeed.entryById` → the entry's `hcType` → an int via
`segmentTypeForCode`, a name→constant table in HealthSync generated
from the SAME verified enumeration (all 68 names resolve). If the
origin row is gone (e.g. a deduped canonical row), an exact catalog
name match is tried (the pattern PhotoLib already used). Fallbacks,
in order: the v3.4 name-keyword map, then generic WEIGHTLIFTING —
which is also what user-added exercises (no `sourceId`) get.

**No DB change (still v4), and upgrades are covered.** Because the
lookup reads the *bundled* JSON at sync time keyed by `sourceId`, an
existing install whose DB was seeded by an older version picks up the
exact types the first time it syncs after upgrading — no reseed, no
migration, no history loss. Fresh installs seed the same JSON
(including `hcType`, parsed into `JsonSeed.Entry`) as before.
Everything else about Health Connect is unchanged: record shape,
notes/weight handling, even segment-time split, clientRecordId
upserts, the generic-retry insert, and the Settings/Home/Summary
behavior.

Verified: build successful; every JSON parses and every `hcType`
value was script-checked against the verified constant set (in the
source files and inside the built APK); no ScrollView, no fitXY;
signing cert unchanged
(0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE).
APK: 12,892,027 bytes (+4,548 vs v3.4 — the JSON fields and the
resolver table). On-device sync behavior is unchanged in shape, but
as with v3.4 the final proof of any given exercise's label is
Mahesh's phone.

## V3.6 (versionCode 22) — the gym's Hoist machines join the catalog

Mahesh photographed the Hoist ROC-IT selectorized machines at his
24 Hour Fitness (15 unique machines) and asked for any missing ones
to be added. **Already supported (7, untouched):** Seated Leg Curl,
Chest Press Machine, Shoulder Press Machine, Lying Leg Curl (his
"Prone Leg Curl"), Hip Abduction Machine (his "Outer Thigh"), Leg
Extension, Leg Press. **Added (8)** as full catalog entries
(Machine, 3 × 10-12, `primary: false`, Beginner posture checks adapted
from each machine's own placard, YouTube *search* URLs — no invented
video IDs):

| New entry | File | hcType |
|---|---|---|
| Torso Rotation Machine | core.json | UPPER_TWIST |
| Mid Row Machine | back.json | WEIGHTLIFTING |
| Back Extension Machine | back.json | BACK_EXTENSION |
| Seated Dip Machine | triceps.json | WEIGHTLIFTING |
| Triceps Extension Machine | triceps.json | DOUBLE_ARM_TRICEPS_EXTENSION |
| Bicep Curl Machine | biceps.json | ARM_CURL |
| Hip Adduction Machine | glutes.json | WEIGHTLIFTING |
| Glute Kickback Machine | glutes.json | WEIGHTLIFTING |

hcTypes come from the same verified 68-constant table (see
`research-health-connect-types.md`, which gains a v3.6 section): the
generics are the API's gaps, not shortcuts — there is no dip or
machine-row constant, and the only row constant is DUMBBELL_ROW.
Catalog is now 102 entries; a scripted semantic diff against the
pre-change JSONs proves the 94 existing entries are byte-content
unchanged (pure additions).

**Photos** (same free-exercise-db pipeline, both frames, candidates
visually checked — full table in `research-exercise-images.md`):
matched for 6 — Mid Row (Leverage Iso Row), Seated Dip (Dip Machine,
shared with Assisted Dip (Chest): same machine, different emphasis),
Triceps Extension Machine, Bicep Curl Machine, Hip Adduction (Thigh
Adductor), Glute Kickback Machine (bodyweight Glute Kickback — the
same kneeling hip-extension movement as the Glute Master).
**Photo-less on purpose (2):** Torso Rotation Machine (the only
candidate is a standing exercise-ball twist whose first frame is a
neutral pose — it would teach the wrong setup for a seated machine)
and Back Extension Machine (no seated machine entry exists; the
bench photo belongs to Back Extension and shows a different
position). Both fall back to the body map, never a wrong movement.

**Additive seed — existing installs actually get the new machines.**
Installs seeded before v3.6 never re-run the v3 seed, so
`JsonSeed.applyV3IfNeeded` now falls through to a one-time v3.6 step
(gated by the new `v36CatalogApplied` pref) that calls the same
insert-only helper the v3 seed uses: any bundled entry whose
`sourceId` is absent from the exercises table is inserted — checked
per entry, so nothing is duplicated or overwritten, user-added rows
and history are untouched, and no DB schema change was needed (still
v4). On a fresh install the v3 seed has just inserted everything, so
the step finds nothing to add (verified by code inspection: the
helper's only write is `insertExercise` for ids not in the present
sourceId set; the step runs at most once per install thanks to the
pref gate, and the v3 catalog UI has no delete path for catalog rows,
so a missing sourceId can only mean a later catalog addition).

Verified: offline build successful; `aapt` versionCode 22 / "3.6";
the built APK's JSONs carry all 102 entries with the 8 new hcTypes,
and 174 exercise-image assets (162 + 12 new); no ScrollView, no
fitXY; signing cert unchanged
(0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE).
APK: 13,532,093 bytes (+640,066 vs v3.5 — the 12 new photo frames).

## V3.7 (versionCode 23) — hub flow, section transitions, slim card actions

Mahesh's gym-session redesign (approved mock: single-column
`v3.7-mockup.html`, 3 screens — hub, transition, card; Classic design
language + Mist palette, chosen "for now" over the Athletic/Soft/
Editorial/Data-forward variants). The assemble/select page is retired
from the start path: picking body parts creates the session
immediately with **ALL** exercises for the parts (warm-ups unioned and
deduped, every Main — primaries and non-primaries —, stretches;
equipment-cluster order Machine → Dumbbell → Kettlebell → Bodyweight,
JSON order inside a cluster) and opens the new scrolling **hub**.
AssembleActivity stays in the codebase, dormant; PreviewCards is
untouched.

### Fidelity checklist (written from the approved mock BEFORE coding;
each line marked after verification against the built code/layout
budgets — headless screenshots do not work in this environment, so
verification is code inspection + dp budgets, and anything only an
on-device render can prove is named as such)

Hub (mock screen 1):
- [x] Header: "Today's Workout" title, parts label, ticking elapsed
  timer (WorkoutTimer chip), "X of Y done" count.
- [x] 3-segment progress bar (segments sized by section size, 6dp
  tall, radius 3dp): Warm-Up fills amber #D97706, Workout blue
  #2563EB, Cool-Down green #15803D, each by its section's handled
  (done + passed) fraction.
- [x] Section bands, radius 10dp: Warm-Up #FEF3C7 / ink #92400E,
  Workout #DBEAFE / ink #1E40AF, Cool-Down #DCFCE7 / ink #166534,
  each "LABEL · N of M done".
- [x] Rows on Theme surface (#F7F9FB) with Theme line border, radius
  12dp: 26dp status circle (done = green fill, white ✓; passed =
  grey ✕ + name struck through + secondary ink; to-do = neutral ring
  with the section position number).
- [x] Body-part tag pill on Main rows only: #E0E7FF bg / #3730A3 ink.
- [x] Last-time line per row ("Last: 100×10 · 100×9 · 100×8" format)
  or "New exercise" (+ target sets×reps when known).
- [x] Pass button (white, bordered, grey ink) on to-do rows only —
  marks skipped by status write only; a pass NEVER starts a rest.
- [x] Drag handle per row; drag reorders WITHIN the section only and
  persists (sortOrder swaps, same mechanism as Do-later).
- [x] Next to-do exercise (first pending in order) gets the 2dp
  primary outline + "UP NEXT — TAP TO START" micro-label (10sp,
  primary, bold); tapping any row opens its exercise card.
- [x] Finish Workout button pinned at the bottom → the existing
  finish flow unchanged (Finish-early confirm, Summary celebrate,
  Health Connect + Drive call sites byte-identical semantics).
- [x] The hub list SCROLLS (RecyclerView) — the user's explicit
  request and the one approved exception to the no-scroll rule.

Transition (mock screen 2):
- [x] Fires only from the card advance path (Done/Skip/last set)
  when the LAST pending exercise of Warm-Up or Workout resolves and
  the next section still has pending exercises; never forced from
  the hub; Cool-Down completion goes to the normal finish path.
- [x] 74dp green check circle (white ✓), "<Section> complete" 23sp,
  counts line "D done · P passed".
- [x] UP-NEXT card: "UP NEXT — <SECTION> · N EXERCISES" kicker in
  #1E40AF, first pending exercise name + its last-time line.
- [x] Primary "Start <Section> Section" (opens that exercise's
  card); secondary "See All Exercises" (back to hub).
- [x] 3-dot section progress: done = green #15803D, up-next/current
  = blue #2563EB, upcoming = grey #CBD8E4.

Exercise card (mock screen 3):
- [x] Video = icon only: 38dp circular translucent-dark
  (rgba(15,23,42,.55)) button, white ▶, photo top-right, only when
  the exercise has a URL; the old full-width "Watch Form Video"
  button is GONE from the posture card.
- [x] "☰ All exercises" chip at the photo's top-left (same dark
  translucent pill, white text) → returns to the hub.
- [x] Section pill on the photo: white 92% bg, #1E40AF ink,
  "<SECTION> · n of m" for the current exercise.
- [x] One simple compact "+ Log set" button (~48dp tall, wrap width,
  right-aligned in the bottom action row) replacing the full-width
  Log set bar; the set-logger dialog it opens is unchanged; a short
  swipe-hint text sits at the row's left, as in the mock.
- [x] Scroller strip directly under the card: ALL session exercises
  in order, 66dp tiles (44dp image, radius 9dp), name beneath (2
  lines max), status dot (✓ green done / ✕ grey passed), current
  exercise ringed in primary; tap jumps to that exercise (same
  section switches in place, other section reopens the card there);
  strip refreshes on every render incl. on resume.
- [x] Navigation outcomes: Done → section complete ? transition :
  hub (after the between-exercise Rest Ring when one runs);
  Skip → next pending card (unchanged, never rests); Later
  (down-swipe) → advance (unchanged); set rests unchanged.
- [x] Rest chip: amber pill (#FEF3C7 bg, #F59E0B 1dp border, #92400E
  ink) labeled "REST m:ss" next to the elapsed timer in the top bar
  of BOTH card and hub while a rest is active, ticking each second;
  tap reopens the Rest Ring (same rest: +15s / Skip rest intact);
  chip disappears when the rest ends or is skipped. Dismissing the
  ring (back/outside) now hides the ring but keeps the rest counting
  in the background — that is what makes the chip reachable; Skip
  rest / GO still end it and fire the pending advance exactly once.

Standing corrections (still in force, re-verified):
- [x] Photos uncropped: 3:2 aspect-matched band, CENTER_CROP; strip
  thumbnails keep their own ratio (66×44) and never stretch;
  fitXY = 0 in the whole codebase.
- [x] Theme tokens equal the mock hexes (bg #EAF0F5, card #F7F9FB,
  line #D7E1EB, primary #2563EB, success #15803D, text #1F2937,
  secondary #64748B) — checked against Theme in Ui.kt.
- [x] Picker five states still distinguishable (untouched);
  Settings switches unclipped (untouched); skip never rests;
  Done-with-unlogged-sets confirmation for Main exercises only;
  posture content untouched; no gamification; icon unchanged;
  DB stays v4; scrolling ONLY in hub list + card strip.
- [x] All sizes in new layout code are dp (via dp()), never raw px.

Verification status: VERIFIED 2026-10-14 by code inspection + dp
budgets (headless screenshots do not work in this environment — the
same constraint as every prior version). On-device-only proof still
needed: the exact pixel look of the hub rows/bands against the mock,
ItemTouchHelper drag feel (handle-only drag, same-section swaps),
the strip's horizontal scroll + tap targets at 66dp, the rest chip's
1-second tick alignment next to the elapsed chip, and that hiding
the Rest Ring (back/outside) keeps the countdown running behind the
chip on a real device. One placement deviation from the mock,
forced by mechanics: the scroller strip sits directly under the
swipe card rather than inside it (inside, its drags would fight the
card's swipe handling); the mock's visual order — photo header,
strip, action row — is preserved at screen level, with the posture
card travelling inside the swipe card as before.

Implementation notes:
- New: WorkoutHubActivity (the sections screen was NOT reworked —
  it stays functional for the legacy WorkoutDetail "Open Sections"
  path; the hub is a new activity and the start/resume flow points
  at it). PickPartsActivity creates the session itself (the assemble
  buildLists logic moved there, minus the primary-only filter);
  AssembleActivity is untouched and dormant.
- Theme tokens adjusted to the mock hexes app-wide (they are shared
  tokens): stroke #D6DEE8 -> #D7E1EB, textPrimary #132033 ->
  #1F2937, textSecondary #5A6B80 -> #64748B. bg/surface/primary/
  success already matched. Every other token unchanged.
- RestTimer is now a countdown engine with observable state
  (isActive()/remainingSec() + listeners): the ring dialog is a view
  onto the active rest; back/outside dismiss hides the ring but the
  rest keeps counting (the chip is how you watch and reopen it);
  Skip rest and GO end it and fire the pending advance exactly once,
  as before. +15s, cue rotation, GO pop and haptic are unchanged.
  SessionActivity's Rest Ring calls keep the same signature and the
  same GO/Skip outcomes.
- RecyclerView 1.3.2 (paired with appcompat 1.7.0) mirrored into the
  offline localrepo via resolve.py. It is used ONLY by the hub list
  and the card strip: grep-verbatim — ScrollView: 0 hits app-wide;
  RecyclerView: WorkoutHubActivity.kt + CardSessionActivity.kt
  only; fitXY: 0; every ImageView scaleType is CENTER_CROP (6
  sites). DB stays v4; posture content, picker, Settings, Health
  Connect and Drive call sites untouched.

Verified: offline build successful; `aapt` versionCode 23 /
"3.7"; signing cert unchanged
(0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE).
APK: 13,753,360 bytes (+221,267 vs v3.6 — the RecyclerView AAR and
the new hub/transition/strip code).

## V3.8.1 (versionCode 24) — At Home card + the endless Movie Movements deck

Mahesh's extension beyond the gym: everyday living-room movements,
"extremely simple motion and extremely light" (approved mock:
`v3.8.1-home-mock.html`, screens 1–3; screen 4, the Home Workout mode,
is DEFERRED to the catalog build, as is the 108-entry home catalog —
this build ships only the deck's 18 movements). Tapping 🎬 Movie
Movements starts an ENDLESS deck: no schedule is ever shown, movements
are dealt one at a time in random order until he stops it, each with a
30–60s counter pinned at the top of the screen. Deck membership and
counters live in the catalog JSON (`home: true` + `counterSec`), per
the research file's "Revised Movie deck — 18" table (45 sources, 136
candidates reviewed).

### Fidelity checklist (written from the approved mock BEFORE coding;
each line marked after verification against the built code/layout
budgets — headless screenshots do not work in this environment, so
verification is code inspection + dp budgets, and anything only an
on-device render can prove is named as such)

Home — At Home card (mock screen 1):
- [x] Card below Start Workout: bg #FFF7E8, 1.5dp #F2C94C border,
  16dp radius; "🏠 At Home" title + "No gym, no equipment — everyday
  movements for the living room." (desc line omitted on compact
  screens only, to protect the no-scroll budget).
- [x] ONE row this build: 🎬 Movie Movements — "Endless: one movement
  at a time, random order, until you stop." Whole row tappable; the
  pill reads "Start", or "Resume" while a movie session is in
  progress (row line switches to "In progress — pick up where you
  left off."). The card is structured so the Home Workout row can be
  added inside it later.
- [x] No-scroll budget by construction: the body-part grid became the
  page's single FLEX region (rows share its height), so the fixed
  blocks — title 32 + hero ~65 + sync 52 + Start 54 + At Home card
  ~97 + margins ≈ 310dp compact — always fit the ~546dp floor and the
  card can never push the Health Connect sync row (kept above Start,
  its v3.4 position) or anything else off-screen; the grid compresses
  on short screens and breathes on tall ones (full card ~160dp there).
- [x] A movie session never leaks into the gym flow: Start Workout's
  in-progress check excludes routineName "Movie Movements" (gym
  resume dialog otherwise untouched).

Countdown (mock screen 2):
- [x] Big numeral in a 170dp primary circle, 3 → 2 → 1 at 800ms,
  "Get ready…" + the deck line with the REAL pool size interpolated
  ("18 movements…") — the mock's literal "10 movements" was its
  sample copy; the app never hardcodes a wrong count.
- [x] Back during the countdown discards the just-created empty
  session (no History trace).

Movement state (mock screen 3):
- [x] Fixed layout, NO scrolling. Top bar: "🎬 Movie Movements" left;
  right: ticking session elapsed (WorkoutTimer) + "✕ End" chip
  (#FEE2E2 bg / #991B1B text).
- [x] COUNTER BAND pinned under the top bar: bg #16324F, radius 14dp;
  30sp tabular countdown seconds; movement name + "· COUNTER"
  micro-label; ⏸/▶ pause toggle at right (freezes the counter only —
  the session elapsed keeps running); draining bar under the seconds,
  volt #A3E635, turning amber #F59E0B in the last 10s.
- [x] Photo band flexes to fit (CENTER_CROP, two-frame PhotoLooper
  where 2 frames exist; body-map fallback otherwise), "🏠 HOME ·
  <PART>" tag top-left, name + last-time line on the bottom scrim
  ("Last: 45 sec · ~15 reps · Mon"; "New movement" when none — the
  last-time lookup excludes the current session).
- [x] Target card: big rep guide ("~15 reps") + the entry's first 2
  cues as the guidance text.
- [x] Actions: "✓ Done early" (green #15803D, flex 1.4) + "Skip →"
  (56dp, white/outline).
- [x] Session line: "N done this session · the deck keeps dealing 🎲".
- [x] Deck chips row (wrapped LinearLayouts — NO RecyclerView):
  backwards-looking only; done = green tint + ✓, skipped = grey + ✕,
  current = primary + "· now"; capped at the most recent 12 + a
  "+N earlier" pill.

Standing corrections (still in force, re-verified):
- [x] Photos uncropped: CENTER_CROP everywhere; fitXY = 0 app-wide
  (grep-verbatim).
- [x] Fixed screens don't scroll: ScrollView = 0 app-wide
  (grep-verbatim); RecyclerView still confined to
  WorkoutHubActivity.kt + CardSessionActivity.kt — the deck uses
  none.
- [x] Copy minimal per the v2.6.1 audit: no instructional filler
  added anywhere; the one new toast is "Movie session discarded".
- [x] No gamification; icon unchanged; posture content untouched;
  Health Connect sync and Drive backup call sites unchanged (movie
  sessions reach them through the standard finish path).

### The deck — 18 movements (catalog order = deal pool order)

15 NEW entries + 3 existing entries flagged in place (no duplicates).
Every entry: equipment Bodyweight, sets 1, research rep guide,
4 Beginner posture checks + 3 couch-aware cues, YouTube SEARCH url
only, primary=false, verified hcType, `home: true`, `counterSec` as
listed. Photos bundled ONLY on a genuine free-exercise-db match.

| id | part | counterSec | photo |
|---|---|---|---|
| march-in-place | Quads | 60 | no |
| bodyweight-squat *(existing, warmups)* | Quads | 45 | yes (existing) |
| sit-to-stand | Quads | 45 | no |
| step-jacks | Quads | 45 | no |
| step-touch | Quads | 45 | no |
| wall-push-ups | Chest | 45 | no |
| incline-push-up | Chest | 40 | yes (2 frames) |
| standing-calf-raises | Calves | 45 | no |
| single-leg-calf-raise *(existing)* | Calves | 40 | yes (existing) |
| standing-knee-raises | Core | 45 | no |
| standing-cross-body-crunch | Core | 40 | no |
| standing-hip-abduction | Glutes | 40 | no |
| standing-hamstring-curl | Hamstrings | 45 | no — see below |
| good-morning | Hamstrings | 40 | no |
| shoulder-rolls | Shoulders | 30 | yes (2 frames) |
| arm-circles *(existing, warmups)* | Shoulders | 30 | yes (existing) |
| shoulder-shrugs | Traps | 30 | no |
| scapular-squeeze | Traps | 30 | no |

Photo correction vs the research file: its photo column suggested a
dataset match for standing-hamstring-curl, but visual inspection of
the "Single Leg Butt Kick" frames showed a knee-drive drill, not a
heel-to-butt curl — a wrong-movement photo, so the entry ships with
the body-map fallback instead. New photos this build: 4 frames
(incline-push-up ×2, shoulder-rolls ×2; 640px JPEG q80). Asset count:
178 exercise images (174 + 4).

### Implementation notes

- Data/seed: JsonSeed.Entry gains `home`/`counterSec`
  (optBoolean/optInt — existing parsing unaffected);
  `deckEntries()` = catalog entries with counterSec in 30–60, in
  catalog order. Existing installs receive the new entries via a
  one-time additive step gated by the `v381CatalogApplied` pref:
  insert-missing by sourceId only — never updates or deletes, user
  history untouched. Fresh installs get everything via the normal
  seed. Entries are full catalog citizens (they appear in Library
  under their parts, posture checks included). Catalog now 117
  entries. A semantic diff of the bundled JSONs against the pre-build
  files shows ONLY the 15 additions + `home`/`counterSec` fields on
  the 3 reused entries; all 18 hcTypes verified against the
  HealthSync constant table.
- Sessions: `createMovieSession()` makes an EMPTY session
  (routineName "Movie Movements"); each deal APPENDS one pending
  session_exercises row via `appendMovieExercise()` with exactly ONE
  bodyweight set. The session copy's type is forced to "Main" (two
  deck entries are Warmup-typed in the catalog) so Summary/History
  group the whole session together. `deleteSession()` now also
  deletes `session_parts` — a pre-existing orphan leak on the discard
  path, fixed here because the deck's 0-done End path relies on it.
- Dealing: shuffle-bag over the 18 ids; reshuffle when empty; across
  a reshuffle the first deal never repeats the immediately previous
  movement. Bag state is deliberately NOT persisted.
- Logging: Done early or counter expiry = the dealt row done + ONE
  set row (reps = first integer of the entry's rep guide, weight 0);
  the part's session_parts row is inserted on its FIRST completion in
  the session (week credit on completion, not on dealing). Skip =
  row marked skipped, next deal instantly, no set, no beat.
- Beat: completion (Done or expiry) shows a ~1.2s green ✓ state with
  a haptic pop — the Rest Ring GO feel — before the next deal.
- ✕ End: ≥1 done → the standard finish path (WorkoutTimer.finish →
  SummaryActivity celebrate → the existing automatic Health Connect
  attempt + DriveBackup.backupSilently call sites); HealthSync
  titles the session exactly "Movie Movements". 0 done → the session
  is deleted outright (no empty History entry) + "Movie session
  discarded" toast.
- Resume: Home's card detects the in-progress movie session; the
  deck re-deals its pending row with a FRESH counter (bag rebuilt,
  no-repeat applies from the last dealt movement). Leaving mid-deck
  via Back keeps the session in progress.
- The deck uses no RestTimer, no set-logger dialog, no swipe
  gestures.

Verified: offline build successful; `aapt` versionCode 24 /
"3.8.1"; minSdk 26; signing cert unchanged
(0A:1A:71:63:12:09:A8:1A:7B:69:F7:F0:C2:B6:2A:53:4A:A8:46:CE).
Scripted check against the built APK's bundled JSONs: 117 entries;
the deck is exactly the 18 ids above, all `home=true`, every
counterSec in 30–60. DB stays v4. APK: 14,247,920 bytes (+715,827 vs
v3.7 — the 15 catalog entries, 4 photos, MovieDeckActivity and the
Home card).

On-device-only proof still owed (cannot be shown headlessly): deck
visuals vs the mock, the pinned counter band, countdown feel, the
completion beat, chips packing, pause behavior, resume from the Home
card, and the At Home card on compact screens.
