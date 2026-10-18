# Состояние проекта LocalNewsAssistant

Редакция документа: 2026-10-17, MSK (UTC+3); точное время не фиксируется.
Контрольная точка документации: ROOM-FILE-27D-VERIFIED.
Основание: сохранённый полный контекст; принятые локальные коммиты 6d36ebedfebcfd48acb894238579e692e51e6fc6 (SUMMARY) и e5b1ae69753e9001272e988c2a86b54806431f3b (документальный); локальный checkpoint-коммит 41f42b2b27ae567e70083ebb5e0731f544c54953 (Room in-memory foundation); журналы 27/27A/27B/27C/27D; явная приёмка и разрешение checkpoint 28 в сопровождающем сообщении пользователя от 2026-10-17.

## 1. Как читать этот документ

Это точка восстановления контекста, не свидетельство нового запуска приложения. Редактор не проверял свежий GitHub HEAD. Основание новых фактов — предоставленные пользователем журналы шагов 27D/28 с разрешением приёмки/коммита и фактическими ответами Git/ADB.

Технический объём подэтапа файловой базы данных Room принят пользователем, локальный checkpoint-коммит создаётся в этом шаге по прямому поручению пользователя. Кодовый этап Room продолжается; качество LLM не принято; модельные эксперименты остаются на паузе.

Типы сведений:
- Подтверждено журналом: имеется соответствующий вывод инструмента.
- Проверено разбором исходника: прочитан фактический код, но это не самостоятельный запуск.
- Исторический результат: относится к прежнему указанному состоянию/артефакту.
- Сообщено пользователем/агентом: обозначенный отчёт, не независимая проверка.
- Проектное решение: определяет направление, не доказывает реализацию.
- План: будущая работа, не выполненная функция.
- Неизвестно: достаточного подтверждения нет.

Дата редакции не равна дате проверки поведения. Код, hash и commit сами по себе не доказывают прохождения тестов. При расхождении источников устанавливается конкретная причина без нового общего аудита.

## 2. Продукт и границы проекта

Создаётся самостоятельное Android-приложение для преимущественно локального анализа новостей: получение сообщений из разрешённых источников, нормализация, хранение, поиск, отбор контекста, локальная генерация и проверка отчётов со ссылками на источники.

Целевые возможности: выбор и импорт/получение совместимой модели, подключение Telegram-аккаунта, выбор источников, темы и инструкции, задачи и расписания, сводки, устранение дублей, поиск, отслеживание информации и уведомления.

Это целевой продукт, а не перечень уже готовых функций. Сейчас подтверждён технический прототип, а не завершённый анализатор новостей. Полный маршрут и границы этапов находятся в ROADMAP.md; правила архитектуры — в DECISIONS.md.

## 3. Последняя известная исходная точка Git

| Поле | Последнее доступное значение | Основание и граница |
| --- | --- | --- |
| Локальный каталог | /Users/a1/AndroidStudioProjects/LocalNewsAssistant | Предыдущие журналы и отчёт агента |
| Ветка | main | Вывод Git шага 28 |
| Base HEAD перед Room File Checkpoint 28 | 41f42b2b27ae567e70083ebb5e0731f544c54953 | git rev-parse HEAD в шаге 28 |
| Заголовок base HEAD | Add device-tested synthetic Room storage foundation | Вывод Git шагов 26/27; кодовой checkpoint synthetic storage — 41f42b2 |
| Состав staged для Checkpoint 28 | Ровно 4 файла из раздела allowlist | `git add -- NewsDatabaseFactory.kt NewsFilePersistenceInstrumentedTest.kt PROJECT_STATE.md ROADMAP.md` |
| Рабочее дерево в конце шага 28 | Чистое | Пустой git status --short --untracked-files=all после commit 28 |
| Индекс в конце шага 28 | Пуст | Пустой git diff --cached --name-only |
| Последний сообщённый push | Пользователь сообщил о самостоятельном push после шага 24 | Удалённый SHA инструментом не сверялся; Room еще не отправлялся |
| GitHub HEAD / удалённая ветка | Свежая проверка не выполнялась | Локальный commit не доказывает push |
| Push шага 28 | Не выполнялся по инструкции и отчёту | Не объявлять commit доступным в GitHub |

