# -*- coding: utf-8 -*-
"""Markdown (subsetul folosit de manual) -> PDF, cu reportlab."""
import re, sys, os
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, PageTemplate, Frame, Paragraph, Spacer,
                                Table, TableStyle, KeepTogether, PageBreak, CondPageBreak)

SUP = "/System/Library/Fonts/Supplemental/"
FONTS = {
    "Body":      "Times New Roman.ttf",
    "Body-B":    "Times New Roman Bold.ttf",
    "Body-I":    "Times New Roman Italic.ttf",
    "Body-BI":   "Times New Roman Bold Italic.ttf",
    "Head":      "Verdana Bold.ttf",
    "HeadR":     "Verdana.ttf",
    "Code":      "Courier New.ttf",
    "Code-B":    "Courier New Bold.ttf",
    "Fallback":  "Arial Unicode.ttf",
}
for name, f in FONTS.items():
    pdfmetrics.registerFont(TTFont(name, SUP + f))
pdfmetrics.registerFontFamily("Body", normal="Body", bold="Body-B", italic="Body-I", boldItalic="Body-BI")
pdfmetrics.registerFontFamily("Code", normal="Code", bold="Code-B", italic="Code", boldItalic="Code-B")

# caractere pe care Georgia/Verdana nu le au -> se scriu cu fontul de rezerva
def _cmap(path):
    from fontTools.ttLib import TTFont as FT
    t = FT(path); s = set()
    for tbl in t["cmap"].tables: s |= set(tbl.cmap.keys())
    return s
BODY_CMAP = _cmap(SUP + FONTS["Body"]) & _cmap(SUP + FONTS["Head"])

INK   = colors.HexColor("#1a1a1a")
MUTED = colors.HexColor("#5a5a5a")
RULE  = colors.HexColor("#c9c4bb")
ACCENT= colors.HexColor("#7a3b2e")
CODEBG= colors.HexColor("#f4f2ee")
THBG  = colors.HexColor("#e8e4dc")

S_BODY = ParagraphStyle("body", fontName="Body", fontSize=10.4, leading=14.8, textColor=INK,
                        spaceAfter=6.5, alignment=TA_LEFT)
S_H1 = ParagraphStyle("h1", fontName="Head", fontSize=17, leading=21, textColor=ACCENT,
                      spaceBefore=0, spaceAfter=13)
S_H2 = ParagraphStyle("h2", fontName="Head", fontSize=12.6, leading=16.5, textColor=INK,
                      spaceBefore=15, spaceAfter=7)
S_H3 = ParagraphStyle("h3", fontName="Head", fontSize=9.8, leading=13.5, textColor=ACCENT,
                      spaceBefore=11, spaceAfter=5)
S_LI = ParagraphStyle("li", parent=S_BODY, leftIndent=13, bulletIndent=2, spaceAfter=3.4)
S_CODE = ParagraphStyle("code", fontName="Code", fontSize=7.6, leading=10.2, textColor=INK)
S_TD = ParagraphStyle("td", fontName="Body", fontSize=9.1, leading=12.2, textColor=INK)
S_TH = ParagraphStyle("th", fontName="Head", fontSize=7.8, leading=11, textColor=INK)
S_QUOTE = ParagraphStyle("quote", parent=S_BODY, leftIndent=14, rightIndent=8,
                         fontName="Body-I", textColor=colors.HexColor("#3a3a3a"))
S_TITLE = ParagraphStyle("title", fontName="Head", fontSize=23, leading=28, textColor=ACCENT, spaceAfter=4)
S_SUB   = ParagraphStyle("sub", fontName="HeadR", fontSize=9.6, leading=15, textColor=MUTED, spaceAfter=3)

def fallback(s):
    """Inconjoara cu <font name=Fallback> caracterele absente din fontul de text."""
    out, buf, bad = [], [], []
    for ch in s:
        if ord(ch) < 128 or ord(ch) in BODY_CMAP:
            if bad: out.append('<font name="Fallback">%s</font>' % "".join(bad)); bad = []
            out.append(ch)
        else:
            bad.append(ch)
    if bad: out.append('<font name="Fallback">%s</font>' % "".join(bad))
    return "".join(out)

