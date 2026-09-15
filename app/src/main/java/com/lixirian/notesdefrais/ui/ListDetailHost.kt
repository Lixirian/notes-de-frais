package com.lixirian.notesdefrais.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lixirian.notesdefrais.ui.edit.EditArgs
import com.lixirian.notesdefrais.ui.edit.ExpenseEditScreen
import kotlinx.coroutines.launch

/**
 * Hôte liste + détail adaptatif : un seul volet sur téléphone, deux volets côte à côte sur
 * tablette, pliable déplié ou double écran. Le scaffold Material 3 Adaptive lit la posture de
 * la fenêtre (charnière, pli) et place la séparation des volets sur la charnière.
 * Le volet détail est toujours l'écran d'édition d'une dépense.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ListDetailHost(
    onSettings: () -> Unit,
    listPane: @Composable (openDetail: (EditArgs) -> Unit) -> Unit,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()

    val openDetail: (EditArgs) -> Unit = { args ->
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, args.encode()) }
    }
    val closeDetail: () -> Unit = { scope.launch { navigator.navigateBack() } }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = { AnimatedPane { listPane(openDetail) } },
        detailPane = {
            AnimatedPane {
                val key = navigator.currentDestination?.contentKey
                if (key != null) {
                    ExpenseEditScreen(
                        args = EditArgs.decode(key),
                        argsKey = key,
                        onDone = closeDetail,
                        onSettings = onSettings,
                    )
                } else {
                    DetailPlaceholder()
                }
            }
        },
    )
}

@Composable
private fun DetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                modifier = Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Sélectionnez une dépense", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "ou ajoutez-en une nouvelle depuis la liste : elle s'ouvrira ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
