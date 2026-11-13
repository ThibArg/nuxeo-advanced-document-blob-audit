"""Shared helpers for the blob audit test file generator.

Every generator is deterministic: running it twice produces byte-identical files, except the
LibreOffice conversions (.odt, .doc, .rtf, .ppt), which embed their own metadata.
"""
import hashlib
import io
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(os.environ.get("BLOBAUDIT_OUT", Path(__file__).resolve().parent.parent))

SOFFICE_CANDIDATES = [
    "/Applications/LibreOffice.app/Contents/MacOS/soffice",
    shutil.which("soffice") or "",
    shutil.which("libreoffice") or "",
]


def scenario_dir(name):
    path = ROOT / name
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def write_text(path, text, encoding="utf-8", newline="\n", bom=False):
    data = text.replace("\n", newline).encode(encoding)
    if bom:
        data = b"\xef\xbb\xbf" + data
    return write_bytes(path, data)


def neutral_properties(core_properties):
    """Replaces the author names the library templates ship with."""
    core_properties.author = "Blob audit test generator"
    core_properties.last_modified_by = "Blob audit test generator"
    core_properties.comments = ""


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------------------------
# Images: four small, clearly different pictures. Same call, same bytes.
# ---------------------------------------------------------------------------------------------

IMAGE_SPECS = {
    "A": ((30, 90, 200), "IMAGE A - blue"),
    "B": ((200, 40, 40), "IMAGE B - red"),
    "C": ((40, 150, 60), "IMAGE C - green"),
    "D": ((230, 140, 20), "IMAGE D - orange"),
}


def make_image(letter, fmt="PNG", size=(320, 200)):
    """Returns the bytes of a plain colored picture with a label, PNG or JPEG."""
    color, label = IMAGE_SPECS[letter]
    img = Image.new("RGB", size, color)
    draw = ImageDraw.Draw(img)
    draw.rectangle([8, 8, size[0] - 9, size[1] - 9], outline=(255, 255, 255), width=4)
    font = ImageFont.load_default()
    draw.text((24, size[1] // 2 - 6), label, fill=(255, 255, 255), font=font)
    buf = io.BytesIO()
    if fmt.upper() == "JPEG":
        img.save(buf, format="JPEG", quality=85, optimize=False)
    else:
        img.save(buf, format="PNG", optimize=False)
    return buf.getvalue()


def image_stream(letter, fmt="PNG"):
    stream = io.BytesIO(make_image(letter, fmt))
    stream.seek(0)
    return stream


def image_file(letter, fmt="PNG"):
    """Writes the picture to a temporary file and returns its path (for libraries wanting a path)."""
    suffix = ".jpg" if fmt.upper() == "JPEG" else ".png"
    tmp = Path(tempfile.gettempdir()) / f"blobaudit-image-{letter}{suffix}"
    tmp.write_bytes(make_image(letter, fmt))
    return str(tmp)


# ---------------------------------------------------------------------------------------------
# LibreOffice conversions (.odt, .doc, .rtf, .ppt)
# ---------------------------------------------------------------------------------------------

def soffice():
    for candidate in SOFFICE_CANDIDATES:
        if candidate and Path(candidate).exists():
            return candidate
    return None


def convert(source, target_ext, target_path):
    """Converts a file with LibreOffice headless, in a private profile, and moves the result."""
    binary = soffice()
    if binary is None:
        print(f"  !! LibreOffice not found: cannot produce {target_path}")
        return None
    source = Path(source)
    target_path = Path(target_path)
    with tempfile.TemporaryDirectory() as outdir, tempfile.TemporaryDirectory() as profile:
        cmd = [
            binary,
            f"-env:UserInstallation=file://{profile}",
            "--headless",
            "--norestore",
            "--convert-to",
            target_ext,
            "--outdir",
            outdir,
            str(source),
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
        produced = Path(outdir) / (source.stem + "." + target_ext.split(":")[0])
        if not produced.exists():
            print(f"  !! LibreOffice produced nothing for {target_path}")
            return None
        target_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(produced), target_path)
    return target_path
