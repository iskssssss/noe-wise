"""核验72行多格式资料的真值及不可覆盖目录，不调用业务服务。"""
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from complex_fixture import fixture, write_fixture


class ComplexFixtureTest(unittest.TestCase):
    """独立解析CSV/TSV/JSON并核对重复行、空值、引用和原字节。"""
    def test_formats_preserve_72_rows_and_exact_aggregate(self):
        files, oracle = fixture()
        rows = list(csv.DictReader(io.StringIO(files["sales-72.csv"].decode())))
        tsv = list(csv.DictReader(io.StringIO(files["sales-72.tsv"].decode()), delimiter="\t"))
        values = json.loads(files["data.json"])["records"]
        self.assertEqual(72, len(rows))
        self.assertEqual(rows, tsv)
        self.assertEqual(oracle["total_cents"], sum(int(row["amount_cents"]) for row in rows))
        self.assertEqual(72, len({row["record_id"] for row in rows}))
        self.assertEqual(8, sum(value["owner"] is None for value in values))
        self.assertEqual(18, sum(row["status"] == "待审批" for row in rows))
        self.assertEqual('中文, quoted "value"\n第二行', rows[0]["note"])
        self.assertEqual(72, files["brief.md"].decode().count("|sale-"))
        self.assertEqual(72, files["index.html"].decode().count("<tr data-id="))

    def test_bytes_and_references_are_frozen(self):
        files, oracle = fixture()
        for fact in oracle["files"]:
            self.assertEqual(fact["size"], len(files[fact["path"]]))
            self.assertEqual(fact["sha256"], hashlib.sha256(files[fact["path"]]).hexdigest())
        references = json.loads(files["data.json"])["references"]
        self.assertTrue(set(references.values()).issubset(files))
        self.assertEqual(fixture(), (files, oracle))

    def test_owned_directory_never_overwrites_existing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "batch"
            write_fixture(output)
            before = (output / "oracle.json").read_bytes()
            with self.assertRaises(FileExistsError):
                write_fixture(output)
            self.assertEqual(before, (output / "oracle.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
