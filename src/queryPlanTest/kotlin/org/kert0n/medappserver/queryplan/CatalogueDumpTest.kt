package org.kert0n.medappserver.queryplan

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.db.tables.DrugTemplates
import org.testcontainers.containers.BindMode
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Справочник доезжает из файла до выдачи поиска.
 *
 * Единственное место, где проверяется путь загрузки целиком: `db/schema.sql` создаёт таблицы,
 * `db/load-catalogue.sh` заливает данные, `CatalogueStore` их находит. До этого теста путь не
 * был покрыт ничем, и это стоило пустого справочника в проде — `init-scripts/cleaned-init.sql`
 * оказался необработанной выгрузкой скраппера, загрузчик её отверг, инициализация Postgres
 * оборвалась, а `restart: unless-stopped` поднял базу второй раз уже мимо init-скриптов.
 *
 * Почему этого не видели остальные: `CatalogueSearchTest` начинает с `deleteAll()` и вставляет
 * свои шесть записей, `CatalogueServiceTest` работает на моке, `CatalogueQueryPlanTest` меряет
 * планы на `LargeFixture` из `generate_series`. Содержимое настоящего справочника для них
 * невидимо по построению, и ни один из них никогда не утверждал, что он непуст.
 *
 * Контейнер здесь свой, а не общий `TestcontainersConfiguration`: тому схему строит `TestSchema`
 * из объектов `Table`, то есть мимо `db/schema.sql` и мимо загрузчика — проверять на нём было бы
 * нечего. Spring-контекст не нужен: `CatalogueStore` не имеет зависимостей и берёт транзакцию из
 * `TransactionManager`.
 */
class CatalogueDumpTest {

    /**
     * Настоящий дамп: единственная проверка, что в прод поедет непустой справочник.
     *
     * В CI пропускается и иначе не может: справочник закрытый, в git его нет. Признать это
     * честнее, чем изображать покрытие, поэтому здесь `assumeTrue`, а не молчаливое согласие с
     * пустой базой.
     */
    @Test
    fun `настоящий справочник грузится и находится поиском`() {
        assumeTrue(
            Files.exists(REAL_CATALOGUE.resolve(DUMP_NAME)),
            "$REAL_CATALOGUE/$DUMP_NAME отсутствует: справочник закрытый и в git не попадает, " +
                "поэтому в CI этой проверки нет. Локально она обязательна перед развёртыванием."
        )

        // Без Docker и за миллисекунды — то самое условие, на котором стоит загрузчик. Ради
        // одной этой строки тест уже оправдан: именно она поймала бы исходную поломку.
        assertTrue(
            isDataOnly(REAL_CATALOGUE.resolve(DUMP_NAME)),
            "$DUMP_NAME — необработанная выгрузка скраппера. db/load-catalogue.sh откажется её " +
                "грузить, инициализация Postgres оборвётся, и после перезапуска база поднимется " +
                "с пустым справочником. Примените db/rewrite-catalogue-dump.py."
        )

        // Число строк берётся из файла, а короткий словарь — из независимого манифеста.
        val expected = copiedRows(REAL_CATALOGUE.resolve(DUMP_NAME))
        assertEquals(18L, expected.getValue("form_types"), "в production не должен вернуться подробный словарь")

        withCatalogue(REAL_CATALOGUE) { catalogue ->
            assertEquals(
                expected.getValue("parsed_drugs"),
                DrugTemplates.selectAll().count(),
                "в parsed_drugs доехали не все строки COPY"
            )
            // Ровно то, что отдают GET /v1/form-types и GET /v1/quantity-units.
            assertEquals(
                expected.getValue("form_types"),
                catalogue.formTypes().size.toLong(),
                "словарь форм выпуска загружен не полностью"
            )
            assertEquals(
                expected.getValue("quantity_units"),
                catalogue.quantityUnits().size.toLong(),
                "словарь единиц измерения загружен не полностью"
            )

            // Название набрано целиком и точно — такая запись обязана быть первой: за это
            // отвечает первая ступень порядка в CatalogueStore.
            assertEquals(
                "Аспирин",
                catalogue.searchTemplates("аспирин", listOf("аспирин"), 10).firstOrNull()?.name,
                "точное название не поднялось на первое место"
            )

            // Опечатка проверяет не только ранжирование: триграммы из кириллицы извлекаются
            // лишь при локали из POSTGRES_INITDB_ARGS, и на настоящих названиях это видно.
            assertTrue(
                catalogue.searchTemplates("аспирни", listOf("аспирни"), 10)
                    .any { it.name.startsWith("Аспирин") },
                "поиск с опечаткой не нашёл препарат — похоже на локаль без кириллических триграмм"
            )

            // Цена склейки четырёх полей видна только на настоящем объёме: у «Норадреналина»
            // вещество «норэпинефрин» и производитель «Аспектус Фарма» стыкуются в участок,
            // похожий на «асприн» сильнее самого слова «аспирин». На нестрогой метрике он и
            // стоял первым.
            assertEquals(
                "Аспирин",
                catalogue.searchTemplates("асприн", listOf("асприн"), 10).firstOrNull()?.name,
                "опечатку обогнала склейка соседних полей"
            )

            // Действующее вещество повторяется у сотен карточек: без ступени «совпало название»
            // первым оказывается алфавитно ближайший препарат, а не одноимённый.
            assertEquals(
                "Парацетамол",
                catalogue.searchTemplates("парацетомол", listOf("парацетомол"), 10).firstOrNull()?.name,
                "по составу нашлось раньше, чем по названию"
            )

            // Несколько неполных слов: название и производитель вместе.
            assertTrue(
                catalogue.searchTemplates("аспир байер", listOf("аспир", "байер"), 10)
                    .any { it.name.startsWith("Аспирин") },
                "несколько неполных слов перестали находить запись"
            )
        }
    }

