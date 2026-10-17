package app.mg4dash.ui

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.media.MediaHub

/** Menu des sources audio sous le nom de la source (« Radio ▾ ») : la source active est en couleur. */
object SourceMenu {
    fun show(activity: Activity, anchor: View) {
        val d = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (16 * d).toInt())   // marge basse : le contour de la dernière ligne surlignée n'est plus coupé
            elevation = 12 * d
        }
        // Fenêtre non focalisable : sinon elle prend le focus et la voiture réaffiche sa barre latérale et
        // son bandeau (plein écran perdu). Un toucher à côté la ferme quand même.
        val popup = android.widget.PopupWindow(list, (300 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        val cur = MediaHub.currentSource()
        MediaHub.availableSources().forEach { pkg ->
            list.addView(TextView(activity).apply {
                text = MediaHub.sourceLabel(pkg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Themes.color(activity, if (pkg == cur) R.color.accent_action else R.color.text_value))
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = pkg == cur
                setOnClickListener { popup.dismiss(); MediaHub.switchTo(pkg) }
            }, LinearLayout.LayoutParams(-1, (60 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.showAsDropDown(anchor, 0, (4 * d).toInt())
    }

    /** Menu de choix générique sous [anchor] ; [current] est en couleur. */
    fun choose(activity: Activity, anchor: View, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (16 * d).toInt())   // marge basse : le contour de la dernière ligne surlignée n'est plus coupé
            elevation = 12 * d
        }
        val popup = android.widget.PopupWindow(list, (340 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        labels.forEachIndexed { i, label ->
            list.addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Themes.color(activity, if (i == current) R.color.accent_action else R.color.text_value))
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = i == current
                setOnClickListener { popup.dismiss(); onPick(i) }
            }, LinearLayout.LayoutParams(-1, (60 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.showAsDropDown(anchor, 0, (4 * d).toInt())
    }

    /** Prépare un titre de carte comme sélecteur de source. */
    fun attach(activity: Activity, title: TextView) {
        val d = activity.resources.displayMetrics.density
        title.isClickable = true
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setTextColor(Themes.color(activity, R.color.text_value))
        title.setPadding(0, (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        title.setOnClickListener { show(activity, it) }
    }
}
