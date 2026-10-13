package app.mg4dash.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Cadre carré dont le côté suit la hauteur disponible (pochette média). */
class SquareFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = View.MeasureSpec.getSize(heightMeasureSpec)
        val spec = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}
