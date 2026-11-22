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

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.bean.AppInfo
import com.onebitmonochrome.blacksbbox.view.apps.AppsAdapter

/**
 * Multi-select adapter of all installed (virtual) apps across spaces, used by BackupActivity
 * in multi-select mode. Selection is keyed by (packageName, userId) so the same package in
 * different spaces stays independently selectable.
 */
class BackupAppAdapter : RecyclerView.Adapter<BackupAppAdapter.AppViewHolder>() {

    companion object {
        private const val TAG = "BackupAppAdapter"
    }

    private val items = mutableListOf<AppInfo>()
    private val selectedKeys = LinkedHashSet<Pair<String, Int>>()

    /** Invoked (on the main thread) whenever the selection changed, with the new count. */
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun setItems(apps: List<AppInfo>) {
        items.clear()
        items.addAll(apps)
        // Drop selections for entries that disappeared between reloads.
        val validKeys = items.map { key(it) }.toSet()
        selectedKeys.retainAll(validKeys)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedKeys.size)
    }

    fun selectAll() {
        selectedKeys.clear()
        items.forEach { selectedKeys.add(key(it)) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedKeys.size)
    }

    fun clearSelection() {
        selectedKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun selectedCount(): Int = selectedKeys.size

    fun selectedApps(): List<AppInfo> = items.filter { key(it) in selectedKeys }

    private fun key(app: AppInfo): Pair<String, Int> = Pair(app.packageName, app.userId)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_backup_app, parent, false)
        return AppViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.app_icon)
        private val name: TextView = itemView.findViewById(R.id.app_name)
        private val pkg: TextView = itemView.findViewById(R.id.app_pkg)
        private val spaceTag: TextView = itemView.findViewById(R.id.space_tag)
        private val checkbox: CheckBox = itemView.findViewById(R.id.app_checkbox)
        private var current: AppInfo? = null

        init {
            itemView.setOnClickListener {
                try {
                    val app = current ?: return@setOnClickListener
                    val appKey = key(app)
                    if (!selectedKeys.add(appKey)) {
                        selectedKeys.remove(appKey)
                    }
                    checkbox.isChecked = appKey in selectedKeys
                    onSelectionChanged?.invoke(selectedKeys.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling selection: ${e.message}")
                }
            }
        }

        fun bind(app: AppInfo) {
            current = app
            try {
                icon.setImageDrawable(app.icon)
                name.text = app.name
                pkg.text = app.packageName
                spaceTag.text = AppsAdapter.resolveUserTag(app.userId)
                checkbox.isChecked = key(app) in selectedKeys
            } catch (e: Exception) {
                Log.e(TAG, "Error binding ${app.packageName}: ${e.message}")
            }
        }
    }
}