### 3.1. Состав локального checkpoint-коммита 28

```text
app/src/main/java/com/example/localnewsassistant/data/local/NewsDatabaseFactory.kt
app/src/androidTest/java/com/example/localnewsassistant/NewsFilePersistenceInstrumentedTest.kt
docs/PROJECT_STATE.md
docs/ROADMAP.md
```

Текст commit header и body:
```text
Add file-backed Room persistence verification

Add a noBackupFilesDir Room database factory and instrumented file close/reopen tests. Record 8 passing persistence tests and 8 passing in-memory regression tests on POCO F3. Process death, backup restore, archive UI and search remain unverified.
```

### 3.2. Контрольные значения файлов подэтапа 27/27D

| Файл | SHA-256 в шаге 27D/28 | Статус |
| --- | --- | --- |
| NewsSummaryOrchestrator.kt | 492702fd18195a106e4f4cebc37d403ba9bcc63926d0a65673545d7e5288b91e | Сохранен без изменений |
| ModelImportViewModel.kt | a897218a76477724cfda02cd270704a29c38dbb0de7ec2b63c8b1f2aa6f39c5a | Сохранен без изменений |
| ModelImportViewModelTest.kt | 1ecd547c64e595c314e36eb342c120a0cfc78094d4301602152cdc68c368711a | Сохранен без изменений |
| docs/DECISIONS.md | bfe2febeff8b7b706d0d3752ae52c982b463cf984bca0d097858d289a7adbec9 | Сохранен без изменений |
| NewsStorageInstrumentedTest.kt | 29bfaf13a568a3c19bd0d51d434334cbc2f53aa6fc10449e0709a5c44959e152 | Сохранен без изменений |
| StoredNewsEntity.kt | 4d5ff26d2227f4e826ac29a66e8b7b476f30275fbc8440b7fcb0e27faece4a7b | Сохранен без изменений |
| NewsDao.kt | a4c37affee257f96420c88de2ea3bbc8f669c448943ff39893bc38a967ecf646 | Сохранен без изменений |
| NewsDatabase.kt | bfca4fac22694d87296c094068172335b9fd4ab8fef32a00faec67f2c9b36a8c | Сохранен без изменений |
| SyntheticNewsStore.kt | 00bc0795e43c1f8a3357296ed3ac6fed9dd7d3409463d8d40f74ff829b1580a3 | Сохранен без изменений |
| NewsDatabaseFactory.kt | 60428153e98de59df696e9f37c39ee7492af433ab33cc7e11902b48c4e496687 | Новый файл, закоммичен |
| NewsFilePersistenceInstrumentedTest.kt | abf7aab8a41b3adb905309361dfa7c2dcd37b52d8a2297b2b6822aff4589753d | Новый файл, закоммичен |

## 4. Последняя известная конфигурация

| Параметр | Значение | Источник в проекте |
| --- | --- | --- |
| Application ID | com.example.localnewsassistant | app/build.gradle.kts |
| Compile SDK / Target SDK | 37 / 37 | app/build.gradle.kts |
| Min SDK | 26 | app/build.gradle.kts |
| AGP | 9.4.1 | gradle/libs.versions.toml |
| Kotlin | 2.2.10 | gradle/libs.versions.toml |
| KSP | 2.3.12 | gradle/libs.versions.toml |
| Room | 2.8.5 | gradle/libs.versions.toml |
| Gradle Wrapper | 9.6.0 | gradle/wrapper/gradle-wrapper.properties |
| Compose BOM | 2026.02.01 | gradle/libs.versions.toml |
| LiteRT-LM Android | 0.16.1 | gradle/libs.versions.toml |

Тестовое устройство: POCO F3 (M2012K11AG), Android 13 / API 33, serial 495755ca.

## 5. Состояние функциональных блоков

