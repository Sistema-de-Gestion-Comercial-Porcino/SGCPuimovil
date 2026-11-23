package bo.edu.univalle.sgcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class InterfazTest {
    @Test fun entregadaYObservadaSeVenDistintasYNingunaComoError() {
        assertEquals(Tono.EXITO, tonoEntrega(Confirmada, true))
        assertEquals(Tono.OBSERVADA, tonoEntrega(Confirmada, false))
        assertTrue(tonoEntrega(Confirmada, false) != Tono.PELIGRO)
    }

    @Test fun soloElConflictoUsaElTonoDePeligro() {
        assertEquals(Tono.PELIGRO, tonoEntrega(Conflicto, null))
        assertEquals(Tono.AVISO, tonoEntrega(Pendiente, true))
        assertEquals(Tono.AVISO, tonoEntrega(Enviando, true))
        assertEquals(Tono.NEUTRO, tonoEntrega(null, null))
        assertEquals(Tono.NEUTRO, tonoEntrega(Descartada, true))
    }

    @Test fun telefonoMarcable() {
        assertEquals("tel:70012345", Enlaces.telefono("700-123 45"))
        assertEquals("tel:+59170012345", Enlaces.telefono(" +591 700 12345 "))
        assertNull(Enlaces.telefono(""))
        assertNull(Enlaces.telefono(null))
        assertNull(Enlaces.telefono("sin dato"))
    }

    @Test fun mapaAgregaLaCiudadSoloSiFalta() {
        assertEquals("geo:0,0?q=Av.%20Banzer%20123%2C%20Santa%20Cruz%2C%20Bolivia", Enlaces.mapa("Av. Banzer 123"))
        assertEquals("geo:0,0?q=Calle%201%2C%20Santa%20Cruz", Enlaces.mapa("Calle 1, Santa Cruz"))
        assertNull(Enlaces.mapa("  "))
    }

    @Test fun elSelectorProduceElFormatoQueValidaElFormulario() {
        val texto = FechasPantalla.desdeSelector(2026, 10, 23, 9, 5) // mes 10 = noviembre en Android
        assertEquals("23/11/2026 09:05", texto)
        // Lo elegido en el selector es hora de Bolivia y se envía en UTC (+4 h).
        assertEquals("2026-11-23T13:05:00Z", Formato.fechaUtc(texto))
    }

    @Test fun elSelectorParteDeLoEscritoONow() {
        assertEquals(LocalDateTime.of(2026, 11, 20, 18, 30), FechasPantalla.paraSelector("20/11/2026 18:30"))
        val ahora = FechasPantalla.paraSelector("no es fecha")
        assertTrue(java.time.Duration.between(ahora, LocalDateTime.now(Formato.BOLIVIA)).abs().toMinutes() < 2)
    }

    @Test fun momentoDeCapturaEnHoraDeBolivia() {
        // 2026-11-23 13:05 UTC = 09:05 en Bolivia.
        assertEquals("23/11/2026 09:05", FechasPantalla.capturada(java.time.Instant.parse("2026-11-23T13:05:00Z").toEpochMilli()))
    }
}
