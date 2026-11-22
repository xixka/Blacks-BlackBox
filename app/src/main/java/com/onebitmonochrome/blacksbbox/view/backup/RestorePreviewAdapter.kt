/*
 * Copyright 2026 Black's BlackBox contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.onebitmonochrome.blacksbbox.view.backup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.onebitmonochrome.blacksbbox.R

/**
 * Simple list adapter for the restore preview: shows each app recorded in the backup
 * manifest with a placeholder icon, its version / space / size and an APK badge when the
 * archive contains the APK. Icon pixels are not stored in the backup, so a launcher icon
 * placeholder is used.
 */
class RestorePreviewAdapter : RecyclerView.Adapter<RestorePreviewAdapter.RowViewHolder>() {

    private val rows = mutableListOf<RestorePreviewRow>()

    fun setRows(rows: List<RestorePreviewRow>) {
        this.rows.clear()
        this.rows.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_restore_app, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.app_title)
        private val meta: TextView = itemView.findViewById(R.id.app_meta)
        private val apkBadge: TextView = itemView.findViewById(R.id.apk_badge)

        fun bind(row: RestorePreviewRow) {
            val context = itemView.context
            title.text = row.packageName
            val spacePart = context.getString(R.string.restore_space_label, row.userId)
            val versionPart = row.versionName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { context.getString(R.string.restore_version_label, it) }
            val sizePart = BackupUiLogic.formatSize(row.sizeBytes)
            meta.text = listOfNotNull(versionPart, spacePart, sizePart).joinToString(" · ")
            apkBadge.visibility = if (row.includeApk) View.VISIBLE else View.GONE
        }
    }
}
