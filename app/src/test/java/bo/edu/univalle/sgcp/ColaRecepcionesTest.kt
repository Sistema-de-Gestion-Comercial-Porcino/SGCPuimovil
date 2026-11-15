package bo.edu.univalle.sgcp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Patrón Command: la cola ejecuta las órdenes guardadas, en orden, y reintenta sin cambiar nada. */
class ColaRecepcionesTest {

    /** Almacén en memoria con el mismo contrato que Room. */
    private class AlmacenMemoria : AlmacenPendientes {
        val registros = linkedMapOf<String, RegistroEnvio>()
        override suspend fun guardar(registro: RegistroEnvio) { registros[registro.clave] = registro }
        override suspend fun listar() = registros.values.filter { it.estado == Pendiente.nombre }.sortedBy { it.creadaEn }
        override suspend fun enConflicto() = registros.values.filter { it.estado == Conflicto.nombre }
        override suspend fun enviando() = registros.values.filter { it.estado == Enviando.nombre }
        override suspend fun actualizar(sincronizacion: SincronizacionRecepcion) {
            val r = registros.getValue(sincronizacion.clave)
            registros[r.clave] = r.copy(
                estado = sincronizacion.estado.nombre,
                recepcionId = sincronizacion.recepcionId,
                fechaSincronizacion = sincronizacion.fechaSincronizacion,
                motivo = sincronizacion.motivo
            )
        }
        fun estado(clave: String) = registros.getValue(clave).estado
    }

    /** Servidor simulado: guarda por clave única, como el índice de MySQL. */
    private class ServidorFalso : ClienteRecepcionAPI {
        val recibidos = mutableListOf<RecepcionDto>()
        val filas = linkedMapOf<String, String>()
        var respuestas = ArrayDeque<(RecepcionDto) -> Resultado>()
        var estadosCentrales = mutableMapOf<String, String>()

        override suspend fun registrar(datos: RecepcionDto): Resultado {
            recibidos += datos
            respuestas.removeFirstOrNull()?.let { return it(datos) }
            val existente = filas[datos.claveUnica]
            if (existente != null) return Resultado.Confirmado(existente, "2026-11-14T20:00:00Z", duplicada = true)
            val id = "rec-${filas.size + 1}"
            filas[datos.claveUnica] = id
            return Resultado.Confirmado(id, "2026-11-14T20:00:00Z", duplicada = false)
        }

        override suspend fun consultarEstado(recepcionId: String) = estadosCentrales[recepcionId]
    }

    private fun captura(clave: String, orden: Long) = RegistroEnvio(
        clave = clave,
        entregaId = "entrega-$clave",
        versionBase = 2,
        autorId = "autor-1",
        fechaReal = "2026-11-14T15:00:00Z",
        pesoRecibido = "12.500",
        unidad = "kg",
        conforme = true,
        receptor = "Ana Rojas",
        observacion = "",
        evidencias = listOf("foto-$clave.jpg"),
        creadaEn = orden
    )

    private fun cola(almacen: AlmacenMemoria, servidor: ServidorFalso) =
        ColaRecepciones(almacen, servidor) { ruta -> ruta.toByteArray() }

