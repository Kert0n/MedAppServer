"""Быстрые отрицательные сценарии предзагрузочной проверки каталога."""

import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FORMS = (ROOT / "form-vocabulary.tsv").read_text(encoding="utf-8").splitlines()
UNIT = "b77b71ff-9e75-4621-be22-5ec134e6fb50"
DRUG = "00000000-0000-4000-8000-000000000001\tПроба\t\\N\t%s\t\\N\t%s\t\\N\t\\N\tНе указан\t\\N\t\\N\tf"


def dump(forms=FORMS, form_id=None, unit_id=UNIT, extra=""):
    chosen_form = form_id or FORMS[0].split("\t")[0]
    return "\n".join([
        "SET client_encoding = 'UTF8';",
        "COPY public.form_types (id, name) FROM stdin;",
        *forms, r"\.",
        "COPY public.quantity_units (id, name) FROM stdin;",
        f"{UNIT}\tшт", r"\.",
        "COPY public.parsed_drugs (id, name, name_lat, form_type_id, quantity, quantity_unit_id, active_substance, category, manufacturer, country, description, otc) FROM stdin;",
        DRUG % (chosen_form, unit_id), r"\.", extra,
    ])


class CatalogueGuardTest(unittest.TestCase):
    def check(self, content, good):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cleaned-init.sql"
            path.write_text(content, encoding="utf-8")
            result = subprocess.run(
                ["awk", "-f", str(ROOT / "validate-catalogue.awk"),
                 str(ROOT / "form-vocabulary.tsv"), str(path)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode == 0, good, result.stdout + result.stderr)

    def test_short_catalogue(self):
        self.check(dump(), True)

    def test_old_208_form_dump(self):
        extra = [f"00000000-0000-4000-8000-{n:012d}\tподробная форма {n}"
                 for n in range(190)]
        self.check(dump(forms=FORMS + extra), False)

    def test_changed_canonical_id_or_name(self):
        self.check(dump(forms=[FORMS[0].replace("аэрозоль", "пена")] + FORMS[1:]), False)

    def test_broken_reference(self):
        self.check(dump(form_id="00000000-0000-4000-8000-000000000999"), False)
        self.check(dump(unit_id="00000000-0000-4000-8000-000000000999"), False)

    def test_missing_form_or_drugs(self):
        self.check(dump(forms=FORMS[:-1]), False)
        self.check(dump().replace(DRUG % (FORMS[0].split("\t")[0], UNIT), ""), False)

    def test_unexpected_sql(self):
        self.check(dump(extra="DROP TABLE public.form_types;"), False)


if __name__ == "__main__":
    unittest.main()
