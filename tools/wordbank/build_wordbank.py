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
    r"racis\w*|vulgar\w*|obscen\w*|swear\w*|curse word|profan\w*|nazi\w*|lynch\w*|castrat\w*|ovulat\w*|"
    r"menstru\w*|pregnan\w*|childbirth|uter\w*|womb|ovar\w*|puerper\w*|contracept\w*|abortion\w*|"
    r"bowel\w*|rectum|disease of|venereal|lust\w*|seduc\w*|lewd|kinky|gay|lesbian|homosexual\w*|"
    r"execution|behead\w*|corpse|genocide|slave\w*|whore|bitch\w*|bastard\w*|damn\w*|hell)\b", re.I)
BAN_CATEGORY = re.compile(r"(swear|vulgar|sex|slur|offensive|pejorative|derogatory|drug|genital|"
                          r"prostitut|pornograph|obscen|ethnic|insult)", re.I)

LANG_FIX = [("Latin", "Latin"), ("Greek", "Greek"), ("Old English", "Old English"),
            ("Middle English", "Middle English"), ("Old French", "Old French"),
            ("Anglo-Norman", "Anglo-French"), ("Middle French", "French"), ("Old Norse", "Old Norse"),
            ("Middle Dutch", "Dutch"), ("High German", "German")]
ETYM_TEMPLATES = {"inh", "inh+", "der", "der+", "bor", "bor+", "lbor", "uder", "ubor", "slbor", "learned borrowing"}


def norm_lang(name):
    name = re.sub(r"^(Borrowed|Learned borrowing|Unadapted borrowing|Semi-learned borrowing|Derived|Inherited)\s+from\s+", "", name, flags=re.I)
    name = re.sub(r"^(From|from)\s+", "", name)
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
        name = norm_lang(re.sub(r"\s*\(.*?\)", "", name).strip())
        if not name:
            continue
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
    """True if the definition contains the word or an obvious part of it ("kicking" -> "kick")."""
    t = text.lower()
    stems = {word}
    if len(word) >= 6:
        stems.add(word[:-2])
    if len(word) >= 7:
        stems |= {word[:6], word[:-3]}
    for suf in ("ing", "ed", "es", "s", "tion", "ment", "ness", "ly", "er", "ity", "ous"):
        if word.endswith(suf) and len(word) - len(suf) >= 4:
            stems.add(word[: -len(suf)])
    return any(st in t for st in stems)


# Definitions that only make sense next to the dictionary entry before them.
VAGUE_GLOSS = re.compile(r"^(A similar|Similar|Synonym of|See |Same as|That which is|The same|Alternative|Abbreviation|"
                         r"Initialism|A variant|Variant)|\bthe verb\b", re.I)


def categories(sense):
    out = []
    for c in sense.get("categories") or []:
        out.append(c.get("name", "") if isinstance(c, dict) else str(c))
    return out


# Words about these topics are skipped entirely (kids' app).
ADULT_TOPIC = re.compile(r"\b(alcohol\w*|beer|wine|liquor|whiske?y|vodka|brew\w*|cigar\w*|tobacco|dating|romantic\w*|"
                         r"lovers?|kiss\w*|flirt\w*|gambl\w*|casino|hangover)\b", re.I)
# Example sentences mentioning these are not used (the word is kept, with its definition).
DARK_SENTENCE = re.compile(r"\b(kill\w*|die|died|dies|dying|death|dead|blood\w*|guns?|shot|shoot\w*|bomb\w*|war|wars|"
                           r"weapon\w*|crime\w*|criminal\w*|prison\w*|jail\w*|violen\w*|attack\w*|murder\w*|stupid|"
                           r"idiot\w*|fat|ugly|hate\w*|drunk\w*|misery|corpse|grave|funeral|terror\w*|abuse\w*|beat|beaten)\b", re.I)


