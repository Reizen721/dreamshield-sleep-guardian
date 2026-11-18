"""Actual Python unit tests for the offline project parser; not XCTest."""
import unittest

from validate_project import code_for_privacy_scan, parse_openstep


class OpenStepParserTests(unittest.TestCase):
    def test_dictionary_and_array(self):
        self.assertEqual(parse_openstep('{ key = (one, two,); nested = { yes = YES; }; }'), {'key': ['one', 'two'], 'nested': {'yes': 'YES'}})

    def test_comments_and_quotes(self):
        self.assertEqual(parse_openstep('// header\n{ /* comment */ a = "$(TARGET_NAME)"; }'), {'a': '$(TARGET_NAME)'})

    def test_duplicate_key_rejected(self):
        with self.assertRaises(ValueError):
            parse_openstep('{ a = 1; a = 2; }')

    def test_trailing_content_rejected(self):
        with self.assertRaises(ValueError):
            parse_openstep('{} {}')

    def test_missing_terminator_rejected(self):
        with self.assertRaises(ValueError):
            parse_openstep('{ a = b }')

    def test_unclosed_array_rejected(self):
        with self.assertRaises(ValueError):
            parse_openstep('{ a = (one, two; }')

    def test_empty_array_and_dictionary(self):
        self.assertEqual(parse_openstep('{ a = (); b = {}; }'), {'a': [], 'b': {}})

    def test_missing_array_comma_rejected(self):
        with self.assertRaises(ValueError):
            parse_openstep('{ a = (one two); }')


class PrivacyScannerTests(unittest.TestCase):
    def test_documentation_does_not_count_as_use(self):
        self.assertNotIn('UserDefaults', code_for_privacy_scan('/// No UserDefaults.\nlet x = 1'))

    def test_ordinary_literal_does_not_count_as_use(self):
        self.assertNotIn('UserDefaults', code_for_privacy_scan('let x = "UserDefaults is not used"'))

    def test_actual_api_use_remains_visible(self):
        self.assertIn('UserDefaults.standard', code_for_privacy_scan('let x = UserDefaults.standard'))

    def test_property_wrapper_remains_visible(self):
        self.assertIn('@AppStorage', code_for_privacy_scan('@AppStorage("setting") var enabled = false'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
