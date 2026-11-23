#!/usr/bin/env python3
"""يفتح عينات التقارير فعلياً ويتحقق من بنيتها وتطابق أرقامها.
- XLSX عبر openpyxl: RTL، صف عناوين مثبّت، فلترة، خلايا رقمية/تاريخية، مجاميع العملات.
- DOCX عبر python-docx: جداول RTL، تكرار رأس الجدول، تذييل الترقيم.
- PDF عبر pdfplumber: عدد الصفحات، الترويسة والترقيم في كل صفحة، النص قابل للاستخراج.
- LibreOffice (soffice --headless) يحوّل XLSX وDOCX إلى PDF ليُثبت أنها تُفتح في محرر متوافق.
الاستخدام: python3 scripts/verify_samples.py samples [out_dir]
"""
import sys, os, glob, subprocess, datetime, decimal, collections, json
import openpyxl, docx, pdfplumber

root = sys.argv[1] if len(sys.argv) > 1 else "samples"
out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/rasidi-verify"
os.makedirs(out, exist_ok=True)
results = []
ok_all = True

def check(name, cond, detail=""):
    global ok_all
    results.append(("PASS" if cond else "FAIL", name, detail))
    if not cond: ok_all = False

for x in sorted(glob.glob(os.path.join(root, "**", "*.xlsx"), recursive=True)):
    base = os.path.basename(x)
    wb = openpyxl.load_workbook(x)
    check(f"{base}: يُفتح في openpyxl", True, f"{len(wb.sheetnames)} أوراق: {', '.join(wb.sheetnames)}")
    check(f"{base}: كل الأوراق RTL", all(ws.sheet_view.rightToLeft for ws in wb.worksheets))
    for ws in wb.worksheets[1:]:
        if ws.max_row > 1:
            check(f"{base}/{ws.title}: صف العناوين مثبّت", ws.freeze_panes == "A2", str(ws.freeze_panes))
            check(f"{base}/{ws.title}: فلترة مفعّلة", bool(ws.auto_filter.ref), ws.auto_filter.ref or "")
            headers = [c.value for c in ws[1]]
            for ci, h in enumerate(headers):
                col = [r[ci].value for r in ws.iter_rows(min_row=2) if r[ci].value is not None]
                if h in ("المبلغ", "وارد", "صادر", "الرصيد", "بالدولار", "المحتسب") and col:
                    nums = [v for v in col if isinstance(v, (int, float))]
                    check(f"{base}/{ws.title}: «{h}» خلايا رقمية قابلة للفرز", len(nums) >= len(col) - 2, f"{len(nums)}/{len(col)}")
                if h in ("التاريخ", "تاريخ السعر", "الاستحقاق") and col:
                    dates = [v for v in col if isinstance(v, datetime.datetime)]
                    check(f"{base}/{ws.title}: «{h}» خلايا تاريخية", len(dates) == len(col), f"{len(dates)}/{len(col)}")
            # مجموع كل عملة في التفاصيل يطابق الملخّص (لتقرير الفترة)
            if "المبلغ" in headers and "العملة" in headers and "النوع" in headers and ws.title == "التفاصيل":
                ai, ci_, ti = headers.index("المبلغ"), headers.index("العملة"), headers.index("النوع")
                inc = collections.defaultdict(decimal.Decimal); exp = collections.defaultdict(decimal.Decimal)
                for r in ws.iter_rows(min_row=2, values_only=True):
                    if r[ai] is None or r[ci_] is None: continue
                    v = decimal.Decimal(str(r[ai]))
                    if r[ti] == "دخل": inc[r[ci_]] += v
                    if r[ti] in ("مصروف",): exp[r[ci_]] -= v
                summ = {row[0]: row[1] for row in wb.worksheets[0].iter_rows(values_only=True) if row and row[0]}
                for cur, v in inc.items():
                    sv = summ.get(f"الدخل ({cur})")
                    check(f"{base}: دخل {cur} في التفاصيل = الملخّص", sv is not None and abs(decimal.Decimal(str(sv)) - v) < decimal.Decimal("0.005"), f"{v} / {sv}")

for d in sorted(glob.glob(os.path.join(root, "**", "*.docx"), recursive=True)):
    base = os.path.basename(d)
    doc = docx.Document(d)
    xml = doc.element.xml
    check(f"{base}: يُفتح في python-docx", True, f"{len(doc.tables)} جداول، {len(doc.paragraphs)} فقرة")
    check(f"{base}: جداول RTL", "bidiVisual" in xml)
    if doc.tables and len(doc.tables[-1].rows) > 3:
        check(f"{base}: رأس الجدول يتكرر", "tblHeader" in xml)
    footer = doc.sections[0].footer._element.xml
    check(f"{base}: ترقيم الصفحات في التذييل", "NUMPAGES" in footer and "PAGE" in footer)

for p in sorted(glob.glob(os.path.join(root, "**", "*.pdf"), recursive=True)):
    base = os.path.basename(p)
    with pdfplumber.open(p) as pdf:
        n = len(pdf.pages)
        texts = [pg.extract_text() or "" for pg in pdf.pages]
        check(f"{base}: يُفتح في pdfplumber", n > 0, f"{n} صفحة، {pdf.pages[0].width:.0f}×{pdf.pages[0].height:.0f}")
        check(f"{base}: نص قابل للتحديد والاستخراج", all(len(t) > 20 for t in texts))
        check(f"{base}: ترقيم في كل صفحة", all(str(n) in t for t in texts))

# LibreOffice: تحويل XLSX وDOCX إلى PDF (فتح فعلي في محرر متوافق) ثم صورة الصفحة الأولى
lo_dir = os.path.join(out, "libreoffice"); os.makedirs(lo_dir, exist_ok=True)
import shutil
lo_files = [] if (os.environ.get("SKIP_LIBREOFFICE") or not shutil.which("soffice")) else None
for f in [] if lo_files is not None else sorted(glob.glob(os.path.join(root, "*.xlsx")) + glob.glob(os.path.join(root, "*.docx"))):
    r = subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", lo_dir, f], capture_output=True, text=True, timeout=180)
    pdf_out = os.path.join(lo_dir, os.path.splitext(os.path.basename(f))[0] + ".pdf")
    okc = os.path.exists(pdf_out)
    pages = 0
    if okc:
        with pdfplumber.open(pdf_out) as pdf: pages = len(pdf.pages)
        tag = "xlsx" if f.endswith(".xlsx") else "docx"
        subprocess.run(["pdftoppm", "-r", "60", "-png", "-f", "1", "-l", "1", pdf_out, os.path.join(out, f"lo-{tag}")])
    check(f"LibreOffice يفتح {os.path.basename(f)}", okc and pages > 0, f"{pages} صفحة بعد التحويل")
for p in sorted(glob.glob(os.path.join(root, "*.pdf"))):
    subprocess.run(["pdftoppm", "-r", "60", "-png", "-f", "1", "-l", "2", p, os.path.join(out, "pdf")])

passed = sum(1 for r in results if r[0] == "PASS")
lines = [f"| {s} | {n} | {d} |" for s, n, d in results]
report = "| النتيجة | الفحص | التفاصيل |\n|---|---|---|\n" + "\n".join(lines) + f"\n\n**{passed}/{len(results)} ناجح**\n"
open(os.path.join(out, "verify-samples.md"), "w").write(report)
print(report)
sys.exit(0 if ok_all else 1)
