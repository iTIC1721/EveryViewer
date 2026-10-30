"""
Writes app/src/main/resources/com/tabpdf/doc/office/office-fonts.bin: for the fonts Word documents
use (Korean fonts and common Latin ones installed on this PC), how tall Word makes a single-spaced
line, how wide each printable ASCII letter, a Hangul syllable, Han and some symbols are, and how
much pairs of letters are kerned. The app has none of these fonts, so it draws with the system
ones and spaces their letters to these widths, so lines break and pages end where Word's do
(기획서 5.7). Only numbers are taken from the fonts.

Run on Windows:  python tools/office-font-metrics.py      (needs fontTools: pip install fonttools)

Word's single line, measured against its PDFs: (winAscent + winDescent) x 1.3 for Korean fonts,
max(win, hhea) for others.

File format (big-endian): "OFM1", font count (u16), then per font:
  names (u8 count, each u8 length + UTF-8), ascent, descent, hangul, han (i16, thousandths of an em),
  95 ASCII advances (i16), bold flag (u8) [+ 95 bold advances], other letters (u16 count, each
  u16 char + i16 advance), kerning pairs (u16 count, each u8 + u8 letters + i16 value).
"""
import glob
import os
import struct

from fontTools.ttLib import TTCollection, TTFont

OUT = os.path.join(os.path.dirname(__file__), '..', 'app/src/main/resources/com/tabpdf/doc/office/office-fonts.bin')
FONT_FILES = glob.glob('C:/Windows/Fonts/*.tt[fc]') + glob.glob('C:/Windows/Fonts/*.otf') + \
    glob.glob(os.path.expandvars(r'%LOCALAPPDATA%\Microsoft\Windows\Fonts\*.*'))

# Latin fonts kept besides every Korean one: Windows' and Office's text fonts.
LATIN = {
    'Arial', 'Arial Narrow', 'Arial Black', 'Bahnschrift', 'Book Antiqua', 'Bookman Old Style', 'Calibri', 'Calibri Light',
    'Cambria', 'Candara', 'Century', 'Century Gothic', 'Century Schoolbook', 'Comic Sans MS', 'Consolas', 'Constantia',
    'Corbel', 'Courier New', 'Franklin Gothic Book', 'Franklin Gothic Medium', 'Garamond', 'Georgia', 'Gill Sans MT',
    'Impact', 'Lucida Console', 'Lucida Sans Unicode', 'Microsoft Sans Serif', 'Palatino Linotype', 'Segoe UI',
    'Segoe UI Light', 'Segoe UI Semibold', 'Segoe UI Semilight', 'Tahoma', 'Times New Roman', 'Trebuchet MS', 'Verdana',
    'Cascadia Code', 'Cascadia Mono', 'Aptos',
}
# Korean fonts made for other scripts first.
SKIP = ('Noto Sans CJK JP', 'Noto Sans CJK SC', 'Noto Sans CJK TC', 'Noto Sans CJK HK', 'Noto Sans Mono CJK JP',
        'Noto Sans Mono CJK SC', 'Noto Sans Mono CJK TC', 'Noto Sans Mono CJK HK')

HANGUL = '가나다라마바사아자차카타파하한글신규거래처등록및결제조건변경요청'
OTHERS = '—–―·…‘’“”•※○●◎□■△▲▽▼◇◆☆★→←↑↓↔①②③④⑤⑥⑦⑧⑨⑩ⅠⅡⅢⅣⅤ℃°±×÷「」『』《》〈〉【】〔〕、。ㆍ～￦％'
HAN = '漢字文書國家'


def faces():
    """Every face installed: (family names, subfamily, font)."""
    for path in FONT_FILES:
        try:
            fonts = TTCollection(path).fonts if path.lower().endswith('.ttc') else [TTFont(path)]
        except Exception:
            continue
        for font in fonts:
            try:
                name = font['name']
                families = []
                for r in name.names:
                    if r.nameID == 1 and r.platformID == 3 and r.langID in (0x409, 0x412):
                        families.append(r.toUnicode())
                english = name.getDebugName(1)
                if english and english not in families:
                    families.insert(0, english)
                yield families, name.getDebugName(2) or '', font
            except Exception:
                continue


def advance_of(font):
    cmap, hmtx, upm = font.getBestCmap(), font['hmtx'], font['head'].unitsPerEm
    def adv(c):
        g = cmap.get(ord(c))
        return round(hmtx[g][0] * 1000 / upm) if g else 0
    return adv


def widths(font):
    adv = advance_of(font)
    ascii_ = [adv(chr(c)) for c in range(0x20, 0x7F)]
    hangul = {adv(c) for c in HANGUL}
    han = {adv(c) for c in HAN}
    others = [(c, adv(c)) for c in OTHERS if adv(c)]
    return ascii_, (hangul.pop() if len(hangul) == 1 else 0), (han.pop() if len(han) == 1 else 0), others


