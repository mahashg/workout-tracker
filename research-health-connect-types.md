# Health Connect segment types — verified constants + per-exercise mapping

Companion to the v3.5 change: Health Connect segment types are no longer
guessed from exercise names at runtime. Each catalog entry in
`app/src/main/assets/data/<part>.json` carries a pre-researched `hcType`
(the constant NAME below), and `HealthSync` resolves the segment type
from that stored value (fallback: the v3.4 keyword map, then generic
`WEIGHTLIFTING`).

## Method

The constant list was enumerated with `javap -constants` on
`androidx.health.connect.client.records.ExerciseSegment` from the
**resolved connect-client 1.1.0-alpha08 AAR** (the version this project
pins — see README V3.4 for why alpha08). Nothing below is taken from
documentation or memory; if a constant is not in this table, it does not
exist in the API the app links against.

## Verified constants (68, values 0–67)

| Value | Constant |
|---:|---|
| 0 | EXERCISE_SEGMENT_TYPE_UNKNOWN |
| 1 | EXERCISE_SEGMENT_TYPE_ARM_CURL |
| 2 | EXERCISE_SEGMENT_TYPE_BACK_EXTENSION |
| 3 | EXERCISE_SEGMENT_TYPE_BALL_SLAM |
| 4 | EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS |
| 5 | EXERCISE_SEGMENT_TYPE_BENCH_PRESS |
| 6 | EXERCISE_SEGMENT_TYPE_BENCH_SIT_UP |
| 7 | EXERCISE_SEGMENT_TYPE_BIKING |
| 8 | EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY |
| 9 | EXERCISE_SEGMENT_TYPE_BURPEE |
| 10 | EXERCISE_SEGMENT_TYPE_CRUNCH |
| 11 | EXERCISE_SEGMENT_TYPE_DEADLIFT |
| 12 | EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION |
| 13 | EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_LEFT_ARM |
| 14 | EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_RIGHT_ARM |
| 15 | EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE |
| 16 | EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE |
| 17 | EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW |
| 18 | EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_LEFT_ARM |
| 19 | EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_RIGHT_ARM |
| 20 | EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM |
| 21 | EXERCISE_SEGMENT_TYPE_ELLIPTICAL |
| 22 | EXERCISE_SEGMENT_TYPE_FORWARD_TWIST |
| 23 | EXERCISE_SEGMENT_TYPE_FRONT_RAISE |
| 24 | EXERCISE_SEGMENT_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING |
| 25 | EXERCISE_SEGMENT_TYPE_HIP_THRUST |
| 26 | EXERCISE_SEGMENT_TYPE_HULA_HOOP |
| 27 | EXERCISE_SEGMENT_TYPE_JUMPING_JACK |
| 28 | EXERCISE_SEGMENT_TYPE_JUMP_ROPE |
| 29 | EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING |
| 30 | EXERCISE_SEGMENT_TYPE_LATERAL_RAISE |
| 31 | EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN |
| 32 | EXERCISE_SEGMENT_TYPE_LEG_CURL |
| 33 | EXERCISE_SEGMENT_TYPE_LEG_EXTENSION |
| 34 | EXERCISE_SEGMENT_TYPE_LEG_PRESS |
| 35 | EXERCISE_SEGMENT_TYPE_LEG_RAISE |
| 36 | EXERCISE_SEGMENT_TYPE_LUNGE |
| 37 | EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER |
| 38 | EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT |
| 39 | EXERCISE_SEGMENT_TYPE_PAUSE |
| 40 | EXERCISE_SEGMENT_TYPE_PILATES |
| 41 | EXERCISE_SEGMENT_TYPE_PLANK |
| 42 | EXERCISE_SEGMENT_TYPE_PULL_UP |
| 43 | EXERCISE_SEGMENT_TYPE_PUNCH |
| 44 | EXERCISE_SEGMENT_TYPE_REST |
| 45 | EXERCISE_SEGMENT_TYPE_ROWING_MACHINE |
| 46 | EXERCISE_SEGMENT_TYPE_RUNNING |
| 47 | EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL |
| 48 | EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS |
| 49 | EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION |
| 50 | EXERCISE_SEGMENT_TYPE_SIT_UP |
| 51 | EXERCISE_SEGMENT_TYPE_SQUAT |
| 52 | EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING |
| 53 | EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE |
| 54 | EXERCISE_SEGMENT_TYPE_STRETCHING |
| 55 | EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE |
| 56 | EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE |
| 57 | EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY |
| 58 | EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE |
| 59 | EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED |
| 60 | EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER |
| 61 | EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER |
| 62 | EXERCISE_SEGMENT_TYPE_SWIMMING_POOL |
| 63 | EXERCISE_SEGMENT_TYPE_UPPER_TWIST |
| 64 | EXERCISE_SEGMENT_TYPE_WALKING |
| 65 | EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING |
| 66 | EXERCISE_SEGMENT_TYPE_WHEELCHAIR |
| 67 | EXERCISE_SEGMENT_TYPE_YOGA |

