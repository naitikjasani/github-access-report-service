#!/usr/bin/env python3
"""Prints a Markdown line-coverage table per package from JaCoCo's CSV report (for CI summaries)."""
import collections
import csv
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "target/site/jacoco/jacoco.csv"
try:
    rows = list(csv.DictReader(open(path)))
except FileNotFoundError:
    print("No coverage report found (tests did not run).")
    sys.exit(0)

lines = collections.defaultdict(lambda: [0, 0])
for row in rows:
    lines[row["PACKAGE"]][0] += int(row["LINE_COVERED"])
    lines[row["PACKAGE"]][1] += int(row["LINE_MISSED"])

covered = sum(c for c, _ in lines.values())
total = sum(c + m for c, m in lines.values())
print("### Line coverage\n")
print("| Package | Coverage | Lines |")
print("|---|---:|---:|")
for package, (c, m) in sorted(lines.items()):
    print(f"| `{package}` | {100 * c / (c + m):.1f}% | {c}/{c + m} |")
print(f"| **Total** | **{100 * covered / total:.1f}%** | {covered}/{total} |")
