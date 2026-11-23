package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.automation.LuaSyntaxResult
import com.example.data.LuaScript
import com.example.data.ScriptRunStatus
import com.example.data.UiState
import com.example.ui.theme.CodeEditorTextStyle
import com.example.ui.theme.CodeGutterDark
import com.example.ui.theme.CodeSurfaceDark
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.SyntaxApi
import com.example.ui.theme.SyntaxComment
import com.example.ui.theme.SyntaxKeyword
import com.example.ui.theme.SyntaxNumber
import com.example.ui.theme.SyntaxString

private val luaSnippets = listOf(
    "click()" to "click(540, 960, 60)\n",
    "swipe()" to "swipe(540, 1500, 540, 600, 350)\n",
    "getColor()" to "local hex, r, g, b = getColor(540, 960)\nlog('Warna: ' .. tostring(hex))\n",
    "waitForColor()" to "if waitForColor(540, 960, '#2DD4BF', 20, 5000) then\n    click(540, 960)\nend\n",
    "sleep()" to "sleep(800)\n",
    "for loop" to "for i = 1, 5 do\n    if not isRunning() then break end\n    log('Iterasi #' .. i)\n    sleep(500)\nend\n"
)

private val defaultNewLuaTemplate = """
|-- Script Lua Kustom TapLua
|local w, h = getScreenSize()
|log("Resolusi layar: " .. w .. "x" .. h)
|
|if waitForColor(540, 1200, "#2DD4BF", 20, 3000) then
|    log("Warna target cocok, melakukan klik!")
|    click(540, 1200, 60)
|else
|    log("Warna tidak ditemukan, lanjut klik default.")
|    click(math.floor(w / 2), math.floor(h / 2), 60)
|end
""".trimMargin()

