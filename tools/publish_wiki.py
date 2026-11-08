#!/usr/bin/env python3
"""Publish docs/wiki to this repository's GitHub Wiki.

docs/wiki is the source of truth. Its pages link to each other as `Page.md` and to pictures as
`images/name.png`, so they also read correctly in the main repository. This script rewrites those links
to wiki URLs, mirrors the result into a fresh clone of the `.wiki.git` repository, and pushes one commit.

GitHub creates the wiki repository only when its first page is saved on the website, so save any page
once before the first publish. Edits made on the website are kept safe: publishing stops if the wiki's
latest commit did not come from this script, until those edits are copied into docs/wiki and --force is
given.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs" / "wiki"
COMMIT_PREFIX = "Publish wiki from docs/wiki"
ORIGIN_PATTERN = re.compile(r"github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/]+?)(?:\.git)?/?$")
PAGE_LINK = re.compile(r"\]\((?P<page>[A-Za-z0-9_-]+)\.md(?P<anchor>#[^)\s]*)?\)")
IMAGE_LINK = re.compile(r"(?P<lead>\]\(|src=\")images/(?P<file>[^)\"\s]+)")
LOCAL_LINK = re.compile(r"\]\((?!https?://|#|mailto:)(?P<target>[^)\s]+)\)|src=\"(?!https?://)(?P<src>[^\"]+)\"")


def git(*args: str, cwd: Path = ROOT, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def github_repository() -> tuple[str, str, str]:
    origin = git("remote", "get-url", "origin")
    match = ORIGIN_PATTERN.search(origin)
    if not match:
        raise SystemExit(f"The origin remote is not a GitHub repository: {origin}")
    wiki_remote = re.sub(r"(\.git)?/?$", ".wiki.git", origin, count=1)
    return match["owner"], match["repo"], wiki_remote


def check_footer_version() -> None:
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^mod_version=([^+\s]+)", properties, re.MULTILINE)
    if not match:
        raise SystemExit("gradle.properties has no mod_version")
    footer = SOURCE / "_Footer.md"
    if footer.is_file() and match[1] not in footer.read_text(encoding="utf-8"):
        raise SystemExit(f"docs/wiki/_Footer.md does not mention the current version {match[1]}; update it first")


def convert(text: str, page: str, pages: set[str], images: set[str], owner: str, repo: str) -> str:
    def page_url(match: re.Match[str]) -> str:
        if match["page"] not in pages:
            raise SystemExit(f"{page}.md links to a missing page: {match['page']}.md")
        return f"](https://github.com/{owner}/{repo}/wiki/{match['page']}{match['anchor'] or ''})"

    def image_url(match: re.Match[str]) -> str:
        if match["file"] not in images:
            raise SystemExit(f"{page}.md uses a missing image: images/{match['file']}")
        return f"{match['lead']}https://raw.githubusercontent.com/wiki/{owner}/{repo}/images/{match['file']}"

    converted = IMAGE_LINK.sub(image_url, PAGE_LINK.sub(page_url, text))
    leftover = LOCAL_LINK.search(converted)
    if leftover:
        raise SystemExit(f"{page}.md has a link the wiki cannot resolve: {leftover['target'] or leftover['src']}")
    return converted


def build(destination: Path, owner: str, repo: str) -> None:
    pages = {path.stem for path in SOURCE.glob("*.md")}
    if "Home" not in pages:
        raise SystemExit("docs/wiki has no Home.md, which GitHub uses as the wiki's front page")
    image_dir = SOURCE / "images"
    images = {path.name for path in image_dir.iterdir() if path.is_file()} if image_dir.is_dir() else set()
    for path in sorted(SOURCE.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        (destination / path.name).write_text(convert(text, path.stem, pages, images, owner, repo), encoding="utf-8")
    if images:
        (destination / "images").mkdir()
        for name in sorted(images):
            shutil.copy2(image_dir / name, destination / "images" / name)


def source_description() -> str:
    revision = git("rev-parse", "--short", "HEAD")
    dirty = git("status", "--porcelain", "--", str(SOURCE.relative_to(ROOT)))
    return f"{revision} with uncommitted changes" if dirty else revision


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path,
                        help="only write the converted pages to this new directory; nothing is pushed")
    parser.add_argument("--force", action="store_true",
                        help="replace wiki changes that were not published from docs/wiki")
    args = parser.parse_args()

    check_footer_version()
    owner, repo, wiki_remote = github_repository()
    if args.output:
        if args.output.exists():
            raise SystemExit(f"{args.output} already exists; choose a new directory")
        args.output.mkdir(parents=True)
        build(args.output, owner, repo)
        print(f"Converted pages written to {args.output}")
        return

    with tempfile.TemporaryDirectory(prefix="wiki-publish-") as temporary:
        clone = Path(temporary) / "wiki"
        result = subprocess.run(["git", "clone", "--quiet", wiki_remote, str(clone)], capture_output=True, text=True)
        if result.returncode != 0:
            raise SystemExit(
                f"Could not clone {wiki_remote}:\n{result.stderr.strip()}\n\n"
                f"GitHub creates the wiki repository when its first page is saved. Open "
                f"https://github.com/{owner}/{repo}/wiki, choose 'Create the first page', save it, and run this again.")

        latest = git("log", "-1", "--format=%s", cwd=clone, check=False)
        if latest and not latest.startswith(COMMIT_PREFIX) and not args.force:
            raise SystemExit(
                f"The wiki's latest commit was not published from docs/wiki: '{latest}'.\n"
                "Copy any edits made on GitHub into docs/wiki, then rerun with --force to replace the wiki.")

        for entry in clone.iterdir():
            if entry.name == ".git":
                continue
            if entry.is_dir():
                shutil.rmtree(entry)
            else:
                entry.unlink()
        build(clone, owner, repo)

        git("add", "--all", cwd=clone)
        if not git("status", "--porcelain", cwd=clone):
            print(f"The wiki is already up to date: https://github.com/{owner}/{repo}/wiki")
            return
        print(git("diff", "--cached", "--stat", cwd=clone))
        git("commit", "--quiet", "-m", f"{COMMIT_PREFIX} ({source_description()})", cwd=clone)
        git("push", "--quiet", "origin", "HEAD", cwd=clone)
        print(f"Published https://github.com/{owner}/{repo}/wiki")


if __name__ == "__main__":
    sys.exit(main())
