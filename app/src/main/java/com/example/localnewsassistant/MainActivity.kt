package com.example.localnewsassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.localnewsassistant.model.ModelImportValidator
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.ui.ModelImportViewModel
import com.example.localnewsassistant.ui.ModelUiState
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
        onImportClick = { filePickerLauncher.launch(arrayOf("*/*")) },
        onCheckInterfaceClick = { isInterfaceChecked = true },
        modifier = modifier,
        isInterfaceChecked = isInterfaceChecked
    )
}

@Composable
fun MainScreenContent(
    uiState: ModelUiState,
    onImportClick: () -> Unit,
    onCheckInterfaceClick: () -> Unit,
    modifier: Modifier = Modifier,
    isInterfaceChecked: Boolean = false
) {
    val isActionDisabled = uiState is ModelUiState.Loading || uiState is ModelUiState.Importing || uiState is ModelUiState.IndeterminateError

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

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onImportClick,
            enabled = !isActionDisabled,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Импортировать модель")
        }

        Spacer(modifier = Modifier.height(16.dp))

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
            onImportClick = {},
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
            onImportClick = {},
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
                    internalFileName = "model_12345.bin"
                )
            ),
            onImportClick = {},
            onCheckInterfaceClick = {}
        )
    }
}

@Preview(showBackground = true, name = "4. Ошибка импорта")
@Composable
fun MainScreenErrorPreview() {
    LocalNewsAssistantTheme {
        MainScreenContent(
            uiState = ModelUiState.Error(
                message = "Выбран неверный формат файла. Требуется файл с расширением .litertlm",
                previousMetadata = ModelMetadata(
                    originalName = "old_gemma-2b.litertlm",
                    sizeBytes = 800000000L,
                    internalFileName = "model_old.bin"
                )
            ),
            onImportClick = {},
            onCheckInterfaceClick = {}
        )
    }
}
