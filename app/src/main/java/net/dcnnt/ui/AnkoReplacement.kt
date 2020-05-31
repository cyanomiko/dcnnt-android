package net.dcnnt.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat

class LParam {
    companion object {
        val W = ViewGroup.LayoutParams.WRAP_CONTENT
        val M = ViewGroup.LayoutParams.MATCH_PARENT

        fun lp(width: Int, height: Int) = ViewGroup.LayoutParams(width, height)
        fun ww() = ViewGroup.LayoutParams(W, W)
        fun wm() = ViewGroup.LayoutParams(W, M)
        fun mw() = ViewGroup.LayoutParams(M, W)
        fun mm() = ViewGroup.LayoutParams(M, M)
        fun set(v: View, width: Int, height: Int) { v.layoutParams = lp(width, height) }
        fun set(v: View, layoutParams: ViewGroup.LayoutParams) { v.layoutParams = layoutParams }
    }
}

var TextView.textAppearance: Int
    get() = 0
    set(value) = TextViewCompat.setTextAppearance(this, value)

var View.padding: Int
    get() = 0
    inline set(value) = setPadding(value, value, value, value)

fun Context.dip(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.dip(value: Float): Int = (value * resources.displayMetrics.density).toInt()
fun Context.sp(value: Int): Int = (value * resources.displayMetrics.scaledDensity).toInt()
fun Context.sp(value: Float): Int = (value * resources.displayMetrics.scaledDensity).toInt()
fun Context.px2dip(px: Int): Float = px.toFloat() / resources.displayMetrics.density
fun Context.px2sp(px: Int): Float = px.toFloat() / resources.displayMetrics.scaledDensity
