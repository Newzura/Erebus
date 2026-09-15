import re

# Fix SettingsViews unresolved zxing (missed one import?)
path_settings = 'app/src/main/java/fr/thomas/erebus/settings/SettingsViews.kt'
with open(path_settings, 'r') as f:
    content = f.read()
content = re.sub(r'import com\.google\.zxing\..*\n', '', content)
with open(path_settings, 'w') as f:
    f.write(content)

# Fix StartPageManager calling SponsorsManager with wrong arguments
path_spm = 'app/src/main/java/fr/thomas/erebus/startpage/StartPageManager.kt'
with open(path_spm, 'r') as f:
    content = f.read()

# Just restore the original SponsorsManager! It's much safer than trying to mock it out incorrectly
