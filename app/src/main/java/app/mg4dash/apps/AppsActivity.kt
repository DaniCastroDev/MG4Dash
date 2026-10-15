package app.mg4dash.apps

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.mg4dash.R

/** Liste des applis installées (appui long sur « Accueil » dans le dock). */
class AppsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.mg4dash.ui.Themes.apply(this)
        val pm = packageManager
        val apps: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val grid = GridLayout(this).apply {
            columnCount = 8
            setPadding(dp(24), dp(84), dp(24), dp(24))   // haut : zone antibrouillards
        }
        apps.forEach { ri ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_dock_item)
                setPadding(dp(8), dp(14), dp(8), dp(14))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(i)
                    finish()
                }
            }
            cell.addView(ImageView(this).apply { setImageDrawable(ri.loadIcon(pm)) }, LinearLayout.LayoutParams(dp(72), dp(72)))
            cell.addView(TextView(this).apply {
                text = ri.loadLabel(pm); maxLines = 2; gravity = Gravity.CENTER
                setTextColor(app.mg4dash.ui.Themes.color(this@AppsActivity, R.color.text_sub))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = dp(196); height = dp(150)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
        }
        setContentView(ScrollView(this).apply { addView(grid) })
    }

    override fun onResume() {
        super.onResume()
        app.mg4dash.ui.Fullscreen.apply(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
