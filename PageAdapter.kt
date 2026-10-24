package com.bookscan.ai

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PageAdapter(
    private val pages: List<Page>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val no: TextView = v.findViewById(R.id.txtNo)
        val done: TextView = v.findViewById(R.id.txtDone)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    override fun getItemCount() = pages.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = pages[position]
        h.img.setImageBitmap(BitmapFactory.decodeFile(p.thumb))
        h.no.text = "${position + 1}"
        h.done.visibility = if (p.proc != null) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
    }
}
