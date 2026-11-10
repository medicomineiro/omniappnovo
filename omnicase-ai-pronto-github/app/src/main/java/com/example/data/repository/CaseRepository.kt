package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.AiEvaluation
import com.example.data.model.AiPersona
import com.example.data.model.AiProvider
import com.example.data.model.AttachedImage
import com.example.data.model.CaseCategory
import com.example.data.model.CaseItem
import com.example.data.model.CaseStatus
import com.example.data.model.ConsultationLogEntry
import com.example.data.model.ConsultationType
import com.example.data.model.CouncilSynthesis
import com.example.data.model.EvaluationStatus
import com.example.data.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class CaseRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("omnicase_prefs", Context.MODE_PRIVATE)
    private val casesFile = File(context.filesDir, "cases_data.json")
    private val evaluationsFile = File(context.filesDir, "evaluations_data.json")
    private val synthesisFile = File(context.filesDir, "synthesis_data.json")
    private val consultationLogsFile = File(context.filesDir, "consultations_history.json")

    private val _cases = MutableStateFlow<List<CaseItem>>(emptyList())
    val cases: StateFlow<List<CaseItem>> = _cases.asStateFlow()

    private val _evaluations = MutableStateFlow<Map<String, List<AiEvaluation>>>(emptyMap())
    val evaluations: StateFlow<Map<String, List<AiEvaluation>>> = _evaluations.asStateFlow()

    private val _consultationLogs = MutableStateFlow<Map<String, List<ConsultationLogEntry>>>(emptyMap())
    val consultationLogs: StateFlow<Map<String, List<ConsultationLogEntry>>> = _consultationLogs.asStateFlow()

    private val _syntheses = MutableStateFlow<Map<String, CouncilSynthesis>>(emptyMap())
    val syntheses: StateFlow<Map<String, CouncilSynthesis>> = _syntheses.asStateFlow()

    private val _providerConfigs = MutableStateFlow<Map<AiProvider, ProviderConfig>>(emptyMap())
    val providerConfigs: StateFlow<Map<AiProvider, ProviderConfig>> = _providerConfigs.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadProviderConfigs()
        loadCases()
        loadEvaluations()
        loadConsultationLogs()
        loadSyntheses()

        if (_cases.value.isEmpty()) {
            seedSampleCases()
        }
    }

    private fun loadProviderConfigs() {
        val map = mutableMapOf<AiProvider, ProviderConfig>()
        for (provider in AiProvider.entries) {
            val key = prefs.getString("key_${provider.id}", "") ?: ""
            val storedModel = prefs.getString("model_${provider.id}", provider.defaultModel) ?: provider.defaultModel
            val model = if (storedModel.isBlank() || AiProvider.isRetiredModel(storedModel)) provider.defaultModel else storedModel
            val endpoint = prefs.getString("endpoint_${provider.id}", "") ?: ""
            val enabled = prefs.getBoolean("enabled_${provider.id}", true)
            val personaId = prefs.getString("persona_${provider.id}", AiPersona.CLINICAL_SPECIALIST.id) ?: AiPersona.CLINICAL_SPECIALIST.id

            map[provider] = ProviderConfig(
                provider = provider,
                apiKey = key,
                selectedModel = model,
                customEndpoint = endpoint,
                isEnabledForCouncil = enabled,
                defaultPersona = AiPersona.fromId(personaId)
            )
        }
        _providerConfigs.value = map
    }

    suspend fun saveProviderConfig(config: ProviderConfig) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            putString("key_${config.provider.id}", config.apiKey)
            putString("model_${config.provider.id}", config.selectedModel)
            putString("endpoint_${config.provider.id}", config.customEndpoint)
            putBoolean("enabled_${config.provider.id}", config.isEnabledForCouncil)
            putString("persona_${config.provider.id}", config.defaultPersona.id)
            apply()
        }
        _providerConfigs.update { it + (config.provider to config) }
    }

    private fun loadCases() {
        if (!casesFile.exists()) return
        try {
            val jsonStr = casesFile.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<CaseItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val imagesArray = obj.optJSONArray("attachedImages")
                val imagesList = mutableListOf<AttachedImage>()
                if (imagesArray != null) {
                    for (j in 0 until imagesArray.length()) {
                        val imgObj = imagesArray.getJSONObject(j)
                        imagesList.add(
                            AttachedImage(
                                uri = imgObj.optString("uri"),
                                caption = imgObj.optString("caption"),
                                timestamp = imgObj.optLong("timestamp")
                            )
                        )
                    }
                }

                val tagsList = jsonArrayToStringList(obj.optJSONArray("tags"))

                list.add(
                    CaseItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        category = CaseCategory.fromName(obj.optString("category", "MEDICINA_GERAL")),
                        patientCode = obj.optString("patientCode", "PAC-001"),
                        clinicalHistory = obj.optString("clinicalHistory", ""),
                        physicalExam = obj.optString("physicalExam", ""),
                        labResults = obj.optString("labResults", ""),
                        specificQuestions = obj.optString("specificQuestions", ""),
                        attachedImages = imagesList,
                        tags = tagsList,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        status = runCatching { CaseStatus.valueOf(obj.optString("status", CaseStatus.NOVO.name)) }.getOrDefault(CaseStatus.NOVO),
                        isArchived = obj.optBoolean("isArchived", false)
                    )
                )
            }
            _cases.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCasesToDisk() {
        try {
            val array = JSONArray()
            for (item in _cases.value) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("category", item.category.name)
                obj.put("patientCode", item.patientCode)
                obj.put("clinicalHistory", item.clinicalHistory)
                obj.put("physicalExam", item.physicalExam)
                obj.put("labResults", item.labResults)
                obj.put("specificQuestions", item.specificQuestions)
                obj.put("createdAt", item.createdAt)
                obj.put("updatedAt", item.updatedAt)
                obj.put("status", item.status.name)
                obj.put("isArchived", item.isArchived)
                obj.put("tags", JSONArray(item.tags))

                val imagesArray = JSONArray()
                for (img in item.attachedImages) {
                    val imgObj = JSONObject()
                    imgObj.put("uri", img.uri)
                    imgObj.put("caption", img.caption)
                    imgObj.put("timestamp", img.timestamp)
                    imagesArray.put(imgObj)
                }
                obj.put("attachedImages", imagesArray)
                array.put(obj)
            }
            writeAtomically(casesFile, array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun saveOrUpdateCase(item: CaseItem) = withContext(Dispatchers.IO) {
        _cases.update { list ->
            val current = list.toMutableList()
            val index = current.indexOfFirst { it.id == item.id }
            if (index >= 0) current[index] = item.copy(updatedAt = System.currentTimeMillis())
            else current.add(0, item)
            current
        }
        saveCasesToDisk()
    }

    suspend fun toggleArchiveCase(caseId: String) = withContext(Dispatchers.IO) {
        val current = _cases.value.toMutableList()
        val index = current.indexOfFirst { it.id == caseId }
        if (index >= 0) {
            val old = current[index]
            val newArchivedState = !old.isArchived
            val newStatus = if (newArchivedState) CaseStatus.ARQUIVADO else CaseStatus.EM_DISCUSSAO
            current[index] = old.copy(isArchived = newArchivedState, status = newStatus, updatedAt = System.currentTimeMillis())
            _cases.value = current
            saveCasesToDisk()
        }
    }

    suspend fun deleteCase(caseId: String) = withContext(Dispatchers.IO) {
        val current = _cases.value.toMutableList()
        current.removeAll { it.id == caseId }
        _cases.value = current
        saveCasesToDisk()

        val evals = _evaluations.value.toMutableMap()
        evals.remove(caseId)
        _evaluations.value = evals
        saveEvaluationsToDisk()

        val logs = _consultationLogs.value.toMutableMap()
        logs.remove(caseId)
        _consultationLogs.value = logs
        saveConsultationLogsToDisk()

        val synths = _syntheses.value.toMutableMap()
        synths.remove(caseId)
        _syntheses.value = synths
        saveSynthesesToDisk()
    }

    // -------------------------------------------------------------
    // CONSULTATION LOGS (HISTÓRICO COMPLETO DE CONSULTAS)
    // -------------------------------------------------------------
    private fun loadConsultationLogs() {
        if (!consultationLogsFile.exists()) return
        try {
            val jsonStr = consultationLogsFile.readText()
            val root = JSONObject(jsonStr)
            val map = mutableMapOf<String, List<ConsultationLogEntry>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val caseId = keys.next()
                val array = root.getJSONArray(caseId)
                val list = mutableListOf<ConsultationLogEntry>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val evalObj = obj.getJSONObject("evaluation")

                    val diffs = jsonArrayToStringList(evalObj.optJSONArray("differentialDiagnoses"))
                    val flags = jsonArrayToStringList(evalObj.optJSONArray("redFlags"))
                    val actions = jsonArrayToStringList(evalObj.optJSONArray("recommendedActions"))

                    val evaluation = AiEvaluation(
                        id = evalObj.getString("id"),
                        caseId = caseId,
                        provider = AiProvider.fromId(evalObj.getString("provider")),
                        modelName = evalObj.optString("modelName", ""),
                        roleName = evalObj.optString("roleName", ""),
                        timestamp = evalObj.optLong("timestamp", System.currentTimeMillis()),
                        rawResponse = evalObj.optString("rawResponse", ""),
                        visualFindings = evalObj.optString("visualFindings", ""),
                        differentialDiagnoses = diffs,
                        redFlags = flags,
                        recommendedActions = actions,
                        confidencePercent = evalObj.optInt("confidencePercent", 80),
                        status = runCatching { EvaluationStatus.valueOf(evalObj.optString("status", EvaluationStatus.SUCCESS.name)) }.getOrDefault(EvaluationStatus.SUCCESS),
                        errorMessage = if (evalObj.has("errorMessage")) evalObj.getString("errorMessage") else null,
                        isSimulatedDemo = evalObj.optBoolean("isSimulatedDemo", false)
                    )

                    val imgsArray = obj.optJSONArray("analyzedImagesSnapshot")
                    val imgsList = mutableListOf<AttachedImage>()
                    if (imgsArray != null) {
                        for (j in 0 until imgsArray.length()) {
                            val imgJson = imgsArray.getJSONObject(j)
                            imgsList.add(
                                AttachedImage(
                                    uri = imgJson.optString("uri"),
                                    caption = imgJson.optString("caption"),
                                    timestamp = imgJson.optLong("timestamp")
                                )
                            )
                        }
                    }

                    list.add(
                        ConsultationLogEntry(
                            id = obj.getString("id"),
                            caseId = caseId,
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            roundIndex = obj.optInt("roundIndex", 1),
                            consultationType = runCatching { ConsultationType.valueOf(obj.optString("consultationType", ConsultationType.RODADA_JUNTA.name)) }.getOrDefault(ConsultationType.RODADA_JUNTA),
                            promptSent = obj.optString("promptSent", ""),
                            analyzedImagesSnapshot = imgsList,
                            evaluation = evaluation
                        )
                    )
                }
                map[caseId] = list
            }
            _consultationLogs.value = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveConsultationLogsToDisk() {
        try {
            val root = JSONObject()
            for ((caseId, list) in _consultationLogs.value) {
                val array = JSONArray()
                for (entry in list) {
                    val obj = JSONObject()
                    obj.put("id", entry.id)
                    obj.put("caseId", entry.caseId)
                    obj.put("timestamp", entry.timestamp)
                    obj.put("roundIndex", entry.roundIndex)
                    obj.put("consultationType", entry.consultationType.name)
                    obj.put("promptSent", entry.promptSent)

                    // Images snapshot
                    val imgsArray = JSONArray()
                    for (img in entry.analyzedImagesSnapshot) {
                        val imgJson = JSONObject()
                        imgJson.put("uri", img.uri)
                        imgJson.put("caption", img.caption)
                        imgJson.put("timestamp", img.timestamp)
                        imgsArray.put(imgJson)
                    }
                    obj.put("analyzedImagesSnapshot", imgsArray)

                    // Evaluation
                    val evObj = JSONObject()
                    evObj.put("id", entry.evaluation.id)
                    evObj.put("provider", entry.evaluation.provider.id)
                    evObj.put("modelName", entry.evaluation.modelName)
                    evObj.put("roleName", entry.evaluation.roleName)
                    evObj.put("timestamp", entry.evaluation.timestamp)
                    evObj.put("rawResponse", entry.evaluation.rawResponse)
                    evObj.put("visualFindings", entry.evaluation.visualFindings)
                    evObj.put("differentialDiagnoses", JSONArray(entry.evaluation.differentialDiagnoses))
                    evObj.put("redFlags", JSONArray(entry.evaluation.redFlags))
                    evObj.put("recommendedActions", JSONArray(entry.evaluation.recommendedActions))
                    evObj.put("confidencePercent", entry.evaluation.confidencePercent)
                    evObj.put("status", entry.evaluation.status.name)
                    evObj.put("isSimulatedDemo", entry.evaluation.isSimulatedDemo)
                    if (entry.evaluation.errorMessage != null) evObj.put("errorMessage", entry.evaluation.errorMessage)
                    obj.put("evaluation", evObj)

                    array.put(obj)
                }
                root.put(caseId, array)
            }
            writeAtomically(consultationLogsFile, root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun recordConsultation(entry: ConsultationLogEntry) = withContext(Dispatchers.IO) {
        // update{} é atômico: rodadas paralelas da junta não sobrescrevem entradas umas das outras
        _consultationLogs.update { map ->
            map + (entry.caseId to (listOf(entry) + (map[entry.caseId] ?: emptyList())))
        }
        saveConsultationLogsToDisk()

        // Also update latest evaluation in active evaluations map
        addEvaluation(entry.evaluation)
    }

    private fun loadEvaluations() {
        if (!evaluationsFile.exists()) return
        try {
            val jsonStr = evaluationsFile.readText()
            val root = JSONObject(jsonStr)
            val map = mutableMapOf<String, List<AiEvaluation>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val caseId = keys.next()
                val array = root.getJSONArray(caseId)
                val list = mutableListOf<AiEvaluation>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val diffs = jsonArrayToStringList(obj.optJSONArray("differentialDiagnoses"))
                    val flags = jsonArrayToStringList(obj.optJSONArray("redFlags"))
                    val actions = jsonArrayToStringList(obj.optJSONArray("recommendedActions"))

                    list.add(
                        AiEvaluation(
                            id = obj.getString("id"),
                            caseId = caseId,
                            provider = AiProvider.fromId(obj.getString("provider")),
                            modelName = obj.optString("modelName", ""),
                            roleName = obj.optString("roleName", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            rawResponse = obj.optString("rawResponse", ""),
                            visualFindings = obj.optString("visualFindings", ""),
                            differentialDiagnoses = diffs,
                            redFlags = flags,
                            recommendedActions = actions,
                            confidencePercent = obj.optInt("confidencePercent", 80),
                            status = runCatching { EvaluationStatus.valueOf(obj.optString("status", EvaluationStatus.SUCCESS.name)) }.getOrDefault(EvaluationStatus.SUCCESS),
                            errorMessage = if (obj.has("errorMessage")) obj.getString("errorMessage") else null,
                            isSimulatedDemo = obj.optBoolean("isSimulatedDemo", false)
                        )
                    )
                }
                map[caseId] = list
            }
            _evaluations.value = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveEvaluationsToDisk() {
        try {
            val root = JSONObject()
            for ((caseId, list) in _evaluations.value) {
                val array = JSONArray()
                for (ev in list) {
                    val obj = JSONObject()
                    obj.put("id", ev.id)
                    obj.put("caseId", ev.caseId)
                    obj.put("provider", ev.provider.id)
                    obj.put("modelName", ev.modelName)
                    obj.put("roleName", ev.roleName)
                    obj.put("timestamp", ev.timestamp)
                    obj.put("rawResponse", ev.rawResponse)
                    obj.put("visualFindings", ev.visualFindings)
                    obj.put("differentialDiagnoses", JSONArray(ev.differentialDiagnoses))
                    obj.put("redFlags", JSONArray(ev.redFlags))
                    obj.put("recommendedActions", JSONArray(ev.recommendedActions))
                    obj.put("confidencePercent", ev.confidencePercent)
                    obj.put("status", ev.status.name)
                    obj.put("isSimulatedDemo", ev.isSimulatedDemo)
                    if (ev.errorMessage != null) obj.put("errorMessage", ev.errorMessage)
                    array.put(obj)
                }
                root.put(caseId, array)
            }
            writeAtomically(evaluationsFile, root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun addEvaluation(evaluation: AiEvaluation) = withContext(Dispatchers.IO) {
        _evaluations.update { map ->
            val list = map[evaluation.caseId]?.toMutableList() ?: mutableListOf()
            val existingIndex = list.indexOfFirst { it.provider == evaluation.provider }
            when {
                existingIndex < 0 -> list.add(evaluation)
                // erro de rede não apaga o último parecer válido do mesmo provedor
                evaluation.status == EvaluationStatus.SUCCESS || list[existingIndex].status != EvaluationStatus.SUCCESS ->
                    list[existingIndex] = evaluation
            }
            map + (evaluation.caseId to list)
        }
        saveEvaluationsToDisk()

        val caseItem = _cases.value.find { it.id == evaluation.caseId }
        if (caseItem != null && (caseItem.status == CaseStatus.NOVO || caseItem.isArchived)) {
            saveOrUpdateCase(caseItem.copy(status = CaseStatus.EM_DISCUSSAO, isArchived = false))
        }
    }

    suspend fun saveSynthesis(synthesis: CouncilSynthesis) = withContext(Dispatchers.IO) {
        _syntheses.update { it + (synthesis.caseId to synthesis) }
        saveSynthesesToDisk()
    }

    private fun loadSyntheses() {
        if (!synthesisFile.exists()) return
        try {
            val jsonStr = synthesisFile.readText()
            val root = JSONObject(jsonStr)
            val map = mutableMapOf<String, CouncilSynthesis>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val caseId = keys.next()
                val obj = root.getJSONObject(caseId)
                map[caseId] = CouncilSynthesis(
                    id = obj.getString("id"),
                    caseId = caseId,
                    summary = obj.getString("summary"),
                    pointsOfAgreement = jsonArrayToStringList(obj.optJSONArray("pointsOfAgreement")),
                    pointsOfDivergence = jsonArrayToStringList(obj.optJSONArray("pointsOfDivergence")),
                    jointRecommendations = jsonArrayToStringList(obj.optJSONArray("jointRecommendations")),
                    participatingModels = jsonArrayToStringList(obj.optJSONArray("participatingModels")),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
            }
            _syntheses.value = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSynthesesToDisk() {
        try {
            val root = JSONObject()
            for ((caseId, syn) in _syntheses.value) {
                val obj = JSONObject()
                obj.put("id", syn.id)
                obj.put("caseId", syn.caseId)
                obj.put("summary", syn.summary)
                obj.put("pointsOfAgreement", JSONArray(syn.pointsOfAgreement))
                obj.put("pointsOfDivergence", JSONArray(syn.pointsOfDivergence))
                obj.put("jointRecommendations", JSONArray(syn.jointRecommendations))
                obj.put("participatingModels", JSONArray(syn.participatingModels))
                obj.put("timestamp", syn.timestamp)
                root.put(caseId, obj)
            }
            writeAtomically(synthesisFile, root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Escrita atômica (tmp + rename): evita JSON truncado se o app for morto durante a gravação. */
    private val writeLock = Any()
    private fun writeAtomically(target: File, content: String) = synchronized(writeLock) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun jsonArrayToStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    private suspend fun seedSampleCases() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2 * 24 * 60 * 60 * 1000L
        val oneWeekAgo = now - 7 * 24 * 60 * 60 * 1000L

        val case1 = CaseItem(
            id = "case_xray_sample",
            title = "Opacidade e Consolidação em Lobo Inferior Direito",
            category = CaseCategory.RADIOLOGIA,
            patientCode = "PAC-7182",
            clinicalHistory = "Paciente de 54 anos, sexo masculino, previamente hígido, procura pronto atendimento com história de febre alta (38.8°C) há 4 dias, tosse produtiva com escarro amarelado e dor pleurítica ventilatório-dependente em base pulmonar direita. Nega viagens recentes.",
            physicalExam = "Estado geral regular, taquipneico (FR 24 irpm), FC 102 bpm, PA 125/80 mmHg, SpO2 93% em ar ambiente. Murmúrio vesicular diminuído em terço inferior de hemitórax direito com estertores crepitantes tele-inspiratórios e aumento do frêmito toracovocal.",
            labResults = "Leucograma com leucocitose (16.200/mm³) com desvio à esquerda (10% de bastões). PCR sérica elevada (142 mg/L). Ureia 38 mg/dL, Creatinina 1.0 mg/dL.",
            specificQuestions = "1. Qual a impressão diagnóstica radiológica mais provável?\n2. Existe indicação de internação com base nos escores clínicos (ex: CURB-65)?\n3. Qual a terapêutica antimicrobiana empírica recomendada?",
            attachedImages = listOf(
                AttachedImage(
                    uri = "drawable://sample_chest_xray",
                    caption = "Radiografia de Tórax em PA: consolidação lobar inferior direita destacada",
                    timestamp = twoDaysAgo
                )
            ),
            tags = listOf("Pneumonia", "Tórax", "Raio-X", "Febre", "Infeccioso"),
            createdAt = twoDaysAgo,
            updatedAt = twoDaysAgo + 3600000L,
            status = CaseStatus.EM_DISCUSSAO
        )

        val case2 = CaseItem(
            id = "case_derm_sample",
            title = "Lesão Melanocítica Cutânea Assimétrica em Dorso",
            category = CaseCategory.DERMATOLOGIA,
            patientCode = "PAC-3390",
            clinicalHistory = "Paciente do sexo feminino, 41 anos, fototipo II de Fitzpatrick, relata mácula acastanhada pré-existente na região interescapular que apresentou crescimento progressivo e escurecimento heterogêneo nos últimos 6 meses. Relata história familiar positiva para melanoma (mãe tratada aos 52 anos). Nega sangramento ou prurido.",
            physicalExam = "Lesão pigmentada com diâmetro máximo de 8.5 mm, bordas irregulares e chanfradas, assimetria em dois eixos, variação policromática (castanho escuro, marrom claro, preto e área de descoloração cinza-azulada focal).",
            labResults = "Sem exames laboratoriais prévios.",
            specificQuestions = "1. A imagem dermatoscópica sugere critérios de malignidade (ABCD / regra dos 7 pontos)?\n2. Qual o tipo de biópsia indicada (excisional x incisional)?\n3. Quais as margens cirúrgicas recomendadas no estadiamento inicial?",
            attachedImages = listOf(
                AttachedImage(
                    uri = "drawable://sample_derm_lesion",
                    caption = "Dermatoscopia com luz polarizada: rede pigmentar atípica e véu azul-esbranquiçado",
                    timestamp = oneWeekAgo
                )
            ),
            tags = listOf("Melanoma", "Dermatoscopia", "Biópsia", "Bordas Irregulares"),
            createdAt = oneWeekAgo,
            updatedAt = oneWeekAgo + 7200000L,
            status = CaseStatus.EM_DISCUSSAO
        )

        val case3 = CaseItem(
            id = "case_archive_sample",
            title = "Investigação de Cefaleia em Salvas e Sinais Focais",
            category = CaseCategory.NEUROLOGIA,
            patientCode = "PAC-1904",
            clinicalHistory = "Paciente de 38 anos com episódios recorrentes de cefaleia periorbitária unilateral intensa associada a lacrimejamento e congestão nasal ipsilateral. Episódios com duração de 45 a 90 minutos.",
            physicalExam = "Exame neurológico sem déficits motores ou sensitivos aparentes fora das crises.",
            labResults = "Ressonância magnética de crânio com protocolo para encéfalo e vasos sem evidência de aneurismas ou lesões expansivas.",
            specificQuestions = "1. Diagnóstico diferencial com migrânea e neuralgia do trigêmeo.\n2. Profilaxia farmacológica indicada.",
            attachedImages = emptyList(),
            tags = listOf("Neurologia", "Cefaleia", "RM Encéfalo", "Caso Resolvido"),
            createdAt = now - 45 * 24 * 60 * 60 * 1000L,
            updatedAt = now - 40 * 24 * 60 * 60 * 1000L,
            status = CaseStatus.ARQUIVADO,
            isArchived = true
        )

        _cases.value = listOf(case1, case2, case3)
        saveCasesToDisk()

        // Seed initial consultation log history for Case 1 (X-Ray)
        val evalGemini = AiEvaluation(
            id = UUID.randomUUID().toString(),
            caseId = case1.id,
            provider = AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            roleName = "Radiologista / Especialista em Imagem",
            timestamp = twoDaysAgo + 1800000L,
            rawResponse = "Laudo Radiológico Integrado: Identificada condensação alveolar em lobo inferior direito compatível com pneumonia bacteriana comunitária. Ausência de derrame pleural volumoso.",
            visualFindings = "Notável área focal de opacidade heterogênea em lobo inferior direito, com consolidação alveolar e broncogramas aéreos visíveis. Seios costofrênicos preservados. Silhueta cardíaca com dimensões normais.",
            differentialDiagnoses = listOf(
                "Pneumonia Adquirida na Comunidade (PAC) bacteriana (Probabilidade: 75%)",
                "Atelectasia segmentar secundária (Probabilidade: 15%)",
                "Infarto pulmonar atípico (Probabilidade: 10%)"
            ),
            redFlags = listOf(
                "Possibilidade de derrame parapneumônico se houver piora da dispneia",
                "Saturação de O2 deve ser monitorada continuamente"
            ),
            recommendedActions = listOf(
                "Iniciar antibioticoterapia empírica conforme protocolo institucional",
                "Hemograma completo, PCR quantitativa e hemoculturas",
                "Controle radiográfico em 48-72h se ausência de melhora clínica"
            ),
            confidencePercent = 88,
            isSimulatedDemo = false
        )

        val evalGpt = AiEvaluation(
            id = UUID.randomUUID().toString(),
            caseId = case1.id,
            provider = AiProvider.OPENAI,
            modelName = "gpt-4o",
            roleName = "Clínico Geral & Diagnóstico",
            timestamp = twoDaysAgo + 2400000L,
            rawResponse = "Avaliação Clínica: Paciente com CURB-65 = 1 (pela taquipneia FR 24). Risco intermediário, considerar internação em enfermaria se suporte social inadequado.",
            visualFindings = "Opacidade alveolar evidente em hemitórax inferior direito sem pneumotórax visível.",
            differentialDiagnoses = listOf(
                "Pneumonia Lobar bacteriana por S. pneumoniae (Probabilidade: 80%)",
                "Pneumonia atípica por Mycoplasma (Probabilidade: 15%)"
            ),
            redFlags = listOf(
                "Taquipneia (FR 24) e queda na saturação (93%)"
            ),
            recommendedActions = listOf(
                "Amoxicilina + Clavulanato associado a Macrolídeo",
                "Oxigenoterapia em cânula nasal se SpO2 < 92%"
            ),
            confidencePercent = 90,
            isSimulatedDemo = false
        )

        val logEntry1 = ConsultationLogEntry(
            id = UUID.randomUUID().toString(),
            caseId = case1.id,
            timestamp = twoDaysAgo + 1800000L,
            roundIndex = 1,
            consultationType = ConsultationType.RODADA_JUNTA,
            promptSent = "Consulta Inicial da Junta com 1 imagem radiológica PA.",
            analyzedImagesSnapshot = case1.attachedImages,
            evaluation = evalGemini
        )

        val logEntry2 = ConsultationLogEntry(
            id = UUID.randomUUID().toString(),
            caseId = case1.id,
            timestamp = twoDaysAgo + 2400000L,
            roundIndex = 1,
            consultationType = ConsultationType.RODADA_JUNTA,
            promptSent = "Consulta Inicial da Junta com 1 imagem radiológica PA.",
            analyzedImagesSnapshot = case1.attachedImages,
            evaluation = evalGpt
        )

        val mapLogs = mutableMapOf<String, List<ConsultationLogEntry>>()
        mapLogs[case1.id] = listOf(logEntry2, logEntry1)
        _consultationLogs.value = mapLogs
        saveConsultationLogsToDisk()

        val mapEvals = mutableMapOf<String, List<AiEvaluation>>()
        mapEvals[case1.id] = listOf(evalGpt, evalGemini)
        _evaluations.value = mapEvals
        saveEvaluationsToDisk()
    }
}
