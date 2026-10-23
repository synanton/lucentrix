#!/usr/bin/env python3
"""Deterministic margin-stress corpus for Lucentrix #410/#16.

Generates N one-page PDFs, each containing the query term. The first
TOMBSTONES files are the deletion set; the remaining LIVE files stay. With
fewer than `limit` live documents among the top `limit + margin`, the search
backend must refetch to fill the requested limit.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

SEED = "lucentrix-margin-stress-v1"
QUERY = "retention"
DEFAULT_DOCUMENTS = 111
DEFAULT_TOMBSTONES = 101


def pdf_bytes(index: int) -> bytes:
    body = f"Stress document {index:03d} {SEED} {QUERY} policy."
    stream = f"BT /F1 12 Tf 72 720 Td ({body}) Tj ET".encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for offset in offsets[1:]:
        out += f"{offset:010d} 00000 n \n".encode()
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref}\n%%EOF\n"
    ).encode()
    return bytes(out)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--documents", type=int, default=DEFAULT_DOCUMENTS)
    parser.add_argument("--tombstones", type=int, default=DEFAULT_TOMBSTONES)
    args = parser.parse_args()

    if args.tombstones >= args.documents:
        raise SystemExit("tombstones must be fewer than documents")
    if args.documents - args.tombstones > 10:
        raise SystemExit("live documents must be fewer than the rerun limit+margin - tombstones")

    args.output.mkdir(parents=True, exist_ok=True)
    paths = []
    for index in range(args.documents):
        path = args.output / f"doc-{index:03d}.pdf"
        path.write_bytes(pdf_bytes(index))
        paths.append(path)

    manifest = "\n".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}"
        for path in sorted(paths, key=lambda p: p.name)
    ) + "\n"
    manifest_path = args.output / "manifest.sha256"
    manifest_path.write_text(manifest)
    manifest_hash = hashlib.sha256(manifest.encode()).hexdigest()

    metadata = {
        "seed": SEED,
        "query": QUERY,
        "documents": args.documents,
        "tombstones": args.tombstones,
        "live": args.documents - args.tombstones,
        "tombstone_files": [f"doc-{i:03d}.pdf" for i in range(args.tombstones)],
        "live_files": [f"doc-{i:03d}.pdf" for i in range(args.tombstones, args.documents)],
        "manifest_sha256": manifest_hash,
        "guard_trigger": (
            "limit=10, margin=100: top 110 contains 101 tombstones + at most 9 live; "
            "refetch to 210 returns all 10 live documents"
        ),
    }
    (args.output / "stress-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(manifest_hash)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
