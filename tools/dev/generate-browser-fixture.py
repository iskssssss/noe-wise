"""生成自有浏览器图片验收页；随机答案只出现在PNG像素和服务目录外的安全预期文件。"""
from pathlib import Path
import argparse
import json
import secrets
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output-dir", required=True)
args = parser.parse_args()
root = Path(args.output_dir).resolve()
page = root / "page"
page.mkdir(parents=True, exist_ok=True)
code = f"{secrets.randbelow(1_000_000):06d}"
# 纹理使真实截图显著超过8KiB，数字区域保持清楚可读。
noise = secrets.token_bytes(1000 * 600 * 3)
pixels = bytes(210 + value % 30 for value in noise)
image = Image.frombytes("RGB", (1000, 600), pixels)
draw = ImageDraw.Draw(image)
draw.rounded_rectangle((145, 145, 855, 455), radius=25, fill="white", outline="#2350aa", width=9)
font_path = Path("C:/Windows/Fonts/consolab.ttf")
font = ImageFont.truetype(str(font_path), 150)
bounds = draw.textbbox((0, 0), code, font=font)
draw.text(((1000 - bounds[2]) / 2, 190), code, font=font, fill="black")
image.save(page / "challenge.png")
(root / "expected.internal.json").write_text(json.dumps({"code": code, "png_bytes": (page / "challenge.png").stat().st_size}), encoding="utf-8")
(page / "browser-fixture.html").write_text("""<!doctype html><html lang="zh"><meta charset="utf-8"><title>自有浏览器验收页</title>
<style>body{font:24px system-ui;margin:24px;background:#f4f6fa}button{font-size:24px;padding:15px 30px}img{display:block;max-width:100%;margin-top:20px;border:2px solid #224}</style>
<h1>自有图片挑战</h1><p>点击按钮后查看图片中的六位数字。图片像素是唯一的答案来源。</p>
<button id="reveal" type="button">显示图片</button><div id="result"></div>
<script>document.getElementById('reveal').onclick=()=>{if(document.querySelector('img'))return;const image=document.createElement('img');image.src='/challenge.png';image.alt='待识别的图片';document.getElementById('result').appendChild(image);};</script></html>""", encoding="utf-8")
print(json.dumps({"status": "prepared", "page_directory": str(page), "png_bytes": (page / "challenge.png").stat().st_size}))
