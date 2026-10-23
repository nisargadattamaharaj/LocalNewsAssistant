package com.example.localnewsassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.localnewsassistant.data.local.StoredNewsEntity

@Composable
fun LocalSearchCard(
    state: NewsSearchUiState,
    onSearchClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var queryInput by rememberSaveable { mutableStateOf("") }
    val isLoading = state is NewsSearchUiState.Loading

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Локальный поиск новостей",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = queryInput,
                onValueChange = { queryInput = it },
                label = { Text("Текст запроса") },
                singleLine = true,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onSearchClick(queryInput) },
                enabled = queryInput.trim().isNotEmpty() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Найти")
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is NewsSearchUiState.NotRequested -> {
                    Text(
                        text = "Поиск ещё не выполнялся",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                is NewsSearchUiState.Loading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Поиск по запросу «${state.query}»...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is NewsSearchUiState.Success -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Показано записей: ${state.results.size}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        state.results.forEach { item ->
                            SearchResultItemCard(item = item)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                is NewsSearchUiState.EmptyResult -> {
                    Text(
                        text = "По запросу «${state.query}» ничего не найдено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                is NewsSearchUiState.Error -> {
                    Text(
                        text = "Ошибка: ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun SearchResultItemCard(
    item: StoredNewsEntity,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[${item.datasetId} / ${item.messageId}] ${item.source}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.publishedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
