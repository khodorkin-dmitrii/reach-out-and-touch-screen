# AGENTS.md

## Project Overview

Reach Out and Touch Screen is a compact Android portfolio project focused on
interactive real-time 3D rendering with Google Filament.

The central interaction is intentionally simple: the user touches a 3D surface
and the surface responds with a visible ripple. Later milestones may add several
simultaneous ripples, wave interference, GPU-driven surface deformation, and
interactive lighting.

The project should demonstrate senior-level Android engineering through a small,
polished, technically explainable implementation. It must not grow into a game
engine, a general-purpose 3D editor, or a large physics sandbox.

## Product Priorities

When making implementation decisions, optimize for:

1. A clear and compelling visual result.
2. Correct Android and GPU resource lifecycle handling.
3. Stable frame time and predictable memory use.
4. Small, independently verifiable milestones.
5. Code and architecture that can be explained in a portfolio or interview.
6. Testability of non-GPU logic without hiding Filament behind excessive
   abstraction.

Do not add technologies, layers, or dependencies only to expand the technology
list in the README or CV.

## Confirmed Technical Direction

- Target platform: Android.
- Language: Kotlin.
- UI shell: Jetpack Compose created from the Android Studio Empty Activity
  template.
- 3D renderer: Google Filament using its direct Android/Kotlin API.
- The preferred integration prototype uses `AndroidExternalSurface` to provide
  an Android `Surface` to Filament.
- Compose owns the screen structure, touch input, controls, and optional debug
  overlay. Filament owns real-time 3D rendering.
- The initial scene contains one central interactive 3D object.
- Touch-driven ripple effects are the core MVP interaction.
- Multiple sequentially created ripples are part of the intended MVP.

The direct `AndroidExternalSurface` integration is the current selected path,
but it must still be validated with a minimal lifecycle prototype. Do not replace
it with `AndroidView`, a custom `SurfaceView`, or a third-party wrapper such as
SceneView unless a verified technical limitation is documented first.

## Open Decisions

Do not silently turn any of the following into requirements:

- sphere versus cube for the first interactive surface;
- exact Filament version;
- minimum and target Android SDK changes;
- mesh source and density;
- renderer and material class structure;
- ripple equation and visual profile;
- CPU versus GPU responsibility for ripple state;
- how multiple ripple parameters are transferred to the GPU;
- the maximum number of active ripples;
- true simultaneous multitouch in the MVP;
- camera, object, and light gestures;
- final visual theme;
- performance budget and target test devices;
- additional Gradle modules.

When a task depends on an unresolved decision, inspect the repository, present
the relevant alternatives and trade-offs, and ask for a decision when the choice
would materially affect the implementation.

## Milestone Boundaries

Work in small stages. The intended progression is:

1. A stable Filament scene with one object, a camera, a light, a material, and
   correct render lifecycle handling.
2. Screen-to-world ray construction and one touch ripple.
3. Several active ripples with bounded storage and predictable replacement or
   expiry behavior.
4. Linear wave interference.
5. GPU-oriented surface deformation, initially considering vertex displacement
   along the surface normal.
6. Interactive lighting and useful visual comparisons.

Do not implement later stages as part of an earlier task unless the task
explicitly requests them. In particular, the first scene milestone does not need
wave interference, geometry deformation, multitouch, camera gestures, presets,
or a settings architecture.

## Architecture Guidelines

- Inspect the current repository before proposing or making changes.
- Preserve existing working behavior and user changes.
- Keep the structure proportional to the current scope. A single `app` module is
  acceptable until a real separation need appears.
- Do not introduce Clean Architecture layers, use cases, repositories, or a DI
  framework without a concrete project need.
- Keep Android lifecycle and input handling separate from scene state and ripple
  mathematics where that separation improves clarity or testing.
- Make ownership of every Filament and GPU resource explicit.
- Destroy resources deterministically and in the correct dependency order.
- Treat `Surface` creation, resize, destruction, pause/resume, activity
  recreation, and background/foreground transitions as first-class cases.
- Keep allocations, logging, synchronization, and expensive work out of the
  per-frame path.
- Prefer immutable configuration and bounded mutable runtime state where
  practical.
- Isolate ray, intersection, timing, lifetime, and wave math from Android or GPU
  APIs when this allows meaningful unit tests.
- Add an abstraction only when it protects a real boundary or makes a concrete
  behavior testable.
- Document significant trade-offs in code, README, or an ADR only when the
  decision is non-obvious and likely to matter later.

## Filament and Compose Integration

