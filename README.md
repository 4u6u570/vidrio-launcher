# Vidrio

Launcher liviano para Android TV (Android 7 o más nuevo), con estilo de vidrio esmerilado al modo de Apple. El APK pesa unos 32 KB: está escrito en Java, sin bibliotecas y sin Gradle.

- Reloj, fecha y estado de la red arriba; botón de Ajustes.
- **Favoritos** y una grilla con todas las apps. Las apps de TV muestran su banner.
- **OK** abre la app. **Mantener OK** (o la tecla Menú) abre el menú de la app: agregar o quitar de favoritos, reordenar, ver la información.
- Configuración se abre encima del launcher, que queda visible detrás.

## Compilar

Hacen falta el SDK de Android (build-tools 34 y la plataforma android-34) y un JDK 17.

```sh
./build.sh                                  # genera vidrio.apk
VERSION_CODE=2 VERSION_NAME=1.1 ./build.sh  # para una versión nueva
```

La primera vez, `build.sh` crea la clave de firma en `firma/`, que queda fuera de git. **Respaldala**: sin esa clave, una versión nueva no se instala encima de la anterior.

## Instalar y dejarlo como pantalla de inicio

```sh
adb install -r vidrio.apk
adb shell cmd package set-home-activity com.augusto.vidrio/.MainActivity
```
