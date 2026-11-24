package com.wjust.exportbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal launcher UI for `Wjust Export Bridge`.
 * Guides users migrating from Wjust macro v2.6.0 on CloudPhones (Redfinger) without DocumentsUI
 * and lists all saved backup/macro `.zip` files with a 1-tap Share option.
 */
class BridgeMainActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var countLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080E1A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val ins = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = ins.left; top = ins.top; right = ins.right; bottom = ins.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(
                dp(16) + left,
                dp(16) + top,
                dp(16) + right,
                dp(16) + bottom
            )
            insets
        }

        root.addView(TextView(this).apply {
            text = "Wjust Export Bridge"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "Penyelamat Ekspor CloudPhone / Redfinger (Tanpa DocumentsUI & Tanpa Root)"
            setTextColor(Color.parseColor("#2DD4BF"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(12))
        })

        // Step-by-step migration guide card
        val guideCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111C33"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#1E3A5F"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        guideCard.addView(TextView(this).apply {
            text = "Cara Backup Data Wjust macro v2.6.0:"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        guideCard.addView(TextView(this).apply {
            text = "1. JANGAN uninstall Wjust macro v2.6.0 dulu.\n" +
                "2. Buka Wjust macro v2.6.0 → Alat → Pengaturan → Backup (.zip) (atau Ekspor Macro).\n" +
                "3. File .zip otomatis ditangkap aplikasi ini & disimpan ke Download/WjustMacro/.\n" +
                "4. Cek file backup Anda muncul di daftar bawah, lalu uninstall v2.6.0 & pasang Wjust macro v2.9.2.\n" +
                "5. Di v2.9.2 → Alat → Pengaturan → Impor dr Download."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        root.addView(guideCard)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        countLabel = TextView(this).apply {
            text = "File Tersimpan"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(countLabel)
        headerRow.addView(TextView(this).apply {
            text = "Segarkan"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D9488"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { refreshList() }
        })
        root.addView(headerRow)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listContainer)
        }
        root.addView(scroll)

        setContentView(root)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

        listContainer.removeAllViews()
        val items = BridgeExportProvider.listExportedAndImportableFiles(this)
        countLabel.text = "File Backup / Macro (${items.size})"

        if (items.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Belum ada file ekspor.\nSilakan buka Wjust macro v2.6.0 lalu klik Backup (.zip) atau Ekspor Macro."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12.5f
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }

        for (item in items) {
            val kb = ((item.sizeBytes + 1023L) / 1024L).coerceAtLeast(1L)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#131E34"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = item.name
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            infoCol.addView(TextView(this).apply {
                text = "${item.displayPath}\n$kb KB • ${fmt.format(Date(item.modifiedMs))}"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
            })
            card.addView(infoCol)

            card.addView(TextView(this).apply {
                text = "Bagikan"
                setTextColor(Color.WHITE)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0284C7"))
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
                setOnClickListener { shareExportedItem(item) }
            })
            listContainer.addView(card)
        }
    }

    private fun shareExportedItem(item: BridgeExportProvider.ExportedItem) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, item.readUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(sendIntent, "Bagikan ${item.name}"))
        }.onFailure {
            Toast.makeText(this, "File tersimpan di ${item.displayPath}", Toast.LENGTH_LONG).show()
        }
    }
}
