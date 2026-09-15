# Проверка data-only каталога до psql. Первый аргумент — фиксированный короткий словарь,
# второй — закрытый дамп. Не доверяем даже заголовкам COPY и SQL вне разрешённого набора.
BEGIN { FS = "\t" }
NR == FNR {
    if (NF != 2 || $1 !~ /^[0-9a-f-]+$/ || $2 == "" || $1 in expected) bad("манифест форм повреждён")
    expected[$1] = $2
    manifest_count++
    next
}
function bad(message) {
    print "load-catalogue: " message > "/dev/stderr"
    failed = 1
    exit 1
}
/^COPY public\./ {
    if (section != "") bad("незаконченный COPY")
    if ($0 == "COPY public.form_types (id, name) FROM stdin;") section = "forms"
    else if ($0 == "COPY public.quantity_units (id, name) FROM stdin;") section = "units"
    else if ($0 == "COPY public.parsed_drugs (id, name, name_lat, form_type_id, quantity, quantity_unit_id, active_substance, category, manufacturer, country, description, otc) FROM stdin;") section = "drugs"
    else bad("неожиданная таблица или колонки COPY")
    if (section in visited) bad("повторный COPY")
    visited[section] = 1
    next
}
section != "" && $0 == "\\." { section = ""; next }
section == "forms" {
    if (NF != 2 || !($1 in expected) || expected[$1] != $2 || $1 in seen_forms) bad("форма не совпадает с коротким словарём")
    seen_forms[$1] = 1
    form_count++
    next
}
section == "units" {
    if (NF != 2 || $1 !~ /^[0-9a-f-]+$/ || $1 in units) bad("единица повреждена или повторяется")
    units[$1] = 1
    unit_count++
    next
}
section == "drugs" {
    if (NF != 12 || ($4 != "\\N" && !($4 in expected)) || ($6 != "\\N" && !($6 in units))) bad("препарат в строке " FNR " ссылается на неизвестную форму или единицу: " $4 " / " $6)
    drug_count++
    next
}
/^--/ || /^$/ || /^SET [a-z_]+ = [^;]*;$/ || /^ANALYZE public\.(form_types|quantity_units|parsed_drugs);$/ { next }
{ bad("посторонний SQL в data-only дампе") }
END {
    if (failed) exit 1
    if (manifest_count != 18 || form_count != manifest_count || unit_count == 0 || drug_count == 0 || section != "" || length(visited) != 3)
        bad("неполный каталог: нужны 18 форм, единицы и препараты")
    for (id in expected) if (!(id in seen_forms)) bad("в дампе отсутствует форма " id)
    print "load-catalogue: проверено " form_count " форм, " unit_count " единиц, " drug_count " препаратов"
}
