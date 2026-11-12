#!/usr/bin/env python3
"""
Builds Spell Bee's big word bank from Wiktionary (via the kaikki.org extract).

Word data © Wiktionary contributors, CC BY-SA 4.0 (https://en.wiktionary.org).

Steps:
  1. Stream the English Wiktionary JSONL (never stored whole on disk).
  2. Keep plain lowercase English words (nouns, verbs, adjectives, adverbs) with a clean,
     kid-safe definition that doesn't give away the spelling.
  3. Pull language of origin from the etymology and a short example sentence when Wiktionary has one.
  4. Sort into 5 levels by how common the word is (wordfreq), like One Bee → Finals.
Output: compact JSON array of [word, level, pos, definition, sentence, origin].
"""
import argparse, gzip, io, json, random, re, sys, urllib.request
from collections import Counter

from wordfreq import zipf_frequency

URLS = [
    "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz",
    "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl",
]

POS = {"noun": "noun", "verb": "verb", "adj": "adjective", "adv": "adverb"}

# Whole word dropped if any sense carries one of these tags.
BAN_TAGS = {"vulgar", "offensive", "derogatory", "pejorative", "slur", "ethnic", "sexual", "obscene"}
# Individual senses skipped with these tags.
SKIP_TAGS = {"obsolete", "archaic", "dated", "rare", "slang", "informal", "colloquial", "nonstandard",
             "dialectal", "Internet", "nonce-word", "misspelling", "proscribed", "abbreviation",
             "initialism", "acronym", "form-of", "alt-of", "alternative", "euphemistic", "humorous",
             "historical", "obsolete-sense", "uncommon"}
# Kid-safety: words dropped if a definition, category, or example mentions these.
BAN_TEXT = re.compile(
    r"\b(sex\w*|genital\w*|penis|vagin\w*|anus|anal|buttock\w*|breasts?|nipple\w*|testic\w*|semen|sperm|"
    r"erot\w*|porn\w*|orgasm\w*|masturbat\w*|copulat\w*|intercourse|prostitut\w*|brothel|rape\w*|"
    r"incest|fetish\w*|nud(e|ity)|naked|feces|faec\w*|excrement|urin\w*|vomit\w*|drunk\w*|"
    r"narcotic\w*|cocaine|heroin|marijuana|cannabis|opium|suicid\w*|murder\w*|tortur\w*|slur|"
    r"racis\w*|vulgar\w*|obscen\w*|swear\w*|curse word|profan\w*|nazi\w*|lynch\w*)\b", re.I)
BAN_CATEGORY = re.compile(r"(swear|vulgar|sex|slur|offensive|pejorative|derogatory|drug|genital|"
                          r"prostitut|pornograph|obscen|ethnic|insult)", re.I)

LANG_FIX = [("Latin", "Latin"), ("Greek", "Greek"), ("Old English", "Old English"),
            ("Middle English", "Middle English"), ("Old French", "Old French"),
            ("Anglo-Norman", "Anglo-French"), ("Middle French", "French"), ("Old Norse", "Old Norse"),
            ("Middle Dutch", "Dutch"), ("High German", "German")]
ETYM_TEMPLATES = {"inh", "inh+", "der", "der+", "bor", "bor+", "lbor", "uder", "ubor", "slbor", "learned borrowing"}


def norm_lang(name):
    for key, val in LANG_FIX:
        if key in name:
            return val
    return name


def origin_of(entry):
    langs = []
    for t in entry.get("etymology_templates") or []:
        if t.get("name") not in ETYM_TEMPLATES:
            continue
        args = t.get("args") or {}
        code = args.get("2", "")
        if code.endswith("-pro") or code in ("en", ""):
            continue
        exp = (t.get("expansion") or "").strip()
        term = (args.get("3") or "").strip()
        name = exp.replace(term, "").strip(" ,;:") if term else exp
        if not name or len(name.split()) > 4 or "Proto" in name:
            continue
        name = norm_lang(name)
        if not langs or langs[-1] != name:
            langs.append(name)
        if len(langs) >= 3:
            break
    if not langs:
        return ""
    # Native words: "Old English". Borrowed words: the deepest of the first few sources (e.g. Latin, Old Norse).
    chain = [l for l in langs if l != "Middle English"] or langs
    return chain[-1] if len(chain) <= 2 else chain[1]


def clean(text):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text.strip('"“”')


def gives_away(word, text):
    t = text.lower()
    return word in t or (len(word) >= 6 and word[:-2] in t)


def categories(sense):
    out = []
    for c in sense.get("categories") or []:
        out.append(c.get("name", "") if isinstance(c, dict) else str(c))
    return out


