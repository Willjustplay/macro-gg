package com.wjust.exportbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Handles `ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT`, and `ACTION_GET_CONTENT` on CloudPhone
 * ROMs (like Redfinger) where Android's built-in `DocumentsUI` app was removed.
 */
class BridgeDocumentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_CREATE_DOCUMENT -> handleCreateDocument()
            Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT -> showOpenDocumentPicker()
            else -> {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun handleCreateDocument() {
        val desiredTitle = intent?.getStringExtra(Intent.EXTRA_TITLE)
        val (_, writeUri) = BridgeExportProvider.buildWriteUri(desiredTitle)
        val result = Intent().apply {
            data = writeUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun showOpenDocumentPicker() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#22D3EE"))
            }
        }

        val title = TextView(this).apply {
            text = "Wjust Export Bridge — Pilih File"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "File ditemukan di Download/WjustMacro/ dan Download/"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(subtitle)

        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val files = BridgeExportProvider.listExportedAndImportableFiles(this)
        if (files.isEmpty()) {
            listCol.addView(TextView(this).apply {
                text = "Belum ada file .zip atau .json di folder Download/WjustMacro/ atau Download/."
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12.5f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        } else {
            for (item in files) {
                val kb = ((item.sizeBytes + 1023L) / 1024L).coerceAtLeast(1L)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    setOnClickListener {
                        val res = Intent().apply {
                            data = item.readUri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        setResult(RESULT_OK, res)
                        finish()
                    }
                }
                row.addView(TextView(this).apply {
                    text = item.name
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                })
                row.addView(TextView(this).apply {
                    text = "${item.displayPath} • $kb KB"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11f
                })
                listCol.addView(row)
            }
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
            addView(listCol)
        }
        root.addView(scroll)

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(cancelBtn)

        setContentView(root)
    }
}
