# SnowSeek ❄

Неофициальный открытый Android-клиент с нативным интерфейсом для дальнейшего исследования интеграции DeepSeek. **Не связан с DeepSeek и не одобрен им.** Android 8.0+ (API 26).

**0.2.0 — экспериментальная интеграция бесплатной веб-версии DeepSeek с нативным чатом.** Нажмите «Войти в DeepSeek» (или откройте вход в настройках), завершите авторизацию/CAPTCHA на официальном сайте и нажмите **«Подключить SnowSeek»**. Приложение проверит сессию отдельным HTTP-запросом и загрузит реальные диалоги. До подключения доступен прежний локальный демочат. Платный Developer API не используется. Полный цикл на телефоне требует практической проверки.

## Что реализовано

- Kotlin, Jetpack Compose и Material 3, MVVM, Coroutines/Flow.
- Приветствие, история диалогов, создание и удаление чатов с подтверждением.
- Отправка и потоковые ответы через выбранный провайдер, остановка получения и отображение частичного ответа.
- Локальная история демочата сохраняется атомарно. Реальные диалоги и сообщения загружаются из аккаунта DeepSeek; отдельного постоянного кеша реальных сообщений на устройстве нет.
- Markdown: заголовки, абзацы, списки, цитаты, жирный и моноширинный текст, блоки кода. Подсветка распространённых ключевых слов Kotlin/Java/Python/JS/TS/JSON, копирование ответа/кода и выделение текста. Это ограниченный renderer, не полный CommonMark; HTML не исполняется, Markdown-ссылки отображаются как текст.
- Раскрываемые размышления и переключатели DeepThink/Search; в деморежиме они моделируются локально.
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

В Debug-сборке **0.2.3** на экране входа есть кнопка с жуком **«Отладка WebView»**. Панель открывается поверх страницы и содержит Console, Network и WebView. Запись начинается при открытии сайта и продолжается при закрытой панели. Для воспроизведения очистите журнал, закройте панель, обновите страницу, дождитесь проблемы и нажмите **«Копировать всё»** — копируются все три вкладки вместе с версией движка и текущим URL.

Network показывает только доступные callbacks WebView: запросы ресурсов, сообщённые HTTP-ошибки, сетевые/TLS-ошибки и блокировки приложения. `HTTP ?` означает, что статус не предоставлен; события загрузки не означают успешный ответ. Заголовки и тела запросов/ответов не собираются, повторных HTTP-запросов для диагностики нет. Значения query-параметров, фрагменты URL и распознанные секреты скрываются до записи. Console содержит произвольный текст сайта: перед отправкой проверьте скопированный текст. Журнал ограничен 600 записями и 256 000 символами, хранится только в памяти текущего экрана, не пишется в Logcat/файлы и не отправляется автоматически. В Release запись и кнопка панели выключены; удалённая отладка WebView остаётся выключенной.

В **0.2.4** добавлены подробные `WINDOW_ERROR` и `UNHANDLED_REJECTION` со стеком, если движок его предоставляет. Обработчики устанавливаются в начале официального документа через AndroidX WebKit; при отсутствии поддержки используется поздняя установка с явным предупреждением о пропущенных ранних ошибках. Сообщения до 65 536 символов сохраняются частями `DETAIL`; превышение отмечается, старые записи удаляются при заполнении общего буфера. Произвольные объекты Promise не сериализуются: доступны только строковые name/message/stack. Чужие фреймы и ошибки с ограничениями cross-origin могут не раскрывать стек.

Во вкладке WebView кнопка **«Снимок DOM сейчас»** собирает геометрию и computed styles контейнеров, форм/полей и их предков: display, visibility, opacity, position/z-index, pointer-events, overflow, transform, clip-path, цвета. Счётчики разделяют элементы с ненулевым размером, условно отображаемые и попадающие в viewport. `forms.loginHeuristic` — признак кандидатов, а не доказательство наличия видимой формы входа. `targets.hitTests` и `viewportHitTests` используют `elementsFromPoint()`; `overlays` включает крупные позиционированные элементы, в том числе прозрачные. Это выборочная проверка геометрии/CSS, не анализ пикселей: pseudo-elements, closed shadow roots и содержимое сторонних фреймов не исследуются.

