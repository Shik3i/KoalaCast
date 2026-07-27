"""Vendor a type stack into the repository. A one-off developer tool.

Nothing here runs at build time, in Docker, or on a user's device. You run it
once when the type stack changes, and you *commit the font files it writes* —
the same way `apps/web/static/fonts/phosphor-*.ttf` and
`apps/android/core/ui/src/main/res/font/*.ttf` are committed today. From then on
the fonts are ordinary tracked assets and this script is irrelevant:

  * Android — TTF in `core/ui/src/main/res/font/` is compiled into the APK by
    the Android resource pipeline. Nothing is downloaded at runtime.
  * Web — WOFF2 in `apps/web/static/fonts/` is copied verbatim into `build/` by
    adapter-static, which the Dockerfile copies to `/app/web/build`, which the
    Go binary serves from `WEB_STATIC_DIR`. The image is self-contained; the
    browser fetches the fonts from your instance, never from a font provider.

The upstream OFL text is written next to the fonts in both trees, which the
licence requires when redistributing.

    python tools/vendor-fonts.py <stack>

Run with no arguments to list the stacks. `Type.kt` (Android) and the `--font-*`
variables in `app.css` (web) still name the families by hand — this script only
fetches the files and writes the @font-face rules.
"""
import io, os, sys, urllib.parse, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ANDROID_FONTS = os.path.join(ROOT, "apps", "android", "core", "ui", "src", "main", "res", "font")
ANDROID_LICENSES = os.path.join(ROOT, "apps", "android", "core", "ui", "licenses")
WEB_FONTS = os.path.join(ROOT, "apps", "web", "static", "fonts")
WEB_CSS = os.path.join(ROOT, "apps", "web", "src", "lib", "styles", "fonts.css")

GF = "https://github.com/google/fonts/raw/main/ofl/"

# Latin plus the punctuation the UI actually uses. Cyrillic and Greek are dropped:
# the interface ships in English and German, and a listener who needs another
# script gets it from the system font in podcast titles, not from the UI face.
UNICODES = ("U+0020-007E,U+00A0-00FF,U+0100-017F,U+2013,U+2014,U+2018,U+2019,"
            "U+201C,U+201D,U+2022,U+00B7,U+00D7,U+2026,U+20AC,U+2212")

# family key -> (google-fonts dir, [(upstream file, android res name, css weight)])
FAMILIES = {
    "fraunces": ("fraunces", [
        ("Fraunces[SOFT,WONK,opsz,wght].ttf", "fraunces_variable.ttf", "100 900"),
    ]),
    "firasans": ("firasans", [
        ("FiraSans-Regular.ttf", "fira_sans_regular.ttf", "400"),
        ("FiraSans-Medium.ttf", "fira_sans_medium.ttf", "500"),
        ("FiraSans-SemiBold.ttf", "fira_sans_semibold.ttf", "600"),
        ("FiraSans-Bold.ttf", "fira_sans_bold.ttf", "700"),
    ]),
    "alegreya": ("alegreya", [
        ("Alegreya[wght].ttf", "alegreya_variable.ttf", "400 900"),
    ]),
    "alegreyasans": ("alegreyasans", [
        ("AlegreyaSans-Regular.ttf", "alegreya_sans_regular.ttf", "400"),
        ("AlegreyaSans-Medium.ttf", "alegreya_sans_medium.ttf", "500"),
        ("AlegreyaSans-Bold.ttf", "alegreya_sans_bold.ttf", "700"),
    ]),
    "nunito": ("nunito", [
        ("Nunito[wght].ttf", "nunito_variable.ttf", "200 1000"),
    ]),
    "nunitosans": ("nunitosans", [
        ("NunitoSans[YTLC,opsz,wdth,wght].ttf", "nunito_sans_variable.ttf", "200 1000"),
    ]),
}

