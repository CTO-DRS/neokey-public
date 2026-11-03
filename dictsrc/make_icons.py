# Fetch Material Symbols Rounded (Apache-2.0) + Simple Icons (CC0) → VectorDrawables in res/drawable
import re, urllib.request, os
MS = {"close":"close","check":"check","gesture":"gesture","book":"menu_book","mic":"mic","search":"search",
 "tools":"handyman","palette":"palette","shield":"shield_lock","bolt":"bolt","open":"open_in_new",
 "scan":"qr_code_scanner","copy":"content_copy","share":"share","globe":"language","arrow":"arrow_back",
 "pin":"keep","incognito":"visibility_off","sparkle":"auto_awesome","edit":"edit_note","chat":"chat",
 "hand":"front_hand","height":"height","paste":"content_paste","hide":"keyboard_hide","spell":"spellcheck",
 "calc":"calculate","calendar":"calendar_month","translate":"translate","volume":"volume_up","numbers":"123",
 "case":"match_case","undo":"undo","schedule":"schedule","mood":"mood","favorite":"favorite","pets":"pets",
 "food":"lunch_dining","sports":"sports_soccer","mosque":"mosque","rocket":"rocket_launch","verified":"verified",
 "keyboard":"keyboard","star":"star","download":"download","upload":"upload","tune":"tune","add":"add","celebration":"celebration","touch":"touch_app","lock":"lock"}
SI = {"whatsapp":"whatsapp","telegram":"telegram","tiktok":"tiktok","instagram":"instagram","x":"x",
 "youtube":"youtube","github":"github"}
os.makedirs("res/drawable", exist_ok=True)
def get(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"x"}), timeout=30).read().decode()
for k, n in MS.items():
    s = get(f"https://cdn.jsdelivr.net/npm/@material-symbols/svg-400@latest/rounded/{n}.svg")
    d = re.findall(r' d="([^"]+)"', s)
    paths = "".join(f'<path android:fillColor="#FFFFFFFF" android:pathData="{x}"/>' for x in d)
    open(f"res/drawable/ic_{k}.xml","w").write(
      '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
      'android:viewportWidth="960" android:viewportHeight="960" android:autoMirrored="false">'
      f'<group android:translateY="960">{paths}</group></vector>')
for k, n in SI.items():
    s = get(f"https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/{n}.svg")
    d = re.findall(r' d="([^"]+)"', s)
    paths = "".join(f'<path android:fillColor="#FFFFFFFF" android:pathData="{x}"/>' for x in d)
    open(f"res/drawable/ic_b_{k}.xml","w").write(
      '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
      f'android:viewportWidth="24" android:viewportHeight="24">{paths}</vector>')
print("done", len(MS)+len(SI))