def inline(text, code_size=8.3):
    t = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = re.sub(r"`([^`]+)`", lambda m: '<font face="Code" size="%s">%s</font>' % (code_size, m.group(1)), t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<i>\1</i>", t)
    return fallback(t)

def parse(md):
    lines = md.split("\n"); blocks = []; i = 0
    while i < len(lines):
        ln = lines[i]
        if ln.startswith("```"):
            j = i + 1; buf = []
            while j < len(lines) and not lines[j].startswith("```"):
                buf.append(lines[j]); j += 1
            blocks.append(("code", buf)); i = j + 1; continue
        if ln.strip() == "---":
            blocks.append(("hr", None)); i += 1; continue
        if not ln.strip():
            i += 1; continue
        m = re.match(r"^(#{1,3}) (.*)$", ln)
        if m:
            blocks.append(("h%d" % len(m.group(1)), m.group(2))); i += 1; continue
        if ln.lstrip().startswith("|"):
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append(lines[i].strip()); i += 1
            cells = [[c.strip() for c in r.strip("|").split("|")] for r in rows]
            cells = [c for c in cells if not all(re.fullmatch(r":?-{2,}:?", x or "-") for x in c)]
            blocks.append(("table", cells)); continue
        if ln.startswith("> "):
            buf = []
            while i < len(lines) and lines[i].startswith("> "):
                buf.append(lines[i][2:]); i += 1
            blocks.append(("quote", buf)); continue
        m = re.match(r"^(\s*)[-*] (.*)$", ln)
        if m:
            items = []
            while i < len(lines):
                mm = re.match(r"^(\s*)[-*] (.*)$", lines[i])
                if not mm: break
                txt = mm.group(2); i += 1
                while i < len(lines) and lines[i].startswith("  ") and not re.match(r"^\s*[-*] ", lines[i]) and lines[i].strip():
                    txt += " " + lines[i].strip(); i += 1
                items.append((len(mm.group(1)) // 2, txt))
            blocks.append(("ul", items)); continue
        m = re.match(r"^(\d+)\. (.*)$", ln)
        if m:
            items = []
            while i < len(lines):
                mm = re.match(r"^(\d+)\. (.*)$", lines[i])
                if not mm: break
                txt = mm.group(2); num = mm.group(1); i += 1
                while i < len(lines) and lines[i].startswith("   ") and lines[i].strip():
                    txt += " " + lines[i].strip(); i += 1
                items.append((num, txt))
            blocks.append(("ol", items)); continue
        buf = []
        while i < len(lines) and lines[i].strip() and not re.match(r"^(#{1,3} |```|\||> |\s*[-*] |\d+\. )", lines[i]) and lines[i].strip() != "---":
            buf.append(lines[i].strip()); i += 1
        blocks.append(("p", " ".join(buf)))
    return blocks

AVAIL = A4[0] - 4 * cm

def code_flowable(buf):
    longest = max((len(x) for x in buf), default=0)
    size = 7.6
    while longest * size * 0.601 > AVAIL - 16 and size > 5.2:
        size -= 0.15
    st = ParagraphStyle("c%d" % int(size * 100), parent=S_CODE, fontSize=size, leading=size * 1.34)
    body = "<br/>".join(fallback(x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                  .replace(" ", "&nbsp;")) or "&nbsp;" for x in buf)
    p = Paragraph(body, st)
    t = Table([[p]], colWidths=[AVAIL])
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), CODEBG),
                           ("BOX", (0, 0), (-1, -1), 0.4, RULE),
                           ("LEFTPADDING", (0, 0), (-1, -1), 8), ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                           ("TOPPADDING", (0, 0), (-1, -1), 6), ("BOTTOMPADDING", (0, 0), (-1, -1), 6)]))
    return t

def _seg_width(text, code_size=7.6, body_size=9.1):
    """Latimea reala a unui text markdown inline, in puncte."""
    w = 0.0
    for i, part in enumerate(re.split(r"`", text)):
        clean = re.sub(r"\*\*|\*", "", part)
        if i % 2:
            w += pdfmetrics.stringWidth(clean, "Code", code_size)
        else:
            w += pdfmetrics.stringWidth(clean, "Body-B", body_size)
    return w

def _token_width(text):
    """Cel mai lat token indivizibil din celula."""
    best = 0.0
    for tok in re.split(r"\s+", text):
        if tok: best = max(best, _seg_width(tok))
    return best

def table_flowable(cells):
    ncol = max(len(r) for r in cells)
    cells = [r + [""] * (ncol - len(r)) for r in cells]
    PAD = 11.0
    floors, desired = [], []
    for c in range(ncol):
        floors.append(min(AVAIL * 0.42, max(_token_width(r[c]) for r in cells) + PAD))
        desired.append(max(_seg_width(r[c]) for r in cells) + PAD)
    desired = [max(d, f) for d, f in zip(desired, floors)]
    slack = AVAIL - sum(floors)
    if slack <= 0:
        scale = AVAIL / sum(floors)
        widths = [f * scale for f in floors]
    else:
        extra = [d - f for d, f in zip(desired, floors)]
        tot = sum(extra)
        if tot <= 0:
            widths = [f + slack / ncol for f in floors]
        else:
            share = min(1.0, slack / tot)
            widths = [f + e * share for f, e in zip(floors, extra)]
            rest = AVAIL - sum(widths)
            if rest > 0.5:
                widths = [w + rest * (d / sum(desired)) for w, d in zip(widths, desired)]
    data = [[Paragraph(inline(c, code_size=7.6), S_TH if r == 0 else S_TD) for c in row]
            for r, row in enumerate(cells)]
    t = Table(data, colWidths=widths, repeatRows=1)
    style = [("BACKGROUND", (0, 0), (-1, 0), THBG),
             ("LINEBELOW", (0, 0), (-1, 0), 0.6, RULE),
             ("GRID", (0, 0), (-1, -1), 0.25, RULE),
             ("VALIGN", (0, 0), (-1, -1), "TOP"),
             ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5),
             ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4)]
    for r in range(1, len(data)):
        if r % 2 == 0: style.append(("BACKGROUND", (0, r), (-1, r), colors.HexColor("#faf9f7")))
    t.setStyle(TableStyle(style))
    return t

