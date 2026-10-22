# Overlapping path pauses

Movement audit finding #3: Auto Eat, Auto Gap, Kill Aura and Bow Aimbot each used a shared backend pause boolean. One module could resume another's pause. Checking only `isPathing()` also prevented a second module from acquiring an already-paused path.

0.14.40 adds shared identity-based ownership in `PathManagers`, ahead of the existing backend interface. The first owner pauses the backend; the last release resumes it. Duplicate operations and a release by an unrelated owner do nothing. Navigation remains discoverable while paused, and pending Baritone navigation uses 0.14.39's ownership check.

All four built-in callers use the owned API. Bow Aimbot additionally releases on disable, invalid player/weapon/ammo, lost target or released aiming input. Kill Aura releases its owner when combat pause is switched off and during normal attack cleanup. These fix actual leaked-owner paths; no new settings or automatic combat behavior is introduced.

Behavioral tests run the real owner set against a simulated path backend, covering overlapping meal/combat owners, different release orders, repeated/unknown releases and object identity. Compiled-call checks cover all four module migrations and Bow Aimbot deactivation cleanup. This does not claim control over third-party modules that call a backend's legacy pause/resume methods directly.

The update also contains the preceding flight and deferred-cancellation fixes. Live overlapping meal/combat timing still needs exercising after installation.
