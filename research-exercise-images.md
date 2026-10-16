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

## Second frames (v3.1 animation)

Each dataset entry ships a second photo (`images[1]` — the other position of the movement). On 2026-10-13 the second frame of every matched entry above was downloaded from the same URL pattern (`.../exercises/<Entry>/1.jpg`), resized to max width 640px and re-encoded as JPEG quality 80 with Pillow, and bundled as `app/src/main/assets/exercise-images/<our-id>-2.jpg`. Coverage: **81 of 81** bundled photos have a second frame (every matched entry had one). The app crossfade-loops the two frames in card mode and in the Library detail (hold ~800ms, fade ~350ms); exercises without a photo still use the body-map display. Like the first frames, the `-2.jpg` binaries are NOT pushed to GitHub.

## Gym machines — v3.6 (8 added from the user's gym photos)

On 2026-10-13 the user photographed the Hoist ROC-IT selectorized machines at his 24 Hour Fitness. Audit against the catalog found 7 already supported (Seated Leg Curl, Chest Press Machine, Shoulder Press Machine, Lying Leg Curl = his "Prone Leg Curl", Hip Abduction Machine = his "Outer Thigh", Leg Extension, Leg Press). The 8 missing ones were added as catalog entries; photos were matched from the same dataset on 2026-10-14 with the same pipeline (max width 640px, JPEG q80, both frames bundled as `<our-id>.jpg` + `<our-id>-2.jpg`). Each candidate photo was visually checked before acceptance.

| Our ID | Our exercise | Dataset exercise | Image path (images[0]) | Status |
|---|---|---|---|---|
| torso-rotation-machine | Torso Rotation Machine | — | — | unmatched |
| mid-row-machine | Mid Row Machine | Leverage Iso Row | Leverage_Iso_Row/0.jpg | matched |
| back-extension-machine | Back Extension Machine | — | — | unmatched |
| seated-dip-machine | Seated Dip Machine | Dip Machine | Dip_Machine/0.jpg | matched |
| triceps-extension-machine | Triceps Extension Machine | Machine Triceps Extension | Machine_Triceps_Extension/0.jpg | matched |
| bicep-curl-machine | Bicep Curl Machine | Machine Bicep Curl | Machine_Bicep_Curl/0.jpg | matched |
| hip-adduction-machine | Hip Adduction Machine | Thigh Adductor | Thigh_Adductor/0.jpg | matched |
| glute-kickback-machine | Glute Kickback Machine | Glute Kickback | Glute_Kickback/0.jpg | matched |

Notes on the v3.6 matches:
- **Mid Row Machine → Leverage Iso Row**: the dataset's seated lever row; its instructions ("adjust the seat height so that the handles are at chest level") mirror the Hoist placard, and the photo shows the same seated chest-height pull. Viewed and confirmed.
- **Seated Dip Machine → Dip Machine**: literally the seated dip machine — the same dataset entry already used by Assisted Dip (Chest); the two catalog entries are the same machine/movement with different emphasis (upright triceps vs chest lean), so the files are bundled separately under each id (the Dumbbell Lunges precedent).
- **Glute Kickback Machine → Glute Kickback**: the dataset's kneeling bodyweight kickback — the same hip-extension movement and kneeling base position as the Hoist Glute Master (one knee on a pad, working leg drives rearward); equipment variant per the variant-tolerant rule. Viewed and confirmed.
- **Torso Rotation Machine → unmatched (rejected after check)**: the only candidate, "Torso Rotation", is a standing exercise-ball twist whose frame 0 is a neutral standing pose showing no rotation — the same defect that dropped Cross-Body Shoulder Stretch. No machine torso-rotation entry exists, so the entry stays photo-less (body-map fallback) rather than teach a standing ball twist for a seated machine.
- **Back Extension Machine → unmatched**: no seated machine back-extension entry exists. The only back-extension photos are the 45° hyperextension bench (already used by Back Extension, and a clearly different position from a seated pad machine) and Reverse Hyperextension (a different movement). Photo-less rather than wrong.

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


