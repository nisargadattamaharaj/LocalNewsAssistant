package com.example.localnewsassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.example.localnewsassistant.model.LlmExecutionResult
import com.example.localnewsassistant.model.ModelImportValidator
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.NewsSummaryResult
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import com.example.localnewsassistant.model.TestPromptOption
import com.example.localnewsassistant.ui.LlmExecutionState
import com.example.localnewsassistant.ui.ModelImportViewModel
import com.example.localnewsassistant.ui.ModelUiState
import com.example.localnewsassistant.ui.SummaryUiState
import com.example.localnewsassistant.ui.theme.LocalNewsAssistantTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ModelImportViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ModelImportViewModel::class.java]
        enableEdgeToEdge()

        setContent {
            LocalNewsAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: ModelImportViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val llmState by viewModel.llmState.collectAsState()
    val summaryState by viewModel.summaryState.collectAsState()
    val selectedPromptOption by viewModel.selectedPromptOption.collectAsState()
    val isCoordinatorBusy by viewModel.isBusy.collectAsState()
    var isInterfaceChecked by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importModel(uri)
        }
    }

    MainScreenContent(
        uiState = uiState,
        llmState = llmState,
        summaryState = summaryState,
        selectedPromptOption = selectedPromptOption,
        isCoordinatorBusy = isCoordinatorBusy,
        onSelectPromptOption = { viewModel.selectPromptOption(it) },
        onImportClick = { filePickerLauncher.launch(arrayOf("*/*")) },
        onRunLlmClick = { viewModel.runLlmTest() },
        onRunSummaryClick = { viewModel.runNewsSummary() },
        onCheckInterfaceClick = { isInterfaceChecked = true },
        modifier = modifier,
        isInterfaceChecked = isInterfaceChecked
    )
}