    /**
     * Postgres, поднятый ровно как в `compose.yaml`.
     *
     * Три монтирования, те же пути внутри контейнера и та же локаль. Пересказывать инициализацию
     * своими операторами бессмысленно: проверяется как раз она, а не наше представление о ней.
     * Успешный старт контейнера — это и есть утверждение «init не оборвался»: упади загрузчик,
     * entrypoint Postgres вышел бы с ошибкой и `start()` не дождался бы готовности.
     */
    private fun <T> withCatalogue(catalogue: Path, block: JdbcTransaction.(CatalogueStore) -> T): T {
        val postgres = PostgreSQLContainer(DockerImageName.parse(IMAGE))
            // Та же локаль, что в compose и в TestcontainersConfiguration: в локали C кириллица
            // буквой не считается, и триграммы из русских названий не извлекаются вовсе.
            .withEnv("POSTGRES_INITDB_ARGS", INITDB_ARGS)
            .withEnv("CATALOGUE_REQUIRED", "1")
            .withFileSystemBind(absolute("db/schema.sql"), "$INITDB_DIR/01-schema.sql", BindMode.READ_ONLY)
            .withFileSystemBind(absolute("db/load-catalogue.sh"), "$INITDB_DIR/02-load-catalogue.sh", BindMode.READ_ONLY)
            .withFileSystemBind(absolute("db/validate-catalogue.awk"), "/catalogue-check/validate-catalogue.awk", BindMode.READ_ONLY)
            .withFileSystemBind(absolute("db/form-vocabulary.tsv"), "/catalogue-check/form-vocabulary.tsv", BindMode.READ_ONLY)
            .withFileSystemBind(catalogue.toAbsolutePath().toString(), "/catalogue", BindMode.READ_ONLY)
            // Восемнадцать тысяч записей заливаются в таблицу с двумя GIN-индексами, и следом
            // идёт ANALYZE: умолчания в минуту здесь не хватает.
            .withStartupTimeout(STARTUP_TIMEOUT)

        postgres.start()
        return try {
            val database = Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = postgres.username,
                password = postgres.password
            )
            transaction(database) { block(CatalogueStore()) }
        } finally {
            postgres.stop()
        }
    }

    /**
     * Условие из `db/load-catalogue.sh`, слово в слово: `grep -q '^CREATE TABLE public\.drugs'`.
     *
     * Копия здесь сознательная. Загрузчик применяет своё условие внутри инициализации продовой
     * базы, то есть в момент, когда узнавать об этом уже поздно; тот же вопрос, заданный файлу
     * заранее, стоит миллисекунды.
     */
    private fun isDataOnly(dump: Path): Boolean =
        Files.newBufferedReader(dump).useLines { lines -> lines.none { it.startsWith(RAW_MARKER) } }

    /** Сколько строк несёт каждый блок `COPY` — ожидания теста берутся отсюда, а не из головы. */
    private fun copiedRows(dump: Path): Map<String, Long> {
        val counts = mutableMapOf<String, Long>()
        Files.newBufferedReader(dump).useLines { lines ->
            var table: String? = null
            var rows = 0L
            lines.forEach { line ->
                val current = table
                when {
                    current == null -> COPY_HEADER.matchEntire(line)?.let {
                        table = it.groupValues[1]
                        rows = 0
                    }

                    line == COPY_END -> {
                        counts[current] = rows
                        table = null
                    }

                    else -> rows++
                }
            }
        }
        return counts
    }

    /** Тесты запускаются из каталога проекта — как и `SchemaSnapshotTest`, который на это же и опирается. */
    private fun absolute(path: String): String = Path.of(path).toAbsolutePath().toString()

    private companion object {
        const val IMAGE = "postgres:18.3-trixie"
        const val INITDB_ARGS = "--encoding=UTF8 --lc-ctype=en_US.utf8 --lc-collate=en_US.utf8"
        const val INITDB_DIR = "/docker-entrypoint-initdb.d"

        /** Имя задано загрузчиком: он читает `/catalogue/cleaned-init.sql` и никакое другое. */
        const val DUMP_NAME = "cleaned-init.sql"
        const val RAW_MARKER = "CREATE TABLE public.drugs"
        const val COPY_END = "\\."

        val REAL_CATALOGUE: Path = Path.of("init-scripts")
        val COPY_HEADER = Regex("""COPY public\.(\w+) \(.*\) FROM stdin;""")
        val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}
