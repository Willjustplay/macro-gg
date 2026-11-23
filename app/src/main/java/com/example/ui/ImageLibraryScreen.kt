package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.automation.OverlayCropView
import com.example.data.ImageAsset
import java.io.File

private const val FILTER_ALL = "__ALL__"
private const val FILTER_ROOT = ""

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageLibraryScreen(
    images: List<ImageAsset>,
    folders: List<String>,
    onCropFromScreen: (folder: String) -> Unit,
    onImportFromGallery: (folder: String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveImages: (Collection<String>, targetFolder: String) -> Unit,
    onRecropImage: (String, Bitmap) -> Unit,
    onExportImage: (ImageAsset) -> Unit,
    onExportMultipleImages: (Collection<String>) -> Unit,
    onRenameImage: (String, String) -> Unit,
    onDeleteImage: (String) -> Unit,
    onDeleteMultipleImages: (Collection<String>) -> Unit
) {
    var activeFilter by remember { mutableStateOf(FILTER_ALL) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    var detailAsset by remember { mutableStateOf<ImageAsset?>(null) }
    var recropAsset by remember { mutableStateOf<ImageAsset?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var showMoveDialogFor by remember { mutableStateOf<Set<String>?>(null) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    // If active folder was deleted, fall back to ALL
    if (activeFilter != FILTER_ALL && activeFilter != FILTER_ROOT && activeFilter !in folders) {
        activeFilter = FILTER_ALL
    }

    val currentTargetFolder = if (activeFilter == FILTER_ALL) "" else activeFilter

    val visibleImages = remember(images, activeFilter) {
        when (activeFilter) {
            FILTER_ALL -> images
            FILTER_ROOT -> images.filter { it.folder.isBlank() }
            else -> images.filter { it.folder == activeFilter }
        }
    }

    // Drop any deleted names from selection
    val validNameSet = remember(images) { images.map { it.name }.toSet() }
    val activeSelected = remember(selectedNames, validNameSet) { selectedNames.intersect(validNameSet) }

    fun toggleSelect(name: String) {
        val next = if (name in activeSelected) activeSelected - name else activeSelected + name
        selectedNames = next
        if (next.isEmpty()) selectionMode = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Animated transition between Normal Header and Multi-Select Toolbar
        AnimatedContent(
            targetState = selectionMode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "libraryHeader"
        ) { inSelection ->
            if (inSelection) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    selectionMode = false
                                    selectedNames = emptySet()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Batal Pilih")
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${activeSelected.size} gambar dipilih",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val visibleSet = visibleImages.map { it.name }.toSet()
                                    selectedNames = if (visibleSet.all { it in activeSelected } && visibleSet.isNotEmpty()) {
                                        activeSelected - visibleSet
                                    } else {
                                        activeSelected + visibleSet
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.SelectAll, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pilih Semua", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { showMoveDialogFor = activeSelected },
                                enabled = activeSelected.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pindah", style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (activeSelected.size == 1) {
                                        val one = images.firstOrNull { it.name == activeSelected.first() }
                                        if (one != null) onExportImage(one)
                                    } else if (activeSelected.size > 1) {
                                        onExportMultipleImages(activeSelected)
                                    }
                                    selectionMode = false
                                    selectedNames = emptySet()
                                },
                                enabled = activeSelected.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ekspor", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { showBulkDeleteConfirm = true },
                                enabled = activeSelected.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.DeleteOutline, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Hapus", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pustaka Gambar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text(
                                "Tambah banyak gambar sekaligus, atur folder, atau crop layar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (images.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { selectionMode = true },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.CheckBox, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pilih", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onCropFromScreen(currentTargetFolder) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Crop Layar", maxLines = 1, fontWeight = FontWeight.SemiBold)
                        }
                        FilledTonalButton(
                            onClick = { onImportFromGallery(currentTargetFolder) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.15f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Galeri (Multi)", maxLines = 1, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Folder Chips Bar: Semua | Utama | Custom Folders | + Folder
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item(key = "folder_all") {
                FilterChip(
                    selected = activeFilter == FILTER_ALL,
                    onClick = { activeFilter = FILTER_ALL },
                    label = { Text("Semua (${images.size})", style = MaterialTheme.typography.labelSmall) }
                )
            }
            item(key = "folder_root") {
                val rootCount = images.count { it.folder.isBlank() }
                FilterChip(
                    selected = activeFilter == FILTER_ROOT,
                    onClick = { activeFilter = FILTER_ROOT },
                    label = { Text("Utama ($rootCount)", style = MaterialTheme.typography.labelSmall) }
                )
            }
            items(folders, key = { "folder_$it" }) { folderName ->
                val count = images.count { it.folder == folderName }
                val isSelected = activeFilter == folderName
                FilterChip(
                    selected = isSelected,
                    onClick = { activeFilter = folderName },
                    leadingIcon = {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
                    },
                    label = { Text("$folderName ($count)", style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Hapus folder",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { folderToDelete = folderName }
                            )
                        }
                    } else null
                )
            }
            item(key = "folder_add") {
                OutlinedButton(
                    onClick = { showCreateFolderDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Folder", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (visibleImages.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.ImageSearch, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (activeFilter == FILTER_ALL) "Belum ada gambar target" else "Folder ini masih kosong",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Tekan 'Crop Layar' atau 'Galeri (Multi)' untuk memilih banyak gambar sekaligus, atau pilih gambar lalu tekan 'Pindah' untuk memasukkannya ke folder ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 144.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visibleImages, key = { it.name }) { asset ->
                    val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                    val isSelected = asset.name in activeSelected
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) toggleSelect(asset.name)
                                    else detailAsset = asset
                                },
                                onLongClick = {
                                    selectionMode = true
                                    toggleSelect(asset.name)
                                }
                            ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.4f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0A1020)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = asset.displayName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp)
                                    )
                                }
                                if (selectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xAA0A1020))
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                } else if (asset.folder.isNotBlank() && activeFilter == FILTER_ALL) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xCC0A1020)
                                    ) {
                                        Text(
                                            "📁 ${asset.folder}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF22D3EE),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Text(
                                asset.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${asset.width}×${asset.height} px • ${(asset.sizeBytes / 1024).coerceAtLeast(1)} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        var folderInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Buat Folder Gambar") },
            text = {
                OutlinedTextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    label = { Text("Nama folder (mis. Boss, Menu, Skill)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderInput.isNotBlank()) {
                            onCreateFolder(folderInput)
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = folderInput.isNotBlank()
                ) { Text("Buat") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateFolderDialog = false }) { Text("Batal") }
            }
        )
    }

    // Delete Folder Confirm Dialog
    folderToDelete?.let { fname ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Hapus Folder '$fname'?") },
            text = {
                Text("Folder akan dihapus, tetapi semua gambar di dalamnya tetap aman dan dipindahkan ke 'Utama' (referensi macro otomatis diperbarui).")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFolder(fname)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Hapus Folder") }
            },
            dismissButton = {
                OutlinedButton(onClick = { folderToDelete = null }) { Text("Batal") }
            }
        )
    }

    // Move Images to Folder Dialog
    showMoveDialogFor?.let { namesToMove ->
        AlertDialog(
            onDismissRequest = { showMoveDialogFor = null },
            title = { Text("Pindahkan ${namesToMove.size} Gambar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Pilih folder tujuan (referensi gambar di semua macro otomatis disesuaikan):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            onMoveImages(namesToMove, "")
                            showMoveDialogFor = null
                            selectionMode = false
                            selectedNames = emptySet()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Utama (Tanpa Folder)")
                    }
                    folders.forEach { folderName ->
                        FilledTonalButton(
                            onClick = {
                                onMoveImages(namesToMove, folderName)
                                showMoveDialogFor = null
                                selectionMode = false
                                selectedNames = emptySet()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Folder, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(folderName)
                        }
                    }
                    TextButton(
                        onClick = {
                            showMoveDialogFor = null
                            showCreateFolderDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("+ Buat Folder Baru")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showMoveDialogFor = null }) { Text("Batal") }
            }
        )
    }

    // Bulk Delete Confirm Dialog
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Hapus ${activeSelected.size} Gambar?") },
            text = { Text("Gambar yang dipilih akan dihapus permanen dari pustaka.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteMultipleImages(activeSelected)
                        showBulkDeleteConfirm = false
                        selectionMode = false
                        selectedNames = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Hapus Semua") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBulkDeleteConfirm = false }) { Text("Batal") }
            }
        )
    }

    // Single Image Detail Dialog
    detailAsset?.let { asset ->
        var newName by remember(asset.name) { mutableStateOf(asset.displayName.removeSuffix(".png")) }
        val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
        AlertDialog(
            onDismissRequest = { detailAsset = null },
            title = { Text("Detail Gambar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bmp != null) {
                            Image(bitmap = bmp, contentDescription = asset.displayName, contentScale = ContentScale.Fit, modifier = Modifier.padding(8.dp))
                        }
                    }
                    Text(
                        "Ukuran: ${asset.width}×${asset.height} px • Folder: ${asset.folder.ifBlank { "Utama" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Di Lua: findImage(\"${asset.name}\", 0.80)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FilledTonalButton(
                        onClick = {
                            detailAsset = null
                            recropAsset = asset
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Crop, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Potong Ulang Gambar (Re-Crop)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                detailAsset = null
                                showMoveDialogFor = setOf(asset.name)
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pindah", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                detailAsset = null
                                onExportImage(asset)
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ekspor .png", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Ubah nama") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank() && "${newName.trim()}.png" != asset.displayName) {
                        onRenameImage(asset.name, newName)
                    }
                    detailAsset = null
                }) {
                    Icon(Icons.Filled.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Simpan")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        onDeleteImage(asset.name)
                        detailAsset = null
                    }) {
                        Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { detailAsset = null }) { Text("Tutup") }
                }
            }
        )
    }

    recropAsset?.let { asset ->
        InAppRecropDialog(
            asset = asset,
            onSave = { cropped ->
                onRecropImage(asset.name, cropped)
                recropAsset = null
            },
            onDismiss = { recropAsset = null }
        )
    }
}

/**
 * Full-screen zoomable Re-Crop dialog reusing [OverlayCropView] so users can trim any
 * already-cropped image right inside the app without needing MediaProjection active.
 */
@Composable
fun InAppRecropDialog(
    asset: ImageAsset,
    onSave: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val sourceBitmap = remember(asset.path, asset.updatedAt) {
        val f = File(asset.path)
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
    }

    DisposableEffect(sourceBitmap) {
        onDispose { sourceBitmap?.recycle() }
    }

    if (sourceBitmap == null) {
        onDismiss()
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            factory = { ctx ->
                OverlayCropView(
                    context = ctx,
                    screenshot = sourceBitmap,
                    onSave = { cropped, _ -> onSave(cropped) },
                    onCancel = onDismiss
                )
            }
        )
    }
}
