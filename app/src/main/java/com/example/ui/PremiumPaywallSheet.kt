package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.billing.BillingUiState
import com.example.billing.PremiumFeature
import com.example.billing.SubscriptionOfferUi
import com.example.billing.SubscriptionPlan

val ProGoldColor = Color(0xFFF59E0B)
val ProGoldLightColor = Color(0xFFFDE68A)
val ProCardBg = Color(0xFF1A172A)

/**
 * Compact golden "PRO" lock pill reused across buttons, action picker rows, and settings cards.
 */
@Composable
fun ProLockBadge(
    modifier: Modifier = Modifier,
    text: String = "PRO"
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = ProGoldColor.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, ProGoldColor.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = ProGoldColor,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = text,
                color = ProGoldLightColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * Play Store compliant Subscription Paywall sheet for Wjust macro PRO (Monthly & Yearly plans).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumPaywallSheet(
    feature: PremiumFeature,
    customReason: String? = null,
    billingState: BillingUiState,
    onSelectSubscribe: (SubscriptionPlan) -> Unit,
    onRestorePurchases: () -> Unit,
    onManageSubscriptions: () -> Unit,
    onDebugTogglePremium: ((Boolean?) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPlan by remember { mutableStateOf(SubscriptionPlan.YEARLY) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E1426),
        contentColor = Color.White,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFF59E0B), Color(0xFF22D3EE))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WorkspacePremium,
                            contentDescription = null,
                            tint = Color(0xFF0A1020),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Wjust macro PRO",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Otomasi Android penuh tanpa batas",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Tutup", tint = Color(0xFF94A3B8))
                }
            }

            // Context Banner (why the paywall was opened)
            if (feature != PremiumFeature.UPGRADE_GENERAL || !customReason.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ProGoldColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ProGoldColor.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = ProGoldColor,
                            modifier = Modifier.padding(top = 2.dp).size(18.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = feature.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ProGoldLightColor
                            )
                            Text(
                                text = customReason?.takeIf { it.isNotBlank() } ?: feature.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }

            // Free vs PRO feature checklist
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF141C33),
                border = BorderStroke(1.dp, Color(0xFF233052)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Yang Anda Dapatkan di PRO",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF22D3EE)
                    )
                    val benefits = listOf(
                        "Macro tanpa batas (Gratis dibatasi maks. 3 macro)",
                        "Rotasi Multi-Akun tanpa batas untuk semua game — OCR + Auto-Scroll, Clone App & Hybrid (Gratis maks. 2 akun)",
                        "Semua 22 aksi: Cabang Urutan, Tunggu, Loop, Teks OCR, Warna, Kejar Objek, Rekam Sentuhan, Keluar/Masuk Aplikasi & Hubungkan Macro",
                        "Ekspor & Impor Macro (.zip), Macro Set, dan Backup Penuh",
                        "Kunci Sandi, Batas Waktu (Tenggat), Mode Pasif & Logo Kustom",
                        "Auto-Scale Resolusi Lintas Layar & Watchdog Anti-Macet",
                        "Lua Studio penuh untuk script tingkat lanjut"
                    )
                    benefits.forEach { line ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.padding(top = 1.dp).size(16.dp)
                            )
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF233052), modifier = Modifier.padding(vertical = 2.dp))
                    Text(
                        text = "Versi Gratis tetap aktif selamanya untuk: Klik, Swipe, dan Deteksi Gambar (hingga 3 macro).",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Plan Selector (Monthly + Yearly ONLY)
            if (billingState.isPremium) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF064E3B).copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, Color(0xFF10B981)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Status: ${billingState.activePlanLabel}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Semua fitur Wjust macro PRO sudah terbuka di perangkat ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA7F3D0)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Pilih Paket Langganan",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SubscriptionPlanCard(
                        plan = SubscriptionPlan.MONTHLY,
                        offer = billingState.monthlyOffer,
                        selected = selectedPlan == SubscriptionPlan.MONTHLY,
                        onClick = { selectedPlan = SubscriptionPlan.MONTHLY },
                        modifier = Modifier.weight(1f)
                    )
                    SubscriptionPlanCard(
                        plan = SubscriptionPlan.YEARLY,
                        offer = billingState.yearlyOffer,
                        selected = selectedPlan == SubscriptionPlan.YEARLY,
                        onClick = { selectedPlan = SubscriptionPlan.YEARLY },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Subscribe CTA
                Button(
                    onClick = { onSelectSubscribe(selectedPlan) },
                    enabled = !billingState.isLoading,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF22D3EE),
                        contentColor = Color(0xFF07101F)
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selectedPlan == SubscriptionPlan.YEARLY) {
                            "Berlangganan PRO Tahunan"
                        } else {
                            "Berlangganan PRO Bulanan"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Status / Error feedback from Play Billing
            billingState.statusMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Restore & Manage row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRestorePurchases) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pulihkan Pembelian", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onManageSubscriptions) {
                    Text("Kelola di Google Play", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Google Play mandatory subscription renewal notice
            Text(
                text = "Pembayaran diproses aman melalui Google Play. Langganan diperpanjang otomatis kecuali dibatalkan minimal 24 jam sebelum akhir periode penagihan melalui pengaturan Google Play Store.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // DEBUG-ONLY preview toggle (compiled out / inert in Play Store Release APK/AAB)
            if (BuildConfig.DEBUG && onDebugTogglePremium != null) {
                HorizontalDivider(color = Color(0xFF233052))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1B4B).copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Mode Preview Developer (Debug Only)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA5B4FC)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onDebugTogglePremium(false) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Text("Simulasi Gratis", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onDebugTogglePremium(true) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Text("Simulasi PRO", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionPlanCard(
    plan: SubscriptionPlan,
    offer: SubscriptionOfferUi?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        selected -> Color(0xFF22D3EE)
        plan == SubscriptionPlan.YEARLY -> ProGoldColor.copy(alpha = 0.5f)
        else -> Color(0xFF2B3A5E)
    }
    val containerColor = if (selected) Color(0xFF132A45) else Color(0xFF141C33)

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (plan.badge != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ProGoldColor.copy(alpha = 0.22f)
                ) {
                    Text(
                        text = plan.badge,
                        color = ProGoldLightColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Spacer(Modifier.height(18.dp))
            }

            Text(
                text = plan.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Text(
                text = offer?.formattedPrice ?: "Google Play",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color(0xFF22D3EE) else Color(0xFFE2E8F0)
            )

            Text(
                text = offer?.introOfferLabel ?: plan.periodLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

/**
 * Mandatory Google Play Prominent Disclosure Dialog shown before requesting AccessibilityService.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = Color(0xFF11192E),
        icon = {
            Icon(
                imageVector = Icons.Filled.AccessibilityNew,
                contentDescription = null,
                tint = Color(0xFF22D3EE),
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Penggunaan Layanan Aksesibilitas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Wjust macro memerlukan izin AccessibilityService API untuk menjalankan fungsi inti aplikasi:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE2E8F0)
                )
                Text(
                    text = "• Melakukan ketukan (Klik) dan geseran (Swipe) otomatis di layar sesuai macro yang Anda buat sendiri.\n" +
                        "• Menjalankan aksi navigasi yang Anda pilih saat macro aktif.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B253A),
                    border = BorderStroke(1.dp, Color(0xFF22D3EE).copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = Color(0xFF22D3EE),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Privasi Anda terlindungi: Wjust macro TIDAK mengumpulkan, membaca sandi, menyimpan, atau mengirim data pribadi/layar Anda ke server mana pun. Semua proses berjalan 100% lokal di perangkat Anda.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBAE6FD)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF22D3EE),
                    contentColor = Color(0xFF07101F)
                )
            ) {
                Text("Setuju & Lanjutkan", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Tolak", color = Color(0xFF94A3B8))
            }
        }
    )
}