@Composable
fun MainScreenContent(
    uiState: ModelUiState,
    llmState: LlmExecutionState,
    summaryState: SummaryUiState = SummaryUiState.Idle,
    selectedPromptOption: TestPromptOption = TestPromptOption.OPTION_A,
    isCoordinatorBusy: Boolean = false,
    onSelectPromptOption: (TestPromptOption) -> Unit = {},
    onImportClick: () -> Unit = {},
    onRunLlmClick: () -> Unit = {},
    onRunSummaryClick: () -> Unit = {},
    onCheckInterfaceClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isInterfaceChecked: Boolean = false
) {
    val isBusy = isCoordinatorBusy ||
            uiState is ModelUiState.Loading ||
            uiState is ModelUiState.Importing ||
            uiState is ModelUiState.IndeterminateError ||
            llmState is LlmExecutionState.Initializing ||
            llmState is LlmExecutionState.Generating ||
            summaryState is SummaryUiState.PreparingData ||
            summaryState is SummaryUiState.Initializing ||
            summaryState is SummaryUiState.Generating

    val isIndeterminate = uiState is ModelUiState.IndeterminateError
    val canRunLlm = uiState is ModelUiState.Imported && !isBusy && !isIndeterminate

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Локальный анализ новостей",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (uiState) {
            is ModelUiState.Loading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Проверка хранилища...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is ModelUiState.NoModel -> {
                Text(
                    text = "Модель не подключена",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is ModelUiState.Importing -> {
                ImportingProgressCard(state = uiState)
            }

            is ModelUiState.Imported -> {
                ModelInfoCard(metadata = uiState.metadata)
            }

            is ModelUiState.Corrupted -> {
                CorruptedCard(
                    originalName = uiState.originalName,
                    message = uiState.message
                )
            }

            is ModelUiState.IndeterminateError -> {
                IndeterminateCard(message = uiState.message)
            }

            is ModelUiState.Error -> {
                ErrorCard(message = uiState.message)
                if (uiState.previousMetadata != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Ранее импортированная модель:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ModelInfoCard(metadata = uiState.previousMetadata)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (summaryState !is SummaryUiState.Idle) {
            NewsSummaryCard(state = summaryState)
            Spacer(modifier = Modifier.height(16.dp))
        }

        LlmExecutionCard(state = llmState)

        if (canRunLlm || llmState !is LlmExecutionState.Idle) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Тестовый вариант запроса:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(0.9f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TestPromptOption.entries.forEach { option ->
                    val isSelected = selectedPromptOption == option
                    OutlinedButton(
                        onClick = { onSelectPromptOption(option) },
                        enabled = canRunLlm,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isSelected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onRunSummaryClick,
            enabled = canRunLlm,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            ),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Сводка по тестовым новостям")
        }

        Text(
            text = "3 синтетических сообщения. Не реальные новости.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onImportClick,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Импортировать модель")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onRunLlmClick,
            enabled = canRunLlm,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Проверить LLM")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCheckInterfaceClick,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Проверить интерфейс")
        }

        if (isInterfaceChecked) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Интерфейс работает",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun NewsSummaryCard(state: SummaryUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is SummaryUiState.Success -> MaterialTheme.colorScheme.secondaryContainer
                is SummaryUiState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            when (state) {
                is SummaryUiState.Idle -> {}

                is SummaryUiState.PreparingData -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Подготовка тестовых данных...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is SummaryUiState.Initializing -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Инициализация модели...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is SummaryUiState.Generating -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Генерация сводки...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is SummaryUiState.Success -> {
                    var showSources by remember { mutableStateOf(false) }
                    var showRawResponse by remember { mutableStateOf(false) }

                    Text(
                        text = "Черновик сводки — требуется проверка фактов",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (state.summaryResult.bulletPoints.isNotEmpty()) {
                        state.summaryResult.bulletPoints.forEach { point ->
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = "• ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = point.textWithoutCitation,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                if (point.citedIds.isNotEmpty()) {
                                    Text(
                                        text = "Источники: [${point.citedIds.joinToString()}]",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (point.isValidCitation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                                    )
                                }
                                if (!point.warningMessage.isNullOrBlank()) {
                                    Text(
                                        text = "⚠️ ${point.warningMessage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = state.summaryResult.rawResponse,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    if (state.summaryResult.warningMessages.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        state.summaryResult.warningMessages.forEach { warning ->
                            Text(
                                text = "⚠️ $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Инициализация: ${state.executionResult.initTimeMs} мс | Генерация: ${state.executionResult.inferenceTimeMs} мс",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (showRawResponse) "▼ Скрыть исходный ответ модели" else "► Показать исходный ответ модели",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showRawResponse = !showRawResponse }
                    )

                    AnimatedVisibility(visible = showRawResponse) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Text(
                                text = state.summaryResult.rawResponse,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (showSources) "▼ Скрыть исходные сообщения JSON" else "► Показать исходные сообщения JSON (${state.dataset.messages.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showSources = !showSources }
                    )

                    AnimatedVisibility(visible = showSources) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            state.dataset.messages.forEach { msg ->
                                SourceMessageCard(msg = msg)
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }

                is SummaryUiState.Error -> {
                    Text(
                        text = "Ошибка генерации сводки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun SourceMessageCard(msg: SyntheticNewsMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[${msg.id}] ${msg.source}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = msg.publishedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun LlmExecutionCard(state: LlmExecutionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is LlmExecutionState.Success -> MaterialTheme.colorScheme.primaryContainer
                is LlmExecutionState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            when (state) {
                is LlmExecutionState.Idle -> {
                    Text(
                        text = "LLM ещё не запускалась",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is LlmExecutionState.Initializing -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Инициализация модели...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is LlmExecutionState.Generating -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Генерация ответа...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is LlmExecutionState.Success -> {
                    Text(
                        text = "Ответ модели (LiteRT-LM):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.result.responseText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Инициализация: ${state.result.initTimeMs} мс | Генерация: ${state.result.inferenceTimeMs} мс | Backend: ${state.result.backendName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                is LlmExecutionState.Error -> {
                    Text(
                        text = "Ошибка запуска LLM",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ImportingProgressCard(state: ModelUiState.Importing) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Идет импорт файла...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.originalName,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (state.totalBytes > 0) {
                val progress = (state.copiedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                val copiedStr = ModelImportValidator.formatFileSize(state.copiedBytes)
                val totalStr = ModelImportValidator.formatFileSize(state.totalBytes)
                val percentStr = (progress * 100).toInt()
                Text(
                    text = "Скопировано: $copiedStr из $totalStr ($percentStr%)",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                val copiedStr = ModelImportValidator.formatFileSize(state.copiedBytes)
                Text(
                    text = "Скопировано: $copiedStr",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ModelInfoCard(metadata: ModelMetadata) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Файл модели импортирован",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Имя: ${metadata.originalName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Размер: ${ModelImportValidator.formatFileSize(metadata.sizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Совместимость и запуск пока не проверены",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun CorruptedCard(originalName: String?, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Ошибка восстановления модели",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (!originalName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Модель: $originalName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun IndeterminateCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Неопределённое состояние хранилища",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Перезапустите приложение для автоматической проверки хранилища.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Ошибка импорта",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Preview(showBackground = true, name = "1. Модель отсутствует")
@Composable
fun MainScreenNoModelPreview() {
    LocalNewsAssistantTheme {
        MainScreenContent(
            uiState = ModelUiState.NoModel,
            llmState = LlmExecutionState.Idle,
            selectedPromptOption = TestPromptOption.OPTION_A,
            onSelectPromptOption = {},
            onImportClick = {},
            onRunLlmClick = {},
            onRunSummaryClick = {},
            onCheckInterfaceClick = {}
        )
    }
}

@Preview(showBackground = true, name = "2. Идет импорт модели")
@Composable
fun MainScreenImportingPreview() {
    LocalNewsAssistantTheme {
        MainScreenContent(
            uiState = ModelUiState.Importing(
                copiedBytes = 524288000L,
                totalBytes = 1073741824L,
                originalName = "gemma-2b-it-q4.litertlm"
            ),
            llmState = LlmExecutionState.Idle,
            selectedPromptOption = TestPromptOption.OPTION_A,
            onSelectPromptOption = {},
            onImportClick = {},
            onRunLlmClick = {},
            onRunSummaryClick = {},
            onCheckInterfaceClick = {}
        )
    }
}

@Preview(showBackground = true, name = "3. Модель импортирована")
@Composable
fun MainScreenImportedPreview() {
    LocalNewsAssistantTheme {
        MainScreenContent(
            uiState = ModelUiState.Imported(
                metadata = ModelMetadata(
                    originalName = "gemma-2b-it-q4.litertlm",
                    sizeBytes = 1073741824L,
                    internalFileName = "model_12345.litertlm"
                )
            ),
            llmState = LlmExecutionState.Success(
                LlmExecutionResult(
                    responseText = "Локальная языковая модель — это нейросеть, выполняющая генерацию и обработку текста непосредственно на вашем устройстве без отправки данных в сеть.",
                    initTimeMs = 1200L,
                    inferenceTimeMs = 850L,
                    backendName = "CPU"
                )
            ),
            selectedPromptOption = TestPromptOption.OPTION_A,
            onSelectPromptOption = {},
            onImportClick = {},
            onRunLlmClick = {},
            onRunSummaryClick = {},
            onCheckInterfaceClick = {}
        )
    }
}

@Preview(showBackground = true, name = "4. Ошибка генерации LLM")
@Composable
fun MainScreenErrorPreview() {
    LocalNewsAssistantTheme {
        MainScreenContent(
            uiState = ModelUiState.Imported(
                metadata = ModelMetadata(
                    originalName = "gemma-2b-it-q4.litertlm",
                    sizeBytes = 1073741824L,
                    internalFileName = "model_12345.litertlm"
                )
            ),
            llmState = LlmExecutionState.Error("Ошибка инициализации модели: несовместимый формат весов"),
            selectedPromptOption = TestPromptOption.OPTION_A,
            onSelectPromptOption = {},
            onImportClick = {},
            onRunLlmClick = {},
            onRunSummaryClick = {},
            onCheckInterfaceClick = {}
        )
    }
}
