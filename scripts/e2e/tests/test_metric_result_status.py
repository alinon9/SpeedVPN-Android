import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from metric_result_status import has_terminal_metric_row


class MetricResultStatusTests(unittest.TestCase):
    def write_xml(self, directory: str, baseline: str, plan: str, vpn: str, verdict: str) -> Path:
        xml = Path(directory) / "result.xml"
        xml.write_text(
            "<hierarchy>"
            f'<node text="🌐 سرعة الإنترنت الأصلية"/><node text="{baseline}"/>'
            f'<node text="🔒 السرعة المحجوزة / المحددة"/><node text="{plan}"/>'
            f'<node text="🚀 السرعة الفعلية داخل VPN"/><node text="{vpn}"/>'
            f'<node text="{verdict}"/>'
            "</hierarchy>",
            encoding="utf-8",
        )
        return xml

    def test_numeric_success_is_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = self.write_xml(directory, "100 Mbps", "800 Kbps", "802 Kbps", "✅ السرعة متطابقة")
            self.assertTrue(has_terminal_metric_row(xml, 800))

    def test_explicit_vpn_measurement_error_is_terminal_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = self.write_xml(directory, "100 Mbps", "800 Kbps", "فشل القياس", "⚠️ لا يمكن الحكم")
            self.assertTrue(has_terminal_metric_row(xml, 800))

    def test_explicit_baseline_error_is_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = self.write_xml(directory, "فشل القياس", "800 Kbps", "فشل القياس", "⚠️ لا يمكن الحكم")
            self.assertTrue(has_terminal_metric_row(xml, 800))

    def test_wrong_selected_plan_is_not_terminal_for_current_case(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = self.write_xml(directory, "100 Mbps", "600 Kbps", "580 Kbps", "❌ السرعة غير متطابقة")
            self.assertFalse(has_terminal_metric_row(xml, 800))

    def test_missing_final_verdict_is_not_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = self.write_xml(directory, "100 Mbps", "800 Kbps", "فشل القياس", "جارٍ القياس")
            self.assertFalse(has_terminal_metric_row(xml, 800))

    def test_missing_metric_card_is_not_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "result.xml"
            xml.write_text("<hierarchy><node text=\"MainActivity\"/></hierarchy>", encoding="utf-8")
            self.assertFalse(has_terminal_metric_row(xml, 800))


if __name__ == "__main__":
    unittest.main()
