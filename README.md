# SnowSeek ❄

Неофициальный открытый Android-клиент с нативным интерфейсом для дальнейшего исследования интеграции DeepSeek. **Не связан с DeepSeek и не одобрен им.** Android 8.0+ (API 26).

**Текущая версия — локальная демонстрация. Ответы не поступают от DeepSeek.** DeepThink и Search моделируются на устройстве; настоящий интернет-поиск отсутствует. Вход на официальном сайте не подключает нативный чат. Платный Developer API не используется.

## Что реализовано

- Kotlin, Jetpack Compose и Material 3, MVVM, Coroutines/Flow.
- Приветствие, история диалогов, создание и удаление чатов с подтверждением.
- Отправка, потоковая демонстрационная генерация, остановка и сохранение частичного ответа.
- Локальное сохранение истории между запусками. Атомарная запись в приватном каталоге приложения; ошибка чтения не подменяется пустой историей.
- Markdown: заголовки, абзацы, списки, цитаты, жирный и моноширинный текст, блоки кода. Подсветка распространённых ключевых слов Kotlin/Java/Python/JS/TS/JSON, копирование ответа/кода и выделение текста. Это ограниченный renderer, не полный CommonMark; HTML не исполняется, Markdown-ссылки отображаются как текст.
- Раскрываемая **демонстрационная** сводка размышлений, переключатели DeepThink/Search.
- Системная/светлая/тёмная тема, русский/английский язык, размер текста, переключатель анимаций. Настройки в DataStore.
- Официальный сайт `https://chat.deepseek.com/` в отдельном WebView: загрузка, ошибки, повтор, навигация назад, открытие браузера, очистка веб-сессии.
- Gradle Wrapper, unit-тесты, Android Lint, GitHub Actions с Debug APK и условным подписанным Release APK.
- Оригинальная иконка-снежинка; нет рекламы, аналитики или собственного сервера.

## Получить и установить APK

