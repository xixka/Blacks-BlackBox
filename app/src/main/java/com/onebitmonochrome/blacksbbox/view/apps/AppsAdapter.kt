package com.onebitmonochrome.blacksbbox.view.apps

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.App
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.bean.AppInfo
import com.onebitmonochrome.blacksbbox.databinding.ItemAppBinding
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView



class AppsAdapter : RVHolderFactory() {

    companion object {
        private const val TAG = "AppsAdapter"
        private const val MAX_ICON_SIZE = 96
        private val DEFAULT_ICON_COLOR = Color.parseColor("#CCCCCC")

        /**
         * Resolves the small space label shown under the app name:
         * the space remark ("Remark$userId") or a "User N" fallback.
         */
        fun resolveUserTag(userId: Int): String {
            return try {
                val remark = AppManager.mRemarkSharedPreferences
                        .getString("Remark$userId", null)
                        ?.takeIf { it.isNotBlank() }
                remark ?: App.getContext().getString(R.string.default_user_name, userId)
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving user tag for userId=$userId: ${e.message}")
                App.getContext().getString(R.string.default_user_name, userId)
            }
        }
    }

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return try {
            AppsVH(inflate(R.layout.item_app, parent))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating ViewHolder: ${e.message}")
            FallbackAppsVH(inflate(R.layout.item_app, parent))
        }
    }

    class AppsVH(itemView: View) : RVHolder<AppInfo>(itemView) {
        val binding = ItemAppBinding.bind(itemView)
        private var currentIcon: Drawable? = null
        private var isAttached = false

        init {
            try {
                binding.icon.scaleType = ImageView.ScaleType.CENTER_CROP

                itemView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (isAttached) {
                            itemView.viewTreeObserver.removeOnPreDrawListener(this)
                        }
                        return true
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing ViewHolder: ${e.message}")
            }
        }

        override fun setContent(item: AppInfo, isSelected: Boolean, payload: Any?) {
            try {
                setIconSafely(item.icon, item.packageName)

                binding.name.text = item.name ?: App.getContext().getString(R.string.unknown_app)

                // Small tag under the app name identifying the space this app lives in.
                binding.userTag.text = resolveUserTag(item.userId)

                if (item.isXpModule) {
                    binding.cornerLabel.visibility = View.VISIBLE
                } else {
                    binding.cornerLabel.visibility = View.INVISIBLE
                }

                isAttached = true

            } catch (e: Exception) {
                Log.e(TAG, "Error setting content for ${item.packageName}: ${e.message}")
                setSafeDefaults()
            }
        }

        private fun setIconSafely(icon: Drawable?, packageName: String) {
            try {
                if (icon != null) {
                    val optimizedIcon = optimizeIcon(icon)
                    binding.icon.setImageDrawable(optimizedIcon)
                    currentIcon = optimizedIcon
                } else {
                    binding.icon.setImageDrawable(createDefaultIcon())
                    currentIcon = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set icon for $packageName: ${e.message}")
                binding.icon.setImageDrawable(createDefaultIcon())
                currentIcon = null
            }
        }

        private fun optimizeIcon(icon: Drawable): Drawable {
            return try {
                if (icon is BitmapDrawable) {
                    val bitmap = icon.bitmap
                    if (bitmap.width > MAX_ICON_SIZE || bitmap.height > MAX_ICON_SIZE) {
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap, MAX_ICON_SIZE, MAX_ICON_SIZE, true
                        )
                        BitmapDrawable(itemView.resources, scaledBitmap)
                    } else {
                        icon
                    }
                } else {
                    icon
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error optimizing icon: ${e.message}")
                icon
            }
        }

        private fun createDefaultIcon(): Drawable {
            return try {
                ColorDrawable(DEFAULT_ICON_COLOR)
            } catch (e: Exception) {
                Log.w(TAG, "Error creating default icon: ${e.message}")
                ColorDrawable(Color.GRAY)
            }
        }

        private fun setSafeDefaults() {
            try {
                binding.icon.setImageDrawable(createDefaultIcon())
                binding.name.text = App.getContext().getString(R.string.unknown_app)
                binding.userTag.text = resolveUserTag(0)
                binding.cornerLabel.visibility = View.INVISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Error setting safe defaults: ${e.message}")
            }
        }
    }

    class FallbackAppsVH(itemView: View) : RVHolder<AppInfo>(itemView) {
        val binding = ItemAppBinding.bind(itemView)

        override fun setContent(item: AppInfo, isSelected: Boolean, payload: Any?) {
            try {
                binding.icon.setImageDrawable(ColorDrawable(DEFAULT_ICON_COLOR))
                binding.name.text = item.name ?: App.getContext().getString(R.string.unknown_app)
                binding.userTag.text = resolveUserTag(item.userId)
                binding.cornerLabel.visibility = View.INVISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Error in fallback ViewHolder: ${e.message}")
            }
        }
    }
}