## Media pass — merged catalog additions (201 entries, 2026-10-16)

Second pass over the 201 entries merged into the per-body-part files on 2026-10-16 (Instagram scan/search staging + Nerd Fitness circuits). Same source and processing as above (images[0] -> `<our-id>.jpg`, images[1] -> `<our-id>-2.jpg`, max width 640px, JPEG quality 80). Matching held the same line: variant-tolerant, never forced across movements. **43 of 201 matched**; the other 158 entries (mostly niche hip-mobility, yoga/mobility flows, stepper/step variants and combo movements with no dataset counterpart) are `unmatched` and stay photo-less (body-map fallback). The 86 new image files live only in the local repo assets (binary files are not pushed to GitHub); this mapping is their provenance record.

| Our ID | Our exercise | Dataset exercise | Image paths | Status |
|---|---|---|---|---|
| ankle-rolls | Ankle Rolls | Ankle Circles | Ankle_Circles/0.jpg, Ankle_Circles/1.jpg | matched |
| ball-slam | Ball Slam | Overhead Slam | Overhead_Slam/0.jpg, Overhead_Slam/1.jpg | matched |
| barbell-deadlift | Barbell Deadlift | Barbell Deadlift | Barbell_Deadlift/0.jpg, Barbell_Deadlift/1.jpg | matched |
| barbell-squat | Barbell Squat | Barbell Squat | Barbell_Squat/0.jpg, Barbell_Squat/1.jpg | matched |
| bent-over-db-row | Bent-Over DB Row | Bent Over Two-Dumbbell Row | Bent_Over_Two-Dumbbell_Row/0.jpg, Bent_Over_Two-Dumbbell_Row/1.jpg | matched |
| bicycle-crunch | Bicycle Crunch | Air Bike | Air_Bike/0.jpg, Air_Bike/1.jpg | matched |
| box-jumps | Box Jumps | Front Box Jump | Front_Box_Jump/0.jpg, Front_Box_Jump/1.jpg | matched |
| chin-ups | Chin-Ups | Chin-Up | Chin-Up/0.jpg, Chin-Up/1.jpg | matched |
| db-front-rack-lunge | DB Front-Rack Lunge | Dumbbell Lunges | Dumbbell_Lunges/0.jpg, Dumbbell_Lunges/1.jpg | matched |
| db-hang-clean | DB Hang Clean | Dumbbell Clean | Dumbbell_Clean/0.jpg, Dumbbell_Clean/1.jpg | matched |
| db-overhead-raise | DB Overhead Raise | Standing Dumbbell Straight-Arm Front Delt Raise Above Head | Standing_Dumbbell_Straight-Arm_Front_Delt_Raise_Above_Head/0.jpg, Standing_Dumbbell_Straight-Arm_Front_Delt_Raise_Above_Head/1.jpg | matched |
| db-reverse-fly | DB Reverse Fly | Reverse Flyes | Reverse_Flyes/0.jpg, Reverse_Flyes/1.jpg | matched |
| db-reverse-lunge | DB Reverse Lunge | Dumbbell Rear Lunge | Dumbbell_Rear_Lunge/0.jpg, Dumbbell_Rear_Lunge/1.jpg | matched |
| db-romanian-deadlift | DB Romanian Deadlift | Stiff-Legged Dumbbell Deadlift | Stiff-Legged_Dumbbell_Deadlift/0.jpg, Stiff-Legged_Dumbbell_Deadlift/1.jpg | matched |
| db-swing | DB Swing | Vertical Swing | Vertical_Swing/0.jpg, Vertical_Swing/1.jpg | matched |
| dumbbell-pullover | Dumbbell Pullover | Bent-Arm Dumbbell Pullover | Bent-Arm_Dumbbell_Pullover/0.jpg, Bent-Arm_Dumbbell_Pullover/1.jpg | matched |
| flutter-kicks | Flutter Kicks | Flutter Kicks | Flutter_Kicks/0.jpg, Flutter_Kicks/1.jpg | matched |
| half-crunch | Half Crunch | Crunches | Crunches/0.jpg, Crunches/1.jpg | matched |
| hanging-leg-raises | Hanging Leg Raises | Hanging Leg Raise | Hanging_Leg_Raise/0.jpg, Hanging_Leg_Raise/1.jpg | matched |
| heel-touches | Heel Touches | Alternate Heel Touchers | Alternate_Heel_Touchers/0.jpg, Alternate_Heel_Touchers/1.jpg | matched |
| hip-thrust | Hip Thrust (Bench) | Barbell Hip Thrust | Barbell_Hip_Thrust/0.jpg, Barbell_Hip_Thrust/1.jpg | matched |
| in-and-outs | In & Outs | Seated Leg Tucks | Seated_Leg_Tucks/0.jpg, Seated_Leg_Tucks/1.jpg | matched |
| jump-rope | Jump Rope | Rope Jumping | Rope_Jumping/0.jpg, Rope_Jumping/1.jpg | matched |
| jump-squat | Jump Squat | Freehand Jump Squat | Freehand_Jump_Squat/0.jpg, Freehand_Jump_Squat/1.jpg | matched |
| kb-toe-touches | KB Toe Touches | Toe Touchers | Toe_Touchers/0.jpg, Toe_Touchers/1.jpg | matched |
| lying-leg-raises | Lying Leg Raises | Flat Bench Lying Leg Raise | Flat_Bench_Lying_Leg_Raise/0.jpg, Flat_Bench_Lying_Leg_Raise/1.jpg | matched |
| one-arm-db-row | One-Arm DB Row | One-Arm Dumbbell Row | One-Arm_Dumbbell_Row/0.jpg, One-Arm_Dumbbell_Row/1.jpg | matched |
| overhead-reach | Overhead Reach | Overhead Stretch | Overhead_Stretch/0.jpg, Overhead_Stretch/1.jpg | matched |
| pallof-press | Pallof Press | Pallof Press | Pallof_Press/0.jpg, Pallof_Press/1.jpg | matched |
| pec-deck-fly | Pec Deck Fly | Butterfly | Butterfly/0.jpg, Butterfly/1.jpg | matched |
| pull-ups | Pull-Ups | Pullups | Pullups/0.jpg, Pullups/1.jpg | matched |
| push-up-rotation | Push-Up with Rotation | Push Up to Side Plank | Push_Up_to_Side_Plank/0.jpg, Push_Up_to_Side_Plank/1.jpg | matched |
| resistance-band-overhead-press | Resistance Band Overhead Press | Shoulder Press - With Bands | Shoulder_Press_-_With_Bands/0.jpg, Shoulder_Press_-_With_Bands/1.jpg | matched |
| resistance-band-squat | Resistance Band Squat | Squats - With Bands | Squats_-_With_Bands/0.jpg, Squats_-_With_Bands/1.jpg | matched |
| reverse-crunch | Reverse Crunch | Reverse Crunch | Reverse_Crunch/0.jpg, Reverse_Crunch/1.jpg | matched |
| seated-arm-stretch | Seated Arm Stretch | Shoulder Stretch | Shoulder_Stretch/0.jpg, Shoulder_Stretch/1.jpg | matched |
| shoulder-swings | Shoulder Swings (Stick) | Round The World Shoulder Stretch | Round_The_World_Shoulder_Stretch/0.jpg, Round_The_World_Shoulder_Stretch/1.jpg | matched |
| side-shuffles-step | Side Shuffles (Step) | Side to Side Box Shuffle | Side_to_Side_Box_Shuffle/0.jpg, Side_to_Side_Box_Shuffle/1.jpg | matched |
| sit-up | Sit-Up | Sit-Up | Sit-Up/0.jpg, Sit-Up/1.jpg | matched |
| spider-climbers | Spider Climbers | Spider Crawl | Spider_Crawl/0.jpg, Spider_Crawl/1.jpg | matched |
| suitcase-squat | Suitcase Squat | Dumbbell Squat | Dumbbell_Squat/0.jpg, Dumbbell_Squat/1.jpg | matched |
| supine-heel-swings | Supine Heel Swings | Windmills | Windmills/0.jpg, Windmills/1.jpg | matched |
| worlds-greatest-stretch | World's Greatest Stretch | World's Greatest Stretch | Worlds_Greatest_Stretch/0.jpg, Worlds_Greatest_Stretch/1.jpg | matched |

