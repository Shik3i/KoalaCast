# Repository tools

Developer-only maintenance scripts. Nothing in this directory runs in production
or during a normal application build.

## `vendor-fonts.py`

Downloads a selected Google Fonts stack, subsets it for the shipped Latin
interface, writes Android TTF and web WOFF2 assets, and places the OFL license in
both asset trees.

```bash
python tools/vendor-fonts.py
python tools/vendor-fonts.py c
```

Run without an argument to list available stacks. The current stack is
Nunito/Nunito Sans (`c`). Review every generated asset and CSS change before
committing. The script requires network access and Python `fonttools` with
Brotli support; it is never a runtime dependency.
