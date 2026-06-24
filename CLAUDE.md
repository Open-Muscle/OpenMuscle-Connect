# CLAUDE.md

This file briefs the Claude Code agent that will be working on Open Muscle Connect. Read it in full at the start of every session; it is intentionally written to be self-sufficient for an agent that has never seen this codebase before.

## Your project

You are working on **Open Muscle Connect**, the Android companion application for the OpenMuscle FlexGrid wearable sensor bracelet. This repository is currently in design phase with no code yet. Your work is to scope, decide architecture, scaffold, and then implement.

For the public-facing project description, see `README.md` in this folder.

## Your scope

The high-level goal: bring everything the existing PC application does (sensor visualization, model training, real-time inference) onto an Android phone, plus add Bluetooth as a transport, plus add VR application pairing.

For the working scope details, see `docs/PROJECT-SCOPE.md`.

## The wider ecosystem you should know about

Open Muscle Connect is one node in a multi-app, multi-device research project. The other repositories live as sibling folders in this same parent directory, so you can read them directly from your filesystem in addition to GitHub:

```
D:\MT2-TX\OneDrive - TURFPTAx\Documents\GitHub\
  OpenMuscle-FlexGrid\         ← wearable HARDWARE (KiCad), current V4 in fab
  FlexGridV3-Firmware\         ← FIRMWARE on the wearable (MicroPython, ESP32-S3)
  OpenMuscle-Software\         ← PC APP + ML pipeline (provisional name: Open Muscle Lab)
  OpenMuscle-AR\               ← VR / AR application (Quest 3 + WebXR companion)
  OpenMuscle-LASK5\            ← hand-position LABELER hardware (a separate device that records hand pose as ground truth)
  OpenMuscle-Library\          ← shared ML library
  OpenMuscle-Dataset\          ← captured datasets
  OpenMuscle-Hub\              ← documentation hub
  OpenMuscle-Hardware\         ← older hardware repo from the V0 era
  OpenMuscle-Band\             ← original bracelet hardware concept
  OpenMuscle-Connect\          ← THIS REPO
```

