# Android Emulator CI

The repository runs an Android Emulator end-to-end workflow on pushes to main/master and pull requests.

The E2E job builds the debug APK, boots an Android 9 Google APIs x86_64 emulator, verifies HTTPS connectivity from inside the guest, runs three real Speed Test runs, then runs Verify Speed with a finite 100 KB/s plan.

A failure means the automated emulator test could not complete the requested validation; the emulator diagnostics and logcat artifacts are uploaded when available.