Наблюдение работает автоматически; видимый consent-баннер распознаётся эвристически по кнопкам/окружающему тексту, который в журнал не передаётся. `COOKIE_BEFORE_CLICK` фиксирует состояние до обработчика клика сайта; при исчезновении появляются `COOKIE_BEFORE_DISAPPEAR`, `COOKIE_AFTER_DISAPPEAR`, `COOKIE_DOM_DIFF` и последующие снимки. Если баннер не распознан или уже закрыт, сделайте ручные снимки до и после. Значения полей, HTML, атрибуты с данными и текст чатов не собираются. Условия WebView и проверки сайта не меняются; ошибка `TIP Error` сама по себе ещё не устанавливает причину пустого экрана.

**0.2.5 — коррекция схлопнутой высоты входа.** WebView размещён в контейнере Compose с определённой оставшейся высотой (`weight` → `fillMaxSize`) и Android `MATCH_PARENT`. `NATIVE_LAYOUT` показывает размеры Compose, самого WebView и цепочки Android-родителей в пикселях. Только при положительных размерах этих контейнеров и viewport, существующих полях входа и нулевой высоте обоих `html/body` применяется `height:100%; min-height:100%` к `html`, `body` и непосредственному дочернему контейнеру body с формой. Фикс ограничен официальными `/` и `/sign_in`; поля, цвета, отступы, overflow, opacity и защитные проверки не меняются. `HEIGHT_CORRECTION` содержит результат и размеры до/после; неудачная коррекция откатывается. CSS-проценты следуют viewport при повороте и клавиатуре. Все прежние вкладки и ручной снимок DOM сохранены. Отображение реальной формы проверяется на телефоне; тесты JS проверяют условия применения и область изменения, а не работу браузерного CSS-движка.

При наличии Android Platform Tools:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Для обновления APK необходим тот же ключ подписи. Ключ отладки локального компьютера и временного CI runner может отличаться; при несовпадении подписей Android потребует удалить старое приложение, что удалит локальные данные. Для постоянных обновлений используйте один собственный release-ключ.

**Авторизация Google: безопасный браузерный вариант.** На стартовом экране выберите «Войти через Google». SnowSeek откроет официальный сайт DeepSeek через AndroidX Browser Custom Tabs, с цветами приложения. На сайте выберите Google и пройдите его обычные проверки. Настраивать открытие ссылок Android не нужно. Google-сессия остаётся в браузере: можно пользоваться веб-чатами DeepSeek, но она **не подключается к нативным чатам SnowSeek**. Закрытие вкладки не считается успешным входом. При отсутствии браузера показывается ошибка; ошибки сети и отмена Google обрабатываются официальным сайтом/браузером.

Полностью автоматический Google-вход в нативные чаты в исследованном протоколе не реализован. DeepSeek возвращает `/authorized` на собственном домене. SnowSeek не владеет этим доменом и не может подтвердить его App Links. [Auth Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) также требует Digital Asset Links для HTTPS callback; custom scheme требует поддержки сервера. Проверенный контракт callback/state/PKCE для SnowSeek не найден. Наличие ожидающей попытки и одноразового nonce само по себе не связывает результат с исходным входом и не исключает подмену аккаунта. Поэтому старый exported-обработчик `/authorized`, ручная настройка ссылок, обмен внешнего nonce и подтверждение подключения удалены. Подмена redirect URI и извлечение cookies браузера не используются. Это ограничение нельзя устранить только изменениями Android-клиента; необходим поддерживаемый механизм со стороны DeepSeek. Прямой запрос страницы `/sign_in` при исследовании получил HTTP 403; вывод основан также на существующей реализации и ранее сохранённом официальном frontend, а не на пройденном Google-входе.

Для нативных чатов выберите **«Другой способ для нативного чата»** и войдите на официальном сайте внутри приложения без Google OAuth. SnowSeek автоматически обнаруживает сессию собственного WebView, проверяет профиль через `/users/current`, шифрует подтверждённый токен в Android Keystore и открывает чаты. Кнопка «Подключить SnowSeek» больше не нужна. Проверка выполняется только на активном экране и точном origin `https://chat.deepseek.com`; переход, отмена, выход и очистка сессии блокируют незавершённое сохранение. После ошибки одинаковый токен не проверяется бесконечно: нажмите «Повторить» или войдите заново. При следующем запуске сохранённая сессия проверяется провайдером перед загрузкой чатов. При ошибке сети она не стирается. Выход очищает данные приложения, но не сессию внешнего браузера.

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

