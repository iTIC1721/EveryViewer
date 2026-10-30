# Makes the documents of WordFitTest with Word (Windows, Word installed): tables that just fill a
# page, each saved with a PDF of Word's own layout. Then: python tools/word-fit.py <Out>
#   powershell -File tools/word-fit.ps1 [-Out <folder>] [-Only name,...]
param([string]$Out = "$env:TEMP\word-fit", [string[]]$Only)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Out | Out-Null

$ko = @('서울특별시 중구 세종대로 110', '분기별 매출 보고서(2026년 3분기) 검토 의견', '담당자 확인 후 회신 바랍니다', '계약 기간: 2026.01.01 ~ 2026.12.31',
        '신규 거래처 등록 및 결제 조건 변경 요청', '비고 없음', '회의록 첨부 — 참석자 12명, 안건 3건', 'Q3 KPI 달성률 87.5% (목표 대비 -2.5%p)')
$en = @('Quarterly revenue review and comments', 'Please reply after checking with the owner', 'Contract period: Jan 1 to Dec 31, 2026',
        'Request to register a new vendor and change payment terms', 'None', 'Minutes attached: 12 attendees, 3 agenda items',
        'Q3 KPI achievement 87.5% (2.5 points below target)', 'The quick brown fox jumps over the lazy dog')

function Text([string[]]$pool, [int]$i, [int]$words) {
    $s = @(); for ($k = 0; $k -lt $words; $k++) { $s += $pool[($i * 7 + $k * 3) % $pool.Length] }; $s -join ', '
}

function Make($name, [scriptblock]$setup, [scriptblock]$row, [int]$cols = 2, [int]$pages = 1, [scriptblock]$table, [string]$after) {
    if ($Only -and $name -notin $Only) { return }
    $doc = $w.Documents.Add()
    & $setup $doc
    $t = $doc.Tables.Add($doc.Range(0, 0), 1, $cols)
    $t.Borders.Enable = $true
    if ($table) { & $table $t }
    $i = 1
    & $row $t.Rows.Item(1) $i
    while ($true) {
        $r = $t.Rows.Add()
        $i++
        & $row $r $i
        if ($r.Range.Information(3) -gt $pages) { $r.Delete(); break }
        if ($i -gt 400) { break }
    }
    if ($after) {
        $p = $doc.Content.Paragraphs.Last.Range
        $p.Text = $after
        $doc.Content.InsertParagraphAfter()
        $doc.Content.Paragraphs.Last.Range.Text = $after + ' 2'
    }
    $doc.Repaginate()
    $doc.SaveAs2("$Out\$name.docx", 16)
    $doc.ExportAsFixedFormat("$Out\$name.pdf", 17)
    "{0}: {1} rows, {2} pages" -f $name, $t.Rows.Count, $doc.ComputeStatistics(2)
    $doc.Close(0)
}

$w = New-Object -ComObject Word.Application
try {
    # Korean Word's defaults (맑은 고딕 11, 8pt after), one line per cell.
    Make 'ko-short' { } { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = $ko[$i % $ko.Length] }
    # Cells of Korean with numbers and English, wrapping to two or three lines.
    Make 'ko-wrap' { } { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $ko $i (1 + $i % 3); $r.Cells.Item(3).Range.Text = Text $ko ($i + 3) 2 } 3
    # Calibri 11 (English Word's body font), wrapping.
    Make 'en-calibri' { param($d) $d.Styles.Item(-1).Font.Name = 'Calibri'; $d.Styles.Item(-1).ParagraphFormat.LineSpacingRule = 0; $d.Styles.Item(-1).ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $en $i (1 + $i % 3) }
    # Times New Roman 12, single spacing.
    Make 'times' { param($d) $s = $d.Styles.Item(-1); $s.Font.Name = 'Times New Roman'; $s.Font.Size = 12; $s.ParagraphFormat.LineSpacingRule = 0; $s.ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $en $i (1 + $i % 4) }
    # Arial 10 for Latin, Korean in 맑은 고딕.
    Make 'arial-mixed' { param($d) $s = $d.Styles.Item(-1); $s.Font.Name = 'Arial'; $s.Font.Size = 10; $s.ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = (Text $ko $i 1) + ' / ' + (Text $en $i 1) } 2
    # 굴림 10, single spacing.
    Make 'gulim' { param($d) $s = $d.Styles.Item(-1); $s.Font.Name = '굴림'; $s.Font.Size = 10; $s.ParagraphFormat.LineSpacingRule = 0; $s.ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $ko $i (1 + $i % 2) }
    # Rows of exact and at-least heights.
    Make 'heights' { param($d) $d.Styles.Item(-1).ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = $ko[$i % $ko.Length]; $r.HeightRule = 1 + ($i % 2); $r.Height = 22 + ($i % 3) * 6 }
    # A table over three pages whose first row repeats on each.
    Make 'header-repeat' { } { param($r, $i) if ($i -gt 1) { $r.HeadingFormat = 0; $r.Shading.BackgroundPatternColor = -16777216 }; $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $ko $i (1 + $i % 2) } 2 3 `
        { param($t) $t.Rows.Item(1).HeadingFormat = -1; $t.Rows.Item(1).Shading.BackgroundPatternColor = 14277081 }
    # Fonts outside Windows' own, with text after the table (Word marks where page 2 starts).
    Make 'nanumsquare' { param($d) $s = $d.Styles.Item(-1); $s.Font.Name = 'NanumSquare'; $s.Font.NameFarEast = '나눔스퀘어'; $s.ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $ko $i (1 + $i % 2) } 2 1 $null 'R900 다음 쪽 문단'
    Make 'candara' { param($d) $s = $d.Styles.Item(-1); $s.Font.Name = 'Candara'; $s.ParagraphFormat.SpaceAfter = 0 } `
        { param($r, $i) $r.Cells.Item(1).Range.Text = 'R{0:000}' -f $i; $r.Cells.Item(2).Range.Text = Text $en $i (1 + $i % 3) } 2 1 $null 'R900 Next page paragraph'
} finally {
    $w.Quit()
}
