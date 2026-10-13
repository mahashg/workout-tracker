# Exercise Images — Mapping

Source: [yuhonas/free-exercise-db](https://github.com/yuhonas/free-exercise-db) — public-domain dataset, **Unlicense** (free to use, copy, and redistribute, including commercially). Each photo is the dataset entry's first image (`images[0]`), downloaded 2026-10-13, resized to max width 640px and re-encoded as JPEG quality 80 with Pillow (PIL 10.2.0).

Files live in `app/src/main/assets/exercise-images/<our-id>.jpg`. Matching was variant-tolerant (grip/equipment variants of the same movement accepted) but never forced across movements — exercises with no genuine match are marked `unmatched` and have no file, so the app should fall back to its body-map/no-image display for them.

Verification: 20 downloaded images were spot-viewed (see notes). 1 was dropped after checking (Cross-Body Shoulder Stretch — frame 0 of "Shoulder Stretch" is just a neutral standing pose) and 2 were re-matched after checking (Couch Stretch, Standing Quadriceps Stretch).

## Main exercises — Seed.kt (34)

| Our ID | Our exercise | Dataset exercise | Image path (images[0]) | Status |
|---|---|---|---|---|
| shoulder-press-machine | Shoulder Press Machine | Machine Shoulder (Military) Press | Machine_Shoulder_Military_Press/0.jpg | matched |
| single-arm-kb-press | Single-Arm KB Press | One-Arm Kettlebell Push Press | One-Arm_Kettlebell_Push_Press/0.jpg | matched |
| db-lateral-raise | DB Lateral Raise | Side Lateral Raise | Side_Lateral_Raise/0.jpg | matched |
| reverse-pec-deck | Reverse Pec Deck | Reverse Machine Flyes | Reverse_Machine_Flyes/0.jpg | matched |
| db-shrug | DB Shrug | Dumbbell Shrug | Dumbbell_Shrug/0.jpg | matched |
| pike-push-up | Pike Push-up | — | — | unmatched |
| chest-press-machine | Chest Press Machine | Leverage Chest Press | Leverage_Chest_Press/0.jpg | matched |
| incline-db-press | Incline DB Press | Incline Dumbbell Press | Incline_Dumbbell_Press/0.jpg | matched |
| kb-floor-press | KB Floor Press | One-Arm Kettlebell Floor Press | One-Arm_Kettlebell_Floor_Press/0.jpg | matched |
| push-ups | Push-ups | Pushups | Pushups/0.jpg | matched |
| cable-triceps-pushdown | Cable Triceps Pushdown | Triceps Pushdown | Triceps_Pushdown/0.jpg | matched |
| db-overhead-triceps-extension | DB Overhead Triceps Extension | Standing Dumbbell Triceps Extension | Standing_Dumbbell_Triceps_Extension/0.jpg | matched |
| diamond-push-up | Diamond Push-up | Push-Ups - Close Triceps Position | Push-Ups_-_Close_Triceps_Position/0.jpg | matched |
| lat-pulldown | Lat Pulldown | Wide-Grip Lat Pulldown | Wide-Grip_Lat_Pulldown/0.jpg | matched |
| seated-cable-row | Seated Cable Row | Seated Cable Rows | Seated_Cable_Rows/0.jpg | matched |
| single-arm-kb-row | Single-Arm KB Row | One-Arm Kettlebell Row | One-Arm_Kettlebell_Row/0.jpg | matched |
| db-bicep-curl | DB Bicep Curl | Dumbbell Bicep Curl | Dumbbell_Bicep_Curl/0.jpg | matched |
| db-hammer-curl | DB Hammer Curl | Hammer Curls | Hammer_Curls/0.jpg | matched |
| inverted-row | Inverted Row | Inverted Row | Inverted_Row/0.jpg | matched |
| leg-press | Leg Press | Leg Press | Leg_Press/0.jpg | matched |
| kb-goblet-squat | KB Goblet Squat | Goblet Squat | Goblet_Squat/0.jpg | matched |
| db-walking-lunge | DB Walking Lunge | Dumbbell Lunges | Dumbbell_Lunges/0.jpg | matched |
| leg-extension | Leg Extension | Leg Extensions | Leg_Extensions/0.jpg | matched |
| kb-step-up | KB Step-Up | Dumbbell Step Ups | Dumbbell_Step_Ups/0.jpg | matched |
| wall-sit | Wall Sit | — | — | unmatched |
| leg-curl-machine | Leg Curl Machine | Seated Leg Curl | Seated_Leg_Curl/0.jpg | matched |
| kb-romanian-deadlift | KB Romanian Deadlift | Romanian Deadlift | Romanian_Deadlift/0.jpg | matched |
| kb-swing-light | KB Swing (light) | One-Arm Kettlebell Swings | One-Arm_Kettlebell_Swings/0.jpg | matched |
| db-staggered-romanian-deadlift | DB Staggered Romanian Deadlift | Stiff-Legged Dumbbell Deadlift | Stiff-Legged_Dumbbell_Deadlift/0.jpg | matched |
| cable-glute-kickback | Cable Glute Kickback | One-Legged Cable Kickback | One-Legged_Cable_Kickback/0.jpg | matched |
| hip-thrust-machine | Hip Thrust Machine | Barbell Hip Thrust | Barbell_Hip_Thrust/0.jpg | matched |
| glute-bridge | Glute Bridge | Butt Lift (Bridge) | Butt_Lift_Bridge/0.jpg | matched |
| dead-bug | Dead Bug | Dead Bug | Dead_Bug/0.jpg | matched |
| plank | Plank | Plank | Plank/0.jpg | matched |

## Main exercises — Library.kt (33)

| Our ID | Our exercise | Dataset exercise | Image path (images[0]) | Status |
|---|---|---|---|---|
| db-shoulder-press | DB Shoulder Press | Dumbbell Shoulder Press | Dumbbell_Shoulder_Press/0.jpg | matched |
| cable-lateral-raise | Cable Lateral Raise | Cable Seated Lateral Raise | Cable_Seated_Lateral_Raise/0.jpg | matched |
| face-pull | Face Pull | Face Pull | Face_Pull/0.jpg | matched |
| cable-shrug | Cable Shrug | Cable Shrugs | Cable_Shrugs/0.jpg | matched |
| farmers-carry | Farmer's Carry | Farmer's Walk | Farmers_Walk/0.jpg | matched |
| kb-high-pull-light | KB High Pull (Light) | Kettlebell Sumo High Pull | Kettlebell_Sumo_High_Pull/0.jpg | matched |
| cable-fly | Cable Fly | Cable Crossover | Cable_Crossover/0.jpg | matched |
| db-chest-fly | DB Chest Fly | Dumbbell Flyes | Dumbbell_Flyes/0.jpg | matched |
| assisted-dip-chest | Assisted Dip (Chest) | Dip Machine | Dip_Machine/0.jpg | matched |
| bench-dip | Bench Dip | Bench Dips | Bench_Dips/0.jpg | matched |
| db-triceps-kickback | DB Triceps Kickback | Tricep Dumbbell Kickback | Tricep_Dumbbell_Kickback/0.jpg | matched |
| db-skull-crusher | DB Skull Crusher | Lying Dumbbell Tricep Extension | Lying_Dumbbell_Tricep_Extension/0.jpg | matched |
| chest-supported-db-row | Chest-Supported DB Row | Dumbbell Incline Row | Dumbbell_Incline_Row/0.jpg | matched |
| straight-arm-pulldown | Straight-Arm Pulldown | Straight-Arm Pulldown | Straight-Arm_Pulldown/0.jpg | matched |
| back-extension | Back Extension | Hyperextensions (Back Extensions) | Hyperextensions_Back_Extensions/0.jpg | matched |
| cable-bicep-curl | Cable Bicep Curl | Standing Biceps Cable Curl | Standing_Biceps_Cable_Curl/0.jpg | matched |
| incline-db-curl | Incline DB Curl | Incline Dumbbell Curl | Incline_Dumbbell_Curl/0.jpg | matched |
| machine-preacher-curl | Machine Preacher Curl | Machine Preacher Curls | Machine_Preacher_Curls/0.jpg | matched |
| hack-squat-machine | Hack Squat Machine | Hack Squat | Hack_Squat/0.jpg | matched |
| bulgarian-split-squat | Bulgarian Split Squat | Split Squat with Dumbbells | Split_Squat_with_Dumbbells/0.jpg | matched |
| stationary-db-lunge | Stationary DB Lunge | Dumbbell Lunges | Dumbbell_Lunges/0.jpg | matched |
| seated-leg-curl | Seated Leg Curl | Seated Leg Curl | Seated_Leg_Curl/0.jpg | matched |
| lying-leg-curl | Lying Leg Curl | Lying Leg Curls | Lying_Leg_Curls/0.jpg | matched |
| cable-pull-through | Cable Pull-Through | Pull Through | Pull_Through/0.jpg | matched |
| hip-abduction-machine | Hip Abduction Machine | Thigh Abductor | Thigh_Abductor/0.jpg | matched |
| kb-sumo-squat | KB Sumo Squat | Plie Dumbbell Squat | Plie_Dumbbell_Squat/0.jpg | matched |
| fire-hydrant | Fire Hydrant | — | — | unmatched |
| standing-calf-raise-machine | Standing Calf Raise Machine | Standing Calf Raises | Standing_Calf_Raises/0.jpg | matched |
| seated-calf-raise | Seated Calf Raise | Seated Calf Raise | Seated_Calf_Raise/0.jpg | matched |
| single-leg-calf-raise | Single-Leg Calf Raise | Calf Raise On A Dumbbell | Calf_Raise_On_A_Dumbbell/0.jpg | matched |
| cable-crunch | Cable Crunch | Cable Crunch | Cable_Crunch/0.jpg | matched |
| russian-twist | Russian Twist | Russian Twist | Russian_Twist/0.jpg | matched |
| mountain-climbers | Mountain Climbers | Mountain Climbers | Mountain_Climbers/0.jpg | matched |

## Warmups & stretches — Seed.kt (opportunistic, 27)

| Our ID | Our exercise | Dataset exercise | Image path (images[0]) | Status |
|---|---|---|---|---|
| stationary-bike | Stationary Bike | Bicycling, Stationary | Bicycling_Stationary/0.jpg | matched |
| arm-circles | Arm Circles | Arm Circles | Arm_Circles/0.jpg | matched |
| kettlebell-halo | Kettlebell Halo | — | — | unmatched |
| scap-push-up | Scap Push-up | — | — | unmatched |
| incline-walk | Incline Walk | Walking, Treadmill | Walking_Treadmill/0.jpg | matched |
| push-up-plus | Push-Up Plus | — | — | unmatched |
| light-push-ups | Light Push-ups | Pushups | Pushups/0.jpg | matched |
| rowing-machine-warm-up | Rowing Machine Warm-up | Rowing, Stationary | Rowing_Stationary/0.jpg | matched |
| cat-cow | Cat-Cow | Cat Stretch | Cat_Stretch/0.jpg | matched |
| scapula-pulls | Scapula Pulls | — | — | unmatched |
| bodyweight-squat | Bodyweight Squat | Bodyweight Squat | Bodyweight_Squat/0.jpg | matched |
| leg-swings | Leg Swings | — | — | unmatched |
| glute-bridge-activation | Glute Bridge Activation | Butt Lift (Bridge) | Butt_Lift_Bridge/0.jpg | matched |
| kettlebell-deadlift-light | Kettlebell Deadlift (Light) | — | — | unmatched |
| cross-body-shoulder-stretch | Cross-Body Shoulder Stretch | Shoulder Stretch | Shoulder_Stretch/0.jpg | dropped-after-check |
| neck-trapezius-stretch | Neck Trapezius Stretch | Side Neck Stretch | Side_Neck_Stretch/0.jpg | matched |
| doorway-chest-stretch | Doorway Chest Stretch | Chest And Front Of Shoulder Stretch | Chest_And_Front_Of_Shoulder_Stretch/0.jpg | matched |
| overhead-triceps-stretch | Overhead Triceps Stretch | Triceps Stretch | Triceps_Stretch/0.jpg | matched |
| lat-stretch | Lat Stretch | — | — | unmatched |
| doorway-bicep-stretch | Doorway Bicep Stretch | Standing Biceps Stretch | Standing_Biceps_Stretch/0.jpg | matched |
| childs-pose | Child's Pose | Child's Pose | Childs_Pose/0.jpg | matched |
| standing-quadriceps-stretch | Standing Quadriceps Stretch | Standing Elevated Quad Stretch | Standing_Elevated_Quad_Stretch/0.jpg | matched |
| standing-calf-stretch | Standing Calf Stretch | Standing Gastrocnemius Calf Stretch | Standing_Gastrocnemius_Calf_Stretch/0.jpg | matched |
| couch-stretch | Couch Stretch | Kneeling Hip Flexor | Kneeling_Hip_Flexor/0.jpg | matched |
| hamstring-stretch | Hamstring Stretch | Hamstring Stretch | Hamstring_Stretch/0.jpg | matched |
| pigeon-pose | Pigeon Pose | — | — | unmatched |
| cobra-pose | Cobra Pose | — | — | unmatched |

## Notes on specific matches

- **KB Step-Up → Dumbbell Step Ups**, **KB Romanian Deadlift → Romanian Deadlift** (barbell), **KB Sumo Squat → Plie Dumbbell Squat**, **Hip Thrust Machine → Barbell Hip Thrust**, **Single-Arm KB Press → One-Arm Kettlebell Push Press**, **KB Swing (light) → One-Arm Kettlebell Swings**: no exact kettlebell/machine entry exists in the dataset; these are the same movement with a different equipment/grip variant, per the variant-tolerant rule.
- **Diamond Push-up → Push-Ups - Close Triceps Position**: the close/diamond hand-position push-up entry.
- **DB Skull Crusher → Lying Dumbbell Tricep Extension**: the lying dumbbell triceps extension *is* the dumbbell skull-crusher movement.
- **Bulgarian Split Squat → Split Squat with Dumbbells**: dataset instructions confirm rear-foot-elevated stance (the plain "Split Squats" entry is a stretching-category entry and was rejected).
- **Hip Abduction Machine → Thigh Abductor**: the dataset's name for the abduction machine.
- **Couch Stretch → Kneeling Hip Flexor** (re-matched after visual check): the first candidate, "Intermediate Hip Flexor and Quad Stretch", turned out to depict a seated band hamstring stretch and was rejected; the kneeling hip-flexor stretch is the same target (hip flexor/quad) as a couch stretch.
- **Standing Quadriceps Stretch → Standing Elevated Quad Stretch** (re-matched after visual check): the plain "Quad Stretch" frame depicts a lying strap-assisted stretch, not standing.
- **Cross-Body Shoulder Stretch → dropped-after-check**: "Shoulder Stretch" frame 0 shows only a neutral standing pose, not the stretch; no other genuine cross-body stretch entry exists.
- **Unmatched mains**: Pike Push-up (only "Hanging Pike", a leg raise), Wall Sit (no wall-sit entry), Fire Hydrant (no entry at all).
- **Unmatched warmups/stretches**: Kettlebell Halo, Scap Push-up, Push-Up Plus, Scapula Pulls, Leg Swings, Kettlebell Deadlift (Light) (only a one-legged KB deadlift exists), Lat Stretch (only a partner-assisted "Overhead Lat"), Pigeon Pose, Cobra Pose.
