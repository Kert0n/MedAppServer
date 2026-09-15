#!/usr/bin/env python3
"""Преобразует pg_dump справочника в data-only init-файл MedAppServer.

Вход содержит COPY таблиц `drugs`, `form_types` и `quantity_units` либо уже обработанный
data-only файл. Выход содержит только
COPY для `form_types`, `quantity_units`, `parsed_drugs` и завершающий ANALYZE. Структуру
таблиц всегда создаёт `db/schema.sql`.

    python3 db/rewrite-catalogue-dump.py init-scripts/cleaned-init.sql

Файл переписывается на месте, подробный data-only файл сохраняется с суффиксом
`.detailed.orig`. Повторный запуск на коротком словаре ничего не меняет.

Правила преобразования:

  * `id` вычисляется из `drug_id` через UUIDv5, поэтому одинаковые записи получают
    одинаковые идентификаторы в разных выгрузках.
  * `drug_id`, `form`, `dosage`, `url` отбрасываются: в сущности `VidalDrug` их нет. `form` —
    текстовый дубль нормализованного `form_type_id`.
  * отсутствующий `manufacturer` заменяется на `Не указан`, отсутствующий `otc` — на false;
  * справочники загружаются перед `parsed_drugs`, чтобы внешние ключи проверялись на COPY.
"""

import re
import shutil
import sys
import uuid
from pathlib import Path

# Фиксированное пространство имён для uuid5. Произвольная константа: смысл не в значении, а
# в том, что оно не меняется между прогонами.
NAMESPACE = uuid.UUID("6f2a1c48-0b3d-5e7a-9c14-2d8b5f3e7a10")

MANUFACTURER_FALLBACK = "Не указан"

COMMON_BASES = frozenset("""таблетки раствор капсулы порошок капли лиофилизат концентрат
мазь спрей гель суспензия крем сироп суппозитории гранулы аэрозоль настойка""".split())
OTHER_BASES = frozenset("""жидкость эмульсия экстракт масло пластырь драже эликсир
пастилки имплантат шампунь паста линимент лак сборы бальзам пленки сок пена система
газ губка дисперсия карандаш пилюли плитки резинка""".split())


def vocabulary():
    rows = [line.split("\t") for line in
            Path(__file__).with_name("form-vocabulary.tsv").read_text(encoding="utf-8").splitlines()]
    by_name = {name: identifier for identifier, name in rows}
    if len(rows) != 18 or set(by_name) != COMMON_BASES | {"другие"}:
        raise SystemExit("манифест короткого словаря повреждён")
    return by_name


def compact_forms(columns, rows, target):
    if columns != ["id", "name"]:
        raise SystemExit("структура form_types изменилась")
    original = {}
    for row in rows:
        identifier, name = row.split("\t")
        if identifier in original:
            raise SystemExit(f"повторный id формы: {identifier}")
        original[identifier] = name
    if len(rows) == 18 and {name: identifier for identifier, name in
                             (row.split("\t") for row in rows)} == target:
        return None, rows
    if len(rows) != 208:
        raise SystemExit(f"ожидалось 208 исходных форм, пришло {len(rows)}")
    generic = {name: identifier for identifier, name in
               (row.split("\t") for row in rows) if name in COMMON_BASES}
    if generic != {name: target[name] for name in COMMON_BASES}:
        raise SystemExit("идентификаторы базовых форм не совпадают с манифестом")
    replacement = {}
    for identifier, name in original.items():
        base = name.split()[0]
        if base not in COMMON_BASES | OTHER_BASES:
            raise SystemExit(f"неизвестная исходная форма: {name}")
        replacement[identifier] = target[base if base in COMMON_BASES else "другие"]
    compact = [f"{identifier}\t{name}" for name, identifier in sorted(target.items())]
    return replacement, compact


def replace_form_ids(columns, rows, replacement):
    at = columns.index("form_type_id")
    converted = []
    for row in rows:
        fields = row.split("\t")
        old = fields[at]
        if old != "\\N":
            if old not in replacement:
                raise SystemExit(f"препарат ссылается на неизвестную форму: {old}")
            fields[at] = replacement[old]
        converted.append("\t".join(fields))
    return converted

# Колонки parsed_drugs в том порядке, в каком их получит COPY. search_tsv отсутствует
# намеренно: колонка генерируемая, база считает её сама.
TARGET_COLUMNS = [
    "id", "name", "name_lat", "form_type_id", "quantity", "quantity_unit_id",
    "active_substance", "category", "manufacturer", "country", "description", "otc",
]

HEADER = """--
-- Справочник препаратов: данные для parsed_drugs, form_types и quantity_units.
--
-- Получен из выгрузки скраппера обработкой db/rewrite-catalogue-dump.py — см. её описание.
-- Структуру таблиц создаёт db/schema.sql; этот файл содержит только данные.
--
-- Файл в git не попадает: это закрытые данные.
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET client_min_messages = warning;
SET row_security = off;
"""

