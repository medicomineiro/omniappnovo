package com.example.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector

enum class CaseCategory(val displayName: String, val icon: ImageVector) {
    MEDICINA_GERAL("Clínica Médica", Icons.Default.LocalHospital),
    RADIOLOGIA("Radiologia & Imagem", Icons.Default.Visibility),
    DERMATOLOGIA("Dermatologia", Icons.Default.Face),
    NEUROLOGIA("Neurologia & Psiquiatria", Icons.Default.Psychology),
    CIRURGIA("Cirurgia & Trauma", Icons.Default.Healing),
    ENGENHARIA_PERICIA("Engenharia & Perícia", Icons.Default.Build),
    OUTRO("Multidisciplinar / Outro", Icons.Default.LocalHospital);

    companion object {
        fun fromName(name: String): CaseCategory = entries.find { it.name == name } ?: MEDICINA_GERAL
    }
}

enum class CaseStatus(val label: String) {
    NOVO("Novo"),
    EM_DISCUSSAO("Em Discussão"),
    CONCLUIDO("Concluído"),
    ARQUIVADO("Arquivado")
}

data class AttachedImage(
    val uri: String,
    val caption: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class CaseItem(
    val id: String,
    val title: String,
    val category: CaseCategory,
    val patientCode: String,
    val clinicalHistory: String,
    val physicalExam: String = "",
    val labResults: String = "",
    val specificQuestions: String = "",
    val attachedImages: List<AttachedImage> = emptyList(),
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: CaseStatus = CaseStatus.NOVO,
    val isArchived: Boolean = false
)

enum class EvaluationStatus {
    PENDING,
    SUCCESS,
    ERROR
}

data class AiEvaluation(
    val id: String,
    val caseId: String,
    val provider: AiProvider,
    val modelName: String,
    val roleName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rawResponse: String = "",
    val visualFindings: String = "",
    val differentialDiagnoses: List<String> = emptyList(),
    val redFlags: List<String> = emptyList(),
    val recommendedActions: List<String> = emptyList(),
    val confidencePercent: Int = 0,
    val status: EvaluationStatus = EvaluationStatus.SUCCESS,
    val errorMessage: String? = null,
    val isSimulatedDemo: Boolean = false
)

enum class ConsultationType(val label: String) {
    RODADA_JUNTA("Rodada da Junta"),
    CONSULTA_INDIVIDUAL("Consulta Individual"),
    FOLLOW_UP("Pergunta de Acompanhamento"),
    SINTESE_CONSELHO("Síntese Executiva")
}

data class ConsultationLogEntry(
    val id: String,
    val caseId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val roundIndex: Int = 1,
    val consultationType: ConsultationType = ConsultationType.RODADA_JUNTA,
    val promptSent: String = "",
    val analyzedImagesSnapshot: List<AttachedImage> = emptyList(),
    val evaluation: AiEvaluation
)

data class CouncilSynthesis(
    val id: String,
    val caseId: String,
    val summary: String,
    val pointsOfAgreement: List<String>,
    val pointsOfDivergence: List<String>,
    val jointRecommendations: List<String>,
    val participatingModels: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

data class ProviderConfig(
    val provider: AiProvider,
    val apiKey: String = "",
    val selectedModel: String = provider.defaultModel,
    val customEndpoint: String = "",
    val isEnabledForCouncil: Boolean = true,
    val defaultPersona: AiPersona = AiPersona.CLINICAL_SPECIALIST
)

enum class CaseSortOrder(val label: String) {
    RECENTES("Mais Recentes"),
    ANTIGOS("Mais Antigos"),
    MAIS_CONSULTAS("Mais Consultas de IA"),
    MAIS_IMAGENS("Mais Imagens"),
    ALFABETICA("Ordem Alfabética (A-Z)")
}

enum class DateFilterOption(val label: String) {
    TODOS("Qualquer Data"),
    ULTIMOS_7_DIAS("Últimos 7 dias"),
    ULTIMOS_30_DIAS("Últimos 30 dias"),
    MAIS_DE_30_DIAS("Mais de 1 mês")
}
