package com.hesier.lumy.ui.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hesier.lumy.databinding.ViewActionButtonBinding
import com.hesier.lumy.databinding.ViewBadgeBinding

fun ViewGroup.addActionButton(icon: Int, label: CharSequence, onClick: () -> Unit): View {
    val b = ViewActionButtonBinding.inflate(LayoutInflater.from(context), this, true)
    b.icon.setImageResource(icon)
    b.label.text = label
    b.root.setOnClickListener { onClick() }
    return b.root
}

fun ViewGroup.addBadge(text: String): TextView {
    val b = ViewBadgeBinding.inflate(LayoutInflater.from(context), this, true)
    b.root.text = text
    return b.root
}
