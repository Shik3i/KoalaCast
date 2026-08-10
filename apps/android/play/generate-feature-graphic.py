"""Renders the 1024x500 Play Store feature graphic.

Committed as a script rather than only as a PNG so the graphic can be
regenerated when the wordmark or the palette changes, instead of becoming an
image nobody can reproduce. Colours are the Fjord dark palette, which is the
app's default theme (core/ui/.../Palettes.kt).

    python apps/android/play/generate-feature-graphic.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
ICON = ROOT / "apps" / "web" / "static" / "icon-512.png"
OUT = Path(__file__).resolve().parent / "feature-graphic.png"

WIDTH, HEIGHT = 1024, 500
BG_APP = (0x07, 0x10, 0x1A)
BG_SUNKEN = (0x10, 0x1A, 0x26)
INK = (0xD8, 0xE8, 0xF2)
ACCENT = (0x79, 0xC7, 0xE8)


def load_font(names, size):
    """First font that exists, else Pillow's built-in.

    Play only needs a legible PNG; a missing system font must not stop the
    graphic being produced on a machine that has a different set installed.
    """
    for name in names:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default(size)


def main() -> None:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BG_APP)
    draw = ImageDraw.Draw(canvas)

    # A soft horizontal band so the flat background does not read as an error.
    for x in range(WIDTH):
        blend = x / WIDTH
        draw.line(
            [(x, 0), (x, HEIGHT)],
            fill=tuple(
                round(BG_APP[channel] + (BG_SUNKEN[channel] - BG_APP[channel]) * blend)
                for channel in range(3)
            ),
        )

    icon = Image.open(ICON).convert("RGBA").resize((260, 260), Image.LANCZOS)
    canvas.paste(icon, (86, (HEIGHT - 260) // 2), icon)

    title_font = load_font(["seguisb.ttf", "segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"], 76)
    body_font = load_font(["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"], 32)

    text_x = 410
    draw.text((text_x, 176), "KoalaCast", font=title_font, fill=INK)
    draw.text((text_x, 268), "Podcasts, calmly.", font=body_font, fill=ACCENT)
    draw.text(
        (text_x, 312),
        "No ads. No tracking. No account needed.",
        font=body_font,
        fill=INK,
    )

    canvas.save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
