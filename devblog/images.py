"""Responsive, compressed copies of the site's images, made once the pages are built.

Posts are written with the screenshots exactly as they came off a game client, and
those are full-resolution PNGs: a single dragon screenshot was 9.7 MB, and the Update 7
page asked a phone to download 50 MB of images to read it. Nothing about a PNG is needed
to show a photo of a game world, and nobody's screen is 2,940 pixels wide.

This pass leaves the source media alone and rewrites the finished HTML instead:

- every large raster gets WebP copies at a few widths beside the original, and its
  ``<img>`` gains ``srcset``/``sizes`` so a browser downloads only the width it draws;
- every image gets ``width``/``height``, so the page does not jump as pictures arrive;
- images below the first screen load lazily, and the first one is fetched early;
- share previews point at a 1200-pixel JPEG rather than a multi-megabyte PNG.

Originals are still published at their old URLs. The Discord update notice links to
``/media/<slug>/<file>`` directly, and so may anything already shared.

Small PNGs are left as they are: they are icons and pixel art, already tiny, and lossy
compression blurs the hard edges that make pixel art read.
"""

from __future__ import annotations

import hashlib
import os
import re
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

try:  # Pillow is a build dependency, but a missing one must never stop a build.
    from PIL import Image
except ImportError:  # pragma: no cover - exercised only without Pillow installed
    Image = None

#: Widths a browser can choose between. A variant is never wider than its source.
WIDTHS = (480, 960, 1600)
#: Below this a PNG is an icon or pixel art, and is published untouched.
SMALL_BYTES = 80_000
WEBP_QUALITY = 80
SHARE_WIDTH = 1200
RASTER = re.compile(r"\.(png|jpe?g)$", re.IGNORECASE)
IMG_TAG = re.compile(r"<img\b[^>]*>", re.IGNORECASE)
ATTR = r'\s%s="([^"]*)"'

#: How wide each kind of image is drawn, for ``sizes``. Classes come from theme.py.
SIZES = {
    "blur": "(max-width: 640px) 100vw, 26rem",
    "icon": "(max-width: 640px) 50vw, 14rem",
    "wordmark": "(max-width: 640px) 80vw, 30rem",
    "mark": "(max-width: 640px) 60vw, 20rem",
    "community-mascot": "(max-width: 640px) 60vw, 20rem",
}
#: Drawn in the page header or first screen: fetched straight away, never lazily.
EAGER_CLASSES = {"wordmark", "mark"}


@dataclass
class Report:
    images: int = 0
    variants: int = 0
    tags: int = 0
    bytes_before: int = 0
    bytes_after: int = 0
    skipped: List[str] = field(default_factory=list)

    def summary(self) -> str:
        if not self.images:
            return "images: nothing to optimise"
        saved = 100 * (1 - self.bytes_after / self.bytes_before) if self.bytes_before else 0
        return ("images: %d optimised into %d WebP copies, %d tags rewritten; "
                "largest copy per image %.1f MB instead of %.1f MB (%.0f%% smaller)"
                % (self.images, self.variants, self.tags, self.bytes_after / 1e6,
                   self.bytes_before / 1e6, saved))


def _attr(tag: str, name: str) -> Optional[str]:
    match = re.search(ATTR % re.escape(name), tag)
    return match.group(1) if match else None


def _set_attr(tag: str, name: str, value: str) -> str:
    if _attr(tag, name) is not None:
        return re.sub(ATTR % re.escape(name), ' %s="%s"' % (name, value), tag, count=1)
    return re.sub(r"\s*/?>$", ' %s="%s">' % (name, value), tag, count=1)


def _variant_name(source: Path, width: int) -> str:
    return "%s.w%d.webp" % (source.stem, width)


def _encode(job: Tuple[str, str, int, Optional[str]]) -> Tuple[str, bool]:
    """Writes one WebP (or share JPEG) copy. Runs in a worker process."""
    source, target, width, cache = job
    target_path = Path(target)
    if cache and Path(cache).exists():
        target_path.write_bytes(Path(cache).read_bytes())
        return target, True
    with Image.open(source) as opened:
        share = target.endswith(".jpg")
        image = opened.convert("RGB") if share or opened.mode not in ("RGB", "RGBA") else opened.copy()
        if image.width > width:
            height = round(image.height * width / image.width)
            image = image.resize((width, height), Image.LANCZOS)
        if share:
            image.save(target_path, "JPEG", quality=85, optimize=True, progressive=True)
        else:
            image.save(target_path, "WEBP", quality=WEBP_QUALITY, method=4)
    if cache:
        Path(cache).parent.mkdir(parents=True, exist_ok=True)
        Path(cache).write_bytes(target_path.read_bytes())
    return target, False


def _probe(path: Path) -> Optional[Tuple[int, int]]:
    try:
        with Image.open(path) as image:
            return image.size
    except Exception:  # a stub or a damaged file is simply not optimised
        return None