`ChatProvider` определяет пользователя, чаты, сообщения, отправку, поток и остановку. `MockChatProvider` работает локально. После проверки и сохранения сессии используется `DeepSeekWebProvider`: профиль, список/создание/удаление чатов, история, отправка, SSE и THINK/RESPONSE. HTTP-транспорт использует платформенный HTTPS-клиент Android с проверкой TLS и запрещёнными редиректами. Ошибки и неоднозначный результат отправки не вызывают автоматическую повторную отправку.

Интеграция основана на ранее [проверенных HAR и HTTP/SSE-форматах](docs/web-integration-research.md). Отчёт фиксирует состояние исследования до реализации 0.2.0. Внутренние `/api/v0/*` не являются стабильным публичным API и могут измениться. Для каждой генерации запрашивается свежая PoW-задача и исполняется официальный Web Worker в WebView; обходов и собственного PoW-алгоритма нет. При недоступном worker или проверке сервиса показывается ошибка, ответ не подменяется демонстрацией.

WebView используется для официального входа и штатного PoW. Нативные экраны чата самостоятельны. После входа внутри приложения SnowSeek автоматически читает веб-токен из официального origin, проверяет его через `/users/current` и сохраняет зашифрованным AES-GCM с ключом Android Keystore. Cookies официального origin используются для запросов к тому же origin. Пароль приложение не читает, JS bridge не добавляется, токены и содержимое запросов не логируются.

## Авторизация и данные

- Пароли вводятся на официальном сайте. CAPTCHA и остальные проверки выполняются пользователем обычным способом. Обходов CAPTCHA/Proof-of-Work нет.
- JavaScript и DOM storage разрешены для работы сайта; JS bridge, файловый доступ и смешанный HTTP-контент не используются. Ошибки TLS не игнорируются.
- Google-вход использует Custom Tabs и остаётся на официальном сайте без импорта сессии. Cookies браузера и WebView не общие; прочие OAuth/SSO не реализованы.
- Обычная веб-сессия хранится средствами Android WebView. **Выход** удаляет зашифрованный нативный токен, cookies, WebStorage, кеш и HTTP-auth данные приложения, с ожиданием завершения удаления cookies. Это локальный выход, не отзыв всех сессий аккаунта на сервере.
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

Локально проверено **3 ноября 2026 года** после изменения авторизации:

