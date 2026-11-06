# SGCPuimovil

Base Android Kotlin del Sistema de Gestión Comercial Porcino. Consume la misma API que la web. Sprint 1: acceso online, renovación de sesión y pantalla «Mis entregas» vacía.

## Equipo

- Dario Haziel Tambo Parra
- Angel Sebastian Eguez Mur
- Sofia Cuellar Roca
- Victor Manuel Manrique Arancivia

## Requisitos y compilación

- JDK completo 21 (no un runtime reducido sin `jlink`).
- Android SDK Platform 36 y Build Tools 36.0.0.
- Gradle Wrapper 9.1.0 incluido; Android Gradle Plugin 9.0.1 con Kotlin integrado.
- Android 8.0/API 26 o superior. `targetSdk` y `compileSdk` 36 son decisiones técnicas de esta implementación, no versiones exigidas por el docente.

Configura el SDK desde Android Studio o con `sdk.dir` en `local.properties`, archivo excluido de Git.

```bash
./gradlew assembleDebug testDebugUnitTest --no-daemon --max-workers=1
```

Windows: `gradlew.bat assembleDebug testDebugUnitTest`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

La configuración limita Gradle a un trabajador y 768 MB de heap; evita ejecutar un emulador pesado en la laptop de 4 GB. Usa un teléfono físico si está disponible.

## Conectar con la API

En el acceso escribe la URL base, terminada en `/api/v1/`:

- Emulador Android hacia API en el equipo anfitrión: `http://10.0.2.2:5081/api/v1/`.
- Teléfono físico: `http://IP_DEL_EQUIPO_API:5081/api/v1/` en la misma red; la API debe escuchar en esa interfaz y el firewall permitir la conexión.
- Servidor desplegado: dirección HTTPS con certificado válido.

También puede configurarse al compilar con `-PSGCP_API_URL=https://servidor/api/v1/`. La edición local se conserva en preferencias. Cambiarla no comparte credenciales de forma automática: el usuario debe iniciar sesión de nuevo.

El APK debug permite HTTP para pruebas en red local. Release solo permite HTTPS. No desactivar la validación de certificados.

## Funciones

- Acceso contra `POST auth/login` con cuenta ENTREGA o ADMINISTRACION.
- Token cifrado mediante AES-GCM y clave de Android Keystore; no se guarda la contraseña.
- Verificación de sesión mediante `GET auth/yo`; sesión vencida requiere nuevo acceso.
- Cierre mediante API, sin borrar almacenamiento de otras funciones.
- Estado «No hay reparto descargado». No simula asignaciones ni operaciones offline.
- Mensajes de error de conexión y reintento.

No incluye todavía Room, descarga de entregas, evidencias, captura sin conexión ni sincronización; corresponden al Sprint 3. No requiere acceso a la base de datos desde el teléfono: todo pasa por la API.

La ejecución y prueba de acceso en un dispositivo debe documentarse por separado de la compilación del APK. Un build correcto no demuestra el funcionamiento en un teléfono físico.

## Ejecución paso a paso

La aplicación necesita la API accesible por la IP de red, no por `localhost`. La guía completa del equipo está en `SGCPdocumentacion/00_Definicion/Como_ejecutar_el_sistema.md`.

El motor pasó de SQL Server a MySQL el 6 de noviembre de 2026. Qué se cambió, por qué el proveedor de Oracle y no Pomelo, y las tres trampas de MySQL: `SGCPdocumentacion/00_Definicion/Cambio_a_MySQL.md`.

## Control de versiones

Formato `vMAYOR.MENOR.PARCHE`, donde **MAYOR es el número de sprint**:

| Posición | Significado | Ejemplo |
|---|---|---|
| MAYOR | sprint entregado. `0` es el trabajo anterior a la primera entrega | `v2.0.0` es el Sprint 2 |
| MENOR | un caso de uso o una funcionalidad terminada | `v1.2.0` |
| PARCHE | corrección que no agrega funcionalidad | `v1.2.1` |

Los cuatro repositorios comparten MAYOR, porque el sprint es el mismo para todos, y avanzan MENOR y PARCHE por separado: no hay que forzarlos al mismo número.

Cada versión de la tabla tiene su etiqueta en git, así que `git show v1.0.0` muestra el código exacto de esa fila. **La etiqueta se crea después del commit**, nunca antes, o apunta al estado anterior:

```bash
git commit -m "v1.0.0 - Sprint 1"
git tag -a v1.0.0 -m "Sprint 1 entregado"
git push origin main --follow-tags
```

En la columna *Cambio*, los prefijos son fijos para que la tabla se pueda leer de un tirón: `Corrección:`, `Documentación:`, `Infraestructura:`, o nada cuando es funcionalidad nueva. Si el cambio llegó por una rama, la fila termina en `(PR #n)`; la rama en sí no se anota, porque git ya la guarda en el commit de fusión.

Desde `v1.0.0` el prefijo es `v` sin punto, que es la convención y lo que admite una etiqueta de git. Las filas anteriores se conservan como se escribieron.

### Historial

Más reciente arriba.

| Versión | Fecha | Sprint | Cambio | Autor |
|---|---|---|---|---|
| v1.0.0 | 2026-11-06 | 1 | Documentación: la aplicación no accede a la base de datos, de modo que el cambio de motor no la afecta | Dario Haziel Tambo Parra |
| v.0.1.0 | 2026-11-06 | 1 | Acceso con dirección de API configurable y base técnica del proyecto Android | Dario Haziel Tambo Parra |
