package com.example.itemexporter

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(px(20), px(32), px(20), px(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun text(t: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(this).apply {
            this.text = t
            textSize = size
            setTextColor(color)
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(6), 0, px(6))
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        root.addView(text("استخراج اقلام به اکسل", 22f, true))
        root.addView(
            text(
                "۱) سرویس دسترسی‌پذیری این برنامه را فعال کنید.\n" +
                    "۲) برنامه فروشگاهی را باز کنید و به صفحه «حذف اقلام فروشگاهی» بروید.\n" +
                    "۳) دکمه شناور «استخراج اکسل» ظاهر می‌شود؛ آن را بزنید. برنامه خودش اسکرول می‌کند و همه اقلام را می‌خواند.\n" +
                    "۴) فایل در پوشه Download/ItemExporter ذخیره می‌شود.\n\n" +
                    "• برای توقف زودهنگام و ذخیره آنچه تا الان خوانده شده، دوباره روی دکمه بزنید.\n" +
                    "• دکمه را می‌توان با کشیدن جابه‌جا کرد.\n" +
                    "• لمس طولانی دکمه، ساختار صفحه را برای عیب‌یابی ذخیره می‌کند.",
                14f
            )
        )

        status = text("", 15f, true)
        root.addView(status)
        root.addView(button("باز کردن تنظیمات دسترسی‌پذیری") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(text("متن تشخیص صفحه (اگر عنوان صفحه فرق دارد):", 14f))
        val trigger = EditText(this).apply {
            setText(Prefs.trigger(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(trigger)
        root.addView(button("ذخیره متن تشخیص") {
            Prefs.setTrigger(this, trigger.text.toString())
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        })

        root.addView(text("آخرین فایل:", 14f))
        root.addView(button("باز کردن آخرین فایل اکسل") { openLast(false) })
        root.addView(button("اشتراک‌گذاری آخرین فایل") { openLast(true) })
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        status.text = if (enabled) "✔ سرویس فعال است" else "✘ سرویس هنوز فعال نیست"
        status.setTextColor(if (enabled) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
    }

    private fun isServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains(packageName) && s.contains("ExtractorService")
    }

    private fun openLast(share: Boolean) {
        val uri = Prefs.lastUri(this)
        if (uri == null) {
            Toast.makeText(this, "هنوز فایلی ساخته نشده", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (share) {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = Storage.XLSX_MIME
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(i, "اشتراک‌گذاری"))
            } else {
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, Storage.XLSX_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(i)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "برنامه‌ای برای باز کردن اکسل نصب نیست", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فایل در دسترس نیست", Toast.LENGTH_LONG).show()
        }
    }
}
