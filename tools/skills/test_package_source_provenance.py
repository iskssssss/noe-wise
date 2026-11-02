"""Adapted package hashes do not replace the original upstream provenance."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import package_skills


class AdaptedSourceProvenanceTest(unittest.TestCase):
    def test_obsolete_builtin_file_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory)/'lock.json'
            lock.write_text(json.dumps({'sources':[{'commit':'a'*40, 'file_root':'builtin-skills', 'files':[]}]}), encoding='utf-8')
            with patch.object(package_skills, 'LOCK', lock):
                with self.assertRaisesRegex(ValueError, 'unknown source file root'):
                    package_skills.verify_sources()

    def test_current_adaptation_is_validated_independently_from_upstream_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root/'reference.md'
            package.write_bytes(b'platform adaptation\r\n')
            upstream = hashlib.sha256(b'upstream original\n').hexdigest()
            current = hashlib.sha256(b'platform adaptation\n').hexdigest()
            lock = root/'lock.json'
            lock.write_text(json.dumps({'sources':[{'repository':'https://example.com/original',
                'commit':'a'*40, 'adaptation':'Platform guidance adaptation', 'files':[{
                    'source_path':'original.md', 'package_path':'reference.md',
                    'raw_sha256':upstream, 'upstream_normalized_sha256':upstream,
                    'normalized_sha256':current}]}]}), encoding='utf-8')
            with patch.object(package_skills, 'LOCK', lock), patch.object(package_skills, 'SKILLS', root):
                verified = package_skills.verify_sources()['sources'][0]['files'][0]
                self.assertEqual(upstream, verified['raw_sha256'])
                self.assertEqual(upstream, verified['upstream_normalized_sha256'])
                self.assertEqual(current, verified['normalized_sha256'])
                package.write_bytes(b'unreviewed change\n')
                with self.assertRaisesRegex(ValueError, 'frozen upstream changed'):
                    package_skills.verify_sources()


if __name__ == '__main__':
    unittest.main()