После загрузки проекта в [cneqq/SnowSeek](https://github.com/cneqq/SnowSeek) workflow находится в [Actions](https://github.com/cneqq/SnowSeek/actions/workflows/android.yml).

1. Запустите **Android** через **Run workflow**, либо сделайте push в `main` / откройте PR.
2. Дождитесь успешного задания `build`.
3. Скачайте архив артефакта `SnowSeek-debug`, распакуйте APK и установите на Android 8.0+.
4. При установке из файлового менеджера разрешите установку приложений для выбранного источника. Debug APK подписан отладочным ключом и предназначен для проверки.

Артефакты Actions могут требовать входа в GitHub. APK появляется только после успешного запуска workflow: наличие YAML не подтверждает сборку на GitHub.

При наличии Android Platform Tools:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Для обновления APK необходим тот же ключ подписи. Ключ отладки локального компьютера и временного CI runner может отличаться; при несовпадении подписей Android потребует удалить старое приложение, что удалит локальные данные. Для постоянных обновлений используйте один собственный release-ключ.

## Сборка локально

Точные версии закреплены в Gradle-файлах. Нужны полный JDK 17 или 21 (включая `javac` и `jlink`), Android SDK Platform 37.0 (`platforms;android-37.0`), Build Tools 36.0.0 и Platform Tools. Сокращённый JBR из некоторых Python IDE не содержит `jlink` и не подходит для Android-сборки. Android Studio должен поддерживать используемый AGP 9.4.x. Установите компоненты через SDK Manager и укажите SDK через `ANDROID_HOME` либо локальный, исключённый из Git `local.properties`.

Linux/macOS:

```sh
bash ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Результат: `app/build/outputs/apk/debug/app-debug.apk`. Первой сборке нужен интернет для Gradle, SDK и Maven-зависимостей. Wrapper проверяет SHA-256 дистрибутива Gradle.

## Архитектура и границы интеграции

```text
core/src/main/kotlin/dev/snowseek/core/   # модели, ChatProvider и демопровайдер
app/src/main/java/dev/snowseek/app/
  auth/                                 # официальный WebView и очистка сессии
  data/                                 # локальное атомарное хранилище
  settings/                             # DataStore
  ui/                                   # ViewModel, экраны, Markdown и темы
.github/workflows/android.yml
```

`ChatProvider` определяет пользователя, чаты, сообщения, отправку, поток и остановку. `MockChatProvider` работает полностью локально. `DeepSeekWebProvider` **отключён** и сообщает о недоступности, не выполняя сетевых запросов. Отдельный JVM-модуль позволяет тестировать жизненный цикл генерации без Android.

Этап 4 (исследование протокола) в этой версии не выполнялся. Внутренние `/api/v0/*`, SSE, HAR и чужая реализация не принимаются за стабильный API. Код проекта `kittors/deepseek-web-api` не заимствован. Перед реализацией реальной интеграции нужно отдельно подтвердить актуальность протокола, условия сервиса и совместимость авторизации. OkHttp не добавлен как неиспользуемая зависимость; он потребуется при подтверждённой нативной HTTP-интеграции.

WebView используется для официального сайта и авторизации. Нативные экраны чата самостоятельны. Приложение не определяет успешность веб-входа и не читает cookies, пароли или токены. Сессия сайта не передаётся в нативный провайдер.

## Авторизация и данные

- Пароли вводятся на официальном сайте. CAPTCHA и остальные проверки выполняются пользователем обычным способом. Обходов CAPTCHA/Proof-of-Work нет.
- JavaScript и DOM storage разрешены для работы сайта; JS bridge, файловый доступ и смешанный HTTP-контент не используются. Ошибки TLS не игнорируются.
- Сторонняя HTTPS-авторизация открывается в системном браузере по явному действию. Cookies браузера и WebView не общие: OAuth/SSO и некоторые CAPTCHA могут не работать. Можно пользоваться всем официальным сайтом в браузере.
- Обычная веб-сессия хранится средствами Android WebView. **Выход** в настройках удаляет cookies, WebStorage, кеш и HTTP-auth данные приложения, с ожиданием завершения удаления cookies. Это локальный выход, не отзыв всех сессий аккаунта на сервере.
- Демочаты хранятся в sandbox приложения, без отдельного прикладного шифрования. Они не являются секретным хранилищем: не вводите в демонстрацию пароли и ключи. Защита файлов зависит от защиты устройства и Android; root/разблокированное устройство вне этой модели.
- Облачный backup и перенос данных исключены манифестом и правилами backup. Удаление приложения удаляет историю. Логи запросов/учётных данных, аналитика и промежуточные серверы отсутствуют.
- Выход из веб-аккаунта сохраняет локальные демочаты; удалить диалог можно в истории. Данные сайта передаются непосредственно официальному сайту и его ресурсам средствами WebView.

## Release в GitHub Actions

Обычный Debug APK собирается без secrets. Подписанный релиз публикуется только для **push тега `v*`**, после успешного `build`, если настроены все четыре repository secrets:

| Secret | Значение |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 собственного keystore |
| `KEYSTORE_PASSWORD` | Пароль keystore |
| `KEY_ALIAS` | Alias ключа |
| `KEY_PASSWORD` | Пароль ключа |

Keystore декодируется только во временную папку runner, APK выравнивается и подписывается Android `apksigner`, подпись проверяется перед публикацией. Если хотя бы одного секрета нет, публикация пропускается; Debug APK остаётся доступным. Никогда не добавляйте keystore, `.env`, `local.properties`, cookies и пароли в репозиторий. Храните резервную копию release-ключа вне Git: без него обновления установленного приложения невозможны.

Для публичного распространения подписанного GPL-приложения предоставляйте соответствующий исходный код и инструкции сборки. Размещение workflow не запускает релиз само по себе; для публикации нужно отдельно создать и отправить тег.

## Проверки и ограничения

Локально проверено **2 ноября 2026 года**:

- Чистая сборка Android-модуля без build cache: `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` — успешно.
- **34 unit-теста** (22 core + 12 app), без ошибок и пропусков: жизненный цикл провайдера, сохранение и восстановление, отмена, конкуренция, отключённый веб-провайдер, правила URL, очистка веб-сессии и Markdown.
- Android Lint: **0 ошибок, 2 предупреждения**. `OldTargetApi`: target SDK 36 при compile SDK 37; обновление target требует отдельной проверки поведения на устройстве. `ObsoleteSdkInt`: локальная пустая папка `mipmap-anydpi-v26` после переноса иконки; в Git она не сохраняется.
- Debug APK: проверены подпись (`apksigner`), выравнивание (`zipalign`), package `dev.snowseek.app`, min SDK 26 и target SDK 36. Release APK собран **без подписи**, для установки нужна подпись собственным ключом.
- Workflow прошёл `actionlint`. Запуск GitHub Actions и публикация релиза не выполнялись.

CI повторяет unit-тесты, lint и сборку APK. При устаревших результатах Android resource tasks используйте `:app:clean --no-build-cache` перед сборкой.

Android-устройство или эмулятор не были подключены. Остаются проверки: ширина 320/360/412 dp; светлая/тёмная тема; увеличенный системный шрифт; длинные сообщения/код; клавиатура и прокрутка; stop во время генерации; перезапуск и восстановление; airplane mode; вход/CAPTCHA и выход с повторным открытием сайта. Компиляция и unit-тесты не доказывают работоспособность веб-входа или качество раскладки на устройстве.

Пока нет реальных ответов DeepSeek, полноценного интернет-поиска, перегенерации, вложений, изображений, экспорта, редактирования сообщений или специальных планшетных раскладок.

## Лицензии

SnowSeek распространяется под [GNU GPLv3](LICENSE). Информация о компонентах — в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Официальные логотипы DeepSeek не включены; название используется для обозначения совместимости и назначения проекта.

## English

SnowSeek is an **unofficial, open-source Android 8.0+ app**, not affiliated with DeepSeek. It currently offers a native **offline demo chat**, on-device conversation history, simulated streaming/DeepThink/Search, basic Markdown and code highlighting, RU/EN UI, themes and settings. Replies are not generated by DeepSeek.

The separate WebView opens the official DeepSeek website. You sign in there yourself; the app does not read credentials or transfer cookies into a native HTTP client. Website login does not connect the native chat. CAPTCHA/OAuth support depends on the website and Android WebView; use the system browser when needed. Sign out clears local website data and keeps demo conversations.

Build using JDK 17/21, Android SDK 37.0 and `bash ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (Windows: `gradlew.bat`). Install `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions uploads a Debug APK without secrets; signed releases require a pushed `v*` tag and all four signing secrets listed above. No release is published when secrets are incomplete.

Native DeepSeek integration is deliberately disabled until the private protocol and permitted integration are verified. No paid Developer API, CAPTCHA/PoW bypass, proxy server, tracking or advertisements. GPLv3; see LICENSE and third-party notices.

Verified locally on 2 November 2026: Debug and unsigned Release builds, all 34 unit tests, APK signature/alignment and workflow syntax. Android Lint reports no errors and two warnings (target SDK 36 and an empty local resource directory). No device/emulator validation or GitHub Actions run was performed; website login/CAPTCHA remains untested.
