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

