package com.example.data.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.ClaudeColor
import com.example.ui.theme.GeminiColor
import com.example.ui.theme.GroqColor
import com.example.ui.theme.OpenAiColor
import com.example.ui.theme.OpenRouterColor

enum class AiProvider(
    val id: String,
    val displayName: String,
    val company: String,
    val defaultModel: String,
    val supportedModels: List<String>,
    val brandColor: Color,
    val visionSupported: Boolean,
    val baseUrl: String,
    val apiKeyDocsUrl: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        company = "Google DeepMind",
        defaultModel = "gemini-flash-latest",
        supportedModels = listOf("gemini-flash-latest", "gemini-pro-latest", "gemini-3.5-flash", "gemini-3.1-pro-preview"),
        brandColor = GeminiColor,
        visionSupported = true,
        baseUrl = "https://generativelanguage.googleapis.com",
        apiKeyDocsUrl = "https://aistudio.google.com/app/apikey"
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI GPT",
        company = "OpenAI",
        defaultModel = "gpt-5.4",
        supportedModels = listOf("gpt-5.4", "gpt-5.4-mini", "gpt-5", "gpt-5-mini"),
        brandColor = OpenAiColor,
        visionSupported = true,
        baseUrl = "https://api.openai.com/v1",
        apiKeyDocsUrl = "https://platform.openai.com/api-keys"
    ),
    CLAUDE(
        id = "claude",
        displayName = "Anthropic Claude",
        company = "Anthropic",
        defaultModel = "claude-sonnet-5-5",
        supportedModels = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"),
        brandColor = ClaudeColor,
        visionSupported = true,
        baseUrl = "https://api.anthropic.com/v1",
        apiKeyDocsUrl = "https://console.anthropic.com/settings/keys"
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter (Multi-Model)",
        company = "OpenRouter",
        defaultModel = "openrouter/auto",
        supportedModels = listOf(
            "openrouter/auto",
            "qwen/qwen2.5-vl-72b-instruct",
            "meta-llama/llama-4-maverick"
        ),
        brandColor = OpenRouterColor,
        visionSupported = true,
        baseUrl = "https://openrouter.ai/api/v1",
        apiKeyDocsUrl = "https://openrouter.ai/keys"
    ),
    GROQ_CUSTOM(
        id = "groq",
        displayName = "Groq / OpenAI-Compatível",
        company = "Groq / Endpoint Personalizado",
        defaultModel = "meta-llama/llama-4-scout-17b-16e-instruct",
        supportedModels = listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"),
        brandColor = GroqColor,
        visionSupported = true,
        baseUrl = "https://api.groq.com/openai/v1",
        apiKeyDocsUrl = "https://console.groq.com/keys"
    );

    companion object {
        fun fromId(id: String): AiProvider = entries.find { it.id == id } ?: GEMINI

        /** IDs descontinuados pelos provedores; migrados para o padrão atual ao carregar. */
        private val RETIRED_MODEL_PREFIXES = listOf(
            "gemini-1.", "gemini-2.0", "gemini-2.5", "gpt-4o", "chatgpt-4o", "claude-3",
            "llama-3.2-", "meta-llama/llama-3.2", "qwen/qwen-2-vl", "anthropic/claude-3", "openai/gpt-4o"
        )
        fun isRetiredModel(model: String): Boolean = RETIRED_MODEL_PREFIXES.any { model.startsWith(it) }
    }
}

enum class AiPersona(
    val id: String,
    val title: String,
    val description: String,
    val systemPrompt: String
) {
    CLINICAL_SPECIALIST(
        id = "clinical",
        title = "Clínico Geral & Diagnóstico",
        description = "Foco em diagnósticos diferenciais, fisiopatologia e história clínica.",
        systemPrompt = "Você é um Médico Clínico Geral e Especialista Diagnóstico sênior. Avalie meticulosamente os dados clínicos, história, exames e imagens do caso fornecido. Destaque hipóteses mais prováveis, sinais de alerta e condutas prioritárias."
    ),
    RADIOLOGIST_IMAGING(
        id = "radiologist",
        title = "Radiologista / Especialista em Imagem",
        description = "Foco em achados anatômicos, radiopacidades, densidades e lesões.",
        systemPrompt = "Você é um Radiologista e Especialista em Diagnóstico por Imagem e Visão Computacional. Realize uma leitura metódica das imagens fornecidas (radiografias, tomografias, ressonâncias, dermatoscopias ou fotografias clínicas). Descreva detalhadamente os achados visuais, padrões, simetria, bordas e correlação clínico-radiológica."
    ),
    CRITICAL_SECOND_OPINION(
        id = "critical",
        title = "Revisor Cético / 2ª Opinião",
        description = "Busca vieses diagnósticos, 'zebras' raras e armadilhas comuns.",
        systemPrompt = "Você é um Revisor Crítico de Casos e Consultor de Segunda Opinião. Seu papel é questionar os diagnósticos óbvios, apontar 'zebras' raras que podem ser negligenciadas, alertar sobre armadilhas de fechamento precoce e checar exames necessários para descartar emergências."
    ),
    FORENSIC_TECHNICAL(
        id = "forensic",
        title = "Perito Forense / Técnico",
        description = "Análise objetiva baseada em evidências, medições e nexo causal.",
        systemPrompt = "Você é um Perito Forense e Avaliador Técnico de Evidências. Analise o caso e as imagens com rigor factual, determinando nexo causal, cronologia, conformidade técnica e graus de certeza baseados estritamente nos dados e fotografias apresentados."
    ),
    EMERGENCY_TRIAGE(
        id = "emergency",
        title = "Emergencista / Triagem Rápida",
        description = "Foco em estabilização, risco iminente de morte e red flags.",
        systemPrompt = "Você é um Médico Emergencista e Intensivista. Identifique imediatamente sinais de alarme ('red flags'), risco iminente de deterioração e quais condutas devem ser tomadas nas próximas horas para estabilização do paciente."
    );

    companion object {
        fun fromId(id: String): AiPersona = entries.find { it.id == id } ?: CLINICAL_SPECIALIST
    }
}
