package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiEvaluation
import com.example.data.model.AiPersona
import com.example.data.model.AiProvider
import com.example.data.model.AttachedImage
import com.example.data.model.CaseCategory
import com.example.data.model.CaseItem
import com.example.data.model.CaseSortOrder
import com.example.data.model.CaseStatus
import com.example.data.model.ConsultationLogEntry
import com.example.data.model.ConsultationType
import com.example.data.model.CouncilSynthesis
import com.example.data.model.DateFilterOption
import com.example.data.model.EvaluationStatus
import com.example.data.model.ProviderConfig
import com.example.data.network.MultiAiClient
import com.example.data.repository.CaseRepository
import com.example.data.util.ImageHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed class AppScreen {
    object Dashboard : AppScreen()
    data class CaseDetail(val caseId: String, val initialTab: Int = 0) : AppScreen()
    data class CaseEdit(val caseId: String?) : AppScreen()
    object ApiSettings : AppScreen()
}

data class CaseUiState(
    val currentScreen: AppScreen = AppScreen.Dashboard,
    val searchQuery: String = "",
    val selectedCategoryFilter: CaseCategory? = null,
    val selectedStatusFilter: CaseStatus? = null,
    val selectedDateFilter: DateFilterOption = DateFilterOption.TODOS,
    val selectedProviderFilter: AiProvider? = null,
    val hasImagesOnlyFilter: Boolean = false,
    val sortOrder: CaseSortOrder = CaseSortOrder.RECENTES,
    val selectedCaseId: String? = null,
    val isConsultingCouncil: Boolean = false,
    val activeQueryingProviders: Set<AiProvider> = emptySet(),
    val isSynthesizingCouncil: Boolean = false,
    val isSubmittingFollowUp: Boolean = false,
    val viewingImage: AttachedImage? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isAdvancedFilterExpanded: Boolean = false
)

class CaseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CaseRepository(application)
    private val aiClient = MultiAiClient(application)

    private val _uiState = MutableStateFlow(CaseUiState())
    val uiState: StateFlow<CaseUiState> = _uiState.asStateFlow()

    val cases: StateFlow<List<CaseItem>> = repository.cases
    val evaluations: StateFlow<Map<String, List<AiEvaluation>>> = repository.evaluations
    val consultationLogs: StateFlow<Map<String, List<ConsultationLogEntry>>> = repository.consultationLogs
    val syntheses: StateFlow<Map<String, CouncilSynthesis>> = repository.syntheses
    val providerConfigs: StateFlow<Map<AiProvider, ProviderConfig>> = repository.providerConfigs

    // Filtered and sorted cases list with deep historical log search & retrieval
    val filteredCases: StateFlow<List<CaseItem>> = combine(
        cases,
        consultationLogs,
        _uiState
    ) { casesList, logsMap, state ->
        val query = state.searchQuery.trim().lowercase()
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        val thirtyDaysMs = 30 * 24 * 60 * 60 * 1000L

        casesList.filter { item ->
            // Category filter
            val matchesCategory = state.selectedCategoryFilter == null || item.category == state.selectedCategoryFilter

            // Status / Archive filter
            val matchesStatus = when (state.selectedStatusFilter) {
                null -> !item.isArchived // Default view: non-archived cases
                CaseStatus.ARQUIVADO -> item.isArchived
                else -> item.status == state.selectedStatusFilter && !item.isArchived
            }

            // Has images filter
            val matchesImages = !state.hasImagesOnlyFilter || item.attachedImages.isNotEmpty()

            // Date filter
            val caseAge = now - item.createdAt
            val matchesDate = when (state.selectedDateFilter) {
                DateFilterOption.TODOS -> true
                DateFilterOption.ULTIMOS_7_DIAS -> caseAge <= sevenDaysMs
                DateFilterOption.ULTIMOS_30_DIAS -> caseAge <= thirtyDaysMs
                DateFilterOption.MAIS_DE_30_DIAS -> caseAge > thirtyDaysMs
            }

            // Provider filter (was evaluated by this specific AI)
            val caseLogs = logsMap[item.id] ?: emptyList()
            val matchesProvider = state.selectedProviderFilter == null ||
                    caseLogs.any { it.evaluation.provider == state.selectedProviderFilter }

            // Deep search query: matches title, patientCode, clinical history, exam, labs, questions, image captions, tags, OR AI consultation logs!
            val matchesSearch = query.isBlank() ||
                    item.title.lowercase().contains(query) ||
                    item.patientCode.lowercase().contains(query) ||
                    item.clinicalHistory.lowercase().contains(query) ||
                    item.physicalExam.lowercase().contains(query) ||
                    item.labResults.lowercase().contains(query) ||
                    item.specificQuestions.lowercase().contains(query) ||
                    item.tags.any { it.lowercase().contains(query) } ||
                    item.attachedImages.any { it.caption.lowercase().contains(query) } ||
                    caseLogs.any { log ->
                        log.evaluation.rawResponse.lowercase().contains(query) ||
                                log.evaluation.visualFindings.lowercase().contains(query) ||
                                log.evaluation.differentialDiagnoses.any { it.lowercase().contains(query) } ||
                                log.promptSent.lowercase().contains(query)
                    }

            matchesCategory && matchesStatus && matchesImages && matchesDate && matchesProvider && matchesSearch
        }.sortedWith { a, b ->
            when (state.sortOrder) {
                CaseSortOrder.RECENTES -> b.updatedAt.compareTo(a.updatedAt)
                CaseSortOrder.ANTIGOS -> a.createdAt.compareTo(b.createdAt)
                CaseSortOrder.MAIS_CONSULTAS -> {
                    val countA = logsMap[a.id]?.size ?: 0
                    val countB = logsMap[b.id]?.size ?: 0
                    countB.compareTo(countA)
                }
                CaseSortOrder.MAIS_IMAGENS -> b.attachedImages.size.compareTo(a.attachedImages.size)
                CaseSortOrder.ALFABETICA -> a.title.compareTo(b.title, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.initialize()
        }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.value = _uiState.value.copy(
            currentScreen = screen,
            selectedCaseId = when (screen) {
                is AppScreen.CaseDetail -> screen.caseId
                is AppScreen.CaseEdit -> screen.caseId
                else -> _uiState.value.selectedCaseId
            }
        )
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setCategoryFilter(category: CaseCategory?) {
        _uiState.value = _uiState.value.copy(selectedCategoryFilter = category)
    }

    fun setStatusFilter(status: CaseStatus?) {
        _uiState.value = _uiState.value.copy(selectedStatusFilter = status)
    }

    fun setDateFilter(dateFilter: DateFilterOption) {
        _uiState.value = _uiState.value.copy(selectedDateFilter = dateFilter)
    }

    fun setProviderFilter(provider: AiProvider?) {
        _uiState.value = _uiState.value.copy(selectedProviderFilter = provider)
    }

    fun setHasImagesOnly(hasImages: Boolean) {
        _uiState.value = _uiState.value.copy(hasImagesOnlyFilter = hasImages)
    }

    fun setSortOrder(order: CaseSortOrder) {
        _uiState.value = _uiState.value.copy(sortOrder = order)
    }

    fun toggleAdvancedFilters() {
        _uiState.value = _uiState.value.copy(isAdvancedFilterExpanded = !_uiState.value.isAdvancedFilterExpanded)
    }

    fun resetAllFilters() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            selectedCategoryFilter = null,
            selectedStatusFilter = null,
            selectedDateFilter = DateFilterOption.TODOS,
            selectedProviderFilter = null,
            hasImagesOnlyFilter = false,
            sortOrder = CaseSortOrder.RECENTES
        )
    }

    fun setViewingImage(image: AttachedImage?) {
        _uiState.value = _uiState.value.copy(viewingImage = image)
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }

    fun saveCase(
        id: String?,
        title: String,
        category: CaseCategory,
        patientCode: String,
        clinicalHistory: String,
        physicalExam: String,
        labResults: String,
        specificQuestions: String,
        images: List<AttachedImage>,
        tags: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            val caseId = id ?: UUID.randomUUID().toString()
            val existing = cases.value.find { it.id == caseId }
            val newCase = CaseItem(
                id = caseId,
                title = title.ifBlank { "Caso Sem Título" },
                category = category,
                patientCode = patientCode.ifBlank { "PAC-${(1000..9999).random()}" },
                clinicalHistory = clinicalHistory,
                physicalExam = physicalExam,
                labResults = labResults,
                specificQuestions = specificQuestions,
                attachedImages = images,
                tags = tags.ifEmpty { existing?.tags ?: emptyList() },
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                status = existing?.status ?: CaseStatus.NOVO,
                isArchived = existing?.isArchived ?: false
            )
            repository.saveOrUpdateCase(newCase)
            _uiState.value = _uiState.value.copy(
                currentScreen = AppScreen.CaseDetail(caseId),
                selectedCaseId = caseId,
                statusMessage = "Caso salvo com sucesso no histórico!"
            )
        }
    }

    fun toggleArchiveCase(caseId: String) {
        viewModelScope.launch {
            repository.toggleArchiveCase(caseId)
            val caseItem = cases.value.find { it.id == caseId }
            val newState = !(caseItem?.isArchived ?: false)
            _uiState.value = _uiState.value.copy(
                statusMessage = if (newState) "Caso arquivado no histórico." else "Caso desarquivado e reaberto para discussão."
            )
        }
    }

    fun deleteCase(caseId: String) {
        viewModelScope.launch {
            repository.deleteCase(caseId)
            _uiState.value = _uiState.value.copy(
                currentScreen = AppScreen.Dashboard,
                statusMessage = "Caso excluído."
            )
        }
    }

    suspend fun processPickedImage(uri: Uri, caption: String = ""): AttachedImage {
        val internalUri = ImageHelper.copyUriToInternalStorage(getApplication(), uri)
        return AttachedImage(
            uri = internalUri,
            caption = caption,
            timestamp = System.currentTimeMillis()
        )
    }

    // Consult single AI and append to historical log
    fun consultSingleAi(caseId: String, provider: AiProvider, persona: AiPersona? = null) {
        val caseItem = cases.value.find { it.id == caseId } ?: return
        val config = providerConfigs.value[provider] ?: ProviderConfig(provider)
        val effectivePersona = persona ?: config.defaultPersona
        if (!aiClient.hasUsableKey(config)) {
            _uiState.value = _uiState.value.copy(errorMessage = "Configure a chave de ${provider.displayName} em Ajustes.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                activeQueryingProviders = _uiState.value.activeQueryingProviders + provider
            )
            try {
                val evaluation = aiClient.evaluateCase(caseItem, config, effectivePersona)
                val currentLogs = consultationLogs.value[caseId] ?: emptyList()
                val nextRound = (currentLogs.maxOfOrNull { it.roundIndex } ?: 0) + 1

                val logEntry = ConsultationLogEntry(
                    id = UUID.randomUUID().toString(),
                    caseId = caseId,
                    timestamp = System.currentTimeMillis(),
                    roundIndex = nextRound,
                    consultationType = ConsultationType.CONSULTA_INDIVIDUAL,
                    promptSent = "Consulta individual de ${provider.displayName} (${effectivePersona.title}).",
                    analyzedImagesSnapshot = caseItem.attachedImages,
                    evaluation = evaluation
                )
                repository.recordConsultation(logEntry)

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Consulta com ${provider.displayName} registrada no histórico!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Falha ao consultar ${provider.displayName}: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    activeQueryingProviders = _uiState.value.activeQueryingProviders - provider
                )
            }
        }
    }

    // Multi-AI Council query (records all responses into historical audit log)
    fun consultCouncil(caseId: String, selectedProviders: Set<AiProvider>? = null) {
        val caseItem = cases.value.find { it.id == caseId } ?: return
        val configs = providerConfigs.value

        val requested = selectedProviders ?: configs.values
            .filter { it.isEnabledForCouncil }
            .map { it.provider }
            .toSet()
        // Só consulta quem tem chave: evita registrar pareceres de erro no histórico
        val providersToConsult = requested.filter { aiClient.hasUsableKey(configs[it] ?: ProviderConfig(it)) }.toSet()

        if (providersToConsult.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = if (requested.isEmpty()) "Nenhum modelo de IA selecionado para o conselho."
                               else "Nenhum dos modelos selecionados tem chave de API configurada."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConsultingCouncil = true,
                activeQueryingProviders = providersToConsult
            )

            try {
                val currentLogs = consultationLogs.value[caseId] ?: emptyList()
                val currentRound = (currentLogs.maxOfOrNull { it.roundIndex } ?: 0) + 1
                // Decodifica/comprime as imagens uma única vez para todas as IAs da rodada
                val encodedImages = aiClient.encodeImages(caseItem)

                val deferreds = providersToConsult.map { provider ->
                    async {
                        val config = configs[provider] ?: ProviderConfig(provider)
                        val eval = aiClient.evaluateCase(caseItem, config, config.defaultPersona, encodedImages)

                        // Save as immutable historical log entry
                        val logEntry = ConsultationLogEntry(
                            id = UUID.randomUUID().toString(),
                            caseId = caseId,
                            timestamp = System.currentTimeMillis(),
                            roundIndex = currentRound,
                            consultationType = ConsultationType.RODADA_JUNTA,
                            promptSent = "Rodada da Junta #$currentRound: Consulta simultânea de ${providersToConsult.size} IAs com ${caseItem.attachedImages.size} imagem(ns).",
                            analyzedImagesSnapshot = caseItem.attachedImages,
                            evaluation = eval
                        )
                        repository.recordConsultation(logEntry)

                        _uiState.value = _uiState.value.copy(
                            activeQueryingProviders = _uiState.value.activeQueryingProviders - provider
                        )
                        eval
                    }
                }

                val results = deferreds.awaitAll()

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Rodada #$currentRound concluída e salva no histórico! ${results.count { it.status == EvaluationStatus.SUCCESS }} pareceres registrados."
                )

                // Generate joint synthesis
                val allEvals = repository.evaluations.value[caseId] ?: emptyList()
                if (allEvals.count { it.status == EvaluationStatus.SUCCESS } >= 2) {
                    generateSynthesis(caseId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Erro durante a consulta do conselho: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    isConsultingCouncil = false,
                    activeQueryingProviders = emptySet()
                )
            }
        }
    }

    // Submit a follow-up question to a specific AI model or all active models
    fun submitFollowUpQuestion(caseId: String, provider: AiProvider, followUpQuestion: String) {
        val caseItem = cases.value.find { it.id == caseId } ?: return
        val config = providerConfigs.value[provider] ?: ProviderConfig(provider)
        if (!aiClient.hasUsableKey(config)) {
            _uiState.value = _uiState.value.copy(errorMessage = "Configure a chave de ${provider.displayName} em Ajustes.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmittingFollowUp = true)
            try {
                // Construct follow up case with question
                val augmentedCase = caseItem.copy(
                    specificQuestions = "${caseItem.specificQuestions}\n\n[PERGUNTA DE ACOMPANHAMENTO]: $followUpQuestion"
                )

                val eval = aiClient.evaluateCase(augmentedCase, config, config.defaultPersona)
                val currentLogs = consultationLogs.value[caseId] ?: emptyList()
                val nextRound = (currentLogs.maxOfOrNull { it.roundIndex } ?: 0) + 1

                val logEntry = ConsultationLogEntry(
                    id = UUID.randomUUID().toString(),
                    caseId = caseId,
                    timestamp = System.currentTimeMillis(),
                    roundIndex = nextRound,
                    consultationType = ConsultationType.FOLLOW_UP,
                    promptSent = followUpQuestion,
                    analyzedImagesSnapshot = caseItem.attachedImages,
                    evaluation = eval
                )
                repository.recordConsultation(logEntry)

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Pergunta respondida por ${provider.displayName} e arquivada no log!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Falha ao enviar pergunta: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isSubmittingFollowUp = false)
            }
        }
    }

    fun generateSynthesis(caseId: String) {
        val caseItem = cases.value.find { it.id == caseId } ?: return
        val evals = evaluations.value[caseId] ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSynthesizingCouncil = true)
            try {
                val synthesis = aiClient.synthesizeCouncil(caseItem, evals, providerConfigs.value)
                repository.saveSynthesis(synthesis)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Síntese e consenso da Junta gerados com sucesso!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Falha ao gerar síntese: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isSynthesizingCouncil = false)
            }
        }
    }

    fun saveProviderConfig(config: ProviderConfig) {
        viewModelScope.launch {
            repository.saveProviderConfig(config)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Configurações de ${config.provider.displayName} salvas."
            )
        }
    }

    suspend fun testProviderConnection(provider: AiProvider, key: String, model: String, endpoint: String): Result<String> {
        return aiClient.testConnection(provider, key, model, endpoint)
    }

    fun generateFullCaseHistoryReport(caseItem: CaseItem): String {
        val logs = consultationLogs.value[caseItem.id] ?: emptyList()
        val synthesis = syntheses.value[caseItem.id]

        return buildString {
            appendLine("==========================================================")
            appendLine("    OMNICASE AI - HISTÓRICO COMPLETO & AUDITORIA DE CASO")
            appendLine("==========================================================")
            appendLine("Caso: ${caseItem.title}")
            appendLine("Identificador: ${caseItem.patientCode}")
            appendLine("Especialidade: ${caseItem.category.displayName}")
            appendLine("Status: ${caseItem.status.label}")
            appendLine("Total de Imagens: ${caseItem.attachedImages.size}")
            appendLine("Total de Consultas de IA Registradas: ${logs.size}")
            appendLine()
            appendLine("--- HISTÓRIA CLÍNICA INICIAL ---")
            appendLine(caseItem.clinicalHistory)
            if (caseItem.physicalExam.isNotBlank()) {
                appendLine()
                appendLine("--- EXAME FÍSICO / DADOS OBSERVADOS ---")
                appendLine(caseItem.physicalExam)
            }
            if (caseItem.labResults.isNotBlank()) {
                appendLine()
                appendLine("--- EXAMES LABORATORIAIS / LAUDOS ---")
                appendLine(caseItem.labResults)
            }
            if (synthesis != null) {
                appendLine()
                appendLine("==========================================================")
                appendLine("   ÚLTIMO CONSENSO DA JUNTA DE IAS")
                appendLine("==========================================================")
                appendLine(synthesis.summary)
                appendLine()
                appendLine("PONTOS DE ACORDO:")
                synthesis.pointsOfAgreement.forEach { appendLine("  ✓ $it") }
                appendLine()
                appendLine("PONTOS DE DIVERGÊNCIA:")
                synthesis.pointsOfDivergence.forEach { appendLine("  • $it") }
                appendLine()
                appendLine("CONDUTA CONJUNTA DEFINITIVA:")
                synthesis.jointRecommendations.forEach { appendLine("  ➔ $it") }
            }
            if (logs.isNotEmpty()) {
                appendLine()
                appendLine("==========================================================")
                appendLine("   CRONOLOGIA HISTÓRICA DE CONSULTAS DE IA (LOGS)")
                appendLine("==========================================================")
                logs.forEachIndexed { index, entry ->
                    val dateStr = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                    appendLine()
                    appendLine("----------------------------------------------------------")
                    appendLine("REGISTRO #${logs.size - index} | Data: $dateStr | Tipo: ${entry.consultationType.label} (Rodada ${entry.roundIndex})")
                    appendLine("IA: ${entry.evaluation.provider.displayName} (${entry.evaluation.modelName}) - Papel: ${entry.evaluation.roleName}")
                    appendLine("Imagens Analisadas no Momento: ${entry.analyzedImagesSnapshot.size} imagem(ns)")
                    if (entry.promptSent.isNotBlank()) {
                        appendLine("Pergunta / Contexto Enviado: \"${entry.promptSent}\"")
                    }
                    appendLine("Confiança: ${entry.evaluation.confidencePercent}%")
                    appendLine("Achados Visuais: ${entry.evaluation.visualFindings}")
                    appendLine("Hipóteses:")
                    entry.evaluation.differentialDiagnoses.forEach { appendLine("  - $it") }
                    appendLine("Sinais de Alerta (Red Flags):")
                    entry.evaluation.redFlags.forEach { appendLine("  ⚠️ $it") }
                    appendLine("Conduta:")
                    entry.evaluation.recommendedActions.forEach { appendLine("  ✓ $it") }
                }
            }
            appendLine()
            appendLine("Relatório gerado por OmniCase AI")
        }
    }

    fun shareCaseReport(caseItem: CaseItem) {
        val report = generateFullCaseHistoryReport(caseItem)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Histórico Completo OmniCase AI: ${caseItem.title}")
            putExtra(Intent.EXTRA_TEXT, report)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Compartilhar Histórico do Caso").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(chooser)
    }
}