def kern_pairs(font):
    """Kerning between two printable ASCII letters (the 'kern' feature or table), in thousandths of an em."""
    cmap = font.getBestCmap()
    upm = font['head'].unitsPerEm
    chars = [chr(c) for c in range(0x21, 0x7F)]
    glyph = {c: cmap.get(ord(c)) for c in chars}
    pairs = {}
    if 'GPOS' in font and font['GPOS'].table.FeatureList:
        g = font['GPOS'].table
        lookups = set()
        for fr in g.FeatureList.FeatureRecord:
            if fr.FeatureTag == 'kern':
                lookups.update(fr.Feature.LookupListIndex)
        for li in sorted(lookups):
            lookup = g.LookupList.Lookup[li]
            for st in lookup.SubTable:
                if lookup.LookupType == 9:
                    if st.ExtensionLookupType != 2:
                        continue
                    st = st.ExtSubTable
                elif lookup.LookupType != 2:
                    continue
                coverage = st.Coverage.glyphs
                covered = set(coverage)
                for a in chars:
                    ga = glyph[a]
                    if ga not in covered:
                        continue
                    for b in chars:
                        gb = glyph[b]
                        if gb is None or (a, b) in pairs:
                            continue
                        v = None
                        if st.Format == 1:
                            for r in st.PairSet[coverage.index(ga)].PairValueRecord:
                                if r.SecondGlyph == gb:
                                    v = getattr(r.Value1, 'XAdvance', 0) if r.Value1 else 0
                                    break
                        else:
                            r = st.Class1Record[st.ClassDef1.classDefs.get(ga, 0)].Class2Record[st.ClassDef2.classDefs.get(gb, 0)]
                            v = getattr(r.Value1, 'XAdvance', 0) if r.Value1 else 0
                        if v is not None:
                            pairs[(a, b)] = v
    elif 'kern' in font:
        by_glyph = {g: c for c, g in glyph.items() if g}
        for t in font['kern'].kernTables:
            for (ga, gb), v in getattr(t, 'kernTable', {}).items():
                if ga in by_glyph and gb in by_glyph:
                    pairs.setdefault((by_glyph[ga], by_glyph[gb]), v)
    return {k: round(v * 1000 / upm) for k, v in pairs.items() if round(v * 1000 / upm) != 0}


def line(font, korean):
    """Word's single line: ascent and descent in thousandths of an em."""
    os2, hhea, upm = font['OS/2'], font['hhea'], font['head'].unitsPerEm
    win = (os2.usWinAscent + os2.usWinDescent) / upm
    if korean:
        single = win * 1.3
        ascent = os2.usWinAscent / upm + 0.39 * (single - win)  # the extra goes mostly below the text
    else:
        single = max(win, (hhea.ascent - hhea.descent + hhea.lineGap) / upm)
        ascent = os2.usWinAscent / upm
    return round(ascent * 1000), round((single - ascent) * 1000)


REGULAR = ('Regular', 'Normal', 'Book', 'Roman', 'Medium', 'Light', 'R')


def collect():
    families = {}
    for names, sub, font in faces():
        english = names[0]
        if english.startswith(SKIP):
            continue
        korean = 0xAC00 in (font.getBestCmap() or {})
        if not korean and english not in LATIN:
            continue
        entry = families.setdefault(english, {'names': names, 'korean': korean, 'faces': {}})
        for n in names:
            if n not in entry['names']:
                entry['names'].append(n)
        entry['faces'].setdefault(sub, font)
    return families


def main():
    out = bytearray(b'OFM1')
    rows = []
    for english, entry in sorted(collect().items()):
        faces_ = entry['faces']
        regular = next((faces_[s] for s in REGULAR if s in faces_), None) or next(iter(faces_.values()))
        bold = faces_.get('Bold')
        ascent, descent = line(regular, entry['korean'])
        ascii_, hangul, han, others = widths(regular)
        pairs = sorted(kern_pairs(regular).items())
        b = bytearray()
        names = entry['names'][:255]
        b += struct.pack('>B', len(names))
        for n in names:
            e = n.encode('utf-8')[:255]
            b += struct.pack('>B', len(e)) + e
        b += struct.pack('>4h', ascent, descent, hangul, han)
        b += struct.pack('>95h', *ascii_)
        if bold is not None:
            b += struct.pack('>B', 1) + struct.pack('>95h', *widths(bold)[0])
        else:
            b += struct.pack('>B', 0)
        b += struct.pack('>H', len(others))
        for c, w in others:
            b += struct.pack('>Hh', ord(c), w)
        b += struct.pack('>H', len(pairs))
        for (x, y), v in pairs:
            b += struct.pack('>BBh', ord(x), ord(y), v)
        rows.append(bytes(b))
        print(f'{english}: single {(ascent + descent) / 1000:.3f}, hangul {hangul}, {len(pairs)} kerning pairs'.encode('ascii', 'replace').decode())
    out += struct.pack('>H', len(rows)) + b''.join(rows)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as f:
        f.write(out)
    print(len(rows), 'fonts,', len(out), 'bytes ->', os.path.normpath(OUT))


main()
