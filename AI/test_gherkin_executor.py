import unittest

from gherkin_executor import GherkinExecutor


class AssertTextVisiblePatternTests(unittest.TestCase):
    def test_french_quoted_text_with_etre_visible_is_captured(self):
        step = "Alors le texte 'Veuillez remplir tous les champs' devrait être visible"
        match = GherkinExecutor.STEP_PATTERNS["assert_text_visible"].search(step)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "Veuillez remplir tous les champs")

    def test_english_quoted_text_still_captured(self):
        step = "Then text 'Please fill all fields' should be visible"
        match = GherkinExecutor.STEP_PATTERNS["assert_text_visible"].search(step)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "Please fill all fields")


if __name__ == "__main__":
    unittest.main()
