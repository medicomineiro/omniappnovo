package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.data.model.AiEvaluation
import com.example.data.model.AiPersona
import com.example.data.model.AiProvider
import com.example.data.model.CaseItem
import com.example.data.model.CouncilSynthesis
import com.example.data.model.EvaluationStatus
import com.example.data.model.ProviderConfig
import com.example.data.util.BuildConfigHelper
import com.example.data.util.ImageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Imagem já codificada: (base64, mimeType). */
typealias EncodedImage = Pair<String, String>

class MultiAiClient(private val context: Context) {

    private companion object {
        const val TAG = "MultiAiClient"
        const val MAX_OUTPUT_TOKENS = 4096
        // Modelos com raciocínio interno (Gemini 3.x) consomem tokens de "thinking" dentro do limite de saída
        const val GEMINI_MAX_OUTPUT_TOKENS = 8192
        const val MAX_RETRIES = 2
        val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504, 529)
    }

    // Modelos de raciocínio podem levar >60 s para responder casos com várias imagens
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------
    // Chaves
    // ------------------------------------------------------------------

    fun resolveApiKey(config: ProviderConfig): String = when {
        config.apiKey.isNotBlank() -> config.apiKey.trim()
        config.provider == AiProvider.GEMINI -> BuildConfigHelper.getInjectedGeminiKey()
        else -> ""
    }

    fun hasUsableKey(config: ProviderConfig): Boolean = resolveApiKey(config).isNotBlank()

    /** Codifica as imagens do caso uma única vez (reaproveitado por todas as IAs da rodada). */
    suspend fun encodeImages(caseItem: CaseItem): List<EncodedImage> =
        caseItem.attachedImages.mapNotNull { ImageHelper.getBase64FromUri(context, it.uri) }

    // ------------------------------------------------------------------
    // Avaliação individual
    // ------------------------------------------------------------------

    suspend fun evaluateCase(
        caseItem: CaseItem,
        providerConfig: ProviderConfig,
        persona: AiPersona,
        preEncodedImages: List<EncodedImage>? = null
    ): AiEvaluation = withContext(Dispatchers.IO) {
        val evaluationId = UUID.randomUUID().toString()
        val provider = providerConfig.provider
        val model = providerConfig.selectedModel.trim().ifBlank { provider.defaultModel }
        val apiKey = resolveApiKey(providerConfig)

        // Sem chave: erro explícito. Nada de parecer "simulado" com conteúdo clínico inventado.
        if (apiKey.isBlank()) {
            return@withContext errorEvaluation(
                evaluationId, caseItem.id, provider, model, persona,
                "Chave de API de ${provider.displayName} não configurada."
            )
        }

        try {
            val images = preEncodedImages ?: encodeImages(caseItem)
            val prompt = buildCasePrompt(caseItem, persona)
            val raw = callProvider(provider, apiKey, model, providerConfig.customEndpoint, persona.systemPrompt, prompt, images)
            parseEvaluationResponse(evaluationId, caseItem.id, provider, model, persona.title, raw)
        } catch (e: Exception) {
            Log.w(TAG, "Falha em ${provider.id}", e)
            errorEvaluation(evaluationId, caseItem.id, provider, model, persona, e.message ?: "Erro desconhecido")
        }
    }

    private fun errorEvaluation(
        id: String, caseId: String, provider: AiProvider, model: String, persona: AiPersona, message: String
    ) = AiEvaluation(
        id = id,
        caseId = caseId,
        provider = provider,
        modelName = model,
        roleName = persona.title,
        rawResponse = "Erro: $message",
        visualFindings = "Sem análise: a chamada ao provedor falhou.",
        redFlags = listOf("Falha em ${provider.displayName}"),
        recommendedActions = listOf("Verifique chave, modelo e conexão em Configurações"),
        confidencePercent = 0,
        status = EvaluationStatus.ERROR,
        errorMessage = message
    )

    private fun callProvider(
        provider: AiProvider,
        apiKey: String,
        model: String,
        customEndpoint: String,
        systemPrompt: String,
        userPrompt: String,
        images: List<EncodedImage>
    ): String = when (provider) {
        AiProvider.GEMINI -> callGemini(apiKey, model, systemPrompt, userPrompt, images)
        AiProvider.OPENAI -> callOpenAiCompatible(apiKey, model, systemPrompt, userPrompt, images, provider.baseUrl, isOfficialOpenAi = true)
        AiProvider.CLAUDE -> callClaude(apiKey, model, systemPrompt, userPrompt, images)
        AiProvider.OPENROUTER -> callOpenAiCompatible(apiKey, model, systemPrompt, userPrompt, images, provider.baseUrl)
        AiProvider.GROQ_CUSTOM -> callOpenAiCompatible(
            apiKey, model, systemPrompt, userPrompt, images,
            customEndpoint.ifBlank { provider.baseUrl }
        )
    }

    // ------------------------------------------------------------------
    // HTTP com retry (429 / 5xx / 529 overloaded)
    // ------------------------------------------------------------------

    private fun executeJson(request: Request, label: String): JSONObject {
        var attempt = 0
        while (true) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) return JSONObject(body)

                    if (response.code in RETRYABLE_CODES && attempt < MAX_RETRIES) {
                        val retryAfterSec = response.header("retry-after")?.toLongOrNull()
                        val waitMs = (retryAfterSec?.times(1000) ?: (1500L shl attempt)).coerceAtMost(15_000)
                        Thread.sleep(waitMs)
                        attempt++
                        return@use
                    }
                    val msg = runCatching {
                        val err = JSONObject(body).opt("error")
                        if (err is JSONObject) err.optString("message") else err?.toString()
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(300)
                    throw IOException("$label HTTP ${response.code}: $msg")
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("$label HTTP") == true || attempt >= MAX_RETRIES) throw e
                Thread.sleep(1500L shl attempt)
                attempt++
            }
        }
    }

    private fun callGemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        images: List<EncodedImage>
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", userPrompt))
        images.forEach { (data, mime) ->
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", data)))
        }
        val root = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", GEMINI_MAX_OUTPUT_TOKENS))

        // Chave no header (não na URL): não vaza em logs de proxy/servidor
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(root.toString().toRequestBody(jsonMediaType))
            .build()

        val json = executeJson(request, "Gemini")
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IOException("Gemini sem candidatos (bloqueio: ${json.optJSONObject("promptFeedback")?.optString("blockReason") ?: "?"})")
        val partsOut = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (partsOut != null) for (i in 0 until partsOut.length()) {
                val p = partsOut.getJSONObject(i)
                if (!p.optBoolean("thought", false)) append(p.optString("text"))
            }
        }
        if (text.isBlank()) throw IOException("Gemini retornou vazio (finishReason=${candidate.optString("finishReason")})")
        return text
    }

    private fun callOpenAiCompatible(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        images: List<EncodedImage>,
        baseUrl: String,
        isOfficialOpenAi: Boolean = false
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val url = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"

        val content = JSONArray().put(JSONObject().put("type", "text").put("text", userPrompt))
        images.forEach { (data, mime) ->
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:$mime;base64,$data").put("detail", "high"))
            )
        }
        val root = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", content))
            )

        // GPT-5 / série o: exigem max_completion_tokens e não aceitam temperature customizada
        val isReasoningModel = model.startsWith("gpt-5") || Regex("^o\\d").containsMatchIn(model)
        if (isOfficialOpenAi) {
            root.put("max_completion_tokens", if (isReasoningModel) MAX_OUTPUT_TOKENS * 4 else MAX_OUTPUT_TOKENS)
            if (!isReasoningModel) root.put("temperature", 0.3)
        } else {
            root.put("max_tokens", MAX_OUTPUT_TOKENS)
            root.put("temperature", 0.3)
        }

        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(root.toString().toRequestBody(jsonMediaType))
        if (url.contains("openrouter.ai")) {
            builder.addHeader("X-Title", "OmniCase AI")
        }

        val json = executeJson(builder.build(), "API")
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IOException("Resposta sem 'choices'")
        val text = message.optString("content")
        if (text.isBlank() || text == "null") {
            throw IOException("Resposta vazia (finish_reason=${json.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")})")
        }
        return text
    }

    private fun callClaude(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        images: List<EncodedImage>
    ): String {
        val content = JSONArray()
        images.forEach { (data, mime) ->
            content.put(
                JSONObject().put("type", "image")
                    .put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", data))
            )
        }
        content.put(JSONObject().put("type", "text").put("text", userPrompt))

        val root = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("system", systemPrompt)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(root.toString().toRequestBody(jsonMediaType))
            .build()

        val json = executeJson(request, "Claude")
        val blocks = json.optJSONArray("content") ?: throw IOException("Resposta vazia da API Claude")
        val text = buildString {
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                if (b.optString("type") == "text") append(b.optString("text"))
            }
        }
        if (text.isBlank()) throw IOException("Claude retornou vazio (stop_reason=${json.optString("stop_reason")})")
        return text
    }

    suspend fun testConnection(provider: AiProvider, apiKey: String, model: String, endpoint: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiKey.isNotBlank()) { "Chave de API não informada" }
                callProvider(
                    provider, apiKey.trim(), model.trim().ifBlank { provider.defaultModel }, endpoint,
                    "Você é um assistente de teste.",
                    "Responda apenas: 'Conexão bem-sucedida' seguido do nome do seu modelo.",
                    emptyList()
                ).trim()
            }
        }

    // ------------------------------------------------------------------
    // Síntese da junta
    // ------------------------------------------------------------------

    suspend fun synthesizeCouncil(
        caseItem: CaseItem,
        evaluations: List<AiEvaluation>,
        configs: Map<AiProvider, ProviderConfig>
    ): CouncilSynthesis = withContext(Dispatchers.IO) {
        val successfulEvals = evaluations.filter { it.status == EvaluationStatus.SUCCESS && !it.isSimulatedDemo }
        val models = successfulEvals.map { "${it.provider.displayName} (${it.modelName})" }

        // Usa qualquer provedor com chave (prioriza os que participaram com sucesso), não só a chave Gemini embutida
        val participants = successfulEvals.map { it.provider }.toSet()
        val chair = configs.values
            .filter { hasUsableKey(it) }
            .sortedByDescending { it.provider in participants }
            .firstOrNull()

        if (successfulEvals.isNotEmpty() && chair != null) {
            try {
                val raw = callProvider(
                    chair.provider,
                    resolveApiKey(chair),
                    chair.selectedModel.ifBlank { chair.provider.defaultModel },
                    chair.customEndpoint,
                    "Você preside uma junta multidisciplinar. Sintetize os pareceres de diferentes modelos de IA, " +
                        "apontando consensos, divergências reais (apenas as que constam nos pareceres) e conduta unificada. " +
                        "Não invente dados ausentes nos pareceres.",
                    buildSynthesisPrompt(caseItem, successfulEvals),
                    emptyList()
                )
                return@withContext parseCouncilSynthesis(caseItem.id, models + "Presidente: ${chair.provider.displayName}", raw)
            } catch (e: Exception) {
                Log.w(TAG, "Síntese por IA falhou; usando agregação local", e)
            }
        }
        generateRuleBasedSynthesis(caseItem, successfulEvals, models)
    }

    private fun buildCasePrompt(caseItem: CaseItem, persona: AiPersona): String {
        return buildString {
            appendLine("=== CASO PARA DISCUSSÃO ===")
            appendLine("Título: ${caseItem.title}")
            appendLine("Especialidade: ${caseItem.category.displayName}")
            appendLine("Código/Identificador do Sujeito: ${caseItem.patientCode}")
            appendLine()
            appendLine("=== HISTÓRIA CLÍNICA / FATOS ===")
            appendLine(caseItem.clinicalHistory)
            appendLine()
            if (caseItem.physicalExam.isNotBlank()) {
                appendLine("=== EXAME FÍSICO / DADOS OBSERVACIONAIS ===")
                appendLine(caseItem.physicalExam)
                appendLine()
            }
            if (caseItem.labResults.isNotBlank()) {
                appendLine("=== EXAMES LABORATORIAIS / LAUDOS PRÉVIOS ===")
                appendLine(caseItem.labResults)
                appendLine()
            }
            if (caseItem.specificQuestions.isNotBlank()) {
                appendLine("=== QUESTÕES ESPECÍFICAS DA JUNTA ===")
                appendLine(caseItem.specificQuestions)
                appendLine()
            }
            if (caseItem.attachedImages.isNotEmpty()) {
                appendLine("=== IMAGENS ANEXADAS (${caseItem.attachedImages.size} imagem(ns)) ===")
                caseItem.attachedImages.forEachIndexed { index, img ->
                    appendLine("Imagem #${index + 1}: ${img.caption.ifBlank { "Sem legenda informada" }}")
                }
                appendLine("Por favor, faça uma avaliação visual aprofundada de todas as imagens fornecidas.")
                appendLine()
            }

            appendLine("=== INSTRUÇÕES DE FORMATAÇÃO DA RESPOSTA ===")
            appendLine("Estruture sua resposta de forma clara contendo exatamente as seções abaixo em português:")
            appendLine("1. **ACHADOS VISUAIS E DE IMAGEM**: Descrição metódica das imagens anexadas (anomalias, padrões, densidades, bordas, assimetrias). Se não houver imagem, informe.")
            appendLine("2. **HIPÓTESES DIAGNÓSTICAS**: Liste os diagnósticos mais prováveis com estimativa de relevância (ex: Hipótese A - 70%, Hipótese B - 20%).")
            appendLine("3. **SINAIS DE ALERTA (RED FLAGS)**: Liste pontos críticos de risco ou gravidade que exigem atenção imediata.")
            appendLine("4. **CONDUTA E EXAMES RECOMENDADOS**: Passos práticos, propedêutica armada e tratamentos prioritários.")
            appendLine("5. **CONFIANÇA GERAL**: Uma linha no formato 'CONFIANÇA: XX%' onde XX é um número de 0 a 100.")
        }
    }


    // ------------------------------------------------------------------
    // Parsing (somente linhas com cara de cabeçalho mudam a seção)
    // ------------------------------------------------------------------

    private val headerRegex = Regex("""^\s*(#{1,6}\s+|\d+[.)]\s*\*\*|\*\*[^*]+\*\*\s*:?\s*$|[A-ZÁÉÍÓÚÂÊÔÃÕÇ0-9 .()/:-]{6,}$)""")

    private fun isHeaderLine(line: String): Boolean = line.length < 110 && headerRegex.containsMatchIn(line)

    private fun cleanBullet(line: String): String =
        line.trim().replace(Regex("""^([-*•✓⚠️➔>]+|\d+[.)])\s*"""), "").replace("**", "").trim()

    private fun parseEvaluationResponse(
        id: String,
        caseId: String,
        provider: AiProvider,
        modelName: String,
        roleName: String,
        rawResponse: String
    ): AiEvaluation {
        val visual = StringBuilder()
        val diffList = mutableListOf<String>()
        val redFlags = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var confidence: Int? = null
        var section = 0 // 1 visual, 2 hipóteses, 3 red flags, 4 conduta, 5 confiança

        for (line in rawResponse.lines()) {
            val lower = line.lowercase()
            if (lower.contains("confiança") || lower.contains("confianca")) {
                Regex("""(\d{1,3})\s*%""").find(line)?.let {
                    confidence = it.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
                }
                if (isHeaderLine(line) || lower.contains("confiança:") || lower.contains("confianca:")) { section = 5; continue }
            }
            if (isHeaderLine(line)) {
                val newSection = when {
                    lower.contains("achados") || lower.contains("imagem") || lower.contains("visua") -> 1
                    lower.contains("hipótese") || lower.contains("hipotese") || lower.contains("diferencia") || lower.contains("diagnóstic") -> 2
                    lower.contains("alerta") || lower.contains("red flag") || lower.contains("crític") -> 3
                    lower.contains("conduta") || lower.contains("exames recomendados") || lower.contains("recomenda") -> 4
                    else -> -1
                }
                if (newSection > 0) { section = newSection; continue }
            }
            val clean = cleanBullet(line)
            if (clean.length <= 3) continue
            when (section) {
                1 -> { if (visual.isNotEmpty()) visual.append('\n'); visual.append(clean) }
                2 -> diffList.add(clean)
                3 -> redFlags.add(clean)
                4 -> actions.add(clean)
            }
        }

        return AiEvaluation(
            id = id,
            caseId = caseId,
            provider = provider,
            modelName = modelName,
            roleName = roleName,
            rawResponse = rawResponse,
            visualFindings = visual.toString().ifBlank { "Seção de achados visuais não identificada — ver resposta completa." },
            differentialDiagnoses = diffList.ifEmpty { listOf("Estrutura não reconhecida — ver resposta completa.") },
            redFlags = redFlags,
            recommendedActions = actions,
            // Sem linha de confiança explícita, registra 0 em vez de inventar um número
            confidencePercent = confidence ?: 0,
            status = EvaluationStatus.SUCCESS
        )
    }

    private fun buildSynthesisPrompt(caseItem: CaseItem, evaluations: List<AiEvaluation>): String {
        return buildString {
            appendLine("=== CASO ===")
            appendLine("Título: ${caseItem.title}")
            appendLine("Especialidade: ${caseItem.category.displayName}")
            appendLine("História: ${caseItem.clinicalHistory}")
            appendLine()
            appendLine("=== PARECERES DOS MEMBROS DA JUNTA ===")
            evaluations.forEachIndexed { idx, ev ->
                appendLine("--- Membro #${idx + 1}: ${ev.provider.displayName} (${ev.modelName}) - Função: ${ev.roleName} ---")
                appendLine("Achados de Imagem: ${ev.visualFindings}")
                appendLine("Hipóteses: ${ev.differentialDiagnoses.joinToString("; ")}")
                appendLine("Sinais de Alerta: ${ev.redFlags.joinToString("; ")}")
                appendLine("Condutas: ${ev.recommendedActions.joinToString("; ")}")
                appendLine("Confiança: ${ev.confidencePercent}%")
                appendLine()
            }
            appendLine("=== INSTRUÇÕES PARA A SÍNTESE DO CONSELHO ===")
            appendLine("Produza uma síntese executiva contendo:")
            appendLine("1. RESUMO EXECUTIVO DO CASO: Síntese em 2 parágrafos.")
            appendLine("2. PONTOS DE CONSENSO (ACORDOS): Onde os modelos concordam categoricamente.")
            appendLine("3. PONTOS DE DIVERGÊNCIA: Onde houve discrepâncias ou hipóteses alternativas.")
            appendLine("4. CONDUTA CONJUNTA DEFINITIVA: 3 a 5 ações prioritárias recomendadas pela junta.")
        }
    }

    private fun parseCouncilSynthesis(caseId: String, models: List<String>, rawText: String): CouncilSynthesis {
        val summary = StringBuilder()
        val agreements = mutableListOf<String>()
        val divergences = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        var section = 1

        for (line in rawText.lines()) {
            val lower = line.lowercase()
            if (isHeaderLine(line)) {
                val newSection = when {
                    lower.contains("resumo") -> 1
                    lower.contains("consenso") || lower.contains("acordo") -> 2
                    lower.contains("divergên") || lower.contains("divergen") || lower.contains("discrepân") -> 3
                    lower.contains("conduta") || lower.contains("recomenda") -> 4
                    else -> -1
                }
                if (newSection > 0) { section = newSection; continue }
            }
            val clean = cleanBullet(line)
            if (clean.length <= 5) continue
            when (section) {
                1 -> { if (summary.isNotEmpty()) summary.append('\n'); summary.append(clean) }
                2 -> agreements.add(clean)
                3 -> divergences.add(clean)
                4 -> recommendations.add(clean)
            }
        }

        return CouncilSynthesis(
            id = UUID.randomUUID().toString(),
            caseId = caseId,
            summary = summary.toString().ifBlank { rawText.take(1200) },
            pointsOfAgreement = agreements,
            pointsOfDivergence = divergences,
            jointRecommendations = recommendations,
            participatingModels = models
        )
    }

    /** Agregação local sem IA: apenas reorganiza o que os pareceres disseram, sem fabricar consenso/divergência. */
    private fun generateRuleBasedSynthesis(
        caseItem: CaseItem,
        evaluations: List<AiEvaluation>,
        models: List<String>
    ): CouncilSynthesis {
        fun norm(s: String) = s.lowercase().replace(Regex("""\(.*?\)|\d+\s*%|[^\p{L}\p{N} ]"""), " ").replace(Regex("\\s+"), " ").trim()

        val firstDxByModel = evaluations.mapNotNull { ev -> ev.differentialDiagnoses.firstOrNull()?.let { ev to it } }
        val grouped = firstDxByModel.groupBy { norm(it.second).take(40) }
        val agreement = grouped.filter { it.value.size >= 2 }.map { (_, v) ->
            "Hipótese principal comum a ${v.size} modelos: ${v.first().second}"
        }
        val divergence = if (grouped.size > 1) {
            firstDxByModel.map { (ev, dx) -> "${ev.provider.displayName}: $dx" }
        } else emptyList()

        return CouncilSynthesis(
            id = UUID.randomUUID().toString(),
            caseId = caseItem.id,
            summary = "Agregação local (sem IA presidente — configure ao menos uma chave para síntese real) de " +
                "${evaluations.size} parecer(es) sobre '${caseItem.title}'.",
            pointsOfAgreement = agreement,
            pointsOfDivergence = divergence,
            jointRecommendations = evaluations.flatMap { it.recommendedActions }.distinctBy { norm(it) }.take(6),
            participatingModels = models
        )
    }
}
