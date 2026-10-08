# Diario de entrenamiento

App Android para llevar un diario de entrenamiento: una entrada por día con texto (Markdown), fotos y vídeos. Todo se guarda en local, sin nube.

## Instalar

Descarga el último `diario-entrenamiento-N.apk` desde **Releases** en el móvil y ábrelo. Android pedirá permiso para instalar apps de origen desconocido.

Cada push a `main` compila un APK nuevo con GitHub Actions.

## Dónde se guardan los datos

Al abrir la app por primera vez eliges una carpeta (por ejemplo `Documentos/Entrenamiento`). La app escribe allí:

```
Entrenamiento/
  2026/10/08/
    entrada.json
    media/
      foto-1.jpg
      video-1.mp4
      video-1.jpg      ← miniatura
```

La carpeta es tuya: si desinstalas la app no se borra, y puedes sincronizarla (Syncthing) o copiarla.

### `entrada.json`

```json
{
  "version": 1,
  "fecha": "2026-10-08",
  "modificado": "2026-10-08T19:42:10+02:00",
  "contenido": "## Técnica\nTrabajo de **piernas**…\n\n![](media/foto-1.jpg)",
  "videos": [
    { "titulo": "Forma completa", "archivo": "media/video-1.mp4", "miniatura": "media/video-1.jpg" }
  ],
  "duracion": 90,
  "tags": ["forma", "sanda"]
}
```

## Copias de seguridad (ZIP)

Menú ⋮ → **Exportar copia**: todo el diario o solo lo cambiado desde la última exportación. El ZIP tiene la misma estructura de carpetas más un `manifest.json`.

Menú ⋮ → **Importar copia**: las entradas nuevas se añaden directamente. Si un día ya existe, la app pregunta: mantener la actual, usar la importada o combinar las dos.

## Medios

- Las fotos se reducen a 2048 px por el lado mayor (JPEG 85 %).
- Los vídeos se recomprimen con el lado corto a 720 px (H.264). Si falla, se guarda el original.

## Firma del APK

El repo es público, así que la clave de firma no está en el código. Se guarda en dos secrets del repo:

- `KEYSTORE_BASE64`: el archivo `diario.keystore` en base64
- `KEYSTORE_PASSWORD`: su contraseña

Usa siempre la misma clave: así cada APK nuevo se instala encima del anterior. Sin los secrets, el APK se firma con una clave temporal y habría que desinstalar para actualizar (los datos de la carpeta no se pierden).

## Compilar en local

Android Studio (Ladybug o posterior) → abrir la carpeta del proyecto. O bien:

```
./gradlew assembleDebug
```

## Sailfish OS

De momento, el APK funciona en Sailfish mediante Android AppSupport. El formato de datos es abierto para que una futura app nativa (QML) lea la misma carpeta o los mismos ZIP.