# Статистики после COPY нет, и планировщик выбирает последовательное сканирование вместо
# индексов поиска — на реальных данных это 47 мс против 2 мс на первых запросах, пока не
# придёт autovacuum.
FOOTER = """
ANALYZE public.form_types;
ANALYZE public.quantity_units;
ANALYZE public.parsed_drugs;
"""


def read_copy_blocks(lines):
    """Находит все COPY ... FROM stdin в дампе.

    Формат COPY TEXT экранирует табуляции и переводы строк внутри значений, поэтому строка
    данных всегда физическая строка файла, а разбиение по '\\t' безопасно.
    """
    blocks = {}
    i = 0
    while i < len(lines):
        match = re.match(r"^COPY public\.(\w+) \(([^)]*)\) FROM stdin;$", lines[i])
        if match:
            start = i + 1
            end = start
            while lines[end] != "\\.":
                end += 1
            blocks[match.group(1)] = (match.group(2).split(", "), lines[start:end])
            i = end
        i += 1
    return blocks


def render_copy(table, columns, rows):
    head = f"COPY public.{table} ({', '.join(columns)}) FROM stdin;"
    return "\n".join([head, *rows, "\\."]) + "\n"


def convert_drug_rows(columns, rows):
    at = {name: index for index, name in enumerate(columns)}
    seen = set()
    converted = []
    for row in rows:
        field = row.split("\t")

        drug_id = field[at["drug_id"]]
        if drug_id in seen:
            raise SystemExit(f"drug_id повторяется: {drug_id!r} — uuid5 дал бы коллизию ключа")
        seen.add(drug_id)

        manufacturer = field[at["manufacturer"]]
        otc = field[at["otc"]]
        converted.append("\t".join([
            str(uuid.uuid5(NAMESPACE, drug_id)),
            field[at["name"]],
            field[at["name_lat"]],
            field[at["form_type_id"]],
            field[at["quantity"]],
            field[at["quantity_unit_id"]],
            field[at["active_substance"]],
            field[at["category"]],
            MANUFACTURER_FALLBACK if manufacturer == "\\N" else manufacturer,
            field[at["country"]],
            field[at["description"]],
            "f" if otc == "\\N" else otc,
        ]))
    return converted


def main():
    if len(sys.argv) != 2:
        raise SystemExit(f"использование: {sys.argv[0]} <дамп.sql>")

    path = Path(sys.argv[1])
    if not path.is_file():
        raise SystemExit(f"{path} не найден")

    lines = path.read_text(encoding="utf-8").split("\n")
    blocks = read_copy_blocks(lines)

    for table in ("form_types", "quantity_units"):
        if table not in blocks:
            raise SystemExit(f"в дампе нет данных {table} — на них ссылается parsed_drugs")

    target = vocabulary()
    replacement, compact = compact_forms(*blocks["form_types"], target)
    if replacement is None and "drugs" not in blocks:
        print(f"{path}: короткий словарь уже установлен, ничего не делаю")
        return

    if "drugs" in blocks:
        drug_columns, drug_rows = blocks["drugs"]
        missing = [c for c in TARGET_COLUMNS if c != "id" and c not in drug_columns]
        if missing:
            raise SystemExit(f"в выгрузке нет колонок {missing} — структура дампа изменилась")
        parsed = convert_drug_rows(drug_columns, drug_rows)
    elif "parsed_drugs" in blocks:
        drug_columns, parsed = blocks["parsed_drugs"]
        if drug_columns != TARGET_COLUMNS:
            raise SystemExit("структура parsed_drugs изменилась")
    else:
        raise SystemExit("в дампе нет препаратов")
    if replacement is not None:
        parsed = replace_form_ids(TARGET_COLUMNS, parsed, replacement)

    out = [HEADER]
    for table in ("form_types", "quantity_units"):
        columns, rows = blocks[table]
        if table == "form_types":
            rows = compact
        out.append(render_copy(table, columns, rows))
    out.append(render_copy("parsed_drugs", TARGET_COLUMNS, parsed))
    out.append(FOOTER)

    backup = path.with_suffix(path.suffix + (".orig" if "drugs" in blocks else ".detailed.orig"))
    if not backup.exists():
        shutil.copy2(path, backup)
    result = "\n".join(out)
    staged = path.with_suffix(path.suffix + ".staged")
    staged.write_text(result, encoding="utf-8")
    staged.replace(path)

    print(f"{path}: {len(parsed)} препаратов, {len(compact)} форм выпуска, "
          f"{len(blocks['quantity_units'][1])} единиц измерения")
    print(f"исходная выгрузка сохранена в {backup.name}")


if __name__ == "__main__":
    main()