Unmatched (photo-less) IDs — as of the media pass; SUPERSEDED by the photo completion sweep below. Current photo-less list (38): alternate-arm-swings, arm-switches, around-the-world, bear-hold-shoulder-tap, body-kicks, body-waves, cactus-arms, chin-tuck-spinal-extensions, cobra-hip-rotation, crunch-kicks, curtsy-lunge, db-cluster, db-deadlift-to-press, elbow-clams, forward-lunge, frog-hold, high-knees, hip-circles, hip-flexor-hamstring-flow, knee-drive-press, malasana-walk, neck-circles-up-dog, neck-rotations, offset-push-up, plank-hip-dips, plank-jacks, plank-jump-kick-backs, plank-jumps, resistance-band-high-to-low-row, scapular-mobility, seated-frog-rotations, seated-frog-twist-reach, seated-leg-kicks, squat-to-calf-raise, squat-to-side-crunch, standing-leg-extension, teep, uppercut-punches

## Photo completion sweep — 2026-10-16

Coverage: 132/318 entries with photos after the media pass → **280/318** after the sweep. Images were sourced from free-exercise-db, covers of the entries' own YouTube Shorts/videos (the video each entry already links), targeted web searches, and one Wikimedia Commons file; every image in the final QA table below was downloaded and visually verified to show the named movement before use. All images are local APK assets only (binaries are never pushed to GitHub); processing matches the pipeline spec (max width 640px, JPEG quality 80). Placements made during the sweep's bulk pass drew on the same source classes; the individually verified final-QA placements are recorded per entry here:

