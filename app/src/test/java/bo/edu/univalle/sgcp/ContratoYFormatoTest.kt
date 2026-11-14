package bo.edu.univalle.sgcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Cómo se traduce cada respuesta de SGCPapi y las reglas de formato del proyecto. */
class ContratoYFormatoTest {
    private fun ok(estado: String, duplicada: Boolean = false) = Response.success(
        SincronizarRespuesta(listOf(ResultadoSincronizacion("CLAVE", "rec-1", estado, duplicada, "2026-11-14T20:00:00Z")))
    )

    private fun error(codigo: Int, json: String = "{\"codigo\":\"X\",\"mensaje\":\"m\"}") =
        Response.error<SincronizarRespuesta>(codigo, json.toResponseBody("application/json".toMediaType()))

    @Test fun yaExisteEsExito() {
        val r = ClienteRecepcionRetrofit.interpretar(ok("CONFIRMADA", duplicada = true), "clave")
        assertTrue(r is Resultado.Confirmado && r.duplicada)
    }

    @Test fun conflictoDelServidor() {
        assertTrue(ClienteRecepcionRetrofit.interpretar(ok("CONFLICTO"), "CLAVE") is Resultado.EnConflicto)
    }

    @Test fun errores() {
        assertEquals(Resultado.SesionVencida, ClienteRecepcionRetrofit.interpretar(error(401), "c"))
        assertTrue(ClienteRecepcionRetrofit.interpretar(error(409), "c") is Resultado.SinConexion)
        assertTrue(ClienteRecepcionRetrofit.interpretar(error(503), "c") is Resultado.SinConexion)
        val rechazo = ClienteRecepcionRetrofit.interpretar(error(400, "{\"codigo\":\"CLAVE_REUTILIZADA\",\"mensaje\":\"otra\"}"), "c")
        assertEquals(Resultado.Rechazado("CLAVE_REUTILIZADA", "otra"), rechazo)
    }

    @Test fun pesoConTresDecimalesYPunto() {
        assertEquals("12.500", Formato.peso("12,5"))
        assertEquals("12.375", Formato.peso("12.375"))
        assertEquals("800.000", Formato.peso("800"))
        assertNull(Formato.peso("1.2345"))
        assertNull(Formato.peso("0"))
        assertNull(Formato.peso("-3"))
        assertNull(Formato.peso("abc"))
    }

    @Test fun fechaDeBoliviaSeEnviaEnUtc() {
        assertEquals("2026-11-14T19:30:00Z", Formato.fechaUtc("14/11/2026 15:30"))
        assertNull(Formato.fechaUtc("2026-11-14"))
        assertEquals("14/11/2026 15:53", Formato.bolivia("2026-11-14T19:53:32.314765Z"))
    }
}
