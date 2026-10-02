# Upstream revisions

Sources were taken from the official Evolution X `bka` branch:

| Project | Revision | Source files |
| --- | --- | --- |
| `frameworks_base` | `21fefcddfa4c816801fe01c8e2d4bab5a610691f` | `packages/SystemUI/src/com/android/systemui/lunaris/LunarisIdleManager.java`, `LunarisIdleConstants.java` |
| `packages_apps_Evolver` | `52f54396feb635a4913cd75027a7a2693c475f67` | `src/org/evolution/settings/fragments/miscellaneous/IdleManagerSettings.kt`, `res/values*/evolution_strings.xml` |
| `packages_apps_Settings` | `b734e12d88f9091963839d4d5c0a8e39d4662d07` | `res/xml/power_usage_summary.xml` and translations in `res/values*/evolution_strings.xml` |

## Adaptations for `cnb`

- The engine and settings screen are compiled from a separate project through
  filegroups, following the structure of `packages/features/Routines`.
- Startup from `CentralSurfacesImpl` was replaced with a `CoreStartable` and a
  `WakefulnessLifecycle` observer. The current screen state is checked when
  SystemUI starts.
- The dependency on Sleep Mode, which is unavailable in `cnb`, was removed.
- UI strings were moved into the module's resource directory.

When updating the port, compare the source files against the revisions listed
above and check the settings format and availability of the Android 17 APIs used.
