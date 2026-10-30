"""
Turns the documents tools/word-fit.ps1 made into WordFitTest's assets: copies each .docx to
app/src/androidTest/assets/word-fit/ without the author's name (the repository is public), and
writes word.txt, where Word's PDF of it puts each row marker (R001, …).

    python tools/word-fit.py <folder of word-fit.ps1>        (needs PyMuPDF: pip install pymupdf)
"""
import glob
import os
import re
import sys
import zipfile

import pymupdf

ASSETS = os.path.join(os.path.dirname(__file__), '..', 'app/src/androidTest/assets/word-fit')


def without_author(src, dst):
    with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zo:
        for item in zi.infolist():
            data = zi.read(item.filename)
            if item.filename in ('docProps/core.xml', 'docProps/app.xml'):
                text = data.decode('utf-8')
                for tag in ('dc:creator', 'cp:lastModifiedBy', 'Company'):
                    text = re.sub(f'<{tag}>[^<]*</{tag}>', f'<{tag}></{tag}>', text)
                data = text.encode('utf-8')
            zo.writestr(item, data)


def marks(pdf):
    doc = pymupdf.open(pdf)
    found = {}
    for number, page in enumerate(doc):
        for word in page.get_text('words'):
            if re.fullmatch(r'R\d{3}', word[4]) and word[4] != 'R900':
                found.setdefault(word[4], (number, round(word[1] / page.rect.height, 4)))
    return doc.page_count, found


def main(folder):
    os.makedirs(ASSETS, exist_ok=True)
    lines = ["# Where Word put each row marker: file, pages, then marker:page:top (top as a fraction of the page). "
             "From Word's own PDFs (tools/word-fit.ps1)."]
    for docx in sorted(glob.glob(os.path.join(folder, '*.docx'))):
        name = os.path.splitext(os.path.basename(docx))[0]
        pages, found = marks(os.path.join(folder, name + '.pdf'))
        without_author(docx, os.path.join(ASSETS, name + '.docx'))
        lines.append(f'{name} {pages} ' + ' '.join(f'{m}:{p}:{t}' for m, (p, t) in sorted(found.items())))
        print(name, pages, 'pages,', len(found), 'markers')
    with open(os.path.join(ASSETS, 'word.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


main(sys.argv[1])
