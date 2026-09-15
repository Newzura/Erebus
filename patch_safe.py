import os
filepath = 'app/src/main/java/fr/thomas/erebus/MainActivity.kt'
with open(filepath, 'r') as f:
    content = f.read()

# Replace the CALL to showFreeDroidWarnOnUpgradeMaterial, but keep the method definition
content = content.replace('showFreeDroidWarnOnUpgradeMaterial()', '')
# Ensure the method definition still exists, but without the call, it won't be used!
content = content.replace('private fun showFreeDroidWarnOnUpgradeMaterial() { }', 'private fun showFreeDroidWarnOnUpgradeMaterial()')

with open(filepath, 'w') as f:
    f.write(content)
