package fr.thomas.erebus.startpage

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import fr.thomas.erebus.R
import fr.thomas.erebus.data.BrowserPreferences
import fr.thomas.erebus.databinding.ActivityMainBinding
import fr.thomas.erebus.ui.QRUtils
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class SponsorsManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val callbacks: SponsorsCallbacks
) {

    interface SponsorsCallbacks {
        fun loadUrlFromIntent(url: String)
        fun resolveThemeColor(attrRes: Int): Int
    }

    private val client = OkHttpClient()
    private val avatarCache = LruCache<String, Bitmap>(30)

    data class SponsorInfo(
        val id: Int,
        val name: String,
        val github: String,
        val amount: Double,
        val message: String,
        val date: String
    )

    fun setupSponsorsSection() {
        setupGithubSponsorship()
        loadSponsorsList()
    }

    private fun setupGithubSponsorship() {
        val sponsorUrl = "https://github.com/sponsors/kododake"
        binding.startPageSponsorsAddress.text = sponsorUrl
        
        binding.startPageSponsorsQrImage.setImageBitmap(null)
        activity.lifecycleScope.launch {
            val qrBitmap = QRUtils.generateQrCodeAsync(sponsorUrl)
            if (qrBitmap != null) {
                binding.startPageSponsorsQrImage.setImageBitmap(qrBitmap)
            } else {
                binding.startPageSponsorsQrImage.setImageResource(R.drawable.ic_github)
            }
        }
        
        binding.startPageSponsorsActionButton.text = activity.getString(R.string.settings_sponsors_open_github_sponsors)
        binding.startPageSponsorsActionButton.setIconResource(R.drawable.favorite_24px)
        binding.startPageSponsorsActionButton.iconTint = ColorStateList.valueOf(Color.parseColor("#EC407A"))
        
        binding.startPageSponsorsActionButton.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sponsorUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.error_generic_message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSponsorsList() {
        if (BrowserPreferences.shouldHideSponsors(activity)) {
            binding.startPageSponsorsListCard.visibility = View.GONE
            binding.startPageSponsorsHiddenPienCard.visibility = View.VISIBLE
            binding.startPageSponsorsHiddenPienText.rotation = java.util.Random().nextFloat() * 360f
            return
        }

        binding.startPageSponsorsHiddenPienCard.visibility = View.GONE
        activity.lifecycleScope.launch {
            val jsonStr = fetchSponsorsJson()
            if (jsonStr != null) {
                val sponsors = parseAndSanitizeSponsors(jsonStr)
                updateSponsorsUi(sponsors)
            } else {
                binding.startPageSponsorsListCard.visibility = View.GONE
            }
        }
    }

    private suspend fun fetchSponsorsJson(): String? = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/kododake/AABrowser/refs/heads/main/docs/sponsors.json"
        val request = Request.Builder().url(url).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }
        }.getOrNull()
    }

    private fun parseAndSanitizeSponsors(jsonStr: String): List<SponsorInfo> {
        val resultList = mutableListOf<SponsorInfo>()
        try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            for ((key, element) in root) {
                val id = key.toIntOrNull() ?: continue
                if (id < 0) continue

                val obj = element.jsonObject
                
                val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = rawName.replace(Regex("<[^>]*>"), "").trim().take(50)
                if (name.isEmpty()) continue

                val rawGithub = obj["github"]?.jsonPrimitive?.contentOrNull ?: ""
                val github = if (rawGithub.matches(Regex("^[a-zA-Z0-9-]{1,39}$"))) rawGithub else ""

                val amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                if (amount <= 0.0 || amount.isNaN() || amount.isInfinite()) continue

                val rawMessage = obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = rawMessage.replace(Regex("<[^>]*>"), "")
                    .replace(Regex("\n{2,}"), "\n")
                    .trim()
                    .take(100)

                val rawDate = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val date = if (rawDate.matches(Regex("^\\d{4}[-/]\\d{2}[-/]\\d{2}$"))) rawDate else ""

                resultList.add(SponsorInfo(id, name, github, amount, message, date))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return resultList.sortedByDescending { it.id }
    }

    private fun updateSponsorsUi(sponsors: List<SponsorInfo>) {
        binding.startPageSponsorsListContainer.removeAllViews()
        if (sponsors.isEmpty()) {
            binding.startPageSponsorsListDescription.visibility = View.VISIBLE
            binding.startPageSponsorsListDescription.text = activity.getString(R.string.settings_sponsors_list_description)
            binding.startPageSponsorsListCard.visibility = View.VISIBLE
            return
        }

        binding.startPageSponsorsListDescription.visibility = View.GONE
        binding.startPageSponsorsListCard.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(activity)
        for (sponsor in sponsors) {
            val itemView = inflater.inflate(R.layout.item_sponsor, binding.startPageSponsorsListContainer, false)
            
            val nameView = itemView.findViewById<TextView>(R.id.sponsorName)
            val amountView = itemView.findViewById<TextView>(R.id.sponsorAmount)
            val dateView = itemView.findViewById<TextView>(R.id.sponsorDate)
            val messageView = itemView.findViewById<TextView>(R.id.sponsorMessage)
            val avatarView = itemView.findViewById<ImageView>(R.id.sponsorAvatar)
            val rootLayout = itemView.findViewById<View>(R.id.sponsorItemRoot)

            nameView.text = sponsor.name
            amountView.text = String.format("$%.2f", sponsor.amount)
            dateView.text = sponsor.date

            if (sponsor.message.isNotEmpty()) {
                messageView.text = activity.getString(R.string.sponsor_message_from_developer, sponsor.message)
                messageView.visibility = View.VISIBLE
            } else {
                messageView.visibility = View.GONE
            }

            val tierColor = getTierColor(sponsor.amount)
            amountView.setTextColor(tierColor)
            rootLayout.background = createSponsorBackgroundDrawable(tierColor)

            loadAvatarAsync(sponsor.github, avatarView)

            binding.startPageSponsorsListContainer.addView(itemView)
        }
    }

    private fun getTierColor(amount: Double): Int {
        return when {
            amount < 2.0 -> Color.parseColor("#1E88E5")
            amount < 5.0 -> Color.parseColor("#00B0FF")
            amount < 10.0 -> Color.parseColor("#00BFA5")
            amount < 20.0 -> Color.parseColor("#FFB300")
            amount < 50.0 -> Color.parseColor("#FF9100")
            amount < 100.0 -> Color.parseColor("#E91E63")
            else -> Color.parseColor("#D50000")
        }
    }

    private fun createSponsorBackgroundDrawable(tierColor: Int): GradientDrawable {
        val density = activity.resources.displayMetrics.density
        val glassBgColor = ColorUtils.setAlphaComponent(tierColor, 40)
        val strokeColor = ColorUtils.setAlphaComponent(tierColor, 100)
        
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(glassBgColor)
            setStroke((1.5f * density).toInt(), strokeColor)
        }
    }

    private fun loadAvatarAsync(githubUsername: String, imageView: ImageView) {
        if (githubUsername.isEmpty()) {
            imageView.setImageResource(R.drawable.ic_github)
            return
        }
        
        val cached = avatarCache.get(githubUsername)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        
        imageView.setImageResource(R.drawable.ic_github)
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val url = "https://github.com/$githubUsername.png"
            val request = Request.Builder().url(url).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            avatarCache.put(githubUsername, bitmap)
                            withContext(Dispatchers.Main) {
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }
    }
}