def optimise(dist: Path, site_url: str = "", cache_dir: Optional[Path] = None,
             workers: Optional[int] = None) -> Report:
    """Rewrites every built page in ``dist`` to use responsive images."""
    report = Report()
    if Image is None:
        report.skipped.append("Pillow is not installed")
        return report
    pages = sorted(dist.rglob("*.html"))
    probes: Dict[Path, Optional[Tuple[int, int]]] = {}
    jobs: Dict[str, Tuple[str, str, int, Optional[str]]] = {}
    planned: Dict[Path, List[int]] = {}
    digests: Dict[Path, str] = {}

    def digest(path: Path) -> str:
        if path not in digests:
            digests[path] = hashlib.sha1(path.read_bytes()).hexdigest()
        return digests[path]

    def plan(path: Path) -> Optional[List[int]]:
        """Which WebP widths an image gets, or None when it stays as it is."""
        if path in planned:
            return planned[path]
        size = probes.setdefault(path, _probe(path))
        if size is None or path.stat().st_size < SMALL_BYTES:
            planned[path] = None
            return None
        widths = sorted({min(width, size[0]) for width in WIDTHS})
        for width in widths:
            target = path.with_name(_variant_name(path, width))
            cache = str(cache_dir / ("%s-%d.webp" % (digest(path), width))) if cache_dir else None
            jobs[str(target)] = (str(path), str(target), width, cache)
        planned[path] = widths
        report.images += 1
        report.bytes_before += path.stat().st_size
        return widths

    rewrites: Dict[Path, str] = {}
    for page in pages:
        html = page.read_text(encoding="utf-8")
        seen_content_image = False

        def rewrite(match: "re.Match[str]") -> str:
            nonlocal seen_content_image
            tag = match.group(0)
            src = _attr(tag, "src")
            if not src or src.startswith(("http:", "https:", "data:", "//")) or not RASTER.search(src):
                return tag
            if "minecraft-items" in src:
                return tag
            file = (dist / src.lstrip("/")) if src.startswith("/") else (page.parent / src)
            file = Path(os.path.normpath(file))
            if not file.is_file():
                return tag
            size = probes.setdefault(file, _probe(file))
            if size is None:
                return tag
            classes = set((_attr(tag, "class") or "").split())
            widths = plan(file)
            if widths:
                base = src[: len(src) - len(file.name)]
                srcset = ", ".join("%s%s %dw" % (base, _variant_name(file, width), width)
                                   for width in widths)
                tag = _set_attr(tag, "src", base + _variant_name(file, widths[-1]))
                tag = _set_attr(tag, "srcset", srcset)
                sizes = next((SIZES[name] for name in classes if name in SIZES), None)
                if sizes is None:
                    # Screenshots are drawn at width:auto and never past their natural
                    # size, so the slot is capped at that size too. A 960px slot for a
                    # 600px picture would tell the browser to draw it 960px wide.
                    slot = min(size[0], 960)
                    sizes = "(max-width: %dpx) 100vw, %dpx" % (slot, slot)
                tag = _set_attr(tag, "sizes", sizes)
                report.tags += 1
            if _attr(tag, "width") is None:
                tag = _set_attr(tag, "width", str(size[0]))
                tag = _set_attr(tag, "height", str(size[1]))
            tag = _set_attr(tag, "decoding", "async")
            is_chrome = bool(classes & EAGER_CLASSES) or "assets/" in src
            if _attr(tag, "loading") is None:
                if is_chrome:
                    pass
                elif not seen_content_image:
                    # The first picture on a page is almost always on the first screen.
                    tag = _set_attr(tag, "fetchpriority", "high")
                else:
                    tag = _set_attr(tag, "loading", "lazy")
            if not is_chrome and "blur" not in classes:
                seen_content_image = True
            return tag

        rewritten = IMG_TAG.sub(rewrite, html)
        rewritten = _share_image(rewritten, dist, site_url, jobs, cache_dir, digest, probes)
        if rewritten != html:
            rewrites[page] = rewritten

    if jobs:
        pending = list(jobs.values())
        count = workers if workers is not None else max(1, min(8, (os.cpu_count() or 2)))
        if count > 1 and len(pending) > 4:
            with ProcessPoolExecutor(max_workers=count) as pool:
                list(pool.map(_encode, pending, chunksize=2))
        else:
            for job in pending:
                _encode(job)
        report.variants = sum(1 for job in pending if job[1].endswith(".webp"))

    for page, html in rewrites.items():
        page.write_text(html, encoding="utf-8")

    for path, widths in planned.items():
        if widths:
            largest = path.with_name(_variant_name(path, widths[-1]))
            if largest.exists():
                report.bytes_after += largest.stat().st_size
    return report


def _share_image(html: str, dist: Path, site_url: str, jobs, cache_dir, digest, probes) -> str:
    """Points og:image and twitter:image at a 1200-pixel JPEG copy of a large local image."""
    if not site_url:
        return html
    root = site_url.rstrip("/") + "/"

    def replace(match: "re.Match[str]") -> str:
        url = match.group(2)
        if not url.startswith(root) or not RASTER.search(url):
            return match.group(0)
        file = Path(os.path.normpath(dist / url[len(root):]))
        if not file.is_file() or file.stat().st_size < SMALL_BYTES:
            return match.group(0)
        if probes.setdefault(file, _probe(file)) is None:
            return match.group(0)
        target = file.with_name(file.stem + ".share.jpg")
        cache = str(cache_dir / ("%s-share.jpg" % digest(file))) if cache_dir else None
        jobs[str(target)] = (str(file), str(target), SHARE_WIDTH, cache)
        return match.group(1) + url[: len(url) - len(file.name)] + target.name + match.group(3)

    return re.sub(
        r'(<meta (?:property="og:image"|name="twitter:image") content=")([^"]+)(")',
        replace, html,
    )
