# SnowSeek — план реализации

**Цель:** этапы 1, 2, 3 и 5 пользовательского ТЗ: Android 8+, нативный демонстрационный чат, официальный сайт в WebView, APK и CI.

**Архитектура:** `:core` — Kotlin/JVM модели, ChatProvider, потоковый MockChatProvider и отключённый DeepSeekWebProvider; `:app` — Compose Material 3, MVVM, DataStore и WebView. Ни серверов, ни Developer API, ни извлечения cookies/токенов. Имя SnowSeek, package `dev.snowseek.app`, репозиторий https://github.com/cneqq/SnowSeek.

**Решения:** история демонстрации хранится локально в приватном каталоге приложения с атомарной записью; резервное копирование выключено. Веб-сессией управляет Android WebView. Вход на сайте не означает подключение нативного провайдера. DeepThink/Search явно моделируются локально. Ограниченный собственный Markdown renderer с выделением и подсветкой распространённых языков, без исполнения HTML. GPLv3.

## Контракт ядра (`dev.snowseek.core`)

Все модели сериализуемые kotlinx.serialization:
- `MessageRole { USER, ASSISTANT }`, `MessageStatus { COMPLETE, GENERATING, STOPPED, ERROR }`.
- `ChatMessage(id: String, role: MessageRole, text: String = "", reasoning: String = "", status: MessageStatus = COMPLETE, createdAt: Long = System.currentTimeMillis())`.
- `ChatSession(id: String, title: String, messages: List<ChatMessage> = emptyList(), updatedAt: Long = System.currentTimeMillis())`.
- `ChatOptions(deepThink: Boolean = false, search: Boolean = false, language: String = "ru")`.
- `UserProfile(displayName: String, isDemo: Boolean)`.
- `ProviderCapabilities(streaming: Boolean, deepThink: Boolean, search: Boolean, regeneration: Boolean)`.
- `ChatStore`: `suspend fun load(): List<ChatSession>`, `suspend fun save(chats: List<ChatSession>)`.
- `ChatProvider`: `val chats: StateFlow<List<ChatSession>>`, `val capabilities: ProviderCapabilities`, `suspend fun initialize()`, `suspend fun getCurrentUser(): UserProfile`, `suspend fun getChats(): List<ChatSession>`, `suspend fun createChat(): ChatSession`, `suspend fun deleteChat(chatId: String)`, `suspend fun getMessages(chatId: String): List<ChatMessage>`, `fun sendMessage(chatId: String, prompt: String, options: ChatOptions = ChatOptions()): Flow<ChatMessage>`, `suspend fun stopGeneration(chatId: String)`, `suspend fun clear()`.
- `MockChatProvider(store: ChatStore, tokenDelayMillis: Long = 24L)`.
- `DeepSeekWebProvider`: все операции недоступны с понятной ошибкой; никаких HTTP-запросов.

## Работы и проверки

- [x] 1. Core: реализован контракт и поведенческие тесты пустого ввода, потоковых ответов, остановки/отмены, конкурентной отправки, удаления, перезапуска и отключённой интеграции. `:core:test`: 22 теста прошли.
- [x] 2. App: приветствие, drawer с созданием/удалением, чат и остановка, Markdown/код/копирование, темы/языки/размер/анимации, обработка загрузки/ошибок, постоянное локальное хранение. `:app:assembleDebug` и `:app:lintDebug` прошли.
- [x] 3. WebView: HTTPS-навигация, обычная проверка TLS, нет JS bridge и перехвата учётных данных; прогресс/ошибка/повтор, внешний браузер, явный выход с очисткой cookies и WebStorage. Тесты URL и жизненного цикла очистки прошли; реальный вход требует устройства.
- [x] 4. Build/CI: согласованные зависимости, Gradle Wrapper с checksum, debug build/test/lint/artifact, подписанный release только при всех четырёх secrets и теге v*. Debug и неподписанный Release собраны; workflow проверен actionlint. Публикация не запускалась.
- [x] 5. Документация и финальная проверка: README RU/EN с ограничениями, LICENSE GPLv3, зависимости/лицензии, review с исправлением найденных проблем; готовый Debug APK с проверенной подписью и выравниванием.

**Результат 2 ноября 2026:** 34 unit-теста без ошибок и пропусков; чистая Android-сборка без build cache успешна; lint — 0 ошибок, 2 предупреждения (target SDK 36 и пустая локальная папка ресурсов). Устройство/эмулятор не подключены: визуальная проверка, установка, вход/CAPTCHA и сохранение реальной веб-сессии остаются непроверенными. GitHub Actions не запускался.

## Особое внимание

Отмена не должна оставлять GENERATING после перезапуска; два запроса не должны смешивать ответы; удалённый чат не должен возвращаться; сбой записи не должен терять известную историю; выход должен очищать веб-сессию до нового входа. Длинный текст, крупный шрифт, узкие экраны и клавиатура требуют проверки на устройстве; компиляция не подтверждает внешний вид или прохождение CAPTCHA.

Коммиты, push, GitHub Releases и внешние изменения не выполняются без соответствующей команды пользователя. Работа ведётся в предоставленной пустой рабочей папке, дополнительный worktree не нужен.
