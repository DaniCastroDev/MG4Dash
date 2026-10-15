# Génère res/values/themes_colors.xml (fonds × couleurs de boutons) et branche les ressources sur les attributs de thème.
# À lancer depuis app/src/main/res. Idempotent.
import glob

NL = chr(10)

bgs = {
    # nom : haut, bas, fin du dégradé, panneau, bordure, bouton au repos, bouton appuyé, jauge éteinte
    'Night':    ('#FF0B0F17', '#FF141B29', '#FF0E1420', '#D912161F', '#FF2A3142', '#FF1C2231', '#FF283048', '#FF262D3D'),
    'Black':    ('#FF050506', '#FF0E0E10', '#FF08080A', '#D9121214', '#FF2A2A2F', '#FF1B1B1F', '#FF2A2A31', '#FF26262B'),
    'Charcoal': ('#FF15171A', '#FF22252A', '#FF191B1E', '#D921242A', '#FF3A3F46', '#FF2B2F35', '#FF3A3F47', '#FF33383F'),
    'Forest':   ('#FF07110E', '#FF0F2019', '#FF0A1712', '#D90F1B16', '#FF243A30', '#FF16271F', '#FF22392D', '#FF1F2E27'),
    'Burgundy': ('#FF13090C', '#FF231117', '#FF190C10', '#D91C1015', '#FF3B2229', '#FF29161C', '#FF3B2129', '#FF2E1C22'),
}
accents = {
    # nom : action (boutons, liens, onglet actif), sélection (puces et boutons sélectionnés)
    'Blue':   ('#FF2979FF', '#FF00D2FF'),
    'Grey':   ('#FF8E99A8', '#FFCFD6DF'),
    'Red':    ('#FFE53935', '#FFFF8A80'),
    'Green':  ('#FF00C853', '#FF69F0AE'),
    'Orange': ('#FFFF8F00', '#FFFFC46B'),
}
keys = ['mgBgTop', 'mgBgBottom', 'mgBgEnd', 'mgPanel', 'mgPanelBorder', 'mgButtonIdle', 'mgButtonPressed', 'mgGaugeOff']


def _rgb(h): return int(h[3:5], 16), int(h[5:7], 16), int(h[7:9], 16)
def _hex(r, g, b, a='FF'): return '#%s%02X%02X%02X' % (a, max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))))
def shade(h, f): r, g, b = _rgb(h); return _hex(r * f, g * f, b * f)
def light(h, f): r, g, b = _rgb(h); return _hex(r + (255 - r) * f, g + (255 - g) * f, b + (255 - b) * f)
def alpha(h, a): r, g, b = _rgb(h); return _hex(r, g, b, a)


def bg_items(n):
    v = bgs[n]
    return list(zip(keys, v)) + [('mgPill', alpha(v[3], 'CC')), ('mgButtonDisabled', alpha(v[5], '80'))]


def acc_items(n):
    a, sel = accents[n]
    return [('mgAction', a), ('mgSelect', sel), ('mgActionPressed', shade(a, 0.70)), ('mgActionDeep', shade(a, 0.45)),
            ('mgActionDeeper', shade(a, 0.32)), ('mgActionBorder', light(a, 0.18)), ('mgActionTint', alpha(a, '26')),
            ('mgSelectTint', alpha(sel, '26'))]


out = ['<?xml version="1.0" encoding="utf-8"?>',
       '<!-- Généré par i18n/theme_gen.py : fonds et couleurs des boutons, appliqués par-dessus Theme.MG4Launcher (ui/Themes.kt). -->',
       '<resources>']
for n in bgs:
    out.append(f'    <style name="MgBg.{n}" parent="">')
    out += [f'        <item name="{k}">{c}</item>' for k, c in bg_items(n)]
    out.append('    </style>')
for n in accents:
    out.append(f'    <style name="MgAccent.{n}" parent="">')
    out += [f'        <item name="{k}">{c}</item>' for k, c in acc_items(n)]
    out.append(f'        <item name="colorAccent">{accents[n][0]}</item>')
    out.append('    </style>')
out.append('</resources>' + NL)
open('values/themes_colors.xml', 'w', encoding='utf-8', newline=NL).write(NL.join(out))

# Thème de base : valeurs par défaut = bleu nuit + bleu (toutes les variables doivent y exister)
p = 'values/styles.xml'
s = open(p, encoding='utf-8').read()
s = NL.join(l for l in s.split(NL) if '<item name="mg' not in l)
a = '        <item name="android:textColor">@color/text_value</item>' + NL + '    </style>'
assert a in s
defaults = ''.join(f'        <item name="{k}">{c}</item>' + NL for k, c in bg_items('Night') + acc_items('Blue'))
s = s.replace(a, '        <item name="android:textColor">@color/text_value</item>' + NL + defaults + '    </style>', 1)
s = s.replace('<item name="android:statusBarColor">@color/bg_top</item>', '<item name="android:statusBarColor">?attr/mgBgTop</item>')
s = s.replace('<item name="android:navigationBarColor">@color/bg_top</item>', '<item name="android:navigationBarColor">?attr/mgBgTop</item>')
s = s.replace('<item name="colorAccent">@color/accent_action</item>', '<item name="colorAccent">?attr/mgAction</item>')
open(p, 'w', encoding='utf-8', newline=NL).write(s)

mp = {'"@color/bg_top"': '"?attr/mgBgTop"', '"@color/bg_bottom"': '"?attr/mgBgBottom"', '"@color/panel"': '"?attr/mgPanel"',
      '"@color/panel_raised"': '"?attr/mgPanel"', '"@color/panel_border"': '"?attr/mgPanelBorder"',
      '"@color/button_idle"': '"?attr/mgButtonIdle"', '"@color/button_pressed"': '"?attr/mgButtonPressed"',
      '"@color/gauge_off"': '"?attr/mgGaugeOff"', '"@color/accent_action"': '"?attr/mgAction"'}
hexmap = {'"#FF1A4FB0"': '"?attr/mgActionPressed"', '"#FF0D2A66"': '"?attr/mgActionDeeper"', '"#FF2979FF"': '"?attr/mgAction"',
          '"#FF123A8C"': '"?attr/mgActionDeep"', '"#FF4A8CFF"': '"?attr/mgActionBorder"', '"#FF1A5CCC"': '"?attr/mgActionPressed"',
          '"#262979FF"': '"?attr/mgActionTint"', '"#2600D2FF"': '"?attr/mgSelectTint"', '"#CC12161F"': '"?attr/mgPill"', '"#801C2231"': '"?attr/mgButtonDisabled"'}
selection = {'bg_button.xml', 'bg_chip.xml', 'chip_text.xml'}
for f in glob.glob('drawable/*.xml') + glob.glob('color/*.xml') + glob.glob('layout/*.xml'):
    t = open(f, encoding='utf-8').read()
    o = t
    for a, b in mp.items():
        t = t.replace(a, b)
    name = f.replace(chr(92), '/').split('/')[-1]
    if name in selection:
        t = t.replace('"@color/accent_cold"', '"?attr/mgSelect"')
    if name.startswith('bg_') and name != 'bg_call.xml':
        for a, b in hexmap.items():
            t = t.replace(a, b)
    if name == 'bg_root.xml':
        t = t.replace('android:endColor="#FF0E1420"', 'android:endColor="?attr/mgBgEnd"')
    if t != o:
        open(f, 'w', encoding='utf-8', newline='').write(t)
        print('updated', f)
