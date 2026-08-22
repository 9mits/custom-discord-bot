"""Author and publish dev blog posts without touching git by hand.

    python devblog/blog.py new "Fiesta Forever"    scaffold a post + its media folder
    python devblog/blog.py check                   validate everything, in plain English
    python devblog/blog.py preview                 build and serve on localhost:8000
    python devblog/blog.py publish                 branch, PR, merge, deploy, verify

`publish` is the point of this file. Writing the markdown was never the hard
part; the repository requires a pull request and two green checks before
anything reaches main, and that is the bit worth automating.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from datetime import date
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
sys.path.insert(0, str(ROOT))

REPO_SLUG = "9mits/custom-discord-bot"
SITE_URL = "https://mysterioussmpx.blog"

# gh is often not on PATH on either dev machine.
GH_CANDIDATES = (
    "gh",
    str(Path.home() / ".local" / "bin" / "gh"),
    r"C:\Program Files\GitHub CLI\gh.exe",
)

TEMPLATE = '''---
title: {title}
tagline: One or two sentences that sell the update - this is what shows on the home page card and in link previews. Keep it on one line.
date: {today}
category: {category}
hero: hero.png
icon: icon.png
signoff: SEE YOU ON THE SERVER!
tags: {tag}
---

## {title}

Open with the hook, one beat per line.
Single newlines are real line breaks, so **write short lines**.

*An italic line makes a good closer for a section.*

![](screenshot-1.png)

## Featuring

### First Feature

What it is, in one line.
What the player does with it.
Why they should care.

![](screenshot-2.png)

### Second Feature

Same shape again.
Bold the **nouns that matter**.

## Notes

- Bullet lists work
- So do tables, code and quotes

*Closing line.*
'''


def fail(message: str) -> NoReturn:
    print("\n%s\n" % message, file=sys.stderr)
    raise SystemExit(1)


def run(args, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=str(REPO), text=True, capture_output=True, **kwargs)


def git(*args: str) -> str:
    done = run(["git", *args])
    if done.returncode:
        fail("git %s failed:\n%s" % (" ".join(args), (done.stderr or done.stdout).strip()))
    return done.stdout.strip()


def find_gh() -> str:
    for candidate in GH_CANDIDATES:
        path = shutil.which(candidate) or (candidate if Path(candidate).exists() else None)
        if path:
            return path
    fail(
        "Could not find the GitHub CLI.\n"
        "Install it, or run the git steps by hand:\n"
        "  git checkout -b post/<slug> && git add devblog && git commit && git push"
    )


def gh(*args: str, check: bool = True) -> str:
    done = run([find_gh(), *args])
    if check and done.returncode:
        fail("gh %s failed:\n%s" % (" ".join(args), (done.stderr or done.stdout).strip()))
    return done.stdout.strip()


# --- new ------------------------------------------------------------------

def next_slug() -> str:
    highest = 0
    for path in (ROOT / "posts").glob("*.md"):
        match = re.search(r"update-(\d+)$", path.stem)
        if match:
            highest = max(highest, int(match.group(1)))
    return "update-%d" % (highest + 1)


def cmd_new(args: argparse.Namespace) -> int:
    slug = args.slug or next_slug()
    if not re.match(r"^[a-z0-9][a-z0-9-]*$", slug):
        fail("The slug %r must be lowercase letters, digits and hyphens." % slug)

    post = ROOT / "posts" / ("%s-%s.md" % (date.today().isoformat(), slug))
    media = ROOT / "media" / slug
    if post.exists():
        fail("%s already exists. Pick another --slug." % post.name)

    post.write_text(
        TEMPLATE.format(
            title=args.title,
            today=date.today().isoformat(),
            category=args.category,
            tag=args.category.lower(),
        ),
        encoding="utf-8",
    )
    media.mkdir(parents=True, exist_ok=True)

    print("Created:")
    print("  %s" % post.relative_to(REPO))
    print("  %s/   (put the images here)" % media.relative_to(REPO))
    print("\nNext:")
    print("  1. Drop your screenshots into %s/" % media.relative_to(REPO))
    print("     hero.png and icon.png are referenced by the template.")
    print("  2. Write the post. Every image is referenced by bare filename.")
    print("  3. python devblog/blog.py preview")
    print("  4. python devblog/blog.py publish")
    print("\nThe post will be live at %s/%s" % (SITE_URL, slug))
    return 0


# --- check ----------------------------------------------------------------

def cmd_check(_args: argparse.Namespace) -> int:
    import build

    problems = []
    try:
        posts = build.load_posts(include_drafts=True)
        pages = build.load_pages()
    except build.PostError as exc:
        fail("A post or page is malformed:\n  %s" % exc)

    for post in posts:
        for label, name in (("hero", post.hero), ("icon", post.icon)):
            if name and not re.match(r"^(https?:|/)", name):
                if not (build.MEDIA_DIR / post.slug / name).exists():
                    problems.append(
                        "%s: %s is %r but media/%s/%s does not exist"
                        % (post.path.name, label, name, post.slug, name)
                    )
        for name in re.findall(r"!\[[^\]]*\]\(([^)]+)\)", post.body_md):
            if re.match(r"^(https?:|/)", name):
                continue
            if not (build.MEDIA_DIR / post.slug / name).exists():
                problems.append(
                    "%s: references %r but media/%s/%s does not exist"
                    % (post.path.name, name, post.slug, name)
                )
        if "Open with the hook" in post.body_md:
            problems.append("%s: still contains the template text" % post.path.name)

    if problems:
        print("Problems found:\n")
        for problem in problems:
            print("  - %s" % problem)
        print("\nFix these and run check again.")
        return 1

    drafts = [p.slug for p in posts if p.draft]
    print("OK. %d post(s), %d page(s)." % (len(posts), len(pages)))
    if drafts:
        print("Drafts (not published): %s" % ", ".join(drafts))
    return 0


# --- preview --------------------------------------------------------------

def cmd_preview(args: argparse.Namespace) -> int:
    if cmd_check(args):
        return 1
    print()
    return subprocess.call(
        [sys.executable, str(ROOT / "build.py"), "--serve", "--drafts",
         "--port", str(args.port)],
        cwd=str(REPO),
    )


# --- publish --------------------------------------------------------------

def cmd_publish(args: argparse.Namespace) -> int:
    if cmd_check(args):
        return 1

    staged = git("status", "--porcelain", "--", "devblog")
    if not staged:
        print("\nNothing to publish - devblog/ has no changes.")
        return 0

    print("\nPublishing these changes:\n")
    for line in staged.splitlines():
        print("  %s" % line)

    posts = sorted((ROOT / "posts").glob("*.md"))
    slug = re.sub(r"^\d{4}-\d{2}-\d{2}-", "", posts[-1].stem) if posts else "update"
    branch = args.branch or "post/%s" % slug
    message = args.message or "post: %s" % slug

    if git("rev-parse", "--abbrev-ref", "HEAD") == "main":
        git("checkout", "-b", branch)
        print("\nBranched to %s" % branch)
    else:
        branch = git("rev-parse", "--abbrev-ref", "HEAD")
        print("\nUsing the branch already checked out: %s" % branch)

    git("add", "devblog")
    git("commit", "-m", message)
    git("push", "-u", "origin", branch)
    print("Pushed.")

    existing = gh("pr", "list", "--repo", REPO_SLUG, "--head", branch,
                  "--json", "number", "--jq", ".[0].number", check=False)
    if existing.strip():
        number = existing.strip()
        print("Reusing pull request #%s" % number)
    else:
        url = gh("pr", "create", "--repo", REPO_SLUG, "--base", "main", "--head", branch,
                 "--title", message, "--body", "Published with devblog/blog.py.")
        number = url.rstrip("/").rsplit("/", 1)[-1]
        print("Opened pull request #%s" % number)

    print("\nWaiting for checks (a couple of minutes)...")
    checks = run([find_gh(), "pr", "checks", number, "--repo", REPO_SLUG,
                  "--watch", "--interval", "15"])
    print(checks.stdout.strip() or checks.stderr.strip())
    if checks.returncode:
        fail("Checks did not pass. The post is not published.\n"
             "Look at the run above, fix it, then run publish again.")

    gh("pr", "merge", number, "--repo", REPO_SLUG, "--squash", "--delete-branch")
    print("\nMerged.")
    git("checkout", "main")
    git("pull")
    print("Deploying... the site rebuilds automatically, usually within a minute.")
    print("\nLive shortly at %s/%s" % (SITE_URL, slug))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    new = sub.add_parser("new", help="scaffold a post and its media folder")
    new.add_argument("title", help='the post title, e.g. "Fiesta Forever"')
    new.add_argument("--category", default="Update", help="the pill above the title")
    new.add_argument("--slug", help="URL name; defaults to the next update-N")
    new.set_defaults(func=cmd_new)

    check = sub.add_parser("check", help="validate posts, pages and artwork")
    check.set_defaults(func=cmd_check)

    preview = sub.add_parser("preview", help="build and serve locally, drafts included")
    preview.add_argument("--port", type=int, default=8000)
    preview.set_defaults(func=cmd_preview)

    publish = sub.add_parser("publish", help="branch, PR, merge and deploy")
    publish.add_argument("-m", "--message", help="commit and pull request title")
    publish.add_argument("--branch", help="branch name; defaults to post/<slug>")
    publish.set_defaults(func=cmd_publish)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