| Entry | Source |
|---|---|
| 180-degree-jump-turns | YouTube Short cover u3NdBboBpOI (web search) |
| air-punches | YouTube Short cover 42ANiDqnaZA (web search) |
| baddha-konasana | Cover of the entry's catalog Short qADkL-7MyiQ |
| back-extension-machine | YouTube cover mD_mKrQSNJs (machine in use) |
| burpee | Cover of the entry's catalog Short dpFi7WuhGzc (dumbbell burpee variant) |
| burpee-box-jump-over | YouTube cover zG6yWXld-xs (web search; title-card style) |
| cossack-squat | Cover of the entry's catalog Short JfauHQjKVrU |
| db-push-press | YouTube cover vuaYVK8xyqo (web search) |
| db-reverse-fly | free-exercise-db Reverse_Flyes 0.jpg / 1.jpg (pair; restored) |
| db-snatch | YouTube cover SiqQ_ePRMQQ |
| deep-squat-hip-stretch | Cover of the entry's catalog Short RY8UJetMs3Y |
| deficit-push-up | YouTube cover DCYIpwmesGU |
| down-dog-calf-walk | Cover of the entry's catalog Short RJv9ZFyXQYY |
| elephant-walk | Wikimedia Commons "Downward-Facing-Dog.JPG", CC BY 3.0 (the movement's start position) |
| floor-wipers | Cover of the entry's catalog Short tnHG1R_3L-o |
| frog-rocks | Cover of the entry's catalog Short UYTbTFsg-1c |
| half-side-lunge-stretch | YouTube cover zejTuBTEkfY (web search) |
| jump-step-ups | Cover of the entry's catalog Short Tr5_yGUKuo4 |
| jumping-jacks | Cover of the entry's catalog Short l7uVPxoBUjs |
| kettlebell-clean-and-press | YouTube cover y06rQiKo7-Y |
| kettlebell-halo | Cover of the entry's catalog Short HxrEOOBo_30 (front-pass position) |
| knee-claps | Cover of the entry's catalog Short ux5q0qFCotg |
| kneeling-ankle-mobility | YouTube cover Kn-TjcmuzYQ (web search) |
| lotus-sphinx | Cover of the entry's catalog Short n3oobgBJtEk (sphinx base pose) |
| low-lunge-hip-stretch | Cover of the entry's catalog Short ZT9BjOEDzFw |
| lunge-high-knee-kick | YouTube cover YDrAnayx_X8 (web search) |
| lunge-to-knee-drive | Cover of the entry's catalog Short gyW9LZWdrgA |
| lunge-with-press | Cover of the entry's catalog Short xVPv-osWTxo |
| malasana | Cover of the entry's catalog Short DHagOsZkxX8 |
| manmaker | YouTube cover jLTvwydY2s4 |
| ninety-ninety | Cover of the entry's catalog Short Z4y8JNYdqrA |
| oblique-crunch | YouTube cover icnHT_3UmtY (standing variant) |
| one-legged-squat | Cover of the entry's catalog Short pIdAfW3OoIM |
| pancake-fold | Cover of the entry's catalog Short K9fIbOSaP-o |
| plank-ball-pass | YouTube cover BBaqnwqS5vA (web search) |
| plank-knee-touches | YouTube cover yXre4qLb4cU |
| plank-pull-through | Cover of the entry's catalog Short 1-pVj5L8nkw |
| plank-reach-outs | YouTube cover YZ2UjoBWLGc |
| pogo-jumps | Cover of the entry's catalog Short s_qM8NDHp1k |
| renegade-row | YouTube cover _0MBf2FSy0E |
| resistance-band-row | YouTube cover qkirwOJmLSY |
| runners-lunge-rotations | Cover of the entry's catalog Short 4V4LzWRcuYg |
| running-in-place | Cover of the entry's catalog Short y5GSK_qZHW8 |
| side-plank-dip | Cover of the entry's catalog Short d-pNRtspSK0 |
| spider-man-steps | Cover of the entry's catalog Short -OBN7kZr60c |
| step-touch | Cover of the entry's catalog Short FYFzE3ZB10Q |
| sumo-squat-to-calf-raise | YouTube cover 1uVAOiZnWOk (web search) |
| supine-butterfly | Cover of the entry's catalog Short Nkj21ICOv7Q |
| torso-rotation-machine | Cover of the entry's catalog Short -gorgRO2pSw (machine in use; dim frame) |
| tuck-front-lever-hold | Cover of the entry's catalog Short s_a3gqLFlNw |

Rejected on visual inspection during final QA and returned to photo-less (image did not clearly show the named movement): around-the-world (cover shows dumbbell halos), bear-hold-shoulder-tap (cover shows donkey kicks, no shoulder tap), body-kicks (roundhouse into a heavy bag; entry is in-place front kicks), curtsy-lunge (one-legged balance drill), db-cluster (talking intro cover), forward-lunge (squat hold with blocks), frog-hold (crow-pose attempt; entry is the prone frog stretch), high-knees (unrelated nature thumbnail), hip-circles (kneeling stretch, no circles), neck-rotations (stock portrait, Pexels 8760439), plank-hip-dips (workout menu card), plank-jacks (presenter standing), plank-jumps (bent-knee bear stance, no jump), squat-to-calf-raise (presenter standing), squat-to-side-crunch (standing stance only), teep (guard stance, no kick), uppercut-punches (trainer holding mitts).

Still photo-less after the sweep (38): the 17 QA rejections above plus 21 with no verifiable image found — alternate-arm-swings, arm-switches, body-waves, cactus-arms, chin-tuck-spinal-extensions, cobra-hip-rotation, crunch-kicks, db-deadlift-to-press, elbow-clams, hip-flexor-hamstring-flow, knee-drive-press, malasana-walk, neck-circles-up-dog, offset-push-up (only cover is an unreadably dark frame), plank-jump-kick-backs, resistance-band-high-to-low-row, scapular-mobility, seated-frog-rotations, seated-frog-twist-reach, seated-leg-kicks, standing-leg-extension (Short cover shows a knee lift, not the extension). Most of the 21 are Instagram-reel-only movements whose frames cannot be extracted in this environment, or niche mobility/stepper variants with no verifiable still; they keep the body-map fallback per the never-a-wrong-photo rule.
