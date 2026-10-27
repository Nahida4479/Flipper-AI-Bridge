import re
import sys
from pathlib import Path
from wordfreq import top_n_list

RECORD = 24
COUNT = 9000
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("flipper-app/assets")
PATTERNS = {
    "en": re.compile(r"^[a-z][a-z']*$"),
    "pl": re.compile(r"^[a-ząćęłńóśźż]+$"),
}


def build(lang):
    words = []
    for w in top_n_list(lang, 60000):
        w = w.lower()
        if not PATTERNS[lang].match(w):
            continue
        if len(w) < 2 or len(w.encode("utf-8")) > RECORD - 2:
            continue
        if w not in words:
            words.append(w)
        if len(words) >= COUNT:
            break
    total = len(words)
    ranked = [(w, 255 - int(i * 254 / total)) for i, w in enumerate(words)]
    ranked.sort(key=lambda p: p[0].encode("utf-8"))
    blob = bytearray()
    for w, f in ranked:
        raw = w.encode("utf-8")
        blob.append(f)
        blob += raw + b"\0" * (RECORD - 1 - len(raw))
    (OUT / f"{lang}.dict").write_bytes(bytes(blob))
    print(lang, total, len(blob))


OUT.mkdir(parents=True, exist_ok=True)
for lang in ("en", "pl"):
    build(lang)