- `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — успешно; **60 unit-тестов** (29 core + 31 app), без ошибок и пропусков. Пять новых проверок охватывают проверку перед сохранением, повтор, конкуренцию, сетевые ошибки/недействительную сессию, отмену и повторную проверку после пересоздания контроллера. Это проверки логики; они не заменяют запуск приложения и реальную проверку Keystore/восстановления на устройстве.
- **14 JS-проверок** диагностики и высоты WebView — успешно. `actionlint` — успешно.
- Android Lint: **0 ошибок, 6 предупреждений** (target SDK, доступные обновления Browser/WebKit, пустой локальный каталог ресурсов и рекомендации KTX для синхронного SharedPreferences).
- Debug APK: проверены подпись, выравнивание, min SDK 26, target SDK 36. В merged manifest отсутствуют App Links DeepSeek и экспортируемый OAuth receiver: внешний некорректный, подменённый или повторный callback не может подключить аккаунт.
- Устройства/эмулятора нет. Настоящий Google-вход, браузерные ошибки сети/отмена, мобильные размеры/темы, реальный перезапуск и выход требуют ручной проверки на Android. Поддерживаемый автоматический перенос Google-сессии в нативные чаты не подтверждён и не реализован.
- Тесты удалены **из индекса Git**, добавлены исключения для `src/test` и `src/androidTest`; существующие файлы и новые локальные проверки сохранены на диске. История Git не переписана. CI выполняет lint и сборку; локальные тесты в checkout других пользователей отсутствуют.

Предыдущая проверка до этих изменений:

Локально проверено **2 ноября 2026 года**:

- Чистая сборка Android-модуля без build cache: `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` — успешно.
- **34 unit-теста** (22 core + 12 app), без ошибок и пропусков: жизненный цикл провайдера, сохранение и восстановление, отмена, конкуренция, отключённый веб-провайдер, правила URL, очистка веб-сессии и Markdown.
- Android Lint: **0 ошибок, 2 предупреждения**. `OldTargetApi`: target SDK 36 при compile SDK 37; обновление target требует отдельной проверки поведения на устройстве. `ObsoleteSdkInt`: локальная пустая папка `mipmap-anydpi-v26` после переноса иконки; в Git она не сохраняется.
- Debug APK: проверены подпись (`apksigner`), выравнивание (`zipalign`), package `dev.snowseek.app`, min SDK 26 и target SDK 36. Release APK собран **без подписи**, для установки нужна подпись собственным ключом.
- Workflow прошёл `actionlint`. Запуск GitHub Actions и публикация релиза не выполнялись.

По просьбе владельца исходники тестов исключены из Git, но сохранены локально. CI выполняет lint и сборку APK; unit-тесты и JS-проверки запускаются локально при наличии файлов `src/test`. При устаревших результатах Android resource tasks используйте `:app:clean --no-build-cache` перед сборкой.

Android-устройство или эмулятор не были подключены. Остаются проверки: ширина 320/360/412 dp; светлая/тёмная тема; увеличенный системный шрифт; длинные сообщения/код; клавиатура и прокрутка; stop во время генерации; перезапуск и восстановление; airplane mode; вход/CAPTCHA и выход с повторным открытием сайта. Компиляция и unit-тесты не доказывают работоспособность веб-входа или качество раскладки на устройстве.

Ограничения 0.2.0: каталог загружает до 100 последних чатов; перегенерация, вложения, изображения, экспорт, редактирование и планшетные раскладки не реализованы. Кнопка Stop прекращает получение SSE; удалённая генерация может продолжиться, поскольку серверная команда остановки не подтверждена. Перед повтором после ошибки откройте диалог заново для сверки истории. Google/OAuth и CAPTCHA могут ограничиваться самим WebView; сессия внешнего браузера автоматически обратно не переносится. Если приватный протокол или официальный PoW worker изменится, потребуется обновление клиента.

## Лицензии

SnowSeek распространяется под [GNU GPLv3](LICENSE). Информация о компонентах — в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Официальные логотипы DeepSeek не включены; название используется для обозначения совместимости и назначения проекта.

## English

SnowSeek is an **unofficial, open-source Android 8.0+ app**, not affiliated with DeepSeek. Version 0.2.0 connects the native Compose chat to a user's free DeepSeek web account: conversation list/history, create/delete, messages and SSE reasoning/answers. The offline demo remains available before connecting. Markdown, code highlighting, RU/EN UI, themes and settings are preserved.

For native chats, choose **Another method for native chat** and sign in on the official website inside the app; the session connects automatically. Google sign-in uses Custom Tabs and stays on the official website: automatic transfer to native chats is unavailable in the inspected protocol. No Android link settings or external callback receiver are used. The app verifies the web token through native HTTPS and encrypts it with an Android Keystore key. Passwords are never read; no credentials are logged. Official-origin cookies are used only for that origin. The official Web Worker performs fresh PoW checks; CAPTCHA/WAF/PoW are not bypassed. Sign out clears the native token and website data, keeping demo conversations. External-browser sign-in is not automatically transferred back to WebView.

Build using JDK 17/21, Android SDK 37.0 and `bash ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (Windows: `gradlew.bat`). Install `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions uploads a Debug APK without secrets; signed releases require a pushed `v*` tag and all four signing secrets listed above. No release is published when secrets are incomplete.

The private web integration is experimental and requires phone testing. Up to 100 conversations are loaded; Stop closes local stream reception and does not guarantee stopping server generation. Failed/uncertain sends are never automatically repeated. Website protection and worker changes may require updating the app. No paid Developer API, proxy server, tracking or advertisements. GPLv3; see LICENSE and third-party notices.

Verified locally on 2 November 2026: Debug and unsigned Release builds, all 34 unit tests, APK signature/alignment and workflow syntax. Android Lint reports no errors and two warnings (target SDK 36 and an empty local resource directory). No device/emulator validation or GitHub Actions run was performed; website login/CAPTCHA remains untested.