@Composable
fun LuaStudioScreen(
    isExpandedScreen: Boolean,
    scriptsState: UiState<List<LuaScript>>,
    syntaxResult: LuaSyntaxResult?,
    onValidateSyntax: (String) -> Unit,
    onClearSyntaxResult: () -> Unit,
    onSaveScript: (String?, String, String, String, List<String>, (String) -> Unit) -> Unit,
    onDeleteScript: (String) -> Unit,
    onLaunchOverlayWithScript: (LuaScript) -> Unit,
    onRunScriptNow: (LuaScript) -> Unit
) {
    var editingScriptId by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    var draftTitle by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var draftCode by remember { mutableStateOf(defaultNewLuaTemplate) }
    var draftTagsRaw by remember { mutableStateOf("otomasi, lua") }
    var showApiDocs by remember { mutableStateOf(false) }

    fun openNewScript() {
        editingScriptId = null
        draftTitle = "Script Lua Baru"
        draftDesc = ""
        draftCode = defaultNewLuaTemplate
        draftTagsRaw = "lua, custom"
        onClearSyntaxResult()
        isEditing = true
    }

    fun openExistingScript(script: LuaScript) {
        editingScriptId = script.id
        draftTitle = script.title
        draftDesc = script.description
        draftCode = script.code
        draftTagsRaw = script.tags.joinToString(", ")
        onClearSyntaxResult()
        isEditing = true
    }

    if (!isExpandedScreen && isEditing) {
        BackHandler { isEditing = false }
    }

    val listPane: @Composable () -> Unit = {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { openNewScript() },
                    modifier = Modifier.testTag("add_lua_script_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Script Lua Baru")
                }
            }
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Lua Script Studio",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
                )
                when (scriptsState) {
                    is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is UiState.Error -> Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = scriptsState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    is UiState.Success -> {
                        if (scriptsState.data.isEmpty()) {
                            EmptyLuaState(onCreate = { openNewScript() })
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 84.dp)
                            ) {
                                items(scriptsState.data, key = { it.id }) { script ->
                                    LuaScriptCard(
                                        script = script,
                                        isSelected = isEditing && editingScriptId == script.id,
                                        onClick = { openExistingScript(script) },
                                        onOverlay = { onLaunchOverlayWithScript(script) },
                                        onRun = { onRunScriptNow(script) },
                                        onDelete = {
                                            if (editingScriptId == script.id) isEditing = false
                                            onDeleteScript(script.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val editorPane: @Composable () -> Unit = {
        val currentDraftScript = LuaScript(
            id = editingScriptId.orEmpty(),
            title = draftTitle.ifBlank { "Script Lua" },
            description = draftDesc,
            code = draftCode,
            tags = draftTagsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isExpandedScreen) {
                            IconButton(onClick = { isEditing = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                            }
                        }
                        Text("Editor Lua", style = MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { showApiDocs = !showApiDocs }) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = "Referensi API Lua",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(
                            onClick = {
                                val tags = draftTagsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                onSaveScript(editingScriptId, draftTitle, draftDesc, draftCode, tags) { savedId ->
                                    editingScriptId = savedId
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp).testTag("save_lua_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Simpan")
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(visible = showApiDocs) {
                    LuaApiReferenceCard()
                }
            }

            item {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    label = { Text("Judul Script") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("lua_title_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftDesc,
                        onValueChange = { draftDesc = it },
                        label = { Text("Catatan") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = draftTagsRaw,
                        onValueChange = { draftTagsRaw = it },
                        label = { Text("Tag (koma)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
            }

            // Quick Insert Snippets
            item {
                Text("Sisipkan Snippet API", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(luaSnippets) { (label, snippet) ->
                        AssistChip(
                            onClick = {
                                draftCode = if (draftCode.endsWith("\n") || draftCode.isEmpty()) {
                                    draftCode + snippet
                                } else {
                                    "$draftCode\n$snippet"
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Monospace Lua Code Editor with Line Gutter & Syntax Highlighting
            item {
                LuaCodeEditorBox(
                    code = draftCode,
                    onCodeChange = {
                        draftCode = it
                        onClearSyntaxResult()
                    }
                )
            }

            // Syntax Check Banner
            if (syntaxResult != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (syntaxResult.isValid) StatusEmerald.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (syntaxResult.isValid) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (syntaxResult.isValid) StatusEmerald else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = syntaxResult.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (syntaxResult.isValid) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Execution & Dry-Check Action Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onValidateSyntax(draftCode) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("validate_lua_button")
                    ) {
                        Text("Cek Sintaks")
                    }
                    OutlinedButton(
                        onClick = { onLaunchOverlayWithScript(currentDraftScript) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("lua_overlay_button")
                    ) {
                        Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Overlay")
                    }
                    Button(
                        onClick = { onRunScriptNow(currentDraftScript) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("run_lua_button")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Jalankan")
                    }
                }
            }
        }
    }

    if (isExpandedScreen) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) { listPane() }
            Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                if (isEditing) editorPane()
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Pilih script Lua atau tekan + untuk menulis script baru.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        if (isEditing) editorPane() else listPane()
    }
}

@Composable
private fun LuaCodeEditorBox(
    code: String,
    onCodeChange: (String) -> Unit
) {
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val syntaxVisualTransformation = remember { LuaSyntaxVisualTransformation() }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CodeSurfaceDark,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp, max = 420.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Line Number Gutter
            Box(
                modifier = Modifier
                    .background(CodeGutterDark)
                    .padding(horizontal = 10.dp, vertical = 14.dp)
            ) {
                Text(
                    text = lineNumbers,
                    style = CodeEditorTextStyle,
                    color = SyntaxComment
                )
            }
            // Highlighted Editor Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    textStyle = CodeEditorTextStyle.copy(color = Color(0xFFE2E8F0)),
                    cursorBrush = SolidColor(SyntaxApi),
                    visualTransformation = syntaxVisualTransformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 232.dp)
                        .testTag("lua_code_editor")
                )
            }
        }
    }
}

private class LuaSyntaxVisualTransformation : VisualTransformation {
    private val keywords = setOf(
        "local", "function", "if", "then", "else", "elseif", "end", "for",
        "while", "do", "repeat", "until", "return", "break", "and", "or",
        "not", "true", "false", "nil", "in"
    )
    private val tapLuaApis = setOf(
        "click", "longClick", "swipe", "sleep", "getColor", "matchColor",
        "waitForColor", "getScreenSize", "log", "print", "isRunning", "stop"
    )
    private val tokenRegex = Regex(
        """(--[^\n]*)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_][A-Za-z0-9_]*\b)"""
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val highlighted = buildAnnotatedString {
            append(raw)
            tokenRegex.findAll(raw).forEach { match ->
                val range = match.range
                val value = match.value
                val style = when {
                    match.groups[1] != null -> SpanStyle(color = SyntaxComment)
                    match.groups[2] != null -> SpanStyle(color = SyntaxString)
                    match.groups[3] != null -> SpanStyle(color = SyntaxNumber)
                    value in keywords -> SpanStyle(color = SyntaxKeyword, fontWeight = FontWeight.SemiBold)
                    value in tapLuaApis -> SpanStyle(color = SyntaxApi, fontWeight = FontWeight.Bold)
                    else -> null
                }
                if (style != null) {
                    addStyle(style, range.first, range.last + 1)
                }
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

@Composable
private fun LuaApiReferenceCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("TapLua Sandboxed Lua API", style = MaterialTheme.typography.titleSmall)
            val entries = listOf(
                "click(x, y, [durMs]) — Klik di koordinat piksel",
                "longClick(x, y, [durMs]) — Tahan sentuhan (default 600ms)",
                "swipe(x1, y1, x2, y2, [durMs]) — Geser layar halus",
                "sleep(ms) — Jeda kooperatif (langsung merespons Stop)",
                "hex, r, g, b = getColor(x, y) — Ambil warna piksel",
                "matchColor(x, y, '#HEX', [tol]) — Cek kecocokan warna",
                "waitForColor(x, y, '#HEX', [tol], [timeout]) — Tunggu warna",
                "w, h = getScreenSize() — Resolusi layar",
                "log(msg) / isRunning() / stop() — Kontrol & konsol live"
            )
            entries.forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EmptyLuaState(onCreate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text("Belum Ada Script Lua", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Tulis logika otomasi kustom menggunakan bahasa Lua ter-sandbox dengan deteksi warna dan live log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onCreate,
                modifier = Modifier.height(48.dp).testTag("empty_create_lua_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Tulis Script Pertama")
            }
        }
    }
}

@Composable
private fun LuaScriptCard(
    script: LuaScript,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOverlay: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val status = script.resolvedStatus()
    val statusColor = when (status) {
        ScriptRunStatus.SUCCESS -> StatusEmerald
        ScriptRunStatus.STOPPED -> StatusAmber
        ScriptRunStatus.ERROR -> StatusCrimson
        ScriptRunStatus.NEVER -> MaterialTheme.colorScheme.outline
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("lua_item_card_${script.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(script.title, style = MaterialTheme.typography.titleMedium)
                    if (script.description.isNotBlank()) {
                        Text(
                            text = script.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Hapus Script", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = statusColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOverlay) {
                        Icon(Icons.Filled.Layers, contentDescription = "Jalankan via Overlay")
                    }
                    IconButton(onClick = onRun) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Jalankan Script Lua",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
