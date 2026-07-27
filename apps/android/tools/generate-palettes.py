"""Generate Android Palettes.kt from the web client's app.css.

The CSS is the single source of truth for the nine palettes; this script resolves
the same cascade the browser does and emits the Kotlin equivalents.
"""
import re, os

# Paths resolve from this file, so the script runs from anywhere in the repo.
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
CSS = os.path.join(ROOT, "apps", "web", "src", "lib", "styles", "app.css")
OUT = os.path.join(ROOT, "apps", "android", "core", "ui", "src", "main", "kotlin",
                   "net", "koalastuff", "koalacast", "core", "ui", "theme", "Palettes.kt")

PALETTES = ["eucalyptus", "fjord", "ember", "lavender", "aurora",
            "sandstone", "obsidian", "paper", "ultraviolet"]

src = open(CSS, encoding="utf-8").read()

# ---- parse every :root rule into {selector: {var: value}} -------------------
blocks = {}
for m in re.finditer(r"(:root(?:\[[^\]]+\])*)\s*\{([^}]*)\}", src):
    sel, body = m.group(1), m.group(2)
    decls = dict(re.findall(r"(--[a-z0-9-]+)\s*:\s*([^;]+);", body))
    if sel in blocks:
        blocks[sel].update(decls)
    else:
        blocks[sel] = decls


def resolve(palette, light):
    """Apply the cascade in the same order the browser would."""
    order = [":root"]
    if palette != "eucalyptus":
        order.append(f":root[data-palette='{palette}']")
    if light:
        order.append(":root[data-theme='light']")
        if palette != "eucalyptus":
            order.append(f":root[data-theme='light'][data-palette='{palette}']")
    out = {}
    for sel in order:
        out.update(blocks.get(sel, {}))
    return out


def hex_to_argb(v):
    v = v.strip()
    m = re.fullmatch(r"#([0-9a-fA-F]{6})", v)
    if m:
        return "0xFF" + m.group(1).upper()
    m = re.fullmatch(r"rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)", v)
    if m:
        r, g, b = int(m.group(1)), int(m.group(2)), int(m.group(3))
        a = round(float(m.group(4) or 1) * 255)
        return "0x%02X%02X%02X%02X" % (a, r, g, b)
    raise ValueError("cannot parse colour: " + v)


def color(v):
    return "Color(%s)" % hex_to_argb(v)


def tile_stripes(v):
    """--bg-tile: repeating-linear-gradient(135deg, #141d18 0 12px, #1f2e26 12px 24px)"""
    hexes = re.findall(r"#[0-9a-fA-F]{6}", v)
    return color(hexes[0]), color(hexes[1])


FIELDS = [
    ("bgApp", "--bg-app"), ("bgPanel", "--bg-panel"), ("bgRail", "--bg-rail"),
    ("bgSunken", "--bg-sunken"), ("bgTransport", "--bg-transport"),
    ("ink", "--ink"), ("inkStrong", "--ink-strong"), ("ink2", "--ink-2"),
    ("ink3", "--ink-3"), ("ink4", "--ink-4"),
    ("accentFill", "--accent-fill"), ("accentOn", "--accent-on"),
    ("accentInk", "--accent-ink"), ("accentWash", "--accent-wash"),
    ("borderUi", "--border-ui"), ("borderHair", "--border-hair"),
    ("borderRow", "--border-row"), ("dataBar", "--data-bar"), ("track", "--track"),
]


def emit(palette, light):
    v = resolve(palette, light)
    name = "Koala%s%sColors" % (palette.capitalize(), "Light" if light else "Dark")
    lines = [f"private val {name} = KoalaColors("]
    for field, var in FIELDS:
        lines.append(f"    {field} = {color(v[var])},")
    heat = ", ".join(color(v[f"--heat-{i}"]) for i in range(5))
    lines.append(f"    heatmap = listOf({heat}),")
    d, l = tile_stripes(v["--bg-tile"])
    lines.append(f"    tileStripeDark = {d},")
    lines.append(f"    tileStripeLight = {l},")
    lines.append(f"    isDark = {'false' if light else 'true'},")
    lines.append(")")
    return name, "\n".join(lines)


parts = []
lookup = []
for p in PALETTES:
    for light in (False, True):
        n, code = emit(p, light)
        parts.append(code)
        lookup.append((p, light, n))

entries = []
for p in PALETTES:
    dark = next(n for pp, l, n in lookup if pp == p and not l)
    lite = next(n for pp, l, n in lookup if pp == p and l)
    entries.append(f"    PaletteId.{p.upper()} to (KoalaDarkColors.let {{ {dark} }} to {lite}),")

HEADER = '''package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.ui.graphics.Color
import net.koalastuff.koalacast.core.model.PaletteId

/*
 * GENERATED — do not edit by hand.
 *
 * The nine palettes are defined once, in the web client's
 * `apps/web/src/lib/styles/app.css`, and mirrored here so both clients cannot
 * drift apart. Regenerate with `make android-palettes` after changing the CSS.
 *
 * Each entry resolves the same cascade the browser applies:
 *   :root -> :root[data-palette=X] -> [data-theme=light] -> [data-theme=light][data-palette=X]
 */

'''

FOOTER = '''

/** Every palette, in the order the settings screen lists them. */
internal val KoalaPalettes: Map<PaletteId, Pair<KoalaColors, KoalaColors>> = mapOf(
%s
)

/** The colours for one palette in one mode. */
fun koalaColors(palette: PaletteId, dark: Boolean): KoalaColors {
    val pair = KoalaPalettes[palette] ?: KoalaPalettes.getValue(PaletteId.DEFAULT)
    return if (dark) pair.first else pair.second
}
''' % "\n".join(
    f"    PaletteId.{p.upper()} to ("
    f"{next(n for pp, l, n in lookup if pp == p and not l)} to "
    f"{next(n for pp, l, n in lookup if pp == p and l)}),"
    for p in PALETTES
)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write(HEADER + "\n\n".join(parts) + FOOTER)
print("wrote", OUT)
print("palettes:", len(PALETTES), "colour sets:", len(parts))
