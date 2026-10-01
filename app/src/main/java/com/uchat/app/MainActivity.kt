package com.uchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uchat.app.ui.AIChatApp
import com.uchat.app.ui.UiIntent
import com.uchat.app.viewmodel.ChatViewModel
import com.uchat.app.viewmodel.ChatViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as AIChatApplication).repository
        val toolRegistry = (application as AIChatApplication).toolRegistry
        val factory = ChatViewModelFactory(repository, toolRegistry, applicationContext)

        setContent {
            val viewModel: ChatViewModel = viewModel(factory = factory)
            val uiState by viewModel.uiState.collectAsState()

            AIChatApp(
                uiState = uiState,
                onIntent = { intent: UiIntent ->
                    when (intent) {
                        is UiIntent.ShowSettings -> { viewModel.showSettings() }
                        is UiIntent.HideSettings -> { viewModel.hideSettings() }
                        is UiIntent.NewChat -> { viewModel.newChat() }
                        is UiIntent.GoToList -> { viewModel.goToList() }
                        is UiIntent.SelectChat -> { viewModel.selectChat(intent.chatId) }
                        is UiIntent.DeleteChat -> { viewModel.deleteChat(intent.chatId) }
                        is UiIntent.SendMessage -> { viewModel.sendMessage(intent.text) }
                        is UiIntent.SetActiveModel -> { viewModel.setActiveModel(intent.modelId) }
                        is UiIntent.ToggleDarkMode -> { viewModel.toggleDarkMode() }
                        is UiIntent.StartAddModel -> { viewModel.startAddModel() }
                        is UiIntent.StartEditModel -> { viewModel.startEditModel(intent.model) }
                        is UiIntent.UpdateEditingModel -> { viewModel.updateEditingModel(intent.model) }
                        is UiIntent.SaveEditingModel -> { viewModel.saveEditingModel() }
                        is UiIntent.CancelEditModel -> { viewModel.cancelEditModel() }
                        is UiIntent.DeleteEditingModel -> { viewModel.deleteEditingModel() }
                        is UiIntent.FetchModels -> { viewModel.fetchModels() }
                        is UiIntent.TestApi -> { viewModel.testApi() }
                        is UiIntent.ClearToast -> { viewModel.clearToast() }
                        is UiIntent.ExportChats -> { viewModel.exportChats() }
                        is UiIntent.ClearChat -> { viewModel.clearCurrentChat() }
                        is UiIntent.Regenerate -> { viewModel.regenerateLastResponse() }
                        is UiIntent.StopGenerating -> { viewModel.stopGenerating() }
                        is UiIntent.TruncateFrom -> { viewModel.truncateFrom(intent.messageId) }
                        is UiIntent.StartEditTitle -> { viewModel.startEditTitle() }
                        is UiIntent.CancelEditTitle -> { viewModel.cancelEditTitle() }
                        is UiIntent.UpdateChatTitle -> { viewModel.updateChatTitle(intent.chatId, intent.title) }
                        is UiIntent.SetMessageFontSize -> { viewModel.setMessageFontSize(intent.size) }
                        is UiIntent.ExportBackup -> { viewModel.prepareExport() }
                        is UiIntent.ClearExportPayload -> { viewModel.clearExportPayload() }
                        is UiIntent.ImportBackup -> { viewModel.importData(intent.json) }
                        is UiIntent.ClearAllData -> { viewModel.clearAllData() }
                        is UiIntent.FetchOpenCodeModels -> { viewModel.fetchOpenCodeFreeModels() }
                        is UiIntent.AddOpenCodeModel -> { viewModel.addOpenCodeModel(intent.modelId) }
                        is UiIntent.AddAttachment -> { viewModel.addAttachment(intent.attachment) }
                        is UiIntent.RemoveAttachment -> { viewModel.removeAttachment(intent.index) }
                        is UiIntent.SetThemeMode -> { viewModel.setThemeMode(intent.mode) }
                        is UiIntent.SetAccentColor -> { viewModel.setAccentColor(intent.color) }
                        is UiIntent.ToggleSuperChat -> { viewModel.toggleSuperChat() }
                        is UiIntent.SetApprovalThreshold -> { viewModel.setApprovalThreshold(intent.level) }
                        is UiIntent.ApproveTool -> { viewModel.approveTool(intent.allow) }
                    }
                }
            )
        }
    }
}
