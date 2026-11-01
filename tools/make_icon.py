"""生成 WordDrill 启动图标（PNG mipmap，含圆形版本）。

设计：品牌蓝渐变圆角方块 + 两张叠放的白色练习卡 + 橙色对勾徽标。
坐标系沿用 Android 矢量图的 108 x 108，缩放后输出各密度。
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
SHOT = ROOT / "screenshots"

UNIT = 108.0
SUPER = 8  # 超采样倍数

BRAND_TOP = (74, 99, 228)     # #4A63E4
BRAND_BOTTOM = (42, 58, 150)  # #2A3A96
CARD_BACK = (255, 255, 255, 115)
CARD_FRONT = (255, 255, 255, 255)
LINE = (63, 91, 217)          # #3F5BD9
ACCENT = (242, 160, 61)       # #F2A03D


def rounded_rect_mask(size: int, box, radius: float) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle(box, radius=radius, fill=255)
    return mask


def capsule(draw: ImageDraw.ImageDraw, p1, p2, width, fill):
    draw.line([p1, p2], fill=fill, width=int(round(width)))
    r = width / 2
    for x, y in (p1, p2):
        draw.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def render(size: int, rounded: bool, circle: bool) -> Image.Image:
    s = size * SUPER
    k = s / UNIT

    def pt(x, y):
        return (x * k, y * k)

    # 背景：对角渐变，角上按圆角/圆形裁切
    grad = Image.new("RGB", (s, s))
    gd = ImageDraw.Draw(grad)
    for i in range(s * 2):
        t = i / (s * 2 - 1)
        color = tuple(round(a + (b - a) * t) for a, b in zip(BRAND_TOP, BRAND_BOTTOM))
        gd.line([(i, 0), (0, i)], fill=color)
    base = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    if circle:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    elif rounded:
        mask = rounded_rect_mask(s, [0, 0, s - 1, s - 1], 24 * k)
    else:
        mask = Image.new("L", (s, s), 255)
    base.paste(grad, (0, 0), mask)

    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # 后面那张卡片（半透明白）
    d.rounded_rectangle([34 * k, 33 * k, 72 * k, 62 * k], radius=8 * k, fill=CARD_BACK)
    # 前面那张卡片
    d.rounded_rectangle([30 * k, 40 * k, 78 * k, 70 * k], radius=8 * k, fill=CARD_FRONT)
    # 卡片上的两条文字线
    capsule(d, pt(38, 51), pt(58, 51), 3.6 * k, LINE)
    capsule(d, pt(38, 59), pt(52, 59), 3.6 * k, LINE)
    # 橙色对勾徽标
    d.ellipse([58 * k, 54 * k, 76 * k, 72 * k], fill=ACCENT)
    capsule(d, pt(61.5, 63), pt(64.6, 66.1), 3.2 * k, CARD_FRONT)
    capsule(d, pt(64.6, 66.1), pt(70.4, 60.2), 3.2 * k, CARD_FRONT)

    # 整组稍微倾斜，读起来像一张抽认卡
    layer = layer.rotate(-8, resample=Image.BICUBIC, center=(s / 2, s / 2))

    base.alpha_composite(layer)
    return base.resize((size, size), Image.LANCZOS)


def main() -> None:
    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for name, px in densities.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        render(px, rounded=True, circle=False).save(folder / "ic_launcher.png")
        render(px, rounded=False, circle=True).save(folder / "ic_launcher_round.png")

    SHOT.mkdir(parents=True, exist_ok=True)
    render(512, rounded=True, circle=False).save(SHOT / "icon.png")
    print("icons written")


if __name__ == "__main__":
    main()
