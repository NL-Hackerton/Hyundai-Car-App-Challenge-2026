# Export a DOCX to PDF with Word (COM). Usage: powershell -File pt/tools/script_pdf.ps1 <in.docx> <out.pdf>
param($in, $out)
$w = New-Object -ComObject Word.Application
try { $d = $w.Documents.Open($in, $false, $true); $d.ExportAsFixedFormat($out, 17); "pages: " + $d.ComputeStatistics(2); $d.Close($false) } finally { $w.Quit() }
