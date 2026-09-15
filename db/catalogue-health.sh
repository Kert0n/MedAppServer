#!/bin/sh
# Схема без загруженного каталога не считается здоровой после пропущенного init-скрипта.
set -eu

manifest=/catalogue-check/form-vocabulary.tsv
current=/tmp/catalogue-current-forms.tsv
pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q \
    -c 'COPY (SELECT id::text, name FROM form_types ORDER BY name) TO STDOUT' > "$current"
cmp -s "$manifest" "$current"
test "$(psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    'SELECT (COUNT(*) > 0)::int FROM parsed_drugs')" = 1
test "$(psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    'SELECT (COUNT(*) > 0)::int FROM quantity_units')" = 1