class Rule(Spacer):
    def __init__(self): Spacer.__init__(self, AVAIL, 11)
    def draw(self):
        self.canv.setStrokeColor(RULE); self.canv.setLineWidth(0.5)
        self.canv.line(0, 5, AVAIL, 5)

def build(md_path, pdf_path, title_main="Programare dinamică",
          counts_line="17 puncte de lucru &middot; 51 de explicații principale &middot; 17 întrebări orientative &middot; 17 răspunsuri"):
    md = open(md_path, encoding="utf-8").read()
    blocks = parse(md)
    story = []
    first_h1_seen = False
    # ---- pagina de titlu ----
    story += [Spacer(1, 3.2 * cm),
              Paragraph(fallback(title_main), S_TITLE),
              Paragraph(fallback("Manual de explicație triplă"), ParagraphStyle(
                  "t2", parent=S_TITLE, fontSize=14.5, leading=19, textColor=INK, spaceAfter=16)),
              Rule(), Spacer(1, 0.5 * cm)]
    for line in ["footballmanager-backend &middot; branch master &middot; commit 8488435",
                 counts_line,
                 "Generat la 22 august 2026"]:
        story.append(Paragraph(fallback(line), S_SUB))
    story.append(PageBreak())

    for kind, payload in blocks:
        if kind == "h1":
            if not first_h1_seen:
                first_h1_seen = True   # titlul din markdown e inlocuit de pagina de titlu
                continue
            story.append(PageBreak())
            story.append(Paragraph(inline(payload), S_H1)); continue
        if kind == "h2":
            story.append(CondPageBreak(4.6 * cm))
            story.append(Paragraph(inline(payload), S_H2)); continue
        if kind == "h3":
            story.append(CondPageBreak(3.2 * cm))
            story.append(Paragraph(inline(payload), S_H3)); continue
        if kind == "p":
            story.append(Paragraph(inline(payload), S_BODY)); continue
        if kind == "ul":
            for lvl, txt in payload:
                st = ParagraphStyle("li%d" % lvl, parent=S_LI, leftIndent=13 + 13 * lvl, bulletIndent=2 + 13 * lvl)
                story.append(Paragraph(inline(txt), st, bulletText="•"))
            story.append(Spacer(1, 4)); continue
        if kind == "ol":
            for num, txt in payload:
                story.append(Paragraph(inline(txt), S_LI, bulletText="%s." % num))
            story.append(Spacer(1, 4)); continue
        if kind == "code":
            story.append(code_flowable(payload)); story.append(Spacer(1, 7)); continue
        if kind == "table":
            story.append(CondPageBreak(3.6 * cm))
            story.append(table_flowable(payload)); story.append(Spacer(1, 8)); continue
        if kind == "quote":
            story.append(Paragraph("<br/>".join(inline(x) for x in payload), S_QUOTE)); story.append(Spacer(1, 4)); continue
        if kind == "hr":
            story.append(Rule()); continue

    doc = BaseDocTemplate(pdf_path, pagesize=A4,
                          leftMargin=2 * cm, rightMargin=2 * cm, topMargin=2.1 * cm, bottomMargin=1.9 * cm,
                          title=title_main + " - manual de explicatie tripla",
                          author="explicatie-tripla-pdf")
    frame = Frame(doc.leftMargin, doc.bottomMargin, AVAIL, A4[1] - 4 * cm, id="F")

    def decorate(canv, d):
        canv.saveState()
        if canv.getPageNumber() > 1:
            canv.setFont("HeadR", 7.2); canv.setFillColor(MUTED)
            canv.drawString(2 * cm, A4[1] - 1.42 * cm, title_main + " — manual de explicație triplă")
            canv.drawRightString(A4[0] - 2 * cm, A4[1] - 1.42 * cm, "footballmanager-backend")
            canv.setStrokeColor(RULE); canv.setLineWidth(0.4)
            canv.line(2 * cm, A4[1] - 1.62 * cm, A4[0] - 2 * cm, A4[1] - 1.62 * cm)
            canv.line(2 * cm, 1.5 * cm, A4[0] - 2 * cm, 1.5 * cm)
            canv.setFont("HeadR", 7.6)
            canv.drawCentredString(A4[0] / 2, 1.12 * cm, str(canv.getPageNumber()))
        canv.restoreState()

    doc.addPageTemplates([PageTemplate(id="all", frames=[frame], onPage=decorate)])
    doc.build(story)
    print("scris:", pdf_path)

if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2], *sys.argv[3:])
