package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AiEvaluation
import com.example.data.model.AiProvider
import com.example.data.model.AttachedImage
import com.example.data.model.CaseCategory
import com.example.data.model.CaseItem
import com.example.data.model.CaseStatus
import com.example.data.model.ConsultationLogEntry
import com.example.data.model.ConsultationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OmniCase AI", appName)
    }

    @Test
    fun `verify ai providers configuration`() {
        val gemini = AiProvider.GEMINI
        assertEquals("Google Gemini", gemini.displayName)
        assertTrue(gemini.visionSupported)

        val openai = AiProvider.OPENAI
        assertEquals("OpenAI GPT", openai.displayName)
        assertTrue(openai.visionSupported)

        val claude = AiProvider.CLAUDE
        assertEquals("Anthropic Claude", claude.displayName)
        assertTrue(claude.visionSupported)
    }

    @Test
    fun `verify case model creation and archive toggle`() {
        val caseItem = CaseItem(
            id = "test-123",
            title = "Teste de Avaliação de Imagem",
            category = CaseCategory.RADIOLOGIA,
            patientCode = "PAC-9999",
            clinicalHistory = "História de teste com suspeita diagnóstica.",
            tags = listOf("Radiologia", "Urgente"),
            isArchived = false
        )
        assertNotNull(caseItem)
        assertEquals(CaseCategory.RADIOLOGIA, caseItem.category)
        assertEquals(0, caseItem.attachedImages.size)
        assertFalse(caseItem.isArchived)
        assertEquals(2, caseItem.tags.size)

        val archivedCase = caseItem.copy(isArchived = true, status = CaseStatus.ARQUIVADO)
        assertTrue(archivedCase.isArchived)
        assertEquals(CaseStatus.ARQUIVADO, archivedCase.status)
    }

    @Test
    fun `verify consultation log entry recording`() {
        val eval = AiEvaluation(
            id = UUID.randomUUID().toString(),
            caseId = "test-123",
            provider = AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            roleName = "Radiologista",
            visualFindings = "Opacidade alveolar em base pulmonar",
            differentialDiagnoses = listOf("Pneumonia bacteriana"),
            confidencePercent = 90
        )

        val imageSnapshot = listOf(
            AttachedImage(uri = "file://test.jpg", caption = "Raio-X de Tórax PA")
        )

        val logEntry = ConsultationLogEntry(
            id = UUID.randomUUID().toString(),
            caseId = "test-123",
            timestamp = System.currentTimeMillis(),
            roundIndex = 1,
            consultationType = ConsultationType.RODADA_JUNTA,
            promptSent = "Consulta da junta inicial",
            analyzedImagesSnapshot = imageSnapshot,
            evaluation = eval
        )

        assertEquals("test-123", logEntry.caseId)
        assertEquals(1, logEntry.roundIndex)
        assertEquals(1, logEntry.analyzedImagesSnapshot.size)
        assertEquals("Raio-X de Tórax PA", logEntry.analyzedImagesSnapshot[0].caption)
        assertEquals(90, logEntry.evaluation.confidencePercent)
        assertEquals("Opacidade alveolar em base pulmonar", logEntry.evaluation.visualFindings)
    }
}
