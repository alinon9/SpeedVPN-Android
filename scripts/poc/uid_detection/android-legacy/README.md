# UID detection POC (archived)

The Kotlin sources in `android-legacy/` are retained only as an experimental reference.
They are deliberately outside every Android Gradle source set and are not compiled into
Debug or Release APKs.

The native patcher `patch_hev_uid_detection_poc.py` is also experimental and is **not**
called by `setup.sh`, `setup.bat`, or any production build task. Do not run it as part
of an APK build. Gradle's `verifyNativeSources` gate fails if its JNI markers appear in
the native checkout.

The old POC depended on native-to-JVM callbacks. A future app-attribution implementation
must have a separately reviewed data source for the original TUN 5-tuple and must not
assume that a SOCKS loopback socket identifies the originating application: that socket
belongs to the HEV/SpeedVPN process. `ConnectivityManager.getConnectionOwnerUid()`
can only return meaningful attribution when queried with the actual owning connection's
tuple, so the relay must not substitute its own socket tuple for the original app flow.
