# Контрольная точка: Модуль валидации входного JSON (Data Input Pipeline)

**Базовый коммит:** `02d9f0ebbf82bbeff19683b679da0af79722de89`
**Ветка:** `main`
**Дата проведения испытаний:** 15 октября 2026 года

---

## 1. Назначение и архитектура производственного пути

Разработан и верифицирован изолированный модуль загрузки и валидации новостного набора данных, работающий до вызова генерации LLM.

### Цепочка вызова в приложение:
`ModelImportViewModel.runNewsSummary()` → `Dispatchers.IO` → `SyntheticNewsAssetLoader.loadAndValidateAsset(app, "test_news.json")`:

1. **`BoundedAssetReader.readBoundedStreamBytes`:** Ограниченное чтение из `InputStream` потока `assets.open("test_news.json")` с лимитом не более `51 200` байт (`MAX_ASSET_BYTES = 51200`). Чтение более 50 КБ блокируется до загрузки в память с выбросом `IllegalArgumentException`.
2. **`BoundedAssetReader.decodeUtf8Strict`:** Декодирование байтов в строку UTF-8 с вызовом `CodingErrorAction.REPORT` при сбоях кодировки.
3. **`AndroidJsonReaderParser.verifyNoSemicolonsOutsideStrings`:** Однопроходный пред-сканер, блокирующий точки с запятой `;` вне двойных кавычек.
4. **`AndroidJsonReaderParser.parseJson`:** Строгий разбор структуры через `android.util.JsonReader` с `isLenient = false`. Проверяет строгие типы токенов (`synthetic: BOOLEAN`, `datasetId: STRING`, `messages: ARRAY`), отклоняет дублирующиеся и неизвестные ключи, требует `END_DOCUMENT` после корневого объекта.
5. **`SyntheticNewsValidator.validateDataset`:** Чистая JVM-валидация дат ISO-8601 через `java.time.OffsetDateTime.parse(..., ISO_OFFSET_DATE_TIME)`, формата ID `Regex("^N\\d{3}$")`, уникальности ID и лимитов длин текста (до 1000 символов на новость, до 3000 символов суммарно, от 1 до 10 новостей в наборе).

---

## 2. Обоснование и особенности пред-сканера точек с запятой

AOSP `android.util.JsonReader.nextInObject()` в режиме `isLenient = false` обрабатывает `;` наравне с `,` при разделении пар ключей объекта.
Для устранения этой особенности реализован `verifyNoSemicolonsOutsideStrings`:
- Точки с запятой вне строк (между полями или элементами массива) выбрасывают `IllegalArgumentException`.
- Точки с запятой внутри кавычек (включая экранированные кавычки `\"`) сохраняются разрешёнными.
- Сам разбор структуры объектов и массивов выполняется `android.util.JsonReader`.

---

## 3. Физические испытания на телефоне (Android Instrumented Tests)

Испытания проведены на подключённом устройстве POCO F3 (`495755ca`, Android 13).

* **Метод установки:** Ручная установка без флага `-g` (`adb install -r`) во избежание сбоев выдачи разрешения:
  - `app-debug.apk` → Success
  - `app-debug-androidTest.apk` → Success
* **Регистрация runner:** `com.example.localnewsassistant.test/androidx.test.runner.AndroidJUnitRunner`
* **Запуск целевого класса:**
  ```bash
  adb -s 495755ca shell am instrument -w -e class com.example.localnewsassistant.SyntheticNewsInputInstrumentedTest com.example.localnewsassistant.test/androidx.test.runner.AndroidJUnitRunner
  ```
* **Результат прогона на телефоне:**
  ```text
  com.example.localnewsassistant.SyntheticNewsInputInstrumentedTest:...................

  Time: 0,168

  OK (19 tests)
  ```
  - Проведено тестов: **19**
  - Успешно: **19**
  - Ошибок / пропусков: **0**

---

## 4. Результаты проверок сборки и тестов

* **JVM юнит-тесты (`./gradlew :app:testDebugUnitTest`):** **83 passed, 0 failed**.
* **Сборка APK приложения и тестов (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`):** **BUILD SUCCESSFUL**.
* **Линтер (`./gradlew :app:lintDebug`):** **BUILD SUCCESSFUL**.
* **`git diff --check`:** 0 замечаний.

---

## 5. Сохранение экспериментальных изменений и известные ограничения

1. **Сохранённый состав:** В данный контрольный снимок также входят изменения экспериментальных инструкций `NewsSummaryPromptBuilder.kt` (PROMPT-COMPACT-11, 1509 символов, SHA-256 `f13ed522...`) и сэмплинга `LlmInferenceService.kt` (`topK = 1`). Данные эксперименты **не объявляются принятыми по качеству**, инференс нейросетей переведён на **паузу**.
2. **Границы парсера:** Пред-сканер точки с запятой решает конкретную проблему `JsonReader` вне строк и не заявляет общую RFC 8259 строгость для всех остальных форматов JSON.
3. **Сохранность файлов:** Asset `test_news.json` (`1420` байт, SHA-256 `aa5492b2...`) и скопированные модели на телефоне не затрагивались.
