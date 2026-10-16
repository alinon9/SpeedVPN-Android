from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class NativeBuildContractTests(unittest.TestCase):
    def test_bootstrap_scripts_apply_tcp_patch_without_applying_uid_poc(self) -> None:
        setup_sh = (ROOT / "setup.sh").read_text(encoding="utf-8")
        setup_bat = (ROOT / "setup.bat").read_text(encoding="utf-8")
        for script in (setup_sh, setup_bat):
            self.assertIn("patch_hev_tcp_buffers.py", script)
            self.assertIn("--check", script)
            self.assertNotIn("patch_hev_uid_detection_poc.py", script)

    def test_gradle_verifies_pinned_and_patched_native_sources_before_ndk(self) -> None:
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('tasks.register("prepareNativeSources")', gradle)
        self.assertIn('tasks.register("verifyNativeSources")', gradle)
        self.assertIn('dependsOn(verifyNativeSources)', gradle)
        self.assertIn("HEV_NATIVE_PIN", gradle)
        self.assertIn("patch_hev_tcp_buffers.py", gradle)
        self.assertIn("SPEEDVPN_UID_POC_BEGIN", gradle)

    def test_production_vpn_lifecycle_has_no_uid_poc_jni_calls(self) -> None:
        service = (ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt").read_text(encoding="utf-8")
        bridge = (ROOT / "app/src/main/java/hev/htproxy/TProxyService.java").read_text(encoding="utf-8")
        self.assertNotIn("UidDetectionPoc", service)
        self.assertNotIn("UidPocSettings", service)
        self.assertNotIn("TProxySetUidPocCallback", service)
        self.assertNotIn("UidPocCallback", bridge)
        self.assertFalse((ROOT / "app/src/main/java/com/speedvpn/app/UidDetectionPoc.kt").exists())
        self.assertFalse((ROOT / "app/src/main/java/com/speedvpn/app/UidPocCompatibility.kt").exists())

    def test_release_build_uses_jni_keep_rules(self) -> None:
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        rules = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8")
        release_block = gradle.split('release {', 1)[1].split('\n        }', 1)[0]
        self.assertIn("isMinifyEnabled = true", release_block)
        self.assertIn('"proguard-rules.pro"', release_block)
        self.assertIn("-keep class hev.htproxy.TProxyService { *; }", rules)
        self.assertIn("native <methods>", rules)


if __name__ == "__main__":
    unittest.main()