| Блок | Последнее известное состояние | Принятие, фиксация и ограничения |
| --- | --- | --- |
| Каркас приложения и экран прототипа | Исторически реализованы и запускались на физическом устройстве | Полный набор продуктовых экранов не объявляется готовым |
| Локальный импорт модели и метаданные | Реализованы по предоставленным материалам | Последняя принятая контрольная точка импорта связана со сводкой по 3a6c442 |
| SUMMARY lifecycle | Реализован, технически проверен и закоммичен в 6d36ebe | Push не выполнялся; качество LLM не принято |
| Room: фабрика и файловые close/reopen тесты | Реализованы, собраны и прошли 8+8 instrumented-тестов на POCO F3; явно приняты пользователем 2026-10-17 | Приняты 8 persistence + 8 in-memory тестов. Не проверены process death, перезагрузка ОС, power-loss, backup restore, production UI, поиск/FTS и Telegram |
| Бот, TDLib и реальные источники | Запланированы | Сбор реальных сообщений и Telegram не подтверждены |
| Embeddings, фоновые задачи, дополнительные коннекторы | Запланированы | Не считать реализованными |

## 6. Проверки и происхождение результатов подэтапа 27/27D

### 6.1. Автоматические результаты сборки и тестов

- **JVM Unit Tests (`:app:testDebugUnitTest`):** `110 passed, 0 skipped, 0 failed` (из шага 25A).
- **Сборка Debug APK (`:app:assembleDebug`):** `BUILD SUCCESSFUL` (`app-debug.apk`, 60.7 МБ, SHA-256: `185c8ab1dfd5cff76bcd8651393d2bf58ff6c8bc9e650c11ba76d3396f73d758`).
- **Сборка Test APK (`:app:assembleDebugAndroidTest`):** `BUILD SUCCESSFUL` (`app-debug-androidTest.apk`, 1.1 МБ, SHA-256: `0be3cb3ab745bd35bf375a21a534f03a7456f626a76a36393707f83145b2cde1`).
- **Линтер (`:app:lintDebug`):** `BUILD SUCCESSFUL` (0 ошибок).

### 6.2. Прогон на физическом устройстве POCO F3 (495755ca, Android 13 / API 33)

1. **`NewsFilePersistenceInstrumentedTest` (8 тестов, время 0.717s):** `OK (8 tests)` — **PASSED**.
   - Сценарий A: Создание файла базы под `noBackupFilesDir`, проверка совпадения пути SQLite.
   - Сценарий B: Запись `test_news.json`, `close()`, создание нового экземпляра `db2`, проверка совпадения всех полей.
   - Сценарий C: Переоткрытие, повторная запись без дублей, обновление `N001` на месте, сохранение `publishedAt` и других полей.
   - Сценарий D: Независимое хранение `dataset-A` и `dataset-B` с одинаковым `messageId` («N001») после переоткрытия.
   - Сценарий E: Изоляция двух тестовых БД, удаление первой БД не затрагивает данные второй БД.
   - Сценарий F: Отклонение опасных/недопустимых имен файлов до вызова Room.
   - Сценарий G: Guards очистки отклоняют `news.db` и не зарегистрированные тестовые имена.
   - Сценарий H: Ошибка `close()` не вызывает удаление файлов БД и сохраняет handle в отслеживаемом реестре.
2. **`NewsStorageInstrumentedTest` (8 тестов, время 0.469s):** `OK (8 tests)` — **PASSED**.
   - Внутрипамятная регрессионная проверка 8 сценариев пред-валидации и транзакций.

**Итого 16 instrumented тестов (8+8) прошли на физическом устройстве.**

## 7. Ближайшая последовательность

1. Локальный коммит Checkpoint 28 фиксирует 4 файла (фабрика, файловый тест и документы PROJECT_STATE/ROADMAP). Push не выполняется.
2. Следующее кодовое направление — production-owned файловая база данных, управление её жизненным циклом и проверка согласованного чтения, отдельно от UI, поиска/FTS, Telegram и LLM.
3. Модельные эксперименты и оценка качества сводок остаются на паузе.
