package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.LuaSyntaxResult
import com.example.data.ImageAsset
import com.example.data.LuaScript
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun LuaStudioScreen(
    scripts: List<LuaScript>,
    images: List<ImageAsset>,
    syntaxResult: LuaSyntaxResult?,
    onCreateScript: () -> LuaScript,
    onSaveScript: (LuaScript) -> LuaScript,
    onDeleteScript: (String) -> Unit,
    onValidateSyntax: (String) -> Unit,
    onClearSyntaxResult: () -> Unit,
    onLaunchOverlayWithScript: (LuaScript) -> Unit,
    onRunScriptNow: (LuaScript) -> Unit
) {
    var selectedId by remember { mutableStateOf(scripts.firstOrNull()?.id.orEmpty()) }
    val selectedScript = scripts.firstOrNull { it.id == selectedId } ?: scripts.firstOrNull()

    var title by remember(selectedScript?.id) { mutableStateOf(selectedScript?.name ?: "Script Baru") }
    var code by remember(selectedScript?.id) { mutableStateOf(selectedScript?.code ?: "") }

    LaunchedEffect(scripts) {
        if (selectedScript != null && selectedId != selectedScript.id) {
            selectedId = selectedScript.id
            title = selectedScript.name
            code = selectedScript.code
        }
    }

    val sampleImg = images.firstOrNull()?.name ?: "target.png"
    val snippets = remember(sampleImg) {
        listOf(
            "findImage" to "\nlocal ok, x, y, score = findImage(\"$sampleImg\", 0.85)\nif ok then click(x, y) end",
            "waitImage" to "\nlocal ok, x, y = waitImage(\"$sampleImg\", 0.85, 5000)\nif ok then click(x, y) end",
            "clickImage" to "\nclickImage(\"$sampleImg\", 0.85, 3000)",
            "findAll" to "\nlocal list = findAllImages(\"$sampleImg\", 0.85)\nfor _, p in ipairs(list) do click(p.x, p.y) sleep(200) end",
            "click" to "\nclick(540, 1200, 50)",
            "swipe" to "\nswipe(540, 1500, 540, 800, 350)",
            "zoomIn" to "\nzoomIn(540, 1000, 260, 400)",
            "zoomOut" to "\nzoomOut(540, 1000, 260, 400)",
            "matchColor" to "\nif matchColor(540, 1200, \"#2DD4BF\", 15) then click(540, 1200) end",
            "sleep" to "\nsleep(500)",
            "log" to "\nlog(\"Pesan log\")"
        )
    }

    fun currentDraft(): LuaScript =
        (selectedScript ?: LuaScript()).copy(name = title.trim().ifBlank { "Script Baru" }, code = code)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Script selector row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item(key = "new_script") {
                FilledTonalButton(
                    onClick = {
                        val created = onCreateScript()
                        selectedId = created.id
                        title = created.name
                        code = created.code
                        onClearSyntaxResult()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Baru")
                }
            }
            items(scripts, key = { it.id }) { s ->
                FilterChip(
                    selected = s.id == selectedScript?.id,
                    onClick = {
                        selectedId = s.id
                        title = s.name
                        code = s.code
                        onClearSyntaxResult()
                    },
                    label = { Text(s.name) }
                )
            }
        }

        // Title + delete
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Nama Script Lua") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (selectedScript != null) {
                IconButton(onClick = {
                    onDeleteScript(selectedScript.id)
                    selectedId = ""
                }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Quick API snippet chips (Macrorify image detection + touch)
        Text("Sisipkan API Cepat:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(snippets, key = { it.first }) { (label, snippet) ->
                AssistChip(
                    onClick = {
                        code = (code.trimEnd() + snippet).trimStart()
                        onClearSyntaxResult()
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // Monospace code editor
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                onClearSyntaxResult()
            },
            label = { Text("Kode Lua (Sandbox Aman)") },
            textStyle = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
        )

        // Syntax validation banner
        syntaxResult?.let { res ->
            val bg = if (res.isValid) Color(0xFF10B981).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer
            val fg = if (res.isValid) Color(0xFF10B981) else MaterialTheme.colorScheme.onErrorContainer
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = bg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (res.isValid) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(res.message, style = MaterialTheme.typography.bodySmall, color = fg)
                }
            }
        }

        // Action buttons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onValidateSyntax(code) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.Verified, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cek", maxLines = 1)
            }
            FilledTonalButton(
                onClick = {
                    val saved = onSaveScript(currentDraft())
                    selectedId = saved.id
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Simpan", maxLines = 1)
            }
            Button(
                onClick = {
                    val saved = onSaveScript(currentDraft())
                    selectedId = saved.id
                    onLaunchOverlayWithScript(saved)
                },
                modifier = Modifier.weight(1.2f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.Layers, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Overlay", maxLines = 1)
            }
        }

        OutlinedButton(
            onClick = {
                val saved = onSaveScript(currentDraft())
                selectedId = saved.id
                onRunScriptNow(saved)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Jalankan Sekarang")
        }

        // Mini API Cheat Sheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Referensi API Lua", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                """
                • findImage(nama, [akurasi], [x1,y1,x2,y2]) → ok, x, y, skor
                • waitImage(nama, [akurasi], [timeoutMs]) → ok, x, y, skor
                • clickImage(nama, [akurasi], [timeoutMs], [ox], [oy]) → ok, x, y
                • findAllImages(nama, [akurasi], [max]) → tabel {x, y, score}
                • click(x, y, [ms]) • longClick(x, y, [ms]) • swipe(x1,y1,x2,y2,[ms])
                • getColor(x, y) • matchColor(x, y, hex, [tol]) • waitForColor(...)
                • sleep(ms) • log(teks) • isRunning() • stop()
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
