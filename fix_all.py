import re

def fix_main():
    path = 'app/src/main/java/fr/thomas/erebus/MainActivity.kt'
    with open(path, 'r') as f:
        content = f.read()
    
    # Remove umamiTracker
    content = re.sub(r'import fr\.thomas\.erebus\.analytics\.UmamiTracker\n', '', content)
    content = re.sub(r'private val umamiTracker: UmamiTracker by lazy \{.*?\}\n', '', content, flags=re.DOTALL)
    content = re.sub(r'umamiTracker\.trackEvent\("app_open"\)\n', '', content)
    
    # Remove FreeDroidWarn
    content = re.sub(r'import org\.woheller69\.freeDroidWarn\.R as FreeDroidWarnR\n', '', content)
    content = re.sub(r'showFreeDroidWarnOnUpgradeMaterial\(\)\n', '', content)
    content = re.sub(r'private fun showFreeDroidWarnOnUpgradeMaterial\(\) \{.*?\n    \}\n', '', content, flags=re.DOTALL)
    
    with open(path, 'w') as f:
        f.write(content)

def fix_qrutils():
    path = 'app/src/main/java/fr/thomas/erebus/ui/QRUtils.kt'
    # Just replace it entirely to remove zxing
    with open(path, 'w') as f:
        f.write("""package fr.thomas.erebus.ui
import android.graphics.Bitmap
object QRUtils {
    fun generateQrCode(content: String, size: Int = 512): Bitmap? { return null }
    suspend fun generateQrCodeAsync(content: String, size: Int = 512): Bitmap? { return null }
}
""")

def fix_overlay():
    path = 'app/src/main/java/fr/thomas/erebus/ui/OverlayManager.kt'
    with open(path, 'r') as f:
        content = f.read()
    content = re.sub(r'import com\.google\.zxing\.qrcode\.QRCodeWriter\n', '', content)
    content = re.sub(r'import com\.google\.zxing\.BarcodeFormat\n', '', content)
    with open(path, 'w') as f:
        f.write(content)
        
def fix_settings():
    path = 'app/src/main/java/fr/thomas/erebus/settings/SettingsViews.kt'
    with open(path, 'r') as f:
        content = f.read()
    content = re.sub(r'import com\.google\.zxing\.qrcode\.QRCodeWriter\n', '', content)
    content = re.sub(r'import com\.google\.zxing\.BarcodeFormat\n', '', content)
    content = re.sub(r'import com\.google\.zxing\.WriterException\n', '', content)
    with open(path, 'w') as f:
        f.write(content)

fix_main()
fix_qrutils()
fix_overlay()
fix_settings()
