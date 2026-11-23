package com.example.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.data.Action
import com.example.data.ImageAction
import com.example.data.LocalStore
import com.example.data.Macro
import com.example.data.kind
import com.example.data.summary
import kotlin.math.roundToInt

/**
 * Floating Macro Settings & Action Inspector shown directly over other apps.
 * Allows inspecting & adjusting loop/delay/jitter, toggling marker visibility,
 * tuning Image Detection similarity %, and launching Crop Mode directly for any ImageAction.
 */
@SuppressLint("ViewConstructor")
class OverlayMacroSettingsView(
    context: Context,
    private val macro: Macro,
    private val store: LocalStore,
    private val markersHidden: Boolean,
    private val onToggleMarkersHidden: (Boolean) -> Unit,
    private val onSaveSettings: (loopCount: Int, loopDelayMs: Long, jitterPx: Int) -> Unit,
    private val onToggleActionEnabled: (actionId: String, enabled: Boolean) -> Unit,
    private val onAdjustImageThreshold: (actionId: String, newThreshold: Float) -> Unit,
    private val onCropForImageAction: (actionId: String) -> Unit,
    private val onAddNewCropImageAction: () -> Unit,
    private val onDeleteAction: (actionId: String) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F20F172A"))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#2DD4BF"))
        }
        elevation = dp(10).toFloat()

        // Header
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = macro.name.ifBlank { "Macro Aktif" }
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val loopLabel = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
        titleCol.addView(TextView(context).apply {
            text = "$loopLabel • ${macro.actions.size} aksi • Jeda ${macro.loopDelayMs}ms"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
        })
        header.addView(titleCol)
        header.addView(makeChipBtn("✕", "#334155") { onClose() })
        addView(header)

        // Scrollable content (settings + actions list)
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(320))
        }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        // ---- Section 1: Loop & Touch Settings ----
        val infiniteCheck = CheckBox(context).apply {
            text = "Ulangi tanpa batas (Loop ∞)"
            isChecked = macro.loopCount <= 0
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        content.addView(infiniteCheck)

        val loopCountField = addNumberRow(content, "Jumlah Loop", macro.loopCount.coerceAtLeast(1).toString())
        loopCountField.isEnabled = !infiniteCheck.isChecked
        infiniteCheck.setOnCheckedChangeListener { _, checked ->
            loopCountField.isEnabled = !checked
            loopCountField.alpha = if (checked) 0.45f else 1f
        }
        if (infiniteCheck.isChecked) loopCountField.alpha = 0.45f

        val loopDelayField = addNumberRow(content, "Jeda antar loop (ms)", macro.loopDelayMs.toString())
        val jitterField = addNumberRow(content, "Acak posisi (±px)", macro.jitterPx.toString())

        val hideMarkersCheck = CheckBox(context).apply {
            text = "Sembunyikan penanda titik di layar"
            isChecked = markersHidden
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            setOnCheckedChangeListener { _, checked -> onToggleMarkersHidden(checked) }
        }
        content.addView(hideMarkersCheck)

        // Divider + Actions section header
        val divider = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        }
        content.addView(divider)

        val actHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        actHeader.addView(TextView(context).apply {
            text = "Daftar Aksi (${macro.actions.size})"
            setTextColor(Color.parseColor("#2DD4BF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        actHeader.addView(makeChipBtn("+ Crop Img", "#0F766E") {
            onAddNewCropImageAction()
        })
        content.addView(actHeader)

        if (macro.actions.isEmpty()) {
            content.addView(TextView(context).apply {
                text = "Belum ada aksi. Gunakan tombol '+' atau '+ Crop Img' untuk menambah."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        } else {
            macro.actions.forEachIndexed { idx, action ->
                content.addView(buildActionRow(idx + 1, action))
            }
        }

        scroll.addView(content)
        addView(scroll)

        // Footer buttons
        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        footer.addView(makeChipBtn("Tutup", "#475569", marginEndDp = 6) { onClose() })
        footer.addView(makeChipBtn("Simpan Setelan", "#0D9488", marginEndDp = 0) {
            val newLoop = if (infiniteCheck.isChecked) 0 else (loopCountField.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1)
            val newDelay = (loopDelayField.text.toString().toLongOrNull() ?: macro.loopDelayMs).coerceAtLeast(0L)
            val newJitter = (jitterField.text.toString().toIntOrNull() ?: macro.jitterPx).coerceIn(0, 60)
            onSaveSettings(newLoop, newDelay, newJitter)
            onClose()
        })
        addView(footer)
    }

    private fun buildActionRow(number: Int, action: Action): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
            }
            alpha = if (action.enabled) 1f else 0.5f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Thumbnail for ImageAction
        if (action is ImageAction && action.imageName.isNotBlank()) {
            val file = store.imageFile(action.imageName)
            if (file.exists()) {
                val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (bmp != null) {
                    val iv = ImageView(context).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.parseColor("#0B1220"))
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                        layoutParams = LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
                    }
                    top.addView(iv)
                }
            }
        }

        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(context).apply {
            text = "$number. ${action.kind.label}"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(TextView(context).apply {
            text = action.summary()
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        top.addView(info)

        top.addView(makeChipBtn(if (action.enabled) "ON" else "OFF", if (action.enabled) "#059669" else "#475569", marginEndDp = 4) {
            onToggleActionEnabled(action.id, !action.enabled)
        })
        top.addView(makeChipBtn("✕", "#991B1B", marginEndDp = 0) {
            onDeleteAction(action.id)
        })
        card.addView(top)

        // Extra quick controls for ImageAction: Crop replacement + similarity +/-
        if (action is ImageAction) {
            val imgTools = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            }
            imgTools.addView(makeChipBtn("✂ Crop", "#0F766E", marginEndDp = 6) {
                onCropForImageAction(action.id)
            })
            val pct = (action.threshold * 100).roundToInt()
            imgTools.addView(TextView(context).apply {
                text = "Akurasi: $pct%"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10.5f
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            imgTools.addView(makeChipBtn("-5%", "#334155", marginEndDp = 4) {
                onAdjustImageThreshold(action.id, (action.threshold - 0.05f).coerceIn(0.50f, 0.99f))
            })
            imgTools.addView(makeChipBtn("+5%", "#334155", marginEndDp = 0) {
                onAdjustImageThreshold(action.id, (action.threshold + 0.05f).coerceIn(0.50f, 0.99f))
            })
            card.addView(imgTools)
        }

        return card
    }

    private fun addNumberRow(parent: LinearLayout, label: String, initial: String): EditText {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val et = EditText(context).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            textSize = 11.5f
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LayoutParams(dp(84), LayoutParams.WRAP_CONTENT)
        }
        row.addView(et)
        parent.addView(row)
        return et
    }

    private fun makeChipBtn(label: String, bgHex: String, marginEndDp: Int = 0, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }
    }
}
