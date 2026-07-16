"""Download a PPT template by style name to the templates/ directory.

Usage:
    python scripts/download_template.py 干净商务风
"""
import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
TEMPLATES_JSON = SCRIPTS_DIR / "templates.json"
TEMPLATES_DIR = Path("/tmp")

MAX_RETRIES = 5
MIN_VALID_SIZE = 10 * 1024  # valid pptx must be at least 10 KB


def download(url: str, dest: Path) -> None:
    """Download url to dest, raising on HTTP errors or truncated content."""
    if dest.exists():
        dest.unlink()
    with urllib.request.urlopen(url) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status}")
        data = resp.read()
    if len(data) < MIN_VALID_SIZE:
        raise RuntimeError(f"Response too small ({len(data)} bytes) — likely an error page")
    dest.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description="Download a PPT template by style name.")
    parser.add_argument("name", nargs="+", help="Style name, e.g.: 干净商务风")
    args = parser.parse_args()
    name = " ".join(args.name)

    with open(TEMPLATES_JSON, encoding="utf-8") as f:
        templates = json.load(f)

    if name not in templates:
        print(f"Error: '{name}' not found.\nAvailable styles:\n  " + "\n  ".join(templates.keys()), file=sys.stderr)
        sys.exit(1)

    url = templates[name]
    dest = TEMPLATES_DIR / f"{name}.pptx"
    TEMPLATES_DIR.mkdir(exist_ok=True)

    for attempt in range(1, MAX_RETRIES + 1):
        print(f"Downloading '{name}' (attempt {attempt}/{MAX_RETRIES}) ...")
        try:
            download(url, dest)
            print(f"Saved to: {dest}")
            return
        except Exception as e:
            print(f"  Failed: {e}", file=sys.stderr)
            if dest.exists():
                dest.unlink()
            if attempt < MAX_RETRIES:
                time.sleep(2)

    print(f"Error: failed to download '{name}' after {MAX_RETRIES} attempts.", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