Notable gaps in the API (they drive several generic picks below): there
is **no** push-up, dip, fly, shrug, calf-raise, face-pull, step-up,
kickback (glute), or pull-apart constant, and the only row constant is
`DUMBBELL_ROW`. There is also no dumbbell bench-press constant — presses
map to `BENCH_PRESS`.

## V3.6 additions (8 gym machines, 2026-10-13/14)

Same method — every value below is from the verified 68-constant table;
no new constants. Coverage over the grown catalog: 102 entries.

| Entry | File | hcType | Rationale |
|---|---|---|---|
| torso-rotation-machine | core.json | UPPER_TWIST | Seated trunk rotation — the same call as russian-twist (value 63, verified present). |
| mid-row-machine | back.json | WEIGHTLIFTING | Chest-supported machine row; the only row constant is DUMBBELL_ROW — a machine row is not that (the seated-cable-row precedent). |
| back-extension-machine | back.json | BACK_EXTENSION | The constant names this exact movement (and the bodyweight back-extension entry already uses it). |
| seated-dip-machine | triceps.json | WEIGHTLIFTING | No dip constant exists (the bench-dip / assisted-dip-chest precedent). |
| triceps-extension-machine | triceps.json | DOUBLE_ARM_TRICEPS_EXTENSION | The implement-neutral two-arm extension — both arms press together (the cable-pushdown / skull-crusher precedent; the DUMBBELL_* variants name the wrong implement). |
| bicep-curl-machine | biceps.json | ARM_CURL | No BICEP_CURL constant exists in the table; ARM_CURL is the generic curl constant every other two-arm/machine curl uses (machine-preacher-curl precedent). |
| hip-adduction-machine | glutes.json | WEIGHTLIFTING | No adduction constant exists — mirrors hip-abduction-machine exactly. |
| glute-kickback-machine | glutes.json | WEIGHTLIFTING | No kickback constant exists — mirrors cable-glute-kickback exactly. |

## Coverage

94 catalog entries mapped: **47 specific** (a constant naming the
exercise's movement, allowing equipment/position variants of that same
named movement) and **47 generic**, where no honest specific constant
exists:

- **WEIGHTLIFTING (27)** — resistance moves with no movement constant:
  seated-cable-row, single-arm-kb-row, inverted-row,
  straight-arm-pulldown, standing-calf-raise-machine,
  seated-calf-raise, single-leg-calf-raise, push-up-plus,
  light-push-ups, push-ups, cable-fly, db-chest-fly, assisted-dip-chest,
  cable-glute-kickback, hip-abduction-machine, cable-pull-through,
  kb-step-up, scap-push-up, reverse-pec-deck, pike-push-up, face-pull,
  db-shrug, cable-shrug, farmers-carry, kb-high-pull-light,
  diamond-push-up, bench-dip.
- **STRETCHING (14)** — every Stretch-section entry plus Cat-Cow:
  cat-cow, lat-stretch, childs-pose, cobra-pose, doorway-bicep-stretch,
  standing-calf-stretch, doorway-chest-stretch, pigeon-pose,
  hamstring-stretch, standing-quadriceps-stretch, couch-stretch,
  cross-body-shoulder-stretch, neck-trapezius-stretch,
  overhead-triceps-stretch.
- **OTHER_WORKOUT (6)** — bodyweight warm-up/activation drills with no
  movement constant: scapula-pulls, dead-bug, fire-hydrant, leg-swings,
  arm-circles, kettlebell-halo.

## Per-exercise mapping

`hcType` values as stored in the JSON. Rationales are given for every
generic pick and for the non-obvious specific ones; unmarked specific
picks are the literal movement the constant names.