Before you propose any architecture, **read at minimum these three sibling repos**:
1. `OpenMuscle-FlexGrid\KiCad\OM-FlexGrid V4\README.md` to understand the wearable hardware: 60-sensor 15 by 4 Velostat matrix, ESP32-S3-WROOM-1-N16R8 controller, USB-C charging, BLE-capable, currently streams over Wi-Fi UDP.
2. `FlexGridV3-Firmware\` for the firmware: how the device scans the matrix, formats packets, and currently transmits. Look for the Wi-Fi UDP send loop and the packet format. **The firmware does not yet do BLE for streaming**; adding the BLE transport on the wearable side is firmware work that pairs with your Android work.
3. `OpenMuscle-Software\pc\src\openmuscle\` for the PC app: how it parses incoming UDP packets, builds the 60-channel feature vector, trains the regressor (look for `SGDRegressor` or similar), runs inference, and visualizes both the raw heatmap and the predicted hand pose.

For the OpenMuscle-AR repo, focus on understanding **how it currently couples to the PC app** so you can design an analogous coupling to your Android app.

## The user (Tory)

The user is **Tory** (`TURFPTAx` on GitHub, runs [openmuscle.org](https://openmuscle.org)). He is an AI Engineer at a medical company. He designs the hardware, writes firmware, builds the PC app, and now wants the Android companion. He is the maintainer of every OpenMuscle repo you can see.

Tory's collaboration style:
- He wants honest engineering input, not deference. If a tech-stack choice has tradeoffs, present them; do not just pick one and pretend it is obvious.
- He values direct, terse responses. Match the task scope: a quick question gets a one-sentence answer, not a section header.
- For exploratory questions, give a recommendation in 2-3 sentences with the main tradeoff. Do not implement until he agrees.
- For UI/app work, expect him to want to see screenshots or run the app himself before considering anything "done."

## Your global preferences (carry these forward, they apply across all his projects)

### No em dashes in published artifacts

Do not use em dashes (`—`) in any file written to disk: code, READMEs, code comments, commit messages, docs, configs, anything that ships. Use commas, parens, semicolons, or two sentences instead.

Em dashes in chat replies are fine; they do not get published. The rule is about written artifacts, not conversation.

Why: em dashes have become an AI tell. Tory's GitHub is a portfolio that signals "how a real engineer writes."

### Git commit co-author attribution

**Default: do NOT add a Co-Authored-By line** even though some default tooling tries to.

Only add a co-author when you actually wrote or meaningfully modified the code/content being committed. Running `git add`/`git commit`/`git push` on his behalf does NOT count; that is just typing for him.

When you DO co-author (you wrote real code, designed something, drafted prose), use this trailer with your model version encoded:

```
Co-Authored-By: turfptax-claude <model-tag> <turfptax-claude@openmuscle.org>
```

Use the project email `turfptax-claude@openmuscle.org`, NOT `noreply@anthropic.com`. The OpenMuscle MX server has a shared inbox at that address.

Model-tag is letter-then-version of the model running:

| Model family | Letter | Examples |
|---|---|---|
| Opus | `O` | `O4.7`, `O4.8`, `O5.0` |
| Sonnet | `S` | `S4.5`, `S4.6`, `S5.0` |
| Haiku | `H` | `H4.5` |
| Fable | `F` | `F5` |

A PreToolUse hook at `~/.claude/hooks/validate-coauthor.py` will block commits with the wrong email format if you try to use `noreply@anthropic.com`. Use the openmuscle.org address.

### Executing actions with care

Tory uses Claude Code in agentic mode and may have aggressive permissions set. That does not relieve you of judgment. Before destructive or hard-to-reverse actions (force push, branch delete, dropping data, rm -rf, etc.), confirm with him.

For shared-state actions (pushing code, posting GitHub comments, sending Discord messages), confirm with him first unless he explicitly preauthorized the scope.

## Architectural open questions

These are the decisions you and Tory need to settle together early. Do not pick unilaterally; survey first, propose with tradeoffs, then implement.

See `docs/TECH-DECISIONS.md` for the full list. Top items:

1. **Android framework**: native Kotlin? Flutter? React Native? Each has implications for camera/sensor integration, on-device ML, and code-sharing with a future iOS app.
2. **On-device ML runtime**: TensorFlow Lite? ONNX Runtime Mobile? Custom?
3. **BLE design**: does the wearable advertise as a GATT service with custom characteristics, or does it use a third-party protocol like BLE serial? This affects the firmware side too.
4. **Wire format compatibility**: do you keep the existing UDP packet format and just add BLE as an alternative transport, or do you redesign the wire format for both?
5. **Where does training happen**: phone-only, phone + optional cloud sync, or always paired with the PC app for heavy training?
6. **VR pairing model**: phone as the data hub talking to both the wearable and the VR app, or phone and VR as peers?

## Recommended first session plan

1. Read `docs/PROJECT-SCOPE.md` and `docs/ECOSYSTEM.md` in this folder.
2. Read the three sibling-repo entry points listed above (FlexGrid V4 README, firmware Wi-Fi UDP loop, PC app source root).
3. Write a short architectural recommendation document (`docs/ARCHITECTURE-PROPOSAL.md`) covering: framework choice, transport strategy, ML runtime, and phase plan. Use the "give a recommendation with the main tradeoff" approach; do not over-survey.
4. Wait for Tory's review and decision before scaffolding the Android project.

## Repository state and git

This folder is **not yet a git repository**. Do not `git init` until Tory confirms the repo name and license. The likely GitHub destination is `Open-Muscle/OpenMuscle-Connect` under his GitHub organization, MIT licensed (matching the rest of the software side).

When you do initialize, the first commit should be the briefing files in this folder so the new repo's history starts with the project scope.

## Quick reference: where things live

| What | Where |
|---|---|
| This briefing folder | `D:\MT2-TX\OneDrive - TURFPTAx\Documents\GitHub\OpenMuscle-Connect\` |
| Wearable hardware (KiCad) | `D:\MT2-TX\OneDrive - TURFPTAx\Documents\GitHub\OpenMuscle-FlexGrid\` |
| Firmware (MicroPython) | `D:\MT2-TX\OneDrive - TURFPTAx\Documents\GitHub\FlexGridV3-Firmware\` |
| PC app (provisional: Open Muscle Lab) | `D:\MT2-TX\OneDrive - TURFPTAx\Documents\GitHub\OpenMuscle-Software\` |
| VR app | `D:\MT2-TX\OneDrive - TURFPTAx\Documents\GitHub\OpenMuscle-AR\` |
| GitHub org | `https://github.com/Open-Muscle/` |
| Project site | `https://openmuscle.org` |
| Tory's email | `torylogos@gmail.com` |

Good luck. Start by reading, not coding.
