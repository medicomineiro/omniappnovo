package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.model.AiPersona
import com.example.data.model.AiProvider
import com.example.data.model.ProviderConfig
import com.example.ui.components.AiProviderBadge
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.CaseViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    viewModel: CaseViewModel,
    modifier: Modifier = Modifier
) {
    BackHandler {
        viewModel.navigateTo(AppScreen.Dashboard)
    }

    val providerConfigs by viewModel.providerConfigs.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val testResults = remember { mutableStateMapOf<AiProvider, Pair<Boolean, String>>() }
    val isTesting = remember { mutableStateMapOf<AiProvider, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configuração de APIs de IA",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.Dashboard) },
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 40.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Explanatory guidance banner
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Conecte as IAs para Discussão de Casos",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Insira as chaves de API dos provedores desejados. Cada modelo analisará os dados e avaliará as imagens com sua respectiva rede de visão computacional. As chaves são salvas de forma segura e localmente no seu dispositivo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Cards for each AI provider
            items(AiProvider.entries, key = { it.id }) { provider ->
                val currentConfig = providerConfigs[provider] ?: ProviderConfig(provider)

                ProviderSettingsCard(
                    config = currentConfig,
                    isTesting = isTesting[provider] == true,
                    testResult = testResults[provider],
                    onSave = { updated ->
                        viewModel.saveProviderConfig(updated)
                    },
                    onTestConnection = { key, model, endpoint ->
                        coroutineScope.launch {
                            isTesting[provider] = true
                            testResults.remove(provider)
                            val result = viewModel.testProviderConnection(provider, key, model, endpoint)
                            isTesting[provider] = false
                            if (result.isSuccess) {
                                testResults[provider] = Pair(true, result.getOrNull() ?: "Conectado com sucesso")
                            } else {
                                testResults[provider] = Pair(false, result.exceptionOrNull()?.message ?: "Falha na conexão")
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsCard(
    config: ProviderConfig,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?,
    onSave: (ProviderConfig) -> Unit,
    onTestConnection: (key: String, model: String, endpoint: String) -> Unit
) {
    val provider = config.provider
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var selectedModel by remember(config.selectedModel) { mutableStateOf(config.selectedModel) }
    var customEndpoint by remember(config.customEndpoint) { mutableStateOf(config.customEndpoint) }
    var isEnabled by remember(config.isEnabledForCouncil) { mutableStateOf(config.isEnabledForCouncil) }
    var persona by remember(config.defaultPersona) { mutableStateOf(config.defaultPersona) }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isModelMenuExpanded by remember { mutableStateOf(false) }
    var isPersonaMenuExpanded by remember { mutableStateOf(false) }

    val hasInjectedKey = provider == AiProvider.GEMINI &&
            com.example.data.util.BuildConfigHelper.hasInjectedGeminiKey()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_settings_${provider.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Provider Name, Icon & Enable Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(provider.brandColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = provider.company,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isEnabled) "Ativo na Junta" else "Desativado",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            isEnabled = checked
                            onSave(config.copy(isEnabledForCouncil = checked))
                        },
                        modifier = Modifier.testTag("switch_enable_${provider.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Injected Key notification if applicable
            if (hasInjectedKey && apiKey.isBlank()) {
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Chave Gemini detectada no ambiente. Você pode usar diretamente ou inserir outra abaixo.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }

            // API Key input
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    onSave(config.copy(apiKey = it))
                },
                label = { Text("Chave de API (${provider.displayName})") },
                placeholder = {
                    Text(if (hasInjectedKey) "Usando chave padrão do ambiente" else "sk-... ou AIzaSy...")
                },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isPasswordVisible) "Ocultar chave" else "Exibir chave"
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_key_${provider.id}")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Model Selection Dropdown
            ExposedDropdownMenuBox(
                expanded = isModelMenuExpanded,
                onExpandedChange = { isModelMenuExpanded = !isModelMenuExpanded }
            ) {
                // Campo editável: permite digitar qualquer ID de modelo novo sem recompilar o app
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = {
                        selectedModel = it
                        onSave(config.copy(selectedModel = it.trim()))
                    },
                    singleLine = true,
                    label = { Text("Modelo de IA (editável)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isModelMenuExpanded) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = isModelMenuExpanded,
                    onDismissRequest = { isModelMenuExpanded = false }
                ) {
                    provider.supportedModels.forEach { modelOption ->
                        DropdownMenuItem(
                            text = { Text(modelOption) },
                            onClick = {
                                selectedModel = modelOption
                                isModelMenuExpanded = false
                                onSave(config.copy(selectedModel = modelOption))
                            }
                        )
                    }
                }
            }

            // Custom endpoint for Groq or local models
            if (provider == AiProvider.GROQ_CUSTOM) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = customEndpoint,
                    onValueChange = {
                        customEndpoint = it
                        onSave(config.copy(customEndpoint = it))
                    },
                    label = { Text("Endpoint Base URL (Opcional)") },
                    placeholder = { Text("https://api.groq.com/openai/v1 ou http://localhost:11434/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Default Persona Dropdown
            ExposedDropdownMenuBox(
                expanded = isPersonaMenuExpanded,
                onExpandedChange = { isPersonaMenuExpanded = !isPersonaMenuExpanded }
            ) {
                OutlinedTextField(
                    value = persona.title,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Papel / Especialidade Padrão na Junta") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isPersonaMenuExpanded) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = isPersonaMenuExpanded,
                    onDismissRequest = { isPersonaMenuExpanded = false }
                ) {
                    AiPersona.entries.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(p.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        p.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                persona = p
                                isPersonaMenuExpanded = false
                                onSave(config.copy(defaultPersona = p))
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Test connection action and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        val effectiveKey = when {
                            apiKey.isNotBlank() -> apiKey
                            hasInjectedKey -> com.example.data.util.BuildConfigHelper.getInjectedGeminiKey()
                            else -> ""
                        }
                        onTestConnection(effectiveKey, selectedModel, customEndpoint)
                    },
                    enabled = !isTesting && (apiKey.isNotBlank() || hasInjectedKey),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("test_conn_${provider.id}")
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Testando...", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Testar Conexão", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Text(
                    text = "Suporta Imagem: Sim ✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold
                )
            }

            // Test connection result message
            if (testResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = if (testResult.first) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (testResult.first) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (testResult.first) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = testResult.second,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testResult.first) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }
            }
        }
    }
}
