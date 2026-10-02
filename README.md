# Idle Manager for Evolution X (Android 17)

Порт Idle Manager из ветки `bka` Evolution X. Исходники хранятся в отдельном
проекте `packages/features/IdleManager` и компилируются внутри `SystemUI` и
`com.android.settings`. Отдельный APK не создаётся.

## Состав

- `systemui/src/`: две проверки после выключения экрана, применение правил,
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
он опирался. Политика ожидания задаётся глобально на вкладке приложений:
Balanced — 60 минут, Aggressive — 15 минут, Custom — от 5 до 240 минут.
В диалоге приложения выбирается только действие. Старые per-app policy и
timeout автоматически переносятся из первой записи в общую настройку;
приложения и их действия сохраняются.

За одну блокировку экрана сканирование запускается через 30 секунд и после
выбранного глобального порога бездействия. Затем цикл прекращается до следующей
блокировки. Наблюдатель за завершением процессов Full Kill продолжает работу
независимо от экрана и этих двух проверок.
При проверке Full Kill не пропускает приложение только потому, что у него
работает foreground service; остальные режимы сохраняют эту защиту.

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
Для проверки доставки таймеров в журнале должны появиться `Scan alarm delivered`
и `performIdleScan`; запись `Scan alarm set` означает только планирование.

После ручного запуска приложение в режиме Full Kill работает до следующего
завершения всех его процессов. Включение экрана не снимает force-stop;
приложение возобновляется только после действия пользователя, которое снимает
системное состояние stopped. Выключение Idle Manager не снимает уже
установленный force-stop: для этого нужно открыть приложение вручную.

## Лицензия

Исходные заголовки Lunaris AOSP сохранены. Код распространяется под Apache 2.0;
см. [LICENSE](LICENSE).
