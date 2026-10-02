# Idle Manager for Evolution X (Android 17)

Порт Idle Manager из ветки `bka` Evolution X. Исходники хранятся в отдельном
проекте `packages/features/IdleManager` и компилируются внутри `SystemUI` и
`com.android.settings`. Отдельный APK не создаётся.

## Состав

- `systemui/src/`: сканирование после выключения экрана, применение правил,
  статистика, список защищённых пакетов и запуск через `CoreStartable`.
  Режим Full Kill также наблюдает за завершением последнего процесса UID и
  переводит пакет в системное состояние force-stop независимо от экрана.
- `settings/src/`: экран выбора приложений и правил.
- `settings/res/`: строки интерфейса, включая переводы, перенесённые из
  `packages/apps/Evolver` и `packages/apps/Settings`.
- `Android.bp`: Soong filegroup для обоих модулей.
- `UPSTREAM.md`: исходные ревизии и отличия порта.

## Подключение

Manifest добавляет проект из ветки `cnb` по пути `packages/features/IdleManager`
и linkfile `settings/res` в `packages/apps/Settings/idle-manager-res`.

В основных проектах нужны небольшие подключения:

1. `Settings-core` включает `:evolution-idle-manager-settings-srcs` и
   `idle-manager-res`. В `res/xml/power_usage_summary.xml` размещён пункт
   «Idle Manager», а фрагмент разрешён в `SettingsGateway`.
2. `SystemUI-core` включает `:evolution-idle-manager-systemui-srcs`, а
   `SystemUIModule` включает `IdleManagerModule`. `IdleManagerStartable`
   подписывается на `WakefulnessLifecycle` и запускает менеджер при засыпании.
3. `SystemUI/AndroidManifest.xml` запрашивает права на изменение standby bucket
   и управление процессами. Ключи `Settings.Secure` сохранены с прежними
   строковыми значениями для совместимости с настройками Android 16.

Пункт «Trigger on Sleep Mode» исключён: в ветке `cnb` нет Sleep Mode, на который
он опирался. Остальные настройки и формат JSON сохранены.

## Сборка

В настроенном дереве Android 17:

```sh
source build/envsetup.sh
lunch evolution_fairlady-userdebug
m SettingsGoogle SystemUIGoogle
```

На устройстве проверяйте выбор приложений, все четыре действия, выключение
и пробуждение экрана, отключение менеджера, историю действий и поведение
после перезапуска SystemUI. Журнал движка: `logcat -s LunarisIdleManager`.

После ручного запуска приложение в режиме Full Kill работает до следующего
завершения всех его процессов. Включение экрана не снимает force-stop;
приложение возобновляется только после действия пользователя, которое снимает
системное состояние stopped. Выключение Idle Manager не снимает уже
установленный force-stop: для этого нужно открыть приложение вручную.

## Лицензия

Исходные заголовки Lunaris AOSP сохранены. Код распространяется под Apache 2.0;
см. [LICENSE](LICENSE).
