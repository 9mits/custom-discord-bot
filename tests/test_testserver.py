import unittest

from scripts import testserver


class GrimPrinterConfigTests(unittest.TestCase):
    def test_printer_checks_never_cancel_and_patch_is_idempotent(self):
        original = """\
AirLiquidPlace:
    cancelvl: 0

FabricatedPlace:
    cancelvl: 5

FarPlace:
    cancelvl: 5
"""

        patched = testserver.grim_printer_config(original)

        for check in testserver.GRIM_PRINTER_CHECKS:
            self.assertIn(f"{check}:\n    cancelvl: -1", patched)
        self.assertIn("FarPlace:\n    cancelvl: 5", patched)
        self.assertEqual(patched, testserver.grim_printer_config(patched))

    def test_printer_checks_are_removed_from_the_kick_group(self):
        original = """\
Punishments:
  Misc:
    checks:
      - "Place"
      - "Break"
"""

        patched = testserver.grim_printer_punishments(original)

        for check in testserver.GRIM_PRINTER_CHECKS:
            self.assertIn(f'- "!{check}"', patched)
            self.assertIn(f'      - "{check}"', patched)
        self.assertIn('- "Place"', patched)
        self.assertIn('- "Break"', patched)
        self.assertIn("  LitematicaPrinter:", patched)
        self.assertIn('      - "5:5 [alert]"', patched)
        self.assertIn('      - "1:1 [log]"', patched)
        self.assertNotIn("kick %player%", patched)
        self.assertEqual(patched, testserver.grim_printer_punishments(patched))


if __name__ == "__main__":
    unittest.main()