# stack -> {css family name: family key}. Display carries headlines, ui carries
# headings and body, meta carries counts and time codes.
STACKS = {
    "a": {"label": "Warm Editorial — Fraunces + Fira Sans",
          "faces": [("Fraunces", "fraunces"), ("Fira Sans", "firasans")],
          "roles": {"display": "Fraunces", "ui": "Fraunces",
                    "sans": "Fira Sans", "mono": "Fira Sans"}},
    "b": {"label": "Literarisch — Alegreya + Alegreya Sans",
          "faces": [("Alegreya", "alegreya"), ("Alegreya Sans", "alegreyasans")],
          "roles": {"display": "Alegreya", "ui": "Alegreya",
                    "sans": "Alegreya Sans", "mono": "Alegreya Sans"}},
    "c": {"label": "Weich & freundlich — Nunito + Nunito Sans",
          "faces": [("Nunito", "nunito"), ("Nunito Sans", "nunitosans")],
          "roles": {"display": "Nunito", "ui": "Nunito",
                    "sans": "Nunito Sans", "mono": "Nunito Sans"}},
}


def fetch(gf_dir, filename):
    url = GF + urllib.parse.quote(gf_dir + "/" + filename)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def to_woff2(ttf_bytes):
    from fontTools import subset
    from fontTools.ttLib import TTFont
    font = TTFont(io.BytesIO(ttf_bytes))
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.drop_tables = []
    s = subset.Subsetter(options=opts)
    s.populate(unicodes=subset.parse_unicodes(UNICODES))
    s.subset(font)
    font.flavor = "woff2"
    buf = io.BytesIO()
    font.save(buf)
    return buf.getvalue()


def vendor(stack_key):
    stack = STACKS[stack_key]
    for d in (ANDROID_FONTS, ANDROID_LICENSES, WEB_FONTS, os.path.dirname(WEB_CSS)):
        os.makedirs(d, exist_ok=True)

    css_rules = []
    for css_name, family_key in stack["faces"]:
        gf_dir, files = FAMILIES[family_key]

        licence = fetch(gf_dir, "OFL.txt")
        lic_name = "OFL-%s.txt" % css_name.replace(" ", "")
        open(os.path.join(ANDROID_LICENSES, lic_name), "wb").write(licence)
        open(os.path.join(WEB_FONTS, lic_name), "wb").write(licence)

        for upstream, android_name, weight in files:
            ttf = fetch(gf_dir, upstream)
            open(os.path.join(ANDROID_FONTS, android_name), "wb").write(ttf)

            woff2 = to_woff2(ttf)
            web_name = android_name.replace(".ttf", ".woff2")
            open(os.path.join(WEB_FONTS, web_name), "wb").write(woff2)

            css_rules.append(
                "@font-face {\n"
                "\tfont-family: '%s';\n"
                "\tfont-weight: %s;\n"
                "\tfont-style: normal;\n"
                "\tfont-display: swap;\n"
                "\tsrc: url('/fonts/%s') format('woff2');\n"
                "}" % (css_name, weight, web_name)
            )
            print("  %-34s ttf %6d B   woff2 %6d B" % (android_name, len(ttf), len(woff2)))

    header = (
        "/* GENERATED by tools/vendor-fonts.py — do not edit by hand.\n"
        " *\n"
        " * Self-hosted so the app makes no third-party request at launch, and\n"
        " * subsetted to Latin plus the punctuation the interface uses.\n"
        " * Licences sit next to the files in static/fonts/.\n"
        " */\n\n"
    )
    open(WEB_CSS, "w", encoding="utf-8", newline="\n").write(header + "\n\n".join(css_rules) + "\n")
    print("\nwrote", os.path.relpath(WEB_CSS, ROOT))
    print("\nNow point the CSS variables in app.css at these families:")
    for role, name in stack["roles"].items():
        print("  --font-%-8s '%s'" % (role + ":", name))
    print("\n...and update ArchivoCondensed/Bricolage/Outfit/PlexMono in Type.kt.")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in STACKS:
        print(__doc__)
        print("Stacks:")
        for k, v in STACKS.items():
            print("  %s   %s" % (k, v["label"]))
        sys.exit(1)
    print("Vendoring stack %s — %s\n" % (sys.argv[1], STACKS[sys.argv[1]]["label"]))
    vendor(sys.argv[1])
    print("\nRemember to commit the font files it just wrote.")
