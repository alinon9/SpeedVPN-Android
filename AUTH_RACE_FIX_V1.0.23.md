# Auth race fix — v1.0.23

- Added a process-local `AuthStateLock` around auth-generation validation and token persistence.
- `signOut()` now increments the generation and removes credentials under the same lock, using synchronous `commit()`.
- Token/refresh responses now re-check the generation inside that lock immediately before persistence.
- MainActivity performs sign-out from `lifecycleScope` so the synchronous preference commit does not block the UI thread.
- No network operation is performed while holding `AuthStateLock`.