### back.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| rowing-machine-warm-up | Warmup | ROWING_MACHINE | |
| cat-cow | Warmup | STRETCHING | Spinal mobility stretch; no cat-cow constant. |
| scapula-pulls | Warmup | OTHER_WORKOUT | Activation drill (scapular depression from a hang) — not a pull-up (no elbow bend), not a stretch. |
| lat-pulldown | Main | LAT_PULL_DOWN | |
| seated-cable-row | Main | WEIGHTLIFTING | The only row constant is DUMBBELL_ROW; a cable row is not a dumbbell row. |
| single-arm-kb-row | Main | WEIGHTLIFTING | Same as above — kettlebell, not dumbbell. |
| inverted-row | Main | WEIGHTLIFTING | Bodyweight row; no generic row constant exists. |
| chest-supported-db-row | Main | DUMBBELL_ROW | It is literally a dumbbell row. |
| straight-arm-pulldown | Main | WEIGHTLIFTING | Shoulder-extension isolation movement, distinct from the lat pulldown's vertical pull. |
| back-extension | Main | BACK_EXTENSION | |
| lat-stretch | Stretch | STRETCHING | |
| childs-pose | Stretch | STRETCHING | Held stretch (YOGA exists, but in a strength session the honest category is the stretch). |
| cobra-pose | Stretch | STRETCHING | Same as child's pose. |

### biceps.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| db-bicep-curl | Main | ARM_CURL | Two-arm curl; the DUMBBELL_CURL_LEFT/RIGHT_ARM constants are single-arm variants. |
| db-hammer-curl | Main | ARM_CURL | Hammer grip is still an arm curl; no grip-specific constant. |
| cable-bicep-curl | Main | ARM_CURL | |
| incline-db-curl | Main | ARM_CURL | Bench angle variant of the same named movement. |
| machine-preacher-curl | Main | ARM_CURL | |
| doorway-bicep-stretch | Stretch | STRETCHING | |

### calves.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| standing-calf-raise-machine | Main | WEIGHTLIFTING | No calf-raise constant exists (LEG_RAISE is a different movement — supine/hanging leg raises). |
| seated-calf-raise | Main | WEIGHTLIFTING | Same gap. |
| single-leg-calf-raise | Main | WEIGHTLIFTING | Same gap. |
| standing-calf-stretch | Stretch | STRETCHING | |

### chest.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| push-up-plus | Warmup | WEIGHTLIFTING | Push-up variant; the API has no push-up constant at all. |
| light-push-ups | Warmup | WEIGHTLIFTING | Same gap. |
| chest-press-machine | Main | BENCH_PRESS | Horizontal press = the bench-press movement pattern; no machine-press constant. |
| incline-db-press | Main | BENCH_PRESS | Incline variant of the named movement; no incline-specific constant (and no dumbbell bench-press constant). |
| kb-floor-press | Main | BENCH_PRESS | Floor press is a supine bench-press variant. |
| push-ups | Main | WEIGHTLIFTING | No push-up constant exists. |
| cable-fly | Main | WEIGHTLIFTING | No fly constant exists. |
| db-chest-fly | Main | WEIGHTLIFTING | Same gap. |
| assisted-dip-chest | Main | WEIGHTLIFTING | No dip constant exists. |
| doorway-chest-stretch | Stretch | STRETCHING | |

### core.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| dead-bug | Main | OTHER_WORKOUT | Bodyweight core-stability drill; it is neither a crunch nor a sit-up. |
| plank | Main | PLANK | |
| cable-crunch | Main | CRUNCH | |
| russian-twist | Main | UPPER_TWIST | Seated rotation of the upper body; FORWARD_TWIST is the forward-hinged variant. Judgment call. |
| mountain-climbers | Main | MOUNTAIN_CLIMBER | |

