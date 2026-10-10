import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from preference_matches import preferences_match


class PreferenceMatchesTests(unittest.TestCase):
    def test_exact_value_matches(self):
        xml = '<map><long name="dl" value="800" /></map>'
        self.assertTrue(preferences_match(xml, "dl", 800))

    def test_different_value_does_not_match(self):
        xml = '<map><long name="dl" value="600" /></map>'
        self.assertFalse(preferences_match(xml, "dl", 800))

    def test_missing_key_does_not_match(self):
        xml = '<map><long name="ul" value="800" /></map>'
        self.assertFalse(preferences_match(xml, "dl", 800))

    def test_malformed_xml_does_not_match(self):
        self.assertFalse(preferences_match("<map><long", "dl", 800))

    def test_malformed_numeric_value_does_not_match(self):
        xml = '<map><long name="dl" value="fast" /></map>'
        self.assertFalse(preferences_match(xml, "dl", 800))

    def test_upload_requires_download_to_remain_same_when_requested(self):
        xml = '<map><long name="dl" value="800" /><long name="ul" value="800" /></map>'
        self.assertTrue(preferences_match(xml, "ul", 800, "both"))
        changed_download = '<map><long name="dl" value="600" /><long name="ul" value="800" /></map>'
        self.assertFalse(preferences_match(changed_download, "ul", 800, "both"))

    def test_unlimited_zero_is_valid(self):
        xml = '<map><long name="dl" value="0" /></map>'
        self.assertTrue(preferences_match(xml, "dl", 0))

    def test_unknown_mode_is_rejected(self):
        xml = '<map><long name="dl" value="800" /></map>'
        self.assertFalse(preferences_match(xml, "dl", 800, "maybe"))


if __name__ == "__main__":
    unittest.main()
