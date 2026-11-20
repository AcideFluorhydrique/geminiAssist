#!/usr/bin/env python3
"""Install the debug APK on a running emulator and walk through what most users do.

Usage: emulator_test.py <apk> <out dir>

Checks: launch, the block button (tap, drag, long press), the mode, button position
and hidden state surviving a restart, the + menu in both modes, attaching a picture in
restricted mode, and no crash. Writes screenshots, logcat, the hosts the allowlist
blocked and summary.md to <out dir>. Gemini is used signed out: CI has no account.

Hard checks (the app's own behaviour) fail the run. Checks that depend on the live
Gemini site are only reported, since the site changes and may differ for CI's IPs.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
import struct

PKG = 'io.github.acidefluorhydrique.geminiassist.debug'
ACTIVITY = PKG + '/io.github.acidefluorhydrique.geminiassist.MainActivity'
PREFS = f'shared_prefs/{PKG}_preferences.xml'
PICTURE = '/sdcard/Download/geminiassist-test.png'

results = []  # (name, ok, hard, note)


def check(name, ok, hard=True, note=''):
    results.append((name, bool(ok), hard, note))
    print(f'{"PASS" if ok else ("FAIL" if hard else "WARN")} {name} {note}', flush=True)


def adb(*args, timeout=180):
    r = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def shot(out, name):
    path = os.path.join(out, f'{name}.png')
    with open(path, 'wb') as f:
        f.write(subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True).stdout)
    print(f'screenshot {path}', flush=True)


def nodes():
    """UI nodes as (text, desc, id, x1, y1, x2, y2). uiautomator needs an idle UI and
    Gemini animates, so retry a few times."""
    for _ in range(4):
        adb('shell', 'rm', '-f', '/sdcard/ui.xml')
        adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        xml = adb('shell', 'cat', '/sdcard/ui.xml')
        if '<?xml' in xml:
            try:
                root = ET.fromstring(xml[xml.index('<?xml'):])
            except ET.ParseError:
                root = None
            if root is not None:
                out = []
                for n in root.iter('node'):
                    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
                    if m:
                        out.append((n.get('text', ''), n.get('content-desc', ''), n.get('resource-id', ''),
                                    *map(int, m.groups())))
                return out
        time.sleep(2)
    print('uiautomator dump failed', flush=True)
    return []


def find(*patterns):
    for node in nodes():
        for p in patterns:
            if any(re.search(p, s, re.I) for s in node[:3] if s):
                return node
    return None


def center(node):
    return (node[3] + node[5]) // 2, (node[4] + node[6]) // 2


def tap(*patterns, wait=3, tries=3):
    for _ in range(tries):
        node = find(*patterns)
        if node:
            x, y = center(node)
            adb('shell', 'input', 'tap', str(x), str(y))
            print(f'tapped {node[0] or node[1] or node[2]!r}', flush=True)
            time.sleep(wait)
            return True
        time.sleep(2)
    print(f'not found: {patterns}', flush=True)
    return False


def button():
    return find(r':id/restricted$')


def prefs():
    xml = adb('shell', 'run-as', PKG, 'cat', PREFS)
    return dict(re.findall(r'name="(\w+)" value="([^"]*)"', xml))


def screen():
    m = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
    return tuple(map(int, m.groups())) if m else (1080, 2400)


def start(wait=25):
    print(adb('shell', 'am', 'start', '-W', '-n', ACTIVITY).strip(), flush=True)
    time.sleep(wait)


def restart(wait=25):
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    start(wait)


def write_png(path, w=64, h=64):
    """A small solid-colour PNG, so no image library is needed."""
    raw = b''.join(b'\x00' + b'\x1a\x73\xe8' * w for _ in range(h))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def open_plus_menu():
    return tap(r'upload', r'add file', r'open.*(tools|menu)', r'^\+$', r'attach', wait=4)


def main():
    apk, out = sys.argv[1:3]
    os.makedirs(out, exist_ok=True)
    w, h = screen()
    adb('logcat', '-c')
    print(adb('install', '-r', '-g', apk, timeout=600), flush=True)
    png = os.path.join(out, 'geminiassist-test.png')
    write_png(png)
    adb('push', png, PICTURE)
    os.remove(png)

    # 1. Launch. Dismiss the FreeDroidWarn dialog if it shows on the first start.
    start(40)
    tap(r'^android:id/button1$', wait=2, tries=1)
    shot(out, '01-launch')
    b = button()
    check('launch: block button visible', b, note=f'bounds {b[3:] if b else None}')
    check('launch: starts restricted (no saved mode)', prefs().get('restricted', 'true') == 'true')
    if b:
        check('launch: button below the status bar', b[4] > 0, note=f'top={b[4]}')

    # 2. Tap toggles the mode and saves it
    if b:
        adb('shell', 'input', 'tap', *map(str, center(b)))
        time.sleep(4)
        check('tap: switches to all URLs allowed', prefs().get('restricted') == 'false')
        shot(out, '02-unrestricted')
        open_plus_menu()
        shot(out, '03-plus-menu-unrestricted')
        adb('shell', 'input', 'keyevent', 'KEYCODE_ESCAPE')
        time.sleep(2)
        b = button() or b
        adb('shell', 'input', 'tap', *map(str, center(b)))
        time.sleep(4)
        check('tap again: back to restricted', prefs().get('restricted') == 'true')

    # 3. Drag moves the button without toggling
    b = button()
    if b:
        x, y = center(b)
        tx, ty = w // 2, h // 2
        adb('shell', 'input', 'swipe', str(x), str(y), str(tx), str(ty), '800')
        time.sleep(3)
        moved = button()
        p = prefs()
        check('drag: button moved', moved and abs(center(moved)[0] - tx) < w // 10 and abs(center(moved)[1] - ty) < h // 10,
              note=f'now {center(moved) if moved else None}, target {(tx, ty)}')
        check('drag: position saved', 'buttonX' in p and 'buttonY' in p, note=str(p))
        check('drag: did not toggle the mode', p.get('restricted') == 'true')
        shot(out, '04-dragged')

    # 4. Long press opens the reset dialog; cancel it
    b = button()
    if b:
        x, y = center(b)
        adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '1500')
        time.sleep(3)
        shot(out, '05-long-press')
        check('long press: reset dialog shown', find(r'^Reset chat$'))
        check('long press: dialog cancelled', tap(r'^No$', tries=1))

    # 5. Restart keeps the mode and the position
    before = button()
    restart()
    after = button()
    shot(out, '06-after-restart')
    check('restart: mode kept', prefs().get('restricted') == 'true')
    check('restart: position kept', before and after and abs(center(before)[0] - center(after)[0]) < 20
          and abs(center(before)[1] - center(after)[1]) < 20,
          note=f'{center(before) if before else None} -> {center(after) if after else None}')

    # 6. Swipe up hides the button, the hidden state survives a restart, swipe down shows it
    adb('shell', 'input', 'swipe', str(w // 4), str(h * 3 // 4), str(w // 4), str(h // 3), '150')
    time.sleep(3)
    check('swipe up: button hidden', not button() and prefs().get('buttonHidden') == 'true')
    restart()
    check('restart: button still hidden', not button())
    adb('shell', 'input', 'swipe', str(w // 4), str(h // 3), str(w // 4), str(h * 3 // 4), '150')
    time.sleep(3)
    check('swipe down: button shown again', button() and prefs().get('buttonHidden') == 'false')

    # 7. + menu and attaching a picture in restricted mode (depends on the live site)
    opened = open_plus_menu()
    shot(out, '07-plus-menu-restricted')
    check('+ menu opens (restricted)', opened, hard=False)
    picked = False
    if opened and tap(r'^(Upload )?files?$', r'upload file', r'^Files$', wait=5):
        shot(out, '08-file-picker')
        if not find(r'geminiassist-test'):
            tap(r'show roots', r'navigate up', wait=2, tries=1)
            tap(r'^Downloads?$', wait=3, tries=2)
        picked = tap(r'geminiassist-test', wait=8)
        shot(out, '09-after-attach')
    check('attach a picture (restricted)', picked, hard=False,
          note='signed out, the + menu may offer no upload')

    # 8. No crash, and which hosts restricted mode blocked
    log = adb('logcat', '-d', timeout=300)
    with open(os.path.join(out, 'logcat.txt'), 'w') as f:
        f.write(log)
    crash = re.search(r'FATAL EXCEPTION[\s\S]{0,300}?Process: ' + re.escape(PKG), log)
    check('no crash', not crash)
    blocked = sorted(set(re.findall(r'NOT ON ALLOWLIST\] Blocked access to (\S+)', log)))
    with open(os.path.join(out, 'blocked_hosts.txt'), 'w') as f:
        f.write('\n'.join(blocked) + '\n')

    lines = ['| Check | Result | Note |', '| --- | --- | --- |']
    for name, ok, hard, note in results:
        lines.append(f'| {name} | {"pass" if ok else ("**FAIL**" if hard else "warn")} | {note} |')
    lines += ['', 'Hosts blocked in restricted mode: ' + (', '.join(f'`{b}`' for b in blocked) or 'none')]
    with open(os.path.join(out, 'summary.md'), 'w') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)
    sys.exit(1 if any(not ok and hard for _, ok, hard, _ in results) else 0)


if __name__ == '__main__':
    main()