def pick(entry, word):
    """Return (definition, sentence) for the best kid-safe sense, None if the word must be dropped,
    or ("", "") when no usable sense exists."""
    definition, sentence = "", ""
    for s in entry.get("senses") or []:
        tags = set(s.get("tags") or [])
        if tags & BAN_TAGS or any(BAN_CATEGORY.search(c) for c in categories(s)):
            return None
        glosses = s.get("glosses") or []
        if any(BAN_TEXT.search(g) for g in glosses):
            return None
        for ex in s.get("examples") or []:
            if BAN_TEXT.search(ex.get("text") or ""):
                return None
        if tags & SKIP_TAGS or s.get("form_of") or s.get("alt_of") or not glosses:
            continue
        gloss = clean(glosses[-1])
        if not definition and 8 <= len(gloss) <= 180 and not gives_away(word, gloss):
            definition = gloss.rstrip(".")
        if definition and not sentence:
            for ex in s.get("examples") or []:
                text = clean(ex.get("text"))
                if ex.get("ref") or ex.get("type") == "quotation":
                    continue
                if 15 <= len(text) <= 150 and re.search(r"\b" + re.escape(word), text, re.I):
                    sentence = text if text[-1] in ".!?" else text + "."
                    break
        if definition and sentence:
            break
    return definition, sentence


def level_for(word, zipf):
    n = len(word)
    if zipf >= 4.2 and n <= 8:
        return 1
    if zipf >= 3.5:
        return 2
    if zipf >= 2.8:
        return 3
    if zipf >= 2.0:
        return 4
    if n >= 7:
        return 5
    return 0


def open_stream(path):
    if path:
        raw = open(path, "rb")
    else:
        raw = None
        for url in URLS:
            try:
                print("Downloading", url, file=sys.stderr)
                raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SpellBeeChamp-wordbank"}), timeout=120)
                break
            except Exception as e:  # try the next URL
                print("  failed:", e, file=sys.stderr)
        if raw is None:
            sys.exit("Could not download Wiktionary data")
    buffered = raw if hasattr(raw, "peek") else io.BufferedReader(raw, 1 << 20)
    head = buffered.peek(2)[:2]
    stream = gzip.GzipFile(fileobj=buffered) if head == b"\x1f\x8b" else buffered
    return io.TextIOWrapper(stream, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="local .jsonl or .jsonl.gz (default: download)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-level", type=int, default=3000)
    ap.add_argument("--blocklist", default="tools/wordbank/blocklist.txt")
    ap.add_argument("--skip", default="spellbee/src/main/assets/words.json", help="hand-written list to skip")
    args = ap.parse_args()

    blocked = set()
    try:
        blocked = {l.strip().lower() for l in open(args.blocklist) if l.strip() and not l.startswith("#")}
    except FileNotFoundError:
        pass
    try:
        blocked |= {w["w"].lower() for w in json.load(open(args.skip))}
    except FileNotFoundError:
        pass

    best, dropped, seen = {}, set(), 0
    for line in open_stream(args.input):
        seen += 1
        if seen % 200000 == 0:
            print(f"  {seen:,} entries, {len(best):,} candidates", file=sys.stderr)
        try:
            e = json.loads(line)
        except ValueError:
            continue
        word = e.get("word") or ""
        if e.get("lang_code", "en") != "en" or not re.fullmatch(r"[a-z]{3,20}", word) or word in blocked:
            continue
        if word in dropped:
            continue
        pos = POS.get(e.get("pos"))
        got = pick(e, word)
        if got is None:
            dropped.add(word)
            best.pop(word, None)
            continue
        if not pos or not got[0]:
            continue
        definition, sentence = got
        origin = origin_of(e)
        score = (2 if sentence else 0) + (1 if origin else 0)
        if word not in best or score > best[word][0]:
            best[word] = (score, pos, definition, sentence, origin)

    levels = {i: [] for i in range(1, 6)}
    for word, (score, pos, definition, sentence, origin) in best.items():
        z = zipf_frequency(word, "en")
        lv = level_for(word, z)
        if lv == 5 and (not origin or origin in ("Old English", "Middle English")):
            continue  # Finals favour words borrowed from other languages, like the real bee
        if lv:
            levels[lv].append((score, z, word, pos, definition, sentence, origin))

    rng = random.Random(2026)
    out, report = [], []
    for lv, items in levels.items():
        if lv == 5:
            rng.shuffle(items)
            items.sort(key=lambda x: -x[0])  # with sentence/origin first, otherwise random
        else:
            items.sort(key=lambda x: (-x[0], -x[1]))  # complete entries first, then most common
        chosen = items[: args.per_level]
        out += [[w, lv, p, d, s, o] for (_, _, w, p, d, s, o) in chosen]
        with_sent = sum(1 for c in chosen if c[5])
        report.append(f"Level {lv}: {len(chosen):,} words (pool {len(items):,}), {with_sent:,} with sentences")
        report.append("   sample: " + ", ".join(c[2] for c in rng.sample(chosen, min(25, len(chosen)))))

    out.sort(key=lambda r: (r[1], r[0]))
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    origins = Counter(r[5] or "(none)" for r in out).most_common(15)
    report.append("Top origins: " + ", ".join(f"{k} {v}" for k, v in origins))
    report.append(f"Total {len(out):,} words from {seen:,} entries; {len(dropped):,} words dropped by safety filters")
    print("\n".join(report))


if __name__ == "__main__":
    main()
