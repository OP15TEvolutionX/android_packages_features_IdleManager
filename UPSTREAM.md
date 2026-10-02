# Исходные ревизии

Взяты исходники официальной ветки `bka` Evolution X:

| Проект | Ревизия | Исходные файлы |
| --- | --- | --- |
| `frameworks_base` | `21fefcddfa4c816801fe01c8e2d4bab5a610691f` | `packages/SystemUI/src/com/android/systemui/lunaris/LunarisIdleManager.java`, `LunarisIdleConstants.java` |
| `packages_apps_Evolver` | `52f54396feb635a4913cd75027a7a2693c475f67` | `src/org/evolution/settings/fragments/miscellaneous/IdleManagerSettings.kt`, `res/values*/evolution_strings.xml` |
| `packages_apps_Settings` | `b734e12d88f9091963839d4d5c0a8e39d4662d07` | `res/xml/power_usage_summary.xml` и переводы `res/values*/evolution_strings.xml` |

## Изменения для `cnb`

- Движок и экран компилируются из отдельного проекта через filegroup, как
  `packages/features/Routines`.
- Запуск из `CentralSurfacesImpl` заменён на `CoreStartable` и наблюдатель
  `WakefulnessLifecycle`; при старте SystemUI проверяется текущее состояние
  экрана.
- Удалена зависимость от Sleep Mode, отсутствующего в `cnb`.
- Строки интерфейса перенесены в ресурсный каталог модуля.

При обновлении порта сравнивайте исходные файлы с указанными ревизиями,
проверяйте формат настроек и доступность используемых API Android 17.
