import re

def replace_function_body(filepath, func_signature, new_body=" { }"):
    with open(filepath, 'r') as f:
        content = f.read()
    
    # We find the signature, then count brackets to replace the whole body
    idx = content.find(func_signature)
    if idx == -1: return
    
    start_brace = content.find('{', idx)
    if start_brace == -1: return
    
    brace_count = 1
    end_brace = start_brace + 1
    while brace_count > 0 and end_brace < len(content):
        if content[end_brace] == '{':
            brace_count += 1
        elif content[end_brace] == '}':
            brace_count -= 1
        end_brace += 1
        
    new_content = content[:start_brace] + new_body + content[end_brace:]
    
    with open(filepath, 'w') as f:
        f.write(new_content)

replace_function_body('app/src/main/java/fr/thomas/erebus/MainActivity.kt', 'private fun showFreeDroidWarnOnUpgradeMaterial()')
replace_function_body('app/src/main/java/fr/thomas/erebus/ui/OverlayManager.kt', 'fun showQrCodeView(url: String)')
replace_function_body('app/src/main/java/fr/thomas/erebus/ui/OverlayManager.kt', 'fun hideQrCodeView()')
replace_function_body('app/src/main/java/fr/thomas/erebus/startpage/SponsorsManager.kt', 'fun setupSponsorsSection()')
replace_function_body('app/src/main/java/fr/thomas/erebus/startpage/SponsorsManager.kt', 'suspend fun refreshSponsors()')
replace_function_body('app/src/main/java/fr/thomas/erebus/settings/SettingsViews.kt', 'private fun setupSponsorsList()')

