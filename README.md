# SGCPuimovil

Aplicación Android Kotlin del Sistema de Gestión Comercial Porcino. Consume la misma API que la web. Sprint 1: acceso online y renovación de sesión. Sprint 3: descarga de entregas asignadas, captura de la recepción sin conexión y sincronización.

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
- Estado «No hay reparto descargado» mientras no se descarguen entregas. No simula asignaciones.
- Mensajes de error de conexión y reintento.

No requiere acceso a la base de datos desde el teléfono: todo pasa por la API.

## Sprint 3 · Captura sin conexión y sincronización (S3-P2)

Contrato consumido, tal como está en el README de SGCPapi:

| Endpoint | Uso en la app |
|---|---|
| `GET mis-entregas` | Descarga solo las entregas DESPACHADAS a domicilio del responsable autenticado. Sin descargar no hay nada que capturar |
| `POST recepciones/sincronizar` | Envía cada captura con clave única, versión base, autor, fecha real UTC, peso con unidad, conformidad, receptor, observación y de 1 a 3 fotos JPEG |
| `GET recepciones/{id}` | Consulta si un conflicto ya fue aplicado o descartado por una persona en la web |
| `POST rutas/{id}/finalizar` | Solo se ofrece cuando la ruta no tiene capturas sin enviar en el teléfono |

**Room (RNF-01).** `BaseLocal` guarda las entregas descargadas, las capturas y la ruta de cada evidencia. Las fotos van a `files/evidencias`, almacenamiento privado de la app, nunca a la galería (RNF-03); se reducen a JPEG de hasta 500 KiB. La captura local no cambia la entrega, el inventario ni la deuda: el servidor decide. La pantalla muestra cuántas capturas faltan enviar, el último envío y los conflictos, y avisa que lo no enviado se pierde si se borra la app o se pierde el teléfono.

**Command.** `ComandoEnvio` / `ComandoRecepcion` son la orden; `ColaRecepciones` el invocador; `ClienteRecepcionAPI` el receptor; `AlmacenPendientes` la persistencia. La cola envía una captura por solicitud, en el orden en que se capturaron, y se detiene si no hay red para conservar el orden. El cuerpo se arma siempre igual desde lo guardado, así que el reintento manda la misma clave y el mismo contenido.

**State.** `EstadoEnvio` con `Pendiente`, `Enviando`, `Confirmada`, `Conflicto` y `Descartada`, según el diagrama `16_OperacionPendiente_Estados`. «Ya existe» es éxito. Sin red, 409 o error 5xx vuelven a Pendiente. Un conflicto no se reenvía: sale a Confirmada o Descartada solo cuando una persona lo resuelve en el servidor. Un rechazo por regla (por ejemplo `EVIDENCIA_INVALIDA`) queda en Conflicto con el motivo, sin bloquear las demás capturas. ENTREGADA u OBSERVADA solo se muestran con la captura Confirmada: no hay forma de llegar a entregada sin sincronizar.

**Sincronización.** Con la app abierta: al guardar una captura, al recuperar la red o con «Sincronizar ahora». No hay envío en segundo plano con la app cerrada. Requiere sesión válida; sin señal se puede abrir la app y capturar lo ya descargado aunque la sesión haya vencido.

Pruebas unitarias (27): transiciones válidas e inválidas del State, orden de la cola, reintento sin duplicar, conflicto que no se reenvía, rechazo que no bloquea, sesión vencida, Room real con Robolectric (la captura sobrevive al cierre) y formato de peso y fechas.

Prueba manual del 14 de noviembre de 2026 en un TECNO CK7n con Android 14, contra la API local y MySQL 8.0, sin cable. Se descargaron las entregas con wifi, se cortó toda la señal (modo avión y wifi apagado) y se capturaron tres recepciones: dos conformes y una con observación. La app se cerró y se reabrió, y las tres seguían pendientes. Al volver el wifi se enviaron solas y en orden. En MySQL quedaron tres recepciones con tres claves distintas, una foto cada una, las dos conformes en ENTREGADA y la observada en OBSERVADA, con la fecha real y la de sincronización separadas.

Lo que no cubren las pruebas automáticas y se prueba a mano: modo avión real en el teléfono. Con el cable USB y `adb reverse` el teléfono sigue llegando a la API aunque esté en modo avión, así que esa prueba se hace sin el puente USB.

La ejecución y prueba de acceso en un dispositivo debe documentarse por separado de la compilación del APK. Un build correcto no demuestra el funcionamiento en un teléfono físico.

## Ejecución paso a paso

La aplicación necesita la API accesible por la IP de red, no por `localhost`. La guía completa del equipo está en `SGCPdocumentacion/00_Definicion/Como_ejecutar_el_sistema.md`.

El motor pasó de SQL Server a MySQL el 6 de noviembre de 2026. Qué se cambió, por qué el proveedor de Oracle y no Pomelo, y las tres trampas de MySQL: `SGCPdocumentacion/00_Definicion/Cambio_a_MySQL.md`.

## Control de versiones

Formato `vMAYOR.MENOR.PARCHE`, donde **MAYOR es el número de sprint**:

| Posición | Significado | Ejemplo |
|---|---|---|
| MAYOR | número de sprint. `0` es el trabajo anterior a la primera entrega | `v2.4.0` pertenece al Sprint 2 |
| MENOR | número de parte del reparto: P1, P2, P3 o P4 | `v2.1.0` es la parte 1 del Sprint 2 |
| PARCHE | corrección sobre esa misma parte, sin agregar funcionalidad | `v2.1.1` |

Los cuatro repositorios comparten MAYOR y MENOR, porque el sprint y la parte son los mismos para todos; el PARCHE avanza por separado en cada repositorio.

**La etiqueta de cierre de un sprint es `vN.4.0`**, la versión del commit de P4, que es siempre el último. Desde el Sprint 2 no se crea `vN.0.0`: quedaría por detrás de versiones ya entregadas. El Sprint 1 conserva su `v1.0.0` porque se entregó en un solo bloque.

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
| v3.2.0 | 2026-11-14 | 3 | Captura de recepción sin conexión con Room, evidencia privada, cola Command, estados State y sincronización contra `recepciones/sincronizar` | Angel Sebastian Eguez Mur |
| v1.0.1 | 2026-11-07 | 1 | Corrección: el esquema de versiones aclara que MENOR es el número de parte y que la etiqueta de cierre del sprint es `vN.4.0` | Dario Haziel Tambo Parra |
| v1.0.0 | 2026-11-06 | 1 | Documentación: la aplicación no accede a la base de datos, de modo que el cambio de motor no la afecta | Dario Haziel Tambo Parra |
| v.0.1.0 | 2026-11-06 | 1 | Acceso con dirección de API configurable y base técnica del proyecto Android | Dario Haziel Tambo Parra |
