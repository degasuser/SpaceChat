package dev.spacechat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import dev.spacechat.SpaceViewModel
import dev.spacechat.data.ApiSettings
import dev.spacechat.data.ChatEntity
import dev.spacechat.data.MessageEntity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun SpaceApp(vm: SpaceViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it, actionLabel = "OK", duration = SnackbarDuration.Long); vm.dismissError() } }
    SpaceTheme(settings.theme, settings.accent, settings.dynamicColors) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            if(!ready) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else Box(Modifier.fillMaxSize().padding(padding)) {
                when(screen) { "settings" -> SettingsScreen(vm, settings); "chat" -> ChatScreen(vm); else -> HomeScreen(vm) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: SpaceViewModel) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf<ChatEntity?>(null) }
    var delete by remember { mutableStateOf<ChatEntity?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("SpaceChat", fontWeight = FontWeight.Bold); Text("~/ conversations", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, actions = { IconButton(onClick = { vm.navigate("settings") }) { Icon(Icons.Default.Settings, "Настройки") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = vm::newChat, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Новый чат") }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(value = query, onValueChange = vm::setQuery, placeholder = { Text("Поиск по истории") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
            Text(settings.model.ifBlank { "model: не выбран" }, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 16.dp))
            if(chats.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(">_", style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(if(query.isBlank()) "Пространство для ваших идей" else "Ничего не найдено", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(if(query.isBlank()) "Добавьте API в настройках и начните диалог" else "Попробуйте другой запрос", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if(query.isBlank() && settings.apiKey.isBlank()) TextButton(onClick = { vm.navigate("settings") }) { Text("Настроить подключение") }
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats, key = { it.id }) { chat ->
                    var menu by remember { mutableStateOf(false) }
                    Card(onClick = { vm.openChat(chat.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(chat.title, maxLines = 2, style = MaterialTheme.typography.titleMedium); Text(chat.model.ifBlank { "выберите модель" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия с диалогом") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = chat })
                                    DropdownMenuItem(text = { Text("Удалить") }, enabled = !busy, onClick = { menu = false; delete = chat })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    rename?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Название диалога") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { vm.rename(chat.id, title); rename = null }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("Отмена") } })
    }
    delete?.let { chat -> ConfirmDialog("Удалить диалог?", "Сообщения и изображения будут удалены с устройства.", { vm.delete(chat.id); delete = null }, { delete = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: SpaceViewModel) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val attachment by vm.attachment.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val retrySeconds by vm.retrySeconds.collectAsStateWithLifecycle()
    var modelDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val list = rememberLazyListState()
    var follow by rememberSaveable(chat?.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }
    BackHandler { vm.navigate("home") }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canForward) -> if(scrolling) follow = !canForward else if(!canForward) follow = true }
    }
    LaunchedEffect(messages.size) { if(follow && messages.isNotEmpty()) list.scrollToItem(messages.size) }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(chat?.title ?: "Диалог", maxLines = 1, style = MaterialTheme.typography.titleMedium); TextButton(enabled = !busy, onClick = { modelDialog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) { Text(chat?.model?.ifBlank { "Выбрать Model ID ▾" } ?: "Model ID ▾", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 1) } } }, navigationIcon = { IconButton(onClick = { vm.navigate("home") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К диалогам") } }, actions = { IconButton(onClick = { vm.navigate("settings") }) { Icon(Icons.Default.Settings, "Настройки") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if(messages.isEmpty()) item { Column(Modifier.padding(vertical = 48.dp)) { Text("// новый сигнал", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace); Text("С чего начнём?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp)); Text("Сообщения хранятся на устройстве. Контекст отправляется выбранному API.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) } }
                    items(messages, key = { it.id }, contentType = { it.role }) { message -> MessageCard(message, vm, busy, onEdit = { editTarget = message }) }
                    item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
                }
                if(!follow && messages.isNotEmpty()) FilledTonalButton(onClick = { follow = true; scope.launchScroll { list.animateScrollToItem(messages.size) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("↓ К последнему") }
            }
            StreamAutoScroll(vm, follow, list, messages.size)
            if(messages.any { it.role == "user" } && !busy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::regenerate, enabled = retrySeconds == 0L) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if(retrySeconds > 0) "Повтор через ${retrySeconds}с" else "Повторить ответ") }
            }
            if(editing != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Редактирование · последующие сообщения удалятся", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); IconButton(onClick = vm::cancelEdit) { Icon(Icons.Default.Close, "Отменить редактирование") } }
            attachment?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("▧ Изображение прикреплено", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); IconButton(onClick = vm::removeAttachment, enabled = !busy) { Icon(Icons.Default.Close, "Убрать изображение") } } }
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    TextButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf", "text/*")) }, enabled = !busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("＋", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = "Добавить вложение" }) }
                    OutlinedTextField(draft, vm::setDraft, placeholder = { Text("Сообщение…") }, modifier = Modifier.weight(1f).heightIn(max = 180.dp), maxLines = 6, shape = MaterialTheme.shapes.large)
                    Spacer(Modifier.width(6.dp))
                    if(busy) FilledIconButton(onClick = vm::stop, modifier = Modifier.size(48.dp)) { Text("■", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Остановить генерацию" }) }
                    else FilledIconButton(onClick = vm::send, enabled = (draft.isNotBlank() || attachment != null) && retrySeconds == 0L, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
                }
            }
        }
    }
    if(modelDialog) ModelDialog(vm, chat?.model.orEmpty(), { vm.setModel(it); modelDialog = false }, { modelDialog = false })
    editTarget?.let { message -> ConfirmDialog("Редактировать сообщение?", "Ответ и все последующие сообщения будут удалены после отправки новой версии.", { vm.edit(message); editTarget = null }, { editTarget = null }) }
}

private fun kotlinx.coroutines.CoroutineScope.launchScroll(block: suspend () -> Unit) = launch { block() }

@Composable
private fun StreamAutoScroll(vm: SpaceViewModel, follow: Boolean, list: androidx.compose.foundation.lazy.LazyListState, bottom: Int) {
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    LaunchedEffect(tokens.length, bottom) { if(follow && !list.isScrollInProgress && bottom > 0) list.scrollToItem(bottom) }
}

@Composable
private fun MessageCard(message: MessageEntity, vm: SpaceViewModel, busy: Boolean, onEdit: () -> Unit) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Surface(color = if(user) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if(user) 14.dp else 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(if(user) "you" else "space / assistant", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if(message.status != "streaming") TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, contentPadding = PaddingValues(4.dp)) { Text("Копировать", style = MaterialTheme.typography.labelSmall) }
                if(user && !busy) IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Edit, "Редактировать сообщение", Modifier.size(16.dp)) }
            }
            if(message.imagePath != null) Text("▧ Изображение · JPEG", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
            if(message.status == "streaming") StreamingMessage(vm) else if(message.text.isNotBlank()) MarkdownText(message.text)
            if(message.status in setOf("interrupted", "error")) Text(if(message.status == "error") "Ответ не завершён · повторите вручную" else "Генерация остановлена", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun StreamingMessage(vm: SpaceViewModel) {
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    if(tokens.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  Ожидание ответа…", style = MaterialTheme.typography.bodyMedium) }
    else SelectionContainer { Text(tokens, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun ModelDialog(vm: SpaceViewModel, current: String, select: (String) -> Unit, dismiss: () -> Unit) {
    var model by remember { mutableStateOf(current) }
    val models by vm.models.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.toolsBusy.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Модель диалога") }, text = {
        Column {
            OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true)
            TextButton(onClick = { vm.fetchModels(settings) }, enabled = !loading) { Text(if(loading) "Загрузка…" else "Получить модели API") }
            LazyColumn(Modifier.heightIn(max = 240.dp)) { items(models, key = { it }) { id -> Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 12.dp)) } }
            Text("Если endpoint /models недоступен, введите ID из документации провайдера.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { select(model.trim()) }, enabled = model.isNotBlank()) { Text("Выбрать") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: SpaceViewModel, settings: ApiSettings) {
    var url by rememberSaveable(settings) { mutableStateOf(settings.baseUrl) }
    var key by remember(settings) { mutableStateOf(vm.unsavedApiKey ?: settings.apiKey) }
    var model by rememberSaveable(settings) { mutableStateOf(settings.model) }
    var prompt by rememberSaveable(settings) { mutableStateOf(settings.systemPrompt) }
    var timeout by rememberSaveable(settings) { mutableStateOf(settings.timeoutSeconds.toString()) }
    var theme by rememberSaveable(settings) { mutableStateOf(settings.theme) }
    var accent by rememberSaveable(settings) { mutableStateOf(settings.accent) }
    var dynamic by rememberSaveable(settings) { mutableStateOf(settings.dynamicColors) }
    var images by rememberSaveable(settings) { mutableStateOf(settings.imagesEnabled) }
    var visible by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val models by vm.models.collectAsStateWithLifecycle()
    val loading by vm.toolsBusy.collectAsStateWithLifecycle()
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    fun config() = ApiSettings(url.trim(), key.trim(), model.trim(), prompt, timeout.toIntOrNull() ?: 0, theme, accent, dynamic, images)
    BackHandler { vm.navigate("home") }
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }, navigationIcon = { IconButton(onClick = { vm.navigate("home") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К диалогам") } }, actions = { TextButton(onClick = { vm.saveSettings(config()) { vm.navigate("home") } }, enabled = !storageError) { Text("Сохранить") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { SectionLabel("01 / API CONNECTION") }
            item { Text("Ключ и контекст отправляются только на указанный вами HTTPS-сервер. Выберите провайдера, которому доверяете.", style = MaterialTheme.typography.bodySmall) }
            item { OutlinedTextField(url, { url = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), label = { Text("Base URL") }, placeholder = { Text("https://ваш-сервер/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(key, { key = it; vm.unsavedApiKey = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), label = { Text("API Key") }, visualTransformation = if(visible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if(visible) "Скрыть" else "Показать") } }) }
            item { OutlinedTextField(model, { model = it }, label = { Text("Model ID по умолчанию") }, supportingText = { Text("Используется для новых диалогов") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedButton(onClick = { vm.fetchModels(config()) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { if(loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(if(loading) "  Проверка…" else "Проверить API / получить модели") } }
            if(models.isNotEmpty()) item { Column {
                Text("Модели API · нажмите для выбора", style = MaterialTheme.typography.labelMedium)
                // Bound layout work; the compact chat selector exposes the entire returned list.
                models.take(30).forEach { id -> Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 10.dp)) }
                if(models.size > 30) Text("Все ${models.size} моделей доступны в селекторе чата.", style = MaterialTheme.typography.bodySmall)
            } }
            item { OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt (необязательно)") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6) }
            item { OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(3) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Таймаут чтения, секунд · 10–600") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { SwitchRow("Изображения image_url", "Включайте только если API и модель поддерживают этот формат", images) { images = it } }
            item { SectionLabel("02 / TERMINAL SPACE") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("system" to "Авто", "dark" to "Тёмная", "light" to "Светлая").forEach { (value, title) -> FilterChip(theme == value, { theme = value }, { Text(title) }) } } }
            item { SwitchRow("Цвета Android", "Material You · Android 12+", dynamic) { dynamic = it } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("cyan" to "Cyan", "violet" to "Violet", "mint" to "Mint").forEach { (value, title) -> FilterChip(accent == value, { accent = value }, { Text(title) }, enabled = !dynamic) } } }
            item { SectionLabel("03 / LOCAL DATA") }
            item { Text("История хранится в Room на устройстве. Резервное копирование Android отключено. Вложение: до 12 МБ, JPEG / PNG / WebP; перед отправкой размер уменьшается.", style = MaterialTheme.typography.bodySmall) }
            item { OutlinedButton(onClick = { clear = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Удалить всю историю") } }
            item { OutlinedButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить настройки и API-ключ") } }
            item { SectionLabel("04 / RESEARCH") }
            item { Text("Deep Research недоступен", style = MaterialTheme.typography.titleSmall); Text("Режим появится после подключения API исследований или собственного агента с поиском, источниками и прогрессом.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            item { Text("SpaceChat 0.1.0 · без аналитики и рекламы", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
    if(clear) ConfirmDialog("Очистить историю?", "Все диалоги и изображения будут удалены безвозвратно.", { vm.clearHistory(); clear = false }, { clear = false })
    if(reset) ConfirmDialog("Сбросить настройки?", "API-ключ, адрес сервера и настройки оформления будут удалены. История сохранится.", { vm.resetSettings(); reset = false }, { reset = false })
}

@Composable
private fun SectionLabel(text: String) { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }

@Composable
private fun SwitchRow(title: String, subtitle: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; Spacer(Modifier.width(12.dp)); Switch(value, change) }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = confirm) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