    @Test fun tresCapturasSeEnvianEnElOrdenDeCaptura() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("c", 3)); almacen.guardar(captura("a", 1)); almacen.guardar(captura("b", 2))
        val servidor = ServidorFalso()
        val resumen = cola(almacen, servidor).sincronizar()
        assertEquals(listOf("a", "b", "c"), servidor.recibidos.map { it.claveUnica })
        assertEquals(3, resumen.confirmadas)
        assertEquals(0, resumen.pendientes)
        listOf("a", "b", "c").forEach { assertEquals(Confirmada.nombre, almacen.estado(it)) }
    }

    @Test fun sinConexionQuedaPendienteYReintentaConLaMismaClaveYContenido() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("a", 1)); almacen.guardar(captura("b", 2))
        val servidor = ServidorFalso()
        servidor.respuestas.add { Resultado.SinConexion("sin señal") }
        val primera = cola(almacen, servidor).sincronizar()
        assertEquals(Pendiente.nombre, almacen.estado("a"))
        assertEquals(Pendiente.nombre, almacen.estado("b"))
        assertEquals(2, primera.pendientes)
        assertEquals(1, servidor.recibidos.size) // no intentó la segunda sin red

        cola(almacen, servidor).sincronizar()
        assertEquals(servidor.recibidos[0], servidor.recibidos[1]) // mismo cuerpo, misma clave
        assertEquals(Confirmada.nombre, almacen.estado("a"))
        assertEquals(Confirmada.nombre, almacen.estado("b"))
    }

    @Test fun cortarDespuesDeQueElServidorGuardoNoDuplica() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("a", 1))
        val servidor = ServidorFalso()
        // El servidor guarda, pero la respuesta se pierde en el camino.
        servidor.respuestas.add { d -> servidor.filas[d.claveUnica] = "rec-1"; Resultado.SinConexion("se cortó") }
        cola(almacen, servidor).sincronizar()
        assertEquals(Pendiente.nombre, almacen.estado("a"))
        cola(almacen, servidor).sincronizar()
        assertEquals(1, servidor.filas.size) // una sola fila en el servidor
        assertEquals(Confirmada.nombre, almacen.estado("a"))
        assertEquals("rec-1", almacen.registros.getValue("a").recepcionId)
    }

    @Test fun conflictoNoSeReenviaYSoloLaRevisionLoCierra() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("a", 1))
        val servidor = ServidorFalso()
        servidor.respuestas.add { Resultado.EnConflicto("rec-9", "La versión cambió") }
        cola(almacen, servidor).sincronizar()
        assertEquals(Conflicto.nombre, almacen.estado("a"))

        cola(almacen, servidor).sincronizar()
        assertEquals(1, servidor.recibidos.size) // no se volvió a enviar

        assertEquals(0, cola(almacen, servidor).revisarConflictos()) // nadie lo revisó todavía
        servidor.estadosCentrales["rec-9"] = "DESCARTADA"
        assertEquals(1, cola(almacen, servidor).revisarConflictos())
        assertEquals(Descartada.nombre, almacen.estado("a"))
    }

    @Test fun rechazoDelServidorQuedaEnConflictoLocalConMotivo() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("a", 1)); almacen.guardar(captura("b", 2))
        val servidor = ServidorFalso()
        servidor.respuestas.add { Resultado.Rechazado("EVIDENCIA_INVALIDA", "Imagen dañada") }
        val resumen = cola(almacen, servidor).sincronizar()
        assertEquals(Conflicto.nombre, almacen.estado("a"))
        assertEquals(Confirmada.nombre, almacen.estado("b")) // una mala no bloquea a las demás
        assertEquals(1, resumen.conflictos)
        assertNull(almacen.registros.getValue("a").recepcionId)
    }

    @Test fun capturaQueQuedoEnviandoAlCerrarLaAppSeReintenta() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("a", 1).copy(estado = Enviando.nombre))
        val servidor = ServidorFalso()
        cola(almacen, servidor).sincronizar()
        assertEquals(Confirmada.nombre, almacen.estado("a"))
    }

    @Test fun sesionVencidaDetieneLaColaSinPerderNada() = runTest {
        val almacen = AlmacenMemoria()
        almacen.guardar(captura("a", 1)); almacen.guardar(captura("b", 2))
        val servidor = ServidorFalso()
        servidor.respuestas.add { Resultado.SesionVencida }
        val resumen = cola(almacen, servidor).sincronizar()
        assertEquals(true, resumen.sesionVencida)
        assertEquals(2, resumen.pendientes)
    }

    @Test fun elCuerpoRespetaElContrato() {
        val dto = cola(AlmacenMemoria(), ServidorFalso()).armar(captura("a", 1))
        assertEquals("12.500", dto.pesoRecibido.toPlainString())
        assertEquals("2026-11-14T15:00:00Z", dto.fechaReal)
        assertEquals("image/jpeg", dto.evidencias.single().tipo)
        assertEquals(2, dto.versionBase)
    }
}
