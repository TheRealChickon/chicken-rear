"""Render the twelve first-round labels with librsvg and Noto Sans CJK SC.

Pass the fixture directory. The larger frame uses the same twelve lines spaced down a
1200 x 2608 screen. The recognition benchmark reads these images with the app's Kotlin pipeline.
"""
import subprocess
import sys
from pathlib import Path
from xml.sax.saxutils import escape

LABELS = ["设置 Settings", "显示与亮度", "网络和互联网", "搜索 Chicken Rear", "Battery 84%",
          "Übertragung starten", "蓝牙 Bluetooth", "通知与状态栏", "Privacy & security",
          "無線網路", "画面を検索", "화면 검색"]


def render(folder, width, height, size, dark):
    bg, fg = ("#121212", "#ececec") if dark else ("white", "black")
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">',
           f'<rect width="100%" height="100%" fill="{bg}"/>']
    for i, text in enumerate(LABELS):
        y = 70 + i * (95 if height == 1240 else 215)
        svg.append(f'<text fill="{fg}" x="55" y="{y}" font-family="Noto Sans CJK SC" '
                   f'font-size="{size}">{escape(text)}</text>')
    path = folder / f'{width}-{size}-{"dark" if dark else "light"}.png'
    subprocess.run(["rsvg-convert", "-o", str(path)], input=("".join(svg) + "</svg>").encode(), check=True)


if __name__ == "__main__":
    folder = Path(sys.argv[1])
    folder.mkdir(parents=True, exist_ok=True)
    for dark in (False, True):
        for size in (24, 32, 42):
            render(folder, 1080, 1240, size, dark)
        render(folder, 1200, 2608, 32, dark)
    scripts = {
        "arabic": ("Noto Sans Arabic", "تحديث التطبيقات متاح الآن"),
        "cjk": ("Noto Sans CJK SC", "保存文件并关闭窗口"),
        "cyrillic": ("Noto Sans", "Сохранить изменения"),
        "devanagari": ("Noto Sans Devanagari", "अपनी भाषा का चयन करें"),
        "el": ("Noto Sans", "Αποθήκευση αλλαγών"),
        "kannada": ("Noto Sans Kannada", "ನಿಮ್ಮ ಭಾಷೆಯನ್ನು ಆಯ್ಕೆಮಾಡಿ"),
        "korean": ("Noto Sans CJK KR", "변경사항을 저장하세요"),
        "ta": ("Noto Sans Tamil", "மொழியைத் தேர்ந்தெடுக்கவும்"),
        "te": ("Noto Sans Telugu", "మీ భాషను ఎంచుకోండి"),
        "th": ("Noto Sans Thai", "เลือกภาษาของคุณ"),
    }
    other = folder / "scripts"
    other.mkdir(exist_ok=True)
    for pack, (font, text) in scripts.items():
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="860" height="120">'
               '<rect width="100%" height="100%" fill="white"/>'
               f'<text x="430" y="75" text-anchor="middle" font-family="{font}" font-size="40">'
               f'{escape(text)}</text></svg>')
        subprocess.run(["rsvg-convert", "-o", str(other / f"{pack}.png")], input=svg.encode(), check=True)
    (folder / "labels.txt").write_text("\n".join(LABELS), encoding="utf-8")