def good_sentence(word, ex):
    text = clean(ex.get("text"))
    if ex.get("ref") or ex.get("type") == "quotation":
        return ""
    if not (15 <= len(text) <= 150) or not re.match(r"[A-Z]", text) or len(text.split()) < 4:
        return ""
    if not re.search(r"\b" + re.escape(word), text, re.I) or DARK_SENTENCE.search(text) or ADULT_TOPIC.search(text):
        return ""
    return text if text[-1] in ".!?" else text + "."


def pick(entry, word):
    """Return (definition, sentence) for the best kid-safe sense, None if the word must be dropped,
    or ("", "") when no usable sense exists. Definition and sentence come from the same sense."""
    cands = []
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
        if not (8 <= len(gloss) <= 180) or gives_away(word, gloss) or gloss.startswith("Used ") or VAGUE_GLOSS.search(gloss):
            continue
        if ADULT_TOPIC.search(gloss):
            return None
        sentence = next((t for t in (good_sentence(word, ex) for ex in s.get("examples") or []) if t), "")
        cands.append((gloss.rstrip("."), sentence))
    if not cands:
        return "", ""
    # Prefer one of the first few meanings that has an example sentence; otherwise the main meaning.
    for gloss, sentence in cands[:3]:
        if sentence:
            return gloss, sentence
    return cands[0][0], ""


def level_for(word, zipf):
    n = len(word)
    if zipf >= 4.4 and n <= 7:
        return 1
    if zipf >= 3.6:
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

    best, dropped, seen, headwords, names = {}, set(), 0, set(), set()
    for line in open_stream(args.input):
        seen += 1
        if seen % 200000 == 0:
            print(f"  {seen:,} entries, {len(best):,} candidates", file=sys.stderr)
        try:
            e = json.loads(line)
        except ValueError:
            continue
        word = e.get("word") or ""
        if e.get("pos") == "name" and re.fullmatch(r"[A-Z][a-z]{2,19}", word):
            names.add(word.lower())
        if e.get("lang_code", "en") != "en" or not re.fullmatch(r"[a-z]{3,20}", word) or word in blocked:
            continue
        if word in dropped:
            continue
        pos = POS.get(e.get("pos"))
        if pos:
            headwords.add(word)
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

    def base_forms(w):
        out = []
        if w.endswith("ies"):
            out.append(w[:-3] + "y")
        if w.endswith("es"):
            out.append(w[:-2])
        if w.endswith("s") and not w.endswith("ss"):
            out.append(w[:-1])
        return out

    def verb_forms(w):
        out = []
        if len(w) >= 6 and w.endswith("ied"):
            out.append(w[:-3] + "y")
        if len(w) >= 5 and w.endswith("ed"):
            out += [w[:-2], w[:-1]]
            if w[-3] == w[-4]:
                out.append(w[:-3])
        if len(w) >= 6 and w.endswith("ing"):
            out += [w[:-3], w[:-3] + "e"]
            if w[-4] == w[-5]:
                out.append(w[:-4])
        return out

    inflected = set()
    for word, (score, pos, *_rest) in best.items():
        if any(b in headwords for b in base_forms(word)):
            inflected.add(word)  # plurals / -s forms: "tears", "studs"
        elif pos != "noun" and any(b in headwords for b in verb_forms(word)):
            inflected.add(word)  # "flooded", "conflicting" (but keep nouns like "building")
    for word in inflected:
        best.pop(word)

    levels = {i: [] for i in range(1, 6)}
    for word, (score, pos, definition, sentence, origin) in best.items():
        if word in names and not sentence:
            continue  # mostly a first name or surname ("steve", "barry")
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
            # Rare but real: complete entries first, then the least obscure of the rare words
            items.sort(key=lambda x: (-x[0], -x[1], x[2]))
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
    report.append(f"Removed {len(inflected):,} plural/-ed/-ing forms")
    report.append(f"Total {len(out):,} words from {seen:,} entries; {len(dropped):,} words dropped by safety filters")
    print("\n".join(report))


if __name__ == "__main__":
    main()