- Filament does not render through Compose. Compose provides and arranges the
  surface while Filament renders into the associated swap chain.
- The renderer should clearly own the Filament `Engine`, `Renderer`, `Scene`,
  `View`, camera-related entities, swap chain, and scene resources that it
  creates.
- Swap-chain lifetime must follow the current Android `Surface` lifetime. Never
  continue rendering to a destroyed surface.
- Surface resizing must update the Filament viewport and any projection values
  that depend on the aspect ratio.
- Frame scheduling must stop when rendering is not possible and resume without
  creating duplicate loops.
- Touch coordinates must use the actual rendered surface size and coordinate
  system. Do not assume Compose layout coordinates automatically match Filament
  viewport coordinates.
- Avoid blocking the UI thread with resource creation or per-frame computation.
  At the same time, respect Filament's threading requirements and do not move API
  calls between threads without verifying that the selected Filament version
  supports it.
- Use official Android, Compose, and Filament documentation when behavior is
  version-dependent. Record the version and any unverified assumption in the
  task report.

## Visual and Performance Requirements

A rendering feature is not complete merely because the project compiles.

For visual changes:

- verify the result on an emulator or, preferably, a physical Android device;
- check common portrait and landscape sizes when relevant;
- verify that the object, lighting, and effect remain readable;
- capture a screenshot or short recording when it helps review the result;
- describe anything that could not be visually verified.

For performance-sensitive changes:

- avoid unbounded collections and per-frame object churn;
- measure frame time, FPS, memory, and device behavior before claiming an
  optimization;
- distinguish measured results from estimates;
- use a repeatable scenario and report the device or emulator configuration.

## Testing

Use the smallest useful combination of automated and manual checks.

Automated tests should cover deterministic non-GPU behavior such as:

- ray construction and coordinate conversion;
- ray/object intersection math;
- ripple lifetime and expiry;
- bounded active-ripple behavior;
- wave parameter calculations and interference math;
- state transitions that do not require a real Filament engine.

Manual checks should cover, as applicable:

- initial scene rendering;
- surface creation, resize, and recreation;
- repeated pause/resume and background/foreground transitions;
- activity recreation and orientation changes;
- rapid repeated touches;
- multiple aspect ratios;
- resource cleanup and the absence of duplicate render loops;
- visual quality and stable frame pacing.

## Build and Verification Commands

Before reporting completion, run the relevant commands from the repository root.
For the current single-module Android project, the expected baseline is:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

If the repository defines additional formatting, static analysis, connected
tests, or benchmark tasks, discover and run the tasks relevant to the change.
Do not claim that a command passed if it was not run. If a command cannot run,
report the exact blocker and what remains unverified.

## Code and Repository Conventions

- Follow the existing package structure, Kotlin style, version catalog, and
  Gradle conventions unless the task explicitly changes them.
- Use English for code, identifiers, comments, UI text, README, ADRs, issues,
  and other public repository artifacts.
- Prefer clear names over abbreviations.
- Keep comments focused on intent, constraints, or non-obvious behavior.
- Do not commit local IDE state, generated build output, credentials, signing
  keys, captures, or machine-specific configuration.
- Do not rewrite unrelated files or reformat the repository unnecessarily.
- Do not upgrade Gradle, Kotlin, Android Gradle Plugin, Compose, or Filament as an
  incidental change.
- Do not add copyrighted Depeche Mode artwork, logos, fonts, audio, lyrics, or
  other protected assets. The project name is a wordplay reference only.

## Working Process for Agents

For every implementation task:

1. Read this file and inspect the relevant repository files.
2. Check `git status` and preserve unrelated or pre-existing user changes.
3. Restate the task scope and identify unresolved decisions or assumptions.
4. Implement the smallest coherent change that satisfies the task.
5. Add or update tests for deterministic logic.
6. Run the relevant build, test, lint, and visual checks.
7. Review the diff for accidental changes, lifecycle issues, resource leaks,
   per-frame allocations, and unsupported claims.
8. Report what changed, why, what was verified, and what remains unverified.

Do not make commits, push branches, open pull requests, or change remote
resources unless the user explicitly asks for those actions.

## Completion Report

The final report for a task should include:

- a concise summary of implemented behavior;
- the important files or components changed;
- key technical decisions and their trade-offs;
- commands and manual checks performed with their results;
- visual or performance measurements when relevant;
- limitations, unverified assumptions, and recommended next step;
- any deviation from the requested scope.

Be precise. Separate observed facts, measurements, implementation choices, and
future suggestions.