### glutes.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| glute-bridge-activation | Warmup | HIP_THRUST | A glute bridge is the unloaded bodyweight hip-thrust movement. |
| kb-swing-light | Main | KETTLEBELL_SWING | |
| cable-glute-kickback | Main | WEIGHTLIFTING | No glute-kickback constant (the SINGLE_ARM_TRICEPS_EXTENSION the old keyword map could suggest is a triceps movement — wrong here). |
| hip-thrust-machine | Main | HIP_THRUST | |
| glute-bridge | Main | HIP_THRUST | Same as the activation variant. |
| hip-abduction-machine | Main | WEIGHTLIFTING | No abduction constant exists. |
| kb-sumo-squat | Main | SQUAT | Stance variant of the named movement. |
| fire-hydrant | Main | OTHER_WORKOUT | Bodyweight hip drill; no constant names it. |
| pigeon-pose | Stretch | STRETCHING | Held stretch (see child's pose note). |

### hamstrings.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| kettlebell-deadlift-light | Warmup | DEADLIFT | |
| leg-curl-machine | Main | LEG_CURL | |
| kb-romanian-deadlift | Main | DEADLIFT | Romanian = hip-hinge variant of the named movement. |
| db-staggered-romanian-deadlift | Main | DEADLIFT | Same, staggered stance. |
| seated-leg-curl | Main | LEG_CURL | |
| lying-leg-curl | Main | LEG_CURL | |
| cable-pull-through | Main | WEIGHTLIFTING | A cable hip hinge, but not a deadlift — the bar/bell never leaves the floor pattern the constant names. |
| hamstring-stretch | Stretch | STRETCHING | |

### quads.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| stationary-bike | Warmup | BIKING_STATIONARY | |
| incline-walk | Warmup | WALKING | It is walking (RUNNING_TREADMILL would claim running). |
| bodyweight-squat | Warmup | SQUAT | |
| leg-swings | Warmup | OTHER_WORKOUT | Dynamic mobility drill, not a held stretch. |
| leg-press | Main | LEG_PRESS | |
| kb-goblet-squat | Main | SQUAT | |
| db-walking-lunge | Main | LUNGE | |
| leg-extension | Main | LEG_EXTENSION | |
| kb-step-up | Main | WEIGHTLIFTING | No step-up constant; STAIR_CLIMBING names a continuous cardio activity, not a loaded box step-up. |
| wall-sit | Main | SQUAT | Isometric squat hold — same position the constant names; no wall-sit constant. |
| hack-squat-machine | Main | SQUAT | Machine variant of the named movement. |
| bulgarian-split-squat | Main | SQUAT | A static split-stance squat (LUNGE implies stepping). Judgment call. |
| stationary-db-lunge | Main | LUNGE | |
| standing-quadriceps-stretch | Stretch | STRETCHING | |
| couch-stretch | Stretch | STRETCHING | |

### shoulders.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| arm-circles | Warmup | OTHER_WORKOUT | Mobility drill; no constant. |
| kettlebell-halo | Warmup | OTHER_WORKOUT | Shoulder-mobility drill circling the head; not a press. |
| scap-push-up | Warmup | WEIGHTLIFTING | Push-up family (see push-ups — no push-up constant). |
| shoulder-press-machine | Main | SHOULDER_PRESS | |
| single-arm-kb-press | Main | SHOULDER_PRESS | |
| db-lateral-raise | Main | DUMBBELL_LATERAL_RAISE | The exact constant exists for this one. |
| reverse-pec-deck | Main | WEIGHTLIFTING | Rear-delt fly; LATERAL_RAISE names a different (side) raise. |
| pike-push-up | Main | WEIGHTLIFTING | Push-up family. |
| db-shoulder-press | Main | SHOULDER_PRESS | BARBELL_SHOULDER_PRESS is barbell-specific; the dumbbell version takes the general press constant. |
| cable-lateral-raise | Main | LATERAL_RAISE | The implement-neutral lateral-raise constant (DUMBBELL_LATERAL_RAISE would name the wrong implement). |
| face-pull | Main | WEIGHTLIFTING | No face-pull constant exists. |
| cross-body-shoulder-stretch | Stretch | STRETCHING | |

### traps.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| db-shrug | Main | WEIGHTLIFTING | No shrug constant exists. |
| cable-shrug | Main | WEIGHTLIFTING | Same gap. |
| farmers-carry | Main | WEIGHTLIFTING | A loaded carry trained as a grip/trap strength hold; WALKING was considered and rejected — locomotion is the vehicle, not the exercise. |
| kb-high-pull-light | Main | WEIGHTLIFTING | No high-pull constant (PULL_UP is the bodyweight vertical pull — a different movement). |
| neck-trapezius-stretch | Stretch | STRETCHING | |

### triceps.json
| Entry | Section | hcType | Rationale |
|---|---|---|---|
| cable-triceps-pushdown | Main | DOUBLE_ARM_TRICEPS_EXTENSION | A bar/rope pushdown extends both arms together. |
| db-overhead-triceps-extension | Main | DUMBBELL_TRICEPS_EXTENSION_TWO_ARM | One dumbbell held in both hands overhead — the exact case this constant names. |
| diamond-push-up | Main | WEIGHTLIFTING | Push-up family (no push-up constant). |
| bench-dip | Main | WEIGHTLIFTING | No dip constant exists. |
| db-triceps-kickback | Main | SINGLE_ARM_TRICEPS_EXTENSION | Kickbacks are performed one arm at a time (other arm braced). |
| db-skull-crusher | Main | DOUBLE_ARM_TRICEPS_EXTENSION | Lying two-arm extension; the implement-neutral two-arm constant fits better than the overhead dumbbell one. |
| overhead-triceps-stretch | Stretch | STRETCHING | |
