"""Settings › Console › Keys (LibrarySettings.copyKeys): a picked file is checked beside the keys in the app before it takes their place, so a file that is
not a prod.keys changes nothing and the keys the app had are loaded again; the FAQ says the same. Against the sources (the check runs in the importer)."""
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LIB = REPO / 'android/app/src/main/java/dev/peanuts/ui/LibrarySettings.java'
FAQ = REPO / 'peanuts/docs/FAQ.md'


def between(text, start, end):
    i = text.index(start)
    return text[i:text.index(end, i)]


class KeysCheck(unittest.TestCase):
    def test_a_wrong_keys_file_changes_nothing(self):
        lib = LIB.read_text(encoding='utf-8')
        copy = between(lib, 'private static void copyKeys(Activity a, Uri u) {', '\n    }\n')
        self.assertIn('int r = imp.keys(tmp.getPath());', copy, 'the picked file is checked before it takes the place of the keys in the app')
        self.assertNotIn('imp.keys(out.getPath())', copy, 'never the keys in the app first: a wrong file would already have replaced them')
        self.assertIn('boolean placed = r == 0 && tmp.renameTo(out);', copy, 'only a file the importer accepts takes their place')
        self.assertIn('ImporterPort.Holder.KEYS_OK = false;', copy)
        self.assertIn('ImporterPort.ensureKeys(a);', copy, "the app's own keys are loaded again after a wrong file")
        self.assertLess(copy.index('if (!imp.available())'), copy.index('tmp.renameTo(out)'), 'without the importer the file is placed as before')
        self.assertNotIn('The file is kept; pick another to replace it.', lib)
        refused = between(lib, 'private static void keysRefused(Activity a, String why) {', '\n    }\n')
        self.assertIn('Your keys were not changed.', refused)

    def test_faq_says_the_same(self):
        faq = FAQ.read_text(encoding='utf-8')
        self.assertIn('### The app says "That file is not a prod.keys the app can read". What is wrong?', faq)
        self.assertIn('Nothing was changed: keys you added before stay as they were.', faq)
        self.assertNotIn('the rejected file is kept', faq)
        self.assertNotIn('The importer does not accept these keys', faq)


if __name__ == '__main__':
    unittest.main()
