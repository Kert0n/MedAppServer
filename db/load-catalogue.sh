#!/bin/sh
# Загружает только проверенный data-only справочник препаратов.
#
# Ожидаемый файл `/catalogue/cleaned-init.sql` создаётся rewrite-catalogue-dump.py и
# содержит COPY для form_types, quantity_units и parsed_drugs, затем ANALYZE. Таблицы уже
# должны быть созданы schema.sql. Каталог монтируется целиком, поскольку закрытые данные
# не хранятся в git и файл может отсутствовать.

set -eu

CATALOGUE=/catalogue/cleaned-init.sql

if [ ! -f "$CATALOGUE" ]; then
    if [ "${CATALOGUE_REQUIRED:-0}" = 1 ]; then
        echo "load-catalogue: $CATALOGUE не найден — запуск без справочника запрещён." >&2
        exit 1
    fi
    echo "load-catalogue: $CATALOGUE не найден — локальный каталог пропущен."
    exit 0
fi

# Source dump скраппера не является допустимым init-файлом: сначала преобразовать его в
# data-only формат, чтобы схема приложения оставалась единственным источником структуры.
if grep -q '^CREATE TABLE public\.drugs' "$CATALOGUE"; then
    echo "load-catalogue: $CATALOGUE — необработанная выгрузка скраппера." >&2
    echo "load-catalogue: примените db/rewrite-catalogue-dump.py и пересоздайте том." >&2
    exit 1
fi

awk -f /catalogue-check/validate-catalogue.awk \
    /catalogue-check/form-vocabulary.tsv "$CATALOGUE"

echo "load-catalogue: загружаю справочник из $CATALOGUE"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -q -f "$CATALOGUE"

echo "load-catalogue: готово"
