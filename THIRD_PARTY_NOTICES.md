# Компоненты и лицензии

Исходный код SnowSeek — GNU GPLv3. Оригинальные иконка и оформление включены на тех же условиях. Не используется код сторонних неофициальных клиентов DeepSeek или официальная графика DeepSeek.

| Компонент | Лицензия | Источник |
| --- | --- | --- |
| Kotlin и стандартная библиотека | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| AndroidX: Compose, Material 3, Activity, Lifecycle, Core, DataStore | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Material Icons | Apache-2.0 | https://github.com/google/material-design-icons |
| Gradle Wrapper | Apache-2.0 | https://github.com/gradle/gradle |
| JUnit 4 (только тесты) | EPL-1.0 | https://github.com/junit-team/junit4 |
| Hamcrest (только тесты, транзитивно) | BSD-3-Clause | https://github.com/hamcrest/JavaHamcrest |

Apache-2.0 совместима с распространением объединённой работы под GPLv3 согласно GNU: https://www.gnu.org/licenses/license-list.html#apache2. Тестовые инструменты не включаются в APK. Android SDK/JDK являются инструментами сборки и не распространяются в репозитории.

Собственный ограниченный Markdown renderer и простая подсветка кода не используют сторонние Markdown-библиотеки. При добавлении зависимостей проверяйте их лицензии и обновляйте этот файл; данная таблица перечисляет прямые зависимости и известные тестовые транзитивные компоненты, а не заменяет полный отчёт по всем транзитивным артефактам.

Лицензионные тексты Apache-2.0 и GPLv3 находятся в `licenses/Apache-2.0.txt` и `LICENSE`. Сведения об авторах компонентов сохраняются в их исходных репозиториях и Maven metadata. Для поставки релиза проверяйте конкретные разрешённые версии и их NOTICE-файлы.
