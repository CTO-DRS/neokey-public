#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ci/make_feedback_asset.py — W57 batch-2 lever pull (law 5, the W54 pattern):
# the Feedback survey + join wording moves OUT of the dex into
# assets/feedback_copy.txt, extracted byte-exactly from this file's git
# history (HEAD before the move) — the ONE derivation. The parse is PROVEN
# by reconstruction: the tool re-substitutes the two code constants and
# byte-compares against the captured runtime reference
# (scripts/feedback-reference.txt — printed by the unmodified Feedback.class
# before the move). --check byte-compares a fresh derivation vs the
# committed asset (the Writer/Theme W54 discipline).
#
#   python3 ci/make_feedback_asset.py            # derive + emit + verify
#   python3 ci/make_feedback_asset.py --check    # byte-compare vs committed
import re, subprocess, sys, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "src", "com", "hark", "neokey", "Feedback.java")
OUT = os.path.join(ROOT, "assets", "feedback_copy.txt")
REF = "/home/z/my-project/scripts/feedback-reference.txt"
# the pre-move truth (the W57 main commit's parent) — pinned so the
# derivation survives the move itself landing in HEAD
PRE_MOVE = "ec5adf7"

EMAIL = "cto.drs@gmail.com"       # Feedback.SUPPORT_EMAIL — the one code constant
SUBJECT = "انضمام بيتا"           # Feedback.JOIN_SUBJECT

def parse_head():
    src = subprocess.run(["git", "-C", ROOT, "show", PRE_MOVE + ":src/com/hark/neokey/Feedback.java"],
                         capture_output=True, text=True, check=True).stdout
    m = re.search(r"public static String\[\] surveyQuestions\(\) \{\s*return new String\[\]\{(.*?)\};",
                  src, re.S)
    assert m, "survey block not found"
    survey = re.findall(r'"([^"]*)"', m.group(1))
    assert len(survey) == 5, "expected 5 survey questions, got %d" % len(survey)
    m = re.search(r"public static String joinSteps\(\) \{\s*return (.*?);\s*\}", src, re.S)
    assert m, "join block not found"
    body = m.group(1)
    # walk the concatenation: quoted literals + the two constant references
    out, pos = [], 0
    for tok in re.finditer(r'"((?:[^"\\]|\\.)*)"|(SUPPORT_EMAIL|JOIN_SUBJECT)', body):
        if tok.group(1) is not None:
            out.append(tok.group(1).replace("\\n", "\n"))
        else:
            out.append("%E" if tok.group(2) == "SUPPORT_EMAIL" else "%S")
    join = "".join(out)
    return survey, join

def verify(survey, join):
    """The parse-proof: substituted reconstruction == the captured runtime truth."""
    ref = open(REF, encoding="utf-8").read()
    want_q = [l[2:] for l in ref.split("\n") if l.startswith("Q|")]
    want_j = [l[2:] for l in ref.split("\n") if l.startswith("J|")][0]
    assert survey == want_q, "survey parse drift"
    # the reference carries the probe's <NL> markers where the real text has \n
    assert join.replace("%E", EMAIL).replace("%S", SUBJECT).replace("\n", "<NL>") == want_j, \
        "join parse drift"

def build(survey, join):
    lines = [
        "# assets/feedback_copy.txt — W54 batch-2 lever (law 6, the W57 same-commit",
        "# repayment): the survey + join wording extracted byte-exactly from",
        "# src/Feedback.java history by ci/make_feedback_asset.py (the ONE derivation;",
        "# --check byte-compares a fresh derivation). Strict init in Feedback.java",
        "# refuses any drift — never a half-loaded bank. %E/%S fold to the code",
        "# constants at init (the one truth for the support address).",
    ]
    for i, q in enumerate(survey):
        lines.append("F\t%d\t%s" % (i, q))
    for i, row in enumerate(join.split("\n")):
        lines.append("J\t%d\t%s" % (i, row))
    return "\n".join(lines) + "\n"

def main():
    survey, join = parse_head()
    if "--check" not in sys.argv:
        verify(survey, join)
        print("FEEDBACK_PARSE_PROVEN survey=5 join_lines=%d" % len(join.split("\n")))
    text = build(survey, join)
    if "--check" in sys.argv:
        committed = open(OUT, encoding="utf-8").read()
        if text != committed:
            print("FEEDBACK_CHECK_FAILED non_deterministic (derived != committed)")
            sys.exit(1)
        print("FEEDBACK_CHECK_OK bytes=%d" % len(text.encode("utf-8")))
        return
    open(OUT, "w", encoding="utf-8", newline="").write(text)
    print("FEEDBACK_WRITTEN bytes=%d survey=5 join_lines=%d"
          % (len(text.encode("utf-8")), len(join.split("\n"))))

main()
